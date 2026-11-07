package com.mercury.yktwx.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLDecoder
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production request builder and parser, but never contacts a server. */
class YktApiLoginTest {
    private class FakeHttps(url: URL, private val status: Int, body: JSONObject) : HttpsURLConnection(url) {
        private val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getOutputStream(): OutputStream = sent
        override fun getCipherSuite() = "TLS_FAKE_FOR_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private class ScriptedApi(captcha: Int = 0, private val tokenStatus: Int = 200, private val tokenBody: JSONObject? = null,
        private val balance: Any? = 1234, private val paymentStatus: Int = 1, private val lost: Int = 0, private val frozen: Int = 0,
        private val barcodeBody: JSONObject? = null, private val barcodeHttpStatus: Int = 200,
        private val cardBody: JSONObject? = null, private val cardHttpStatus: Int = 200) {
        val calls = mutableListOf<FakeHttps>()
        private val methods = JSONArray().put(JSONObject().put("key", "snoNew").put("encryption", "keyboard").put("openCaptcha", captcha))
        private val config = JSONObject().put("getFrontConfig", JSONObject().put("loginType", methods.toString()).toString())
        private val keyboard = JSONObject().put("uuid", "fake-uuid").also { obj ->
            KeyboardEncoder.layouts.forEach { (key, value) -> obj.put(key, value.reversed()) }
        }
        val api = YktApi { url ->
            check(url.protocol == "https" && url.host == "yktwx2.huel.edu.cn")
            val body = when (url.path) {
                "/berserker-app/frontInfo" -> JSONObject().put("code", 200).put("data", config)
                "/berserker-secure/keyboard" -> JSONObject().put("code", 200).put("data", keyboard)
                "/berserker-auth/oauth/token" -> tokenBody ?: JSONObject().put("access_token", "fake-token").put("token_type", "bearer").put("expires_in", 3600)
                "/berserker-app/ykt/tsm/codebarPayinfo" -> JSONObject().put("code", 200).put("data", JSONArray().put(
                    JSONObject().put("code", "ACCOUNT").put("status", paymentStatus).put("lostflag", lost).put("freezeflag", frozen)
                        .put("account", "fake-account").put("payacc", "fake-payacc").put("paytype", "1").put("name", "Demo").put("accinfo_balance", balance)))
                "/berserker-app/ykt/tsm/getCampusCards" -> cardBody ?: JSONObject().put("code", 200).put("data", JSONObject()
                    .put("retcode", "0").put("card", JSONArray().put(JSONObject().put("account", "fake-account")
                        .put("lostflag", lost).put("freezeflag", frozen).put("acc_status", 0))))
                "/berserker-app/ykt/tsm/batchGetBarCodeGet" -> barcodeBody ?: JSONObject().put("code", 200).put("data", JSONObject().put("barcode", JSONArray().put("00000000000000000000")).put("expires", 17280))
                else -> error("Unexpected request path")
            }
            FakeHttps(url, when { url.path.endsWith("/oauth/token") -> tokenStatus
                url.path.endsWith("/getCampusCards") -> cardHttpStatus
                url.path.endsWith("/batchGetBarCodeGet") -> barcodeHttpStatus
                else -> 200 }, body).also { calls.add(it) }
        }
    }
    private fun form(value: String): Map<String, String> = value.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }
    @Test fun standaloneBalanceQueryDoesNotCheckCardOrFetchBarcode() = runBlocking {
        val fake = ScriptedApi(paymentStatus = 0, lost = 1)
        assertEquals(1234L, fake.api.paymentAccount("fake-token").balanceCents)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls.single().url.path.endsWith("/codebarPayinfo"))
    }
    @Test fun standaloneCardStatusReadDoesNotFetchBalanceBarcodeOrManagementSettings() = runBlocking {
        val fake = ScriptedApi(paymentStatus = 0, lost = 1)
        val card = fake.api.paymentCard("fake-token", "fake-account")
        assertTrue(card.lost)
        assertFalse(card.frozen)
        assertEquals(0, card.status)
        assertEquals(1, fake.calls.size)
        assertTrue(fake.calls.single().url.path.endsWith("/getCampusCards"))
    }

    @Test fun paymentPublishesBalanceBeforeFetchingCodeWithoutAnotherAccountQuery() = runBlocking {
        val fake = ScriptedApi()
        var published: PaymentAccount? = null
        val batch = fake.api.payment("fake-token") { account ->
            assertEquals(1, fake.calls.size)
            assertTrue(fake.calls.single().url.path.endsWith("/codebarPayinfo"))
            assertEquals(1234L, account.balanceCents)
            published = account
        }
        assertSame(batch.account, published)
        assertEquals(2, fake.calls.size)
        assertEquals(1, fake.calls.count { it.url.path.endsWith("/codebarPayinfo") })
    }
    @Test fun malformedBalancesCannotBecomeZeroOrTruncatedAmounts() = runBlocking {
        for (balance in listOf(null, JSONObject.NULL, "", "oops", -1, 12.5, "9223372036854775808")) {
            val fake = ScriptedApi(balance = balance)
            try { fake.api.paymentAccount("fake-token"); fail("Accepted malformed balance: $balance") }
            catch (_: YktException) { }
        }
        assertEquals(0L, ScriptedApi(balance = "0").api.paymentAccount("fake-token").balanceCents)
    }
    @Test fun loginAndPaymentReachFiveEndpointsWithoutWaitingForCardStatus() = runBlocking {
        val fake = ScriptedApi()
        val session = fake.api.login(Credentials("123456", "Aa1@"))
        val batch = fake.api.payment(session.accessToken)
        assertEquals(5, fake.calls.size)
        assertEquals("fake-token", session.accessToken)
        assertTrue(session.isFresh())
        assertEquals(1, batch.codes.size)
        assertEquals(1234, batch.account.balanceCents)
        assertTrue(fake.calls.all { it.disconnected })
        assertTrue(fake.calls.all { it.url.protocol == "https" })
    }
    @Test fun loginFormAndHeadersMatchWebsiteProtocol() = runBlocking {
        val fake = ScriptedApi(); fake.api.login(Credentials("123456", "Aa1@"))
        val post = fake.calls[2]
        assertEquals("POST", post.requestMethod)
        assertEquals("application/x-www-form-urlencoded", post.getRequestProperty("Content-Type"))
        assertEquals("Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0", post.getRequestProperty("Authorization"))
        val encoded = KeyboardEncoder.encode("Aa1@", KeyboardEncoder.layouts.mapValues { it.value.reversed() }, "fake-uuid")
        assertEquals(mapOf("username" to "123456", "password" to encoded, "grant_type" to "password", "scope" to "all",
            "loginFrom" to "h5", "logintype" to "snoNew", "device_token" to "h5", "synAccessSource" to "h5"), form(post.sent.toString("UTF-8")))
        assertFalse(post.sent.toString("UTF-8").contains("Aa1@"))
        assertEquals(mapOf("type" to "Standard", "order" to "0", "synAccessSource" to "h5"), form(fake.calls[1].url.query))
        assertFalse(fake.calls.any { it.instanceFollowRedirects })
        assertFalse(fake.calls.any { it.getRequestProperty("Cookie") != null })
    }
    @Test fun encodedSymbolsSurviveFormEncoding() = runBlocking {
        val fake = ScriptedApi(); val password = KeyboardEncoder.layouts.values.joinToString("")
        fake.api.login(Credentials("123", password))
        assertEquals(KeyboardEncoder.encode(password, KeyboardEncoder.layouts.mapValues { it.value.reversed() }, "fake-uuid"),
            form(fake.calls[2].sent.toString("UTF-8"))["password"])
    }
    @Test fun captchaConfigStopsBeforeKeyboardOrPasswordRequest() = runBlocking {
        val fake = ScriptedApi(captcha = 1)
        try { fake.api.login(Credentials("123", "Aa1@")); fail("Captcha must stop") } catch (_: CaptchaRequired) {}
        assertEquals(1, fake.calls.size)
    }
    @Test fun rejectedPasswordIsNotRetried() = runBlocking {
        val fake = ScriptedApi(tokenStatus = 400, tokenBody = JSONObject().put("error", "invalid_grant"))
        try { fake.api.login(Credentials("123", "Aa1@")); fail("Expected rejection") } catch (_: InvalidCredentials) {}
        assertEquals(3, fake.calls.size)
    }
    @Test fun paymentRequestsUseTokenNotBasicOrCookies() = runBlocking {
        val fake = ScriptedApi(); val session = fake.api.login(Credentials("123", "Aa1@")); fake.api.payment(session.accessToken)
        for (request in fake.calls.drop(3)) {
            assertEquals("bearer fake-token", request.getRequestProperty("synjones-auth"))
            assertNull(request.getRequestProperty("Authorization")); assertNull(request.getRequestProperty("Cookie"))
        }
    }
    @Test fun lossOrFreezeFlagsDoNotPreventRequestingAServerIssuedBarcode() = runBlocking {
        for ((lost, frozen) in listOf(1 to 0, 0 to 1, 1 to 1)) {
            val fake = ScriptedApi(lost = lost, frozen = frozen)
            val batch = fake.api.payment("fake-token")
            assertEquals(1234L, batch.account.balanceCents)
            assertEquals(1, batch.codes.size)
            assertEquals(2, fake.calls.size)
            assertTrue(fake.calls.any { it.url.path.endsWith("/batchGetBarCodeGet") })
            assertFalse(fake.calls.any { it.url.path.endsWith("/getCampusCards") })
        }
    }

    @Test fun failedOrUnmatchedCardReadRemainsAnIndependentFailure() = runBlocking {
        for (body in listOf(
            JSONObject().put("code", 500),
            JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", JSONArray()
                .put(JSONObject().put("account", "other-card").put("lostflag", 1).put("freezeflag", 0).put("acc_status", 0)))),
            JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", JSONArray()
                .put(JSONObject().put("account", "fake-account").put("lostflag", "invalid")))))) {
            val fake = ScriptedApi(cardBody = body)
            assertThrows(YktException::class.java) { runBlocking { fake.api.paymentCard("fake-token", "fake-account") } }
            assertEquals(1, fake.calls.size)
            assertEquals(1, fake.api.payment("fake-token").codes.size)
        }
    }

    @Test fun duplicateMatchOrMalformedFreezeStatusCannotBePresentedAsVerified() = runBlocking {
        val normal = JSONObject().put("account", "fake-account").put("lostflag", 0).put("freezeflag", 0).put("acc_status", 0)
        for (cards in listOf(JSONArray().put(normal).put(normal),
            JSONArray().put(JSONObject(normal.toString()).put("freezeflag", "invalid")),
            JSONArray().put(JSONObject(normal.toString()).put("acc_status", "invalid")))) {
            val fake = ScriptedApi(cardBody = JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", cards)))
            assertThrows(YktException::class.java) { runBlocking { fake.api.paymentCard("fake-token", "fake-account") } }
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun lossOnAnotherCardDoesNotBlockTheMatchingPaymentAccount() = runBlocking {
        val cards = JSONArray()
            .put(JSONObject().put("account", "other-card").put("lostflag", 1).put("freezeflag", 0).put("acc_status", 0))
            .put(JSONObject().put("account", "fake-account").put("lostflag", 0).put("freezeflag", 0).put("acc_status", 0))
        val fake = ScriptedApi(cardBody = JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", cards)))
        assertFalse(fake.api.paymentCard("fake-token", "fake-account").lost)
    }

    @Test fun cardReadAuthenticationFailureStillRequiresAuthentication() = runBlocking {
        val fake = ScriptedApi(cardHttpStatus = 401, cardBody = JSONObject().put("code", 401))
        assertThrows(AuthenticationRequired::class.java) { runBlocking { fake.api.paymentCard("fake-token", "fake-account") } }
        Unit
    }

    @Test fun serverInactiveStatusKeepsBalanceWithoutRequestingBarcode() = runBlocking {
        val fake = ScriptedApi(paymentStatus = 0)
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals(1234L, error.account.balanceCents)
        assertEquals("fake-account", error.account.account)
        assertEquals(1, fake.calls.size)
    }

    @Test fun serverBarcodeRejectionKeepsCurrentAccountAndOriginalMessage() = runBlocking {
        val message = "  持卡人已挂失！\n"
        val fake = ScriptedApi(barcodeBody = JSONObject().put("code", 60050)
            .put("success", false).put("msg", message))
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals(message, error.message)
        assertEquals(1234L, error.account.balanceCents)
        assertEquals(2, fake.calls.size)
    }

    @Test fun emptyBarcodePreservesInnerServerReasonAndBalance() = runBlocking {
        val fake = ScriptedApi(barcodeBody = JSONObject().put("code", 200).put("data", JSONObject()
            .put("barcode", JSONArray()).put("errmsg", "账户状态提示")))
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals("账户状态提示", error.message)
        assertEquals(1234L, error.account.balanceCents)
    }

    @Test fun barcodeAuthenticationFailureStillRequiresAuthentication() = runBlocking {
        val fake = ScriptedApi(barcodeHttpStatus = 401, barcodeBody = JSONObject().put("code", 401))
        assertThrows(AuthenticationRequired::class.java) { runBlocking { fake.api.payment("fake-token") } }
        Unit
    }

}
