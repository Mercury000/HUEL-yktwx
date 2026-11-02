package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BillStatsState(
    val period: BillDateFilter = BillDateFilter.currentMonth(),
    val type: Int = 2,
    val loading: Boolean = false,
    val error: String? = null,
    val daily: List<BillDailyPoint> = emptyList(),
    val categories: List<BillCategory> = emptyList(),
    val ranking: List<BillRecord> = emptyList(),
    val loaded: Boolean = false,
    val dailyLoaded: Boolean = loaded,
    val categoriesLoaded: Boolean = loaded,
    val rankingLoaded: Boolean = loaded,
    val dailyError: String? = null,
    val categoriesError: String? = null,
    val rankingError: String? = null,
    val displayRestored: Boolean = false,
    val errorKind: ReadFailureKind? = null,
    val dailyErrorKind: ReadFailureKind? = null,
    val categoriesErrorKind: ReadFailureKind? = null,
    val rankingErrorKind: ReadFailureKind? = null,
    /** Cache availability is unknown until local snapshot reads finish. */
    val restoringDisplay: Boolean = false,
)

enum class BillListUpdate { Initial, Restore, Refresh, Append, Query }

data class BillUiState(
    val preview: Boolean = false,
    val needsLogin: Boolean = false,
    val dateFilter: BillDateFilter = BillDateFilter.currentMonth(),
    val items: List<BillRecord> = emptyList(),
    val summary: BillSummary? = null,
    val counts: BillCounts? = null,
    val currentPage: Int = 0,
    val pages: Int = 0,
    val types: List<BillType> = emptyList(),
    val typeId: String? = null,
    val amountFromCents: Long? = null,
    val amountToCents: Long? = null,
    val info: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val error: String? = null,
    val loaded: Boolean = false,
    val detail: BillRecord? = null,
    val statsVisible: Boolean = false,
    val stats: BillStatsState = BillStatsState(),
    val listLoaded: Boolean = loaded,
    val summaryLoaded: Boolean = loaded,
    val countsLoaded: Boolean = loaded,
    val listLoading: Boolean = false,
    val summaryError: String? = null,
    val countsError: String? = null,
    val listAvailable: Boolean = listLoaded && error == null,
    val paginationReady: Boolean = true,
    val listUpdate: BillListUpdate = BillListUpdate.Initial,
    val listUpdateId: Long = 0,
    val animatedOrderIds: Set<String> = emptySet(),
    val displayRestored: Boolean = false,
    val listOnlyRead: Boolean = false,
    val loadMoreErrorKind: ReadFailureKind? = null,
    val errorKind: ReadFailureKind? = null,
    val summaryErrorKind: ReadFailureKind? = null,
    val countsErrorKind: ReadFailureKind? = null,
) {
    val activeFilterCount: Int
        get() = (if (typeId != null) 1 else 0) + (if (amountFromCents != null || amountToCents != null) 1 else 0)
}

class BillViewModel internal constructor(
    app: Application,
    private val repository: AccountRepository,
    private val now: () -> Long = ReadCachePolicy::now,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(BillUiState())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var preview = false
    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var statsJob: Job? = null
    private var loginCheck: Job? = null
    private var initializationJob: Job? = null
    private var displayJob: Job? = null
    private var preparedDisplayKey: String? = null
    private var accountOwner: String? = null
    private var lastLoadedAt = 0L
    private var summaryReadAt = 0L
    private var countsReadAt = 0L
    private data class CachedList(val state: BillUiState, val summaryReadAt: Long, val countsReadAt: Long)
    private val statsCache = RecentReadCache<String, BillStatsState>()
    private val listCache = RecentReadCache<String, CachedList>()
    private var listUpdateId = 0L
    private var pageVisible = false
    private val pendingStatsTypes = mutableSetOf<Int>()
    private fun summaryKey(state: BillUiState = mutable.value) = DisplayKeys.summary(state.dateFilter.timeFrom, state.dateFilter.timeTo)
    private fun countsKey(state: BillUiState = mutable.value) = DisplayKeys.counts(state.dateFilter.timeFrom, state.dateFilter.timeTo)
    private fun listPending(state: BillUiState = mutable.value): Boolean = !state.needsLogin && listRefresh.affects(state.dateFilter) &&
        listOf(listKey(state), summaryKey(state), countsKey(state)).any(listRefresh::needsRead)
    private fun statsPending(stats: BillStatsState = mutable.value.stats): Boolean = !mutable.value.needsLogin && stats.type in pendingStatsTypes && statsRefresh.affects(stats.period) &&
        statsKeys(stats).any(statsRefresh::needsRead)
    private fun statsKeys(stats: BillStatsState) = listOf("categories", "ranking")
        .plus(if (stats.period.trendSupported) listOf("daily") else emptyList())
        .map { DisplayKeys.stats(stats.period, stats.type, it) }
    private val listRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { listPending() }, isReading = { loadJob?.isActive == true || loadMoreJob?.isActive == true || loginCheck?.isActive == true },
        retry = { load(transactionRetry = true, silent = true) })
    private val statsRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { statsPending() }, isReading = { statsJob?.isActive == true },
        retry = { loadStats(transactionRetry = true, silent = true) })

    fun setPageVisible(visible: Boolean) {
        pageVisible = visible
        updateReadVisibility()
        if (visible) onActivate()
        else {
            loginCheck?.cancel(); loginCheck = null
            if (listPending()) {
                cancelListJobs()
                mutable.value = mutable.value.copy(loading = false, refreshing = false, listLoading = false)
            }
            if (statsPending()) {
                statsJob?.cancel()
                mutable.value = mutable.value.copy(stats = mutable.value.stats.copy(loading = false, restoringDisplay = false))
            }
        }
    }
    private fun updateReadVisibility() {
        listRefresh.setVisible(pageVisible && !preview && !mutable.value.statsVisible)
        statsRefresh.setVisible(pageVisible && !preview && mutable.value.statsVisible)
    }

    fun initialize(uiPreview: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = UiPreview.enabled && uiPreview
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState()
            lastLoadedAt = now()
            summaryReadAt = lastLoadedAt; countsReadAt = lastLoadedAt
            rememberStats(lastLoadedAt)
            return
        }
        initializationJob = viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repository.hasCredentials() }
            mutable.value = mutable.value.copy(needsLogin = !exists)
        }
    }

    /** Authentication owns the cache lifetime, including logging back into the same ID. */
    fun accountChanged(username: String?) {
        if (accountOwner == username) return
        initializationJob?.cancel()
        displayJob?.cancel()
        cancelListJobs()
        statsJob?.cancel()
        loginCheck?.cancel()
        loginCheck = null
        accountOwner = username
        preparedDisplayKey = null
        lastLoadedAt = 0L
        summaryReadAt = 0L; countsReadAt = 0L
        statsCache.clear(); listCache.clear()
        listRefresh.clear(); statsRefresh.clear(); pendingStatsTypes.clear()
        mutable.value = if (UiPreview.enabled && preview && username != null) PreviewData.billState()
            else BillUiState(needsLogin = username == null)
        if (!preview && username != null) {
            val snapshot = mutable.value
            displayJob = viewModelScope.launch { restoreListDisplay(username, snapshot) }
        }
    }

    /** A completed transaction makes both date views stale without discarding visible content. */
    fun invalidate(income: Boolean? = null) {
        val period = mutable.value.dateFilter
        pendingStatsTypes.removeAll { type ->
            listOf("categories", "ranking").plus(if (period.trendSupported) listOf("daily") else emptyList())
                .none { statsRefresh.needsRead(DisplayKeys.stats(period, type, it)) }
        }
        pendingStatsTypes += when (income) { true -> listOf(1); false -> listOf(2); null -> listOf(1, 2) }
        listRefresh.invalidate(); statsRefresh.invalidate()
        cancelListJobs(); statsJob?.cancel(); loginCheck?.cancel(); loginCheck = null
        lastLoadedAt = 0L; statsCache.invalidate(); listCache.invalidate()
        summaryReadAt = 0L; countsReadAt = 0L
        mutable.value = mutable.value.copy(loading = false, refreshing = false, listLoading = false, loadingMore = false,
            stats = mutable.value.stats.copy(loading = false, restoringDisplay = false))
    }

    fun onActivate() {
        if (mutable.value.statsVisible) { openStats(); return }
        if (!initialized || preview || loginCheck?.isActive == true || loadJob?.isActive == true) return
        val activatedVisiblePage = pageVisible
        loginCheck = viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repository.hasCredentials() }
            if (activatedVisiblePage && !pageVisible || mutable.value.statsVisible) return@launch
            val current = mutable.value
            if (!exists) {
                clearAuthenticatedData()
                return@launch
            }
            mutable.value = current.copy(needsLogin = false)
            if (!current.loaded || current.error != null || current.summaryError != null || current.countsError != null ||
                listPending() || !ReadCachePolicy.isFresh(lastLoadedAt, now()) || !overviewIsFresh()) loadListQuery()
        }
    }

    fun refresh() {
        if (mutable.value.statsVisible) { refreshStats(); return }
        if (loadJob?.isActive == true || mutable.value.loading || mutable.value.refreshing) return
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState(mutable.value.dateFilter, mutable.value.stats.type)
            lastLoadedAt = now()
            summaryReadAt = lastLoadedAt; countsReadAt = lastLoadedAt
            rememberStats(lastLoadedAt)
            return
        }
        load(manualRefresh = true)
    }

    fun selectDate(filter: BillDateFilter) {
        if (filter == mutable.value.dateFilter) return
        cancelListJobs()
        val current = mutable.value
        statsJob?.cancel()
        summaryReadAt = 0L; countsReadAt = 0L
        mutable.value = current.copy(
            dateFilter = filter, items = emptyList(), summary = null, counts = null, currentPage = 0, pages = 0,
            loaded = false, errorKind = null, error = null, loadMoreErrorKind = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, summaryLoaded = false, countsLoaded = false, listLoading = false,
            summaryErrorKind = null, summaryError = null, countsErrorKind = null, countsError = null,
            displayRestored = false,
            stats = statisticsFor(filter, current.stats.type),
        )
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState(filter, mutable.value.stats.type).copy(statsVisible = current.statsVisible,
                typeId = current.typeId, amountFromCents = current.amountFromCents, amountToCents = current.amountToCents, info = current.info)
            lastLoadedAt = now()
            summaryReadAt = lastLoadedAt; countsReadAt = lastLoadedAt
            rememberStats(lastLoadedAt)
        } else if (!mutable.value.needsLogin) {
            if (current.statsVisible) loadStatsIfNeeded() else loadListQuery()
        }
    }

    fun applyFilters(typeId: String?, amountFromCents: Long?, amountToCents: Long?) {
        val current = mutable.value
        if (current.typeId == typeId && current.amountFromCents == amountFromCents && current.amountToCents == amountToCents) return
        cancelListJobs()
        mutable.value = current.copy(
            typeId = typeId, amountFromCents = amountFromCents, amountToCents = amountToCents,
            items = emptyList(), currentPage = 0, pages = 0,
            loaded = false, errorKind = null, error = null, loadMoreErrorKind = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, listLoading = false,
        )
        if (!preview && !mutable.value.needsLogin) loadListQuery(queryChanged = true)
    }

    fun search(info: String) {
        val value = info.trim()
        if (value == mutable.value.info) return
        cancelListJobs()
        mutable.value = mutable.value.copy(
            info = value, items = emptyList(), currentPage = 0, pages = 0,
            loaded = false, errorKind = null, error = null, loadMoreErrorKind = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, listLoading = false,
        )
        if (!preview && !mutable.value.needsLogin) loadListQuery(queryChanged = true)
    }

    fun clearQuery() {
        val current = mutable.value
        if (current.activeFilterCount == 0 && current.info.isBlank()) return
        cancelListJobs()
        mutable.value = current.copy(typeId = null, amountFromCents = null, amountToCents = null, info = "",
            items = emptyList(), currentPage = 0, pages = 0, loaded = false, errorKind = null, error = null, loadMoreErrorKind = null, loadMoreError = null,
            loading = false, refreshing = false, listLoaded = false, listAvailable = false, listLoading = false)
        if (!preview && !current.needsLogin) loadListQuery(queryChanged = true)
    }

    fun loadMore() {
        val current = mutable.value
        if (UiPreview.enabled && preview || current.listLoading || !current.listLoaded || !current.paginationReady || current.loadingMore || current.needsLogin) return
        if (current.currentPage <= 0 || current.currentPage >= current.pages) return
        val next = current.currentPage + 1
        val dateFilter = current.dateFilter
        val typeId = current.typeId
        val amountFrom = current.amountFromCents
        val amountTo = current.amountToCents
        val info = current.info
        mutable.value = current.copy(loadingMore = true, loadMoreErrorKind = null, loadMoreError = null)
        loadMoreJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.bills(
                        next, PAGE_SIZE, dateFilter.timeFrom, dateFilter.timeTo,
                        typeId, amountFrom, amountTo, info.ifBlank { null },
                    )
                }
                val latest = mutable.value
                if (latest.dateFilter != dateFilter || latest.typeId != typeId || latest.amountFromCents != amountFrom ||
                    latest.amountToCents != amountTo || latest.info != info
                ) return@launch
                val seen = latest.items.mapTo(HashSet()) { it.stableId }
                val newItems = page.items.distinctBy { it.stableId }.filter { it.stableId !in seen }
                val exhausted = page.items.isEmpty() || newItems.isEmpty()
                val loadedPage = next
                val availablePages = if (exhausted) loadedPage else page.pages.coerceAtLeast(loadedPage)
                mutable.value = latest.copy(
                    items = latest.items + newItems,
                    currentPage = loadedPage, pages = availablePages,
                    loadingMore = false, loadMoreErrorKind = null, loadMoreError = null, listUpdate = BillListUpdate.Append,
                    listUpdateId = ++listUpdateId, animatedOrderIds = newItems.mapTo(mutableSetOf()) { it.stableId },
                )
                rememberList(lastLoadedAt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e.isAuthenticationFailure()
                if (auth) { clearAuthenticatedData(); return@launch }
                mutable.value = mutable.value.copy(
                    loadingMore = false, loadMoreErrorKind = e.readFailureKind(), loadMoreError = e.message ?: "加载更多失败，请重试",
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loadingMore = false, loadMoreErrorKind = ReadFailureKind.Other, loadMoreError = "加载更多失败，请重试")
            }
        }
    }

    fun openStats() {
        val current = mutable.value
        val samePeriod = current.stats.period == current.dateFilter
        mutable.value = current.copy(statsVisible = true, stats = if (samePeriod) current.stats else statisticsFor(current.dateFilter, current.stats.type))
        updateReadVisibility()
        if (samePeriod && current.stats.loading) return
        loadStatsIfNeeded()
    }

    fun showDetails() {
        mutable.value = mutable.value.copy(statsVisible = false)
        updateReadVisibility()
        onActivate()
    }

    fun showCurrentMonth() {
        cancelListJobs(); statsJob?.cancel()
        val current = mutable.value
        val period = BillDateFilter.currentMonth()
        mutable.value = if (UiPreview.enabled && preview) PreviewData.billState(period) else BillUiState(needsLogin = current.needsLogin, types = current.types, dateFilter = period)
        updateReadVisibility()
        if (!preview && !current.needsLogin) loadListQuery()
    }

    fun selectStatsType(type: Int) {
        if (type !in 1..2) return
        if (type == mutable.value.stats.type) return
        statsJob?.cancel()
        mutable.value = mutable.value.copy(stats = statisticsFor(mutable.value.stats.period, type))
        loadStatsIfNeeded()
    }

    fun refreshStats() {
        if (statsJob?.isActive == true || mutable.value.stats.loading) return
        loadStats()
    }

    fun openDetail(record: BillRecord) {
        mutable.value = mutable.value.copy(detail = record)
    }

    private fun statsKey(period: BillDateFilter, type: Int) = DisplayKeys.stats(period, type, "complete")

    private fun statisticsFor(period: BillDateFilter, type: Int): BillStatsState {
        if (UiPreview.enabled && preview) return PreviewData.billStats(period, type).also {
            statsCache.put(statsKey(period, type), it, now())
        }
        return statsCache[statsKey(period, type)]?.value?.copy(displayRestored = true)
            ?: BillStatsState(period = period, type = type)
    }

    private fun statsReadSucceeded(stats: BillStatsState) = stats.loaded && !stats.loading &&
        stats.categoriesLoaded && stats.rankingLoaded && (!stats.period.trendSupported || stats.dailyLoaded) &&
        stats.error == null && stats.dailyError == null && stats.categoriesError == null && stats.rankingError == null

    private fun loadStatsIfNeeded() {
        val stats = mutable.value.stats
        if (statsJob?.isActive == true || stats.loading) return
        val readAt = statsCache[statsKey(stats.period, stats.type)]?.readAt ?: 0
        if (statsPending(stats) || !statsReadSucceeded(stats) || !ReadCachePolicy.isFresh(readAt, now())) loadStats()
    }

    private fun rememberStats(readAt: Long) {
        val stats = mutable.value.stats
        if (stats.loaded && !stats.loading) statsCache.put(statsKey(stats.period, stats.type), stats, readAt)
    }

    private fun listKey(state: BillUiState) = DisplayKeys.bills(state.dateFilter, state.typeId,
        state.amountFromCents, state.amountToCents, state.info)

    private fun listReadSucceeded(state: BillUiState) = state.loaded && state.listAvailable &&
        state.summaryLoaded && state.countsLoaded && !state.loading && !state.refreshing && !state.listLoading &&
        state.error == null && state.summaryError == null && state.countsError == null

    private fun overviewIsFresh() = ReadCachePolicy.isFresh(summaryReadAt, now()) && ReadCachePolicy.isFresh(countsReadAt, now())

    private fun rememberList(readAt: Long) {
        val current = mutable.value
        if (!listReadSucceeded(current) || current.loadingMore) return
        // Long histories remain usable in the current view without multiplying their memory use.
        if (current.items.size > MAX_CACHED_LIST_ITEMS) { listCache.remove(listKey(current)); return }
        val snapshot = current.copy(detail = null, statsVisible = false,
            stats = BillStatsState(period = current.dateFilter, type = current.stats.type))
        listCache.put(listKey(current), CachedList(snapshot, summaryReadAt, countsReadAt), readAt)
    }

    private fun loadListQuery(queryChanged: Boolean = false) {
        val current = mutable.value
        val cached = listCache[listKey(current)]
        if (cached != null) {
            val fresh = !listPending(current) && ReadCachePolicy.isFresh(cached.readAt, now())
            val snapshot = cached.value.state
            summaryReadAt = if (queryChanged && current.summary != null) summaryReadAt
                else cached.value.summaryReadAt.takeIf { cached.readAt != 0L } ?: 0
            countsReadAt = if (queryChanged && current.counts != null) countsReadAt
                else cached.value.countsReadAt.takeIf { cached.readAt != 0L } ?: 0
            // Search/filter changes keep the latest overview for the same date range.
            val restored = snapshot.copy(preview = current.preview, needsLogin = current.needsLogin,
                statsVisible = current.statsVisible, stats = current.stats, detail = current.detail,
                types = current.types.ifEmpty { snapshot.types },
                summary = if (queryChanged) current.summary ?: snapshot.summary else snapshot.summary,
                counts = if (queryChanged) current.counts ?: snapshot.counts else snapshot.counts,
                summaryLoaded = snapshot.summaryLoaded || queryChanged && current.summaryLoaded,
                countsLoaded = snapshot.countsLoaded || queryChanged && current.countsLoaded,
                summaryErrorKind = current.summaryErrorKind.takeIf { queryChanged }, summaryError = current.summaryError.takeIf { queryChanged }, countsErrorKind = current.countsErrorKind.takeIf { queryChanged }, countsError = current.countsError.takeIf { queryChanged },
                listUpdate = BillListUpdate.Restore, listUpdateId = ++listUpdateId, animatedOrderIds = emptySet(),
                displayRestored = true, listOnlyRead = false, paginationReady = fresh && snapshot.paginationReady)
            mutable.value = restored
            lastLoadedAt = cached.readAt
            if (fresh && listReadSucceeded(restored) && overviewIsFresh()) return
        }
        load(queryChanged = queryChanged)
    }

    private fun load(manualRefresh: Boolean = false, queryChanged: Boolean = false,
        transactionRetry: Boolean = false, silent: Boolean = false) {
        val current = mutable.value
        if (current.needsLogin && !manualRefresh) return
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val read = listRefresh.started()
        listCache.invalidate(listKey(current))
        lastLoadedAt = 0L
        val dateFilter = current.dateFilter
        val typeId = current.typeId
        val amountFrom = current.amountFromCents
        val amountTo = current.amountToCents
        val info = current.info
        val hasItems = current.items.isNotEmpty()
        val readList = !transactionRetry || listRefresh.needsRead(listKey(current))
        val readSummary = if (transactionRetry) listRefresh.needsRead(summaryKey(current)) else
            !queryChanged || listPending(current) && listRefresh.pending(summaryKey(current)) || !current.summaryLoaded ||
                current.summary == null || current.summaryError != null || !ReadCachePolicy.isFresh(summaryReadAt, now())
        val readCounts = if (transactionRetry) listRefresh.needsRead(countsKey(current)) else
            !queryChanged || listPending(current) && listRefresh.pending(countsKey(current)) || !current.countsLoaded ||
                current.counts == null || current.countsError != null || !ReadCachePolicy.isFresh(countsReadAt, now())
        if (readSummary) summaryReadAt = 0L
        if (readCounts) countsReadAt = 0L
        // PullToRefresh needs the busy state before any suspension, including the credential check.
        mutable.value = current.copy(
            loading = !silent && !manualRefresh && !hasItems,
            refreshing = !silent && (manualRefresh || hasItems),
            errorKind = current.errorKind.takeUnless { readList }, error = current.error.takeUnless { readList }, summaryErrorKind = current.summaryErrorKind.takeUnless { readSummary }, summaryError = current.summaryError.takeUnless { readSummary }, countsErrorKind = current.countsErrorKind.takeUnless { readCounts }, countsError = current.countsError.takeUnless { readCounts },
            loadMoreErrorKind = current.loadMoreErrorKind.takeUnless { readList }, loadMoreError = current.loadMoreError.takeUnless { readList },
            listLoading = readList, loadingMore = false,
            listOnlyRead = queryChanged && !readSummary && !readCounts,
        )
        loadJob = viewModelScope.launch {
            val username = accountOwner.orEmpty()
            displayJob?.join()
            if (!mutable.value.listAvailable && mutable.value.items.isEmpty()) restoreListDisplay(username, current)
            fun publish(change: (BillUiState) -> BillUiState) {
                val latest = mutable.value
                if (listRefresh.accepts(read) && latest.dateFilter == dateFilter && latest.typeId == typeId && latest.amountFromCents == amountFrom &&
                    latest.amountToCents == amountTo && latest.info == info) mutable.value = change(latest)
            }
            try {
                if (manualRefresh) {
                    val exists = withContext(Dispatchers.IO) { repository.hasCredentials() }
                    if (!exists) {
                        clearAuthenticatedData()
                        return@launch
                    }
                    mutable.value = mutable.value.copy(needsLogin = false)
                }
                coroutineScope {
                    if (readList) launch {
                        try {
                            val result = repository.bills(1, PAGE_SIZE, dateFilter.timeFrom, dateFilter.timeTo,
                                typeId, amountFrom, amountTo, info.ifBlank { null })
                            val page = result.copy(items = result.items.distinctBy { it.stableId })
                            currentCoroutineContext().ensureActive()
                            listRefresh.observe(read, listKey(current), page.items)
                            publish {
                                val previousIds = it.items.mapTo(HashSet()) { r -> r.stableId }
                                val update = if (queryChanged) BillListUpdate.Query else if (it.listAvailable) BillListUpdate.Refresh else BillListUpdate.Initial
                                it.copy(items = page.items, currentPage = page.current, pages = page.pages,
                                    listLoading = false, listLoaded = true, listAvailable = true, errorKind = null, error = null, paginationReady = true,
                                    listUpdate = update, listUpdateId = ++listUpdateId,
                                    animatedOrderIds = page.items.filter { r -> update != BillListUpdate.Query && (update == BillListUpdate.Initial || r.stableId !in previousIds) }.mapTo(mutableSetOf()) { r -> r.stableId })
                            }
                            repository.saveDisplay(username, DisplayKeys.bills(dateFilter, typeId, amountFrom, amountTo, info), page, DisplayCodecs.Page)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (e.isAuthenticationFailure()) throw e
                            listRefresh.failed(read, listKey(current))
                            publish { it.copy(listLoading = false, listLoaded = true,
                                errorKind = e.readFailureKind(), error = (e as? YktException)?.message ?: "账单加载失败，请稍后重试") }
                        }
                    }
                    if (readSummary) launch {
                        val result = optionalData { repository.billSummary(dateFilter.timeFrom, dateFilter.timeTo) }
                        listRefresh.record(read, summaryKey(current), result)
                        publish {
                            if (result.isSuccess) summaryReadAt = listRefresh.cacheReadAt(summaryKey(current), now(), listRefresh.affects(dateFilter))
                            it.copy(summary = result.getOrNull() ?: it.summary, summaryLoaded = true,
                                summaryErrorKind = result.exceptionOrNull()?.readFailureKind(), summaryError = result.exceptionOrNull()?.let(::message))
                        }
                        result.getOrNull()?.let { repository.saveDisplay(username, DisplayKeys.summary(dateFilter.timeFrom, dateFilter.timeTo), it, DisplayCodecs.Summary) }
                    }
                    if (readCounts) launch {
                        val result = optionalData { repository.billCounts(dateFilter.timeFrom, dateFilter.timeTo) }
                        listRefresh.record(read, countsKey(current), result)
                        publish {
                            if (result.isSuccess) countsReadAt = listRefresh.cacheReadAt(countsKey(current), now(), listRefresh.affects(dateFilter))
                            it.copy(counts = result.getOrNull() ?: it.counts, countsLoaded = true,
                                countsErrorKind = result.exceptionOrNull()?.readFailureKind(), countsError = result.exceptionOrNull()?.let(::message))
                        }
                        result.getOrNull()?.let { repository.saveDisplay(username, DisplayKeys.counts(dateFilter.timeFrom, dateFilter.timeTo), it, DisplayCodecs.Counts) }
                    }
                    launch {
                        if (current.types.isEmpty()) {
                            val result = optionalData { repository.billTypes() }
                            publish { it.copy(types = result.getOrNull().orEmpty()) }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                publish { it.copy(loading = false, refreshing = false, loaded = true) }
                val latest = mutable.value
                if (latest.dateFilter == dateFilter && latest.typeId == typeId && latest.amountFromCents == amountFrom &&
                    latest.amountToCents == amountTo && latest.info == info && listReadSucceeded(latest)) {
                    lastLoadedAt = if (listPending(latest)) 0L else now()
                    rememberList(lastLoadedAt)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e.isAuthenticationFailure()
                if (auth) { clearAuthenticatedData(); return@launch }
                listRefresh.failed(read, listKey(current))
                mutable.value = mutable.value.copy(
                    needsLogin = false, loading = false, refreshing = false, listLoading = false,
                    errorKind = e.readFailureKind(), error = e.message ?: "账单加载失败，请稍后重试", loaded = true, listLoaded = true,
                )
            } catch (_: Exception) {
                listRefresh.failed(read, listKey(current))
                mutable.value = mutable.value.copy(
                    loading = false, refreshing = false, listLoading = false, errorKind = ReadFailureKind.Other, error = "服务数据异常，请稍后刷新",
                )
            } finally {
                listRefresh.finished(read)
            }
        }
    }

    private fun loadStats(transactionRetry: Boolean = false, silent: Boolean = false) {
        if (UiPreview.enabled && preview) {
            mutable.value = mutable.value.copy(
                stats = PreviewData.billStats(mutable.value.stats.period, mutable.value.stats.type),
            )
            rememberStats(now())
            return
        }
        if (mutable.value.needsLogin) return
        statsJob?.cancel()
        val stats = mutable.value.stats
        val read = statsRefresh.started()
        val dailyKey = DisplayKeys.stats(stats.period, stats.type, "daily")
        val categoriesKey = DisplayKeys.stats(stats.period, stats.type, "categories")
        val rankingKey = DisplayKeys.stats(stats.period, stats.type, "ranking")
        val readDaily = !transactionRetry || statsRefresh.needsRead(dailyKey)
        val readCategories = !transactionRetry || statsRefresh.needsRead(categoriesKey)
        val readRanking = !transactionRetry || statsRefresh.needsRead(rankingKey)
        statsCache.invalidate(statsKey(stats.period, stats.type))
        mutable.value = mutable.value.copy(stats = stats.copy(loading = !silent, restoringDisplay = !stats.loaded, errorKind = null, error = null,
            dailyErrorKind = stats.dailyErrorKind.takeUnless { readDaily }, dailyError = stats.dailyError.takeUnless { readDaily },
            categoriesErrorKind = stats.categoriesErrorKind.takeUnless { readCategories }, categoriesError = stats.categoriesError.takeUnless { readCategories },
            rankingErrorKind = stats.rankingErrorKind.takeUnless { readRanking }, rankingError = stats.rankingError.takeUnless { readRanking }))
        statsJob = viewModelScope.launch {
            val username = accountOwner.orEmpty()
            if (!stats.loaded) {
                val daily = repository.cachedDisplay(username, DisplayKeys.stats(stats.period, stats.type, "daily"), DisplayCodecs.Daily)
                val categories = repository.cachedDisplay(username, DisplayKeys.stats(stats.period, stats.type, "categories"), DisplayCodecs.Categories)
                val ranking = repository.cachedDisplay(username, DisplayKeys.stats(stats.period, stats.type, "ranking"), DisplayCodecs.Records)
                mutable.value = mutable.value.copy(stats = mutable.value.stats.copy(daily = daily.orEmpty(), categories = categories.orEmpty(), ranking = ranking.orEmpty(),
                    dailyLoaded = daily != null, categoriesLoaded = categories != null, rankingLoaded = ranking != null,
                    loaded = daily != null || categories != null || ranking != null,
                    displayRestored = daily != null || categories != null || ranking != null, restoringDisplay = false))
            }
            fun publish(change: (BillStatsState) -> BillStatsState) {
                val latest = mutable.value
                if (statsRefresh.accepts(read) && latest.stats.period == stats.period && latest.stats.type == stats.type)
                    mutable.value = latest.copy(stats = change(latest.stats))
            }
            try {
                coroutineScope {
                    if (readDaily) launch {
                        val result = optionalData {
                            if (stats.period.trendSupported) repository.billTrend(stats.type, stats.period) else emptyList()
                        }
                        statsRefresh.record(read, dailyKey, result)
                        publish { it.copy(daily = result.getOrNull() ?: it.daily,
                            dailyLoaded = it.dailyLoaded || result.isSuccess, dailyErrorKind = result.exceptionOrNull()?.readFailureKind(), dailyError = result.exceptionOrNull()?.let(::message)) }
                        result.getOrNull()?.let { repository.saveDisplay(username, DisplayKeys.stats(stats.period, stats.type, "daily"), it, DisplayCodecs.Daily) }
                    }
                    if (readCategories) launch {
                        val result = optionalData { repository.billCategories(stats.type, stats.period.timeFrom, stats.period.timeTo) }
                        statsRefresh.record(read, categoriesKey, result)
                        publish { it.copy(categories = result.getOrNull() ?: it.categories,
                            categoriesLoaded = it.categoriesLoaded || result.isSuccess, categoriesErrorKind = result.exceptionOrNull()?.readFailureKind(), categoriesError = result.exceptionOrNull()?.let(::message)) }
                        result.getOrNull()?.let { repository.saveDisplay(username, DisplayKeys.stats(stats.period, stats.type, "categories"), it, DisplayCodecs.Categories) }
                    }
                    if (readRanking) launch {
                        val result = optionalData { repository.billRanking(stats.type, stats.period.timeFrom, stats.period.timeTo, 5) }
                        statsRefresh.record(read, rankingKey, result)
                        publish { it.copy(ranking = result.getOrNull() ?: it.ranking,
                            rankingLoaded = it.rankingLoaded || result.isSuccess, rankingErrorKind = result.exceptionOrNull()?.readFailureKind(), rankingError = result.exceptionOrNull()?.let(::message)) }
                        result.getOrNull()?.let { repository.saveDisplay(username, DisplayKeys.stats(stats.period, stats.type, "ranking"), it, DisplayCodecs.Records) }
                    }
                }
                currentCoroutineContext().ensureActive()
                publish { it.copy(loading = false, loaded = true) }
                rememberStats(if (statsReadSucceeded(mutable.value.stats) && !statsPending()) now() else 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e.isAuthenticationFailure()
                if (auth) { clearAuthenticatedData(); return@launch }
                mutable.value = mutable.value.copy(
                    stats = mutable.value.stats.copy(loading = false, errorKind = e.readFailureKind(), error = e.message ?: "统计数据加载失败"),
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    stats = mutable.value.stats.copy(loading = false, errorKind = ReadFailureKind.Other, error = "服务数据异常，请稍后重试"),
                )
            } finally {
                statsRefresh.finished(read)
            }
        }
    }

    private suspend fun restoreListDisplay(username: String, snapshot: BillUiState) {
        val period = snapshot.dateFilter
        val displayKey = DisplayKeys.bills(period, snapshot.typeId, snapshot.amountFromCents, snapshot.amountToCents, snapshot.info)
        if (preparedDisplayKey == displayKey) return
        val page = repository.cachedDisplay(username, displayKey, DisplayCodecs.Page)
        val summary = repository.cachedDisplay(username, DisplayKeys.summary(period.timeFrom, period.timeTo), DisplayCodecs.Summary)
        val counts = repository.cachedDisplay(username, DisplayKeys.counts(period.timeFrom, period.timeTo), DisplayCodecs.Counts)
        val latest = mutable.value
        if (accountOwner != username || latest.dateFilter != period || latest.typeId != snapshot.typeId ||
            latest.amountFromCents != snapshot.amountFromCents || latest.amountToCents != snapshot.amountToCents || latest.info != snapshot.info) return
        preparedDisplayKey = displayKey
        mutable.value = latest.copy(items = page?.items.orEmpty(), currentPage = page?.current ?: 0, pages = page?.pages ?: 0,
            listAvailable = page != null, listLoaded = page != null, paginationReady = false,
            summary = latest.summary ?: summary, counts = latest.counts ?: counts,
            summaryLoaded = summary != null || latest.summaryLoaded, countsLoaded = counts != null || latest.countsLoaded,
            listUpdate = BillListUpdate.Restore, listUpdateId = ++listUpdateId, animatedOrderIds = emptySet(),
            displayRestored = latest.displayRestored || (snapshot.summary == null && snapshot.counts == null &&
                (page != null || summary != null || counts != null)))
    }

    /** Missing optional metrics keep the list usable; an invalid identity cancels the whole load. */
    private suspend fun <T> optionalData(block: suspend () -> T): Result<T> = try {
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        if (e.isAuthenticationFailure()) throw e
        Result.failure(e)
    }

    private fun message(e: Throwable) = (e as? YktException)?.message ?: "读取失败，请稍后重试"

    private fun clearAuthenticatedData() {
        // A metric can fail authentication after the list has already started another page.
        cancelListJobs(); statsJob?.cancel()
        lastLoadedAt = 0L; statsCache.clear(); listCache.clear()
        summaryReadAt = 0L; countsReadAt = 0L
        listRefresh.clear(); statsRefresh.clear(); pendingStatsTypes.clear()
        val current = mutable.value
        mutable.value = BillUiState(needsLogin = true, dateFilter = current.dateFilter,
            typeId = current.typeId, amountFromCents = current.amountFromCents, amountToCents = current.amountToCents,
            info = current.info, statsVisible = current.statsVisible,
            stats = BillStatsState(period = current.stats.period, type = current.stats.type))
    }

    private fun cancelListJobs() {
        listRefresh.pause()
        loadJob?.cancel(); loadMoreJob?.cancel()
        preparedDisplayKey = null
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val MAX_CACHED_LIST_ITEMS = 200
    }
}
