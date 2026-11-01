package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.LocalDate

/** Delayed read replicas, including delays longer than all automatic retries; no real payments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class TransactionDataRefreshTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val delays = listOf(1_000L, 2_000L, 3_000L)
    @Volatile private var balance = 1000L
    @Volatile private var accountId = "fake-account"
    @Volatile private var listUpdated = false
    @Volatile private var summaryUpdated = false
    @Volatile private var countsUpdated = false
    @Volatile private var networkUpdated = false
    @Volatile private var categoriesUpdated = false
    @Volatile private var categoriesAmount = 100L
    @Volatile private var trendUpdated = false
    @Volatile private var rankingUpdated = false
    @Volatile private var listFails = false
    @Volatile private var summaryFails = false
    @Volatile private var categoriesFail = false
    @Volatile private var networkFails = false
    @Volatile private var balanceFails = false
    @Volatile private var configurationFailures = emptySet<FeeProject>()
    @Volatile private var pageGate: CompletableDeferred<Unit>? = null
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val reads = ConcurrentHashMap<String, AtomicInteger>()
    private fun count(key: String) = reads[key]?.get() ?: 0
    private fun read(key: String) { reads.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet() }
    private fun account() = PaymentAccount(accountId, "fake-payacc", "1", "统一账户", balance)
    private val old = record("old", false, 100)
    private val recharge = record("recharge", true, 1000)
    private val service = object : YktService by YktApi({ error("Unexpected live HTTP request") }) {
        override suspend fun login(credentials: Credentials) = Session("fake-token", Long.MAX_VALUE)
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
            read("codes")
            val account = account()
            onAccount(account)
            return PaymentBatch(listOf("12345678901234567890"), 60, account)
        }
        override suspend fun paymentAccount(token: String): PaymentAccount {
            read("balance")
            if (balanceFails) throw NetworkFailure()
            return account()
        }
        override suspend fun paymentCard(token: String, account: String): CampusCard {
            read("card"); return CampusCard(account, "校园卡", "fake-user", false, false, 0)
        }
        override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
            typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
            read("list")
            if (listFails) throw NetworkFailure()
            val page = BillPageData(if (listUpdated) listOf(recharge, old) else listOf(old), current, 1)
            pageGate?.let { withContext(NonCancellable) { it.await() } }
            return page
        }
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
            read("summary")
            if (summaryFails) throw NetworkFailure()
            return BillSummary(if (summaryUpdated) 1000 else 0, 100)
        }
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
            read("count:$type"); return if (type == 1) { if (countsUpdated) 1 else 0 } else 1
        }
        override suspend fun billTypes(token: String) = listOf(BillType("1", "充值"))
        override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
            read("categories")
            if (categoriesFail) throw NetworkFailure()
            return if (categoriesUpdated) listOf(BillCategory("示例消费", categoriesAmount)) else emptyList()
        }
        override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> {
            read("trend"); return if (trendUpdated) listOf(BillDailyPoint(LocalDate.now(), 100)) else emptyList()
        }
        override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
            read("ranking"); return if (rankingUpdated) listOf(old) else emptyList()
        }
    }
    private val profiles = object : ProfileService {
        override suspend fun profile(token: String): PersonalProfile {
            read("profile"); return PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
        }
        override suspend fun avatar(path: String): ByteArray? = null
        override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? { read("identity"); return null }
    }
    private val fees = object : FeeService by FeeApi({ error("Unexpected live fee request") }) {
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            read("config:${project.id}")
            if (project in configurationFailures) throw NetworkFailure()
            return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false))
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
            selections: Map<String, String>, detail: Boolean): FeeQuery {
            check(project == FeeProject.Network)
            read("network")
            if (networkFails) throw NetworkFailure()
            return FeeQuery(display = listOf("余额" to if (networkUpdated) "20" else "10"))
        }
    }
    private val vault = object : Vault {
        private var credentials: Credentials? = Credentials("fake-user", "fake-password")
        override fun credentials() = credentials
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) { credentials = value }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { credentials = null }
    }
    private val repository = AccountRepository(service, vault, feeService = fees, profileService = profiles)

    @After fun cleanup() {
        gates.forEach { it.complete(Unit) }
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun payment(): PaymentViewModel {
        val model = PaymentViewModel(app, repository, delays)
        store.put("payment", model)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground(); await { model.state.value.account != null && !model.state.value.busy }
        return model
    }
    private fun bills(): BillViewModel {
        val model = BillViewModel(app, repository, { 10_000L }, delays)
        store.put("bills", model)
        model.initialize(); model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.refreshing && !model.state.value.loading }
        return model
    }
    private fun await(advance: Boolean = false, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (advance) shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            else shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for transaction refresh")
    }
    private fun balanceEvent(model: PaymentViewModel) = runBlocking {
        withTimeoutOrNull(100) { model.balanceChanges.first() }
    }

    @Test fun ordinaryCodeAndAccountRefreshesNotifyBalanceChangesWithoutReportingPaymentSuccess() {
        val model = payment()
        assertNull(balanceEvent(model))
        balance = 2000
        model.refresh(manual = true)
        await { !model.state.value.busy && model.state.value.account?.balanceCents == 2000L }
        assertEquals(BalanceChange("fake-user", increased = true), balanceEvent(model))
        assertNull(model.paymentSuccess.value)
        model.setPageVisible(false); model.setAccountPageVisible(true)
        await { !model.state.value.cardStatusLoading }
        balance = 1500
        model.refreshAccount(manual = true)
        await { model.state.value.account?.balanceCents == 1500L && !model.state.value.accountLoading }
        assertEquals(BalanceChange("fake-user", increased = false), balanceEvent(model))
        assertNull(model.paymentSuccess.value)
        model.refreshAccount(manual = true)
        await { !model.state.value.accountLoading }
        assertNull(balanceEvent(model))
    }

    @Test fun anotherAccountAndReloginNeverCompareBalancesAcrossIdentities() {
        val model = payment()
        accountId = "other-account"; balance = 20
        model.refresh(manual = true)
        await { !model.state.value.busy && model.state.value.account?.account == "other-account" }
        assertNull(balanceEvent(model))
        model.logout(); await { model.state.value.needsLogin && !model.state.value.busy }
        balance = 9000
        model.login("another-user", "fake-password")
        await { !model.state.value.needsLogin && !model.state.value.busy }
        assertNull(balanceEvent(model))
    }

    @Test fun delayedRechargeBalanceRetriesOnlyTheBalanceAndStopsAfterItChanges() {
        val model = payment()
        model.setPageVisible(false); model.setAccountPageVisible(true)
        await { !model.state.value.cardStatusLoading }
        val cards = count("card")
        val codes = count("codes")
        val before = count("balance")
        model.invalidateBalance()
        await { count("balance") == before + 1 && !model.state.value.accountLoading }
        assertEquals(1000L, model.state.value.account?.balanceCents)
        balance = 2000
        await(advance = true) { model.state.value.account?.balanceCents == 2000L }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(before + 2, count("balance"))
        assertEquals(cards, count("card")); assertEquals(codes, count("codes"))
    }

    @Test fun failedUpdatedBalanceRetriesWithoutRefreshingTheCardOrPaymentCode() {
        val model = payment()
        model.setPageVisible(false); model.setAccountPageVisible(true)
        await { !model.state.value.accountLoading && !model.state.value.cardStatusLoading }
        balance = 2000
        model.invalidateBalance()
        await { model.state.value.account?.balanceCents == 2000L && !model.state.value.accountLoading }
        val before = count("balance")
        val cards = count("card")
        val codes = count("codes")
        balanceFails = true
        await {
            // Balance publication can precede completion of the previous cache write.
            if (count("balance") == before) model.refreshAccount(manual = true, balanceOnly = true)
            model.state.value.accountError != null && !model.state.value.accountLoading
        }
        assertEquals(before + 1, count("balance"))
        balanceFails = false; balance = 3000
        await(advance = true) { model.state.value.account?.balanceCents == 3000L && model.state.value.accountError == null }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(before + 2, count("balance"))
        assertEquals(cards, count("card")); assertEquals(codes, count("codes"))
    }

    @Test fun rechargeSheetDefersItsRefreshUntilThePageIsUncovered() {
        val model = payment()
        model.setPageVisible(false, preserveDisplay = true)
        model.setReadPageVisible(false)
        model.invalidateBalance()
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(1, count("codes")); assertEquals(0, count("balance"))
        balance = 2000
        model.setPageVisible(true); model.setReadPageVisible(true)
        await { !model.state.value.busy && model.state.value.account?.balanceCents == 2000L }
        assertEquals(2, count("codes"))
    }

    @Test fun updatedBillListDoesNotStopDelayedOverviewAndRetriesSkipAlreadyUpdatedParts() {
        val model = bills()
        model.invalidate(); model.setPageVisible(true)
        await { count("list") == 2 && !model.state.value.refreshing }
        listUpdated = true
        await(advance = true) { model.state.value.items.first().orderId == "recharge" }
        val listReads = count("list")
        assertEquals(0L, model.state.value.summary?.incomeCents)
        assertEquals(0, model.state.value.counts?.income)
        summaryUpdated = true; countsUpdated = true
        await(advance = true) { model.state.value.summary?.incomeCents == 1000L && model.state.value.counts?.income == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(listReads, count("list"))
        val summaries = count("summary")
        model.setPageVisible(false); model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listReads, count("list")); assertEquals(summaries, count("summary"))
    }

    @Test fun returningWithinTheCacheWindowForcesAReadAndHiddenPagesDoNotPoll() {
        val model = bills()
        model.invalidate(); model.setPageVisible(true)
        await { count("list") == 2 && !model.state.value.refreshing }
        model.setPageVisible(false)
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(2, count("list"))
        listUpdated = true; summaryUpdated = true; countsUpdated = true
        model.setPageVisible(true)
        await { model.state.value.items.first().orderId == "recharge" && !model.state.value.refreshing }
        assertEquals(3, count("list"))
    }

    @Test fun exhaustingRetriesKeepsNextEntryFreshWithoutRestartingTheAutomaticBudget() {
        val model = bills()
        model.invalidate(); model.setPageVisible(true)
        await(advance = true) { count("list") == 5 && !model.state.value.listLoading }
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(5, count("list"))
        model.setPageVisible(false); model.setPageVisible(true)
        await { count("list") == 6 && !model.state.value.refreshing }
        shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.SECONDS)
        assertEquals(6, count("list"))
        listUpdated = true; summaryUpdated = true; countsUpdated = true
        model.refresh()
        await { model.state.value.items.first().orderId == "recharge" && !model.state.value.refreshing }
        assertEquals(7, count("list"))
    }

    @Test fun aLateOldPageCannotReplaceTheNewTransactionRound() {
        val model = bills()
        val gate = CompletableDeferred<Unit>().also { gates += it }
        pageGate = gate
        model.refresh()
        await { count("list") == 2 }
        model.invalidate()
        pageGate = null
        listUpdated = true; summaryUpdated = true; countsUpdated = true
        model.setPageVisible(true)
        await { model.state.value.items.first().orderId == "recharge" && !model.state.value.refreshing }
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("recharge", model.state.value.items.first().orderId)
    }

    @Test fun myPageRetriesOnlyDelayedMonthlyMetricsAndKeepsProfileAndIdentity() {
        val model = MyViewModel(app, repository, delays)
        store.put("mine", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        model.invalidate(); model.setPageVisible(true)
        await { count("summary") == 2 && !model.state.value.loading }
        summaryUpdated = true
        await(advance = true) { model.state.value.summary?.incomeCents == 1000L }
        val summaries = count("summary")
        countsUpdated = true
        await(advance = true) { model.state.value.counts?.income == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(summaries, count("summary"))
        assertEquals(1, count("profile")); assertEquals(1, count("identity"))
        assertTrue(model.state.value.homeIdentityLoaded)
    }

    @Test fun feeRefreshQueriesOnlyTheAffectedProjectAndUsesTheSamePauseRules() {
        val model = HallViewModel(app, repository, delays)
        store.put("hall", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        model.invalidateTransaction(FeeProject.Network); model.setPageVisible(true)
        await { count("network") == 2 && !model.state.value.loading }
        model.setPageVisible(false)
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(2, count("network"))
        networkUpdated = true
        model.setPageVisible(true)
        await { model.state.value.network?.display?.last()?.second == "20" && !model.state.value.loading }
        for (project in FeeProject.entries.filterNot { it.network }) assertEquals(1, count("config:${project.id}"))
        assertEquals(3, count("network"))
    }

    @Test fun aRechargeDoesNotStartPollingExpenseCharts() {
        val model = bills()
        model.openStats()
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.invalidate(income = true); model.setPageVisible(true)
        await { count("categories") == 2 && !model.state.value.stats.loading }
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(2, count("categories")); assertEquals(2, count("trend")); assertEquals(2, count("ranking"))
    }

    @Test fun chartPartsConvergeIndependentlyAndPollingSkipsAlreadyChangedEndpoints() {
        val model = bills()
        model.openStats()
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        model.invalidate(income = false); model.setPageVisible(true)
        await { count("categories") == 2 && !model.state.value.stats.loading }
        categoriesUpdated = true
        await(advance = true) { model.state.value.stats.categories.isNotEmpty() }
        val categories = count("categories")
        assertTrue(model.state.value.stats.daily.isEmpty()); assertTrue(model.state.value.stats.ranking.isEmpty())
        trendUpdated = true; rankingUpdated = true
        await(advance = true) { model.state.value.stats.daily.isNotEmpty() && model.state.value.stats.ranking.isNotEmpty() }
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertEquals(categories, count("categories"))
        model.setPageVisible(false); model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(categories, count("categories"))
    }

    @Test fun hidingBeforeActivationCheckCompletesDoesNotStartAnOffscreenRead() {
        val model = bills()
        val before = count("list")
        model.invalidate()
        model.setPageVisible(true)
        model.setPageVisible(false)
        repeat(30) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(before, count("list"))
        listUpdated = true; summaryUpdated = true; countsUpdated = true
        model.setPageVisible(true)
        await { model.state.value.items.first().orderId == "recharge" && !model.state.value.refreshing }
        assertEquals(before + 1, count("list"))
    }

    @Test fun selectiveHallRetryPreservesAnUnqueriedNetworkError() {
        networkFails = true
        val model = HallViewModel(app, repository, delays)
        store.put("hall", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        val error = model.state.value.networkError
        assertNotNull(error)
        val networkReads = count("network")
        model.invalidateTransaction(FeeProject.Zhengdong); model.setPageVisible(true)
        await { count("config:${FeeProject.Zhengdong.id}") == 2 && !model.state.value.loading }
        assertEquals(networkReads, count("network"))
        assertEquals(error, model.state.value.networkError)
        networkFails = false
        model.refresh()
        await { count("network") == networkReads + 1 && !model.state.value.loading }
        assertNull(model.state.value.networkError)
    }

    @Test fun selectiveHallRetryPreservesOtherProjectErrorsAndTheirNeedForRecovery() {
        configurationFailures = setOf(FeeProject.Zhengdong)
        val model = HallViewModel(app, repository, delays)
        store.put("hall", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        val error = model.state.value.error
        assertNotNull(error)
        networkUpdated = true
        model.invalidateTransaction(FeeProject.Network); model.setPageVisible(true)
        await { count("network") == 2 && !model.state.value.loading }
        assertEquals(1, count("config:${FeeProject.Zhengdong.id}"))
        assertEquals(error, model.state.value.error)
        configurationFailures = emptySet()
        model.setPageVisible(false); model.setPageVisible(true)
        await { count("config:${FeeProject.Zhengdong.id}") == 2 && !model.state.value.loading }
        assertNull(model.state.value.error)
    }

    @Test fun statsRetriesIncludeAFailedPreviouslyUpdatedEndpointAndStopAfterRecovery() {
        val model = bills()
        model.openStats()
        await { model.state.value.stats.loaded && !model.state.value.stats.loading }
        categoriesUpdated = true
        model.invalidate(income = false); model.setPageVisible(true)
        await { count("categories") == 2 && !model.state.value.stats.loading }
        categoriesFail = true
        model.refreshStats()
        await { count("categories") == 3 && !model.state.value.stats.loading }
        assertNotNull(model.state.value.stats.categoriesError)
        trendUpdated = true; rankingUpdated = true
        await(advance = true) {
            count("categories") == 4 && model.state.value.stats.daily.isNotEmpty() &&
                model.state.value.stats.ranking.isNotEmpty() && model.state.value.stats.categoriesError != null
        }
        val trendReads = count("trend")
        val rankingReads = count("ranking")
        categoriesFail = false; categoriesAmount = 200
        await(advance = true) {
            count("categories") == 5 && model.state.value.stats.categoriesError == null &&
                model.state.value.stats.categories.singleOrNull()?.amountCents == 200L
        }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(5, count("categories"))
        assertEquals(trendReads, count("trend")); assertEquals(rankingReads, count("ranking"))
        model.setPageVisible(false); model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, count("categories"))
    }

    @Test fun billRetriesIncludeAFailedPreviouslyUpdatedListAndStopAfterRecovery() {
        val model = bills()
        listUpdated = true
        model.invalidate(); model.setPageVisible(true)
        await { count("list") == 2 && !model.state.value.refreshing }
        listFails = true
        model.refresh()
        await { count("list") == 3 && !model.state.value.refreshing }
        assertNotNull(model.state.value.error)
        summaryUpdated = true; countsUpdated = true
        await(advance = true) {
            count("list") == 4 && model.state.value.summary?.incomeCents == 1000L &&
                model.state.value.counts?.income == 1 && model.state.value.error != null
        }
        val summaryReads = count("summary")
        val countReads = count("count:1")
        listFails = false
        await(advance = true) { count("list") == 5 && model.state.value.error == null && !model.state.value.listLoading }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(5, count("list"))
        assertEquals(summaryReads, count("summary")); assertEquals(countReads, count("count:1"))
        model.setPageVisible(false); model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(5, count("list"))
    }

    @Test fun myPageRetriesFailedMetricsAfterOtherMetricsConvergeAndKeepsTheBudget() {
        val model = MyViewModel(app, repository, delays)
        store.put("mine", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        summaryUpdated = true
        model.invalidate(); model.setPageVisible(true)
        await { count("summary") == 2 && model.state.value.summary?.incomeCents == 1000L &&
            model.state.value.counts?.income == 0 && !model.state.value.loading }
        summaryFails = true
        model.refresh()
        await { count("summary") == 3 && !model.state.value.loading }
        assertNotNull(model.state.value.summaryError)
        countsUpdated = true
        await(advance = true) { count("summary") == 4 && model.state.value.counts?.income == 1 &&
            model.state.value.summaryError != null && !model.state.value.loading }
        val countReads = count("count:1")
        val profileReads = count("profile")
        await(advance = true) { count("summary") == 5 && model.state.value.summaryError != null }
        summaryFails = false
        await(advance = true) { count("summary") == 6 && model.state.value.summaryError == null }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(6, count("summary"))
        assertEquals(countReads, count("count:1")); assertEquals(profileReads, count("profile"))
    }

    @Test fun hallRetriesAFailedUpdatedProjectWhileOtherProjectsArePendingThenSkipsItAfterRecovery() {
        val model = HallViewModel(app, repository, delays)
        store.put("hall", model)
        model.accountChanged("fake-user"); model.setPageVisible(true)
        await { model.state.value.loaded && !model.state.value.loading }
        networkUpdated = true
        model.invalidateTransaction(FeeProject.Zhengdong)
        model.invalidateTransaction(FeeProject.Network); model.setPageVisible(true)
        await { count("network") == 2 && !model.state.value.loading }
        networkFails = true
        model.refresh()
        await { count("network") == 3 && !model.state.value.loading }
        assertNotNull(model.state.value.networkError)
        await(advance = true) { count("network") == 4 && model.state.value.networkError != null }
        networkFails = false
        await(advance = true) { count("network") == 5 && model.state.value.networkError == null }
        await(advance = true) { count("config:${FeeProject.Zhengdong.id}") == 6 && !model.state.value.loading }
        shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.SECONDS)
        assertEquals(5, count("network"))
        assertEquals(6, count("config:${FeeProject.Zhengdong.id}"))
        assertNull(model.state.value.networkError)
    }

    private fun record(id: String, income: Boolean, amount: Long) = BillRecord(id, "示例交易", "示例支付",
        "示例类型", "", "1", if (income) "1" else "2", amount, 1000, 0,
        "2026-10-31 10:00:00", "2026-10-31 10:00:00", "fake-account", "", "", "", id, "")
}
