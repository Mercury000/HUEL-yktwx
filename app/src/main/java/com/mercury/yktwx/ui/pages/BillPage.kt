package com.mercury.yktwx.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.preview.PreviewNotes
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.data.BillType
import com.mercury.yktwx.data.ReadFailureKind
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
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun BillPage(
    state: BillUiState,
    settings: ThemeSettings,
    onBack: () -> Unit,
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
    val state = if (LocalReadOffline.current) state.offlineDisplay() else state
    val listHasContent = state.items.isNotEmpty() || state.summary != null || state.counts != null ||
        state.listAvailable
    val statsHasContent = state.stats.categoriesLoaded || state.stats.rankingLoaded ||
        (state.stats.period.trendSupported && state.stats.dailyLoaded)
    val networkError = key(state.dateFilter, state.statsVisible, state.stats.type, state.info,
        state.typeId, state.amountFromCents, state.amountToCents) {
        rememberPageNetworkError(if (state.statsVisible) FeedbackText.mergedNetworkError(
            "趋势" to readError(state.stats.dailyError, state.stats.dailyErrorKind), "分类" to readError(state.stats.categoriesError, state.stats.categoriesErrorKind), "排行" to readError(state.stats.rankingError, state.stats.rankingErrorKind))
            else FeedbackText.mergedNetworkError(
                "明细" to FeedbackText.networkError(readError(state.error, state.errorKind), readError(state.loadMoreError, state.loadMoreErrorKind)),
                "概览" to FeedbackText.networkError(readError(state.summaryError, state.summaryErrorKind), readError(state.countsError, state.countsErrorKind))),
            if (state.statsVisible) state.stats.loading else state.loading || state.refreshing,
            if (state.statsVisible) statsHasContent else listHasContent)
    }
    val initialError = key(state.dateFilter, state.statsVisible, state.stats.type, state.info,
        state.typeId, state.amountFromCents, state.amountToCents) {
        rememberInitialReadError(if (state.statsVisible) statsHasContent else listHasContent,
            if (state.statsVisible) FeedbackText.firstError(readError(state.stats.error, state.stats.errorKind),
                readError(state.stats.categoriesError, state.stats.categoriesErrorKind), readError(state.stats.dailyError, state.stats.dailyErrorKind), readError(state.stats.rankingError, state.stats.rankingErrorKind))
            else FeedbackText.firstError(readError(state.error, state.errorKind), readError(state.summaryError, state.summaryErrorKind), readError(state.countsError, state.countsErrorKind)),
            if (state.statsVisible) !state.stats.loading else !state.loading && !state.refreshing)
    }
    val detailBar = key(state.dateFilter, state.typeId, state.amountFromCents, state.amountToCents, state.info) { rememberTopAppBarState() }
    val statsBar = key(state.stats.period) { rememberTopAppBarState() }
    val scrollBehavior = MiuixScrollBehavior(if (state.statsVisible) statsBar else detailBar)
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    fun dismissSearch() { focus.clearFocus(); keyboard?.hide() }
    var showFilter by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf(TextFieldValue(state.info, TextRange(state.info.length))) }
    var submittedQuery by remember { mutableStateOf(state.info) }
    val currentInfo by rememberUpdatedState(state.info)
    val currentOnSearch by rememberUpdatedState(onSearch)
    fun submitSearch(value: String) {
        submittedQuery = value.trim()
        if (submittedQuery != currentInfo) currentOnSearch(submittedQuery)
    }
    LaunchedEffect(state.info, state.needsLogin) {
        // Acknowledging our search must preserve the draft, cursor and IME composition.
        // External condition removal still updates the input immediately.
        if (state.needsLogin || state.info != submittedQuery) {
            query = TextFieldValue(state.info, TextRange(state.info.length))
            submittedQuery = state.info
        }
    }
    LaunchedEffect(query.text, query.composition, isActive, state.statsVisible, state.needsLogin) {
        if (!isActive || state.statsVisible || state.needsLogin || query.composition != null || query.text.trim() == currentInfo) return@LaunchedEffect
        if (query.text.isNotBlank()) delay(350)
        submitSearch(query.text)
    }
    val arrivalAt = remember(state.listUpdateId) { android.os.SystemClock.elapsedRealtime() }
    val groups = remember(state.items) { state.items.groupByDay() }
    val detailsState = key(state.dateFilter, state.typeId, state.amountFromCents, state.amountToCents, state.info) { rememberLazyListState() }
    val statisticsState = key(state.stats.period) { rememberLazyListState() }
    val listState = if (state.statsVisible) statisticsState else detailsState
    val currentState by rememberUpdatedState(state)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    // The shell owns read activation; the page only resets its transient controls.
    LaunchedEffect(isActive, state.statsVisible) {
        if (!isActive || state.statsVisible) showFilter = false
    }
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
                if (!latest.statsVisible && !latest.loadingMore && latest.paginationReady &&
                    latest.currentPage in 1 until latest.pages
                ) {
                    currentOnLoadMore()
                }
            }
    }

    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
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
                InitialReadTransition(initialError, networkError,
                    if (state.statsVisible) state.stats.displayRestored else state.displayRestored) { displayedError ->
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
                        modifier = Modifier.fillMaxSize().testTag(if (state.statsVisible) "bill-stats-list" else "bill-list").overScrollVertical(),
                        contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = maxOf(padding.calculateBottomPadding() + 28.dp, bottomPadding + 24.dp),
                            start = 16.dp,
                            end = 16.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "mode") {
                            Box(Modifier.width(180.dp).testTag("bill-mode-tabs")) {
                                TabRowWithContour(tabs = listOf("明细", "统计"), selectedTabIndex = if (state.statsVisible) 1 else 0,
                                    onTabSelected = { if (it == 0) onShowDetails() else onOpenStats() },
                                    minWidth = 85.dp, maxWidth = 85.dp, height = 38.dp, cornerRadius = 12.dp, itemSpacing = 0.dp,
                                    colors = TabRowDefaults.tabRowColors(
                                        backgroundColor = if (androidx.compose.foundation.isSystemInDarkTheme()) DashboardPalette.card else DashboardPalette.field,
                                        contentColor = DashboardPalette.muted,
                                        selectedBackgroundColor = if (androidx.compose.foundation.isSystemInDarkTheme()) MiuixTheme.colorScheme.secondaryVariant else DashboardPalette.card,
                                        selectedContentColor = DashboardPalette.ink))
                            }
                        }
                        if (showFailure && !state.needsLogin) {
                            item { ReadUnavailable(checkNotNull(displayedError), Modifier.fillParentMaxHeight()) }
                        } else if (state.needsLogin) {
                            item(key = "login") {
                                BillErrorCard(ReadError("登录已失效，请重新登录", ReadFailureKind.Authentication))
                            }
                        } else {
                            item(key = "date") {
                                PageReadNotice(networkError, 12.dp, loading = if (state.statsVisible)
                                    state.stats.loading && (state.stats.loaded || state.stats.displayRestored)
                                    else !state.listOnlyRead && (state.loading || state.refreshing || isActive && !state.loaded) &&
                                        (state.loaded || state.displayRestored),
                                    failed = if (state.statsVisible) state.stats.error != null || state.stats.dailyError != null ||
                                        state.stats.categoriesError != null || state.stats.rankingError != null
                                        else state.error != null || state.summaryError != null || state.countsError != null) {
                                    BillDateSelector(
                                        filter = state.dateFilter,
                                        minimumDate = BillListMinimumDate,
                                        onDateSelected = onDateSelected,
                                        isActive = isActive,
                                    ) {
                                        if (!state.statsVisible) Box {
                                            IconButton(
                                                onClick = { showFilter = true },
                                            ) {
                                                Icon(MiuixIcons.Filter, "筛选账单", Modifier.size(19.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                            }
                                            if (state.activeFilterCount > 0) {
                                                Badge(
                                                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                                                        .size(7.dp),
                                                    containerColor = MiuixTheme.colorScheme.primary,
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
                                Column {
                                Box(Modifier.fillMaxWidth()) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    modifier = Modifier.trackInputFocus().fillMaxWidth().testTag("bill-search")
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
                                        if (query.text.isNotEmpty()) {
                                            Spacer(Modifier.size(IconButtonDefaults.MinWidth, IconButtonDefaults.MinHeight))
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = {
                                        dismissSearch()
                                        submitSearch(query.text)
                                    }),
                                )
                                // Keep this tap outside BasicTextField's focus/selection gesture path.
                                // The empty trailing slot reserves the same space in the decoration.
                                if (query.text.isNotEmpty()) {
                                    IconButton(onClick = {
                                        query = TextFieldValue()
                                        submitSearch("")
                                    }, modifier = Modifier.align(Alignment.CenterEnd)) {
                                        Icon(MiuixIcons.Clear, contentDescription = "清除搜索",
                                            modifier = Modifier.size(17.dp),
                                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                                }
                                // A cached query is checked below the stable overview and search field.
                                PageReadNotice(null, 0.dp, loading = state.listOnlyRead && state.listLoading && state.listAvailable,
                                    failed = state.error != null, topSpacing = 12.dp) {}
                                ReadNotice(readError(state.error.takeIf { state.items.isNotEmpty() }, state.errorKind), cached = true, topSpacing = 20.dp)
                                }
                            }
                            if (state.activeFilterCount > 0 || state.info.isNotBlank()) item(key = "conditions") {
                                BillQueryConditions(state, onFiltersApplied, onSearch)
                            }
                            when {
                                // Precomposed tabs and the credential check precede loading=true.
                                // An unread list is not a confirmed empty result.
                                state.items.isEmpty() && (!state.listLoaded && state.error == null) ->
                                    item(key = "loading") { BillLoadingCard() }
                                state.error != null && state.items.isEmpty() -> item(key = "error") {
                                    BillErrorCard(checkNotNull(readError(state.error, state.errorKind)))
                                }
                                state.items.isEmpty() -> item(key = "empty") {
                                    if (state.activeFilterCount > 0 || state.info.isNotBlank()) {
                                        BillEmptyCard("没有符合条件的账单", "试试调整搜索或筛选条件。")
                                    } else BillEmptyCard("当前时间段还没有账单", "试试切换日期范围。")
                                }
                                else -> {
                                    items(groups, key = { it.key }) { group ->
                                        Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                                            placementSpec = tween(ContentMotion.Insert, easing = ContentMotion.Easing))) {
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
                                                    key(record.stableId) {
                                                        val arrives = remember(state.listUpdateId) {
                                                            record.stableId in state.animatedOrderIds &&
                                                                android.os.SystemClock.elapsedRealtime() - arrivalAt < 450
                                                        }
                                                        val appended = state.listUpdate != com.mercury.yktwx.BillListUpdate.Refresh
                                                        val visible = remember(state.listUpdateId) { MutableTransitionState(!arrives || appended) }
                                                        visible.targetState = true
                                                        AnimatedVisibility(visibleState = visible,
                                                            enter = expandVertically(tween(ContentMotion.Insert, easing = ContentMotion.Easing), expandFrom = Alignment.Top)) {
                                                            Column(recordArrivalModifier(state.listUpdateId, arrives, appended, index)) {
                                                                BillRow(record) { onOpenDetail(record) }
                                                                if (index != group.records.lastIndex) HorizontalDivider(Modifier.padding(start = 68.dp))
                                                            }
                                                        }
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
    val expensesLabel = "${label}全部支出" + (state.counts?.let { "·共 ${it.expenses} 笔" } ?: "")
    val incomeLabel = "${label}全部收入" + (state.counts?.let { "·共 ${it.income} 笔" } ?: "")
    val pending = !state.summaryLoaded && state.error == null && state.summaryError == null && state.summary == null
    Card(Modifier.fillMaxWidth().testTag("bill-summary")) {
        Column {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                UpdatedText(expensesLabel, numeric = false, fontSize = 13.sp, lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                ReadContent(pending to state.summary?.expensesCents, pending = { it.first }, contentKey = { it.first }) { (reading, amount) ->
                if (reading) DashboardPlaceholder(26.sp, Modifier.fillMaxWidth(), fraction = .72f,
                    lineHeight = 34.sp, sample = "¥ 0.00") else UpdatedText(
                    amount?.let(::money) ?: "—", prefix = "¥ ", identity = state.dateFilter,
                    fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                )
                }
            }
            Column(Modifier.weight(1f)) {
                UpdatedText(incomeLabel, numeric = false, fontSize = 13.sp, lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                ReadContent(pending to state.summary?.incomeCents, pending = { it.first }, contentKey = { it.first }) { (reading, amount) ->
                if (reading) DashboardPlaceholder(26.sp, Modifier.fillMaxWidth(), fraction = .72f,
                    lineHeight = 34.sp, sample = "¥ 0.00") else UpdatedText(
                    amount?.let(::money) ?: "—", prefix = "¥ ", identity = state.dateFilter,
                    fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.primary,
                )
                }
            }
        }
        val error = FeedbackText.firstError(readError(state.summaryError, state.summaryErrorKind), readError(state.countsError, state.countsErrorKind))
        ReadNotice(error, cached = state.summary != null || state.counts != null,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp))
        }
    }
}

@Composable
private fun BillRow(record: BillRecord, onClick: () -> Unit) {
    ActionSurface(onClick = onClick, shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BillIcon(record.icon, income = record.income)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                UpdatedText(
                    record.resume.ifBlank { record.payName.ifBlank { "校园卡交易" } },
                    numeric = false, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                UpdatedText(
                    listOf(record.turnoverType, billTimeLabel(record)).filter { it.isNotBlank() }.joinToString(" · "),
                    numeric = false, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(12.dp))
            UpdatedText(money(record.amountCents), prefix = if (record.income) "+¥ " else "-¥ ", identity = record.stableId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (record.income) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun BillFooter(state: BillUiState, onLoadMore: () -> Unit) {
    val mode = when {
        state.loadingMore -> "loading"
        state.loadMoreError != null -> "error"
        !state.paginationReady -> "verifying"
        state.currentPage in 1 until state.pages -> "more"
        state.items.isNotEmpty() -> "end"
        else -> "empty"
    }
    ContentFade(mode to readError(state.loadMoreError, state.loadMoreErrorKind), Modifier.fillMaxWidth().heightIn(min = 56.dp), contentKey = { it.first }) { (displayed, error) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            when (displayed) {
                "loading" -> {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("加载更多", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                "error" -> InlineError(if (error?.kind == ReadFailureKind.Network)
                    "网络连接失败，更多账单未加载，请再次上拉" else "更多账单加载失败，请再次上拉")
                "more" -> Text("继续上拉加载更多", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                "end" -> Text("没有更多了", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
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
        ReadLayout(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("交易类型", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                AmountTextField(min, { min = it; error = null }, "最低", Modifier.weight(1f), enabled = show,
                    wholeDigits = 9, imeAction = ImeAction.Next, useLabelAsPlaceholder = false)
                Text("—", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                AmountTextField(max, { max = it; error = null }, "最高", Modifier.weight(1f), enabled = show,
                    wholeDigits = 9, useLabelAsPlaceholder = false)
            }
            InlineErrorGroup(error, 18.dp) {
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
                            if (min.isNotBlank() && from == null || max.isNotBlank() && to == null) {
                                error = "请输入有效金额"
                            } else if (from != null && to != null && from > to) {
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
    }
}

@Composable
private fun BillQueryConditions(state: BillUiState, onFiltersApplied: (String?, Long?, Long?) -> Unit, onSearch: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.typeId?.let { type ->
            RemovableCondition(state.types.firstOrNull { it.id == type }?.name ?: "交易类型") {
                onFiltersApplied(null, state.amountFromCents, state.amountToCents)
            }
        }
        if (state.amountFromCents != null || state.amountToCents != null) {
            val from = state.amountFromCents; val to = state.amountToCents
            val label = when {
                from != null && to != null -> "¥ ${moneyInput(from)}–${moneyInput(to)}"
                from != null -> "≥ ¥ ${moneyInput(from)}"
                else -> "≤ ¥ ${moneyInput(requireNotNull(to))}"
            }
            RemovableCondition(label) { onFiltersApplied(state.typeId, null, null) }
        }
        if (state.info.isNotBlank()) RemovableCondition("搜索：${state.info}") { onSearch("") }
    }
}

private fun moneyInput(cents: Long): String {
    if (cents % 100L == 0L) return (cents / 100L).toString()
    return String.format(Locale.CHINA, "%.2f", cents / 100.0).trimEnd('0').trimEnd('.')
}

private fun String.centsOrNull(): Long? {
    val value = trim()
    if (value.isEmpty()) return null
    return value.toBigDecimalOrNull()
        ?.multiply(BigDecimal(100))
        ?.setScale(0, RoundingMode.HALF_UP)
        ?.toLong()
}
