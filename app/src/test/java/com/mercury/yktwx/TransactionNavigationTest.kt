package com.mercury.yktwx

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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        compose.waitForIdle()
    }

    @Test fun confirmedRechargeRefreshesBalanceBeforeClosingAndBackRefreshesMyAndInvalidatesBills() {
        val app = compose.activity.application
        val connectivity = compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        fun account() = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", if (paid) 7496 else 6496)
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val service = object : YktService by YktApi({ error("Unexpected HTTP request") }) {
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                codes++; onAccount(account())
                return PaymentBatch(listOf("12345678901234567890"), 60, account())
            }
            override suspend fun paymentAccount(token: String): PaymentAccount { balances++; return account() }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaries++; return BillSummary(if (paid) 1000 else 0, 0)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = if (paid) 1 else 0
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                pages++; return BillPageData(emptyList(), current, 1)
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
        val repository = AccountRepository(service, vault, rechargeService, profileService = profiles,
            feeService = FeeApi({ error("Unexpected HTTP request") }))
        payment = PaymentViewModel(app, repository)
        val mine = MyViewModel(app, repository)
        val bills = BillViewModel(app, repository).also { it.initialize() }
        val recharge = RechargeViewModel(app, repository, 0L)
        for (model in listOf(payment, mine, bills, recharge, HallViewModel(app, repository))) {
            compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        }
        compose.runOnIdle { payment.initialize() }
        await { !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        await { payment.state.value.account != null && !payment.state.value.busy }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) } }
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
        await { recharge.state.value.phase == RechargePhase.Paid && payment.state.value.account?.balanceCents == 7496L }
        compose.onNodeWithText("充值成功").assertIsDisplayed()
        assertEquals(balanceReads + 1, balances)
        assertEquals(summaryReads, summaries)
        assertEquals(pageReads, pages)
        assertEquals(codeReads, codes)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        await { !recharge.state.value.visible && mine.state.value.summary?.incomeCents == 1000L && !mine.state.value.loading }
        compose.onNode(hasText("¥ 74.96") and hasAnyAncestor(hasTestTag("my-balance-card"))).assertIsDisplayed()
        assertEquals(summaryReads + 1, summaries)
        assertEquals(balanceReads + 1, balances)
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        await { pages == pageReads + 1 && bills.state.value.loaded && !bills.state.value.loading }
        assertEquals(1000L, bills.state.value.summary?.incomeCents)
        assertEquals(codeReads, codes)
    }
}
