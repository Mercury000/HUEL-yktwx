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
)

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
) {
    val activeFilterCount: Int
        get() = (if (typeId != null) 1 else 0) + (if (amountFromCents != null || amountToCents != null) 1 else 0)
}

class BillViewModel internal constructor(
    app: Application,
    private val repository: AccountRepository,
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
    private var accountOwner: String? = null
    private var lastLoadedAt = 0L
    private var lastStatsLoadedAt = 0L

    fun initialize(uiPreview: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = UiPreview.enabled && uiPreview
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState()
            lastLoadedAt = System.currentTimeMillis()
            lastStatsLoadedAt = lastLoadedAt
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
        cancelListJobs()
        statsJob?.cancel()
        loginCheck?.cancel()
        loginCheck = null
        accountOwner = username
        lastLoadedAt = 0L
        lastStatsLoadedAt = 0L
        mutable.value = if (UiPreview.enabled && preview && username != null) PreviewData.billState()
            else BillUiState(needsLogin = username == null)
    }

    fun onActivate() {
        if (mutable.value.statsVisible) { openStats(); return }
        if (!initialized || preview || loginCheck?.isActive == true || loadJob?.isActive == true) return
        loginCheck = viewModelScope.launch {
            val exists = withContext(Dispatchers.IO) { repository.hasCredentials() }
            val current = mutable.value
            if (!exists) {
                clearAuthenticatedData()
                return@launch
            }
            mutable.value = current.copy(needsLogin = false)
            if (!current.loaded || current.error != null || current.summaryError != null || current.countsError != null ||
                System.currentTimeMillis() - lastLoadedAt > CACHE_MS) load()
        }
    }

    fun refresh() {
        if (mutable.value.statsVisible) { refreshStats(); return }
        if (mutable.value.loading || mutable.value.refreshing) return
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState(mutable.value.dateFilter, mutable.value.stats.type)
            lastLoadedAt = System.currentTimeMillis()
            lastStatsLoadedAt = lastLoadedAt
            return
        }
        load(manualRefresh = true)
    }

    fun selectDate(filter: BillDateFilter) {
        if (filter == mutable.value.dateFilter) return
        cancelListJobs()
        val current = mutable.value
        statsJob?.cancel()
        mutable.value = current.copy(
            dateFilter = filter, items = emptyList(), summary = null, counts = null, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, summaryLoaded = false, countsLoaded = false, listLoading = false,
            summaryError = null, countsError = null,
            stats = BillStatsState(period = filter, type = current.stats.type),
        )
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.billState(filter, mutable.value.stats.type).copy(statsVisible = current.statsVisible,
                typeId = current.typeId, amountFromCents = current.amountFromCents, amountToCents = current.amountToCents, info = current.info)
            lastLoadedAt = System.currentTimeMillis()
        } else if (!mutable.value.needsLogin) {
            if (current.statsVisible) loadStats() else load()
        }
    }

    fun applyFilters(typeId: String?, amountFromCents: Long?, amountToCents: Long?) {
        val current = mutable.value
        if (current.typeId == typeId && current.amountFromCents == amountFromCents && current.amountToCents == amountToCents) return
        cancelListJobs()
        mutable.value = current.copy(
            typeId = typeId, amountFromCents = amountFromCents, amountToCents = amountToCents,
            items = emptyList(), summary = null, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, summaryLoaded = false, listLoading = false, summaryError = null,
        )
        if (!preview && !mutable.value.needsLogin) load()
    }

    fun search(info: String) {
        val value = info.trim()
        if (value == mutable.value.info) return
        cancelListJobs()
        mutable.value = mutable.value.copy(
            info = value, items = emptyList(), summary = null, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
            listLoaded = false, listAvailable = false, summaryLoaded = false, listLoading = false, summaryError = null,
        )
        if (!preview && !mutable.value.needsLogin) load()
    }

    fun loadMore() {
        val current = mutable.value
        if (UiPreview.enabled && preview || current.listLoading || !current.listLoaded || current.loadingMore || current.needsLogin) return
        if (current.currentPage <= 0 || current.currentPage >= current.pages) return
        val next = current.currentPage + 1
        val dateFilter = current.dateFilter
        val typeId = current.typeId
        val amountFrom = current.amountFromCents
        val amountTo = current.amountToCents
        val info = current.info
        mutable.value = current.copy(loadingMore = true, loadMoreError = null)
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
                val seen = latest.items.mapTo(HashSet()) { it.orderId }
                val newItems = page.items.filter { it.orderId !in seen }
                val exhausted = page.items.isEmpty() || newItems.isEmpty()
                val loadedPage = next
                val availablePages = if (exhausted) loadedPage else page.pages.coerceAtLeast(loadedPage)
                mutable.value = latest.copy(
                    items = latest.items + newItems,
                    currentPage = loadedPage, pages = availablePages,
                    loadingMore = false, loadMoreError = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                if (auth) { clearAuthenticatedData(); return@launch }
                mutable.value = mutable.value.copy(
                    loadingMore = false, loadMoreError = e.message ?: "加载更多失败，请重试",
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(loadingMore = false, loadMoreError = "加载更多失败，请重试")
            }
        }
    }

    fun openStats() {
        val current = mutable.value
        val samePeriod = current.stats.period == current.dateFilter
        mutable.value = current.copy(statsVisible = true, stats = if (samePeriod) current.stats else BillStatsState(period = current.dateFilter, type = current.stats.type))
        if (samePeriod && current.stats.loading) return
        if (!samePeriod || !current.stats.loaded || current.stats.error != null ||
            current.stats.dailyError != null || current.stats.categoriesError != null || current.stats.rankingError != null ||
            System.currentTimeMillis() - lastStatsLoadedAt > CACHE_MS) loadStats()
    }

    fun showDetails() {
        mutable.value = mutable.value.copy(statsVisible = false)
        onActivate()
    }

    fun showCurrentMonth() {
        cancelListJobs(); statsJob?.cancel()
        val current = mutable.value
        val period = BillDateFilter.currentMonth()
        mutable.value = if (UiPreview.enabled && preview) PreviewData.billState(period) else BillUiState(needsLogin = current.needsLogin, types = current.types, dateFilter = period)
        if (!preview && !current.needsLogin) load()
    }

    fun selectStatsType(type: Int) {
        if (type !in 1..2) return
        if (type == mutable.value.stats.type) return
        statsJob?.cancel()
        mutable.value = mutable.value.copy(stats = BillStatsState(period = mutable.value.stats.period, type = type))
        loadStats()
    }

    fun refreshStats() {
        if (mutable.value.stats.loading) return
        loadStats()
    }

    fun openDetail(record: BillRecord) {
        mutable.value = mutable.value.copy(detail = record)
    }

    private fun load(manualRefresh: Boolean = false) {
        val current = mutable.value
        if (current.needsLogin && !manualRefresh) return
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val dateFilter = current.dateFilter
        val typeId = current.typeId
        val amountFrom = current.amountFromCents
        val amountTo = current.amountToCents
        val info = current.info
        val hasItems = current.items.isNotEmpty()
        // PullToRefresh needs the busy state before any suspension, including the credential check.
        mutable.value = current.copy(
            loading = !manualRefresh && !hasItems,
            refreshing = manualRefresh || hasItems,
            error = null, summaryError = null, countsError = null,
            loadMoreError = null,
            listLoading = true, loadingMore = false,
        )
        loadJob = viewModelScope.launch {
            fun publish(change: (BillUiState) -> BillUiState) {
                val latest = mutable.value
                if (latest.dateFilter == dateFilter && latest.typeId == typeId && latest.amountFromCents == amountFrom &&
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
                    launch {
                        try {
                            val page = repository.bills(1, PAGE_SIZE, dateFilter.timeFrom, dateFilter.timeTo,
                                typeId, amountFrom, amountTo, info.ifBlank { null })
                            currentCoroutineContext().ensureActive()
                            publish { it.copy(items = page.items, currentPage = page.current, pages = page.pages,
                                listLoading = false, listLoaded = true, listAvailable = true, error = null) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (isAuthenticationFailure(e)) throw e
                            publish { it.copy(listLoading = false, listLoaded = true,
                                error = (e as? YktException)?.message ?: "账单加载失败，请稍后重试") }
                        }
                    }
                    launch {
                        val result = optionalData { repository.billSummary(dateFilter.timeFrom, dateFilter.timeTo) }
                        publish { it.copy(summary = result.getOrNull() ?: it.summary, summaryLoaded = true,
                            summaryError = result.exceptionOrNull()?.let(::message)) }
                    }
                    launch {
                        val result = optionalData { repository.billCounts(dateFilter.timeFrom, dateFilter.timeTo) }
                        publish { it.copy(counts = result.getOrNull() ?: it.counts, countsLoaded = true,
                            countsError = result.exceptionOrNull()?.let(::message)) }
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
                lastLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = isAuthenticationFailure(e)
                if (auth) { clearAuthenticatedData(); return@launch }
                mutable.value = mutable.value.copy(
                    needsLogin = false, loading = false, refreshing = false, listLoading = false,
                    error = e.message ?: "账单加载失败，请稍后重试", loaded = true, listLoaded = true,
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    loading = false, refreshing = false, listLoading = false, error = "服务数据异常，请稍后刷新",
                )
            }
        }
    }

    private fun loadStats() {
        if (UiPreview.enabled && preview) {
            mutable.value = mutable.value.copy(
                stats = PreviewData.billStats(mutable.value.stats.period, mutable.value.stats.type),
            )
            lastStatsLoadedAt = System.currentTimeMillis()
            return
        }
        if (mutable.value.needsLogin) return
        statsJob?.cancel()
        val stats = mutable.value.stats
        mutable.value = mutable.value.copy(stats = stats.copy(loading = true, error = null,
            dailyError = null, categoriesError = null, rankingError = null))
        statsJob = viewModelScope.launch {
            fun publish(change: (BillStatsState) -> BillStatsState) {
                val latest = mutable.value
                if (latest.stats.period == stats.period && latest.stats.type == stats.type)
                    mutable.value = latest.copy(stats = change(latest.stats))
            }
            try {
                coroutineScope {
                    launch {
                        val result = optionalData {
                            if (stats.period.trendSupported) repository.billTrend(stats.type, stats.period) else emptyList()
                        }
                        publish { it.copy(daily = result.getOrNull() ?: it.daily,
                            dailyLoaded = it.dailyLoaded || result.isSuccess, dailyError = result.exceptionOrNull()?.let(::message)) }
                    }
                    launch {
                        val result = optionalData { repository.billCategories(stats.type, stats.period.timeFrom, stats.period.timeTo) }
                        publish { it.copy(categories = result.getOrNull() ?: it.categories,
                            categoriesLoaded = it.categoriesLoaded || result.isSuccess, categoriesError = result.exceptionOrNull()?.let(::message)) }
                    }
                    launch {
                        val result = optionalData { repository.billRanking(stats.type, stats.period.timeFrom, stats.period.timeTo, 5) }
                        publish { it.copy(ranking = result.getOrNull() ?: it.ranking,
                            rankingLoaded = it.rankingLoaded || result.isSuccess, rankingError = result.exceptionOrNull()?.let(::message)) }
                    }
                }
                currentCoroutineContext().ensureActive()
                publish { it.copy(loading = false, loaded = true) }
                lastStatsLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = isAuthenticationFailure(e)
                if (auth) { clearAuthenticatedData(); return@launch }
                mutable.value = mutable.value.copy(
                    stats = mutable.value.stats.copy(loading = false, error = e.message ?: "统计数据加载失败"),
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    stats = mutable.value.stats.copy(loading = false, error = "服务数据异常，请稍后重试"),
                )
            }
        }
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
        if (isAuthenticationFailure(e)) throw e
        Result.failure(e)
    }

    private fun isAuthenticationFailure(e: Exception) = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
    private fun message(e: Throwable) = (e as? YktException)?.message ?: "读取失败，请稍后重试"

    private fun clearAuthenticatedData() {
        // A metric can fail authentication after the list has already started another page.
        cancelListJobs(); statsJob?.cancel()
        lastLoadedAt = 0L; lastStatsLoadedAt = 0L
        val current = mutable.value
        mutable.value = BillUiState(needsLogin = true, dateFilter = current.dateFilter,
            typeId = current.typeId, amountFromCents = current.amountFromCents, amountToCents = current.amountToCents,
            info = current.info, statsVisible = current.statsVisible,
            stats = BillStatsState(period = current.stats.period, type = current.stats.type))
    }

    private fun cancelListJobs() {
        loadJob?.cancel(); loadMoreJob?.cancel()
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val CACHE_MS = 2 * 60 * 1000L
    }
}
