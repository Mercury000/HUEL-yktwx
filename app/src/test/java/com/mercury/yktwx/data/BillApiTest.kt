package com.mercury.yktwx.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.net.URLDecoder
import java.security.Principal
import java.security.cert.Certificate
import java.time.LocalDate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises bill request construction and parsing without contacting the real server. */
class BillApiTest {
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

    private class ScriptedBillsApi(private val status: Int = 200) {
        val calls = mutableListOf<FakeHttps>()
        private val record = JSONObject()
            .put("orderId", "BILL-01")
            .put("resume", "第一餐厅-扫码支付")
            .put("payName", "扫码支付")
            .put("turnoverType", "二维码支付")
            .put("icon", "qrCode-payment")
            .put("typeId", "4")
            .put("typeFrom", "2")
            .put("tranamt", 800.6)
            .put("cardBalance", 8236.5)
            .put("feeAmt", 0.4)
            .put("effectdateStr", "2026-10-20 12:05:00")
            .put("jndatetimeStr", "2026-10-20 12:06:00")
            .put("fromAccount", "校园卡")
            .put("posCode", "POS-01")
            .put("remark", JSONObject.NULL)
            .put("toMerchant", "第一餐厅")
            .put("sno", "20260001")
            .put("labelName", JSONObject.NULL)
        private val page = JSONObject()
            .put("records", JSONArray().put(record))
            .put("total", 1)
            .put("size", 8)
            .put("current", 2)
            .put("pages", 1)
        private val summary = JSONObject().put("income", 20000.0).put("expenses", 20540.4)
        private val types = JSONArray().put(JSONObject().put("id", "4").put("name", "二维码支付").put("icon", "qrCode-payment"))
        private val categories = JSONArray()
            .put(JSONObject().put("turnoverType", "餐饮").put("amount", 300.0).put("typeId", "4").put("icon", "consume"))
            .put(JSONObject().put("turnoverType", "超市").put("amount", 1000.0).put("typeId", "5").put("icon", "consume"))
        private val daily = JSONObject()
            .put("2026-10-02", 500.0)
            .put("2026-10-01", 300.4)
            .put("not-a-date", 99.0)

        val api = YktApi { url ->
            check(url.protocol == "https" && url.host == "yktwx2.huel.edu.cn")
            val data = when (url.path) {
                "/berserker-search/search/personal/turnover" -> page
                "/berserker-search/statistics/turnover/count" -> summary
                "/berserker-search/search/turnoverType" -> types
                "/berserker-search/statistics/turnover" -> categories
                "/berserker-search/statistics/turnover/sum/user" -> daily
                else -> error("Unexpected request path: ${url.path}")
            }
            val body = JSONObject().put("code", 200).put("data", data)
            FakeHttps(url, status, body).also { calls.add(it) }
        }
    }

    private fun query(url: URL): Map<String, String> = url.query.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }

    @Test fun billListAndRankingUseWebsitePagingAndSortParameters() = runBlocking {
        val fake = ScriptedBillsApi()
        val page = fake.api.bills(
            "fake-token", 2, 8, "2026-10-01", "2026-10-31", "4", 100, 5000, " 食堂 ",
        )
        assertEquals(1, page.items.size)
        assertEquals(1, page.total)
        assertEquals(2, page.current)
        assertEquals(1, page.pages)

        val list = fake.calls[0]
        assertEquals("/berserker-search/search/personal/turnover", list.url.path)
        assertEquals("bearer fake-token", list.getRequestProperty("synjones-auth"))
        assertNull(list.getRequestProperty("Cookie"))
        assertEquals(
            mapOf(
                "current" to "2", "size" to "8", "timeFrom" to "2026-10-01", "timeTo" to "2026-10-31",
                "typeId" to "4", "amountFrom" to "100", "amountTo" to "5000", "info" to "食堂",
                "synAccessSource" to "h5",
            ),
            query(list.url),
        )
        assertTrue(list.disconnected)

        val ranking = fake.api.billRanking("fake-token", 2, "2026-10-01", "2026-10-31", 5)
        assertEquals(1, ranking.size)
        val rank = fake.calls[1]
        assertEquals("/berserker-search/search/personal/turnover", rank.url.path)
        val rankQuery = query(rank.url)
        assertEquals("1", rankQuery["current"])
        assertEquals("5", rankQuery["size"])
        assertEquals("2", rankQuery["type"])
        assertEquals("tranamt", rankQuery["sortFields"])
        assertEquals("desc", rankQuery["sortType"])
        assertTrue(rank.disconnected)
    }

    @Test fun billStatisticsRequestsUseMonthAndTypeParameters() = runBlocking {
        val fake = ScriptedBillsApi()
        fake.api.billSummary("fake-token", "2026-10-01", "2026-10-31")
        fake.api.billTypes("fake-token")
        fake.api.billCategories("fake-token", 2, "2026-10-01", "2026-10-31")
        fake.api.billDaily("fake-token", 2, "2026-10")

        assertEquals("/berserker-search/statistics/turnover/count", fake.calls[0].url.path)
        assertEquals(
            mapOf("timeFrom" to "2026-10-01", "timeTo" to "2026-10-31", "synAccessSource" to "h5"),
            query(fake.calls[0].url),
        )
        assertEquals("/berserker-search/search/turnoverType", fake.calls[1].url.path)
        assertEquals(
            mapOf("type" to "2", "timeFrom" to "2026-10-01", "timeTo" to "2026-10-31", "synAccessSource" to "h5"),
            query(fake.calls[2].url),
        )
        assertEquals("/berserker-search/statistics/turnover/sum/user", fake.calls[3].url.path)
        assertEquals(
            mapOf(
                "dateStr" to "2026-10", "dateType" to "month", "statisticsDateStr" to "day",
                "type" to "2", "synAccessSource" to "h5",
            ),
            query(fake.calls[3].url),
        )
        assertTrue(fake.calls.all { it.getRequestProperty("synjones-auth") == "bearer fake-token" })
        assertTrue(fake.calls.all { it.disconnected })
    }

    @Test fun billCountUsesDirectionFilterAndReadsTotal() = runBlocking {
        val fake = ScriptedBillsApi()
        val count = fake.api.billCount("fake-token", "2026-10-01", "2026-10-31", 2)
        assertEquals(1, count)

        val call = fake.calls.single()
        assertEquals("/berserker-search/search/personal/turnover", call.url.path)
        assertEquals(
            mapOf(
                "current" to "1", "size" to "1", "timeFrom" to "2026-10-01", "timeTo" to "2026-10-31",
                "type" to "2", "synAccessSource" to "h5",
            ),
            query(call.url),
        )
        assertTrue(call.disconnected)
    }

    @Test fun billPayloadsAreParsedWithoutNullStringsOrLostCents() = runBlocking {
        val fake = ScriptedBillsApi()
        val record = fake.api.bills("fake-token", 1, 8, "2026-10-01", "2026-10-31", null, null, null, null).items.single()
        assertEquals("BILL-01", record.orderId)
        assertEquals("第一餐厅-扫码支付", record.resume)
        assertEquals(801, record.amountCents)
        assertEquals(8237, record.balanceCents)
        assertEquals(0, record.feeCents)
        assertEquals("", record.remark)
        assertEquals("", record.labelName)
        assertFalse(record.income)

        val summary = fake.api.billSummary("fake-token", "2026-10-01", "2026-10-31")
        assertEquals(20000, summary.incomeCents)
        assertEquals(20540, summary.expensesCents)

        val type = fake.api.billTypes("fake-token").single()
        assertEquals(BillType("4", "二维码支付", "qrCode-payment"), type)

        val categories = fake.api.billCategories("fake-token", 2, "2026-10-01", "2026-10-31")
        assertEquals(listOf("超市", "餐饮"), categories.map { it.name })
        assertEquals(1000, categories[0].amountCents)

        val daily = fake.api.billDaily("fake-token", 2, "2026-10")
        assertEquals(listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), daily.map { it.date })
        assertEquals(listOf(300L, 500L), daily.map { it.amountCents })
    }

    @Test fun rejectedBillRequestMapsToAuthenticationRequired() = runBlocking {
        val fake = ScriptedBillsApi(status = 401)
        try {
            fake.api.bills("fake-token", 1, 8, "2026-10-01", "2026-10-31", null, null, null, null)
            fail("Expected authentication failure")
        } catch (_: AuthenticationRequired) {
        }
        assertTrue(fake.calls.single().disconnected)
    }
}
