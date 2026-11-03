package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BillViewModelTest {
    private val vault = MemoryVault()
    private val service = FakeService()
    private var now = 10_000L
    private val model = BillViewModel(
        ApplicationProvider.getApplicationContext<Application>(), AccountRepository(service, vault, readNow = { now }), { now },
    )
    private val store = ViewModelStore().apply { put("bills", model) }

    @After fun cleanup() {
        vault.credentialsGate?.countDown()
        service.requestGate.complete(Unit)
        service.auxiliaryGate.complete(Unit)
        service.queryGates.values.forEach { it.complete(Unit) }
        service.searchGates.values.forEach { it.cancel() }
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun offlineSwitchesAndPullsReuseEachQueryWithoutNetworkReads() {
        model.initialize()
        service.pages = 2
        loadExistingBills()
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.selectStatsType(1); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.showDetails(); await { !model.state.value.loading && !model.state.value.refreshing }
        val listReads = service.requests
        val statsReads = service.statsRequests.size
        service.queries.clear()
        now += 120_000
        model.setPageVisible(true, online = false)
        model.loadMore()
        model.refresh()
        model.openStats()
        model.selectStatsType(2)
        model.refresh()
        model.selectStatsType(1)
        model.refreshStats()
        model.showDetails()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listReads, service.requests)
        assertEquals(statsReads, service.statsRequests.size)
        assertTrue(service.queries.isEmpty())
        assertEquals(listOf(record), model.state.value.items)
        assertFalse(model.state.value.refreshing)
        assertFalse(model.state.value.loadingMore)
        assertFalse(model.state.value.stats.loading)
    }

    @Test fun offlineUnknownQueriesDoNotReadAndReconnectChecksOnlyTheSelectedQueryOnce() {
        model.initialize()
        loadExistingBills()
        model.setPageVisible(true, online = false)
        service.queries.clear()
        val listReads = service.requests
        model.search("商户")
        await { !model.state.value.restoringDisplay }
        model.applyFilters("4", 100, 2000)
        await { !model.state.value.restoringDisplay }
        model.openStats()
        await { !model.state.value.stats.restoringDisplay }
        model.selectStatsType(1)
        await { !model.state.value.stats.restoringDisplay }
        model.selectDate(BillDateFilter.month(java.time.YearMonth.from(model.state.value.dateFilter.start).minusMonths(1)))
        await { !model.state.value.stats.restoringDisplay }
        model.refresh(); model.refreshStats(); model.loadMore()
        assertEquals(listReads, service.requests)
        assertTrue(service.queries.isEmpty())
        assertTrue(service.statsRequests.isEmpty())
        model.setPageVisible(true, online = true, recoveryId = 1)
        model.recoverRead(1)
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.recoverRead(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listReads, service.requests)
        assertEquals(3, service.statsRequests.size)
        assertTrue(service.statsRequests.all { it.second == 1 })
        assertEquals(setOf("trend", "categories", "ranking"), service.queries.toSet())
        model.showDetails()
        await { model.state.value.loaded && !model.state.value.loading && !model.state.value.refreshing }
        assertEquals(listReads + 1, service.requests)
    }

    @Test fun aFailedReconnectAttemptDoesNotCauseASecondRecoveryRead() {
        model.initialize()
        loadExistingBills()
        model.setPageVisible(true, online = false)
        model.openStats(); await { !model.state.value.stats.restoringDisplay }
        service.queryFailures = mapOf("trend" to NetworkFailure())
        model.setPageVisible(true, online = true, recoveryId = 1)
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.dailyError)
        model.recoverRead(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, service.statsRequests.size)
        model.setPageVisible(true, online = false, recoveryId = 1)
        service.queryFailures = emptyMap()
        model.setPageVisible(true, online = true, recoveryId = 2)
        await { !model.state.value.stats.loading }
        assertEquals(6, service.statsRequests.size)
        assertNull(model.state.value.stats.dailyError)
    }

    @Test fun leavingFailedStatisticsCancelsItsRetryEvenAfterTheRequestHasFinished() {
        model.initialize()
        loadExistingBills()
        service.queryFailures = mapOf("trend" to NetworkFailure())
        model.openStats()
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.dailyError)
        service.queryFailures = emptyMap()
        model.selectStatsType(1)
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        val requests = service.statsRequests.size
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals(requests, service.statsRequests.size)
        assertFalse(model.state.value.stats.loading)
    }

    @Test fun changingAFailedSearchCancelsItsRetryAfterTheRequestHasFinished() {
        model.initialize()
        loadExistingBills()
        service.failure = NetworkFailure()
        model.search("旧查询")
        await { model.state.value.error != null && !model.state.value.refreshing }
        service.failure = null
        model.search("新查询")
        await { model.state.value.listAvailable && !model.state.value.refreshing }
        val requests = service.requests
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        assertEquals(requests, service.requests)
        assertEquals("新查询", model.state.value.info)
    }

    @Test fun reconnectWaitsForAnOlderReadThenRetriesOnlyItsFailure() {
        model.initialize()
        loadExistingBills()
        model.setPageVisible(true, recoveryId = 0)
        service.queryGates["trend"] = CompletableDeferred()
        service.queryFailures = mapOf("trend" to NetworkFailure())
        model.openStats()
        await { service.statsRequests.size == 3 }
        model.setPageVisible(true, online = false)
        model.setPageVisible(true, recoveryId = 1)
        model.recoverRead(1)
        assertEquals(3, service.statsRequests.size)
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        service.queryFailures = emptyMap()
        model.recoverRead(1)
        await { !model.state.value.stats.loading }
        assertEquals(6, service.statsRequests.size)
        model.recoverRead(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(6, service.statsRequests.size)
    }

    @Test fun hiddenBillQueriesWaitForSelectionBeforeReading() {
        model.initialize()
        loadExistingBills()
        val listReads = service.requests
        model.setPageVisible(false)
        model.search("商户")
        model.refresh()
        model.openStats()
        model.selectStatsType(1)
        model.refreshStats()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listReads, service.requests)
        assertTrue(service.statsRequests.isEmpty())
        model.setPageVisible(true)
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        assertEquals(3, service.statsRequests.size)
        assertTrue(service.statsRequests.all { it.second == 1 })
    }

    @Test fun reconnectWaitsForPaginationAndRecoversItsFailureWithoutDuplicatingTheRefresh() {
        model.initialize()
        service.pages = 2
        loadExistingBills()
        model.setPageVisible(true)
        service.requestGate = CompletableDeferred()
        service.failure = NetworkFailure()
        model.loadMore(); await { service.requests == 2 }
        model.setPageVisible(true, online = false)
        model.setPageVisible(true, recoveryId = 1)
        model.recoverRead(1)
        assertEquals(2, service.requests)
        service.requestGate.complete(Unit)
        await { !model.state.value.loadingMore }
        assertNotNull(model.state.value.loadMoreError)
        service.failure = null
        model.recoverRead(1)
        await { !model.state.value.loading && !model.state.value.refreshing }
        model.recoverRead(1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(3, service.requests)
        assertNull(model.state.value.loadMoreError)
        assertEquals(listOf(record), model.state.value.items)
    }

    @Test fun changingQueryOfflineClearsTheCancelledPaginationState() {
        model.initialize()
        service.pages = 2
        loadExistingBills()
        service.requestGate = CompletableDeferred()
        model.loadMore(); await { service.requests == 2 }
        model.setPageVisible(true, online = false)
        model.applyFilters("4", null, null)
        await { !model.state.value.restoringDisplay }
        assertFalse("A cancelled page must not keep the new offline query busy", model.state.value.loadingMore)
        assertFalse(model.state.value.listLoading)
        assertFalse(model.state.value.refreshing)
        assertEquals(2, service.requests)
    }

    @Test fun refreshIsBusyBeforeCredentialCheckAndIgnoresRepeatedPulls() {
        vault.credentialsGate = CountDownLatch(1)
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertFalse(model.state.value.loading)
        model.refresh()
        vault.credentialsGate!!.countDown()
        await { service.requests == 1 }
        assertTrue(model.state.value.refreshing)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
        assertEquals(1, service.requests)
        assertTrue(model.state.value.loaded)
    }

    @Test fun changingSearchAndFiltersPreservesOverviewAndDoesNotReadItAgainOrAnimateArrivals() {
        loadExistingBills()
        val summary = model.state.value.summary
        val counts = model.state.value.counts
        service.queries.clear()
        service.requestGate = CompletableDeferred()
        model.search("示例商户")
        assertSame(summary, model.state.value.summary)
        assertSame(counts, model.state.value.counts)
        assertTrue(model.state.value.summaryLoaded)
        await { service.requests == 2 }
        assertEquals(setOf("page"), service.queries.toSet())
        service.requestGate.complete(Unit)
        await { model.state.value.loaded }
        assertEquals(BillListUpdate.Query, model.state.value.listUpdate)
        assertTrue(model.state.value.animatedOrderIds.isEmpty())
        service.queries.clear()
        model.applyFilters("4", 100, 2000)
        await { model.state.value.loaded }
        assertSame(summary, model.state.value.summary)
        assertSame(counts, model.state.value.counts)
        assertEquals(setOf("page"), service.queries.toSet())
        val before = service.requests
        model.clearQuery(); await { model.state.value.loaded }
        assertEquals(before, service.requests)
        assertEquals(BillListUpdate.Restore, model.state.value.listUpdate)
        assertEquals("", model.state.value.info)
        assertEquals(0, model.state.value.activeFilterCount)
    }

    @Test fun queryChangeWhileOverviewIsStillReadingCompletesTheMissingMetrics() {
        service.requestGate.complete(Unit)
        service.queryGates["summary"] = CompletableDeferred()
        service.queryGates["income"] = CompletableDeferred()
        model.refresh()
        await { model.state.value.listAvailable && service.queries.contains("income") }
        model.search("商户")
        service.queryGates.getValue("summary").complete(Unit)
        service.queryGates.getValue("income").complete(Unit)
        await { model.state.value.loaded && model.state.value.summary != null && model.state.value.counts != null }
        assertNull(model.state.value.summaryError)
        assertNull(model.state.value.countsError)
    }

    @Test fun newerSearchWinsWhenThePreviousRequestFinishesLater() {
        loadExistingBills()
        val previous = CompletableDeferred<BillPageData>()
        val latest = CompletableDeferred<BillPageData>()
        service.searchGates["餐"] = previous
        service.searchGates["餐厅"] = latest
        model.search("餐")
        await { service.requests == 2 }
        model.search("餐厅")
        await { service.requests == 3 }
        val latestRecord = record.copy(orderId = "latest-search")
        latest.complete(BillPageData(listOf(latestRecord), 1, 1))
        await { model.state.value.loaded }
        previous.complete(BillPageData(listOf(record.copy(orderId = "outdated-search")), 1, 1))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("餐厅", model.state.value.info)
        assertEquals(listOf(latestRecord), model.state.value.items)
        assertEquals(BillListUpdate.Query, model.state.value.listUpdate)
        assertTrue(model.state.value.animatedOrderIds.isEmpty())
    }

    @Test fun emptyBillListStaysRefreshingUntilRequestCompletes() {
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
        assertTrue(model.state.value.items.isEmpty())

        service.requestGate = CompletableDeferred()
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertFalse(model.state.value.loading)
        await { service.requests == 2 }
        assertTrue(model.state.value.refreshing)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
    }

    @Test fun existingBillsStayVisibleThroughoutCredentialCheckAndReload() {
        loadExistingBills()
        vault.credentialsGate = CountDownLatch(1)
        service.requestGate = CompletableDeferred()
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertEquals(listOf(record), model.state.value.items)
        vault.credentialsGate!!.countDown()
        await { service.requests == 2 }
        assertTrue(model.state.value.refreshing)
        assertEquals(listOf(record), model.state.value.items)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
    }

    @Test fun missingCredentialsEndsRefreshAndRequiresLogin() {
        vault.savedCredentials = null
        vault.credentialsGate = CountDownLatch(1)
        model.refresh()
        assertTrue(model.state.value.refreshing)
        vault.credentialsGate!!.countDown()
        await { !model.state.value.refreshing }
        assertTrue(model.state.value.needsLogin)
        assertFalse(model.state.value.loaded)
        assertEquals(0, service.requests)
    }

    @Test fun failedRequestEndsRefreshWithoutDiscardingExistingBills() {
        loadExistingBills()
        service.failure = NetworkFailure()
        model.refresh()
        await { !model.state.value.refreshing }
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.needsLogin)
        assertNotNull(model.state.value.error)
        assertEquals(listOf(record), model.state.value.items)
    }

    @Test fun credentialReadFailureDoesNotLeaveRefreshStuck() {
        vault.readFailure = IllegalStateException("fake vault failure")
        model.refresh()
        await { !model.state.value.refreshing }
        assertNotNull(model.state.value.error)
        assertEquals(0, service.requests)
    }

    @Test fun changingMonthCancelsPendingRefresh() {
        model.initialize()
        await { vault.reads > 0 }
        val originalMonth = model.state.value.dateFilter.timeFrom
        model.refresh()
        assertTrue(model.state.value.refreshing)
        await { service.requests == 1 }
        val nextMonth = java.time.YearMonth.from(model.state.value.dateFilter.start).minusMonths(1)
        model.selectDate(BillDateFilter.month(nextMonth))
        assertFalse(model.state.value.refreshing)
        assertTrue(model.state.value.loading)
        await { service.requests == 2 }
        service.requestGate.complete(Unit)
        await { model.state.value.loaded }
        assertEquals(nextMonth, java.time.YearMonth.from(model.state.value.dateFilter.start))
        assertEquals(listOf(originalMonth, nextMonth.atDay(1).toString()), service.requestMonths)
    }
    @Test fun firstPageSummaryCountsAndTypesStartTogether() {
        service.auxiliaryGate = CompletableDeferred()
        model.refresh()
        await { service.queries.containsAll(listOf("page", "summary", "income", "expenses", "types")) }
        assertTrue(model.state.value.refreshing)
        service.requestGate.complete(Unit)
        service.auxiliaryGate.complete(Unit)
        await { model.state.value.loaded }
        assertEquals(BillCounts(0, 0), model.state.value.counts)
        assertEquals(listOf(BillType("4", "二维码支付")), model.state.value.types)
    }
    @Test fun statsTrendCategoriesAndRankingStartTogether() {
        loadExistingBills()
        service.queries.clear()
        service.auxiliaryGate = CompletableDeferred()
        model.openStats()
        await { service.queries.containsAll(listOf("trend", "categories", "ranking")) }
        assertTrue(model.state.value.stats.loading)
        service.auxiliaryGate.complete(Unit)
        await { model.state.value.stats.loaded }
    }

    @Test fun returningToSuccessfulStatsReusesTheCorrectTypeAndPeriodWithoutLoading() {
        loadExistingBills()
        val original = model.state.value.dateFilter
        service.categories = listOf(BillCategory("支出分类", 800))
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        service.categories = listOf(BillCategory("收入分类", 1200))
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        val calls = service.statsRequests.size
        model.selectStatsType(2)
        assertFalse(model.state.value.stats.loading)
        assertEquals(listOf(BillCategory("支出分类", 800)), model.state.value.stats.categories)
        model.selectStatsType(1)
        assertFalse(model.state.value.stats.loading)
        assertEquals(listOf(BillCategory("收入分类", 1200)), model.state.value.stats.categories)
        assertEquals(calls, service.statsRequests.size)

        val next = BillDateFilter.month(java.time.YearMonth.from(original.start).minusMonths(1))
        service.categories = listOf(BillCategory("其他月份", 2000))
        model.selectDate(next); await { !model.state.value.stats.loading }
        val beforeReturn = service.statsRequests.size
        model.selectDate(original)
        assertEquals(original, model.state.value.stats.period)
        assertFalse(model.state.value.stats.loading)
        assertEquals(listOf(BillCategory("收入分类", 1200)), model.state.value.stats.categories)
        assertEquals(beforeReturn, service.statsRequests.size)
    }

    @Test fun statsFreshnessBelongsToEachQueryAndExpiresWithoutClearingVisibleContent() {
        loadExistingBills()
        service.categories = listOf(BillCategory("支出", 800))
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        now += 30_000
        service.categories = listOf(BillCategory("收入", 1200))
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        now = 69_999
        val before = service.statsRequests.size
        model.selectStatsType(2)
        assertFalse(model.state.value.stats.loading)
        assertEquals(before, service.statsRequests.size)
        now = 70_000
        model.selectStatsType(1)
        assertFalse(model.state.value.stats.loading)
        service.queryGates["categories"] = CompletableDeferred()
        model.selectStatsType(2)
        assertTrue(model.state.value.stats.loading)
        assertEquals(listOf(BillCategory("支出", 800)), model.state.value.stats.categories)
        await { service.statsRequests.size == before + 3 }
        service.queryGates.getValue("categories").complete(Unit)
        await { !model.state.value.stats.loading }
    }

    @Test fun failedStatsAreDisplayedButRetriedWhenReturningWithinTheReuseWindow() {
        loadExistingBills()
        service.queryFailures = mapOf("trend" to NetworkFailure())
        service.categories = listOf(BillCategory("已读取分类", 800))
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.dailyError)
        service.queryFailures = emptyMap()
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        val before = service.statsRequests.size
        service.queryGates["trend"] = CompletableDeferred()
        model.selectStatsType(2)
        assertTrue(model.state.value.stats.loading)
        assertEquals(listOf(BillCategory("已读取分类", 800)), model.state.value.stats.categories)
        await { service.statsRequests.size == before + 3 }
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        assertNull(model.state.value.stats.dailyError)
    }

    @Test fun interruptedManualStatsRefreshRemainsStaleWhenReturningToThatType() {
        loadExistingBills()
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        model.selectStatsType(2)
        service.queryGates["categories"] = CompletableDeferred()
        val before = service.statsRequests.size
        model.refreshStats(); await { service.statsRequests.size == before + 3 }
        model.selectStatsType(1)
        assertFalse(model.state.value.stats.loading)
        model.selectStatsType(2)
        assertTrue(model.state.value.stats.loading)
        await { service.statsRequests.size == before + 6 }
        service.queryGates.getValue("categories").complete(Unit)
        await { !model.state.value.stats.loading }
    }

    @Test fun invalidationAndLogoutRevokeEveryStatsQueryIncludingTheSameAccount() {
        model.initialize(); model.accountChanged("test-user")
        loadExistingBills()
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        model.invalidate()
        val beforeInvalidation = service.statsRequests.size
        model.selectStatsType(2); await { !model.state.value.stats.loading }
        model.selectStatsType(1); await { !model.state.value.stats.loading }
        assertEquals(beforeInvalidation + 6, service.statsRequests.size)
        model.accountChanged(null); model.accountChanged("test-user")
        service.queryGates["categories"] = CompletableDeferred()
        model.openStats()
        assertTrue(model.state.value.stats.loading)
        assertFalse(model.state.value.stats.categoriesLoaded)
        service.queryGates.getValue("categories").complete(Unit)
        await { !model.state.value.stats.loading }
    }

    @Test fun returningToBillQueriesKeepsPaginationAndSkipsArrivalAnimationUntilExpired() {
        val original = model.state.value.dateFilter
        service.pages = 2
        service.nextItems = listOf(record.copy(orderId = "second-page"))
        loadExistingBills()
        model.loadMore(); await { model.state.value.currentPage == 2 }
        model.search("商户"); await { model.state.value.loaded && !model.state.value.loading }
        val before = service.requests
        model.search("")
        assertEquals(before, service.requests)
        assertEquals(2, model.state.value.currentPage)
        assertTrue(model.state.value.paginationReady)
        assertEquals(BillListUpdate.Restore, model.state.value.listUpdate)
        assertTrue(model.state.value.animatedOrderIds.isEmpty())

        model.selectDate(BillDateFilter.month(java.time.YearMonth.from(original.start).minusMonths(1)))
        await { model.state.value.loaded && !model.state.value.loading }
        val beforeDateReturn = service.requests
        model.selectDate(original)
        assertEquals(beforeDateReturn, service.requests)
        assertEquals(2, model.state.value.currentPage)

        model.search("商户")
        now += 60_000
        service.requestGate = CompletableDeferred()
        model.search("")
        assertTrue(model.state.value.refreshing)
        assertEquals(2, model.state.value.items.size)
        assertFalse(model.state.value.paginationReady)
        await { service.requests == beforeDateReturn + 1 }
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
        assertEquals(1, model.state.value.currentPage)
    }

    @Test fun longBillHistoriesDoNotMultiplyTheMemoryCacheAndAreReadAgainOnReturn() {
        val original = model.state.value.dateFilter
        service.items = (0..200).map { record.copy(orderId = "record-$it") }
        service.requestGate.complete(Unit)
        model.refresh(); await { model.state.value.loaded }
        model.selectDate(BillDateFilter.month(java.time.YearMonth.from(original.start).minusMonths(1)))
        await { model.state.value.loaded }
        val before = service.requests
        model.selectDate(original); await { model.state.value.loaded }
        assertEquals(before + 1, service.requests)
    }

    @Test fun readingAnotherQueryDoesNotExtendTheOverviewReuseWindow() {
        loadExistingBills()
        now += 30_000
        model.search("商户"); await { model.state.value.loaded }
        now += 30_000
        service.queries.clear()
        service.queryGates["summary"] = CompletableDeferred()
        model.search("")
        await { service.queries.containsAll(listOf("page", "summary", "income", "expenses")) }
        assertTrue(model.state.value.refreshing)
        service.queryGates.getValue("summary").complete(Unit)
        await { !model.state.value.refreshing }
    }

    @Test fun businessInvalidationRevokesBillQueriesAndTheirCachedOverview() {
        loadExistingBills()
        model.search("商户"); await { model.state.value.loaded }
        model.invalidate()
        service.queries.clear()
        model.clearQuery(); await { model.state.value.loaded && !model.state.value.refreshing }
        assertTrue(service.queries.containsAll(listOf("page", "summary", "income", "expenses")))
        model.search("商户"); await { model.state.value.loaded && !model.state.value.refreshing }
        assertEquals(4, service.requests)
    }

    @Test fun returningFromStatsAfterARepeatDateSelectionReusesTheLoadedBillDetails() {
        model.initialize()
        loadExistingBills()
        val original = model.state.value.dateFilter
        model.openStats(); await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.selectDate(BillDateFilter.month(java.time.YearMonth.from(original.start).minusMonths(1)))
        await { !model.state.value.stats.loading }
        model.selectDate(original)
        val before = service.requests
        model.showDetails(); await { model.state.value.loaded && !model.state.value.loading && !model.state.value.refreshing }
        assertEquals(before, service.requests)
        assertEquals(listOf(record), model.state.value.items)
    }
    @Test fun firstPageAndPaginationRemainUsableWhileOptionalMetricsAreSlow() {
        service.items = listOf(record)
        service.pages = 2
        service.nextItems = listOf(record.copy(orderId = "TEST-02"))
        service.auxiliaryGate = CompletableDeferred()
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.listLoaded }
        assertEquals(listOf(record), model.state.value.items)
        assertTrue(model.state.value.refreshing)
        assertFalse(model.state.value.summaryLoaded)
        model.loadMore()
        await { model.state.value.currentPage == 2 }
        assertEquals(listOf("TEST-01", "TEST-02"), model.state.value.items.map { it.orderId })
        service.auxiliaryGate.complete(Unit)
        await { !model.state.value.refreshing }
        assertEquals(2, model.state.value.currentPage)
        assertEquals(2, model.state.value.items.size)
    }
    @Test fun slowFirstPageDoesNotBlockSummaryCountsOrTypes() {
        model.refresh()
        await { model.state.value.summaryLoaded && model.state.value.countsLoaded && model.state.value.types.isNotEmpty() }
        assertTrue(model.state.value.listLoading)
        assertFalse(model.state.value.listLoaded)
        assertTrue(model.state.value.items.isEmpty())
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
        assertTrue(model.state.value.listLoaded)
    }
    @Test fun oneFailedStatisticDoesNotCancelOtherChartsAndRetryKeepsThemVisible() {
        loadExistingBills()
        service.categories = listOf(BillCategory("餐饮", 800))
        service.ranking = listOf(record)
        service.queryGates["trend"] = CompletableDeferred()
        service.queryFailures = mapOf("trend" to NetworkFailure())
        model.openStats()
        await { model.state.value.stats.categoriesLoaded && model.state.value.stats.rankingLoaded }
        assertTrue(model.state.value.stats.loading)
        assertFalse(model.state.value.stats.dailyLoaded)
        assertEquals(service.categories, model.state.value.stats.categories)
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.dailyError)
        assertNull(model.state.value.stats.categoriesError)
        assertEquals(service.ranking, model.state.value.stats.ranking)
        service.queryFailures = emptyMap()
        service.queryGates["trend"] = CompletableDeferred()
        model.refreshStats()
        assertEquals(service.categories, model.state.value.stats.categories)
        assertEquals(service.ranking, model.state.value.stats.ranking)
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        assertTrue(model.state.value.stats.dailyLoaded)
        assertNull(model.state.value.stats.dailyError)
    }
    @Test fun changingTheStatsTypeClearsPartialResultsAndIgnoresCancelledRequests() {
        loadExistingBills()
        service.categories = listOf(BillCategory("旧分类", 800))
        service.queryGates["trend"] = CompletableDeferred()
        model.openStats()
        await { model.state.value.stats.categoriesLoaded && service.statsRequests.contains("trend" to 2) }
        service.categories = listOf(BillCategory("新分类", 1200))
        // Hold the new response while checking the cleared state; a fast IO thread may otherwise publish it first.
        val categoriesGate = CompletableDeferred<Unit>()
        service.queryGates["categories"] = categoriesGate
        model.selectStatsType(1)
        await { service.statsRequests.contains("categories" to 1) }
        assertFalse(model.state.value.stats.categoriesLoaded)
        assertTrue(model.state.value.stats.categories.isEmpty())
        categoriesGate.complete(Unit)
        await { model.state.value.stats.categoriesLoaded }
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        assertEquals(1, model.state.value.stats.type)
        assertEquals(service.categories, model.state.value.stats.categories)
    }

    @Test fun reactivatingFailedStatisticsRespectsBackoffWithinTheCacheWindow() {
        loadExistingBills()
        service.queryFailures = mapOf("trend" to NetworkFailure())
        model.openStats()
        await { !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.dailyError)
        service.queryFailures = emptyMap()
        service.queryGates["trend"] = CompletableDeferred()
        model.onActivate()
        assertFalse(model.state.value.stats.loading)
        shadowOf(Looper.getMainLooper()).idleFor(600, TimeUnit.MILLISECONDS)
        await { service.statsRequests.size == 6 }
        service.queryGates.getValue("trend").complete(Unit)
        await { !model.state.value.stats.loading }
        assertNull(model.state.value.stats.dailyError)
        assertTrue(model.state.value.stats.dailyLoaded)
    }
    @Test fun failedPageStillAllowsIndependentSummaryToComplete() {
        service.failure = NetworkFailure()
        service.queryGates["summary"] = CompletableDeferred()
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.error != null }
        assertTrue(model.state.value.listLoaded)
        assertFalse(model.state.value.summaryLoaded)
        assertTrue(model.state.value.refreshing)
        service.queryGates.getValue("summary").complete(Unit)
        await { !model.state.value.refreshing }
        assertNotNull(model.state.value.summary)
        assertNotNull(model.state.value.error)
    }
    @Test fun optionalMetricFailuresKeepTheBillListUsable() {
        service.items = listOf(record)
        service.queryFailures = mapOf("summary" to NetworkFailure(), "income" to NetworkFailure(), "types" to NetworkFailure())
        service.requestGate.complete(Unit)
        model.refresh()
        await { !model.state.value.refreshing }
        assertTrue(model.state.value.loaded)
        assertEquals(listOf(record), model.state.value.items)
        assertNull(model.state.value.summary)
        assertNull(model.state.value.counts)
        assertTrue(model.state.value.types.isEmpty())
        assertNull(model.state.value.error)
    }
    @Test fun authenticationFailureInAMetricCancelsTheWholeLoad() {
        service.queryFailures = mapOf("summary" to AuthenticationRequired())
        // The page remains suspended while the summary fails both its token and its renewal.
        model.refresh()
        await { !model.state.value.refreshing }
        assertTrue(model.state.value.needsLogin)
        assertFalse(model.state.value.loaded)
    }
    @Test fun metricAuthenticationFailureClearsThePublishedPageAndCancelsPagination() {
        service.items = listOf(record)
        service.pages = 2
        service.queryGates["summary"] = CompletableDeferred()
        service.queryFailures = mapOf("summary" to AuthenticationRequired())
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.listLoaded }
        assertEquals(listOf(record), model.state.value.items)
        service.requestGate = CompletableDeferred()
        model.loadMore()
        await { service.requests == 2 }
        service.queryGates.getValue("summary").complete(Unit)
        await { model.state.value.needsLogin }
        assertTrue(model.state.value.items.isEmpty())
        assertNull(model.state.value.summary)
        assertNull(model.state.value.counts)
        assertFalse(model.state.value.loadingMore)
        assertFalse(model.state.value.listLoaded)
        service.requestGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.value.items.isEmpty())
    }
    @Test fun statisticAuthenticationFailureClearsPreviouslyPublishedCharts() {
        loadExistingBills()
        service.categories = listOf(BillCategory("餐饮", 800))
        service.queryGates["trend"] = CompletableDeferred()
        service.queryFailures = mapOf("trend" to AuthenticationRequired())
        model.openStats()
        await { model.state.value.stats.categoriesLoaded }
        assertEquals(service.categories, model.state.value.stats.categories)
        service.queryGates.getValue("trend").complete(Unit)
        await { model.state.value.needsLogin }
        assertTrue(model.state.value.items.isEmpty())
        assertTrue(model.state.value.stats.categories.isEmpty())
        assertFalse(model.state.value.stats.loading)
        assertFalse(model.state.value.stats.categoriesLoaded)
    }

    @Test fun selectingCustomRangeUsesExactFromAndToDates() {
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
        service.requests = 0
        service.requestRanges.clear()

        model.selectDate(BillDateFilter.range(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 2)))

        await { model.state.value.loaded && service.requests == 1 }
        assertEquals(listOf("2026-09-03" to "2026-10-02"), service.requestRanges)
        assertEquals(BillDateMode.Range, model.state.value.dateFilter.mode)
    }
    @Test fun detailsAndStatsShareDateAndChangingStatsDateDoesNotLoadHiddenList() {
        service.requestGate.complete(Unit)
        model.initialize()
        await { vault.reads > 0 }
        model.refresh()
        await { model.state.value.loaded }
        model.applyFilters("4", 100L, 2000L)
        await { model.state.value.loaded }
        model.openStats()
        await { model.state.value.stats.loaded }
        val listCalls = service.requests
        val range = BillDateFilter.range(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 7))
        model.selectDate(range)
        await { model.state.value.stats.loaded }
        assertTrue(model.state.value.statsVisible)
        assertEquals(range, model.state.value.dateFilter)
        assertEquals(range, model.state.value.stats.period)
        assertEquals(listCalls, service.requests)
        assertEquals("4", model.state.value.typeId)
        model.showDetails()
        await { model.state.value.loaded }
        assertFalse(model.state.value.statsVisible)
        assertEquals(range.timeFrom to range.timeTo, service.requestRanges.last())
        assertEquals("4", model.state.value.typeId)
    }
    @Test fun myMonthlyShortcutReturnsToUnfilteredCurrentMonthDetails() {
        service.requestGate.complete(Unit)
        model.refresh(); await { model.state.value.loaded }
        model.applyFilters("4", 100L, 5000L); await { model.state.value.loaded }
        model.search("示例商户"); await { model.state.value.loaded }
        model.openStats(); await { model.state.value.stats.loaded }
        model.showCurrentMonth(); await { model.state.value.loaded }
        assertFalse(model.state.value.statsVisible)
        assertEquals(BillDateFilter.currentMonth(), model.state.value.dateFilter)
        assertNull(model.state.value.typeId)
        assertNull(model.state.value.amountFromCents)
        assertNull(model.state.value.amountToCents)
        assertEquals("", model.state.value.info)
    }

    private fun loadExistingBills() {
        service.items = listOf(record)
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for bill state")
    }

    private class MemoryVault : Vault {
        var savedCredentials: Credentials? = Credentials("test-user", "fake-password")
        @Volatile var credentialsGate: CountDownLatch? = null
        @Volatile var reads = 0
        var readFailure: Exception? = null
        override fun credentials(): Credentials? {
            reads++
            check(credentialsGate?.await(5, TimeUnit.SECONDS) != false)
            readFailure?.let { throw it }
            return savedCredentials
        }
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) { savedCredentials = value }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { savedCredentials = null }
    }

    private class FakeService : YktService {
        override suspend fun paymentCard(token: String, account: String) = CampusCard(account, "校园卡", "123", false, false, 0)
        override suspend fun paymentAccount(token: String) = PaymentAccount("0", "0", "0", "Demo", 0)
        @Volatile var requests = 0
        var requestGate = CompletableDeferred<Unit>()
        var auxiliaryGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        val queries = Collections.synchronizedSet(mutableSetOf<String>())
        val statsRequests = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        var queryFailures: Map<String, YktException> = emptyMap()
        val queryGates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val searchGates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<BillPageData>>()
        private suspend fun query(name: String) {
            queries += name
            auxiliaryGate.await()
            queryGates[name]?.await()
            queryFailures[name]?.let { throw it }
        }
        var failure: YktException? = null
        var items = emptyList<BillRecord>()
        var nextItems = emptyList<BillRecord>()
        var pages = 1
        var categories = emptyList<BillCategory>()
        var ranking = emptyList<BillRecord>()
        val requestMonths = mutableListOf<String>()
        val requestRanges = mutableListOf<Pair<String, String>>()
        override suspend fun bills(
            token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
            typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?,
        ): BillPageData {
            queries += "page"
            requestMonths += timeFrom
            requestRanges += timeFrom to timeTo
            requests++
            info?.let { searchGates[it] }?.let { return it.await() }
            requestGate.await()
            failure?.let { throw it }
            return BillPageData(if (current == 1) items else nextItems, current, if (items.isEmpty()) 0 else pages)
        }
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
            query("summary")
            return BillSummary(0, 0)
        }
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
            query(if (type == 1) "income" else "expenses")
            return if (type == 1) items.count { it.income } else items.count { !it.income }
        }
        override suspend fun billTypes(token: String): List<BillType> {
            query("types")
            return listOf(BillType("4", "二维码支付"))
        }
        override suspend fun login(credentials: Credentials): Session = Session("renewed", Long.MAX_VALUE)
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch = error("Unexpected payment")
        override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
            statsRequests += "categories" to type
            query("categories")
            return categories
        }
        override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> {
            statsRequests += "trend" to type
            query("trend")
            return emptyList()
        }
        override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
            statsRequests += "ranking" to type
            query("ranking")
            return ranking
        }
    }

    private companion object {
        val record = BillRecord(
            orderId = "TEST-01", resume = "测试账单", payName = "扫码支付",
            turnoverType = "二维码支付", icon = "qrCode-payment", typeId = "4", typeFrom = "2",
            amountCents = 800, balanceCents = 8236, feeCents = 0,
            effectTime = "2026-10-20 12:05:00", bookTime = "2026-10-20 12:06:00",
            fromAccount = "校园卡", position = "TEST-POS", remark = "测试", merchant = "测试餐厅",
            serialNumber = "TEST-SERIAL", labelName = "",
        )
    }
}
