package com.mercury.yktwx.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPreferencesRepositoryTest {
    @Test fun persistedWriteWinsOverDelayedOlderObserverEmission() = runTest {
        val emissions = Channel<AppPreferences>(Channel.UNLIMITED)
        var persisted = AppPreferences(legacyMigrated = true)
        val store = object : DataStore<AppPreferences> {
            override val data = emissions.receiveAsFlow()
            override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences): AppPreferences {
                persisted = transform(persisted)
                return persisted
            }
        }
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        emissions.send(persisted); runCurrent()
        val first = repository.update { it.copy(updateChannel = "beta") }
        val second = repository.update { it.copy(ui = it.ui.copy(preventScreenshots = false)) }
        emissions.send(first); runCurrent()
        assertEquals(second, repository.snapshot())
        assertEquals(second, (repository.composeState.value as PreferencesLoadState.Ready).preferences)
    }

    @Test fun loadingAndReadFailureNeverExposeAnEmptyPolicyAndCanRetry() = runTest {
        var fail = true
        val good = AppPreferences(legacyMigrated = true, updateChannel = "beta")
        val store = object : DataStore<AppPreferences> {
            override val data = flow { if (fail) throw java.io.IOException("read failed") else emit(good); awaitCancellation() }
            override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences) = transform(good)
        }
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        assertEquals(PreferencesLoadState.Loading, repository.state.value)
        runCurrent()
        val failure = (repository.state.value as PreferencesLoadState.Failed).failure
        assertEquals(PreferencesFailureKind.Storage, failure.kind)
        assertTrue(failure.detail.contains("IOException"))
        assertFalse(failure.detail.contains("read failed"))
        assertNull(repository.readyOrNull())
        val error = assertThrows(PreferencesDataException::class.java) { runBlocking { repository.awaitReady() } }
        assertEquals(failure, error.failure)
        fail = false; repository.retry(); runCurrent()
        assertEquals(good, repository.awaitReady())
    }

    @Test fun serializerRoundTripsAndCorruptionIsNeverDefaultReset() = runTest {
        val fixture = SignedUpdateFixture()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        val data = AppPreferences(legacyMigrated = true, writeRevision = 4, updateChannel = "beta",
            roomSelections = mapOf("owner.selection" to setOf("target")),
            ui = UiPreferences(preventScreenshots = false, themeMode = 99, accentMode = -1,
                floatingBottomBarStyle = 99, navTransitionStyle = 7),
            policies = mapOf("${fixture.request.packageName}:stable" to AcceptedUpdatePolicy(release.policyRevision,
                digest, 9, release.versionCode, release.signedManifest)))
        val output = ByteArrayOutputStream()
        AppPreferencesSerializer.writeTo(data, output)
        val decoded = AppPreferencesSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(data, decoded)
        val restored = appPreferencesRepositoryFixture(MemoryPreferences(decoded), backgroundScope, fixture.verifier::manifest)
        runCurrent()
        assertEquals(data, restored.awaitReady())
        restored.update { it.copy(ui = it.ui.copy(blurTopBar = true)) }
        assertEquals(data.ui.copy(blurTopBar = true), restored.snapshot().ui)
        assertEquals(data.policies, restored.snapshot().policies)
        for (body in listOf("invalid", "[]", "{}", "{} trailing", "{\"schemaVersion\":2}", "{\"writeRevision\":-1}")) {
            assertThrows(androidx.datastore.core.CorruptionException::class.java) { runBlocking {
                AppPreferencesSerializer.readFrom(ByteArrayInputStream(body.toByteArray()))
            } }
        }
    }

    @Test fun malformedRecordsReportSafeFieldSpecificReasons() = runTest {
        val output = ByteArrayOutputStream()
        AppPreferencesSerializer.writeTo(AppPreferences(), output)
        val valid = Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
        val cases = listOf(
            Triple("{\"private-marker\":", PreferencesFailureKind.Format, "有效 JSON"),
            Triple("[]", PreferencesFailureKind.Format, "顶层"),
            Triple(JsonObject(valid - "policies").toString(), PreferencesFailureKind.Record, "policies"),
            Triple(JsonObject(valid + ("schemaVersion" to JsonPrimitive(2))).toString(), PreferencesFailureKind.Record, "schemaVersion=2"),
            Triple(JsonObject(valid + ("writeRevision" to JsonPrimitive(-1))).toString(), PreferencesFailureKind.Record, "writeRevision"),
            Triple(JsonObject(valid + ("updateChannel" to JsonPrimitive("private-marker"))).toString(), PreferencesFailureKind.Record, "updateChannel"),
            Triple(JsonObject(valid + ("ui" to JsonObject(mapOf("floatingBottomBarStyle" to JsonPrimitive("private-marker"))))).toString(),
                PreferencesFailureKind.Format, "ui.floatingBottomBarStyle"),
        )
        for ((body, kind, detail) in cases) {
            val error = assertThrows(androidx.datastore.core.CorruptionException::class.java) { runBlocking {
                AppPreferencesSerializer.readFrom(ByteArrayInputStream(body.toByteArray()))
            } }
            val failure = PreferencesFailure.from(error)
            assertEquals(kind, failure.kind)
            assertTrue(failure.detail, failure.detail.contains(detail))
            assertFalse(failure.detail.contains("private-marker"))
        }
    }

    @Test fun storageFailuresReportTheOperatingSystemReasonWithoutTheExceptionPayload() {
        for ((errno, detail) in listOf(android.system.OsConstants.ENOSPC to "空间不足",
            android.system.OsConstants.EACCES to "访问被拒绝", android.system.OsConstants.EROFS to "只读",
            android.system.OsConstants.EIO to "读写异常")) {
            val error = java.io.IOException("private-marker", android.system.ErrnoException("open", errno))
            val failure = PreferencesFailure.from(error)
            assertEquals(PreferencesFailureKind.Storage, failure.kind)
            assertTrue(failure.detail, failure.detail.contains(detail))
            assertFalse(failure.detail.contains("private-marker"))
        }
    }

    @Test fun concurrentPolicyAcceptanceIsAtomicAndRejectsRollbackAndDigestConflicts() = runTest {
        val fixture = SignedUpdateFixture()
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val updates = UpdateCheckStore(repository, fixture.request.packageName, 9,
            verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
        fun policy(revision: Long, version: Int, forced: Int) = fixture.verifier.manifest(
            fixture.sign(fixture.json(version, revision).withField("maxForcedVersionCode", forced)), "stable")
        val high = policy(3, 12, 9)
        val low = policy(2, 11, 8)
        coroutineScope {
            listOf(high, low).map { (release, digest) -> async { runCatching { updates.acceptPolicy("stable", release, digest) } } }.awaitAll()
        }
        val accepted = repository.snapshot().policies.values.single()
        assertEquals(3L, accepted.revision)
        assertEquals(high.second, accepted.digest)
        assertEquals(9, updates.forcedBoundary())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", low.first, low.second) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", high.first, "b".repeat(64)) } }
        val reset = policy(4, 13, 0)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { updates.acceptPolicy("stable", reset.first, reset.second) } }
        assertEquals(accepted, repository.snapshot().policies.values.single())
    }

    @Test fun withdrawnCacheStateRoundTripsWithoutLosingPolicyOrRevivingOnPartialAcceptance() = runTest {
        val fixture = SignedUpdateFixture()
        val memory = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(memory, backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val updates = UpdateCheckStore(repository, fixture.request.packageName, 9,
            verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json()), "stable")
        updates.acceptPolicy("stable", release, digest)
        val accepted = repository.snapshot().policies.values.single()
        updates.checked(100, true, result = UpdateCheckResult(UpdateCheckReason.NoPublishedRelease))
        assertNull(updates.cachedRelease())
        assertEquals(accepted.copy(cachedAvailable = false), repository.snapshot().policies.values.single())
        val output = ByteArrayOutputStream()
        AppPreferencesSerializer.writeTo(repository.snapshot(), output)
        val decoded = AppPreferencesSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        val restored = appPreferencesRepositoryFixture(MemoryPreferences(decoded), backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val after = UpdateCheckStore(restored, fixture.request.packageName, 9,
            verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
        assertNull(after.cachedRelease())
        after.acceptPolicy("stable", release, digest)
        after.checked(200, false)
        assertNull(after.cachedRelease())
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            after.acceptPolicy("stable", release, "b".repeat(64))
        } }
        after.checked(300, true, result = UpdateCheckResult(UpdateCheckReason.UpdateAvailable, release))
        assertEquals(release, after.cachedRelease())
    }

    @Test fun betaWithdrawalKeepsAvailableStableCacheAndForcedPoliciesSurviveNoRelease() = runTest {
        val fixture = SignedUpdateFixture()
        val repository = appPreferencesRepositoryFixture(MemoryPreferences(AppPreferences(updateChannel = "beta")),
            backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val updates = UpdateCheckStore(repository, fixture.request.packageName, 9,
            verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
        val (stable, stableDigest) = fixture.verifier.manifest(fixture.sign(fixture.json()), "stable")
        val (beta, betaDigest) = fixture.verifier.manifest(fixture.sign(fixture.json(11, channel = "beta")), "beta")
        updates.acceptPolicy("stable", stable, stableDigest)
        updates.acceptPolicy("beta", beta, betaDigest)
        updates.checked(100, true, result = UpdateCheckResult(UpdateCheckReason.UpdateAvailable, stable,
            availableChannels = setOf("stable")))
        assertEquals(stable, updates.cachedRelease())
        val (forced, forcedDigest) = fixture.verifier.manifest(fixture.sign(fixture.json(12, 2, "beta")
            .withField("maxForcedVersionCode", 9)), "beta")
        updates.acceptPolicy("beta", forced, forcedDigest)
        updates.checked(200, true, result = UpdateCheckResult(UpdateCheckReason.NoPublishedRelease))
        assertEquals(9, updates.forcedBoundary())
        assertTrue(updates.cachedRelease()!!.required(9))
    }

    @Test fun partialChannelSuccessPreservesPreviouslyKnownAvailability() = runTest {
        val fixture = SignedUpdateFixture()
        for (unavailable in listOf("stable", "beta")) {
            val repository = appPreferencesRepositoryFixture(MemoryPreferences(AppPreferences(updateChannel = "beta")),
                backgroundScope, fixture.verifier::manifest)
            runCurrent()
            val updates = UpdateCheckStore(repository, fixture.request.packageName, 9,
                verifyCached = { body, channel -> fixture.verifier.manifest(body, channel).first })
            for (channel in listOf("stable", "beta")) {
                val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json(channel = channel)), channel)
                updates.acceptPolicy(channel, release, digest)
            }
            val key = "${fixture.request.packageName}:$unavailable"
            for (previouslyAvailable in listOf(true, false)) {
                repository.update { data -> data.copy(policies = data.policies +
                    (key to data.policies.getValue(key).copy(cachedAvailable = previouslyAvailable))) }
                val previous = repository.snapshot().policies.getValue(key)
                val resolved = setOf("stable", "beta") - unavailable
                updates.checked(100, true, result = UpdateCheckResult(UpdateCheckReason.UpdateAvailable,
                    availableChannels = resolved, resolvedChannels = resolved))
                assertEquals(previous, repository.snapshot().policies.getValue(key))
            }
        }
    }

    @Test fun tamperedPersistedManifestBlocksReadinessWithoutClearingTheLedger() = runTest {
        val fixture = SignedUpdateFixture()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        val envelope = Json.parseToJsonElement(release.signedManifest).jsonObject
        val signature = java.util.Base64.getDecoder().decode(envelope.getValue("signature").jsonPrimitive.content)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        val tampered = JsonObject(envelope + ("signature" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(signature)))).toString()
        val policy = AcceptedUpdatePolicy(release.policyRevision, digest, 9, release.versionCode, tampered)
        val data = AppPreferences(legacyMigrated = true, policies = mapOf("${fixture.request.packageName}:stable" to policy))
        val store = MemoryPreferences(data)
        val repository = appPreferencesRepositoryFixture(store, backgroundScope, fixture.verifier::manifest)
        runCurrent()
        val failure = (repository.state.value as PreferencesLoadState.Failed).failure
        assertEquals(PreferencesFailureKind.Policy, failure.kind)
        assertTrue(failure.detail.contains("正式版"))
        assertTrue(failure.detail.contains("签名验证失败"))
        assertFalse(failure.detail.contains(tampered))
        assertEquals(data, store.values.value)
    }

    @Test fun signedLedgerMismatchesReportTheFieldAndPreserveTheRecord() = runTest {
        val fixture = SignedUpdateFixture()
        val (release, digest) = fixture.verifier.manifest(fixture.sign(fixture.json().withField("maxForcedVersionCode", 9)), "stable")
        val good = AcceptedUpdatePolicy(release.policyRevision, digest, 9, release.versionCode, release.signedManifest)
        for ((policy, field) in listOf(good.copy(digest = "b".repeat(64)) to "digest", good.copy(revision = good.revision + 1) to "revision",
            good.copy(versionCode = good.versionCode + 1) to "versionCode", good.copy(forcedBoundary = 8) to "forcedBoundary")) {
            val data = AppPreferences(legacyMigrated = true, policies = mapOf("${fixture.request.packageName}:stable" to policy))
            val store = MemoryPreferences(data)
            val repository = appPreferencesRepositoryFixture(store, backgroundScope, fixture.verifier::manifest)
            runCurrent()
            val failure = (repository.state.value as PreferencesLoadState.Failed).failure
            assertEquals(PreferencesFailureKind.Policy, failure.kind)
            assertTrue(failure.detail, failure.detail.contains(field))
            assertEquals(data, store.values.value)
            assertNull(repository.readyOrNull())
        }
    }
}
