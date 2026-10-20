package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.BillStatsState
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.time.YearMonth

@Composable
fun BillStatsPage(
    state: BillUiState,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onTypeChange: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenDetail: (BillRecord) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val stats = state.stats
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(
                    title = "账单统计",
                    largeTitle = "账单统计",
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                PullToRefresh(
                    isRefreshing = stats.loading && stats.loaded,
                    onRefresh = onRetry,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    topAppBarScrollBehavior = scrollBehavior,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = AppRefreshTexts,
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 16.dp,
                            bottom = padding.calculateBottomPadding() + 28.dp,
                            start = 16.dp,
                            end = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.needsLogin) {
                            item(key = "login") {
                                BillErrorCard(
                                    "登录已失效，请返回付款页重新登录。",
                                    actionLabel = "返回",
                                    onAction = onBack,
                                )
                            }
                        } else {
                            item(key = "month") {
                                BillMonthSelector(month = stats.month, onMonthSelected = onMonthChange)
                            }
                            item(key = "tabs") {
                                TabRow(
                                    tabs = listOf("支出", "收入"),
                                    selectedTabIndex = if (stats.type == 2) 0 else 1,
                                    onTabSelected = { index -> onTypeChange(if (index == 0) 2 else 1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            when {
                                stats.loading && !stats.loaded -> item(key = "loading") {
                                    BillLoadingCard("正在加载统计")
                                }
                                stats.error != null && !stats.loaded -> item(key = "error") {
                                    BillErrorCard(stats.error, onAction = onRetry)
                                }
                                stats.error != null -> item(key = "inline-error") {
                                    Card(Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Text(
                                                stats.error, modifier = Modifier.weight(1f),
                                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                            TextButton("重试", onClick = onRetry)
                                        }
                                    }
                                }
                                else -> {
                                    item(key = "overview") { StatsOverview(stats) }
                                    item(key = "daily") {
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(20.dp)) {
                                                DailyBillChart(stats.daily)
                                            }
                                        }
                                    }
                                    item(key = "category") {
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(20.dp)) {
                                                CategoryDonutChart(stats.categories, stats.type)
                                            }
                                        }
                                    }
                                    if (stats.ranking.isNotEmpty()) {
                                        item(key = "ranking") { StatsRanking(stats.ranking, onOpenDetail) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsOverview(stats: BillStatsState) {
    val categoriesTotal = stats.categories.sumOf { it.amountCents }
    val dailyTotal = stats.daily.sumOf { it.amountCents }
    val total = if (categoriesTotal > 0) categoriesTotal else dailyTotal
    val typeLabel = if (stats.type == 2) "支出" else "收入"
    val activeDays = stats.daily.count { it.amountCents > 0 }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${stats.month.monthValue}月${typeLabel}合计", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                "¥ ${money(total)}",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (stats.type == 1) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            Text(
                "${stats.month.year}年${stats.month.monthValue}月1日 至 ${stats.month.atEndOfMonth().dayOfMonth}日 · $activeDays 天有记录",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

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
