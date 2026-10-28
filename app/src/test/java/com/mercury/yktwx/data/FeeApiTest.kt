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
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class FeeApiTest {
    private class FakeHttps(url: URL, private val body: String, private val status: Int = 200,
        private val location: String? = null) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        val sent = ByteArrayOutputStream()
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = sent
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }

    private fun query(vararg data: Pair<String, String>) = runBlocking {
        val body = JSONObject().put("code", 200).put("map", JSONObject().put("data", JSONObject().apply {
            data.forEach { (key, value) -> put(key, value) }
        }))
        FeeApi { url -> FakeHttps(url, body.toString()) }.feeQuery("fake-token", FeeProject.Zhengdong, 4, emptyMap(), true)
    }

    @Test fun detailTargetAddsNamedRoomInfo() {
        val target = JSONObject(query(
            "area" to "1", "buildingName" to "示例1号楼", "areaName" to "示例校区",
            "extdata" to "", "floorName" to "1层", "floor" to "demo-floor", "aid" to "demo-aid",
            "account" to "demo-account", "building" to "demo-building", "room" to "demo-room", "roomName" to "示例1-101空调",
        ).thirdParty!!)
        assertEquals("房间：示例校区 示例1号楼 1层 示例1-101空调", target.getString("myCustomInfo"))
    }

    @Test fun detailTargetAddsIdValueRoomInfo() {
        val target = JSONObject(query(
            "room_id" to "demo-room&10号楼1单元001水表", "allAmp" to "100.00",
            "xiaoqu_id" to "demo-campus&示例校区", "usedAmp" to "50.00",
            "loudong_id" to "demo-building&10号楼", "louceng_id" to "demo-floor&10号楼1单元",
        ).thirdParty!!)
        assertEquals("房间：示例校区 10号楼 10号楼1单元 10号楼1单元001水表", target.getString("myCustomInfo"))
    }

    @Test fun detailTargetKeepsServerRoomInfo() {
        val target = JSONObject(query("roomName" to "示例1-101空调", "myCustomInfo" to "服务端摘要").thirdParty!!)
        assertEquals("服务端摘要", target.getString("myCustomInfo"))
    }

    private fun roomConfig(project: FeeProject, status: Int = 1, bindInfo: Int = 1, scene: String? = null): String =
        JSONObject().put("code", 200).put("sceneinfo", scene ?: JSONObject.NULL).put("feeitem", JSONObject()
            .put("feeitemid", project.id.toInt()).put("name", "示例缴费").put("flag", "0000000000")
            .put("bindStatus", status).put("feeitemImplBean", JSONObject().put("bindinfo", bindInfo))).toString()

    @Test fun bindingConfigurationIsDynamicAcrossAllSupportedProjects() = runBlocking {
        val scene = "xiaoqu_id:demo-campus&示例校区#\$#示例校区;room_id:demo-room&示例水表#\$#示例水表"
        for (project in listOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
            val calls = mutableListOf<FakeHttps>()
            val api = FeeApi { url -> FakeHttps(url, roomConfig(project, scene = scene)).also(calls::add) }
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
        val item = JSONObject().put("feeitemid", 448).put("retain_money", "1")
            .put("maxmoney", "1000").put("flag", "1100000000").put("layout", "50,100,200,500")
        val api = FeeApi { url -> FakeHttps(url, JSONObject().put("code", 200).put("feeitem", item).toString()) }
        val rules = api.feeConfiguration("fake-token", FeeProject.FamilyElectric).amountRules
        assertEquals(100L, rules.minimumCents)
        assertEquals(100_000L, rules.maximumCents)
        assertFalse(rules.integerOnly)
        assertNull(rules.amountError(101))
        assertEquals("最低充值 ¥ 1", rules.amountError(99))
        assertEquals("单次最高 ¥ 1000", rules.amountError(100_001))
        item.put("flag", "1100000200").put("maxmoney", JSONObject.NULL)
        val changed = api.feeConfiguration("fake-token", FeeProject.FamilyElectric).amountRules
        assertTrue(changed.integerOnly)
        assertNull(changed.maximumCents)
        assertEquals("仅支持整数金额", changed.amountError(101))
    }
    @Test fun disabledAndMultiRoomConfigurationsDoNotEnableSingleRoomActions() = runBlocking {
        for ((project, status, info) in listOf(Triple(FeeProject.Commercial, 0, 0),
            Triple(FeeProject.Wenbei, 2, 1), Triple(FeeProject.Network, 1, 1))) {
            val config = FeeApi { url -> FakeHttps(url, roomConfig(project, status, info)) }.feeConfiguration("fake-token", project)
            assertFalse(config.roomBindingEnabled)
            assertNull(config.roomBinding)
        }
    }
    @Test fun malformedSavedRoomIsNotTreatedAsUnbound() = runBlocking {
        val config = FeeApi { url -> FakeHttps(url, roomConfig(FeeProject.Wenbei, scene = "room:x;room:y")) }
            .feeConfiguration("fake-token", FeeProject.Wenbei)
        assertTrue(config.roomBindingEnabled)
        assertTrue(config.invalidRoomBinding)
        assertNull(config.roomBinding)
    }
    @Test fun saveAndUnbindUseUtf8JsonProjectIdAndNoSceneOnUnbind() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = FeeApi { url -> FakeHttps(url, "{\"code\":200}").also(calls::add) }
        val room = FeeRoomBinding.parse("room_id:demo-room&示例水表#\$#示例水表")!!
        api.setFeeRoom("fake-token", FeeProject.FamilyWater, room)
        api.setFeeRoom("fake-token", FeeProject.FamilyWater, null)
        assertEquals(2, calls.size)
        calls.forEach {
            assertEquals("/charge/sceneBind/add", it.url.path)
            assertEquals("POST", it.requestMethod)
            assertEquals("application/json;charset=UTF-8", it.getRequestProperty("Content-Type"))
            assertEquals("bearer fake-token", it.getRequestProperty("synjones-auth"))
            assertTrue(it.getRequestProperty("Authorization").startsWith("Basic "))
            assertNull(it.getRequestProperty("Cookie"))
            assertFalse(it.instanceFollowRedirects)
            assertEquals(449, JSONObject(it.sent.toString("UTF-8")).getInt("feeitemid"))
        }
        assertEquals(room.sceneInfo(), JSONObject(calls.first().sent.toString("UTF-8")).getString("sceneinfo"))
        assertEquals(setOf("feeitemid"), JSONObject(calls.last().sent.toString("UTF-8")).keys().asSequence().toSet())
    }
    @Test fun bindingRejectionIsNotRetriedOrMistakenForPayment() = runBlocking {
        var requests = 0
        val api = FeeApi { url -> requests++; FakeHttps(url, "{\"code\":500,\"msg\":\"失败\"}") }
        try { api.setFeeRoom("fake-token", FeeProject.Wenbei, null); fail("Expected rejection") }
        catch (_: YktException) { }
        assertEquals(1, requests)
    }

    @Test fun creationPreservesBackendRejectionsAcrossProjectsWithoutInventingBounds() = runBlocking {
        val message = "金额大于每笔交易允许的最大金额300元"
        val location = "/plat/notfound?msg=" + java.net.URLEncoder.encode(java.net.URLEncoder.encode(message, "UTF-8"), "UTF-8")
        for (project in FeeProject.entries) for (redirect in listOf(false, true)) {
            val calls = mutableListOf<FakeHttps>()
            val api = FeeApi { url -> FakeHttps(url,
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
            assertFalse(calls.single().instanceFollowRedirects)
            assertNull(rules.maximumCents)
        }
    }

    private fun pending() = PendingRecharge("test-user", "{\"room\":\"demo-room\"}", "410", 100, "FAKE-ORDER", "64")
    private fun method() = FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)
    private fun preparation() = FeeAccountPreparation("FAKE-ORDER", "64", listOf(FeeAccount("demo-type", "统一账户", 1000)), "fake-uuid", "9876543210")
    private fun orderInfo(expiry: String = "2026-10-21 12:30:00", status: Int = 0) = JSONObject().put("code", 200)
        .put("order", JSONObject().put("orderid", "FAKE-ORDER").put("third_party", pending().targetId)
            .put("tranamt", 1).put("status", status).put("payexpdate", expiry)
            .put("feeitemlist", JSONArray().put(JSONObject().put("feeitemid", 410))))
        .put("payList", JSONArray().put(JSONObject().put("payid", "64").put("code", "ACCOUNTTSM").put("nopassword", 0)))
    private fun api(calls: MutableList<FakeHttps>, payment: String, expiry: String = "2026-10-21 12:30:00",
        serverTime: String = "2026-10-21 12:00:00", status: Int = 0, timeHttpStatus: Int = 200) = FeeApi { url ->
        val (body, httpStatus) = when (url.path) {
            "/charge/pay/getpayinfo" -> orderInfo(expiry, status).toString() to 200
            "/charge/order/getCurrentTime" -> JSONObject().put("code", 200).put("currentTime", serverTime).toString() to timeHttpStatus
            "/blade-pay/pay" -> payment to 200
            else -> error("Unexpected URL")
        }
        FakeHttps(url, body, httpStatus).also(calls::add)
    }
    private fun fields(call: FakeHttps) = call.sent.toString("UTF-8").split('&').associate {
        val key = it.substringBefore('='); val value = it.substringAfter('=')
        URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
    }

    @Test fun passwordRejectionPreservesOfficialMessageAndNeverReplaysTheRequest() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        val api = api(calls, JSONObject().put("code", 60005).put("success", false)
            .put("data", JSONObject.NULL).put("msg", "账户密码错误").toString())
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
            val calls = mutableListOf<FakeHttps>()
            try {
                api(calls, "{}", expiry, now).payFeeAccount("fake-token", pending(), method(), preparation(), "demo-type", "123456")
                fail("Expected expiry or validation failure")
            } catch (e: YktException) { assertTrue(e is FeeOrderExpired || e is FeePaymentNotStarted) }
            assertTrue(calls.none { it.url.path == "/blade-pay/pay" })
        }
    }

    @Test fun failedPreflightAndAlreadyPaidNeverSubmitPassword() = runBlocking {
        for ((status, httpStatus) in listOf(0 to 503, 1 to 200, 2 to 200)) {
            val calls = mutableListOf<FakeHttps>()
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
            assertEquals(JSONObject(body).getString("msg"), rejected.message)
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
        val calls = mutableListOf<FakeHttps>()
        var expired = false
        val api = FeeApi { url ->
            val body = when (url.path) {
                "/charge/pay/getpayinfo" -> orderInfo().toString()
                "/charge/order/getCurrentTime" -> JSONObject().put("code", 200)
                    .put("currentTime", if (expired) "2026-10-21 12:30:00" else "2026-10-21 12:00:00").toString()
                "/blade-pay/pay" -> JSONObject().put("code", 60005).put("success", false)
                    .put("data", JSONObject.NULL).put("msg", "账户密码错误").toString()
                else -> error("Unexpected URL")
            }
            FakeHttps(url, body).also(calls::add)
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
            val body = JSONObject().put("code", 60005).put("success", false).put("data", JSONObject.NULL).put("msg", text)
            val rejected = FeePaymentErrors.rejection(body)!!
            assertEquals(FeeRejectionReason.Password, rejected.reason)
            assertEquals(text, rejected.message)
        }
    }

    @Test fun server60050UsesOriginalMessageWithoutClassifyingTheCause() = runBlocking {
        for (message in listOf("账户余额不足！", "持卡人已挂失！", "different language", "密码错误")) {
            val calls = mutableListOf<FakeHttps>()
            val response = JSONObject().put("code", 60050).put("success", false)
                .put("data", JSONObject.NULL).put("msg", message)
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
            assertNotNull(FeePaymentErrors.rejection(JSONObject(response)))
        }
        assertNull(FeePaymentErrors.rejection(JSONObject("""{"code":60050,"state":200}""")))
    }

    @Test fun lowOrStaleBalanceDoesNotOverrideBackendRejectionPriority() = runBlocking {
        for (message in listOf("持卡人已挂失！", "账户余额不足！")) {
            val calls = mutableListOf<FakeHttps>()
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
        val calls = mutableListOf<FakeHttps>()
        val lowBalance = FeeAccountPreparation("FAKE-ORDER", "64", listOf(FeeAccount("demo-type", "统一账户", 0)), "fake-uuid", "9876543210")
        api(calls, "{\"code\":200,\"success\":true,\"data\":\"FAKE-ORDER\"}")
            .payFeeAccount("fake-token", pending(), method(), lowBalance, "demo-type", "123456")
        assertEquals(1, calls.count { it.url.path == "/blade-pay/pay" })
    }

    @Test fun emptyOrderResultUsesOfficialStatusZeroFallback() = runBlocking {
        val api = FeeApi { url -> FakeHttps(url, "{\"code\":200,\"orderList\":[]}") }
        val result = api.feeResult("fake-token", pending())
        assertFalse(result.paid)
        assertEquals(0, result.serverStatus)
        for (status in listOf(0, 2, 9)) {
            val order = JSONObject().put("orderid", pending().orderId).put("status", status).put("msg", "后端订单提示")
            val response = JSONObject().put("code", 200).put("orderList", JSONArray().put(order))
            val checked = FeeApi { url -> FakeHttps(url, response.toString()) }.feeResult("fake-token", pending())
            assertEquals("后端订单提示", checked.message)
            assertEquals(status, checked.serverStatus)
            assertFalse(checked.paid)
        }
    }

    @Test fun officialMessagesAreVerbatimAndOnlyMissingOrEmptyTextUsesError() {
        for (code in listOf(60005, 60050, 400, 500, 99999)) {
            for (text in listOf("账户已挂失", "  原始提示\n ", "<b>业务提示</b>", "详细提示".repeat(80))) {
                val body = JSONObject().put("code", code).put("success", false).put("data", JSONObject.NULL).put("msg", text)
                assertEquals(text, FeePaymentErrors.rejection(body)!!.message)
            }
            for (text in listOf(null, "", 123, JSONObject())) {
                val body = JSONObject().put("code", code).put("success", false).put("data", JSONObject.NULL).put("msg", text ?: JSONObject.NULL)
                assertEquals("Error", FeePaymentErrors.rejection(body)!!.message)
            }
        }
    }

}
