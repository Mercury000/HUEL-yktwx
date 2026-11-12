package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production request builder and parser, but never contacts a server. */
class YktApiLoginTest {
    private class FakeResponse(url: URL, private val status: Int, body: JsonObject) : HttpFixture(url) {
        private val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val sent = ByteArrayOutputStream()
        var disconnected = false

        override fun close() { disconnected = true }

        override fun statusCode() = status
        override fun bodyStream(): InputStream = ByteArrayInputStream(bytes)
        override fun errorBodyStream(): InputStream = ByteArrayInputStream(bytes)
        override fun requestBodyStream(): OutputStream = sent

    }
    private class ScriptedApi(captcha: Int = 0, private val tokenStatus: Int = 200, private val tokenBody: JsonObject? = null,
        private val balance: Any? = 1234, private val paymentStatus: Int = 1, private val lost: Int = 0, private val frozen: Int = 0,
        private val barcodeBody: JsonObject? = null, private val barcodeHttpStatus: Int = 200,
        private val cardBody: JsonObject? = null, private val cardHttpStatus: Int = 200,
        private val barcodeFailure: Exception? = null) {
        val calls = mutableListOf<FakeResponse>()
        private val methods = jsonArrayOf(listOf(jsonObjectOf("key" to ("snoNew"), "encryption" to ("keyboard"), "openCaptcha" to (captcha))))
        private val config = jsonObjectOf("getFrontConfig" to (jsonObjectOf("loginType" to (methods.toString())).toString()))
        private val keyboard = buildJsonObject {
            put("uuid", "fake-uuid")
            KeyboardEncoder.layouts.forEach { (key, value) -> put(key, value.reversed()) }
        }
        val api = fixtureYktApi { url ->
            check(url.protocol == "https" && url.host == "yktwx2.huel.edu.cn")
            if (url.path.endsWith("/batchGetBarCodeGet")) barcodeFailure?.let { throw it }
            val body = when (url.path) {
                "/berserker-app/frontInfo" -> jsonObjectOf("code" to (200), "data" to (config))
                "/berserker-secure/keyboard" -> jsonObjectOf("code" to (200), "data" to (keyboard))
                "/berserker-auth/oauth/token" -> tokenBody ?: jsonObjectOf("access_token" to ("fake-token"), "token_type" to ("bearer"), "expires_in" to (3600))
                "/berserker-app/ykt/tsm/codebarPayinfo" -> jsonObjectOf("code" to (200), "data" to (jsonArrayOf(listOf(jsonObjectOf("code" to ("ACCOUNT"), "status" to (paymentStatus), "lostflag" to (lost), "freezeflag" to (frozen), "account" to ("fake-account"), "payacc" to ("fake-payacc"), "paytype" to ("1"), "name" to ("Demo"), "accinfo_balance" to (balance))))))
                "/berserker-app/ykt/tsm/getCampusCards" -> cardBody ?: jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (jsonArrayOf(listOf(jsonObjectOf("account" to ("fake-account"), "lostflag" to (lost), "freezeflag" to (frozen), "acc_status" to (0))))))))
                "/berserker-app/ykt/tsm/batchGetBarCodeGet" -> barcodeBody ?: jsonObjectOf("code" to (200), "data" to (jsonObjectOf("barcode" to (jsonArrayOf(listOf("00000000000000000000"))), "expires" to (17280))))
                else -> error("Unexpected request path")
            }
            FakeResponse(url, when { url.path.endsWith("/oauth/token") -> tokenStatus
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
        for (balance in listOf(null, JsonNull, "", "oops", -1, 12.5, "9223372036854775808")) {
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
        assertEquals("POST", post.request.method)
        assertEquals("application/x-www-form-urlencoded", post.request.header("Content-Type"))
        assertEquals("Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0", post.request.header("Authorization"))
        val encoded = KeyboardEncoder.encode("Aa1@", KeyboardEncoder.layouts.mapValues { it.value.reversed() }, "fake-uuid")
        assertEquals(mapOf("username" to "123456", "password" to encoded, "grant_type" to "password", "scope" to "all",
            "loginFrom" to "h5", "logintype" to "snoNew", "device_token" to "h5", "synAccessSource" to "h5"), form(post.sent.toString("UTF-8")))
        assertFalse(post.sent.toString("UTF-8").contains("Aa1@"))
        assertEquals(mapOf("type" to "Standard", "order" to "0", "synAccessSource" to "h5"), form(fake.calls[1].url.query))
        assertFalse(fake.calls.any { testHttpClients.service.followRedirects })
        assertFalse(fake.calls.any { it.request.header("Cookie") != null })
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
        val fake = ScriptedApi(tokenStatus = 400, tokenBody = jsonObjectOf("error" to ("invalid_grant")))
        try { fake.api.login(Credentials("123", "Aa1@")); fail("Expected rejection") } catch (_: InvalidCredentials) {}
        assertEquals(3, fake.calls.size)
    }
    @Test fun paymentRequestsUseTokenNotBasicOrCookies() = runBlocking {
        val fake = ScriptedApi(); val session = fake.api.login(Credentials("123", "Aa1@")); fake.api.payment(session.accessToken)
        for (request in fake.calls.drop(3)) {
            assertEquals("bearer fake-token", request.request.header("synjones-auth"))
            assertNull(request.request.header("Authorization")); assertNull(request.request.header("Cookie"))
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
            jsonObjectOf("code" to (500)),
            jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (jsonArrayOf(listOf(jsonObjectOf("account" to ("other-card"), "lostflag" to (1), "freezeflag" to (0), "acc_status" to (0)))))))),
            jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (jsonArrayOf(listOf(jsonObjectOf("account" to ("fake-account"), "lostflag" to ("invalid")))))))))) {
            val fake = ScriptedApi(cardBody = body)
            assertThrows(YktException::class.java) { runBlocking { fake.api.paymentCard("fake-token", "fake-account") } }
            assertEquals(1, fake.calls.size)
            assertEquals(1, fake.api.payment("fake-token").codes.size)
        }
    }

    @Test fun duplicateMatchOrMalformedFreezeStatusCannotBePresentedAsVerified() = runBlocking {
        val normal = jsonObjectOf("account" to ("fake-account"), "lostflag" to (0), "freezeflag" to (0), "acc_status" to (0))
        for (cards in listOf(jsonArrayOf(listOf(normal, normal)),
            jsonArrayOf(listOf(parseObject(normal.toString()).withField("freezeflag", "invalid"))),
            jsonArrayOf(listOf(parseObject(normal.toString()).withField("acc_status", "invalid"))))) {
            val fake = ScriptedApi(cardBody = jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (cards)))))
            assertThrows(YktException::class.java) { runBlocking { fake.api.paymentCard("fake-token", "fake-account") } }
            assertEquals(1, fake.calls.size)
        }
    }

    @Test fun lossOnAnotherCardDoesNotBlockTheMatchingPaymentAccount() = runBlocking {
        val cards = jsonArrayOf(listOf(jsonObjectOf("account" to ("other-card"), "lostflag" to (1), "freezeflag" to (0), "acc_status" to (0)), jsonObjectOf("account" to ("fake-account"), "lostflag" to (0), "freezeflag" to (0), "acc_status" to (0))))
        val fake = ScriptedApi(cardBody = jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (cards)))))
        assertFalse(fake.api.paymentCard("fake-token", "fake-account").lost)
    }

    @Test fun cardReadAuthenticationFailureStillRequiresAuthentication() = runBlocking {
        val fake = ScriptedApi(cardHttpStatus = 401, cardBody = jsonObjectOf("code" to (401)))
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
        val fake = ScriptedApi(barcodeBody = jsonObjectOf("code" to (60050), "success" to (false), "msg" to (message)))
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals(message, error.message)
        assertEquals(1234L, error.account.balanceCents)
        assertEquals(2, fake.calls.size)
        assertEquals(ReadFailureKind.Other, error.readFailureKind())
    }

    @Test fun barcodeTransportFailureKeepsAccountAndNetworkCategoryForRecovery() {
        val transportFailure = java.io.IOException("connection reset")
        val fake = ScriptedApi(barcodeFailure = transportFailure)
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals(1234L, error.account.balanceCents)
        assertEquals(ReadFailureKind.Network, error.readFailureKind())
        assertEquals(PaymentCodeProblem.StatusUnverified, error.problem)
        assertTrue(error.cause is NetworkFailure)
        assertTrue(generateSequence(error as Throwable) { it.cause }.any { it === transportFailure })
    }

    @Test fun emptyBarcodePreservesInnerServerReasonAndBalance() = runBlocking {
        val fake = ScriptedApi(barcodeBody = jsonObjectOf("code" to (200), "data" to (jsonObjectOf("barcode" to (jsonArrayOf()), "errmsg" to ("账户状态提示")))))
        val error = assertThrows(PaymentCodeUnavailable::class.java) { runBlocking { fake.api.payment("fake-token") } }
        assertEquals("账户状态提示", error.message)
        assertEquals(1234L, error.account.balanceCents)
    }

    @Test fun barcodeAuthenticationFailureStillRequiresAuthentication() = runBlocking {
        val fake = ScriptedApi(barcodeHttpStatus = 401, barcodeBody = jsonObjectOf("code" to (401)))
        assertThrows(AuthenticationRequired::class.java) { runBlocking { fake.api.payment("fake-token") } }
        Unit
    }

}
