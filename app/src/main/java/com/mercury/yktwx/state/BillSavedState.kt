package com.mercury.yktwx.state

import com.mercury.yktwx.BillStatsState
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.BillDateMode
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
internal data class BillPeriod(val mode: String, val start: String, val end: String) {
    fun resolve(): BillDateFilter? = runCatching {
        val from = LocalDate.parse(start); val to = LocalDate.parse(end)
        require(from.year in 1900..9999 && to.year in 1900..9999 && !from.isAfter(to))
        when (BillDateMode.valueOf(mode)) {
            BillDateMode.Day -> BillDateFilter.day(from).also { require(from == to) }
            BillDateMode.Month -> BillDateFilter.month(java.time.YearMonth.from(from)).also { require(it.start == from && it.end == to) }
            BillDateMode.Year -> BillDateFilter.year(from.year).also { require(it.start == from && it.end == to) }
            BillDateMode.Range -> BillDateFilter.range(from, to)
        }
    }.getOrNull()
    companion object { fun from(value: BillDateFilter) = BillPeriod(value.mode.name, value.timeFrom, value.timeTo) }
}

@Serializable
internal data class BillReference(val id: String, val period: BillPeriod) {
    val valid get() = id.isNotBlank() && id.length <= 512 && period.resolve() != null
}

@Serializable
internal data class BillSavedState(
    val period: BillPeriod,
    val typeId: String?, val minimum: Long?, val maximum: Long?, val search: String,
    val statistics: Boolean, val statsPeriod: BillPeriod, val statsType: Int,
) {
    val valid get() = period.resolve() != null && statsPeriod.resolve() != null &&
        (typeId == null || typeId.length in 1..128) && search.length <= 1024 && statsType in 1..2 &&
        (minimum == null || minimum >= 0) && (maximum == null || maximum >= 0) &&
        (minimum == null || maximum == null || minimum <= maximum)
    fun restore() = BillUiState(dateFilter = period.resolve()!!, typeId = typeId, amountFromCents = minimum,
        amountToCents = maximum, info = search, statsVisible = statistics,
        stats = BillStatsState(period = statsPeriod.resolve()!!, type = statsType))
    companion object {
        fun from(state: BillUiState) = BillSavedState(BillPeriod.from(state.dateFilter), state.typeId,
            state.amountFromCents, state.amountToCents, state.info, state.statsVisible, BillPeriod.from(state.stats.period), state.stats.type)
    }
}
