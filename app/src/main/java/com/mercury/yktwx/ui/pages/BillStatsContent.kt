package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.BillStatsState
import com.mercury.yktwx.data.BillDateMode
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.billStatsContent(stats: BillStatsState, onTypeChange: (Int) -> Unit, onRetry: () -> Unit, onOpenDetail: (BillRecord) -> Unit) {
    item(key = "tabs") {
        Column {
        TabRow(
            tabs = listOf("支出", "收入"),
            selectedTabIndex = if (stats.type == 2) 0 else 1,
            onTabSelected = { index -> onTypeChange(if (index == 0) 2 else 1) },
            modifier = Modifier.fillMaxWidth(),
        )
        ReadNotice(stats.error.takeIf { stats.loaded }, cached = true, topSpacing = 20.dp)
        }
    }
    if (stats.error != null) {
        if (!stats.loaded) {
            item(key = "error") {
                BillErrorCard(stats.error)
            }
            return
        }
    }
    item(key = "overview") { StatsOverview(stats) }
    if (stats.period.trendSupported) item(key = "trend") {
        Card(Modifier.fillMaxWidth().testTag("bill-stats-trend")) {
            Column(Modifier.padding(20.dp)) {
                key(stats.period, stats.type) {
                    ReadContent(stats, pending = { !it.dailyLoaded && it.dailyError == null },
                        contentKey = { if (it.dailyLoaded) "content" else if (it.dailyError != null) "error" else "pending" }) { displayed ->
                        when {
                            displayed.dailyLoaded -> DailyBillChart(displayed.daily, displayed.period.mode)
                            displayed.dailyError != null -> ReadUnavailable(displayed.dailyError)
                            else -> DailyBillChartPlaceholder()
                        }
                    }
                }
                ReadNotice(stats.dailyError.takeIf { stats.dailyLoaded }, cached = true)
            }
        }
    }
    item(key = "category") {
        ReadLayout(Modifier.fillMaxWidth()) {
        Card(Modifier.fillMaxWidth().testTag("bill-stats-category")) {
            Column(Modifier.padding(20.dp)) {
                key(stats.period, stats.type) {
                    ReadContent(stats, pending = { !it.categoriesLoaded && it.categoriesError == null },
                        contentKey = { if (it.categoriesLoaded) "content" else if (it.categoriesError != null) "error" else "pending" }) { displayed ->
                        when {
                            displayed.categoriesLoaded -> CategoryDonutChart(displayed.categories, displayed.type)
                            displayed.categoriesError != null -> ReadUnavailable(displayed.categoriesError)
                            else -> CategoryDonutChartPlaceholder()
                        }
                    }
                }
                ReadNotice(stats.categoriesError.takeIf { stats.categoriesLoaded }, cached = true)
            }
        }
        }
    }
    if (!stats.rankingLoaded && stats.rankingError == null) item(key = "ranking") {
        BillLoadingCard("正在加载单笔排行")
    } else if (stats.ranking.isNotEmpty() || stats.rankingError != null) item(key = "ranking") {
        Column {
            if (stats.ranking.isNotEmpty()) StatsRanking(stats.ranking, onOpenDetail)
            ReadNotice(stats.rankingError.takeIf { stats.ranking.isNotEmpty() }, cached = true)
            if (stats.ranking.isEmpty() && stats.rankingError != null)
                Card(Modifier.fillMaxWidth()) { ReadUnavailable(stats.rankingError) }
        }
    }
}

@Composable
private fun StatsOverview(stats: BillStatsState) {
    // Categories cover every supported date range. Never switch the total between partial sources.
    val total = stats.categories.takeIf { stats.categoriesLoaded }?.sumOf { it.amountCents }
    val amountPending = !stats.categoriesLoaded && stats.categoriesError == null
    val detailPending = stats.period.trendSupported && !stats.dailyLoaded && stats.dailyError == null
    val typeLabel = if (stats.type == 2) "支出" else "收入"
    val activeDays = stats.daily.count { it.amountCents > 0 }
    val periodLabel = billDateScopeLabel(stats.period)
    val heading = when (stats.period.mode) {
        BillDateMode.Day, BillDateMode.Range -> "${typeLabel}合计"
        BillDateMode.Month, BillDateMode.Year -> "${periodLabel}${typeLabel}合计"
    }
    val detail = when (stats.period.mode) {
        BillDateMode.Month -> "${fullDate(stats.period.start)} 至 ${stats.period.end.dayOfMonth}日" +
            if (stats.dailyLoaded) " · $activeDays 天有记录" else ""
        BillDateMode.Year -> if (stats.dailyLoaded) if (activeDays > 0) "共 $activeDays 个月有记录" else "暂无记录"
            else "${fullDate(stats.period.start)} 至 ${fullDate(stats.period.end)}"
        BillDateMode.Day -> fullDate(stats.period.start)
        BillDateMode.Range -> "${fullDate(stats.period.start)} 至 ${fullDate(stats.period.end)}"
    }
    Card(Modifier.fillMaxWidth().testTag("bill-stats-overview").then(
        if (amountPending) Modifier.semantics { contentDescription = "正在加载统计" } else Modifier)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdatedText(heading, numeric = false, fontSize = 13.sp, lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            ReadContent(amountPending to total, pending = { it.first }, contentKey = { it.first }) { (reading, amount) ->
            if (reading) DashboardPlaceholder(30.sp, Modifier.fillMaxWidth(), fraction = .58f, lineHeight = 38.sp, sample = "¥ 0.00")
            else UpdatedText(
                amount?.let(::money) ?: "—", prefix = "¥ ", identity = listOf(stats.period, stats.type),
                fontSize = 30.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (stats.type == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            }
            ReadText(
                if (detailPending) null else detail, Modifier.fillMaxWidth(), fraction = .68f,
                fontSize = 12.sp, lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun fullDate(date: java.time.LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

@Composable
private fun StatsRanking(ranking: List<BillRecord>, onOpenDetail: (BillRecord) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "单笔排行",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 6.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            ranking.forEachIndexed { index, record ->
                ActionSurface(onClick = { onOpenDetail(record) }, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.width(24.dp),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (index < 3) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        BillIcon(record.icon, size = 34.dp, income = record.income)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            UpdatedText(
                                record.resume.ifBlank { record.payName.ifBlank { "校园卡交易" } },
                                numeric = false, fontSize = 14.sp,
                            )
                            UpdatedText(
                                billTimeLabel(record), numeric = false, fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        UpdatedText(
                            money(record.amountCents), prefix = if (record.income) "+¥ " else "-¥ ", identity = record.stableId,
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = if (record.income) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (index != ranking.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp))
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
