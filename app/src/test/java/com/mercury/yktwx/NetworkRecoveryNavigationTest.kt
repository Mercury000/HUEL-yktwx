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
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkRecoveryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var online = false
    private val paymentReads = AtomicInteger()
    private lateinit var payment: PaymentViewModel

    @After fun cleanup() { if (::payment.isInitialized) payment.onBackground() }

    private fun requireOnline() { if (!online) throw NetworkFailure() }
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
            shadowOf(manager).networkCallbacks.forEach { it.onCapabilitiesChanged(manager.activeNetwork!!, capabilities) }
        } else {
            shadowOf(manager).setActiveNetworkInfo(null)
            if (previous != null) shadowOf(manager).networkCallbacks.forEach { it.onLost(previous) }
        }
    }
    private fun pullMyPage() {
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("my-balance-card")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
    }
    private fun visibleTag(tag: String) = hasTestTag(tag) and SemanticsMatcher("Inside the visible page") {
        it.boundsInRoot.left >= 0 && it.boundsInRoot.right > 0
    }

    @Test fun hallRecoversInPlaceAndCachedMyPageRecoversOnReconnectAndForegroundWithoutFetchingPaymentCodes() {
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
        payment = PaymentViewModel(app, repository)
        val hall = HallViewModel(app, repository)
        val mine = MyViewModel(app, repository)
        val bills = BillViewModel(app, repository).also { it.initialize() }
        for (model in listOf(payment, hall, mine, bills)) compose.activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        compose.runOnIdle { connection(false); payment.initialize() }
        await { !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        await { !payment.state.value.busy && payment.state.value.error != null }
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
        compose.onNode(visibleTag("read-unavailable")).assertIsDisplayed()
        assertNull(mine.state.value.profileError)
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        compose.onNode(visibleTag("read-unavailable")).assertIsDisplayed()
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
        compose.runOnIdle { connection(false); hall.refresh() }
        await { hall.state.value.error != null && hall.state.value.networkError != null && !hall.state.value.loading }
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
        pullMyPage()
        await { mine.state.value.profileError != null && payment.state.value.accountError != null && !mine.state.value.loading }
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        compose.onNodeWithText("¥ 64.96").assertIsDisplayed()
        compose.runOnIdle { connection(true) }
        await { mine.state.value.profileError == null && payment.state.value.accountError == null &&
            !mine.state.value.loading && !payment.state.value.accountLoading }
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()

        // The service can also fail while Android still reports an online connection.
        online = false
        pullMyPage()
        await { mine.state.value.profileError != null && payment.state.value.accountError != null && !mine.state.value.loading }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        online = true
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        await { mine.state.value.profileError == null && payment.state.value.accountError == null &&
            !mine.state.value.loading && !payment.state.value.accountLoading }
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        assertEquals(paymentReadsBeforeMy, paymentReads.get())
        assertNull(payment.state.value.code)
    }
}
