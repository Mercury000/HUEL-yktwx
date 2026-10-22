package com.mercury.yktwx.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLDecoder
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class CardServiceApiTest {
    private class FakeHttps(url: URL, private val body: String, private val status: Int = 200) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = sent
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun configuration(lock: String? = null, unlock: String? = null, type: String = "1"): String {
        val front = JSONObject().put("recharge", "401")
        lock?.let { front.put("lockFlag", it) }; unlock?.let { front.put("unlockFlag", it) }
        return JSONObject().put("code", 200).put("data", JSONObject().put("getFrontConfig", front.toString())
            .put("getEcardConfig", JSONObject().put("type", type).toString())).toString()
    }
    private fun card(lost: Int = 0) = JSONObject().put("account", "recipient-card").put("cardname", "校园卡")
        .put("sno", "202600000002").put("name", "示例同学").put("lostflag", lost).put("freezeflag", 0).put("acc_status", 0)
        .put("db_balance", 100).put("unsettle_amount", 200).put("accinfo", JSONArray().put(
            JSONObject().put("type", "recipient-electronic").put("name", "统一账户").put("balance", 300)))
    private fun cardResponse(lost: Int = 0) = JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0")
        .put("card", JSONArray().put(card(lost)))).toString()
    private fun params(text: String?) = text.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val p = it.split('=', limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    private fun selected(lost: Boolean = false) = CampusCard("recipient-card", "校园卡", "202600000002", lost, false, 0)
    private fun lookup(api: CardServiceApi) = runBlocking { api.rechargeRecipients("fake-token", RecipientQueryType.StudentNumber,
        "202600000002", RecipientCaptcha("fake-key", byteArrayOf(1)), "abcde") }

    @Test fun missingFlagsFollowWebsiteLostWithoutPasswordAndUnlostWithPassword() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url -> FakeHttps(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse()).also(calls::add) }
        val result = api.cardConfiguration("fake-token")
        assertFalse(result.lostNeedsPassword); assertTrue(result.unlostNeedsPassword)
        assertFalse(result.cards.single().lost)
        assertTrue(calls.all { it.getRequestProperty("Cookie") == null && it.getRequestProperty("synjones-auth") == "bearer fake-token" && it.closed && !it.instanceFollowRedirects })
    }
    @Test fun unlostUsesFreshNumberKeyboardSingleJsonWriteAndRefreshesState() = runBlocking {
        var lost = true
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url ->
            val body = when {
                url.path.endsWith("frontInfo") -> configuration()
                url.path.endsWith("getCampusCards") -> cardResponse(if (lost) 1 else 0)
                url.path.endsWith("keyboard") -> "{\"code\":200,\"data\":{\"numberKeyboard\":\"abcdefghij\",\"uuid\":\"fake-uuid\"}}"
                url.path.endsWith("unlostCard") -> { lost = false; "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"recipient-card\"}}" }
                else -> error("Unexpected local request")
            }
            FakeHttps(url, body).also(calls::add)
        }
        assertFalse(api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "012345").single().lost)
        val post = calls.single { it.requestMethod == "POST" }
        val body = JSONObject(post.sent.toString("UTF-8"))
        assertEquals("recipient-card", body.getString("account"))
        assertEquals("1\$1\$abcdef\$1\$fake-uuid", body.getString("pwd"))
        assertEquals(1, body.getInt("pwdType")); assertEquals("h5", body.getString("synAccessSource"))
        assertFalse(post.sent.toString("UTF-8").contains("012345"))
        assertEquals("application/json;charset=UTF-8", post.getRequestProperty("Content-Type"))
        val keyboard = calls.single { it.url.path.endsWith("keyboard") }
        assertEquals("Number", params(keyboard.url.query)["type"]); assertEquals("0", params(keyboard.url.query)["order"])
        assertEquals(2, calls.count { it.url.path.endsWith("getCampusCards") })
    }
    @Test fun alreadyReachedStateDoesNotPost() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url -> FakeHttps(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse(1)).also(calls::add) }
        assertTrue(api.changeCardState("fake-token", selected(), CampusCardAction.Lost, "").single().lost)
        assertTrue(calls.none { it.requestMethod == "POST" })
    }
    @Test fun explicitNoPasswordOmitsEmptyKeyboardFields() = runBlocking {
        var lost = false
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url ->
            FakeHttps(url, when {
                url.path.endsWith("frontInfo") -> configuration("0", "0")
                url.path.endsWith("getCampusCards") -> cardResponse(if (lost) 1 else 0)
                url.path.endsWith("lostCard") -> { lost = true; "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"recipient-card\"}}" }
                else -> error("Unexpected request")
            }).also(calls::add)
        }
        api.changeCardState("fake-token", selected(), CampusCardAction.Lost, "")
        val body = JSONObject(calls.single { it.requestMethod == "POST" }.sent.toString("UTF-8"))
        assertFalse(body.has("pwd")); assertFalse(body.has("pwdType")); assertEquals(0, calls.count { it.url.path.endsWith("keyboard") })
    }
    @Test fun failedPostIsNotRetriedAndErrorDoesNotEchoServerAccountOrPassword() = runBlocking {
        var writes = 0
        val api = CardServiceApi { url -> FakeHttps(url, when {
            url.path.endsWith("frontInfo") -> configuration("0", "0")
            url.path.endsWith("getCampusCards") -> cardResponse()
            else -> { writes++; "{\"code\":200,\"data\":{\"retcode\":\"60005\",\"account\":\"recipient-card\",\"errmsg\":\"secret-account-123456\"}}" }
        }) }
        try { api.changeCardState("fake-token", selected(), CampusCardAction.Lost, ""); fail("Expected rejection") }
        catch (e: YktException) { assertFalse(e.message.orEmpty().contains("secret-account")) }
        assertEquals(1, writes)
    }
    @Test fun recipientQueryUsesQueryParametersAndElectronicTargetNotInputOrPayer() {
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url -> FakeHttps(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse()).also(calls::add) }
        val recipient = lookup(api).single()
        assertEquals("recipient-electronic", recipient.targetId); assertEquals("recipient-card", recipient.cardAccount)
        assertEquals(300L, recipient.balanceCents)
        val p = params(calls.first().url.query)
        assertEquals("sno", p["idtype"]); assertEquals("202600000002", p["id"]); assertEquals("recharge", p["scene"])
        assertEquals("fake-key", p["captcha_header_key"]); assertEquals("abcde", p["captcha_header_code"])
        assertNull(calls.first().getRequestProperty("captcha_header_key"))
        assertTrue(calls.none { it.url.path.endsWith("queryCard") || it.requestMethod == "POST" })
    }
    @Test fun nestedAccountMissingIsNotOuterSuccessAndCaptchaErrorIsDistinct() {
        val missing = CardServiceApi { url -> FakeHttps(url, "{\"code\":200,\"success\":true,\"data\":{\"retcode\":\"60003\",\"card\":null},\"msg\":\"操作成功\"}") }
        try { lookup(missing); fail("Expected missing account") } catch (e: RecipientLookupRejected) { assertFalse(e.invalidCaptcha); assertTrue(e.message!!.contains("不存在")) }
        val captcha = CardServiceApi { url -> FakeHttps(url, "{\"code\":8002,\"success\":false,\"data\":null}") }
        try { lookup(captcha); fail("Expected captcha failure") } catch (e: RecipientLookupRejected) { assertTrue(e.invalidCaptcha) }
    }
    @Test fun captchaReadsTopLevelKeyAndBoundedInlineImage() = runBlocking {
        val api = CardServiceApi { url -> FakeHttps(url, "{\"key\":\"fake-key\",\"image\":\"data:image/png;base64,AQID\"}") }
        val c = api.recipientCaptcha("fake-token")
        assertEquals("fake-key", c.key); assertArrayEquals(byteArrayOf(1, 2, 3), c.image)
    }
    @Test fun recipientAmountConfigurationNeverRequeriesPayerAccount() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = CardServiceApi { url -> FakeHttps(url, if (url.path.endsWith("frontInfo")) configuration()
            else "{\"code\":200,\"feeitem\":{\"layout\":\"10,50,100\",\"flag\":\"1100000000\"}}").also(calls::add) }
        val recipient = RechargeRecipient("recipient-card", "recipient-electronic", "示例同学", "202600000002", "统一账户", 300)
        val config = api.recipientRechargeConfiguration("fake-token", recipient)
        assertSame(recipient, config.recipient); assertEquals(recipient.targetId, config.targetId)
        assertEquals(listOf(1000L, 5000L, 10000L), config.amountsCents)
        assertTrue(calls.none { it.url.path.endsWith("queryCard") })
    }
    @Test fun numberEncoderRejectsBadInputsAndSensitiveModelsAreRedacted() {
        for ((password, mapping, uuid) in listOf(Triple("12345", "abcdefghij", "fake"), Triple("123456", "aaaaaaaaaa", "fake"), Triple("123456", "abcdefghij", "bad\$id"))) {
            try { NumberKeyboardEncoder.encode(password, mapping, uuid); fail("Expected invalid input") } catch (_: YktException) { }
        }
        assertEquals("CampusCard([redacted])", selected().toString())
        assertEquals("RecipientCaptcha([redacted])", RecipientCaptcha("secret", byteArrayOf()).toString())
        assertFalse(selected().label.contains("202600000002"))
    }
    @Test fun businessFlagsAreParsedFromGetBusinessConfigAndMatchWebsiteConditions() = runBlocking {
        val payload = JSONObject(configuration()).getJSONObject("data")
        payload.put("getBusinessConfig", JSONObject().put("businessHandleConfig", "{\"lockFlag\":\"1\",\"unlockFlag\":\"0\"}"))
        val wrapper = JSONObject().put("code", 200).put("data", payload)
        val api = CardServiceApi { url -> FakeHttps(url, if (url.path.endsWith("frontInfo")) wrapper.toString() else cardResponse()) }
        val c = api.cardConfiguration("fake-token")
        assertTrue(c.lostNeedsPassword); assertFalse(c.unlostNeedsPassword)
    }
    @Test fun recipientUiValuesAreNotMaskedButStringificationIsRedacted() {
        val recipient = RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户", 0)
        assertEquals("示例同学 · 202600000002", recipient.label)
        assertEquals("RechargeRecipient([redacted])", recipient.toString())
    }

}
