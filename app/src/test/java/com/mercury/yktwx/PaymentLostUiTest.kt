package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.PaymentAccount
import com.mercury.yktwx.data.CampusCard
import com.mercury.yktwx.data.PaymentCodeProblem
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.pages.PaymentPage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentLostUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
    }
    private fun screenshot(name: String) {
        File("../tools/$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun lostApp(dark: Boolean = false) {
        settings.setTheme(if (dark) 2 else 1)
        val model = PaymentViewModel(compose.activity.application)
        val bills = BillViewModel(compose.activity.application)
        model.initialize(uiPreview = true, previewLost = true)
        bills.initialize(true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, model, bills) }
        }
    }
    @Test fun lostAppShowsAnInlineWarningAndOpensUnlostWithoutLeavingPaymentTab() {
        lostApp()
        compose.onNodeWithText("当前卡片已挂失，可能无法支付，点击解挂").assertIsDisplayed()
        compose.onNodeWithText("付款已暂停").assertDoesNotExist()
        compose.onNodeWithText("已挂失").assertDoesNotExist()
        compose.onNodeWithText("解挂").assertDoesNotExist()
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
        compose.onNodeWithText("未找回卡片，请保持挂失").assertDoesNotExist()
        compose.onNodeWithText("去解挂").assertDoesNotExist()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
        screenshot("payment-lost-app-light")
        compose.onNodeWithTag("payment-lost-notice").performClick()
        compose.onNodeWithText("确认解挂").assertIsDisplayed()
        compose.onNodeWithText("校园卡查询密码").assertIsDisplayed()
        compose.onNodeWithText("确认挂失").assertDoesNotExist()
        compose.onNodeWithText("确定").assertIsNotEnabled() // Preview never submits a real operation.
        compose.onNodeWithText("水电费").assertIsNotDisplayed()
        screenshot("payment-lost-unlost-dialog")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("当前卡片已挂失，可能无法支付，点击解挂").assertIsDisplayed()
        compose.onNodeWithText("确认解挂").assertDoesNotExist()
    }
    @Test fun darkLostAppKeepsBalanceRechargeAndStatusVisible() {
        lostApp(dark = true)
        compose.onNodeWithText("当前卡片已挂失，可能无法支付，点击解挂").assertIsDisplayed()
        compose.onNode(hasText("充值") and !hasTestTag("network-recharge")).assertIsDisplayed()
        compose.onNodeWithText("¥ 186.50").assertIsDisplayed()
        screenshot("payment-lost-app-dark")
    }
    @Test fun rechargeButtonKeepsItsLabelWhileOpeningTheSheet() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false,
                        account = PaymentAccount("fake", "fake", "1", "统一账户", 12860)),
                        settings, 0.dp, onRefresh = {}, rechargeLoading = true)
                }
            }
        }
        compose.onNode(hasText("充值") and hasClickAction()).assertIsNotEnabled()
        compose.onNodeWithText("读取中…").assertDoesNotExist()
    }
    @Test fun inlineLossWarningKeepsTheCodeAndRefreshIsOnlyAPullGesture() {
        var unlost = 0
        var refreshed = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, code = "00000000000000000000", secondsLeft = 60,
                        account = PaymentAccount("fake", "fake", "1", "统一账户", 12860),
                        accountCard = CampusCard("fake", "校园卡", "fake", true, false, 0)),
                        settings, 0.dp, onRefresh = { refreshed++ }, onUnlost = { unlost++ })
                }
            }
        }
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        compose.onNodeWithText("60s 后刷新").assertIsDisplayed()
        compose.onNodeWithTag("payment-lost-notice").performClick()
        assertEquals(1, unlost)
        assertEquals(0, refreshed)
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
        compose.onNodeWithText("重新获取").assertDoesNotExist()
        compose.onNodeWithText("重新检查").assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipe(start = center.copy(y = height * 0.15f), end = center.copy(y = height * 0.8f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue("Pull gesture must invoke onRefresh", refreshed > 0)
    }
    @Test fun unverifiableCardStatusCannotOfferUnlost() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, problem = PaymentCodeProblem.StatusUnverified,
                        error = "示例卡状态读取失败"),
                        settings, 0.dp, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("暂时无法加载，请下拉刷新").assertIsDisplayed()
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.onNodeWithText("重新检查").assertDoesNotExist()
        compose.onNodeWithText("已挂失").assertDoesNotExist()
        compose.onNodeWithText("解挂").assertDoesNotExist()
    }
    @Test fun frozenCardCannotShowARefreshOrUnlostButton() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, problem = PaymentCodeProblem.Frozen),
                        settings, 0.dp, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("付款已暂停").assertIsDisplayed()
        compose.onNodeWithText("已冻结").assertIsDisplayed()
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
        compose.onNodeWithText("解挂").assertDoesNotExist()
    }
    @Test fun codeEndpointFailureDoesNotReintroduceARetryButton() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, error = "示例服务端错误"),
                        settings, 0.dp, onRefresh = {})
                }
            }
        }
        compose.onNodeWithText("暂时无法加载，请下拉刷新").assertIsDisplayed()
        compose.onNodeWithText("重新获取").assertDoesNotExist()
        compose.onNodeWithText("重新检查").assertDoesNotExist()
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
    }
    @Test fun codeLoadingHasNoVerificationOrLossPlaceholderAndDisablesTheInlineAction() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PaymentUiState(needsLogin = false, busy = true,
                        account = PaymentAccount("fake", "fake", "1", "统一账户", 12860),
                        accountCard = CampusCard("fake", "校园卡", "fake", true, false, 0),
                        code = "00000000000000000000"), settings, 0.dp, onRefresh = {}, unlostLoading = true)
                }
            }
        }
        compose.onNodeWithTag("payment-code-loading").assertIsDisplayed()
        compose.onNodeWithText("正在确认卡状态").assertDoesNotExist()
        compose.onNodeWithText("核验中").assertDoesNotExist()
        compose.onNodeWithTag("payment-lost-notice").assertIsNotEnabled()
        compose.onNodeWithText("解挂").assertDoesNotExist()
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
    }
    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun lostActionsRemainAccessibleOnNarrowScreenWithLargeText() {
        lostApp()
        compose.onNodeWithText("当前卡片已挂失，可能无法支付，点击解挂").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
        screenshot("payment-lost-large-font")
        compose.onNodeWithTag("payment-page-list").performScrollToIndex(1)
        compose.onNode(hasText("充值") and !hasTestTag("network-recharge")).performScrollTo().assertIsDisplayed()
    }

    @Test fun lossNoticeAnimatesAboveTheCodeAndDisablesItsActionDuringExit() {
        var unlost = 0
        val normal = PaymentUiState(needsLogin = false, code = "00000000000000000000", secondsLeft = 60,
            account = PaymentAccount("fake", "fake", "1", "统一账户", 12860))
        val current = mutableStateOf(normal)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(current.value, settings, 0.dp, onRefresh = {}, onUnlost = { unlost++ })
                }
            }
        }
        fun cardTop() = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top
        val initialTop = cardTop()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { current.value = normal.copy(accountCard = CampusCard("fake", "校园卡", "fake", true, false, 0)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val enteringTop = cardTop()
        compose.mainClock.advanceTimeBy(256)
        compose.waitForIdle()
        val visibleTop = cardTop()
        assertTrue("Notice must expand gradually: before=$initialTop, entering=$enteringTop, shown=$visibleTop",
            enteringTop > initialTop && enteringTop < visibleTop)
        compose.onNodeWithTag("payment-lost-notice").performClick()
        assertEquals(1, unlost)
        compose.runOnIdle { current.value = normal }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val exitingTop = cardTop()
        assertTrue("Notice must shrink gradually: before=$initialTop, exiting=$exitingTop, shown=$visibleTop",
            exitingTop > initialTop && exitingTop < visibleTop)
        compose.onNodeWithTag("payment-lost-notice").assertIsNotEnabled()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(256)
        compose.waitForIdle()
        compose.onNodeWithTag("payment-lost-notice").assertDoesNotExist()
        assertEquals(initialTop, cardTop(), 1f)
    }
}
