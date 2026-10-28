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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val paymentGate = CompletableDeferred<Unit>()
    private val balanceGate = CompletableDeferred<Unit>()
    @Volatile private var payments = 0
    @Volatile private var balanceReads = 0
    @Volatile private var paymentResponses = 0
    private lateinit var payment: PaymentViewModel

    @After fun cleanup() {
        paymentGate.complete(Unit); balanceGate.complete(Unit)
        if (::payment.isInitialized) payment.onBackground()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun switchingToMyLoadsMissingBalanceAndAccountDetailsShareThePendingRead() {
        val app = compose.activity.application
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
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(0, 0)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = 0
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val repository = AccountRepository(service, vault, profileService = profiles,
            feeService = FeeApi({ error("Unexpected HTTP request") }))
        payment = PaymentViewModel(app, repository)
        val mine = MyViewModel(app, repository)
        val hall = HallViewModel(app, repository)
        for (model in listOf(mine, hall)) compose.activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        compose.runOnIdle { payment.initialize() }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payment.state.value.restoringLogin }
        compose.runOnIdle { payment.onForeground() }
        compose.waitUntil(5_000) { payments == 1 }
        val bills = BillViewModel(app, repository).also { it.initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) }
        }
        // Adjacent tab composition must not start the independent balance read.
        assertEquals(0, balanceReads)
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("我的") and hasClickAction()).assertIsSelected()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); balanceReads == 1 }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); mine.state.value.profile != null }
        compose.onNodeWithText("¥ —").assertDoesNotExist()
        compose.onNodeWithText("账户详情").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, balanceReads)
        compose.runOnIdle { balanceGate.complete(Unit) }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payment.state.value.account != null && !payment.state.value.accountLoading }
        compose.onNode(hasText("¥ 64.96") and hasAnySibling(hasText("余额"))).assertIsDisplayed()
        compose.runOnIdle { paymentGate.complete(Unit) }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); paymentResponses == 1 }
        assertEquals(1, payments)
        assertNull(payment.state.value.code)
        compose.onNodeWithContentDescription("付款条形码").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("¥ 64.96") and hasAnyAncestor(hasTestTag("my-balance-card"))).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasTestTag("my-balance-card")) and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); balanceReads == 2 }
        assertEquals(1, payments)
        assertNull(payment.state.value.code)
    }
}
