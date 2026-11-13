package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64

internal class SignedUpdateFixture {
    val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val verifier = UpdateManifestVerifier(Base64.getEncoder().encodeToString(key.public.encoded))
    val request = UpdateRequest("com.mercury.yktwx", 9, 35)
    fun json(version: Int = 10, revision: Long = 1, channel: String = "stable") = jsonObjectOf("schemaVersion" to (1), "channel" to (channel), "packageName" to (request.packageName), "versionCode" to (version), "versionName" to ("1.5.0"), "minSdk" to (34), "publishedAt" to ("2026-11-01T00:00:00Z"), "expiresAt" to ("2099-01-01T00:00:00Z"), "changelog" to ("## 更新\n\n- **修复**问题"), "maxForcedVersionCode" to (0), "policyRevision" to (revision), "sha256" to ("a".repeat(64)), "size" to (1024), "releaseUrl" to ("https://github.com/${AppUpdateApi.REPOSITORY}/releases/tag/10"), "urls" to (jsonArrayOf((1..5).map { "https://cdn$it.example.com/app.apk" } + "https://github.com/app.apk")))
    fun sign(json: JsonObject): String {
        val bytes = json.toString().toByteArray()
        val signer = Signature.getInstance("Ed25519").apply { initSign(key.private); update(bytes) }
        return jsonObjectOf("algorithm" to ("Ed25519"), "payload" to (Base64.getEncoder().encodeToString(bytes)), "signature" to (Base64.getEncoder().encodeToString(signer.sign()))).toString()
    }
    fun pair(json: JsonObject): Pair<String, String> {
        val manifest = sign(json)
        val hash = sha256(manifest.toByteArray())
        val pointer = jsonObjectOf("schemaVersion" to (1), "channel" to (json.stringValue("channel")), "policyRevision" to (json.longValue("policyRevision")), "manifestPath" to ("manifests/${json.longValue("policyRevision")}-${hash}.json"), "manifestSha256" to (hash), "expiresAt" to (json.stringValue("expiresAt")))
        return sign(pointer) to manifest
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateApiTest {
    private val fixture = SignedUpdateFixture()
    private fun store(): UpdateCheckStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("signed-update-api-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        resetUpdateFixture(prefs)
        return updateStoreFixture(prefs, "package:stable:9", "package", 9, verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
    }
    private open class FakeResponse(url: URL, private val body: String, private val status: Int = 200) : HttpFixture(url) {
        var disconnected = false
        override fun statusCode() = status
        override fun bodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun close() { disconnected = true }

    }
    private fun api(responses: (URL) -> Pair<String, Int>): AppUpdateApi = appUpdateApiFixture(fixtureUpdateHttp { url ->
        val (body, status) = responses(url); FakeResponse(url, body, status)
    }, fixture.verifier, now = { Instant.parse("2026-11-10T00:00:00Z") })

    private suspend fun withDelayedThirdSource(first: Pair<String, String>, third: Pair<String, String>,
        thirdHost: String, firstPointerDelayMillis: Long = 0, thirdPointerDelayMillis: Long = 100,
        sourceTimeoutMillis: Long = 2_500, verify: suspend (AppUpdateApi) -> Unit) {
        val fastManifestsRead = java.util.concurrent.CountDownLatch(2)
        val releaseOtherSources = java.util.concurrent.CountDownLatch(1)
        val http = fixtureUpdateHttp { url ->
            val fast = url.host in listOf("cdn.jsdelivr.net", "fastly.jsdelivr.net")
            val delayed = url.host == thirdHost
            val pointer = url.path.endsWith("latest.json")
            val pair = if (delayed) third else first
            object : FakeResponse(url, if (pointer) pair.first else pair.second) {
                override fun statusCode(): Int {
                    if (delayed && pointer) {
                        check(fastManifestsRead.await(2, java.util.concurrent.TimeUnit.SECONDS))
                        Thread.sleep(thirdPointerDelayMillis)
                    } else if (fast && pointer) {
                        Thread.sleep(firstPointerDelayMillis)
                    } else if (!fast && !delayed) {
                        releaseOtherSources.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        return 503
                    }
                    return super.statusCode()
                }
                override fun bodyStream() = super.bodyStream().also {
                    if (fast && !pointer) fastManifestsRead.countDown()
                }
            }
        }
        try { verify(appUpdateApiFixture(http, fixture.verifier, sourceTimeoutMillis = sourceTimeoutMillis)) }
        finally { releaseOtherSources.countDown() }
    }

    @Test fun configHasSevenHttpsSourcesAndNoAccountOrDeviceParameters() {
        val sources = AppUpdateApi.sources("stable", "latest.json")
        assertEquals(7, sources.size)
        assertEquals("api.github.com", URL(sources.first()).host)
        sources.forEach { assertEquals("https", URL(it).protocol); assertFalse(it.contains("versionCode=")) }
        assertTrue(AppUpdateApi.cacheBust(sources.first()).contains("&t="))
    }

    @Test fun pythonPublisherOutputResolvesInAndroidAndSurvivesVerifiedCacheRestoration() = runBlocking {
        fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/app-updates/publisher/$name"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val publicKey = resource("public-key.txt").trim()
        val pointer = resource("latest.json")
        val manifest = resource("manifest.json")
        val verifier = UpdateManifestVerifier(publicKey)
        val expected = verifier.manifest(manifest, "beta").first
        // Time is bound to the immutable fixture, so its 90-day expiry cannot age this test out.
        val instant = checkNotNull(expected.publishedAt).plusSeconds(60)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("python-publisher-interoperability", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fun restored() = updateStoreFixture(prefs, "${expected.packageName}:beta:9", expected.packageName, 9,
            now = { instant }, verifyCached = { body, channel -> verifier.manifest(body, channel).first })
        val api = appUpdateApiFixture(fixtureUpdateHttp { url ->
            when {
                url.path.contains("/stable/") -> FakeResponse(url, "", 404)
                url.path.endsWith("latest.json") -> FakeResponse(url, pointer)
                else -> FakeResponse(url, manifest)
            }
        }, verifier) { instant }
        val result = api.check(UpdateRequest(expected.packageName, 9, 35, "beta"), restored())
        assertEquals(UpdateCheckReason.UpdateAvailable, result.reason)
        assertEquals(100001, result.release!!.versionCode)
        assertEquals("1.5.0-beta.100001", result.release!!.versionName)
        assertEquals("beta", result.release!!.channel)
        assertEquals(expected.sha256, result.release!!.sha256)
        assertEquals(6, result.release!!.urls.size)
        assertEquals(result.release, restored().cachedRelease())
    }
    @Test fun unpublishedChannelsGiveAnExplicitResultWhileUnavailableServicesRemainFailures() = runBlocking {
        assertEquals(UpdateCheckReason.NoPublishedRelease,
            api { "" to 404 }.check(fixture.request, store()).reason)
        assertEquals(UpdateCheckReason.NoPublishedRelease,
            api { "" to 404 }.check(fixture.request.copy(channel = "beta"), store()).reason)
        assertThrows(UpdateSourceUnavailable::class.java) { runBlocking {
            api { "" to 503 }.check(fixture.request, store())
        } }
        val pointer = fixture.pair(fixture.json()).first
        assertThrows(UpdateSourceUnavailable::class.java) { runBlocking {
            api { url -> (if (url.path.endsWith("latest.json")) pointer to 200 else "" to 404) }
                .check(fixture.request, store())
        } }
        Unit
    }
    @Test fun unpublishedAuthorityDoesNotWaitForBlockedMirrors() = runBlocking {
        val releaseMirrors = java.util.concurrent.CountDownLatch(1)
        val http = fixtureUpdateHttp { url ->
            if (url.host == "api.github.com") FakeResponse(url, "", 404)
            else object : FakeResponse(url, "", 404) {
                override fun statusCode(): Int {
                    releaseMirrors.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return super.statusCode()
                }
            }
        }
        try {
            val result = withTimeout(2_000) {
                appUpdateApiFixture(http, fixture.verifier, sourceTimeoutMillis = 2_000)
                    .check(fixture.request, store())
            }
            assertEquals(UpdateCheckReason.NoPublishedRelease, result.reason)
            assertEquals(1L, releaseMirrors.count)
        } finally { releaseMirrors.countDown() }
    }
    @Test fun verifiedMirrorsReturnWithoutWaitingForBlockedAuthority() = runBlocking {
        val releaseAuthority = java.util.concurrent.CountDownLatch(1)
        val (pointer, manifest) = fixture.pair(fixture.json())
        val http = fixtureUpdateHttp { url ->
            val body = if (url.path.endsWith("latest.json")) pointer else manifest
            if (url.host != "api.github.com") FakeResponse(url, body)
            else object : FakeResponse(url, body) {
                override fun statusCode(): Int {
                    releaseAuthority.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return super.statusCode()
                }
            }
        }
        try {
            val result = withTimeout(1_000) {
                appUpdateApiFixture(http, fixture.verifier, sourceTimeoutMillis = 2_500)
                    .check(fixture.request, store())
            }
            assertEquals(UpdateCheckReason.UpdateAvailable, result.reason)
            assertEquals(10, result.release!!.versionCode)
            assertEquals(1L, releaseAuthority.count)
        } finally { releaseAuthority.countDown() }
    }
    @Test fun oneVerifiedMirrorIsEnoughWhenEveryOtherSourceIsBlocked() = runBlocking {
        val releaseSources = java.util.concurrent.CountDownLatch(1)
        val (pointer, manifest) = fixture.pair(fixture.json())
        val http = fixtureUpdateHttp { url ->
            val body = if (url.path.endsWith("latest.json")) pointer else manifest
            if (url.host == "cdn.jsdelivr.net") FakeResponse(url, body)
            else object : FakeResponse(url, body) {
                override fun statusCode(): Int {
                    releaseSources.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return super.statusCode()
                }
            }
        }
        try {
            val result = withTimeout(1_000) {
                appUpdateApiFixture(http, fixture.verifier).check(fixture.request, store())
            }
            assertEquals(UpdateCheckReason.UpdateAvailable, result.reason)
            assertEquals(10, result.release!!.versionCode)
            assertEquals(1L, releaseSources.count)
        } finally { releaseSources.countDown() }
    }
    @Test fun authorityArrivingAfterTwoMirrorsStillWinsWithinConvergenceWindow() = runBlocking {
        val mirrors = fixture.pair(fixture.json(version = 11, revision = 2))
        val authority = fixture.pair(fixture.json())
        withDelayedThirdSource(mirrors, authority, "api.github.com") { api ->
            val result = withTimeout(2_000) { api.check(fixture.request, store()) }
            assertEquals(10, result.release!!.versionCode)
            assertEquals(1L, result.release.policyRevision)
        }
    }
    @Test fun convergenceWindowContinuesBeyondInitialSourceWaitDeadline() = runBlocking {
        val mirrors = fixture.pair(fixture.json(version = 11, revision = 2))
        val authority = fixture.pair(fixture.json())
        withDelayedThirdSource(mirrors, authority, "api.github.com", firstPointerDelayMillis = 400,
            thirdPointerDelayMillis = 200, sourceTimeoutMillis = 550) { api ->
            val result = withTimeout(2_000) { api.check(fixture.request, store()) }
            assertEquals(10, result.release!!.versionCode)
        }
    }
    @Test fun newerMirrorArrivingAfterTwoMirrorsIsUsedWithinConvergenceWindow() = runBlocking {
        val first = fixture.pair(fixture.json())
        val newer = fixture.pair(fixture.json(version = 11, revision = 2))
        withDelayedThirdSource(first, newer, "gcore.jsdelivr.net") { api ->
            val result = withTimeout(2_000) { api.check(fixture.request, store()) }
            assertEquals(11, result.release!!.versionCode)
            assertEquals(2L, result.release.policyRevision)
        }
    }
    @Test fun conflictingThirdMirrorWithinConvergenceWindowIsRejected() = runBlocking {
        val first = fixture.pair(fixture.json())
        val conflict = fixture.pair(fixture.json(version = 11))
        withDelayedThirdSource(first, conflict, "gcore.jsdelivr.net") { api ->
            val failure = withTimeout(2_000) {
                runCatching { api.check(fixture.request, store()) }.exceptionOrNull()
            }
            assertNotNull(failure)
            assertEquals("更新清单存在冲突", failure!!.message)
        }
    }
    @Test fun stalledSourcesRespectTheDeadlineWithoutClaimingNoPublishedRelease() = runBlocking {
        val releaseSources = java.util.concurrent.CountDownLatch(1)
        val http = fixtureUpdateHttp { url -> object : FakeResponse(url, "", 503) {
            override fun statusCode(): Int {
                releaseSources.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return super.statusCode()
            }
        } }
        try {
            val failure = withTimeout(2_000) {
                runCatching {
                    appUpdateApiFixture(http, fixture.verifier, sourceTimeoutMillis = 500)
                        .check(fixture.request, store())
                }.exceptionOrNull()
            }
            assertTrue(failure is UpdateSourceUnavailable)
            assertFalse((failure as UpdateSourceUnavailable).notPublished)
            assertEquals(1L, releaseSources.count)
        } finally { releaseSources.countDown() }
    }
    @Test fun betaFallsBackWhenOneChannelIsUnavailableButRejectsInvalidSignedMetadata() = runBlocking {
        val stable = fixture.pair(fixture.json(version = 11))
        val beta = fixture.pair(fixture.json(version = 12, channel = "beta"))
        for (missing in listOf("stable", "beta")) {
            val result = api { url ->
                val isBeta = url.path.contains("/beta/")
                val pair = if (isBeta) beta else stable
                (if (url.path.endsWith("latest.json")) pair.first else pair.second) to
                    (if (isBeta == (missing == "beta")) 404 else 200)
            }.check(fixture.request.copy(channel = "beta"), store())
            assertEquals(if (missing == "beta") 11 else 12, result.release!!.versionCode)
        }
        assertThrows(Exception::class.java) { runBlocking {
            api { url ->
                if (url.path.contains("/beta/")) "{}" to 200
                else (if (url.path.endsWith("latest.json")) stable.first else stable.second) to 200
            }.check(fixture.request.copy(channel = "beta"), store())
        } }
        Unit
    }

    @Test fun channelSwitchClearsOptionalBetaCacheButRetainsConfirmedForcedPolicy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("channel-policy-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fun restored() = updateStoreFixture(prefs, "package:beta:9", "package", 9) { body, channel ->
            fixture.verifier.manifest(body, channel).first
        }
        val store = restored()
        val optional = fixture.verifier.manifest(fixture.sign(fixture.json(channel = "beta")), "beta")
        store.acceptPolicy("beta", optional.first, optional.second)
        assertNotNull(store.cachedRelease())
        store.repository.update { it.copy(updateChannel = "stable") }
        assertNull(store.cachedRelease())
        assertEquals("stable", restored().channel)
        val forced = fixture.verifier.manifest(fixture.sign(fixture.json(revision = 2, channel = "beta")
            .withField("maxForcedVersionCode", 9)), "beta")
        store.acceptPolicy("beta", forced.first, forced.second)
        assertTrue(store.cachedRelease()!!.required(9))
        assertEquals(9, restored().forcedBoundary())
    }
    @Test fun signedSchemaRejectsInvalidNumbersChannelsHashesAndCdns() {
        val valid = fixture.sign(fixture.json())
        assertEquals(10, fixture.verifier.manifest(valid, "stable").first.versionCode)
        for (invalid in listOf(fixture.json().withField("versionCode", "10"), fixture.json().withField("versionCode", 10.5),
            fixture.json().withField("maxForcedVersionCode", 10), fixture.json().withField("sha256", "A".repeat(64)),
            fixture.json().withField("urls", jsonArrayOf(listOf("https://cdn.example.com/app.apk"))),
            fixture.json().withField("releaseUrl", "intent://install"))) {
            assertThrows(Exception::class.java) { fixture.verifier.manifest(fixture.sign(invalid), "stable") }
        }
        assertThrows(Exception::class.java) { fixture.verifier.manifest(valid, "beta") }
        val altered = parseObject(valid).withField("payload", Base64.getEncoder().encodeToString(fixture.json(11).toString().toByteArray()))
        assertThrows(Exception::class.java) { fixture.verifier.manifest(altered.toString(), "stable") }
    }
    @Test fun signedManifestRetainsCertificateFingerprintAndRejectsMalformedValues() {
        val signer = sha256(byteArrayOf(1))
        val signed = fixture.sign(fixture.json().withField("signerSha256", signer))
        assertEquals(signer, fixture.verifier.manifest(signed, "stable").first.signerSha256)
        assertNull(fixture.verifier.manifest(fixture.sign(fixture.json()), "stable").first.signerSha256)
        for (invalid in listOf("", "A".repeat(64), "not-a-certificate", 123)) {
            assertThrows(Exception::class.java) {
                fixture.verifier.manifest(fixture.sign(fixture.json().withField("signerSha256", invalid)), "stable")
            }
        }
    }
    @Test fun localVersionAndCompatibilityDecideResult() = runBlocking {
        for ((json, reason) in listOf(fixture.json() to UpdateCheckReason.UpdateAvailable,
            fixture.json(version = 9) to UpdateCheckReason.CurrentOrNewer,
            fixture.json().withField("minSdk", 99) to UpdateCheckReason.NoCompatibleRelease)) {
            val (pointer, manifest) = fixture.pair(json)
            val result = api { url -> (if (url.path.endsWith("latest.json")) pointer else manifest) to 200 }.check(fixture.request, store())
            assertEquals(reason, result.reason)
        }
    }
    @Test fun authorityWinsOverNewerMirrorAndMirrorsRecoverAuthorityFailure() = runBlocking {
        val authoritative = fixture.pair(fixture.json(revision = 1))
        val mirror = fixture.pair(fixture.json(version = 11, revision = 2))
        val metadata = api { url ->
            if (url.host !in listOf("api.github.com", "cdn.jsdelivr.net")) return@api "" to 503
            val pair = if (url.host == "api.github.com") authoritative else mirror
            (if (url.path.endsWith("latest.json")) pair.first else pair.second) to 200
        }
        assertEquals(10, metadata.check(fixture.request, store()).release!!.versionCode)
        val fallback = api { url ->
            (if (url.path.endsWith("latest.json")) mirror.first else mirror.second) to
                (if (url.host == "api.github.com") 503 else 200)
        }
        assertEquals(11, fallback.check(fixture.request, store()).release!!.versionCode)
    }
    @Test fun sameRevisionConflictsAndExpiredSignedMetadataFail() {
        val one = fixture.pair(fixture.json())
        val two = fixture.pair(fixture.json(version = 11))
        assertThrows(Exception::class.java) { runBlocking {
            api { url ->
                if (url.host !in listOf("api.github.com", "cdn.jsdelivr.net")) return@api "" to 503
                val pair = if (url.host == "api.github.com") one else two
                (if (url.path.endsWith("latest.json")) pair.first else pair.second) to 200
            }.check(fixture.request, store())
        } }
        val expired = fixture.pair(fixture.json().withField("expiresAt", "2020-01-01T00:00:00Z"))
        assertThrows(Exception::class.java) { runBlocking {
            api { url -> (if (url.path.endsWith("latest.json")) expired.first else expired.second) to 200 }
                .check(fixture.request, store())
        } }
    }
    @Test fun policyRejectsRollbackConflictsAndForcedBoundaryReset() = runBlocking {
        val store = store()
        val first = fixture.verifier.manifest(fixture.sign(fixture.json(revision = 2).withField("maxForcedVersionCode", 8)), "stable")
        store.acceptPolicy("stable", first.first, first.second)
        val lower = fixture.verifier.manifest(fixture.sign(fixture.json(revision = 1)), "stable")
        assertThrows(Exception::class.java) { runBlocking { store.acceptPolicy("stable", lower.first, lower.second) } }
        assertThrows(Exception::class.java) { runBlocking { store.acceptPolicy("stable", first.first, "b".repeat(64)) } }
        val reset = fixture.verifier.manifest(fixture.sign(fixture.json(version = 11, revision = 3)), "stable")
        assertThrows(Exception::class.java) { runBlocking { store.acceptPolicy("stable", reset.first, reset.second) } }
        Unit
    }
    @Test fun timestampParsingAndDangerousUrlsAreBounded() {
        val invalidTime = fixture.verifier.manifest(fixture.sign(fixture.json().withField("publishedAt", "not a date")), "stable").first
        assertNull(invalidTime.publishedAt)
        listOf("http://example.com/a", "https://user@example.com/a", "file:///a", "https://example.com:8080/a")
            .forEach { url -> assertThrows(Exception::class.java) { httpsUri(url) } }
    }
    @Test fun forcedSignedPolicySurvivesRestartButActualNewInstalledVersionClearsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("forced-restart-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        fun restored(version: Int) = updateStoreFixture(prefs, "package:stable:$version", "package", version) { body, channel ->
            fixture.verifier.manifest(body, channel).first
        }
        restored(9).acceptPolicy("stable", release, digest)
        assertTrue(restored(9).cachedRelease()!!.required(9))
        assertNull(restored(10).cachedRelease())
    }
    @Test fun expiredOptionalCacheIsHiddenButConfirmedForcedUpdatesRemainBlocked() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("expired-cache-review-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fun restored() = updateStoreFixture(prefs, "package:stable:9", "package", 9,
            now = { Instant.parse("2026-11-11T00:00:00Z") }) { body, channel ->
            fixture.verifier.manifest(body, channel).first
        }
        val optional = fixture.verifier.manifest(fixture.sign(fixture.json()
            .withField("expiresAt", "2026-11-10T00:00:00Z")), "stable")
        restored().acceptPolicy("stable", optional.first, optional.second)
        assertNull(restored().cachedRelease())
        val forced = fixture.verifier.manifest(fixture.sign(fixture.json(revision = 2)
            .withField("maxForcedVersionCode", 9).withField("expiresAt", "2026-11-10T00:00:00Z")), "stable")
        restored().acceptPolicy("stable", forced.first, forced.second)
        assertTrue(restored().cachedRelease()!!.required(9))
    }
    @Test fun transportClosesConnectionsLimitsSizeAndAddsCacheControl() = runBlocking {
        val connection = FakeResponse(URL("https://example.com/update"), "{}")
        assertEquals("{}", fixtureUpdateHttp { connection }.json("https://example.com/update"))
        assertTrue(connection.disconnected)
        assertFalse(testHttpClients.service.followRedirects)
        assertEquals("no-cache, no-store", connection.request.header("Cache-Control"))
        assertNull(connection.request.header("Authorization"))
        assertNull(connection.request.header("Cookie"))
        val oversized = FakeResponse(URL("https://example.com/update"), "x".repeat(1_048_577))
        assertThrows(Exception::class.java) { runBlocking { fixtureUpdateHttp { oversized }.json("https://example.com/update") } }
        assertTrue(oversized.disconnected)
    }
    @Test fun cancellingARequestDisconnectsItsBlockedInputStream() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = java.util.concurrent.CountDownLatch(1)
        val connection = object : HttpFixture(URL("https://example.com/update")) {
            override fun statusCode() = 200
            override fun bodyStream(): java.io.InputStream {
                started.complete(Unit)
                return object : java.io.InputStream() {
                    override fun read(): Int {
                        stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        throw java.io.IOException("Connection closed")
                    }
                }
            }
            override fun close() { stopped.countDown() }

        }
        val task = launch { fixtureUpdateHttp { connection }.json("https://example.com/update") }
        withTimeout(2_000) { started.await(); task.cancelAndJoin() }
        assertTrue(withContext(Dispatchers.IO) { stopped.await(1, java.util.concurrent.TimeUnit.SECONDS) })
    }
    @Test fun metadataTimeoutReturnsEvenWhenThePlatformConnectionCannotBeInterrupted() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseConnection = java.util.concurrent.CountDownLatch(1)
        val connection = object : FakeResponse(URL("https://example.com/update"), "{}") {
            override fun statusCode(): Int {
                started.complete(Unit)
                releaseConnection.await(5, java.util.concurrent.TimeUnit.SECONDS)
                return super.statusCode()
            }
        }
        try {
            val task = async {
                runCatching { fixtureUpdateHttp(metadataTimeoutMillis = 500) { connection }.json("https://example.com/update") }
            }
            withTimeout(2_000) { started.await() }
            val failure = withTimeout(2_000) { task.await() }.exceptionOrNull()
            assertTrue(failure is java.net.SocketTimeoutException)
            assertEquals(1L, releaseConnection.count)
        } finally { releaseConnection.countDown() }
    }
}
