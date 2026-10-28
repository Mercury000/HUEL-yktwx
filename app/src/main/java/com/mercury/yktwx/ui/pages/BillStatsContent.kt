package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        TabRow(
            tabs = listOf("支出", "收入"),
            selectedTabIndex = if (stats.type == 2) 0 else 1,
            onTabSelected = { index -> onTypeChange(if (index == 0) 2 else 1) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (stats.error != null) {
        if (!stats.loaded) {
            item(key = "error") {
                BillErrorCard(stats.error, title = "统计数据加载失败", onAction = onRetry)
            }
            return
        }
        item(key = "inline-error") {
            InlineError(stats.error, actionLabel = "重试", onAction = onRetry)
        }
    }
    item(key = "overview") { StatsOverview(stats) }
    if (stats.period.trendSupported) item(key = "trend") {
        Card(Modifier.fillMaxWidth().testTag("bill-stats-trend")) {
            Column(Modifier.padding(20.dp)) {
                StableChartContent({ DailyBillChartPlaceholder() }) {
                    when {
                        stats.dailyLoaded -> DailyBillChart(stats.daily, stats.period.mode)
                        stats.dailyError != null -> StatsBlockError("趋势图读取失败", stats.dailyError, onRetry)
                        else -> DailyBillChartPlaceholder()
                    }
                }
                if (stats.dailyLoaded && stats.dailyError != null) InlineError(stats.dailyError, actionLabel = "重试", onAction = onRetry)
            }
        }
    }
    item(key = "category") {
        Card(Modifier.fillMaxWidth().testTag("bill-stats-category")) {
            Column(Modifier.padding(20.dp)) {
                StableChartContent({ CategoryDonutChartPlaceholder() }) {
                    when {
                        stats.categoriesLoaded -> CategoryDonutChart(stats.categories, stats.type)
                        stats.categoriesError != null -> StatsBlockError("分类统计读取失败", stats.categoriesError, onRetry)
                        else -> CategoryDonutChartPlaceholder()
                    }
                }
                if (stats.categoriesLoaded && stats.categoriesError != null) InlineError(stats.categoriesError, actionLabel = "重试", onAction = onRetry)
            }
        }
    }
    if (!stats.rankingLoaded && stats.rankingError == null) item(key = "ranking") {
        BillLoadingCard("正在加载单笔排行")
    } else if (stats.ranking.isNotEmpty() || stats.rankingError != null) item(key = "ranking") {
        Column {
            if (stats.ranking.isNotEmpty()) StatsRanking(stats.ranking, onOpenDetail)
            stats.rankingError?.let { InlineError(it, actionLabel = "重试", onAction = onRetry) }
        }
    }
}

/** Measure a content-shaped placeholder without placing it; small/empty results keep the same slot. */
@Composable
private fun StableChartContent(placeholder: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(content = {
        Box(Modifier.clearAndSetSemantics {}) { placeholder() }
        Box { content() }
    }) { measurables, constraints ->
        val reserved = measurables[0].measure(constraints.copy(minHeight = 0))
        val actual = measurables[1].measure(constraints.copy(minHeight = maxOf(constraints.minHeight, reserved.height)))
        layout(actual.width, actual.height) { actual.placeRelative(0, 0) }
    }
}

@Composable
private fun StatsBlockError(title: String, error: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        InlineError(error, actionLabel = "重试", onAction = onRetry)
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
            Text(heading, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (amountPending) DashboardPlaceholder(30.sp, Modifier.fillMaxWidth(), fraction = .58f, lineHeight = 38.sp, sample = "¥ 0.00")
            else Text(
                total?.let { "¥ ${money(it)}" } ?: "—",
                fontSize = 30.sp, lineHeight = 38.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = if (stats.type == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            if (detailPending) DashboardPlaceholder(12.sp, Modifier.fillMaxWidth(), fraction = .68f, lineHeight = 18.sp)
            else Text(
                detail,
                fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                Surface(onClick = { onOpenDetail(record) }, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
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
                            Text(
                                record.resume.ifBlank { record.payName.ifBlank { "校园卡交易" } },
                                fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                billTimeLabel(record), fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            signedMoney(record),
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
