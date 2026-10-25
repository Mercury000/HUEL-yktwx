package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

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
    val total: Int = 0,
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
        preview = BuildConfig.DEBUG && uiPreview
        if (preview) {
            mutable.value = previewState()
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
        mutable.value = if (preview && username != null) previewState()
            else BillUiState(needsLogin = username == null)
    }

    fun onActivate() {
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
        if (mutable.value.loading || mutable.value.refreshing) return
        if (preview) {
            mutable.value = previewState(mutable.value.dateFilter, mutable.value.stats.type)
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
        mutable.value = current.copy(
            dateFilter = filter, items = emptyList(), summary = null, counts = null, total = 0, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
        )
        if (preview) {
            mutable.value = previewState(filter, mutable.value.stats.type)
            lastLoadedAt = System.currentTimeMillis()
        } else if (!mutable.value.needsLogin) load()
    }

    fun selectMonth(month: YearMonth) {
        selectDate(BillDateFilter.month(month))
    }

    fun applyFilters(typeId: String?, amountFromCents: Long?, amountToCents: Long?) {
        val current = mutable.value
        if (current.typeId == typeId && current.amountFromCents == amountFromCents && current.amountToCents == amountToCents) return
        cancelListJobs()
        mutable.value = current.copy(
            typeId = typeId, amountFromCents = amountFromCents, amountToCents = amountToCents,
            items = emptyList(), summary = null, total = 0, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
        )
        if (!preview && !mutable.value.needsLogin) load()
    }

    fun search(info: String) {
        val value = info.trim()
        if (value == mutable.value.info) return
        cancelListJobs()
        mutable.value = mutable.value.copy(
            info = value, items = emptyList(), summary = null, total = 0, currentPage = 0, pages = 0,
            loaded = false, error = null, loadMoreError = null, loading = false, refreshing = false,
        )
        if (!preview && !mutable.value.needsLogin) load()
    }

    fun loadMore() {
        val current = mutable.value
        if (preview || current.loading || current.refreshing || current.loadingMore) return
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
                    total = page.total, currentPage = loadedPage, pages = availablePages,
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
        mutable.value = current.copy(stats = current.stats.copy(period = current.dateFilter))
        if (!samePeriod || !current.stats.loaded || System.currentTimeMillis() - lastStatsLoadedAt > CACHE_MS) loadStats()
    }

    fun selectStatsDate(filter: BillDateFilter) {
        if (filter == mutable.value.stats.period) return
        statsJob?.cancel()
        mutable.value = mutable.value.copy(stats = mutable.value.stats.copy(
            period = filter, loading = false, error = null,
            daily = emptyList(), categories = emptyList(), ranking = emptyList(), loaded = false,
        ))
        loadStats()
    }

    fun selectStatsMonth(month: YearMonth) {
        selectStatsDate(BillDateFilter.month(month))
    }

    fun selectStatsType(type: Int) {
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

    fun clearDetail() {
        mutable.value = mutable.value.copy(detail = null)
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
                    needsLogin = false, items = page.items, summary = summary, counts = counts, total = page.total,
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
        if (preview) {
            mutable.value = mutable.value.copy(
                stats = previewStats(mutable.value.stats.period, mutable.value.stats.type),
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

    private fun previewState(
        period: BillDateFilter = BillDateFilter.currentMonth(),
        statsType: Int = 2,
    ): BillUiState {
        val records = previewRecords(period)
        return BillUiState(
            preview = true, needsLogin = false, dateFilter = period, items = records,
            summary = BillSummary(20000, 20540),
            counts = BillCounts(records.count { it.income }, records.count { !it.income }),
            total = records.size, currentPage = 1, pages = 1,
            types = listOf(
                BillType("1", "消费", "consume"), BillType("2", "充值", "recharge"),
                BillType("3", "退款", "refund"), BillType("4", "二维码支付", "qrCode-payment"),
            ),
            loaded = true, stats = previewStats(period, statsType),
        )
    }

    private fun previewStats(period: BillDateFilter, type: Int): BillStatsState {
        val records = previewRecords(period)
        val daily = when (period.mode) {
            BillDateMode.Month -> YearMonth.from(period.start).days().map { day ->
                val amount = when {
                    type == 2 && day.dayOfMonth % 5 == 0 -> 1800L
                    type == 2 && day.dayOfMonth % 3 == 0 -> 640L
                    type == 1 && day.dayOfMonth == 1 -> 20000L
                    else -> 0L
                }
                BillDailyPoint(day, amount)
            }
            BillDateMode.Year -> (1..12).map { monthValue ->
                val month = YearMonth.of(period.start.year, monthValue)
                val amount = when {
                    type == 2 && monthValue % 3 == 0 -> 8400L
                    type == 1 -> 12000L
                    else -> 0L
                }
                BillDailyPoint(month.atDay(1), amount)
            }
            BillDateMode.Day, BillDateMode.Range -> emptyList()
        }
        val categories = if (type == 2) listOf(
            BillCategory("二维码支付", 15000, "4", "qrCode-payment"),
            BillCategory("消费", 4000, "1", "consume"),
            BillCategory("退款", 1540, "3", "refund"),
        ) else listOf(
            BillCategory("充值", 18000, "2", "recharge"),
            BillCategory("退款", 2000, "3", "refund"),
        )
        val ranking = records.filter { (it.typeFrom == "1") == (type == 1) }.sortedByDescending { it.amountCents }.take(5)
        return BillStatsState(
            period = period, type = type, daily = daily, categories = categories, ranking = ranking, loaded = true,
        )
    }

    private fun previewRecords(period: BillDateFilter): List<BillRecord> {
        val today = LocalDate.now()
        val anchor = minOf(today, period.end).coerceAtLeast(period.start)
        fun date(offset: Long): LocalDate = anchor.minusDays(offset).coerceIn(period.start, period.end)
        fun time(day: LocalDate, hour: Int, minute: Int): String =
            LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).format(PREVIEW_TIME)
        fun record(
            id: String, resume: String, turnover: String, icon: String, typeId: String, typeFrom: String,
            amount: Long, day: LocalDate, hour: Int, minute: Int, payName: String, merchant: String, remark: String,
        ) = BillRecord(
            orderId = id, resume = resume, payName = payName, turnoverType = turnover, icon = icon,
            typeId = typeId, typeFrom = typeFrom, amountCents = amount, balanceCents = 8236,
            feeCents = 0, effectTime = time(day, hour, minute), bookTime = time(day, hour, minute + 1),
            fromAccount = "校园卡", position = "POS-01", remark = remark, merchant = merchant,
            serialNumber = "20260001", labelName = "",
        )
        val d0 = date(0); val d1 = date(1); val d2 = date(2); val d3 = date(3); val d4 = date(4); val d5 = date(5)
        return listOf(
            record("DEMO-01", "第一餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 800, d0, 12, 5, "扫码支付", "第一餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-02", "第二餐厅二楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 800, d0, 11, 42, "扫码支付", "第二餐厅二楼", "[reserve=DEMO]"),
            record("DEMO-03", "校园超市-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 1200, d1, 18, 20, "扫码支付", "校园超市", "[reserve=DEMO]"),
            record("DEMO-04", "第一餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 1400, d1, 12, 3, "扫码支付", "第一餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-05", "浴室热水-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 940, d2, 21, 16, "扫码支付", "公共浴室", "[reserve=DEMO]"),
            record("DEMO-06", "校园卡充值", "充值", "recharge", "2", "1", 20000, d3, 9, 0, "校园卡充值", "一卡通中心", "充值"),
            record("DEMO-07", "第三餐厅一楼-扫码支付", "二维码支付", "qrCode-payment", "4", "2", 500, d4, 17, 35, "扫码支付", "第三餐厅一楼", "[reserve=DEMO]"),
            record("DEMO-08", "退款", "退款", "refund", "3", "1", 300, d5, 10, 10, "扫码支付", "校园超市", "退款"),
        )
    }

    private fun YearMonth.days(): List<LocalDate> = (1..lengthOfMonth()).map { atDay(it) }

    private companion object {
        const val PAGE_SIZE = 8
        const val CACHE_MS = 2 * 60 * 1000L
        val PREVIEW_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
