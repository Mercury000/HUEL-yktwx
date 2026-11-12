package com.mercury.yktwx

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkRecoveryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()
    @Volatile private var online = false
    @Volatile private var failureMessage = "网络连接失败，请检查网络后刷新"
    private val paymentReads = AtomicInteger()
    private lateinit var payment: PaymentViewModel

    @After fun cleanup() { if (::payment.isInitialized) payment.onBackground() }

    private fun requireOnline() { if (!online) throw NetworkFailure(failureMessage) }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        compose.waitForIdle()
    }
    private fun connection(connected: Boolean) {
        online = connected
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        val previous = manager.activeNetwork
        if (connected) {
            shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            shadowOf(manager).networkCallbacks.forEach {
                it.onAvailable(manager.activeNetwork!!)
                it.onCapabilitiesChanged(manager.activeNetwork!!, capabilities)
                it.onBlockedStatusChanged(manager.activeNetwork!!, false)
            }
        } else {
            shadowOf(manager).setActiveNetworkInfo(null)
            if (previous != null) shadowOf(manager).networkCallbacks.forEach { it.onLost(previous) }
        }
        // The monitor debounces default-network handovers on the main Handler. Robolectric's
        // paused looper does not advance that delay on its own while tests await Compose state.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
    }
    private fun pullMyPage() {
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("my-balance-card")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
    }
    private fun visibleTag(tag: String, page: String = "hall-page-list") =
        hasTestTag(tag) and hasAnyAncestor(hasTestTag(page))

    @Test fun billInternalTabsShareShellRecoveryDuringSlowCacheReadsWithoutDuplicateOrHiddenRequests() {
        val app = compose.activity.application
        val holdCache = AtomicBoolean(false)
        val cacheEntered = CountDownLatch(1)
        val releaseCache = CountDownLatch(1)
        val cache = DisplayCache(folder.root, { bytes, _ -> bytes }, { bytes, _ ->
            if (holdCache.get()) {
                cacheEntered.countDown()
                check(releaseCache.await(30, TimeUnit.SECONDS))
            }
            bytes
        })
        val incomeReads = AtomicInteger()
        val expenseReads = AtomicInteger()
        val listReads = AtomicInteger()
        val summaryReads = AtomicInteger()
        val failStats = AtomicBoolean(true)
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val period = BillDateFilter.currentMonth()
        val display = PreviewData.billState(period)
        fun statsRead(type: Int) {
            (if (type == 1) incomeReads else expenseReads).incrementAndGet()
            requireOnline()
            if (failStats.get()) throw NetworkFailure()
        }
        val service = object : YktService by FakeAccountService() {
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                paymentReads.incrementAndGet(); requireOnline()
                return PaymentBatch(emptyList(), 60, PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 0))
            }
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                listReads.incrementAndGet(); requireOnline()
                return BillPageData(display.items, current, 1)
            }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaryReads.incrementAndGet(); requireOnline(); return requireNotNull(display.summary)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                requireOnline(); return 1
            }
            override suspend fun billTypes(token: String) = emptyList<BillType>()
            override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> {
                statsRead(type); return PreviewData.billStats(filter, type).daily
            }
            override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
                statsRead(type); return PreviewData.billStats(period, type).categories
            }
            override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
                statsRead(type); return PreviewData.billStats(period, type).ranking
            }
        }
        val repository = AccountRepository(service, vault, displayCache = cache,
            feeService = FeeApi({ error("Unexpected fee HTTP request") }))
        runBlocking {
            repository.username()
            repository.saveDisplay("fake-user", DisplayKeys.bills(period, null, null, null, ""), BillPageData(display.items, 1, 1), DisplayCodecs.Page)
            repository.saveDisplay("fake-user", DisplayKeys.summary(period.timeFrom, period.timeTo), requireNotNull(display.summary), DisplayCodecs.Summary)
            repository.saveDisplay("fake-user", DisplayKeys.counts(period.timeFrom, period.timeTo), requireNotNull(display.counts), DisplayCodecs.Counts)
            for (type in 1..2) {
                val stats = PreviewData.billStats(period, type)
                repository.saveDisplay("fake-user", DisplayKeys.stats(period, type, "daily"), stats.daily, DisplayCodecs.Daily)
                repository.saveDisplay("fake-user", DisplayKeys.stats(period, type, "categories"), stats.categories, DisplayCodecs.Categories)
                repository.saveDisplay("fake-user", DisplayKeys.stats(period, type, "ranking"), stats.ranking, DisplayCodecs.Records)
            }
        }
        payment = PaymentViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive)
        val bills = BillViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive).also { it.initialize() }
        for (model in listOf(payment, bills, MyViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive),
            HallViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive))) {
            compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        }
        try {
            compose.runOnIdle { connection(false); payment.initialize() }
            await { !payment.state.value.restoringLogin }
            compose.runOnIdle { payment.onForeground() }
            await { !payment.state.value.busy }
            compose.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) }
            }
            compose.onNode(hasText("账单") and hasClickAction()).performClick()
            await { bills.state.value.listAvailable && !bills.state.value.restoringDisplay }
            compose.onNode(hasText("统计") and hasClickAction()).performClick()
            await { bills.state.value.stats.loaded && !bills.state.value.stats.restoringDisplay }
            compose.mainClock.advanceTimeBy(400)
            val originalPaymentReads = paymentReads.get()
            holdCache.set(true)
            compose.onNode(hasText("收入") and hasClickAction()).performClick()
            await { cacheEntered.count == 0L && bills.state.value.stats.restoringDisplay }
            compose.runOnIdle { connection(true) }
            await { bills.state.value.stats.loading }
            assertEquals(0, incomeReads.get())
            assertEquals(0, expenseReads.get())
            releaseCache.countDown()
            await { !bills.state.value.stats.loading && bills.state.value.stats.dailyError != null }
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            assertEquals("Activation and recovery must share one failed attempt", 3, incomeReads.get())
            assertEquals(0, expenseReads.get())
            assertEquals(0, listReads.get())
            assertEquals(0, summaryReads.get())
            compose.onNode(visibleTag("page-network-error", "bill-scroll-surface")).assertIsDisplayed()
            compose.runOnIdle { connection(false); failStats.set(false) }
            compose.runOnIdle { connection(true) }
            await { !bills.state.value.stats.loading && bills.state.value.stats.dailyError == null }
            assertEquals(6, incomeReads.get())
            compose.mainClock.advanceTimeBy(400)
            compose.onNode(visibleTag("page-network-error", "bill-scroll-surface")).assertDoesNotExist()
            compose.runOnIdle { connection(false) }
            compose.onNode(hasText("支出") and hasClickAction()).performClick()
            await { bills.state.value.stats.type == 2 && !bills.state.value.stats.restoringDisplay }
            compose.mainClock.advanceTimeBy(400)
            compose.onNode(hasText("明细") and hasClickAction()).performClick()
            await { !bills.state.value.statsVisible && !bills.state.value.restoringDisplay }
            assertEquals(0, expenseReads.get())
            assertEquals(0, listReads.get())
            compose.runOnIdle { connection(true) }
            await { bills.state.value.loaded && !bills.state.value.refreshing && !bills.state.value.loading }
            assertEquals(1, listReads.get())
            assertEquals(1, summaryReads.get())
            assertEquals(6, incomeReads.get())
            assertEquals(0, expenseReads.get())
            assertEquals(originalPaymentReads, paymentReads.get())
        } finally {
            releaseCache.countDown()
        }
    }

    @Test fun allMainPagesRecoverAcrossReconnectTabReturnAndForegroundWithoutHiddenPaymentReads() {
        val app = compose.activity.application
        val feeReads = AtomicInteger()
        val profileReads = AtomicInteger()
        val ledgerReads = AtomicInteger()
        val billReads = AtomicInteger()
        val account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val service = object : YktService by YktApi({ error("Unexpected HTTP request") }) {
            override suspend fun paymentCard(token: String, account: String): CampusCard {
                requireOnline(); return CampusCard(account, "校园卡", "fake-user", false, false, 0)
            }
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                paymentReads.incrementAndGet(); requireOnline(); onAccount(account)
                return PaymentBatch(listOf("12345678901234567890"), 60, account)
            }
            override suspend fun paymentAccount(token: String): PaymentAccount { requireOnline(); return account }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                ledgerReads.incrementAndGet()
                requireOnline(); return BillSummary(0, 0)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                requireOnline(); return 0
            }
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                billReads.incrementAndGet(); requireOnline()
                return BillPageData(emptyList(), current, 0)
            }
            override suspend fun billTypes(token: String) = emptyList<BillType>()
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                profileReads.incrementAndGet()
                requireOnline(); return PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            }
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val fees = object : FeeService by FeeApi({ error("Unexpected HTTP request") }) {
            override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
                feeReads.incrementAndGet()
                requireOnline()
                return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false),
                    roomBindingEnabled = !project.network)
            }
            override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
                selections: Map<String, String>, detail: Boolean): FeeQuery {
                requireOnline(); return FeeQuery(thirdParty = "{}", display = listOf("用户套餐" to "示例套餐"))
            }
        }
        val repository = AccountRepository(service, vault, profileService = profiles, feeService = fees)
        payment = PaymentViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive)
        val hall = HallViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive)
        val mine = MyViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive)
        val bills = BillViewModel(app, repository, initialEnvironment = ReadEnvironment.Inactive).also { it.initialize() }
        for (model in listOf(payment, hall, mine, bills)) compose.activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        compose.runOnIdle { connection(false); payment.initialize() }
        await { !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        await { !payment.state.value.busy }
        assertEquals(0, paymentReads.get())
        assertNull(payment.state.value.error)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) }
        }
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        assertFalse(hall.state.value.loaded)
        assertFalse(hall.state.value.loading)
        compose.onNode(visibleTag("read-unavailable")).assertIsDisplayed()
        compose.onNode(hasContentDescription("正在读取房间") and SemanticsMatcher("Inside the visible page") {
            it.boundsInRoot.left >= 0 && it.boundsInRoot.right > 0
        }).assertDoesNotExist()
        compose.onNode(visibleTag("network-package-placeholder")).assertDoesNotExist()
        assertNull(hall.state.value.error)
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNode(visibleTag("read-unavailable", "my-page-list")).assertIsDisplayed()
        assertNull(mine.state.value.profileError)
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        compose.onNode(visibleTag("read-unavailable", "bill-list")).assertIsDisplayed()
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        assertEquals(0, feeReads.get())
        assertEquals(0, profileReads.get())
        assertEquals(0, ledgerReads.get())
        assertEquals(0, billReads.get())
        val originalPaymentReads = paymentReads.get()
        compose.runOnIdle { connection(true) }
        await { hall.state.value.network != null && hall.state.value.error == null && !hall.state.value.loading }
        assertEquals(FeeProject.entries.size, feeReads.get())
        compose.onNode(visibleTag("read-unavailable")).assertDoesNotExist()
        assertEquals(originalPaymentReads, paymentReads.get())

        // Offline re-entry preserves the notice without starting another automatic read.
        val hallReadsBeforeOfflineRefresh = feeReads.get()
        compose.runOnIdle { connection(false) }
        compose.waitForIdle()
        compose.runOnIdle { hall.refresh() }
        await { !hall.state.value.loading }
        assertEquals(hallReadsBeforeOfflineRefresh, feeReads.get())
        assertNull(hall.state.value.error)
        assertNull(hall.state.value.networkError)
        compose.onNode(visibleTag("page-network-error")).assertIsDisplayed()
        val failedTop = compose.onNode(visibleTag("hall-utility-card")).fetchSemanticsNode().boundsInRoot.top
        val offlineReads = feeReads.get()
        compose.onNode(hasText("付款") and hasClickAction()).performClick()
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        assertFalse(hall.state.value.loading)
        assertEquals(offlineReads, feeReads.get())
        compose.mainClock.autoAdvance = false
        for (frame in 1..5) {
            compose.mainClock.advanceTimeBy(64)
            compose.onNode(visibleTag("page-network-error")).assertIsDisplayed()
            assertEquals("The offline re-entry must not collapse/reinsert the same notice", failedTop,
                compose.onNode(visibleTag("hall-utility-card")).fetchSemanticsNode().boundsInRoot.top, 1f)
        }
        compose.mainClock.advanceTimeBy(320)
        assertEquals(failedTop, compose.onNode(visibleTag("hall-utility-card")).fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { connection(true) }
        await { hall.state.value.error == null && hall.state.value.networkError == null && !hall.state.value.loading }
        compose.onNode(visibleTag("page-network-error")).assertDoesNotExist()
        val paymentReadsBeforeMy = paymentReads.get()

        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        await { mine.state.value.profile != null && payment.state.value.account != null && !mine.state.value.loading }
        compose.runOnIdle { connection(false) }
        val profileReadsBeforeOfflinePull = profileReads.get()
        val ledgerReadsBeforeOfflinePull = ledgerReads.get()
        pullMyPage()
        await { !mine.state.value.loading && !payment.state.value.accountLoading }
        assertEquals(profileReadsBeforeOfflinePull, profileReads.get())
        assertEquals(ledgerReadsBeforeOfflinePull, ledgerReads.get())
        assertNull(mine.state.value.profileError)
        assertNull(payment.state.value.accountError)
        compose.onAllNodes(visibleTag("page-network-error", "my-page-list")).assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        compose.onNodeWithText("¥ 64.96").assertIsDisplayed()
        compose.runOnIdle { connection(true) }
        await { mine.state.value.profileError == null && payment.state.value.accountError == null &&
            !mine.state.value.loading && !payment.state.value.accountLoading }
        compose.onNode(visibleTag("page-network-error", "my-page-list")).assertDoesNotExist()

        // The service can also fail while Android still reports an online connection.
        // Recovery must work even when the exception has no recognized network wording.
        failureMessage = "服务暂时无法访问"
        online = false
        pullMyPage()
        await { mine.state.value.profileError != null && payment.state.value.accountError != null && !mine.state.value.loading }
        assertEquals(failureMessage, mine.state.value.profileError)
        assertEquals(ReadFailureKind.Network, mine.state.value.profileErrorKind)
        compose.onAllNodes(visibleTag("page-network-error", "my-page-list")).assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        online = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { mine.state.value.profileError == null && payment.state.value.accountError == null &&
            !mine.state.value.loading && !payment.state.value.accountLoading }
        compose.onNode(visibleTag("page-network-error", "my-page-list")).assertDoesNotExist()
        assertNull(mine.state.value.profileErrorKind)
        assertNull(payment.state.value.accountErrorKind)
        assertEquals(paymentReadsBeforeMy, paymentReads.get())
        assertNull(payment.state.value.code)

        // Exhaust the shared automatic budget while Android keeps the SAME default network.
        // Both tab return and Activity return must recover each page and remove its banner.
        data class PageRecovery(val tab: String, val surface: String, val refresh: () -> Unit,
            val activate: () -> Unit, val loading: () -> Boolean, val failure: () -> String?,
            val reads: () -> Int, val ready: () -> Boolean)
        val pages = listOf(
            PageRecovery("大厅", "hall-page-list", hall::refresh, hall::onActivate,
                { hall.state.value.loading }, { hall.state.value.networkError }, { feeReads.get() },
                { hall.state.value.network != null && !hall.state.value.restoringDisplay }),
            PageRecovery("账单", "bill-scroll-surface", bills::refresh, bills::onActivate,
                { bills.state.value.loading || bills.state.value.refreshing }, { bills.state.value.error }, { billReads.get() },
                { bills.state.value.listAvailable && bills.state.value.summary != null && !bills.state.value.restoringDisplay }),
            PageRecovery("我的", "my-page-list", mine::refresh, mine::onActivate,
                { mine.state.value.loading }, { mine.state.value.profileError }, { profileReads.get() },
                { mine.state.value.profile != null && !mine.state.value.restoringDisplay }),
        )
        for (page in pages) {
            compose.onNode(hasText(page.tab) and hasClickAction()).performClick()
            await { page.ready() && !page.loading() && page.failure() == null }
            for (foregroundReturn in listOf(false, true)) {
                online = false // Transport fails; there is deliberately no connectivity event.
                compose.runOnIdle { page.refresh() }
                await { !page.loading() && page.failure() != null }
                val firstAttemptReads = page.reads()
                compose.runOnIdle { page.activate() }
                await { !page.loading() && page.failure() != null && page.reads() > firstAttemptReads }
                val exhaustedReads = page.reads()
                compose.runOnIdle { page.activate() }
                compose.mainClock.advanceTimeBy(1_000)
                assertEquals(page.tab, exhaustedReads, page.reads())
                compose.onNode(visibleTag("page-network-error", page.surface)).assertIsDisplayed()
                if (foregroundReturn) {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    online = true
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                } else {
                    val otherTab = if (page.tab == "大厅") "账单" else "大厅"
                    compose.onNode(hasText(otherTab) and hasClickAction()).performClick()
                    online = true
                    compose.onNode(hasText(page.tab) and hasClickAction()).performClick()
                }
                await { !page.loading() && page.failure() == null && page.reads() > exhaustedReads }
                compose.mainClock.advanceTimeBy(400)
                compose.onNode(visibleTag("page-network-error", page.surface)).assertDoesNotExist()
                assertEquals(paymentReadsBeforeMy, paymentReads.get())
            }
        }

        compose.onNode(hasText("付款") and hasClickAction()).performClick()
        await { payment.state.value.code != null && !payment.state.value.busy }
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        val defaultNetwork = manager.activeNetwork!!
        val callback = shadowOf(manager).networkCallbacks.single()
        repeat(3) {
            val beforeBackground = paymentReads.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            // Revocation is synchronous, without waiting for another Compose frame.
            assertNull(payment.state.value.code)
            compose.runOnIdle {
                callback.onBlockedStatusChanged(defaultNetwork, true)
                shadowOf(manager).setActiveNetworkInfo(null)
                shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
                callback.onBlockedStatusChanged(defaultNetwork, false)
            }
            assertEquals(beforeBackground, paymentReads.get())
            // The synchronous getter still reports null when ON_START runs.
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            await { payment.state.value.code != null && !payment.state.value.busy }
            assertNull(payment.state.value.error)
            assertEquals(beforeBackground + 1, paymentReads.get())
        }
    }

    @Test fun profileDetailsRecoverAnIdentityOnlyFailureFromAnOlderRequest() {
        val app = compose.activity.application
        val identityGate = CompletableDeferred<Unit>()
        val identityReads = AtomicInteger()
        val profileReads = AtomicInteger()
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                profileReads.incrementAndGet()
                return PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            }
            override suspend fun avatar(path: String): ByteArray? = null
            override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? {
                if (identityReads.incrementAndGet() == 1) {
                    identityGate.await()
                    throw NetworkFailure("服务暂时无法访问")
                }
                return HomeCardIdentity("示例姓名", sno, "示例院系", "示例专业")
            }
        }
        val service = object : YktService by FakeAccountService() {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(0, 0)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = 0
        }
        val repo = AccountRepository(service, vault, profileService = profiles,
            feeService = FeeApi({ error("Unexpected fee HTTP request") }))
        payment = PaymentViewModel(app, repo, initialEnvironment = ReadEnvironment.Inactive)
        val mine = MyViewModel(app, repo, initialEnvironment = ReadEnvironment.Inactive)
        val bills = BillViewModel(app, repo, initialEnvironment = ReadEnvironment.Inactive).also { it.initialize() }
        for (model in listOf(payment, mine, bills, HallViewModel(app, repo, initialEnvironment = ReadEnvironment.Inactive))) {
            compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        }
        try {
            compose.runOnIdle { connection(true); payment.initialize() }
            await { !payment.state.value.restoringLogin }
            compose.runOnIdle { payment.onForeground() }
            compose.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) }
            }
            compose.onNode(hasText("我的") and hasClickAction()).performClick()
            await { identityReads.get() >= 1 && mine.state.value.profile != null }
            assertEquals(1, identityReads.get())
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            await { compose.onAllNodesWithContentDescription("个人信息").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithContentDescription("个人信息").performClick()
            compose.runOnIdle { connection(false) }
            compose.waitForIdle()
            compose.runOnIdle { connection(true) }
            compose.waitForIdle()
            compose.runOnIdle { identityGate.complete(Unit) }
            await { identityReads.get() == 2 && mine.state.value.homeIdentity != null && !mine.state.value.loading }
            assertNull(mine.state.value.homeIdentityError)
            assertEquals(2, profileReads.get())
            compose.mainClock.advanceTimeBy(500)
            assertEquals(2, identityReads.get())
        } finally { identityGate.complete(Unit) }
    }
}
