package com.mercury.yktwx

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.LocalReadOffline
import com.mercury.yktwx.ui.pages.PaymentPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentInitialLoadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()
    private val gate = CompletableDeferred<Unit>()
    private val account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 12860)
    private val reads = AtomicInteger()
    private var model: PaymentViewModel? = null

    @After fun cleanup() { gate.complete(Unit); model?.onBackground() }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        compose.waitForIdle()
    }

    private fun assertLoadingCode() {
        compose.onNodeWithTag("payment-code-loading").assertIsDisplayed()
        compose.onNodeWithText("正在确认卡状态").assertDoesNotExist()
        compose.onNodeWithText("核验中").assertDoesNotExist()
        compose.onNodeWithText("暂时无法确认卡状态").assertDoesNotExist()
        compose.onNodeWithText("付款码暂不可用，请下拉刷新").assertDoesNotExist()
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
    }

    private fun restoredPayment(cached: Boolean, fail: Boolean) {
        val vault = object : Vault {
            override fun credentials() = Credentials("fake-user", "fake-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val service = object : YktService by FakeAccountService() {
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                reads.incrementAndGet()
                gate.await()
                if (fail) throw NetworkFailure()
                onAccount(account)
                return PaymentBatch(listOf("00000000000000000000"), 60, account)
            }
        }
        val cache = DisplayCache(folder.root, { bytes, _ -> bytes }, { bytes, _ -> bytes })
        val repository = AccountRepository(service, vault, displayCache = cache)
        if (cached) runBlocking {
            repository.username()
            repository.saveDisplay("fake-user", DisplayKeys.Account, account, DisplayCodecs.Account)
        }
        val payment = PaymentViewModel(compose.activity.application, repository, initialEnvironment = ReadEnvironment.Inactive)
        model = payment
        compose.activity.viewModelStore.put("payment", payment)
        payment.initialize()
        await { !payment.state.value.restoringLogin }
        val settings = ThemeSettings(compose.activity)
        compose.setContent {
            val state by payment.state.collectAsState()
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) { PaymentPage(state, settings, 0.dp, onRefresh = {}) }
            }
        }
        // Render the actual restored state before the shell admits any network read.
        assertFalse(payment.state.value.busy)
        assertEquals(0, reads.get())
        assertLoadingCode()
        if (cached) compose.onNodeWithText("¥ 128.60").assertIsDisplayed()
        compose.runOnIdle { payment.onReadEnvironment(ReadEnvironment.standalone(ReadPage.Payment)) }
        await { reads.get() == 1 }
        assertLoadingCode()
        compose.runOnIdle { gate.complete(Unit) }
        await { !payment.state.value.busy }
        if (fail) {
            compose.onNodeWithText("付款码暂不可用").assertIsDisplayed()
            compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
            compose.onNodeWithText("¥ 128.60").assertIsDisplayed()
            compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        } else {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("付款二维码").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
            compose.onNodeWithText("正在确认卡状态").assertDoesNotExist()
        }
    }

    @Test fun cachedAccountStartsWithCodeLoadingAndShowsFailureOnlyAfterTheReadFails() =
        restoredPayment(cached = true, fail = true)

    @Test fun emptyAccountStartsWithCodeLoadingAndDisplaysTheSuccessfulCode() =
        restoredPayment(cached = false, fail = false)

    @Test fun recoveredPaymentClearsBalanceFailureWhileCardIsStillLoading() {
        val ready = PaymentUiState(needsLogin = false, username = "fake-user", account = account,
            code = "00000000000000000000", secondsLeft = 60)
        var state by mutableStateOf(ready.copy(code = null, secondsLeft = 0,
            problem = PaymentCodeProblem.StatusUnverified,
            error = "网络连接失败", errorKind = ReadFailureKind.Network,
            accountError = "网络连接失败", accountErrorKind = ReadFailureKind.Network))
        val settings = themeSettingsFixture(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides false) {
                AppTheme { PaymentPage(state, settings, 0.dp, onRefresh = {}) }
            }
        }
        compose.onNodeWithText("付款码暂不可用").assertIsDisplayed()
        compose.runOnIdle {
            state = ready.copy(code = null, busy = true, balanceLoading = true, cardStatusLoading = true)
        }
        compose.waitForIdle()
        compose.runOnIdle { state = ready.copy(cardStatusLoading = true) }
        // Finish the status-to-code transition while the independent card read stays pending.
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        compose.onAllNodesWithText("余额更新失败，显示上次数据，请下拉刷新").assertCountEquals(0)
    }

    @Test fun offlineCachedAccountShowsConnectionFeedbackInsteadOfWaitingForANetworkRead() {
        val settings = ThemeSettings(compose.activity)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, account = account,
                        problem = PaymentCodeProblem.StatusUnverified), settings, 0.dp, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
        compose.onNodeWithText("正在确认卡状态").assertDoesNotExist()
        compose.onNodeWithText("¥ 128.60").assertIsDisplayed()
    }
}
