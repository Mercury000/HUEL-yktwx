package com.mercury.yktwx.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToLong

data class BillRecord(
    val orderId: String,
    val resume: String,
    val payName: String,
    val turnoverType: String,
    val icon: String,
    val typeId: String,
    val typeFrom: String,
    val amountCents: Long,
    val balanceCents: Long,
    val feeCents: Long,
    val effectTime: String,
    val bookTime: String,
    val fromAccount: String,
    val position: String,
    val remark: String,
    val merchant: String,
    val serialNumber: String,
    val labelName: String,
) {
    val income: Boolean get() = typeFrom == "1"
    override fun toString() = "BillRecord(type=$typeId, amountCents=$amountCents)"
}

data class BillPageData(
    val items: List<BillRecord>,
    val total: Int,
    val current: Int,
    val pages: Int,
    val size: Int,
)

data class BillSummary(val incomeCents: Long, val expensesCents: Long)

data class BillType(val id: String, val name: String, val icon: String)

data class BillCategory(
    val name: String,
    val amountCents: Long,
    val typeId: String,
    val icon: String,
)

data class BillDailyPoint(val date: LocalDate, val amountCents: Long)

internal object BillParser {
    fun page(data: JSONObject): BillPageData {
        val array = data.optJSONArray("records") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::record)
        }
        return BillPageData(
            items = items,
            total = data.optInt("total"),
            current = data.optInt("current", 1),
            pages = data.optInt("pages"),
            size = data.optInt("size"),
        )
    }

    fun summary(data: JSONObject) = BillSummary(
        incomeCents = data.cents("income"),
        expensesCents = data.cents("expenses"),
    )

    fun types(data: JSONArray): List<BillType> = (0 until data.length()).mapNotNull { index ->
        data.optJSONObject(index)?.let { item ->
            BillType(item.text("id"), item.text("name"), item.text("icon"))
        }
    }

    fun categories(data: JSONArray): List<BillCategory> = (0 until data.length()).mapNotNull { index ->
        data.optJSONObject(index)?.let { item ->
            BillCategory(
                name = item.text("turnoverType"),
                amountCents = item.cents("amount"),
                typeId = item.text("typeId"),
                icon = item.text("icon"),
            )
        }
    }.sortedByDescending { it.amountCents }

    fun daily(data: JSONObject): List<BillDailyPoint> {
        val points = mutableListOf<BillDailyPoint>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
            points += BillDailyPoint(date, data.cents(key))
        }
        return points.sortedBy { it.date }
    }

    private fun record(item: JSONObject) = BillRecord(
        orderId = item.text("orderId"),
        resume = item.text("resume"),
        payName = item.text("payName"),
        turnoverType = item.text("turnoverType"),
        icon = item.text("icon"),
        typeId = item.text("typeId"),
        typeFrom = item.text("typeFrom"),
        amountCents = item.cents("tranamt"),
        balanceCents = item.cents("cardBalance"),
        feeCents = item.cents("feeAmt"),
        effectTime = item.text("effectdateStr"),
        bookTime = item.text("jndatetimeStr"),
        fromAccount = item.text("fromAccount"),
        position = item.text("posCode"),
        remark = item.text("remark"),
        merchant = item.text("toMerchant"),
        serialNumber = item.text("sno"),
        labelName = item.text("labelName"),
    )
}

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

private fun JSONObject.cents(key: String): Long = optDouble(key, 0.0).roundToLong().coerceAtLeast(0)
