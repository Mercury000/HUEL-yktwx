package com.mercury.yktwx

import com.mercury.yktwx.data.feeApiFixture
import com.mercury.yktwx.data.yktApiFixture
import com.mercury.yktwx.ui.themeSettingsFixture
import com.mercury.yktwx.data.accountRepositoryFixture
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Confirmed transactions update the actual app shell without relying on its finish button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransactionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private lateinit var payment: PaymentViewModel
    private val resultGate = CompletableDeferred<Unit>()
    private var paid = false
    private var balances = 0
    private var summaries = 0
    private var pages = 0
    private var codes = 0

    @After fun cleanup() {
        resultGate.complete(Unit)
        if (::payment.isInitialized) payment.onBackground()
        compose.activity.viewModelStore.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(advanceTime: Boolean = false, condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            if (advanceTime) shadowOf(Looper.getMainLooper()).idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            else shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        compose.waitForIdle()
    }

    @Test fun confirmedRechargeDefersBalanceUntilBackAndThenRefreshesMyAndBills() = rechargeScenario(finish = false)

    @Test fun finishRefreshesBalanceAndDelayedBillsAppearWithoutPullToRefresh() = rechargeScenario(finish = true, delayedBills = true)

    private fun rechargeScenario(finish: Boolean, delayedBills: Boolean = false) {
        val app = compose.activity.application
        val connectivity = compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        fun account() = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", if (paid) 7496 else 6496)
        var ledgerReady = !delayedBills
        val record = BillRecord("fake-order", "示例充值", "微信", "充值", "", "1", "1", 1000, 7496, 0,
            "2026-10-31 10:00:00", "2026-10-31 10:00:00", "fake-account", "", "", "", "fake-order", "")
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val service = object : YktService by yktApiFixture({ error("Unexpected HTTP request") }) {
            override suspend fun paymentCard(token: String, account: String) = CampusCard(account, "校园卡", "fake-user", false, false, 0)
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                codes++; onAccount(account())
                return PaymentBatch(listOf("12345678901234567890"), 60, account())
            }
            override suspend fun paymentAccount(token: String): PaymentAccount { balances++; return account() }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaries++; return BillSummary(if (paid && ledgerReady) 1000 else 0, 0)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = if (paid && ledgerReady) 1 else 0
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                pages++; return BillPageData(if (paid && ledgerReady) listOf(record) else emptyList(), current, 1)
            }
            override suspend fun billTypes(token: String) = listOf(BillType("1", "充值"))
        }
        val rechargeService = object : RechargeService {
            override suspend fun rechargeConfiguration(token: String, preferredTarget: String?) =
                RechargeConfiguration("401", "fake-account", listOf(1000), 1, 50000, true, false)
            override suspend fun createRecharge(token: String, config: RechargeConfiguration, amountCents: Long) = RechargeOrder("fake-order", "81")
            override suspend fun launchRecharge(token: String, pending: PendingRecharge) = WechatRechargeLaunch("weixin://wap/pay?prepayid=fake")
            override suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult {
                resultGate.await(); paid = true; return RechargeResult(true, 1)
            }
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val repository = accountRepositoryFixture(service, vault, rechargeService, profileService = profiles,
            feeService = feeApiFixture({ error("Unexpected HTTP request") }))
        payment = paymentViewModelFixture(app, repository)
        val mine = myViewModelFixture(app, repository)
        val bills = billViewModelFixture(app, repository).also { it.initialize() }
        val recharge = rechargeViewModelFixture(app, repository, 0L)
        for (model in listOf(payment, mine, bills, recharge, hallViewModelFixture(app, repository))) {
            compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        }
        compose.runOnIdle { payment.initialize() }
        await { !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        await { payment.state.value.account != null && !payment.state.value.busy }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(themeSettingsFixture(app), payment, bills) } }
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        await { bills.state.value.loaded }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        await { mine.state.value.loaded && !mine.state.value.loading }
        val summaryReads = summaries
        val pageReads = pages
        val codeReads = codes
        val balanceReads = balances
        compose.onNode(hasText("+ 充值") and hasClickAction() and hasAnyAncestor(hasTestTag("my-balance-card"))).performClick()
        await { recharge.state.value.visible && !recharge.state.value.loading }
        compose.runOnIdle { recharge.onForeground(); recharge.onResumed(true) }
        compose.onNodeWithText("¥ 10").performClick()
        compose.onNodeWithText("充值 ¥ 10.00").performClick()
        await { recharge.state.value.pending?.orderId != null && !recharge.state.value.loading }
        compose.runOnIdle { recharge.checkResult() }
        await { recharge.state.value.loading }
        compose.runOnIdle { resultGate.complete(Unit) }
        await { recharge.state.value.phase == RechargePhase.Paid }
        compose.onNodeWithText("充值成功").assertIsDisplayed()
        assertEquals(balanceReads, balances)
        assertEquals(6496L, payment.state.value.account?.balanceCents)
        assertEquals(summaryReads, summaries)
        assertEquals(pageReads, pages)
        assertEquals(codeReads, codes)
        if (finish) compose.onNodeWithText("完成").performClick()
        else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        await { !recharge.state.value.visible && payment.state.value.account?.balanceCents == 7496L && !mine.state.value.loading }
        if (!delayedBills) await { mine.state.value.summary?.incomeCents == 1000L }
        compose.onNode(hasText("¥ 74.96") and hasAnyAncestor(hasTestTag("my-balance-card"))).assertIsDisplayed()
        assertTrue(summaries >= summaryReads + 1)
        assertEquals(balanceReads + 1, balances)
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        await { pages >= pageReads + 1 && bills.state.value.loaded && !bills.state.value.loading }
        if (delayedBills) {
            assertTrue(bills.state.value.items.isEmpty())
            ledgerReady = true
            await(advanceTime = true) { bills.state.value.items.any { it.orderId == "fake-order" } && bills.state.value.summary?.incomeCents == 1000L }
            compose.onNode(hasText("我的") and hasClickAction()).performClick()
            await { mine.state.value.summary?.incomeCents == 1000L && !mine.state.value.loading }
        }
        assertEquals(1000L, bills.state.value.summary?.incomeCents)
        assertEquals(codeReads, codes)
    }
}
