package com.mercury.yktwx.data.life

import com.mercury.yktwx.data.HttpFixture
import com.mercury.yktwx.data.AppHttp
import com.mercury.yktwx.data.fixtureLifeDeviceApi

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLDecoder

class LifeDeviceApiTest {
    @Test fun accountProfileUsesAuthenticatedReadWithoutScanningDevices() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf("/user/info" to """{"code":0,"data":{"id":"fixture-user","phone":"13800000000","userName":"示例","balance":"0.00","unCompleteOrders":null}}"""), calls)
        assertEquals(LifeAccountProfile("fixture-user", "13800000000"), service.profile("fixture-token"))
        val call = calls.single()
        assertEquals("/user/info", call.url.path)
        assertEquals(mapOf("token" to "fixture-token"), call.form())
        assertEquals("fixture-token", call.request.header("Authorization"))
        assertEquals(LifeDeviceProtocol.sign("test-secret", "123456789", "/user/info", "fixture-token"), call.request.header("sign"))
        assertTrue(call.closed)
    }

    @Test fun accountProfileRejectsMissingIdentityAndRecognizesExpiredCredentials() = runBlocking {
        for (data in listOf("null", "{}", """{"id":"fixture-user"}""", """{"id":"fixture-user","phone":"138****0000"}""")) {
            val service = api(mapOf("/user/info" to """{"code":0,"data":$data}"""), mutableListOf())
            try { service.profile("fixture-token"); fail("Incomplete account must stay unverified") }
            catch (e: LifeDeviceException) { assertFalse(e.loginExpired) }
        }
        val service = api(mapOf("/user/info" to """{"code":401,"msg":"登录失效"}"""), mutableListOf())
        try { service.profile("fixture-token"); fail("Expired token must be rejected") }
        catch (e: LifeDeviceException) { assertTrue(e.loginExpired) }
    }
    private class Response(url: URL, val body: String, val status: Int = 200) : HttpFixture(url) {
        val sent = ByteArrayOutputStream()
        var closed = false

        override fun close() { closed = true }

        override fun statusCode() = status
        override fun bodyStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun errorBodyStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream(): OutputStream = sent

        fun form() = sent.toString("UTF-8").split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }
    private fun api(responses: Map<String, String>, calls: MutableList<Response>): LifeDeviceApi =
        fixtureLifeDeviceApi("test-secret", { url ->
            assertEquals("https", url.protocol)
            assertEquals("userapi.qiekj.com", url.host)
            Response(url, responses[url.path] ?: error("Unexpected request: ${url.path}")).also(calls::add)
        }, { 123456789L })

    @Test fun creationRejectsParsedBusinessErrorsButMissingOrMalformedResultsRemainUncertain() = runBlocking {
        val rejected = api(mapOf("/trade/create" to """{"code":7,"msg":"30s内不能在同一设备下单","data":null}"""), mutableListOf())
        try { rejected.createLaundryOrder("77", "clean", "test-token"); fail("Creation must be rejected") }
        catch (e: LifeDeviceException) { assertTrue(e.businessRejected); assertEquals("30s内不能在同一设备下单", e.message) }
        for (body in listOf("invalid", """{"msg":"30s内不能在同一设备下单"}""",
            """{"code":0,"data":null}""", """{"code":0,"data":{"tradeNo":"wash-1"}}""")) {
            val service = api(mapOf("/trade/create" to body), mutableListOf())
            try { service.createLaundryOrder("77", "clean", "test-token"); fail("Incomplete response must stay uncertain") }
            catch (e: LifeDeviceException) { assertFalse(e.businessRejected) }
        }
    }
    @Test fun healthyDeviceWithoutSyncMessageIsReadyButFaultStillBlocksCreation() = runBlocking {
        for (msgField in listOf("", ",\"msgId\":\"\"", ",\"msgId\":null")) {
            val service = api(mapOf("/trade/check_device_status" to """{"code":0,"data":{"health":true$msgField}}"""), mutableListOf())
            assertEquals("", service.checkLaundryDevice("77", "test-token"))
        }
        val service = api(mapOf("/trade/check_device_status" to """{"code":0,"data":{"health":false,"faultDesc":"设备工作中，请更换设备使用"}}"""), mutableListOf())
        try { service.checkLaundryDevice("77", "test-token"); fail("Fault must block creation") }
        catch (e: LifeDeviceException) { assertEquals("设备工作中，请更换设备使用", e.message) }
    }
    @Test fun authorizationRejectionCannotLeaveAnUnknownCreationButServerTransportFailureCan() = runBlocking {
        for (status in listOf(401, 403, 500)) {
            val service = fixtureLifeDeviceApi("test-secret", { url -> Response(url, "unavailable", status) })
            try { service.createLaundryOrder("77", "clean", "test-token"); fail("HTTP error must fail") }
            catch (e: LifeDeviceException) {
                assertEquals(status != 500, e.businessRejected)
                assertEquals(status != 500, e.loginExpired)
            }
        }
    }

    @Test fun observedQrFormatParsesWithoutGuessingNumbersInOtherLinks() {
        assertEquals("867369079842630", DeviceSerial.parse("https://h5.qiekj.com/skip?SN=867369079842630"))
        assertEquals("867369079842630", DeviceSerial.parse("867369079842630"))
        assertNull(DeviceSerial.parse("https://example.org/skip?SN=867369079842630"))
        assertNull(DeviceSerial.parse("https://h5.qiekj.com/867369079842630"))
        assertNull(DeviceSerial.parse("https://h5.qiekj.com/skip?SN=867369079842630&SN=867369079842630"))
        assertNull(DeviceSerial.parse("02"))
    }

    @Test fun deviceKeepsDistinctShopIdentifiersAndUsesOnlyReturnedOptions() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf(
            "/goods/scan/v2" to """{"code":0,"data":{"id":"11","shopId":22,"categoryCode":"04","type":"goods"}}""",
            "/goods/normal/details" to """{"code":0,"data":{"goodsId":11,"name":"开水","categoryCode":"04","orgId":22,"shopId":"actual-shop","imei":"867369079842630","payment":"after","isBluetooth":0,"supportAppUserFinish":true}}""",
            "/goods/normal/skus" to """{"code":0,"data":[{"skuId":33,"name":"饮水","scanAvailable":true,"stockState":1,"price":"1.00","unit":"33"}]}""",
            "/shop/outline/v2" to """{"code":0,"data":{"shopName":"","address":""}}""",
            "/payChannelRoute/afterPay" to """{"code":0,"data":[{"method":15,"methodName":"支付宝支付","useStatus":true,"needOpenFlag":false}]}"""
        ), calls)
        val device = service.device("867369079842630", "test-token")
        assertEquals("22", device.routeShopId)
        assertEquals("actual-shop", device.shopId)
        assertEquals("", device.address)
        assertEquals("饮水", device.skus.single().name)
        assertNull(device.skus.single().minutes)
        assertNull(device.startError)
        val channel = calls.single { it.url.path == "/payChannelRoute/afterPay" }
        assertEquals(mapOf("imei" to "867369079842630", "useTicket" to "0", "shopId" to "22", "goodsCategory" to "04", "token" to "test-token"), channel.form())
        calls.forEach { call ->
            assertTrue(call.closed)
            assertEquals("POST", call.request.method)
            assertEquals("1.109.1", call.request.header("Version"))
            assertEquals(LifeDeviceProtocol.sign("test-secret", "123456789", call.url.path, "test-token"), call.request.header("sign"))
            assertFalse(AppHttp.service.followRedirects)
        }
    }

    @Test fun loginUsesObservedSmsAndRegistrationFieldsWithoutDeviceRequests() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf(
            "/common/sms/sendCode" to """{"code":0,"data":null}""",
            "/user/reg" to """{"code":0,"data":{"token":"test-token"}}"""
        ), calls)
        service.sendCode("13800000000")
        assertEquals(LifeAccount("13800000000", "test-token"), service.login("13800000000", "123456"))
        assertEquals(mapOf("phone" to "13800000000", "template" to "reg"), calls.first().form())
        assertEquals(mapOf("channel" to "android_app", "phone" to "13800000000", "verify" to "123456"), calls.last().form())
        assertTrue(calls.all { it.request.header("sign") == null })
    }

    @Test fun finishingProcessesAfterPayWithoutReadingBillsOrInferringAmount() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf(
            "/goods/endWater" to """{"code":0,"data":true}""",
            "/goods/water/sync" to """{"code":0,"data":{"status":5,"workStatus":1,"identify":"order-1","amount":1}}""",
            "/order/afterPay/creating" to """{"code":0,"data":{"orderId":"id-1","orderNo":"order-1","orderStatus":0,"payType":null}}""",
            "/order/sync" to """{"code":0,"data":{"code":0,"messageId":""}}"""
        ), calls)
        service.endWater("11", "test-token")
        assertEquals(WaterReading(5, 1, "order-1", positiveConsumption = true), service.water("33", "test-token"))
        assertEquals("id-1", service.createAfterPay("order-1", "test-token"))
        service.syncOrder("order-1", "test-token")
        assertEquals("0", calls.last().form()["payType"])
        assertEquals(4, calls.size)
    }

    @Test fun zeroDeviceConsumptionIsExplicitAndMissingAmountIsNotZero() = runBlocking {
        for ((amount, zero) in listOf("0" to true, "\"0.00\"" to true, "null" to false, "1" to false,
            null to false, "\"unknown\"" to false)) {
            val calls = mutableListOf<Response>()
            val amountField = amount?.let { """, "amount":$it""" }.orEmpty()
            val service = api(mapOf("/goods/water/sync" to
                """{"code":0,"data":{"status":5,"workStatus":1,"identify":"order-1"$amountField}}"""), calls)
            assertEquals(WaterReading(5, 1, "order-1", zero, amount == "1"), service.water("33", "test-token"))
            assertEquals(listOf("/goods/water/sync"), calls.map { it.url.path })
        }
    }

    private fun showerResponses(category: String = "08", detailCategory: String = category) = mapOf(
        "/goods/scan/v2" to """{"code":0,"data":{"id":"shower-device","shopId":22,"categoryCode":"$category","type":"goods"}}""",
        "/goods/normal/details" to """{"code":0,"data":{"goodsId":"shower-device","name":"淋浴设备","categoryCode":"$detailCategory","orgId":22,"shopId":"actual-shop","imei":"shower-imei","payment":"after","isBluetooth":0,"supportAppUserFinish":true}}""",
        "/goods/normal/skus" to """{"code":0,"data":[{"skuId":"outlet-1","name":"01","feature":"1号出水口","scanAvailable":true,"stockState":1,"price":"0.00","unit":"33.333"}]}""",
        "/shop/outline/v2" to """{"code":0,"data":{"shopName":"学校","address":""}}""",
        "/payChannelRoute/afterPay" to """{"code":0,"data":[{"method":15,"methodName":"支付宝支付","useStatus":true,"needOpenFlag":false},{"method":53,"methodName":"云闪付免密支付","useStatus":true,"needOpenFlag":false}]}""",
    )

    @Test fun absentCapabilityFieldsStayUnknownAndDoNotInventADeviceOrChannelRejection() = runBlocking {
        val responses = showerResponses().toMutableMap()
        responses["/goods/normal/details"] = responses.getValue("/goods/normal/details")
            .replace(",\"isBluetooth\":0", "").replace(",\"supportAppUserFinish\":true", "")
        responses["/goods/normal/skus"] = responses.getValue("/goods/normal/skus")
            .replace(",\"scanAvailable\":true", "").replace(",\"stockState\":1", "")
        responses["/payChannelRoute/afterPay"] = responses.getValue("/payChannelRoute/afterPay")
            .replace(",\"useStatus\":true", "").replace(",\"needOpenFlag\":false", "")
        val device = api(responses, mutableListOf()).device("test-shower-sn", "test-token")
        assertNull(device.bluetooth); assertNull(device.supportsFinish); assertNull(device.skus.single().available)
        assertNull(device.alipay!!.available); assertNull(device.alipay!!.needsOpen); assertNull(device.startError)
    }
    @Test fun absentHealthResultDoesNotInventAFaultAndPromotionMetadataDoesNotBlockAQuote() = runBlocking {
        for (data in listOf("null", "{}", "{\"msgId\":\"sync-1\"}")) {
            val service = api(mapOf("/trade/check_device_status" to "{\"code\":0,\"data\":$data}"), mutableListOf())
            assertEquals(if (data.contains("sync-1")) "sync-1" else "", service.checkLaundryDevice("77", "test-token"))
        }
        val device = LifeDevice("77", "code", "洗衣", "00", "22", "22", "shop", "imei", "", "", "before",
            false, false, null, listOf(LifeSku("clean", "桶自洁", true)), emptyList())
        val calls = mutableListOf<Response>()
        val service = api(mapOf("/trade/preview/v4" to """{"code":0,"data":{"realPrice":"0.00","items":[{"goodsId":"77","skuId":"clean"}],"promotions":[{"isUsed":true}]}}""",
            "/payChannelRoute/prePay" to """{"code":0,"data":[{"method":"2","methodName":"余额支付"}]}"""), calls)
        assertEquals("0.00", service.laundryQuote(device, "clean", "test-token").price)
        assertEquals("[]", calls.first().form()["promotions"])
    }
    @Test fun showerScanUsesSnAndActualCategoryForAfterPayAndKeepsOutletData() = runBlocking {
        val calls = mutableListOf<Response>(); val service = api(showerResponses(), calls)
        val device = service.device("test-shower-sn", "test-token")
        assertEquals("08", device.category); assertEquals("淋浴", device.waterAction); assertNull(device.startError)
        assertEquals(LifeSku("outlet-1", "01", true, "0.00", "1号出水口"), device.skus.single())
        assertEquals(mapOf("SN" to "test-shower-sn", "token" to "test-token"), calls.first().form())
        assertEquals("08", calls.last().form()["goodsCategory"]); assertEquals("22", calls.last().form()["shopId"])
        assertEquals("15", device.alipay?.method)
        assertNotNull(device.copy(channels = device.channels.filter { it.method == "53" }).startError)
        assertNotNull(device.copy(bluetooth = true).startError)
        assertNotNull(device.copy(payment = "before").startError)
    }

    @Test fun showerScanRejectsUnknownCategoryAndMismatchedDetailsWithoutCommands() = runBlocking {
        for ((category, detailCategory, message) in listOf(Triple("99", "99", "未知设备"),
            Triple("08", "04", "设备信息不匹配，请重新扫码"))) {
            val calls = mutableListOf<Response>(); val service = api(showerResponses(category, detailCategory), calls)
            try { service.device("test-shower-sn", "test-token"); fail("Must reject unrelated device") }
            catch (e: LifeDeviceException) { assertEquals(message, e.message) }
            assertFalse(calls.any { it.url.path.contains("unlock") || it.url.path.contains("afterPay") })
        }
    }

    @Test fun showerCommandsUseDedicatedPathsAndSameOrderWithoutPrepayment() = runBlocking {
        val calls = mutableListOf<Response>(); val service = api(mapOf(
            "/payChannelRoute/addUserAfterPayChannel" to """{"code":0,"data":null}""",
            "/goods/shower/unlock" to """{"code":0,"data":{"msgId":"shower-message","orderNo":"shower-order","cmd":"","audioFlag":false}}""",
            "/goods/shower/sync" to """{"code":0,"data":{"status":1,"workStatus":2,"identify":"shower-order","amount":null}}""",
            "/shower/goods/remoteEnd" to """{"code":0,"data":true}""",
            "/goods/water/sync" to """{"code":0,"data":{"status":5,"workStatus":1,"identify":"shower-order","amount":0}}""",
        ), calls)
        service.selectAfterPay("test-token")
        val unlock = service.unlockShower("outlet-1", "test-token")
        assertEquals(WaterUnlock("shower-message", "shower-order"), unlock)
        assertEquals(WaterReading(1, 2, unlock.orderNo), service.shower("outlet-1", "test-token"))
        service.endShower("shower-device", unlock.orderNo, "test-token")
        assertTrue(service.water("outlet-1", "test-token").zeroConsumption)
        assertEquals(mapOf("skuId" to "outlet-1", "promotions" to LifeDeviceProtocol.Promotions, "token" to "test-token"),
            calls.single { it.url.path.endsWith("unlock") }.form())
        assertEquals(mapOf("goodsId" to "shower-device", "orderNo" to unlock.orderNo, "token" to "test-token"),
            calls.single { it.url.path.endsWith("remoteEnd") }.form())
        calls.forEach { assertEquals(LifeDeviceProtocol.sign("test-secret", "123456789", it.url.path, "test-token"), it.request.header("sign")) }
    }

    @Test fun showerEndDoesNotAcceptNullOrRejectedSuccess() = runBlocking {
        for (data in listOf("null", "false")) {
            val service = api(mapOf("/shower/goods/remoteEnd" to """{"code":0,"data":$data}"""), mutableListOf())
            try { service.endShower("shower-device", "shower-order", "test-token"); fail("End must be acknowledged") }
            catch (e: LifeDeviceException) { assertEquals("结束淋浴失败，请重试", e.message) }
        }
    }

    private fun orderPayload(status: Int = 0, price: String = "0.00", order: String = "wash-1") =
        """{"code":0,"data":{"id":"wash-id","orderNo":"$order","orderStatus":$status,"payPrice":"$price","machineFunctionName":"桶自洁","markMinutes":5,"payTime":"2026-11-04 12:41:47","completeTime":"2026-11-04 12:46:47","finishTime":"2026-11-04 12:48:48","tradeOrderItem":[{"goodsId":"77","skuId":"clean"}]}}"""

    @Test fun laundryCancellationUsesOrderIdAndAcceptsObservedNullSuccessPayload() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf("/order/cancel" to """{"code":0,"msg":"成功","data":null}"""), calls)
        service.cancelLaundryOrder("test-order-id", "test-token")
        val call = calls.single()
        assertEquals("/order/cancel", call.url.path)
        assertEquals("POST", call.request.method)
        assertEquals(mapOf("orderId" to "test-order-id", "token" to "test-token"), call.form())
        assertTrue(call.closed)
        assertNotNull(call.request.header("sign"))
    }
    @Test fun laundryCancellationPropagatesBackendRejection() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf("/order/cancel" to """{"code":7,"msg":"订单暂不可取消","data":null}"""), calls)
        try {
            service.cancelLaundryOrder("test-order-id", "test-token")
            fail("Rejected cancellation must not be treated as closed")
        } catch (e: LifeDeviceException) {
            assertEquals("订单暂不可取消", e.message)
        }
        assertEquals(1, calls.size)
    }
    @Test fun laundryUsesNqtAndOnlyActualProgramsThenLoadsQuoteAndNumericShopChannel() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf(
            "/goods/scan/v2" to """{"code":0,"data":{"id":"77","shopId":22,"categoryCode":"00","type":"goods"}}""",
            "/goods/normal/details" to """{"code":0,"data":{"goodsId":"77","name":"77","categoryCode":"00","orgId":22,"shopId":"actual-shop","imei":"serial-77","payment":"before","isBluetooth":0,"supportAppUserFinish":false}}""",
            "/goods/normal/skus" to """{"code":0,"data":[{"skuId":"clean","name":"桶自洁","scanAvailable":true,"stockState":1,"price":"0.00","feature":"设备桶自洁","unit":"5"}]}""",
            "/shop/outline/v2" to """{"code":0,"data":{"shopName":"学校","address":""}}""",
            "/trade/preview/v4" to """{"code":0,"data":{"realPrice":"0.00","items":[{"goodsId":"77","skuId":"clean","skuInfo":{"markMinutes":5}}],"promotions":[{"isUsed":false}]}}""",
            "/payChannelRoute/prePay" to """{"code":0,"data":[{"method":2,"methodName":"余额支付","useStatus":true,"needOpenFlag":false}]}"""
        ), calls)
        val nqt = "00000000-0000-4000-8000-000000000001"
        val device = service.laundryDevice(nqt, "test-token")
        assertNull(device.laundryError)
        assertEquals(false, device.supportsFinish)
        assertEquals(LifeSku("clean", "桶自洁", true, "0.00", "设备桶自洁", 5), device.skus.single())
        assertTrue(device.channels.isEmpty())
        assertFalse(calls.any { it.url.path == "/trade/preview/v4" })
        assertEquals(mapOf("NQT" to nqt, "token" to "test-token"), calls.first().form())
        val quote = service.laundryQuote(device, "clean", "test-token")
        assertEquals(5, quote.minutes)
        assertEquals("0.00", quote.price)
        assertEquals("22", calls.last().form()["shopId"])
        val preview = calls.single { it.url.path == "/trade/preview/v4" }.form()
        assertEquals("0", preview["isAutoSelectPromotion"])
        assertEquals("[]", preview["promotions"])
        assertEquals("[]", preview["forbiddenPromotions"])
        assertFalse(calls.any { it.url.path.contains("afterPay") })
    }

    @Test fun zeroLaundryStillCreatesPaysAndReadsSameOrderFinishedTime() = runBlocking {
        val calls = mutableListOf<Response>()
        val service = api(mapOf(
            "/trade/check_device_status" to """{"code":0,"data":{"health":true,"msgId":"m-1"}}""",
            "/machine/sync" to """{"code":0,"data":true}""",
            "/trade/create" to """{"code":0,"data":{"tradeNo":"wash-1","orderId":"wash-id"}}""",
            "/pay/unify/pay" to """{"code":0,"data":null}""",
            "/order/pay_status" to """{"code":0,"data":true}""",
            "/machine/device_is_start" to """{"code":0,"data":true}""",
            "/order/detail" to orderPayload(3),
        ), calls)
        assertTrue(service.laundryMachineReady(service.checkLaundryDevice("77", "test-token"), "test-token"))
        val order = service.createLaundryOrder("77", "clean", "test-token")
        assertEquals(LaundryOrder("wash-1", "wash-id"), order)
        service.payLaundryBalance(order.orderNo, "test-token")
        assertTrue(service.laundryPaid(order.orderNo, "2", "test-token"))
        assertTrue(service.laundryStarted(order.orderNo, "test-token"))
        val reading = service.laundryOrder(order.orderNo, "test-token")
        assertEquals(3, reading.status)
        assertTrue(reading.finishedAtMillis!! > reading.expectedEndMillis!!)
        assertEquals("12:48:48", java.time.Instant.ofEpochMilli(reading.finishedAtMillis).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalTime().toString())
        assertEquals("[]", calls.single { it.url.path == "/trade/create" }.form()["promotions"])
        assertTrue(calls.filter { it.url.path in setOf("/pay/unify/pay", "/order/pay_status", "/machine/device_is_start", "/order/detail") }
            .all { it.form()["orderNo"] == order.orderNo })
        assertFalse(calls.any { it.url.path in setOf("/order/list", "/goods/endWater", "/order/afterPay/creating") })
    }

    @Test fun laundryAgainPayUsesOrderShopAndSignedAlipayPayloadIsPassedWithoutRewriting() = runBlocking {
        val calls = mutableListOf<Response>()
        val payload = "app_id=test&biz_content=test&sign_type=RSA2&sign=test"
        val service = api(mapOf(
            "/payChannelRoute/againPay" to """{"code":0,"data":[{"method":13,"methodName":"支付宝","useStatus":true,"needOpenFlag":false}]}""",
            "/pay/unify/prePay" to """{"code":0,"data":{"prepayParam":"$payload"}}"""
        ), calls)
        val device = LifeDevice("77", "nqt", "77", "00", "22", "22", "actual-shop", "serial", "", "", "before", false, false, null,
            listOf(LifeSku("clean", "桶自洁", true)), emptyList())
        val session = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying, orderNo = "wash-1")
        assertEquals("13", service.laundryChannels(session, "test-token").single().method)
        assertEquals("actual-shop", calls.first().form()["shopId"])
        assertEquals(payload, service.prepareLaundryPayment("wash-1", "13", "test-token"))
        assertEquals("13", calls.last().form()["payType"])
    }
    @Test fun laundryUnionPayPassesTransactionNumberAndUsesChannel52ForResultQuery() = runBlocking {
        val calls = mutableListOf<Response>()
        val tn = "202611071234567890123"
        val service = api(mapOf(
            "/pay/unify/prePay" to """{"code":0,"data":{"prepayParam":"$tn"}}""",
            "/order/pay_status" to """{"code":0,"data":true}"""
        ), calls)
        assertEquals(tn, service.prepareLaundryPayment("wash-52", "52", "test-token"))
        assertTrue(service.laundryPaid("wash-52", "52", "test-token"))
        assertTrue(calls.all { it.form()["orderNo"] == "wash-52" && it.form()["payType"] == "52" })
        assertEquals(listOf("/pay/unify/prePay", "/order/pay_status"), calls.map { it.url.path })
    }
    @Test fun closedLaundryReadsServerCreateTimeWithoutInventingPaymentTimes() = runBlocking {
        val payload = """{"code":0,"data":{"id":"wash-id","orderNo":"wash-1","orderStatus":1,"createTime":"2026-11-04 18:50:39","payPrice":"2.50","showStart":false,"machineName":"77","shopName":"河南财经政法大学","machineFunctionName":"快洗","markMinutes":25,"tradeOrderItem":[{"goodsId":"77","skuId":"quick","skuInfo":{"feature":"适用不太脏的衣物"}}]}}"""
        val reading = api(mapOf("/order/detail" to payload), mutableListOf()).laundryOrder("wash-1", "test-token")
        assertEquals(1, reading.status)
        assertEquals(java.time.LocalDateTime.of(2026, 11, 4, 18, 50, 39).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), reading.createdAtMillis)
        assertNull(reading.paidAtMillis); assertNull(reading.expectedEndMillis); assertNull(reading.finishedAtMillis)
        assertEquals("77", reading.deviceName)
        assertEquals("河南财经政法大学", reading.shopName)
        assertEquals("适用不太脏的衣物", reading.programFeature)
        assertEquals("", reading.payTypeName)
    }

    @Test fun laundryOrderKeepsCapturedPaymentNameAndStringSkuInfo() = runBlocking {
        val payload = """{"code":0,"data":{"id":"wash-id","orderNo":"wash-1","orderStatus":3,"payPrice":"0.00","machineFunctionName":"桶自洁","payTypeName":"余额","tradeOrderItem":[{"goodsId":"77","skuId":"clean","skuInfo":"{\"feature\":\"设备桶自洁\"}"}]}}"""
        val reading = api(mapOf("/order/detail" to payload), mutableListOf()).laundryOrder("wash-1", "test-token")
        assertEquals("余额", reading.payTypeName)
        assertEquals("设备桶自洁", reading.programFeature)
    }

    @Test fun laundryParsingDoesNotAcceptWrongLinksAndOrderIdentityMismatchIsRejected() = runBlocking {
        val nqt = "00000000-0000-4000-8000-000000000001"
        assertEquals(nqt, LifeDeviceCode.parse(nqt, LifeFeature.Laundry))
        assertEquals(nqt, LifeDeviceCode.parse("https://h5.qiekj.com/skip?NQT=$nqt", LifeFeature.Laundry))
        assertNull(LifeDeviceCode.parse("https://example.com/skip?NQT=$nqt", LifeFeature.Laundry))
        assertNull(LifeDeviceCode.parse("867369079842630", LifeFeature.Laundry))
        assertNull(LifeDeviceCode.parse("https://h5.qiekj.com/skip?NQT=$nqt&NQT=$nqt", LifeFeature.Laundry))
        assertEquals(LifeFeature.Laundry to nqt, LifeDeviceCode.identify(nqt))
        assertEquals(LifeFeature.Water to "867369079842630", LifeDeviceCode.identify("867369079842630"))
        assertNull(LifeDeviceCode.identify("https://h5.qiekj.com/skip?NQT=$nqt&SN=867369079842630"))
        assertNull(LifeDeviceCode.identify("https://example.com/skip?NQT=$nqt"))
        try {
            api(mapOf("/order/detail" to orderPayload(order = "other-order")), mutableListOf()).laundryOrder("wash-1", "test-token")
            fail("Cannot accept another order")
        } catch (expected: LifeDeviceException) { assertEquals("洗衣订单信息不匹配", expected.message) }
    }
}
