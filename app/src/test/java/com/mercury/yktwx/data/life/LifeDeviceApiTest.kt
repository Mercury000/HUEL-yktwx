package com.mercury.yktwx.data.life

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLDecoder
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class LifeDeviceApiTest {
    private class Response(url: URL, val body: String) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream(): OutputStream = sent
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
        fun form() = sent.toString("UTF-8").split('&').associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }
    private fun api(responses: Map<String, String>, calls: MutableList<Response>): LifeDeviceApi =
        LifeDeviceApi("test-secret", { url ->
            assertEquals("https", url.protocol)
            assertEquals("userapi.qiekj.com", url.host)
            Response(url, responses[url.path] ?: error("Unexpected request: ${url.path}")).also(calls::add)
        }, { 123456789L })

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
            assertEquals("POST", call.requestMethod)
            assertEquals("1.109.1", call.getRequestProperty("Version"))
            assertEquals(LifeDeviceProtocol.sign("test-secret", "123456789", call.url.path, "test-token"), call.getRequestProperty("sign"))
            assertFalse(call.instanceFollowRedirects)
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
        assertTrue(calls.all { it.getRequestProperty("sign") == null })
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
        assertEquals(WaterReading(5, 1, "order-1"), service.water("33", "test-token"))
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
            assertEquals(WaterReading(5, 1, "order-1", zero), service.water("33", "test-token"))
            assertEquals(listOf("/goods/water/sync"), calls.map { it.url.path })
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
        assertEquals("POST", call.requestMethod)
        assertEquals(mapOf("orderId" to "test-order-id", "token" to "test-token"), call.form())
        assertTrue(call.closed)
        assertNotNull(call.getRequestProperty("sign"))
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
        assertFalse(device.supportsFinish)
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
        assertEquals(payload, service.prepareLaundryAlipay("wash-1", "test-token"))
        assertEquals("13", calls.last().form()["payType"])
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
