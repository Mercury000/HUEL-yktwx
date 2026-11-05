package com.mercury.yktwx.data.life

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class Hui798ApiTest {
    private class Response(url: URL, val body: String, val status: Int = 200, val missingErrorBody: Boolean = false) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream? = if (missingErrorBody) null else ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = sent
        override fun getCipherSuite() = "TEST_TLS"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    @Test fun appLoginAndSmsUseIndependentJsonAndSignedLoginFields() = runBlocking {
        val calls = mutableListOf<Response>()
        val api = Hui798Api("https://fixture.test/api/v1", "fixture-salt", { url ->
            Response(url, """{"code":0,"data":{"al":{"token":"fixture-token","uid":"fixture-user"}}}""").also(calls::add)
        })
        api.sendCode("13800000000", "aB12", "image-key")
        assertEquals(Hui798Account("13800000000", "fixture-token", "fixture-user"), api.login("13800000000", "123456"))
        assertEquals("/api/v1/acc/login/code", calls[0].url.path)
        assertEquals("image-key", JSONObject(calls[0].sent.toString("UTF-8")).getString("s"))
        assertEquals("aB12", JSONObject(calls[0].sent.toString("UTF-8")).getString("authCode"))
        assertNull(calls[0].url.query)
        val login = calls[1]
        assertEquals("sign=c811cc18f9d81e8ed95ccbe427709a4c", login.url.query)
        val body = JSONObject(login.sent.toString("UTF-8"))
        assertEquals(2, body.length()); assertEquals("123456", body.getString("authCode")); assertEquals("13800000000", body.getString("un"))
        calls.forEach {
            assertEquals("POST", it.requestMethod); assertEquals("1,1", it.getRequestProperty("ApplicationType"))
            assertEquals("3.1.9", it.getRequestProperty("VersionCode")); assertNull(it.getRequestProperty("Authorization"))
            assertFalse(it.instanceFollowRedirects); assertTrue(it.closed)
        }
    }
    @Test fun commandsUseFixedPaymentParametersAndAuthorizationWithoutEndSignature() = runBlocking {
        val calls = mutableListOf<Response>()
        val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0}""").also(calls::add) })
        api.start("123456789012345", "fixture-token"); api.end("123456789012345", "fixture-token")
        assertEquals("/api/v1/dev/start", calls[0].url.path)
        assertEquals("did=123456789012345&upgrade=true&ptype=91&rcp=false", calls[0].url.query)
        assertEquals("/api/v1/dev/end", calls[1].url.path); assertEquals("did=123456789012345&rcp=false", calls[1].url.query)
        calls.forEach { assertEquals("GET", it.requestMethod); assertEquals("fixture-token", it.getRequestProperty("Authorization"))
            assertFalse(it.instanceFollowRedirects); assertEquals(0, it.sent.size()); assertTrue(it.closed) }
    }
    @Test fun recoveryReadsAccountIdentityAndVerifiesUseThroughHomeRatherThanFavoOwner() = runBlocking {
        val calls = mutableListOf<Response>()
        val api = Hui798Api("https://fixture.test/api/v1", "", { url ->
            val body = if (url.path.endsWith("/master"))
                """{"code":0,"data":{"account":{"id":"current-user"},"favos":[{"id":"device-1","name":"饮水机","owner":{"id":"operator"}}]}}"""
            else """{"code":0,"data":{"device":{"id":"device-1","name":"饮水机","gene":{"status":1,"time":"1700000000000"}},"user":{"id":"current-user"}}}"""
            Response(url, body).also(calls::add)
        })
        val device = Hui798Device("device-1", "饮水机")
        assertEquals(Hui798Overview("current-user", listOf(device)), api.overview("fixture-token"))
        assertEquals(Hui798DeviceUse(device, Hui798Reading(1, 1_700_000_000_000), "current-user"), api.deviceUse(device.id, "fixture-token"))
        assertEquals("/api/v1/ui/app/master", calls[0].url.path); assertEquals("ad=true", calls[0].url.query)
        assertEquals("did=device-1&apply=6", calls[1].url.query)
        calls.forEach { assertEquals("GET", it.requestMethod); assertEquals("fixture-token", it.getRequestProperty("Authorization")) }
    }
    @Test fun malformedRecoveryResponsesCannotPretendThatNoAccountIsUsingWater() = runBlocking {
        for (data in listOf("{}", """{"account":{"id":null},"favos":[]}""", """{"account":{"id":"user"}}""")) {
            val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0,"data":$data}""") })
            try { api.overview("token"); fail("Missing identity/list must fail") } catch (_: Hui798Exception) {}
        }
        for (data in listOf("""{"device":{"gene":{"status":1}}}""",
            """{"device":{"id":"other-device","gene":{"status":99}}}""")) {
            val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0,"data":$data}""") })
            try { api.deviceUse("device", "token"); fail("Unidentified/mismatched device must fail") } catch (_: Hui798Exception) {}
        }
        val api = Hui798Api("https://fixture.test/api/v1", "", { url ->
            Response(url, """{"code":0,"data":{"device":{"gene":{"status":1,"time":1700000000000}},"user":{"id":null}}}""")
        })
        assertNull(api.deviceUse("device", "token").userId)
    }
    @Test fun deviceHomeUsesTheReferenceOptionalNameAndStillQueriesStatus() = runBlocking {
        for ((body, name) in listOf(
            """{"code":0}""" to "",
            """{"code":0,"data":{}}""" to "",
            """{"code":0,"data":{"device":{}}}""" to "",
            """{"code":0,"data":{"device":{"name":"五号楼饮水机"}}}""" to "五号楼饮水机",
        )) {
            val calls = mutableListOf<Response>()
            val api = Hui798Api("https://fixture.test/api/v1", "", { url ->
                Response(url, if (url.path.endsWith("/status"))
                    """{"code":0,"data":{"device":{"gene":{"status":99}}}}""" else body).also(calls::add)
            })
            assertEquals(Hui798Device("864165069279947", name), api.device("864165069279947", "fixture-token"))
            assertEquals(Hui798Reading(99, null), api.status("864165069279947", "fixture-token"))
            assertEquals("/api/v1/ui/app/dev/home/1", calls[0].url.path)
            assertEquals("did=864165069279947&apply=6", calls[0].url.query)
            assertEquals("/api/v1/ui/app/dev/status", calls[1].url.path)
            assertEquals("did=864165069279947&more=false", calls[1].url.query)
            calls.forEach { assertEquals("fixture-token", it.getRequestProperty("Authorization")) }
        }
    }
    @Test fun missingUnknownAndUnidentifiedRunningStatusAreRejectedRatherThanStopped() = runBlocking {
        for (gene in listOf("{}", """{"status":null}""", """{"status":0}""", """{"status":5}""", """{"status":1}""", """{"status":1,"time":0}""")) {
            val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0,"data":{"device":{"gene":$gene}}}""") })
            try { api.status("fixture-device", "fixture-token"); fail("Unknown state must fail") } catch (_: Hui798Exception) {}
        }
        val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0,"data":{"device":{"status":1,"gene":{"status":99}}}}""") })
        assertEquals(Hui798Reading(99, null), api.status("fixture-device", "fixture-token"))
    }
    @Test fun onlyAuthenticationErrorsExpireTheAccountAndHttpFailureCannotReportSuccess() = runBlocking {
        for ((code, expired) in listOf(-99 to true, -21 to true, -88 to false, -52 to false)) {
            val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":$code,"msg":"fixture-error"}""") })
            try { api.start("fixture-device", "fixture-token"); fail() } catch (e: Hui798Exception) { assertEquals(expired, e.loginExpired); assertTrue(e.rejected) }
        }
        val api = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, """{"code":0}""", 503) })
        try { api.end("fixture-device", "fixture-token"); fail() } catch (e: Hui798Exception) { assertFalse(e.loginExpired); assertFalse(e.rejected) }
        for (status in listOf(401, 403)) {
            val expiredApi = Hui798Api("https://fixture.test/api/v1", "", { url -> Response(url, "", status, missingErrorBody = true) })
            try { expiredApi.status("fixture-device", "fixture-token"); fail() } catch (e: Hui798Exception) { assertTrue(e.loginExpired) }
        }
    }
    @Test fun qrDomainRoutesBothPlatformsAndKeepsBareSerialCompatibility() {
        val id = "123456789012345"
        assertEquals(DeviceScanRoute(LifeProvider.Hui798, LifeFeature.Water, id), UnifiedDeviceCode.identify("https://i.hnkzy.com/q/1/$id"))
        assertEquals(id, Hui798DeviceCode.parse("https://cloud.hnkzy.com/h5/?atype=1&id=$id#/pages/app/index"))
        assertEquals(LifeProvider.Panggui, UnifiedDeviceCode.identify(id)?.provider)
        assertEquals(LifeProvider.Panggui, UnifiedDeviceCode.identify("https://h5.qiekj.com/skip?SN=$id")?.provider)
        val nqt = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(LifeFeature.Laundry, UnifiedDeviceCode.identify(nqt)?.feature)
        assertNull(UnifiedDeviceCode.identify("https://h5.qiekj.com/skip?SN=$id&NQT=$nqt"))
        for (raw in listOf("http://i.hnkzy.com/q/1/$id", "https://i.hnkzy.com.evil.test/q/1/$id", "https://evil-hnkzy.com/q/1/$id",
            "https://i.hnkzy.com/q/1/$id?did=$id", "https://cloud.hnkzy.com/h5/?atype=1&id=$id&id=$id",
            "https://cloud.hnkzy.com/h5/?atype=1&id=$id&%69d=$id", "https://cloud.hnkzy.com/h5/?atype=1&id=$id&SN=$id",
            "https://cloud.hnkzy.com/h5/?atype=1&id=$id#https://evil.test", "https://i.hnkzy.com/q/2/$id", "https://example.test/?id=$id"))
            assertNull(raw, Hui798DeviceCode.parse(raw))
    }
}
