package com.mercury.yktwx.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class AppUpdateApiTest {
    private val request = UpdateRequest("com.mercury.yktwx", 3, 35)
    private fun response() = JSONObject().put("schemaVersion", 1).put("updateAvailable", true)
        .put("reason", "updateAvailable").put("latest", JSONObject()
            .put("id", "r_fb80282e9ed7f90694a0ec56").put("name", "1.2.0").put("channel", "stable")
            .put("versionCode", 4).put("versionName", "1.2.0")
            .put("publishedAt", "2026-11-01T00:00:00Z").put("syncedAt", "2026-11-01T00:00:00Z")
            .put("minSdk", 34).put("assets", org.json.JSONArray().put(JSONObject()
                .put("id", "a_test").put("name", "CaiXiaoTong-1.2.0-release.apk").put("sizeBytes", 2121535)
                .put("sha256", "a".repeat(64)).put("packageName", request.packageName)
                .put("versionCode", 4).put("versionName", "1.2.0").put("minSdk", 34)
                .put("signerSha256", org.json.JSONArray().put("b".repeat(64)))
                .put("downloadUrl", "https://cxt.303889.xyz/v1/assets/a_test/download")))
            .put("notes", "修复与优化").put("downloadPageUrl", "https://cxt.303889.xyz/download/"))

    @Test fun publicRequestUsesDeviceVersionAndChannelWithoutAccountParameters() {
        val url = AppUpdateApi.checkUrl(request)
        assertEquals("https", url.protocol)
        assertEquals("cxt.303889.xyz", url.host)
        assertEquals("/v1/app/check", url.path)
        assertEquals("packageName=com.mercury.yktwx&versionCode=3&sdkInt=35&channel=stable", url.query)
        assertTrue(AppUpdateApi.checkUrl(request.copy(channel = "preview")).query.contains("channel=preview"))
    }

    @Test fun trustsBackendUpdateDecisionAndPreservesReleaseNotes() {
        val json = response()
        val notes = "- feat(ui): 后端整理的更新说明\n\n原有格式"
        json.getJSONObject("latest").put("versionName", "0.9.0").put("versionCode", 3).put("notes", notes)
        val result = AppUpdateApi.parse(json.toString())
        assertEquals(UpdateCheckReason.UpdateAvailable, result.reason)
        assertEquals(3, result.release!!.versionCode)
        assertEquals("0.9.0", result.release.versionName)
        assertEquals(notes, result.release.notes)
        assertEquals("https://cxt.303889.xyz/download/", result.release.downloadPageUrl)
    }

    @Test fun downloadPageUrlsAreUsedExactlyAsReturnedByTheBackend() {
        for (url in listOf(
            "https://cxt.303889.xyz/download/",
            "https://cxt.303889.xyz/download",
            "https://cxt.303889.xyz/download.html",
            "https://cxt.303889.xyz/download.html#history",
            "https://example.com/download/",
        )) {
            val json = response().apply { getJSONObject("latest").put("downloadPageUrl", url) }
            assertEquals(url, AppUpdateApi.parse(json.toString()).release!!.downloadPageUrl)
        }
    }

    @Test fun currentVersionDoesNotPromptEvenWhenLatestContainsARelease() {
        val json = response().put("updateAvailable", false).put("reason", "currentOrNewer")
        val result = AppUpdateApi.parse(json.toString())
        assertEquals(UpdateCheckReason.CurrentOrNewer, result.reason)
        assertNull(result.release)
    }

    @Test fun distinguishesCurrentVersionMissingReleaseAndIncompatibleDevice() {
        for ((reason, expected) in listOf("currentOrNewer" to UpdateCheckReason.CurrentOrNewer,
            "noPublishedRelease" to UpdateCheckReason.NoPublishedRelease,
            "noCompatibleRelease" to UpdateCheckReason.NoCompatibleRelease)) {
            val body = JSONObject().put("schemaVersion", 1).put("updateAvailable", false).put("reason", reason)
                .put("latest", JSONObject.NULL)
            val result = AppUpdateApi.parse(body.toString())
            assertEquals(expected, result.reason)
            assertNull(result.release)
        }
    }

    @Test fun unreadableResponsesFailInsteadOfReportingCurrentVersion() {
        for (body in listOf("not-json", response().put("schemaVersion", 2).toString(),
            response().put("reason", "unknown").toString(), response().put("latest", JSONObject.NULL).toString(),
            response().put("updateAvailable", false).toString(), response().put("reason", "currentOrNewer").toString(),
            response().apply { remove("latest") }.toString(),
            response().apply { getJSONObject("latest").remove("notes") }.toString(),
            response().apply { getJSONObject("latest").remove("downloadPageUrl") }.toString())) {
            assertThrows(Exception::class.java) { AppUpdateApi.parse(body) }
        }
    }

    private class FakeHttps(url: URL, private val body: String, private val status: Int = 200) : HttpsURLConnection(url) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun disconnect() { disconnected = true }
        override fun connect() {}
        override fun usingProxy() = false
        override fun getCipherSuite() = "fake"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun transportHasNoAuthHeadersAndDoesNotFollowRedirects() = runBlocking {
        val connection = FakeHttps(AppUpdateApi.checkUrl(request), response().toString())
        val result = AppUpdateApi { connection }.check(request)
        assertNotNull(result.release)
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("synjones-auth"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun redirectsAndOversizedResponsesFailInsteadOfReportingCurrentVersion() {
        for (connection in listOf(FakeHttps(AppUpdateApi.checkUrl(request), "", 302),
            FakeHttps(AppUpdateApi.checkUrl(request), "x".repeat(262_145)))) {
            assertThrows(Exception::class.java) { runBlocking { AppUpdateApi { connection }.check(request) } }
            assertTrue(connection.disconnected)
        }
    }
}
