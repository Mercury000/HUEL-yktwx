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
import java.net.URLEncoder
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class RechargeApiTest {
    private class FakeHttps(url: URL, private val status: Int, private val body: String, private val location: String? = null) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = sent
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private fun pending() = PendingRecharge("123456", "fake-account", "401", 1000, 0, "FAKE-ORDER", "81")
    private fun config() = RechargeConfiguration("401", "fake-account", "统一账户", 1000, listOf(1000, 5000, 10000), 1, 50000, true, false)
    private fun params(text: String) = text.split('&').filter { it.isNotEmpty() }.associate {
        val p = it.split('=', limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    private fun cards() = JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", JSONArray().put(
        JSONObject().put("account", "fake-card").put("lostflag", 0).put("freezeflag", 0).put("accinfo", JSONArray().put(
            JSONObject().put("type", "fake-account").put("name", "统一账户").put("balance", 2000)
        )))))
    private fun result(status: Int, amount: Int = 10) = JSONObject().put("code", 200).put("orderList", JSONArray().put(JSONObject()
        .put("orderid", "FAKE-ORDER").put("payid", 81).put("third_party", "fake-account").put("tranamt", amount).put("actulamt", amount)
        .put("status", status).put("feeitemid", 0).put("feeitemlist", JSONArray().put(JSONObject().put("feeitemid", 401)))))
    @Test fun configurationReadsTopLevelFeeAndMapsElectronicAccount() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = RechargeApi { url ->
            val payload = when (url.path) {
                "/berserker-app/frontInfo" -> JSONObject().put("code", 200).put("data", JSONObject().put("getFrontConfig", "{\"recharge\":\"401\"}").put("getEcardConfig", "{\"type\":\"1\"}"))
                "/charge/feeitem/singleFeeitem" -> JSONObject().put("code", 200).put("feeitem", JSONObject().put("layout", "10,50,100").put("flag", "1100000000"))
                else -> cards()
            }
            FakeHttps(url, 200, payload.toString()).also(calls::add)
        }
        val c = api.rechargeConfiguration("fake-token", "fake-account")
        assertEquals(listOf(1000L, 5000L, 10000L), c.amountsCents)
        assertEquals("fake-account", c.targetId)
        assertEquals(2000, c.balanceCents)
        assertTrue(c.allowCustom)
        assertEquals("recharge", params(calls.last().url.query)["scene"])
        assertTrue(calls.all { it.disconnected && it.getRequestProperty("Cookie") == null && it.getRequestProperty("synjones-auth") == "bearer fake-token" })
    }
    @Test fun createUsesSignedPostAndDoesNotFollowTokenRedirect() = runBlocking {
        lateinit var call: FakeHttps
        val api = RechargeApi { url -> FakeHttps(url, 302, "", "https://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytypeid=81&paytype=CAMPUSCARD&token=fake-token").also { call = it } }
        val o = api.createRecharge("fake-token", config(), 1000)
        assertEquals("FAKE-ORDER", o.orderId)
        assertEquals("POST", call.requestMethod)
        assertFalse(call.instanceFollowRedirects)
        assertNull(call.url.query)
        val p = params(call.sent.toString("UTF-8"))
        assertEquals("10.00", p["tranamt"])
        assertEquals("h5", p["synAccessSource"])
        assertEquals("bearer fake-token", p["synjones-auth"])
        assertEquals("fake-account", p["yktcard"])
        assertEquals(64, p.getValue("SIGN").length)
        assertTrue(call.disconnected)
    }
    @Test fun h5FormBridgeNeverForwardsSchoolCredentialsOrExecutesJs() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val back = URLEncoder.encode("https://yktwx2.huel.edu.cn/payment/?name=result&orderid=FAKE-ORDER&payid=81", "UTF-8")
        val action = "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fake-prepay&ct=1&sign=fake-sign&package=fake&redirect_url=" + back
        val form = "<form method='POST' action='" + action + "'><input name='' value='' type='hidden'></form><script>doNotExecute()</script>"
        val api = RechargeApi { url ->
            val text = when (url.path) {
                "/charge/pay/getpayinfo" -> JSONObject().put("code", 200).put("order", JSONObject().put("orderid", "FAKE-ORDER").put("tranamt", 10)).put("payList", JSONArray().put(JSONObject().put("payid", 81).put("code", "CAMPUSCARD").put("remark", "weixin"))).toString()
                "/blade-pay/pay" -> JSONObject().put("code", 200).put("data", JSONObject().put("orderid", "FAKE-ORDER").put("paysubmit", form)).toString()
                else -> "<script>var url='weixin://wap/pay?prepayid%3Dfake-prepay&package=fake&noncestr=fake-nonce&sign=fake-sign';</script>"
            }
            FakeHttps(url, 200, text).also(calls::add)
        }
        val launch = api.launchRecharge("fake-token", pending())
        assertTrue(launch.uri.startsWith("weixin://wap/pay?"))
        val p = params(calls[1].sent.toString("UTF-8"))
        assertEquals("h5", p["userAgent"])
        val unsigned = p.filterKeys { it !in listOf("APP_ID", "TIMESTAMP", "SIGN_TYPE", "NONCE", "SIGN", "synAccessSource") }
        assertEquals(p["SIGN"], RechargeProtocol.signed(unsigned, p.getValue("TIMESTAMP"), p.getValue("NONCE"))["SIGN"])
        val bridge = calls.last()
        assertEquals("wx.tenpay.com", bridge.url.host)
        assertEquals("POST", bridge.requestMethod)
        assertEquals(0, bridge.sent.size())
        assertNull(bridge.getRequestProperty("synjones-auth"))
        assertNull(bridge.getRequestProperty("synAccessSource"))
        assertNull(bridge.getRequestProperty("Cookie"))
        assertEquals("https://yktwx2.huel.edu.cn/", bridge.getRequestProperty("Referer"))
        assertTrue(calls.all { it.disconnected && !it.instanceFollowRedirects })
    }
    @Test fun resultRequiresMatchingOrderAmountAndNestedFeeitem() = runBlocking {
        val api = RechargeApi { url -> FakeHttps(url, 200, if (url.path.endsWith("queryCard")) cards().toString() else result(1).toString()) }
        val paid = api.rechargeResult("fake-token", pending())
        assertTrue(paid.paid)
        assertEquals(1000, paid.amountCents)
        assertNull(paid.balanceCents)
        val unpaid = RechargeApi { url -> FakeHttps(url, 200, result(0).toString()) }.rechargeResult("fake-token", pending())
        assertFalse(unpaid.paid)
        try {
            RechargeApi { url -> FakeHttps(url, 200, result(1, 20).toString()) }.rechargeResult("fake-token", pending())
            fail("Mismatched amount")
        } catch (_: YktException) { }
    }
    @Test fun ambiguousCreationIsNotRetried() = runBlocking {
        var calls = 0
        val api = RechargeApi { url -> calls++; FakeHttps(url, 200, "{}") }
        try { api.createRecharge("fake-token", config(), 1000); fail("Expected unknown outcome") } catch (_: YktException) { }
        assertEquals(1, calls)
    }
}
