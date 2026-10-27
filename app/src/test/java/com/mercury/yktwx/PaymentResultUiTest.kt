package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.HallFeeDialog
import com.mercury.yktwx.ui.pages.PaymentResultContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentResultUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)
    private val wechat = FeeMethod("101", "TSM", "微信", false, 0)
    private val methods = listOf(account, wechat)

    private fun content(
        phase: RechargePhase = RechargePhase.Failed,
        detail: String? = "持卡人已挂失！",
        subject: String = "缴费",
        retryMethods: List<FeeMethod> = emptyList(),
        loading: Boolean = false,
        fontScale: Float = 1f,
        onContinue: (FeeMethod) -> Unit = {},
        onFinish: () -> Unit = {},
        onCheck: (() -> Unit)? = null,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                AppTheme {
                    Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            PaymentResultContent(phase, loading, 1000, detail, onFinish, subject,
                                retryMethods = retryMethods, onContinuePayment = onContinue, onCheck = onCheck)
                        }
                    }
                }
            }
        }
    }

    @Test fun scanPaymentSuccessReusesPaidContentAndFinishAction() {
        var finished = false
        content(phase = RechargePhase.Paid, detail = null, subject = "支付", onFinish = { finished = true })
        compose.onNodeWithText("支付成功").assertIsDisplayed()
        compose.onNodeWithText("¥ 10.00").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertTrue(finished) }
    }

    @Test fun rechargeFailureReplacesAmountWithOfficialMessageWithoutRepeatingIt() {
        content(subject = "充值")
        compose.onNodeWithText("充值失败").assertExists()
        compose.onAllNodesWithText("持卡人已挂失！").assertCountEquals(1)
        compose.onNodeWithText("¥ 10.00").assertDoesNotExist()
        compose.onNodeWithText("完成").assertExists()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
    }

    @Test fun othersRechargeFailureAlsoReplacesAmountAndKeepsCompletion() {
        content(subject = "代充值", detail = "账户已挂失")
        compose.onNodeWithText("代充值失败").assertExists()
        compose.onNodeWithText("账户已挂失").assertExists()
        compose.onNodeWithText("¥ 10.00").assertDoesNotExist()
        compose.onNodeWithText("完成").assertExists()
    }

    @Test fun missingFailureMessageHasReadableFallbackInsteadOfAmount() {
        content(detail = "")
        compose.onNodeWithText("若扣款成功，请稍后在账单查看").assertExists()
        compose.onNodeWithText("¥ 10.00").assertDoesNotExist()
    }

    @Test fun timedOutResultKeepsOfficialMessageAndDoesNotOfferAnotherPayment() {
        content(phase = RechargePhase.TimedOut, detail = "订单超时原因", retryMethods = methods)
        compose.onNodeWithText("支付超时").assertExists()
        compose.onNodeWithText("订单超时原因").assertExists()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertExists()
    }

    @Test fun paidResultStillShowsAmountAndCompletionNotFailureReason() {
        content(phase = RechargePhase.Paid, detail = null, retryMethods = methods)
        compose.onNodeWithText("缴费成功").assertExists()
        compose.onNodeWithText("¥ 10.00").assertExists()
        compose.onNodeWithText("完成").assertExists()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
    }

    @Test fun queryErrorRetriesOnlyTheQueryEvenWhenMethodsWereProvided() {
        var checks = 0
        content(phase = RechargePhase.Checking, detail = "网络连接失败", retryMethods = methods,
            onCheck = { checks++ }, onContinue = { fail("A query error cannot submit payment") })
        compose.onNodeWithText("支付结果").assertExists()
        compose.onNodeWithText("¥ 10.00").assertExists()
        compose.onNodeWithText("网络连接失败").assertExists()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, checks) }
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("一卡通支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertExists()
    }

    @Test fun multiMethodFailureRequiresSelectionAndShowsCancelToTheLeft() {
        val submitted = mutableListOf<FeeMethod>()
        content(retryMethods = methods, onContinue = { submitted += it })
        compose.onNodeWithText("可选择以下方式重新支付").assertExists()
        val cancel = compose.onNodeWithText("取消支付").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        val continueButton = compose.onNodeWithText("继续支付").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue("Cancel must be to the left of continue", cancel.right < continueButton.left)
        assertEquals(cancel.center.y, continueButton.center.y, 1f)
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithText("¥ 10.00").assertDoesNotExist()
        compose.onNodeWithText("一卡通支付").assertIsNotSelected()
        compose.onNodeWithText("微信支付").assertIsNotSelected().performClick()
        compose.onNodeWithText("微信支付").assertIsSelected()
        compose.onNodeWithText("一卡通支付").assertIsNotSelected()
        assertTrue(submitted.isEmpty())
        screenshot("fee-payment-failure")
        compose.onNodeWithText("继续支付").assertIsEnabled().performClick()
        assertSame(wechat, submitted.single())
        compose.onNodeWithText("一卡通支付").performClick().assertIsSelected()
        compose.onNodeWithText("微信支付").assertIsNotSelected()
    }

    @Test fun noAlternativeCannotShowContinue() {
        content(retryMethods = emptyList())
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("可选择以下方式重新支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertExists()
    }

    @Test fun cancelIsAvailableBeforeAndAfterSelectionAndNeverContinuesPayment() {
        var cancellations = 0
        var payments = 0
        content(retryMethods = listOf(wechat), onFinish = { cancellations++ }, onContinue = { payments++ })
        compose.onNodeWithText("取消支付").assertIsEnabled().performClick()
        assertEquals(1, cancellations)
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
        compose.onNodeWithText("微信支付").performClick()
        compose.onNodeWithText("继续支付").assertIsEnabled()
        compose.onNodeWithText("取消支付").performClick()
        assertEquals(2, cancellations)
        assertEquals(0, payments)
    }

    @Test fun singleAlternativeUsesSharedGreyAndBlueCardAndCanBeDeselected() {
        var grey = Color.Unspecified
        var primary = Color.Unspecified
        val submitted = mutableListOf<FeeMethod>()
        compose.setContent {
            AppTheme {
                grey = MiuixTheme.colorScheme.surfaceContainerHigh
                primary = MiuixTheme.colorScheme.primary
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp)) {
                        PaymentResultContent(RechargePhase.Failed, false, 1000, "持卡人已挂失！", {}, "缴费",
                            retryMethods = listOf(wechat, wechat), onContinuePayment = { submitted += it })
                    }
                }
            }
        }
        fun background() = compose.onNode(hasText("微信支付") and hasClickAction()).captureToImage()
            .asAndroidBitmap().let { bitmap -> bitmap.getPixel(bitmap.width / 2, 8) }
        compose.onNodeWithText("一卡通支付").assertDoesNotExist()
        compose.onAllNodesWithText("微信支付").assertCountEquals(1)
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
        compose.onNodeWithText("完成").assertDoesNotExist()
        assertEquals(grey.toArgb(), background())
        screenshot("fee-payment-failure-unselected")
        compose.onNodeWithText("微信支付").performClick().assertIsSelected()
        assertEquals(primary.toArgb(), background())
        compose.onNodeWithText("已选择").assertExists()
        compose.onNodeWithText("继续支付").assertIsEnabled().performClick()
        assertSame(wechat, submitted.single())
        screenshot("fee-payment-failure-selected")
        compose.onNodeWithText("微信支付").performClick().assertIsNotSelected()
        assertEquals(grey.toArgb(), background())
        compose.onNodeWithText("已选择").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
    }

    @Test fun creatingNewOrderHasNoPaymentChoicesOrCompletion() {
        content(phase = RechargePhase.Creating, loading = true, detail = null, retryMethods = methods)
        compose.onNodeWithText("正在发起缴费").assertExists()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithText("一卡通支付").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w320dp-h568dp-xxhdpi")
    fun longOfficialMessageAndLargeFontKeepContinueReachable() {
        val message = "服务端返回的详细失败原因：当前账户无法完成本次缴费，请核实账户状态后重新选择支付方式。".repeat(4)
        content(detail = message, retryMethods = methods, fontScale = 1.8f)
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNodeWithText("微信支付").performScrollTo().performClick()
        compose.onNodeWithText("继续支付").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消支付").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("完成").assertDoesNotExist()
        screenshot("fee-payment-failure-large-font")
    }

    @Test fun hallFeeDialogConnectsDraftToInlineRetryAndKeepsTarget() {
        val state = FeeUiState(
            config = FeeConfiguration(FeeProject.Network, RechargeConfiguration("412", "", listOf(1000), 1, 10000, true, false)),
            query = FeeQuery(thirdParty = "{}"), stage = FeeStage.Result, resultPhase = RechargePhase.Failed,
            error = "持卡人已挂失！", paymentRetry = FeePaymentRetry(1000, listOf(wechat)),
        )
        var submitted: FeeMethod? = null
        val current = mutableStateOf(state)
        var cancellations = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(current.value, true, FeeProject.Network, null,
                        onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> },
                        onMethod = {}, onPayAccount = { _, _ -> }, onRoomAction = {},
                        onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {},
                        onFinish = {
                            cancellations++
                            current.value = FeeUiState(config = state.config, query = state.query)
                        }, onCancelPayment = {}, onContinuePayment = { submitted = it })
                } }
            }
        }
        compose.onNodeWithText("持卡人已挂失！").assertExists()
        compose.onNodeWithText("一卡通支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
        compose.onNodeWithText("微信支付").performClick()
        screenshot("fee-payment-failure-sheet")
        compose.onNodeWithText("继续支付").performClick()
        assertSame(wechat, submitted)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("取消支付").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("充值失败").assertExists()
        compose.onNodeWithText("微信支付").assertIsNotEnabled()
        compose.onNodeWithText("取消支付").assertIsNotEnabled()
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("充值失败").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        assertEquals(1, cancellations)
        assertSame(state.query, current.value.query)
    }

    @Test fun passwordRejectionLeavesEmptyInputAndRequiresFreshPasswordConfirmation() {
        val preparation = FeeAccountPreparation("FAKE-ORDER", account.id,
            listOf(FeeAccount("demo-type", "统一账户", 1000)), "fake-uuid", "9876543210")
        val initial = FeeUiState(
            config = FeeConfiguration(FeeProject.Network, RechargeConfiguration("412", "", listOf(1000), 1, 10000, true, false)),
            query = FeeQuery(thirdParty = "{}"), stage = FeeStage.Password,
            order = FeeOrder(PendingRecharge("test-user", "{}", "412", 1000, "FAKE-ORDER", account.id), methods),
            method = account, preparation = preparation,
        )
        val current = mutableStateOf(initial)
        val passwords = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(current.value, true, FeeProject.Network, null,
                        onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> },
                        onMethod = {}, onContinuePayment = {}, onRoomAction = {},
                        onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {},
                        onPayAccount = { type, password ->
                            assertEquals("demo-type", type)
                            passwords += password
                            current.value = FeeUiState(config = initial.config, query = initial.query,
                                stage = FeeStage.Password, order = initial.order, method = account,
                                preparation = preparation, error = "账户密码错误")
                        }, onFinish = {}, onCancelPayment = {})
                } }
            }
        }
        fun input() = compose.onNode(hasSetTextAction() and
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password))
        compose.onNodeWithText("确定支付").assertIsNotEnabled()
        input().performTextInput("123456")
        compose.onNodeWithText("确定支付").assertIsEnabled().performClick()
        assertEquals(listOf("123456"), passwords)
        compose.onNodeWithText("账户密码错误").assertExists()
        compose.onNodeWithText("六位支付密码").assertExists()
        compose.onNodeWithText("缴费失败").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        input().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("确定支付").assertIsNotEnabled()
        screenshot("fee-password-correction")
        input().performTextInput("65432")
        compose.onNodeWithText("确定支付").assertIsNotEnabled()
        input().performTextInput("1")
        assertEquals(1, passwords.size)
        compose.onNodeWithText("确定支付").assertIsEnabled().performClick()
        assertEquals(listOf("123456", "654321"), passwords)
        input().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("")))
        assertSame(initial.order, current.value.order)
        assertSame(preparation, current.value.preparation)
    }

    private fun screenshot(name: String) {
        val output = File("../tools/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
