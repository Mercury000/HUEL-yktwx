package com.mercury.yktwx

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.LoginPage
import com.mercury.yktwx.ui.pages.PaymentPage
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalOverlayRestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun sharedSmsAndCaptchaFieldsCannotRestorePrivateTextAfterOwnerClearsCodes() {
        val restorer = StateRestorationTester(compose)
        var recreated = false
        restorer.setContent { AppTheme { androidx.compose.foundation.layout.Column {
            com.mercury.yktwx.ui.components.DigitTextField(if (recreated) "" else "123456", {}, "短信验证码", 8,
                Modifier.testTag("restoration-sms"))
            com.mercury.yktwx.ui.components.CaptchaTextField(if (recreated) "" else "ABCD", {}, "图形验证码",
                Modifier.testTag("restoration-captcha"))
        } } }
        compose.onNodeWithTag("restoration-sms").assertTextContains("123456")
        compose.onNodeWithTag("restoration-captcha").assertTextContains("ABCD")
        recreated = true
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("restoration-sms").assert(hasText("", substring = false))
        compose.onNodeWithTag("restoration-captcha").assert(hasText("", substring = false))
    }

    @Test fun campusLoginRestoresUsernameButPasswordAndSubmissionDoNotSurvive() {
        val restorer = StateRestorationTester(compose)
        var logins = 0
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            LoginPage(PaymentUiState(needsLogin = true), onLogin = { _, _ -> logins++ })
        } } }
        compose.onNodeWithText("学号 / 工号").performTextReplacement("202611130001")
        compose.onNodeWithText("密码").performTextReplacement("temporary-input")
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("学号 / 工号").assertTextContains("202611130001")
        compose.onNodeWithText("密码").assert(hasText("", substring = false))
        assertEquals(0, logins)
    }

    @Test fun enlargedCodeWaitsForFreshReadAndNeverPaintsPreviousCode() {
        val restorer = StateRestorationTester(compose)
        val settings = themeSettingsFixture(compose.activity.application)
        val ready = PreviewData.paymentState(false)
        var recreated = false
        lateinit var live: MutableState<PaymentUiState>
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            val state = remember { mutableStateOf(if (recreated) ready.copy(code = null, busy = true) else ready) }
            SideEffect { live = state }
            PaymentPage(state.value, settings, 0.dp, {})
        } } } }
        compose.onNodeWithContentDescription("付款二维码").performClick()
        compose.onNodeWithContentDescription("放大的付款二维码").assertIsDisplayed()
        recreated = true
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
        compose.runOnIdle { live.value = ready.copy(code = "12345678901234567890") }
        compose.onNodeWithContentDescription("放大的付款二维码").assertIsDisplayed()
        compose.runOnIdle { live.value = live.value.copy(code = null, error = "网络连接失败") }
        compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
        compose.runOnIdle { live.value = ready }
        compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
    }
}
