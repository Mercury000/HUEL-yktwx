package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.AppearanceBackdropContent
import com.mercury.yktwx.ui.components.rememberWhileShown
import com.mercury.yktwx.ui.pages.LifeAccountLoginSheet as AccountLoginSheet
import com.mercury.yktwx.ui.pages.LoginForm
import com.mercury.yktwx.ui.pages.OobePrimaryButton
import com.mercury.yktwx.ui.pages.OobePages
import com.mercury.yktwx.ui.pages.OobeStep
import com.mercury.yktwx.ui.pages.SetupAccounts
import com.mercury.yktwx.ui.pages.SetupAppearance
import com.mercury.yktwx.ui.pages.SetupBookend
import com.mercury.yktwx.ui.pages.UsageConsentPage
import com.mercury.yktwx.ui.themeColorSchemeMode
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OobePreviewTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun renderNativeOobeConceptScreens() {
        val settings = themeSettingsFixture(compose.activity)
        settings.setTheme(1)
        settings.setFloating(true)
        settings.setFloatingStyle(2)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme(themeColorSchemeMode(settings.themeMode.value), settings.accentMode.value, settings.glassStyle.value) {
                Scaffold { AppearanceBackdropContent { OobeDemo(settings, onReject = { compose.activity.finish() }) } }
            }
        }

        compose.mainClock.advanceTimeBy(480)
        captureFrame("01-welcome-reveal")
        compose.mainClock.advanceTimeBy(1550)
        val firstGlow = captureFrame("01-welcome")
        compose.mainClock.advanceTimeBy(970)
        val nextGlow = captureFrame("01-welcome-flow")
        assertNotEquals(firstGlow.getPixel(firstGlow.width / 4, firstGlow.height / 6),
            nextGlow.getPixel(nextGlow.width / 4, nextGlow.height / 6))
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("开始设置").performClick()
        compose.onNodeWithText("上滑并阅读").assertIsNotEnabled()
        val consentPanel = compose.onNodeWithTag("oobe-consent-panel").getUnclippedBoundsInRoot()
        capture("02-privacy")
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").assertIsEnabled()
        capture("02-privacy-read")
        compose.onNodeWithText("同意并继续").performClick()

        val next = compose.onNodeWithText("下一步")
        val campusRow = compose.onNodeWithTag("oobe-campus-row")
        val lifeRow = compose.onNodeWithTag("oobe-life-row-${LifeProvider.Hui798.tag}")
        val campusLabel = compose.onNodeWithTag("oobe-campus-row-label", useUnmergedTree = true)
        val lifeLabel = compose.onNodeWithTag("oobe-life-row-${LifeProvider.Hui798.tag}-label", useUnmergedTree = true)
        val nextRequirement = compose.onNodeWithText("登录校园一卡通后可继续")
        next.assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("校园服务").assertIsDisplayed()
        compose.onNodeWithText("生活服务").assertIsDisplayed()
        nextRequirement.assertIsDisplayed()
        val campusBeforeLogin = campusLabel.assertTextEquals("去登录").getUnclippedBoundsInRoot()
        val lifeBeforeLogin = lifeLabel.assertTextEquals("去登录").getUnclippedBoundsInRoot()
        assertEquals(campusBeforeLogin.left, lifeBeforeLogin.left)
        assertEquals(campusBeforeLogin.right, lifeBeforeLogin.right)
        capture("03-login-accounts")

        campusRow.assertHasClickAction().performClick()
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithTag("oobe-login-drawer").assertIsDisplayed()
        capture("04-login-drawer")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("oobe-login-drawer").assertDoesNotExist()
        compose.onNodeWithText("登录账号").assertIsDisplayed()
        next.assertIsNotEnabled()
        campusRow.performClick()
        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        capture("04-login-drawer-filled")
        compose.onNodeWithText("登录").assertIsEnabled().performClick()
        compose.onNodeWithTag("oobe-login-drawer").assertDoesNotExist()
        campusRow.assertHasNoClickAction()
        assertEquals(campusBeforeLogin, campusLabel.assertTextEquals("已登录").getUnclippedBoundsInRoot())
        compose.onAllNodesWithText("去登录").assertCountEquals(LifeProvider.entries.size)
        next.assertIsEnabled()
        nextRequirement.assertDoesNotExist()
        capture("05-login-optional")
        compose.mainClock.autoAdvance = false
        next.performClick()
        compose.mainClock.advanceTimeBy(175)
        val midSlide = compose.onNodeWithText("选择外观").getUnclippedBoundsInRoot().left
        compose.mainClock.advanceTimeBy(210)
        val settled = compose.onNodeWithText("选择外观").getUnclippedBoundsInRoot().left
        assertTrue(midSlide > settled)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("选择外观").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        campusLabel.assertTextEquals("已登录")
        val lifeName = compose.onNodeWithText("慧生活798", useUnmergedTree = true)
        val lifeNameBeforeLogin = lifeName.getUnclippedBoundsInRoot()
        lifeRow.assertHasClickAction().performClick()
        compose.onNodeWithText("登录慧生活798").assertIsDisplayed()
        compose.onNodeWithText("模拟登录").performClick()
        lifeRow.assertHasNoClickAction()
        assertEquals(lifeBeforeLogin, lifeLabel.assertTextEquals("已登录").getUnclippedBoundsInRoot())
        assertEquals(lifeNameBeforeLogin, lifeName.getUnclippedBoundsInRoot())
        next.assertIsEnabled()
        capture("05-login-mixed")
        next.performClick()

        compose.onNodeWithTag("main-floating-scan").assertIsDisplayed()
        compose.onNodeWithText("充值").assertIsDisplayed()
        val settingsCard = compose.onNodeWithTag("theme-settings-card").getUnclippedBoundsInRoot()
        val balanceCard = compose.onNodeWithTag("oobe-balance-card").getUnclippedBoundsInRoot()
        assertEquals(settingsCard.left, balanceCard.left)
        assertEquals(settingsCard.right, balanceCard.right)
        assertEquals(settingsCard.left, consentPanel.left)
        assertEquals(settingsCard.right, consentPanel.right)
        capture("06-appearance")
        compose.onNode(hasText("导航样式") and hasClickAction()).performClick()
        compose.onNodeWithText("iOS").performClick()
        compose.waitUntil(5_000) { settings.floatingBottomBarStyle.value == 1 }
        val iosBalance = compose.onNodeWithTag("oobe-balance-card").assertIsDisplayed().getUnclippedBoundsInRoot()
        val iosScan = compose.onNodeWithTag("main-floating-scan").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithText("统一账户").assertIsDisplayed()
        compose.onNodeWithText("86.50", substring = true).assertIsDisplayed()
        compose.onNodeWithText("充值").assertIsDisplayed()
        assertEquals(balanceCard.bottom - balanceCard.top, iosBalance.bottom - iosBalance.top)
        assertTrue(iosBalance.bottom <= iosScan.top)
        capture("06-appearance-ios")
        compose.onNode(hasText("导航样式") and hasClickAction()).performClick()
        compose.onNodeWithText("OS4").performClick()
        compose.waitUntil(5_000) { settings.floatingBottomBarStyle.value == 2 }

        compose.onNode(hasText("悬浮导航") and hasClickAction()).performClick()
        compose.onNodeWithTag("main-floating-scan").assertDoesNotExist()
        compose.onNodeWithContentDescription("扫一扫").assertIsDisplayed()
        compose.onNode(hasText("悬浮导航") and hasClickAction()).performClick()
        compose.onNodeWithTag("theme-page-list").performTouchInput { swipeUp() }
        compose.onNodeWithText("顶栏模糊").assertIsDisplayed()
        compose.onNodeWithText("滑动返回").assertIsDisplayed()
        capture("07-appearance-options")
        compose.onNodeWithText("完成设置").performClick()
        compose.onNodeWithText("设置完成").assertIsDisplayed()
        capture("08-complete")
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1900)
        compose.waitForIdle()
        captureFrame(name)
    }

    private fun captureFrame(name: String): Bitmap {
        val snapshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File("../tools/consent-review/oobe-$name.png").apply { parentFile?.mkdirs() }
        output.outputStream().use { snapshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return snapshot
    }
}

private sealed interface LoginTarget {
    val title: String

    data object Campus : LoginTarget { override val title = "登录校园一卡通" }
    data class Life(val provider: LifeProvider) : LoginTarget { override val title = "登录${provider.displayName}" }
}

@Composable
private fun OobeDemo(settings: ThemeSettings, onReject: () -> Unit) {
    var page by remember { mutableStateOf(OobeStep.Welcome) }
    var accepted by remember { mutableStateOf(false) }
    var campusSignedIn by remember { mutableStateOf(false) }
    val connected = remember { mutableStateListOf<LifeProvider>() }
    OobePages(page) { visibleStep, active, onStartBounds ->
        when (visibleStep) {
            OobeStep.Welcome -> SetupBookend(complete = false, active = active, onStartBounds = onStartBounds) { page = OobeStep.Privacy }
            OobeStep.Privacy -> {
                BackHandler(enabled = active) { page = OobeStep.Welcome }
                UsageConsentPage(false, null, onReject, onBack = { page = OobeStep.Welcome }, accepted = accepted) {
                    accepted = true
                    page = OobeStep.Accounts
                }
            }
            OobeStep.Accounts -> AccountStep(campusSignedIn, connected, connected::add,
                onBack = { page = OobeStep.Privacy }, active = active,
                onCampusLogin = { campusSignedIn = true },
                onNext = { if (campusSignedIn) page = OobeStep.Appearance })
            OobeStep.Appearance -> SetupAppearance(settings, onBack = { page = OobeStep.Accounts }, active = active) {
                page = OobeStep.Complete
            }
            OobeStep.Complete -> SetupBookend(complete = true, active = active) { page = OobeStep.Welcome }
        }
    }
}

@Composable
private fun AccountStep(campusSignedIn: Boolean, connected: List<LifeProvider>,
    onOptionalLogin: (LifeProvider) -> Unit, onBack: () -> Unit, active: Boolean, onCampusLogin: () -> Unit, onNext: () -> Unit) {
    var target by remember { mutableStateOf<LoginTarget?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissLogin() { keyboard?.hide(); target = null }
    val goBack = { if (target != null) dismissLogin() else onBack() }
    BackHandler(enabled = active, onBack = goBack)
    SetupAccounts(campusSignedIn, campusRestoring = false, signedIn = connected.toSet(), restoring = emptySet(),
        onBack = goBack, onCampus = { target = LoginTarget.Campus },
        onOptionalLogin = { target = LoginTarget.Life(it) }, onNext = onNext) {
        val showLogin = target != null
        val displayTarget = rememberWhileShown(showLogin, target)
        AccountLoginSheet(showLogin, displayTarget?.title.orEmpty(), "oobe-login-drawer", ::dismissLogin) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                when (displayTarget) {
                    LoginTarget.Campus -> LoginForm(PaymentUiState(), onLogin = { _, _ ->
                        onCampusLogin()
                        dismissLogin()
                    }, agreed = showLogin, onInputEdited = {}, updateNotice = {}, showHeading = false)
                    is LoginTarget.Life -> OobePrimaryButton("模拟登录", {
                        onOptionalLogin(displayTarget.provider)
                        dismissLogin()
                    }, Modifier.fillMaxWidth(), enabled = showLogin)
                    null -> Unit
                }
            }
        }
    }
}
