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
}
