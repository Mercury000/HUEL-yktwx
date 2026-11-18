package com.mercury.yktwx

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mercury.yktwx.data.PaymentCodeProblem
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.PaymentPage
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import top.yukonga.miuix.kmp.basic.Scaffold
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi", shadows = [PaymentCodeScreenshotUiTest.CaptureActivity::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentCodeScreenshotUiTest {
    @Implements(Activity::class)
    class CaptureActivity : ShadowActivity() {
        val callbacks = linkedMapOf<Activity.ScreenCaptureCallback, Executor>()
        @Implementation(minSdk = 34)
        fun registerScreenCaptureCallback(executor: Executor, callback: Activity.ScreenCaptureCallback) {
            check(callback !in callbacks)
            callbacks[callback] = executor
        }
        @Implementation(minSdk = 34)
        fun unregisterScreenCaptureCallback(callback: Activity.ScreenCaptureCallback) {
            check(callbacks.remove(callback) != null)
        }
        fun screenshot() = callbacks.toMap().forEach { (callback, executor) -> executor.execute { callback.onScreenCaptured() } }
    }

    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val codeState = PreviewData.paymentState(false).copy(preview = false)
    private val capture get() = Shadow.extract<CaptureActivity>(compose.activity)
    private fun awaitListening(listening: Boolean) {
        compose.waitUntil(5_000) { capture.callbacks.isNotEmpty() == listening }
        compose.waitForIdle()
    }
    private fun content(page: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { page() } }
            }
        }
    }

    @Test fun screenshotShowsRequestedDialogWithoutBlockingAndAcknowledgementAllowsAnotherReminder() {
        val settings = themeSettingsFixture(compose.activity)
        content { PaymentPage(codeState, settings, 0.dp, {}) }
        awaitListening(true)
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertIsDisplayed()
        compose.onNodeWithText("仅限本人使用，外传可能造成损失").assertIsDisplayed()
        compose.onNodeWithText("知道了").assertIsDisplayed()
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onAllNodesWithText("请勿截图使用付款码").assertCountEquals(1)
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertDoesNotExist()
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertIsDisplayed()
    }

    @Test fun missingCodePreviewLeavingAndBackgroundStopListeningAndIgnoreQueuedCallbacks() {
        val settings = themeSettingsFixture(compose.activity)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var state by mutableStateOf(codeState.copy(code = null))
        var visible by mutableStateOf(true)
        content {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PaymentPage(state, settings, 0.dp, {}, isActive = visible)
            }
        }
        awaitListening(false)
        compose.runOnIdle { state = codeState }
        awaitListening(true)
        for (hidden in listOf(codeState.copy(busy = true), codeState.copy(problem = PaymentCodeProblem.Frozen),
            codeState.copy(error = "付款码暂不可用"), codeState.copy(preview = true))) {
            val oldCallback = capture.callbacks.keys.single()
            compose.runOnIdle { state = hidden }
            awaitListening(false)
            compose.runOnIdle { oldCallback.onScreenCaptured() }
            compose.waitForIdle()
            compose.onNodeWithText("请勿截图使用付款码").assertDoesNotExist()
            compose.runOnIdle { state = codeState }
            awaitListening(true)
        }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        awaitListening(true)
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertIsDisplayed()
        compose.onNodeWithText("知道了").performClick()
        compose.waitForIdle()
        val oldCallback = capture.callbacks.keys.single()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        awaitListening(false)
        compose.runOnIdle { oldCallback.onScreenCaptured() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertDoesNotExist()
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitListening(true)
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        awaitListening(false)
        compose.onNodeWithText("请勿截图使用付款码").assertDoesNotExist()
    }

    @Test fun scrollingCodeOffScreenStopsReminderAndEnlargedCodeStillTriggersIt() {
        val settings = themeSettingsFixture(compose.activity)
        content { PaymentPage(codeState, settings, 1000.dp, {}) }
        awaitListening(true)
        compose.onNodeWithTag("payment-page-list").performScrollToIndex(1)
        compose.onNodeWithTag("payment-page-list").performTouchInput { swipeUp() }
        awaitListening(false)
        compose.onNodeWithTag("payment-page-list").performScrollToIndex(0)
        awaitListening(true)
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("付款二维码").isDisplayed() }
        compose.onNodeWithContentDescription("付款二维码").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("放大的付款二维码").assertIsDisplayed()
        compose.runOnIdle { capture.screenshot() }
        compose.waitForIdle()
        compose.onNodeWithText("请勿截图使用付款码").assertIsDisplayed()
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
    }
}
