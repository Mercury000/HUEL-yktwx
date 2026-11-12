package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class RechargeApiTest {
    private class FakeResponse(url: URL, private val status: Int, private val body: String, private val location: String? = null) : HttpFixture(url) {
        val sent = ByteArrayOutputStream()
        var disconnected = false

        override fun close() { disconnected = true }

        override fun statusCode() = status
        override fun bodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun errorBodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream() = sent
        override fun header(name: String?) = if (name.equals("Location", true)) location else null

    }
    private fun pending() = PendingRecharge("123456", "fake-account", "401", 1000, "FAKE-ORDER", "81")
    private fun config() = RechargeConfiguration("401", "fake-account", listOf(1000, 5000, 10000), 1, 50000, true, false)
    private fun params(text: String) = text.split('&').filter { it.isNotEmpty() }.associate {
        val p = it.split('=', limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    private fun cards() = jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (jsonArrayOf(listOf(jsonObjectOf("account" to ("fake-card"), "lostflag" to (0), "freezeflag" to (0), "accinfo" to (jsonArrayOf(listOf(jsonObjectOf("type" to ("fake-account"), "name" to ("统一账户"), "balance" to (2000))))))))))))
    private fun result(status: Int, amount: Int = 10) = jsonObjectOf("code" to (200), "orderList" to (jsonArrayOf(listOf(jsonObjectOf("orderid" to ("FAKE-ORDER"), "payid" to (81), "third_party" to ("fake-account"), "tranamt" to (amount), "actulamt" to (amount), "status" to (status), "feeitemid" to (0), "feeitemlist" to (jsonArrayOf(listOf(jsonObjectOf("feeitemid" to (401))))))))))
    @Test fun configurationReadsTopLevelFeeAndMapsElectronicAccount() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureRechargeApi { url ->
            val payload = when (url.path) {
                "/berserker-app/frontInfo" -> jsonObjectOf("code" to (200), "data" to (jsonObjectOf("getFrontConfig" to ("{\"recharge\":\"401\"}"), "getEcardConfig" to ("{\"type\":\"1\"}"))))
                "/charge/feeitem/singleFeeitem" -> jsonObjectOf("code" to (200), "feeitem" to (jsonObjectOf("layout" to ("10,50,100"), "flag" to ("1100000000"))))
                else -> cards()
            }
            FakeResponse(url, 200, payload.toString()).also(calls::add)
        }
        val c = api.rechargeConfiguration("fake-token", "fake-account")
        assertEquals(listOf(1000L, 5000L, 10000L), c.amountsCents)
        assertEquals("fake-account", c.targetId)
        assertTrue(c.allowCustom)
        assertNull(c.minimumCents)
        assertNull(c.maximumCents)
        assertNull(c.amountError(80_000)) // No invented 500 yuan ceiling when the config is silent.
        assertEquals("recharge", params(calls.last().url.query)["scene"])
        assertTrue(calls.all { it.disconnected && it.request.header("Cookie") == null && it.request.header("synjones-auth") == "bearer fake-token" })
        val front = jsonObjectOf("rechargeMaxAmount" to ("600"))
        val fee = jsonObjectOf("retain_money" to ("1"), "maxmoney" to ("800"), "flag" to ("1100000200"))
        val limited = RechargeApi.amountConfiguration("401", "fake-account", front, fee)
        assertEquals(100L, limited.minimumCents)
        assertEquals(80_000L, limited.maximumCents)
        assertTrue(limited.integerOnly)
        assertEquals("单次最高 ¥ 800", limited.amountError(80_001))
        assertEquals(60_000L, RechargeApi.amountConfiguration("401", "fake-account", front, fee.withField("maxmoney", null)).maximumCents)
    }
    @Test fun createUsesSignedPostAndDoesNotFollowTokenRedirect() = runBlocking {
        lateinit var call: FakeResponse
        val api = fixtureRechargeApi { url ->
            FakeResponse(url, 302, "", "https://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytypeid=81&paytype=CAMPUSCARD&token=fake-token").also { call = it }
        }
        val o = api.createRecharge("fake-token", config(), 1000)
        assertEquals("FAKE-ORDER", o.orderId)
        assertEquals("POST", call.request.method)
        assertFalse(AppHttp.service.followRedirects)
        assertNull(call.url.query)
        val p = params(call.sent.toString("UTF-8"))
        assertEquals("10.00", p["tranamt"])
        assertEquals("h5", p["synAccessSource"])
        assertEquals("bearer fake-token", p["synjones-auth"])
        assertEquals("fake-account", p["yktcard"])
        assertEquals(64, p.getValue("SIGN").length)
        assertTrue(call.disconnected)
    }
    @Test fun creationErrorRedirectShowsOfficialLimitWithoutFollowingOrRetrying() = runBlocking {
        val message = "金额大于每笔交易允许的最大金额800元"
        val encoded = URLEncoder.encode(URLEncoder.encode(message, "UTF-8"), "UTF-8")
        val location = RechargeProtocol.SCHOOL + "/plat/notfound?msg=" + encoded
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureRechargeApi { url -> FakeResponse(url, 302, "", location).also(calls::add) }
        val config = RechargeConfiguration("401", "fake-account", emptyList(), null, null, true, false)
        val error = assertThrows(RechargePaymentRejected::class.java) {
            runBlocking { api.createRecharge("fake-token", config, 80_001) }
        }
        assertEquals(message, error.message)
        assertEquals(1, calls.size)
        assertFalse(AppHttp.service.followRedirects)
        // Untrusted/malformed locations remain ambiguous, never a classified business rejection.
        for (invalid in listOf(location.replace("yktwx2.huel.edu.cn", "example.invalid"),
            location.replace("/plat/notfound", "/payment/"), location + "&orderid=OTHER")) {
            RechargeErrors.creationFailure(PaymentHttp.Response(302, "", invalid))
        }
    }
    @Test fun h5FormBridgeNeverForwardsSchoolCredentialsOrExecutesJs() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val back = URLEncoder.encode("https://yktwx2.huel.edu.cn/payment/?name=result&orderid=FAKE-ORDER&payid=81", "UTF-8")
        val action = "https://wx.tenpay.com/cgi-bin/mmpayweb-bin/checkmweb?prepay_id=fake-prepay&ct=1&sign=fake-sign&package=fake&redirect_url=" + back
        val form = "<form method='POST' action='" + action + "'><input name='' value='' type='hidden'></form><script>doNotExecute()</script>"
        val api = fixtureRechargeApi { url ->
            val text = when (url.path) {
                "/charge/pay/getpayinfo" -> jsonObjectOf("code" to (200), "order" to (jsonObjectOf("orderid" to ("FAKE-ORDER"), "tranamt" to (10))), "payList" to (jsonArrayOf(listOf(jsonObjectOf("payid" to (81), "code" to ("CAMPUSCARD"), "remark" to ("weixin")))))).toString()
                "/blade-pay/pay" -> jsonObjectOf("code" to (200), "data" to (jsonObjectOf("orderid" to ("FAKE-ORDER"), "paysubmit" to (form)))).toString()
                else -> "<script>var url='weixin://wap/pay?prepayid%3Dfake-prepay&package=fake&noncestr=fake-nonce&sign=fake-sign';</script>"
            }
            FakeResponse(url, 200, text).also(calls::add)
        }
        val launch = api.launchRecharge("fake-token", pending())
        assertTrue(launch.uri.startsWith("weixin://wap/pay?"))
        val p = params(calls[1].sent.toString("UTF-8"))
        assertEquals("h5", p["userAgent"])
        val unsigned = p.filterKeys { it !in listOf("APP_ID", "TIMESTAMP", "SIGN_TYPE", "NONCE", "SIGN", "synAccessSource") }
        assertEquals(p["SIGN"], RechargeProtocol.signed(unsigned, p.getValue("TIMESTAMP"), p.getValue("NONCE"))["SIGN"])
        val bridge = calls.last()
        assertEquals("wx.tenpay.com", bridge.url.host)
        assertEquals("POST", bridge.request.method)
        assertEquals(0, bridge.sent.size())
        assertNull(bridge.request.header("synjones-auth"))
        assertNull(bridge.request.header("synAccessSource"))
        assertNull(bridge.request.header("Cookie"))
        assertEquals("https://yktwx2.huel.edu.cn/", bridge.request.header("Referer"))
        assertTrue(calls.all { it.disconnected && !AppHttp.service.followRedirects })
    }
    @Test fun resultRequiresMatchingOrderAmountAndNestedFeeitem() = runBlocking {
        val api = fixtureRechargeApi { url -> FakeResponse(url, 200, if (url.path.endsWith("queryCard")) cards().toString() else result(1).toString()) }
        val paid = api.rechargeResult("fake-token", pending())
        assertTrue(paid.paid)
        val unpaid = fixtureRechargeApi { url -> FakeResponse(url, 200, result(0).toString()) }.rechargeResult("fake-token", pending())
        assertFalse(unpaid.paid)
        for (status in listOf(0, 2, 9)) {
            val response = result(status).let { it.withField("orderList",
                jsonArrayOf(listOf(it.arrayValue("orderList").objectValue(0).withField("msg", "后端订单提示")))) }
            val checked = fixtureRechargeApi { url -> FakeResponse(url, 200, response.toString()) }.rechargeResult("fake-token", pending())
            assertEquals("后端订单提示", checked.message)
            assertEquals(status, checked.serverStatus)
            assertFalse(checked.paid)
        }
        val conflictingFields = listOf<Pair<String, Any>>(
            "orderid" to "OTHER-ORDER",
            "third_party" to "other-account",
            "payid" to 99,
            "tranamt" to 20,
            "actulamt" to 20,
            "feeitemlist" to jsonArrayOf(listOf(jsonObjectOf("feeitemid" to (999)))),
        )
        for ((field, value) in conflictingFields) {
            val response = result(1).let { it.withField("orderList",
                jsonArrayOf(listOf(it.arrayValue("orderList").objectValue(0).withField(field, value)))) }
            try {
                fixtureRechargeApi { url -> FakeResponse(url, 200, response.toString()) }.rechargeResult("fake-token", pending())
                fail("Successful response must match the submitted $field")
            } catch (_: YktException) { }
        }
    }
    @Test fun ambiguousCreationIsNotRetried() = runBlocking {
        var calls = 0
        val api = fixtureRechargeApi { url ->
            calls++; FakeResponse(url, 200, "{}")
        }
        try { api.createRecharge("fake-token", config(), 1000); fail("Expected unknown outcome") } catch (_: YktException) { }
        assertEquals(1, calls)
    }
    @Test fun othersRechargeSignsRecipientTargetAndRetainsMetadataInPendingOrder() = runBlocking {
        lateinit var call: FakeResponse
        val recipient = RechargeRecipient("other-card", "other-target", "示例同学", "202600000002", "统一账户")
        val config = RechargeConfiguration("401", "other-target", listOf(1000), 1, 50000, true, false, recipient)
        val api = fixtureRechargeApi { url -> FakeResponse(url, 302, "", "https://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytypeid=81&paytype=CAMPUSCARD").also { call = it } }
        api.createRecharge("fake-token", config, 1000)
        val p = params(call.sent.toString("UTF-8"))
        assertEquals("other-target", p["yktcard"])
        assertTrue(parseObject(p.getValue("abstracts")).booleanValue("othersRecharge"))
        assertEquals("recharge", parseObject(p.getValue("abstracts")).stringValue("type"))
        assertEquals("10.00", p["tranamt"]); assertEquals(64, p.getValue("SIGN").length)
        val pending = PendingRecharge("payer", config.targetId, "401", 1000, recipient = recipient).withOrder("FAKE-ORDER", "81")
        assertSame(recipient, pending.recipient); assertEquals("other-target", pending.targetId)
    }

    @Test fun lostRechargeIsRefusedBeforeAnyExternalBridgeForOwnAndOtherTargets() = runBlocking {
        for (other in listOf(false, true)) {
            val recipient = RechargeRecipient("fake-card", "fake-account", "示例", "123456", "统一账户")
            val pending = PendingRecharge("123456", "fake-account", "401", 1000, "FAKE-ORDER", "81", if (other) recipient else null)
            val calls = mutableListOf<FakeResponse>()
            val api = fixtureRechargeApi { url ->
                val text = when (url.path) {
                    "/charge/pay/getpayinfo" -> jsonObjectOf("code" to (200), "order" to (jsonObjectOf("orderid" to ("FAKE-ORDER"), "third_party" to ("fake-account"), "tranamt" to (10))), "payList" to (jsonArrayOf(listOf(jsonObjectOf("payid" to (81), "code" to ("CAMPUSCARD"), "remark" to ("weixin")))))).toString()
                    "/blade-pay/pay" -> "{\"code\":60007,\"success\":false,\"data\":null,\"msg\":\"账户已挂失\"}"
                    else -> error("No external payment bridge is allowed")
                }
                FakeResponse(url, 200, text).also(calls::add)
            }
            assertThrows(RechargePaymentRejected::class.java) { runBlocking { api.launchRecharge("fake-token", pending) } }
            assertEquals(2, calls.size)
            assertEquals(1, calls.count { it.request.method == "POST" })
            assertTrue(calls.all { it.url.host == "yktwx2.huel.edu.cn" && it.disconnected })
        }
    }

    @Test fun officialCashierRejectsNon200CodesRegardlessOfSuccessDataOrState() {
        for (body in listOf("""{"code":60007,"success":true,"data":null}""",
            """{"code":60007,"success":false,"data":"FAKE-ORDER"}""",
            """{"code":60007,"state":200}""", """{"code":60007}""")) {
            assertThrows(RechargePaymentRejected::class.java) {
                RechargeErrors.paymentFailure(PaymentHttp.Response(200, body, null))
            }
        }
        RechargeErrors.paymentFailure(PaymentHttp.Response(200,
            """{"code":200,"data":{"retcode":"60007"}}""", null))
        val auth = assertThrows(AuthenticationRequired::class.java) {
            RechargeErrors.paymentFailure(PaymentHttp.Response(200,
                """{"code":401,"msg":"后端登录提示"}""", null))
        }
        assertEquals("后端登录提示", auth.message)
        val httpError = assertThrows(YktException::class.java) {
            RechargeErrors.paymentFailure(PaymentHttp.Response(500,
                """{"code":60007,"msg":"后端服务错误"}""", null))
        }
        assertFalse(httpError is RechargePaymentRejected)
        assertEquals("后端服务错误", httpError.message)
    }

    @Test fun creationDoesNotAddAnUnofficialAccountPrecheck() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureRechargeApi { url ->
            assertEquals("/charge/order/thirdOrder", url.path)
            FakeResponse(url, 302, "", "https://yktwx2.huel.edu.cn/payment/?orderid=FAKE-ORDER&paytypeid=81&paytype=CAMPUSCARD").also(calls::add)
        }
        api.createRecharge("fake-token", config(), 1000)
        assertEquals(1, calls.size)
        assertEquals("POST", calls.single().request.method)
    }

    @Test fun lostPaymentKeepsOriginalMessageAndOfficialEmptyFallback() {
        for (text in listOf("账户已挂失", "  服务端提示\n", "<b>业务提示</b>", "提示".repeat(160), "", null)) {
            val body = jsonObjectOf("code" to (60007), "success" to (false), "data" to (JsonNull), "msg" to (text ?: JsonNull))
            val error = assertThrows(RechargePaymentRejected::class.java) {
                RechargeErrors.paymentFailure(PaymentHttp.Response(200, body.toString(), null))
            }
            assertEquals(text?.takeIf { it.isNotEmpty() } ?: "Error", error.message)
        }
    }

    @Test fun explicitBusinessFailureDoesNotNeedAnErrorCodeAllowlist() {
        for (code in listOf(500, 99999)) {
            val body = "{\"code\":$code,\"success\":false,\"data\":null,\"msg\":\"新的业务提示\"}"
            val response = PaymentHttp.Response(200, body, null)
            val creation = assertThrows(RechargePaymentRejected::class.java) { RechargeErrors.creationFailure(response) }
            val payment = assertThrows(RechargePaymentRejected::class.java) { RechargeErrors.paymentFailure(response) }
            assertEquals("新的业务提示", creation.message)
            assertEquals(creation.message, payment.message)
        }
    }

    @Test fun officialRechargeSuccessPredicateUsesCodeOnly() = runBlocking {
        val http = PaymentHttp { error("No network allowed") }
        assertNotNull(http.json(PaymentHttp.Response(200,
            """{"code":200,"success":false}""", null), rechargePayment = true))
    }

    @Test fun ownRechargeSelectionHonorsOfficialLossAndFreezeConfiguration() = runBlocking {
        for ((key, blocked) in listOf("showLost" to "已挂失", "freezeRecharge" to "已冻结")) {
            for (allow in listOf(true, false)) {
                val response = cards().let { body ->
                    val data = body.objectValue("data")
                    body.withField("data", data.withField("card", jsonArrayOf(listOf(data.arrayValue("card").objectValue(0)
                        .withField(if (key == "showLost") "lostflag" else "freezeflag", 1)))))
                }
                val settings = jsonObjectOf("type" to ("1"), key to (if (allow) "1" else "0"))
                val api = fixtureRechargeApi { url ->
                    val payload = when (url.path) {
                        "/berserker-app/frontInfo" -> jsonObjectOf("code" to (200), "data" to (jsonObjectOf("getFrontConfig" to ("{\"recharge\":\"401\"}"), "getEcardConfig" to (settings.toString()))))
                        "/charge/feeitem/singleFeeitem" -> jsonObjectOf("code" to (200), "feeitem" to (jsonObjectOf("layout" to ("10"), "flag" to ("1100000000"))))
                        else -> response
                    }
                    FakeResponse(url, 200, payload.toString())
                }
                if (allow) assertEquals("fake-account", api.rechargeConfiguration("fake-token", "fake-card").targetId)
                else {
                    val error = assertThrows(RechargeTargetUnavailable::class.java) { runBlocking { api.rechargeConfiguration("fake-token", "fake-card") } }
                    assertTrue(error.message!!.contains(blocked))
                }
            }
        }
    }

    @Test fun officialFreezeOverrideAndMissingFlagsAreNotGuessed() {
        val card = jsonObjectOf("lostflag" to (1), "freezeflag" to (1), "acc_status" to (99))
        val error = assertThrows(RechargeTargetUnavailable::class.java) {
            RechargeErrors.requireAvailable(card, jsonObjectOf("showLost" to ("0"), "freezeRecharge" to ("0")))
        }
        assertEquals("已冻结，禁止充值，请先解冻", error.message)
        RechargeErrors.requireAvailable(card, jsonObjectOf("showLost" to ("1"))) // Missing freeze setting does not block.
        RechargeErrors.requireAvailable(card, jsonObjectOf("showLost" to ("1"), "freezeRecharge" to ("1")))
    }

}
