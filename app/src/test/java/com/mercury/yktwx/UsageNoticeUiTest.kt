package com.mercury.yktwx

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.UsageConsent
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.ui.pages.LoginPage
import com.mercury.yktwx.ui.components.UsageNoticeSheet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageNoticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        repeat(2) { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
        compose.onRoot().captureToImage()
        val file = File("../tools/consent-review/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun end() {
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").assertIsEnabled()
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h780dp-xxhdpi")
    fun checkboxOpensFullNoticeAndOnlyExplicitConfirmationAfterReadingEnablesLogin() {
        val consent = UsageConsent(compose.activity)
        var requests = 0
        compose.setContent { AppTheme { LoginPage(PaymentUiState(), consent) { _, _ -> requests++ } } }
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        capture("login-initial-light")
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        capture("notice-light-top")
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        compose.onNodeWithText("不同意").performClick()
        assertFalse(consent.accepted)
        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithText("登录").assertIsNotEnabled()
        compose.onNodeWithText("密码").performImeAction()
        assertEquals(0, requests)
        compose.onNodeWithTag("usage-consent-checkbox").assertIsOff().performClick()
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        end()
        assertFalse(consent.accepted)
        capture("notice-light-end")
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) { consent.accepted }
        compose.onNodeWithTag("usage-consent-checkbox").assertIsOn()
        capture("login-agreed-light")
        compose.onNodeWithText("学号 / 工号").assertTextContains("202600000001")
        compose.onNodeWithText("密码").assertTextContains("fictional-password")
        compose.onNodeWithText("登录").assertIsEnabled()
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithText("登录").assertIsNotEnabled()
        compose.onNodeWithText("密码").performSemanticsAction(SemanticsActions.OnImeAction) { it() }
        assertEquals(0, requests)
        compose.onNodeWithTag("usage-consent-row").performClick()
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertFalse(consent.accepted)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi", fontScale = 1.5f)
    fun narrowLargeFontKeepsActionsVisibleAndReopeningResetsTheReadingPosition() {
        val consent = UsageConsent(compose.activity)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(ColorSchemeMode.Dark) { LoginPage(PaymentUiState(), consent) { _, _ -> } }
        } }
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithText("不同意").assertIsDisplayed()
        compose.onNodeWithText("同意并继续").assertIsDisplayed().assertIsNotEnabled()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("同意并继续").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        capture("notice-narrow-large-dark")
        end(); capture("notice-narrow-large-dark-end")
        compose.onNodeWithText("不同意").performClick()
        compose.onNodeWithTag("usage-consent-row").performClick()
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        compose.onNodeWithTag("usage-notice-paragraph-0").assertIsDisplayed()
        assertFalse(consent.accepted)
    }

    @Test fun existingAgreementDoesNotPromptAgainAndReadOnlyNoticeCannotChangeConsent() {
        val consent = UsageConsent(compose.activity)
        runBlocking { consent.accept() }
        compose.setContent { AppTheme { LoginPage(PaymentUiState(), consent) { _, _ -> } } }
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        compose.onNodeWithTag("usage-consent-checkbox").assertIsOn()
        // Click the actual link rather than the containing toggle row.
        compose.onNodeWithText("我已阅读并同意《${UsageNotice.Title}》").performTouchInput {
            click(center.copy(x = width * .65f))
        }
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        assertTrue(consent.accepted)
    }

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h1600dp-xhdpi")
    fun fullyVisibleNoticeCanBeConfirmedWithoutAnArtificialScroll() {
        var agreed = false
        compose.setContent { AppTheme { Scaffold { UsageNoticeSheet(true, {}, { agreed = true }) } } }
        compose.onNodeWithTag("usage-notice-paragraph-5").assertIsDisplayed()
        compose.onNodeWithText("同意并继续").assertIsEnabled().performClick()
        assertTrue(agreed)
    }

    @Test fun oldHashRevokesAlreadyRestoredLoginAndConfirmationDoesNotAutomaticallySignIn() {
        val app = compose.activity.application
        app.getSharedPreferences("usage_consent", android.content.Context.MODE_PRIVATE).edit()
            .putString("accepted_hash", "old-body").commit()
        val consent = UsageConsent(app)
        val payments = PaymentViewModel(app).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            YktApp(ThemeSettings(app), payments, bills, consent)
        } }
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        compose.onNodeWithText("我的").assertDoesNotExist()
        val profile = ViewModelProvider(compose.activity)[MyViewModel::class.java]
        assertNull(profile.state.value.profile)
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payments.state.value.needsLogin && !payments.state.value.busy }
        assertEquals("使用须知已更新，请重新阅读并同意后登录。", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("使用须知已更新，请重新阅读并同意后登录。").assertDoesNotExist()
        capture("login-notice-updated-light")
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) { consent.accepted }
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithTag("usage-notice-body").assertDoesNotExist()
        assertTrue(payments.state.value.needsLogin)
        assertTrue(consent.accepted)
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
    fun actualScrollRequiresAnExplicitButtonPressAndReturningUpDisablesConfirmation() {
        var accepted = 0
        compose.setContent { AppTheme { Scaffold { UsageNoticeSheet(true, {}, { accepted++ }) } } }
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        repeat(5) { compose.onNodeWithTag("usage-notice-body").performTouchInput { swipeUp(durationMillis = 300) } }
        compose.onNodeWithText("同意并继续").assertIsEnabled()
        assertEquals(0, accepted)
        compose.onNodeWithTag("usage-notice-body").performTouchInput { swipeDown(durationMillis = 300) }
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        assertEquals(0, accepted)
    }
}
