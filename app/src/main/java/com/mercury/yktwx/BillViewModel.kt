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
                mutable.value = current.copy(needsLogin = true, loaded = false)
                return@launch
            }
            mutable.value = current.copy(needsLogin = false)
            if (!current.loaded || System.currentTimeMillis() - lastLoadedAt > CACHE_MS) load()
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
        )
        if (!preview && !mutable.value.needsLogin) load()
    }

    fun loadMore() {
        val current = mutable.value
        if (UiPreview.enabled && preview || current.loading || current.refreshing || current.loadingMore) return
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
                mutable.value = mutable.value.copy(
                    needsLogin = auth, loadingMore = false,
                    loadMoreError = if (auth) null else e.message ?: "加载更多失败，请重试",
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
        if (!samePeriod || !current.stats.loaded || System.currentTimeMillis() - lastStatsLoadedAt > CACHE_MS) loadStats()
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
        mutable.value = mutable.value.copy(stats = mutable.value.stats.copy(
            type = type, loading = false, error = null, daily = emptyList(), categories = emptyList(), ranking = emptyList(), loaded = false,
        ))
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
            error = null,
            loadMoreError = null,
        )
        loadJob = viewModelScope.launch {
            try {
                if (manualRefresh) {
                    val exists = withContext(Dispatchers.IO) { repository.hasCredentials() }
                    if (!exists) {
                        mutable.value = mutable.value.copy(
                            needsLogin = true, loaded = false, loading = false, refreshing = false,
                        )
                        return@launch
                    }
                    mutable.value = mutable.value.copy(needsLogin = false)
                }
                val page = withContext(Dispatchers.IO) {
                    repository.bills(
                        1, PAGE_SIZE, dateFilter.timeFrom, dateFilter.timeTo,
                        typeId, amountFrom, amountTo, info.ifBlank { null },
                    )
                }
                val summary = try {
                    withContext(Dispatchers.IO) {
                        repository.billSummary(dateFilter.timeFrom, dateFilter.timeTo)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: YktException) {
                    null
                }
                val counts = try {
                    withContext(Dispatchers.IO) {
                        repository.billCounts(dateFilter.timeFrom, dateFilter.timeTo)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: YktException) {
                    null
                }
                val types = if (current.types.isEmpty()) {
                    try {
                        withContext(Dispatchers.IO) { repository.billTypes() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: YktException) {
                        emptyList()
                    }
                } else current.types
                val latest = mutable.value
                if (latest.dateFilter != dateFilter || latest.typeId != typeId || latest.amountFromCents != amountFrom ||
                    latest.amountToCents != amountTo || latest.info != info
                ) return@launch
                mutable.value = latest.copy(
                    needsLogin = false, items = page.items, summary = summary, counts = counts,
                    currentPage = page.current, pages = page.pages, types = types,
                    loading = false, refreshing = false, error = null, loaded = true,
                )
                lastLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                mutable.value = mutable.value.copy(
                    needsLogin = auth, loading = false, refreshing = false,
                    error = if (auth) null else e.message ?: "账单加载失败，请稍后重试",
                    loaded = !auth,
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    loading = false, refreshing = false, error = "服务数据异常，请稍后刷新",
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
        mutable.value = mutable.value.copy(stats = stats.copy(loading = true, error = null))
        statsJob = viewModelScope.launch {
            try {
                val daily = if (stats.period.trendSupported) {
                    withContext(Dispatchers.IO) { repository.billTrend(stats.type, stats.period) }
                } else {
                    emptyList()
                }
                val categories = withContext(Dispatchers.IO) {
                    repository.billCategories(stats.type, stats.period.timeFrom, stats.period.timeTo)
                }
                val ranking = withContext(Dispatchers.IO) {
                    repository.billRanking(stats.type, stats.period.timeFrom, stats.period.timeTo, 5)
                }
                val latest = mutable.value
                if (latest.stats.period != stats.period || latest.stats.type != stats.type) return@launch
                mutable.value = latest.copy(stats = latest.stats.copy(
                    loading = false, error = null, daily = daily, categories = categories, ranking = ranking, loaded = true,
                ))
                lastStatsLoadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                val auth = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                mutable.value = mutable.value.copy(
                    needsLogin = auth,
                    stats = mutable.value.stats.copy(loading = false, error = if (auth) null else e.message ?: "统计数据加载失败"),
                )
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(
                    stats = mutable.value.stats.copy(loading = false, error = "服务数据异常，请稍后重试"),
                )
            }
        }
    }

    private fun cancelListJobs() {
        loadJob?.cancel(); loadMoreJob?.cancel()
    }

    private companion object {
        const val PAGE_SIZE = 8
        const val CACHE_MS = 2 * 60 * 1000L
    }
}
