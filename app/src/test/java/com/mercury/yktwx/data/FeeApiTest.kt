package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLDecoder

class FeeApiTest {
    private class FakeResponse(url: URL, private val body: String, private val status: Int = 200,
        private val location: String? = null) : HttpFixture(url) {

        override fun close() {}

        val sent = ByteArrayOutputStream()
        override fun statusCode() = status
        override fun header(name: String?) = if (name.equals("Location", true)) location else null
        override fun bodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun errorBodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream() = sent

    }

    private fun query(vararg data: Pair<String, String>) = runBlocking {
        val body = jsonObjectOf("code" to 200, "map" to jsonObjectOf("data" to jsonObjectOf(*data)))
        fixtureFeeApi { url -> FakeResponse(url, body.toString()) }.feeQuery("fake-token", FeeProject.Zhengdong, 4, emptyMap(), true)
    }

    @Test fun detailTargetAddsNamedRoomInfo() {
        val target = parseObject(query(
            "area" to "1", "buildingName" to "示例1号楼", "areaName" to "示例校区",
            "extdata" to "", "floorName" to "1层", "floor" to "demo-floor", "aid" to "demo-aid",
            "account" to "demo-account", "building" to "demo-building", "room" to "demo-room", "roomName" to "示例1-101空调",
        ).thirdParty!!)
        assertEquals("房间：示例校区 示例1号楼 1层 示例1-101空调", target.stringValue("myCustomInfo"))
    }

    @Test fun detailTargetAddsIdValueRoomInfo() {
        val target = parseObject(query(
            "room_id" to "demo-room&10号楼1单元001水表", "allAmp" to "100.00",
            "xiaoqu_id" to "demo-campus&示例校区", "usedAmp" to "50.00",
            "loudong_id" to "demo-building&10号楼", "louceng_id" to "demo-floor&10号楼1单元",
        ).thirdParty!!)
        assertEquals("房间：示例校区 10号楼 10号楼1单元 10号楼1单元001水表", target.stringValue("myCustomInfo"))
    }

    @Test fun detailTargetKeepsServerRoomInfo() {
        val target = parseObject(query("roomName" to "示例1-101空调", "myCustomInfo" to "服务端摘要").thirdParty!!)
        assertEquals("服务端摘要", target.stringValue("myCustomInfo"))
    }

    private fun roomConfig(project: FeeProject, status: Int = 1, bindInfo: Int = 1, scene: String? = null): String =
        jsonObjectOf("code" to (200), "sceneinfo" to (scene ?: JsonNull), "feeitem" to (jsonObjectOf("feeitemid" to (project.id.toInt()), "name" to ("示例缴费"), "flag" to ("0000000000"), "bindStatus" to (status), "feeitemImplBean" to (jsonObjectOf("bindinfo" to (bindInfo)))))).toString()

    @Test fun bindingConfigurationIsDynamicAcrossAllSupportedProjects() = runBlocking {
        val scene = "xiaoqu_id:demo-campus&示例校区#\$#示例校区;room_id:demo-room&示例水表#\$#示例水表"
        for (project in listOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
            val calls = mutableListOf<FakeResponse>()
            val api = fixtureFeeApi { url -> FakeResponse(url, roomConfig(project, scene = scene)).also(calls::add) }
            val config = api.feeConfiguration("fake-token", project)
            assertTrue(config.roomBindingEnabled)
            assertNull(config.amountRules.minimumCents)
            assertNull(config.amountRules.maximumCents)
            assertNull(config.amountRules.amountError(1_000_000))
            assertEquals("demo-room&示例水表", config.roomBinding?.selections?.get("room_id"))
            assertEquals(project.id, URLDecoder.decode(calls.single().url.query, "UTF-8").substringAfter("feeitemid=").substringBefore('&'))
        }
    }
    @Test fun amountBoundsAndIntegerModeUseReturnedFieldsNotProjectIds() = runBlocking {
        var item = jsonObjectOf("feeitemid" to (448), "retain_money" to ("1"), "maxmoney" to ("1000"), "flag" to ("1100000000"), "layout" to ("50,100,200,500"))
        val api = fixtureFeeApi { url -> FakeResponse(url, jsonObjectOf("code" to (200), "feeitem" to (item)).toString()) }
        val rules = api.feeConfiguration("fake-token", FeeProject.FamilyElectric).amountRules
        assertEquals(100L, rules.minimumCents)
        assertEquals(100_000L, rules.maximumCents)
        assertFalse(rules.integerOnly)
        assertNull(rules.amountError(101))
        assertEquals("最低充值 ¥ 1", rules.amountError(99))
        assertEquals("单次最高 ¥ 1000", rules.amountError(100_001))
        item = item.withField("flag", "1100000200").withField("maxmoney", JsonNull)
        val changed = api.feeConfiguration("fake-token", FeeProject.FamilyElectric).amountRules
        assertTrue(changed.integerOnly)
        assertNull(changed.maximumCents)
        assertEquals("仅支持整数金额", changed.amountError(101))
    }
    @Test fun disabledAndMultiRoomConfigurationsDoNotEnableSingleRoomActions() = runBlocking {
        for ((project, status, info) in listOf(Triple(FeeProject.Commercial, 0, 0),
            Triple(FeeProject.Wenbei, 2, 1), Triple(FeeProject.Network, 1, 1))) {
            val config = fixtureFeeApi { url -> FakeResponse(url, roomConfig(project, status, info)) }.feeConfiguration("fake-token", project)
            assertFalse(config.roomBindingEnabled)
            assertNull(config.roomBinding)
        }
    }
    @Test fun malformedSavedRoomIsNotTreatedAsUnbound() = runBlocking {
        val config = fixtureFeeApi { url -> FakeResponse(url, roomConfig(FeeProject.Wenbei, scene = "room:x;room:y")) }
            .feeConfiguration("fake-token", FeeProject.Wenbei)
        assertTrue(config.roomBindingEnabled)
        assertTrue(config.invalidRoomBinding)
        assertNull(config.roomBinding)
    }
    @Test fun saveAndUnbindUseUtf8JsonProjectIdAndNoSceneOnUnbind() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureFeeApi { url -> FakeResponse(url, "{\"code\":200}").also(calls::add) }
        val room = FeeRoomBinding.parse("room_id:demo-room&示例水表#\$#示例水表")!!
        api.setFeeRoom("fake-token", FeeProject.FamilyWater, room)
        api.setFeeRoom("fake-token", FeeProject.FamilyWater, null)
        assertEquals(2, calls.size)
        calls.forEach {
            assertEquals("/charge/sceneBind/add", it.url.path)
            assertEquals("POST", it.request.method)
            assertEquals("application/json;charset=UTF-8", it.request.header("Content-Type"))
            assertEquals("bearer fake-token", it.request.header("synjones-auth"))
            assertTrue(it.request.header("Authorization")!!.startsWith("Basic "))
            assertNull(it.request.header("Cookie"))
            assertFalse(AppHttp.service.followRedirects)
            assertEquals(449, parseObject(it.sent.toString("UTF-8")).intValue("feeitemid"))
        }
        assertEquals(room.sceneInfo(), parseObject(calls.first().sent.toString("UTF-8")).stringValue("sceneinfo"))
        assertEquals(setOf("feeitemid"), parseObject(calls.last().sent.toString("UTF-8")).keys.iterator().asSequence().toSet())
    }
    @Test fun bindingRejectionIsNotRetriedOrMistakenForPayment() = runBlocking {
        var requests = 0
        val api = fixtureFeeApi { url -> requests++; FakeResponse(url, "{\"code\":500,\"msg\":\"失败\"}") }
        try { api.setFeeRoom("fake-token", FeeProject.Wenbei, null); fail("Expected rejection") }
        catch (_: YktException) { }
        assertEquals(1, requests)
    }

    @Test fun creationPreservesBackendRejectionsAcrossProjectsWithoutInventingBounds() = runBlocking {
        val message = "金额大于每笔交易允许的最大金额300元"
        val location = "/plat/notfound?msg=" + java.net.URLEncoder.encode(java.net.URLEncoder.encode(message, "UTF-8"), "UTF-8")
        for (project in FeeProject.entries) for (redirect in listOf(false, true)) {
            val calls = mutableListOf<FakeResponse>()
            val api = fixtureFeeApi { url -> FakeResponse(url,
                "{\"code\":500,\"success\":false,\"data\":null,\"msg\":\"$message\"}",
                if (redirect) 302 else 200, if (redirect) location else null).also(calls::add) }
            val rules = RechargeConfiguration(project.id, "", emptyList(), null, null, true, false)
            val error = assertThrows(FeePaymentRejected::class.java) {
                runBlocking { api.createFee("fake-token", "test-user", FeeConfiguration(project, rules),
                    FeeQuery(thirdParty = "{}"), 99_999_900) }
            }
            assertEquals(message, error.message)
            assertEquals(FeeRejectionReason.Other, error.reason)
            assertEquals(listOf("/blade-pay/pay"), calls.map { it.url.path })
            assertEquals("0", fields(calls.single())["paystep"])
            assertFalse(AppHttp.service.followRedirects)
            assertNull(rules.maximumCents)
        }
    }

    private fun pending() = PendingRecharge("test-user", "{\"room\":\"demo-room\"}", "410", 100, "FAKE-ORDER", "64")
    private fun method() = FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)
    private fun preparation() = FeeAccountPreparation("FAKE-ORDER", "64", listOf(FeeAccount("demo-type", "统一账户", 1000)), "fake-uuid", "9876543210")
    private fun orderInfo(expiry: String = "2026-10-21 12:30:00", status: Int = 0) = jsonObjectOf("code" to (200), "order" to (jsonObjectOf("orderid" to ("FAKE-ORDER"), "third_party" to (pending().targetId), "tranamt" to (1), "status" to (status), "payexpdate" to (expiry), "feeitemlist" to (jsonArrayOf(listOf(jsonObjectOf("feeitemid" to (410))))))), "payList" to (jsonArrayOf(listOf(jsonObjectOf("payid" to ("64"), "code" to ("ACCOUNTTSM"), "nopassword" to (0))))))
    private fun api(calls: MutableList<FakeResponse>, payment: String, expiry: String = "2026-10-21 12:30:00",
        serverTime: String = "2026-10-21 12:00:00", status: Int = 0, timeHttpStatus: Int = 200) = fixtureFeeApi { url ->
        val (body, httpStatus) = when (url.path) {
            "/charge/pay/getpayinfo" -> orderInfo(expiry, status).toString() to 200
            "/charge/order/getCurrentTime" -> jsonObjectOf("code" to (200), "currentTime" to (serverTime)).toString() to timeHttpStatus
            "/blade-pay/pay" -> payment to 200
            else -> error("Unexpected URL")
        }
        FakeResponse(url, body, httpStatus).also(calls::add)
    }
    private fun fields(call: FakeResponse) = call.sent.toString("UTF-8").split('&').associate {
        val key = it.substringBefore('='); val value = it.substringAfter('=')
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    @Test fun passwordRejectionPreservesOfficialMessageAndNeverReplaysTheRequest() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = api(calls, jsonObjectOf("code" to (60005), "success" to (false), "data" to (JsonNull), "msg" to ("账户密码错误")).toString())
        try {
            api.payFeeAccount("fake-token", pending(), method(), preparation(), "demo-type", "123456")
            fail("Expected rejection")
        } catch (e: FeePaymentRejected) {
            assertEquals(FeeRejectionReason.Password, e.reason)
            assertEquals("账户密码错误", e.message)
        }
        assertEquals(listOf("/charge/pay/getpayinfo", "/charge/order/getCurrentTime", "/blade-pay/pay"), calls.map { it.url.path })
        assertEquals("876543", fields(calls.last())["password"])
        assertEquals("fake-uuid", fields(calls.last())["uuid"])
        assertFalse(calls.last().sent.toString("UTF-8").contains("123456"))
    }

    @Test fun expiredAndMalformedTimesNeverSubmitPassword() = runBlocking {
        for ((expiry, now) in listOf("2026-10-21 12:00:00" to "2026-10-21 12:00:00",
            "2026-10-21 11:59:59" to "2026-10-21 12:00:00", "2026-02-30 12:00:00" to "2026-10-21 12:00:00",
            "" to "2026-10-21 12:00:00", "2026-10-21 12:30:00" to "not-a-time")) {
            val calls = mutableListOf<FakeResponse>()
            try {
                api(calls, "{}", expiry, now).payFeeAccount("fake-token", pending(), method(), preparation(), "demo-type", "123456")
                fail("Expected expiry or validation failure")
            } catch (e: YktException) { assertTrue(e is FeeOrderExpired || e is FeePaymentNotStarted) }
            assertTrue(calls.none { it.url.path == "/blade-pay/pay" })
        }
    }

    @Test fun failedPreflightAndAlreadyPaidNeverSubmitPassword() = runBlocking {
        for ((status, httpStatus) in listOf(0 to 503, 1 to 200, 2 to 200)) {
            val calls = mutableListOf<FakeResponse>()
            try {
                api(calls, "{}", status = status, timeHttpStatus = httpStatus)
                    .payFeeAccount("fake-token", pending(), method(), preparation(), "demo-type", "123456")
                fail("Expected no submission")
            } catch (e: YktException) { assertTrue(e is FeePaymentNotStarted || e is FeeOrderAlreadyPaid || e is FeeOrderExpired) }
            assertTrue(calls.none { it.url.path == "/blade-pay/pay" })
        }
    }

    @Test fun officialCashierUsesCodeAndStateWithoutAnErrorAllowlist() = runBlocking {
        val http = PaymentHttp { error("No requests allowed") }
        for (body in listOf(
            """{"code":500,"msg":"金额超出交易限额"}""",
            """{"code":999,"msg":"新的业务提示"}""",
            """{"code":400,"success":true,"data":"FAKE-ORDER","msg":"后端拒绝"}""",
            """{"code":60005,"msg":"任意语言的提示"}""",
        )) {
            val rejected = assertThrows(FeePaymentRejected::class.java) {
                runBlocking { http.json(PaymentHttp.Response(200, body, null), feePayment = true) }
            }
            assertEquals(parseObject(body).stringValue("msg"), rejected.message)
        }
        // Exact success branches in the official old cashier's ge().
        for (body in listOf(
            """{"code":200,"success":false}""", """{"code":302}""",
            """{"code":60005,"state":200}""", """{"state":200}""", "{}",
        )) {
            assertNotNull(http.json(PaymentHttp.Response(200, body, null), feePayment = true))
        }
        for ((status, body) in listOf(503 to """{"code":400,"msg":"服务不可用"}""", 200 to "not-json")) {
            val error = assertThrows(YktException::class.java) {
                runBlocking { http.json(PaymentHttp.Response(status, body, null), feePayment = true) }
            }
            assertFalse(error is FeePaymentRejected)
        }
        val auth = assertThrows(AuthenticationRequired::class.java) {
            runBlocking { http.json(PaymentHttp.Response(200, """{"code":401,"msg":"后端登录提示"}""", null), feePayment = true) }
        }
        assertEquals("后端登录提示", auth.message)
    }

    @Test fun manualPasswordCorrectionRechecksServerExpiryBeforeAnotherPayment() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        var expired = false
        val api = fixtureFeeApi { url ->
            val body = when (url.path) {
                "/charge/pay/getpayinfo" -> orderInfo().toString()
                "/charge/order/getCurrentTime" -> jsonObjectOf("code" to (200), "currentTime" to (if (expired) "2026-10-21 12:30:00" else "2026-10-21 12:00:00")).toString()
                "/blade-pay/pay" -> jsonObjectOf("code" to (60005), "success" to (false), "data" to (JsonNull), "msg" to ("账户密码错误")).toString()
                else -> error("Unexpected URL")
            }
            FakeResponse(url, body).also(calls::add)
        }
        val keyboard = preparation()
        try {
            api.payFeeAccount("fake-token", pending(), method(), keyboard, "demo-type", "123456")
            fail("Expected password rejection")
        } catch (e: FeePaymentRejected) { assertEquals(FeeRejectionReason.Password, e.reason) }
        assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
        expired = true
        try {
            api.payFeeAccount("fake-token", pending(), method(), keyboard, "demo-type", "654321")
            fail("Expected order expiry before password submission")
        } catch (_: FeeOrderExpired) { }
        assertEquals(2, calls.count { it.url.path == "/charge/pay/getpayinfo" })
        assertEquals(2, calls.count { it.url.path == "/charge/order/getCurrentTime" })
        assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
    }

    @Test fun passwordClassificationUsesCodeAndIgnoresMessageText() {
        for (text in listOf("账户密码错误", "不同语言的提示", "余额不足", "<script>private-message</script>")) {
            val body = jsonObjectOf("code" to (60005), "success" to (false), "data" to (JsonNull), "msg" to (text))
            val rejected = FeePaymentErrors.rejection(body)!!
            assertEquals(FeeRejectionReason.Password, rejected.reason)
            assertEquals(text, rejected.message)
        }
    }

    @Test fun server60050UsesOriginalMessageWithoutClassifyingTheCause() = runBlocking {
        for (message in listOf("账户余额不足！", "持卡人已挂失！", "different language", "密码错误")) {
            val calls = mutableListOf<FakeResponse>()
            val response = jsonObjectOf("code" to (60050), "success" to (false), "data" to (JsonNull), "msg" to (message))
            try {
                api(calls, response.toString()).payFeeAccount("fake-token", pending(), method(), preparation(), "demo-type", "123456")
                fail("Expected server balance rejection")
            } catch (e: FeePaymentRejected) {
                assertEquals(FeeRejectionReason.Rejected, e.reason)
                assertEquals(message, e.message)
            }
            assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
        }
        for (response in listOf("""{"code":60050,"success":true,"data":null}""",
            """{"code":60050,"success":false,"data":"FAKE-ORDER"}""", """{"code":60050}""")) {
            assertNotNull(FeePaymentErrors.rejection(parseObject(response)))
        }
        assertNull(FeePaymentErrors.rejection(parseObject("""{"code":60050,"state":200}""")))
    }

    @Test fun lowOrStaleBalanceDoesNotOverrideBackendRejectionPriority() = runBlocking {
        for (message in listOf("持卡人已挂失！", "账户余额不足！")) {
            val calls = mutableListOf<FakeResponse>()
            val lowBalance = FeeAccountPreparation("FAKE-ORDER", "64", listOf(FeeAccount("demo-type", "统一账户", 0)), "fake-uuid", "9876543210")
            val rejection = "{\"code\":60050,\"success\":false,\"data\":null,\"msg\":\"$message\"}"
            val error = assertThrows(FeePaymentRejected::class.java) {
                runBlocking { api(calls, rejection).payFeeAccount("fake-token", pending(), method(), lowBalance, "demo-type", "123456") }
            }
            assertEquals(message, error.message)
            assertEquals(FeeRejectionReason.Rejected, error.reason)
            assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
        }
    }

    @Test fun staleZeroBalanceDoesNotPreventServerConfirmedPayment() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val lowBalance = FeeAccountPreparation("FAKE-ORDER", "64", listOf(FeeAccount("demo-type", "统一账户", 0)), "fake-uuid", "9876543210")
        api(calls, "{\"code\":200,\"success\":true,\"data\":\"FAKE-ORDER\"}")
            .payFeeAccount("fake-token", pending(), method(), lowBalance, "demo-type", "123456")
        assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
    }

    @Test fun emptyOrderResultUsesOfficialStatusZeroFallback() = runBlocking {
        val api = fixtureFeeApi { url -> FakeResponse(url, "{\"code\":200,\"orderList\":[]}") }
        val result = api.feeResult("fake-token", pending())
        assertFalse(result.paid)
        assertEquals(0, result.serverStatus)
        for (status in listOf(0, 2, 9)) {
            val order = jsonObjectOf("orderid" to (pending().orderId), "status" to (status), "msg" to ("后端订单提示"))
            val response = jsonObjectOf("code" to (200), "orderList" to (jsonArrayOf(listOf(order))))
            val checked = fixtureFeeApi { url -> FakeResponse(url, response.toString()) }.feeResult("fake-token", pending())
            assertEquals("后端订单提示", checked.message)
            assertEquals(status, checked.serverStatus)
            assertFalse(checked.paid)
        }
    }

    @Test fun officialMessagesAreVerbatimAndOnlyMissingOrEmptyTextUsesError() {
        for (code in listOf(60005, 60050, 400, 500, 99999)) {
            for (text in listOf("账户已挂失", "  原始提示\n ", "<b>业务提示</b>", "详细提示".repeat(80))) {
                val body = jsonObjectOf("code" to (code), "success" to (false), "data" to (JsonNull), "msg" to (text))
                assertEquals(text, FeePaymentErrors.rejection(body)!!.message)
            }
            for (text in listOf(null, "", 123, jsonObjectOf())) {
                val body = jsonObjectOf("code" to (code), "success" to (false), "data" to (JsonNull), "msg" to (text ?: JsonNull))
                assertEquals("Error", FeePaymentErrors.rejection(body)!!.message)
            }
        }
    }

}
