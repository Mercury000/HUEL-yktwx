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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MyBalanceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val paymentGate = CompletableDeferred<Unit>()
    private val balanceGate = CompletableDeferred<Unit>()
    private val billsGate = CompletableDeferred<Unit>()
    @Volatile private var billReads = 0
    @Volatile private var payments = 0
    @Volatile private var balanceReads = 0
    @Volatile private var paymentResponses = 0
    private lateinit var payment: PaymentViewModel

    @After fun cleanup() {
        paymentGate.complete(Unit); balanceGate.complete(Unit); billsGate.complete(Unit)
        if (::payment.isInitialized) payment.onBackground()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun myBalanceCardAndStatisticsLoadWhilePaymentAndBillsArePendingAndAccountDetailsShareTheRead() {
        val app = compose.activity.application
        val connectivity = compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        val account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)
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
                payments++
                withContext(NonCancellable) { paymentGate.await(); onAccount(account) }
                paymentResponses++
                return PaymentBatch(listOf("12345678901234567890"), 60, account)
            }
            override suspend fun paymentAccount(token: String): PaymentAccount {
                balanceReads++
                balanceGate.await()
                return account
            }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(1200, 3456)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
            override suspend fun billTypes(token: String) = emptyList<BillType>()
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                billReads++
                billsGate.await()
                return BillPageData(emptyList(), current, 0)
            }
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val repository = accountRepositoryFixture(service, vault, profileService = profiles,
            feeService = feeApiFixture({ error("Unexpected HTTP request") }))
        payment = paymentViewModelFixture(app, repository)
        val mine = myViewModelFixture(app, repository)
        val hall = hallViewModelFixture(app, repository)
        for (model in listOf(mine, hall)) compose.activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        compose.runOnIdle { payment.initialize() }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        compose.waitUntil(5_000) { payments == 1 }
        val bills = billViewModelFixture(app, repository).also { it.initialize() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(themeSettingsFixture(app), payment, bills) }
        }
        // Adjacent tab composition must not start the independent balance read.
        assertEquals(0, balanceReads)
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); billReads == 1 }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("我的") and hasClickAction()).assertIsSelected()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); balanceReads == 1 }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); mine.state.value.loaded }
        assertEquals(0, paymentResponses)
        assertTrue(bills.state.value.loading)
        assertEquals(1200L, mine.state.value.summary!!.incomeCents)
        assertEquals(3456L, mine.state.value.summary!!.expensesCents)
        compose.onNodeWithText("本月共 3 笔交易").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("¥ —").assertDoesNotExist()
        compose.onNodeWithText("账户详情").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, balanceReads)
        compose.runOnIdle { balanceGate.complete(Unit) }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payment.state.value.account != null && !payment.state.value.accountLoading }
        compose.waitForIdle()
        compose.onNode(hasText("¥ 64.96") and hasAnyAncestor(hasTestTag("profile-field-余额"))).assertIsDisplayed()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payment.state.value.accountCard != null }
        compose.waitForIdle()
        compose.onNode(hasText("正常") and hasAnyAncestor(hasTestTag("profile-field-校园卡状态"))).assertIsDisplayed()
        compose.runOnIdle { paymentGate.complete(Unit) }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); paymentResponses == 1 }
        assertEquals(1, payments)
        assertNull(payment.state.value.code)
        compose.onNodeWithContentDescription("付款条形码").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("¥ 64.96") and hasAnyAncestor(hasTestTag("my-balance-card"))).assertIsDisplayed()
        compose.onNode(hasText("正常") and hasAnyAncestor(hasTestTag("my-balance-card"))).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("my-balance-card")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); balanceReads == 2 }
        assertEquals(1, payments)
        assertNull(payment.state.value.code)
    }
}
