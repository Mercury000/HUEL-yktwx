package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.data.BillType
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.util.Locale

@Composable
fun BillPage(
    state: BillUiState,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onMonthSelected: (YearMonth) -> Unit,
    onFiltersApplied: (String?, Long?, Long?) -> Unit,
    onSearch: (String) -> Unit,
    onOpenStats: () -> Unit,
    onOpenDetail: (BillRecord) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val keyboard = LocalSoftwareKeyboardController.current
    var showFilter by remember { mutableStateOf(false) }
    var query by remember(state.info) { mutableStateOf(state.info) }
    val groups = remember(state.items) { state.items.groupByDay() }
    LaunchedEffect(Unit) { onActivate() }

    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(
                    title = "账单",
                    largeTitle = "账单",
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = onOpenStats) {
                            Icon(AppIcons.Chart, contentDescription = "账单统计", tint = MiuixTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { if (!state.refreshing) onRefresh() }) {
                            Icon(
                                MiuixIcons.Refresh, contentDescription = "刷新账单",
                                tint = if (state.refreshing) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                PullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    topAppBarScrollBehavior = scrollBehavior,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成"),
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
                            if (state.preview) item(key = "preview") { BillPreviewNote() }
                            item(key = "summary") { BillSummaryCard(state) }
                            item(key = "month") {
                                BillMonthSelector(month = state.month, onMonthSelected = onMonthSelected) {
                                    Box {
                                        IconButton(
                                            onClick = { showFilter = true },
                                            backgroundColor = if (state.activeFilterCount > 0) MiuixTheme.colorScheme.primaryContainer else Color.Transparent,
                                        ) {
                                            Icon(
                                                MiuixIcons.Filter, contentDescription = "筛选账单",
                                                modifier = Modifier.size(20.dp),
                                                tint = if (state.activeFilterCount > 0) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface,
                                            )
                                        }
                                        if (state.activeFilterCount > 0) {
                                            Box(
                                                Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                                                    .size(7.dp).background(MiuixTheme.colorScheme.primary, CircleShape),
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "search") {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "搜索商户或摘要",
                                    useLabelAsPlaceholder = true,
                                    singleLine = true,
                                    leadingIcon = {
                                        Icon(
                                            MiuixIcons.Search, contentDescription = null,
                                            modifier = Modifier.padding(start = 16.dp, end = 10.dp).size(20.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                    },
                                    trailingIcon = {
                                        if (query.isNotEmpty()) {
                                            IconButton(onClick = {
                                                query = ""
                                                onSearch("")
                                            }) {
                                                Icon(MiuixIcons.Clear, contentDescription = "清除搜索")
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        keyboard?.hide()
                                        onSearch(query)
                                    }),
                                )
                            }
                            if (state.error != null && state.items.isNotEmpty()) {
                                item(key = "inline-error") {
                                    Card(Modifier.fillMaxWidth()) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(
                                                MiuixIcons.Info, contentDescription = null,
                                                modifier = Modifier.size(20.dp),
                                                tint = MiuixTheme.colorScheme.error,
                                            )
                                            Text(
                                                state.error, modifier = Modifier.weight(1f),
                                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                            TextButton("重试", onClick = onRefresh)
                                        }
                                    }
                                }
                            }
                            when {
                                state.loading && state.items.isEmpty() -> item(key = "loading") { BillLoadingCard() }
                                state.error != null && state.items.isEmpty() -> item(key = "error") {
                                    BillErrorCard(state.error, onAction = onRefresh)
                                }
                                state.items.isEmpty() -> item(key = "empty") {
                                    BillEmptyCard("这个月还没有账单", "试试切换月份，或调整搜索和筛选条件。")
                                }
                                else -> {
                                    items(groups, key = { it.key }) { group ->
                                        Column {
                                            Text(
                                                billDayLabel(group.date),
                                                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                                fontSize = 13.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            )
                                            Card(Modifier.fillMaxWidth()) {
                                                group.records.forEachIndexed { index, record ->
                                                    BillRow(record) { onOpenDetail(record) }
                                                    if (index != group.records.lastIndex) {
                                                        HorizontalDivider(Modifier.padding(start = 68.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    item(key = "footer") { BillFooter(state, onLoadMore) }
                                }
                            }
                        }
                    }
                }
            }
            BillFilterSheet(
                show = showFilter,
                types = state.types,
                appliedTypeId = state.typeId,
                appliedFrom = state.amountFromCents,
                appliedTo = state.amountToCents,
                onDismiss = { showFilter = false },
                onApply = { typeId, from, to ->
                    onFiltersApplied(typeId, from, to)
                    showFilter = false
                },
            )
        }
    }
}

@Composable
private fun BillSummaryCard(state: BillUiState) {
    val label = if (state.month == YearMonth.now()) "本月" else "${state.month.monthValue}月"
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${label}支出", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.summary?.let { "¥ ${money(it.expensesCents)}" } ?: "—",
                        fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("${label}收入", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.summary?.let { "¥ ${money(it.incomeCents)}" } ?: "—",
                        fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "共 ${state.total} 笔",
                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun BillRow(record: BillRecord, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BillIcon(record.icon, income = record.income)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    record.resume.ifBlank { record.payName.ifBlank { "校园卡交易" } },
                    fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    listOf(record.turnoverType, billTimeLabel(record)).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                signedMoney(record),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = if (record.income) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BillFooter(state: BillUiState, onLoadMore: () -> Unit) {
    when {
        state.loadingMore -> Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("加载更多", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        state.loadMoreError != null -> Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton("加载失败，点击重试", onClick = onLoadMore)
        }
        state.currentPage in 1 until state.pages -> {
            LaunchedEffect(state.currentPage, state.pages) { onLoadMore() }
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("上拉加载更多", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        state.items.isNotEmpty() -> Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("没有更多了", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun BillFilterSheet(
    show: Boolean,
    types: List<BillType>,
    appliedTypeId: String?,
    appliedFrom: Long?,
    appliedTo: Long?,
    onDismiss: () -> Unit,
    onApply: (String?, Long?, Long?) -> Unit,
) {
    var typeId by remember { mutableStateOf(appliedTypeId) }
    var min by remember { mutableStateOf(appliedFrom?.let(::moneyInput).orEmpty()) }
    var max by remember { mutableStateOf(appliedTo?.let(::moneyInput).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(show) {
        if (show) {
            typeId = appliedTypeId
            min = appliedFrom?.let(::moneyInput).orEmpty()
            max = appliedTo?.let(::moneyInput).orEmpty()
            error = null
        }
    }
    OverlayBottomSheet(
        show = show,
        title = "筛选账单",
        onDismissRequest = onDismiss,
        renderInRootScaffold = false,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("交易类型", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BillFilterPill("全部", typeId == null) { typeId = null }
                types.forEach { type ->
                    BillFilterPill(type.name, typeId == type.id) { typeId = type.id }
                }
            }
            Text("金额范围（元）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = min, onValueChange = { min = it.amountInput() },
                    modifier = Modifier.weight(1f), label = "最低", singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                Text("—", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextField(
                    value = max, onValueChange = { max = it.amountInput() },
                    modifier = Modifier.weight(1f), label = "最高", singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
            }
            if (error != null) {
                Text(error!!, fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton("重置", onClick = {
                    typeId = null
                    min = ""
                    max = ""
                    error = null
                })
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        val from = min.centsOrNull()
                        val to = max.centsOrNull()
                        if (from != null && to != null && from > to) {
                            error = "最低金额不能高于最高金额"
                        } else {
                            onApply(typeId, from, to)
                        }
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("应用") }
            }
        }
    }
}

private fun moneyInput(cents: Long): String {
    if (cents % 100L == 0L) return (cents / 100L).toString()
    return String.format(Locale.CHINA, "%.2f", cents / 100.0).trimEnd('0').trimEnd('.')
}

private fun String.amountInput(): String {
    val cleaned = filter { it.isDigit() || it == '.' }
    val dot = cleaned.indexOf('.')
    return if (dot < 0) {
        cleaned.take(9)
    } else {
        val whole = cleaned.take(dot).take(9)
        val fraction = cleaned.drop(dot + 1).filter { it.isDigit() }.take(2)
        "$whole.$fraction"
    }
}

private fun String.centsOrNull(): Long? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()
}
