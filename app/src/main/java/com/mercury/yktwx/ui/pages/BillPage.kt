package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.preview.PreviewNotes
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.data.BillType
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import java.util.Locale

@Composable
fun BillPage(
    state: BillUiState,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onActivate: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onDateSelected: (BillDateFilter) -> Unit,
    onFiltersApplied: (String?, Long?, Long?) -> Unit,
    onSearch: (String) -> Unit,
    onOpenStats: () -> Unit,
    onOpenDetail: (BillRecord) -> Unit,
    isActive: Boolean = true,
    showBack: Boolean = true,
    bottomPadding: Dp = 0.dp,
    onShowDetails: () -> Unit = {},
    onStatsTypeChange: (Int) -> Unit = {},
    onRefreshStats: () -> Unit = {},
) {
    val listHasContent = state.items.isNotEmpty() || state.summary != null || state.counts != null ||
        state.listAvailable
    val statsHasContent = state.stats.categoriesLoaded || state.stats.rankingLoaded ||
        (state.stats.period.trendSupported && state.stats.dailyLoaded)
    val networkError = key(state.dateFilter, state.statsVisible, state.stats.type, state.info,
        state.typeId, state.amountFromCents, state.amountToCents) {
        rememberPageNetworkError(if (state.statsVisible) FeedbackText.mergedNetworkError(
            "趋势" to state.stats.dailyError, "分类" to state.stats.categoriesError, "排行" to state.stats.rankingError)
            else FeedbackText.mergedNetworkError(
                "明细" to FeedbackText.networkError(state.error, state.loadMoreError),
                "概览" to FeedbackText.networkError(state.summaryError, state.countsError)),
            if (state.statsVisible) state.stats.loading else state.loading || state.refreshing,
            if (state.statsVisible) statsHasContent else listHasContent)
    }
    val initialError = key(state.dateFilter, state.statsVisible, state.stats.type, state.info,
        state.typeId, state.amountFromCents, state.amountToCents) {
        rememberInitialReadError(if (state.statsVisible) statsHasContent else listHasContent,
            if (state.statsVisible) state.stats.error ?: state.stats.categoriesError ?: state.stats.dailyError ?: state.stats.rankingError
            else state.error ?: state.summaryError ?: state.countsError,
            if (state.statsVisible) !state.stats.loading else !state.loading && !state.refreshing)
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    fun dismissSearch() { focus.clearFocus(); keyboard?.hide() }
    var showFilter by remember { mutableStateOf(false) }
    var query by remember(state.info) { mutableStateOf(state.info) }
    val groups = remember(state.items) { state.items.groupByDay() }
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    // Adjacent tabs may be precomposed. Load only when the bill tab is active.
    LaunchedEffect(isActive, state.statsVisible) {
        if (isActive) onActivate()
        if (!isActive || state.statsVisible) showFilter = false
    }
    LaunchedEffect(state.statsVisible, state.dateFilter) { listState.scrollToItem(0) }
    LaunchedEffect(isActive, state.statsVisible) { if (!isActive || state.statsVisible) dismissSearch() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().filter { it }.collect { dismissSearch() }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            listState.isScrollInProgress && lastVisible >= layout.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                val latest = currentState
                if (!latest.statsVisible && !latest.loadingMore && latest.loadMoreError == null &&
                    latest.currentPage in 1 until latest.pages
                ) {
                    currentOnLoadMore()
                }
            }
    }

    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(modifier = Modifier.onGloballyPositioned { contentOrigin = it.positionInWindow() }
            .pointerInput(focus, keyboard) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!searchBounds.contains(down.position + contentOrigin)) dismissSearch()
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                }
            }, topBar = {
            BlurredBar(topGradient = true) {
                if (!showBack) DashboardTopBar("账单", scrollBehavior = scrollBehavior) else TopAppBar(
                    title = "账单",
                    largeTitle = "账单",
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent,
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                DashboardPullToRefresh(
                    loading = if (state.statsVisible) state.stats.loading else state.loading || state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    scrollBehavior = scrollBehavior,
                ) {
                InitialReadTransition(initialError, networkError) { displayedError ->
                    val showFailure = displayedError != null
                    // Only an empty pending shell is retained for the outgoing skeleton.
                    val state = if (!showFailure && (initialError != null ||
                        (if (state.statsVisible) state.stats.loading && !statsHasContent
                         else (state.loading || state.refreshing) && !listHasContent))) state.copy(
                            error = null, loaded = false, listLoaded = false, listAvailable = false,
                            summaryLoaded = false, countsLoaded = false, summaryError = null, countsError = null,
                            stats = com.mercury.yktwx.BillStatsState(period = state.stats.period, type = state.stats.type)) else state
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().overScrollVertical(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = maxOf(padding.calculateBottomPadding() + 28.dp, bottomPadding + 24.dp),
                            start = 16.dp,
                            end = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "mode") {
                            Box(Modifier.width(180.dp)) {
                                TabRowWithContour(tabs = listOf("明细", "统计"), selectedTabIndex = if (state.statsVisible) 1 else 0,
                                    onTabSelected = { if (it == 0) onShowDetails() else onOpenStats() },
                                    minWidth = 85.dp, maxWidth = 85.dp, height = 38.dp, cornerRadius = 12.dp, itemSpacing = 0.dp,
                                    colors = TabRowDefaults.tabRowColors(backgroundColor = DashboardPalette.field,
                                        contentColor = DashboardPalette.muted, selectedBackgroundColor = DashboardPalette.card,
                                        selectedContentColor = DashboardPalette.ink))
                            }
                        }
                        if (showFailure && !state.needsLogin) {
                            item { ReadUnavailable(displayedError.orEmpty(), Modifier.fillParentMaxHeight()) }
                        } else if (state.needsLogin) {
                            item(key = "login") {
                                BillErrorCard("登录已失效，请重新登录")
                            }
                        } else {
                            item(key = "date") {
                                PageNetworkNotice(networkError, 12.dp) {
                                    BillDateSelector(
                                        filter = state.dateFilter,
                                        minimumDate = BillListMinimumDate,
                                        onDateSelected = onDateSelected,
                                        isActive = isActive,
                                    ) {
                                        if (!state.statsVisible) Box {
                                            IconButton(
                                                onClick = { showFilter = true },
                                                backgroundColor = if (state.activeFilterCount > 0) MiuixTheme.colorScheme.primaryContainer else Color.Transparent,
                                            ) {
                                                Icon(MiuixIcons.Filter, "筛选账单", Modifier.size(19.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                            }
                            if (state.statsVisible) {
                                billStatsContent(state.stats, onStatsTypeChange, onRefreshStats, onOpenDetail)
                            } else {
                            item(key = "summary") {
                                BillSummaryCard(state)
                            }
                            item(key = "search") {
                                Box(Modifier.fillMaxWidth()) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier.trackInputFocus().fillMaxWidth().testTag("bill-search")
                                        .onGloballyPositioned { searchBounds = it.boundsInWindow() }
                                        .semantics { contentDescription = "搜索商户或摘要" },
                                    insideMargin = androidx.compose.ui.unit.DpSize(14.dp, 10.dp),
                                    label = "搜索商户或摘要",
                                    useLabelAsPlaceholder = true,
                                    singleLine = true,
                                    cornerRadius = 14.dp,
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                    colors = TextFieldDefaults.textFieldColors(),
                                    leadingIcon = {
                                        Icon(
                                            MiuixIcons.Search, contentDescription = null,
                                            modifier = Modifier.padding(start = 14.dp, end = 10.dp).size(17.dp),
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
                                        dismissSearch()
                                        onSearch(query)
                                    }),
                                )
                                }
                            }
                            if (state.error != null && state.items.isNotEmpty()) {
                                item(key = "inline-error") {
                                    ReadNotice(state.error, cached = true)
                                }
                            }
                            when {
                                // Precomposed tabs and the credential check precede loading=true.
                                // An unread list is not a confirmed empty result.
                                state.items.isEmpty() && (!state.listLoaded && state.error == null) ->
                                    item(key = "loading") { BillLoadingCard() }
                                state.error != null && state.items.isEmpty() -> item(key = "error") {
                                    BillErrorCard(state.error)
                                }
                                state.items.isEmpty() -> item(key = "empty") {
                                    BillEmptyCard("当前时间段还没有账单", "试试切换日期范围，或调整搜索和筛选条件。")
                                }
                                else -> {
                                    items(groups, key = { it.key }) { group ->
                                        Column {
                                            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Text(billDayLabel(group.date), Modifier.weight(1f), fontSize = 11.sp,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                // Only complete, unfiltered ranges can support full daily subtotals.
                                                if (state.listLoaded && state.currentPage == state.pages && state.activeFilterCount == 0 && state.info.isBlank()) {
                                                    val expense = group.records.filter { !it.income }.sumOf { it.amountCents }
                                                    val income = group.records.filter { it.income }.sumOf { it.amountCents }
                                                    if (expense > 0) Text("支出 ¥ ${money(expense)}", fontSize = 10.sp,
                                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                    if (income > 0) Text("收入 ¥ ${money(income)}", Modifier.padding(start = 8.dp), fontSize = 10.sp,
                                                        color = MiuixTheme.colorScheme.primary)
                                                }
                                            }
                                            Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
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
                            if (UiPreview.enabled && state.preview) item(key = "preview") { PreviewNotes.Bill() }
                            }
                        }
                    }
                }
                }
            }
            BillFilterSheet(
                show = showFilter && isActive && !state.statsVisible,
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
    val label = billDateScopeLabel(state.dateFilter)
    val expensesLabel = "${label}支出" + (state.counts?.let { "·共 ${it.expenses} 笔" } ?: "")
    val incomeLabel = "${label}收入" + (state.counts?.let { "·共 ${it.income} 笔" } ?: "")
    val pending = !state.summaryLoaded && state.error == null && state.summaryError == null && state.summary == null
    Card(Modifier.fillMaxWidth()) {
        Column {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(expensesLabel, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                if (pending) DashboardPlaceholder(26.sp, Modifier.fillMaxWidth(), fraction = .72f,
                    lineHeight = 34.sp, sample = "¥ 0.00") else Text(
                    state.summary?.let { "¥ ${money(it.expensesCents)}" } ?: "—",
                    fontSize = 26.sp, lineHeight = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(incomeLabel, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                if (pending) DashboardPlaceholder(26.sp, Modifier.fillMaxWidth(), fraction = .72f,
                    lineHeight = 34.sp, sample = "¥ 0.00") else Text(
                    state.summary?.let { "¥ ${money(it.incomeCents)}" } ?: "—",
                    fontSize = 26.sp, lineHeight = 34.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
        val error = state.summaryError ?: state.countsError
        if (error != null) ReadNotice(error, cached = state.summary != null || state.counts != null,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
        }
    }
}

@Composable
private fun BillRow(record: BillRecord, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
            Text(signedMoney(record), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (record.income) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
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
        state.loadMoreError != null -> ReadNotice(state.loadMoreError, cached = true)
        state.currentPage in 1 until state.pages -> Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("继续上拉加载更多", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
    val session = rememberOverlaySession(show)
    var typeId by remember(session) { mutableStateOf(appliedTypeId) }
    var min by remember(session) { mutableStateOf(appliedFrom?.let(::moneyInput).orEmpty()) }
    var max by remember(session) { mutableStateOf(appliedTo?.let(::moneyInput).orEmpty()) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    OverlayBottomSheet(
        show = show,
        title = "筛选账单",
        onDismissRequest = onDismiss,
        // Cover the main tab bar, not just the nested bill page.
        renderInRootScaffold = true,
    ) {
        Column(
            Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("交易类型", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BillFilterPill("全部", typeId == null, show) { typeId = null }
                types.forEach { type ->
                    BillFilterPill(type.name, typeId == type.id, show) { typeId = type.id }
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
                    modifier = Modifier.trackInputFocus().weight(1f), label = "最低", singleLine = true, enabled = show,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                )
                Text("—", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                TextField(
                    value = max, onValueChange = { max = it.amountInput() },
                    modifier = Modifier.trackInputFocus().weight(1f), label = "最高", singleLine = true, enabled = show,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
            }
            if (!error.isNullOrBlank()) InlineError(error)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton("重置", enabled = show, onClick = {
                    typeId = null
                    min = ""
                    max = ""
                    error = null
                })
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = show,
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
