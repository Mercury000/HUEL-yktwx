package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

enum class BillDateMode { Day, Month, Year, Range }

data class BillDateFilter(
    val mode: BillDateMode,
    val start: LocalDate,
    val end: LocalDate,
) {
    init {
        require(!start.isAfter(end)) { "Bill date range start must not be after end" }
    }

    val timeFrom: String get() = start.toString()
    val timeTo: String get() = end.toString()
    val trendSupported: Boolean get() = mode == BillDateMode.Month || mode == BillDateMode.Year

    fun trendDateStr(): String? = when (mode) {
        BillDateMode.Month -> YearMonth.from(start).toString()
        BillDateMode.Year -> start.year.toString()
        BillDateMode.Day, BillDateMode.Range -> null
    }

    companion object {
        fun day(date: LocalDate) = BillDateFilter(BillDateMode.Day, date, date)

        fun month(month: YearMonth) = BillDateFilter(
            BillDateMode.Month,
            month.atDay(1),
            month.atEndOfMonth(),
        )

        fun year(year: Int) = BillDateFilter(
            BillDateMode.Year,
            LocalDate.of(year, 1, 1),
            LocalDate.of(year, 12, 31),
        )

        fun range(start: LocalDate, end: LocalDate) =
            BillDateFilter(BillDateMode.Range, start, end)

        fun currentMonth(today: LocalDate = LocalDate.now()) =
            month(YearMonth.from(today))
    }
}

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
    /** Some legacy transactions omit orderId; UI identity must still survive refresh/pagination. */
    val stableId: String get() = orderId.ifBlank {
        serialNumber.takeIf { it.isNotBlank() }?.let { "serial:$fromAccount:$it" } ?: "record:" +
            java.security.MessageDigest.getInstance("SHA-256").digest(stableIdentityArray(listOf(fromAccount, typeId,
                typeFrom, effectTime, bookTime, amountCents, balanceCents, resume, merchant)).toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
    override fun toString() = "BillRecord(type=$typeId, amountCents=$amountCents)"
}

data class BillPageData(
    val items: List<BillRecord>,
    val current: Int,
    val pages: Int,
)

data class BillSummary(val incomeCents: Long, val expensesCents: Long)

data class BillCounts(val income: Int, val expenses: Int)

data class BillType(val id: String, val name: String)

data class BillCategory(
    val name: String,
    val amountCents: Long,
)

data class BillDailyPoint(val date: LocalDate, val amountCents: Long)

internal object BillParser {
    fun page(data: JsonObject): BillPageData {
        val array = data.arrayOrNull("records") ?: jsonArrayOf()
        val items = (0 until array.size).mapNotNull { index ->
            array.objectOrNull(index)?.let(::record)
        }
        return BillPageData(
            items = items,
            current = data.intOrDefault("current", 1),
            pages = data.intOrDefault("pages"),
        )
    }

    fun summary(data: JsonObject) = BillSummary(
        incomeCents = data.cents("income"),
        expensesCents = data.cents("expenses"),
    )

    fun types(data: JsonArray): List<BillType> = (0 until data.size).mapNotNull { index ->
        data.objectOrNull(index)?.let { item ->
            BillType(item.text("id"), item.text("name"))
        }
    }

    fun categories(data: JsonArray): List<BillCategory> = (0 until data.size).mapNotNull { index ->
        data.objectOrNull(index)?.let { item ->
            BillCategory(
                name = item.text("turnoverType"),
                amountCents = item.cents("amount"),
            )
        }
    }.sortedByDescending { it.amountCents }

    fun daily(data: JsonObject): List<BillDailyPoint> {
        val points = mutableListOf<BillDailyPoint>()
        val keys = data.keys.iterator()
        while (keys.hasNext()) {
            val key = keys.next()
            val date = runCatching { LocalDate.parse(key) }.getOrNull()
                ?: runCatching { YearMonth.parse(key).atDay(1) }.getOrNull()
                ?: continue
            points += BillDailyPoint(date, data.cents(key))
        }
        return points.sortedBy { it.date }
    }

    private fun record(item: JsonObject) = BillRecord(
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

private fun JsonObject.text(key: String): String = if (isNull(key)) "" else stringOrDefault(key).trim()

private fun JsonObject.cents(key: String): Long = doubleOrDefault(key, 0.0).roundToLong().coerceAtLeast(0)
