package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.PaymentAccount
import com.mercury.yktwx.data.PaymentCodeProblem
import com.mercury.yktwx.data.ReadFailureKind
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.PaymentPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentCardLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = PaymentAccount("fake-card", "fake-payacc", "1", "统一账户", 12860)
    private fun state(problem: PaymentCodeProblem? = null, busy: Boolean = false, error: String? = null) =
        PaymentUiState(needsLogin = false, username = "fake-user", account = account,
            // Deliberately retain dummy code in the input: restricted/loading branches must hide it.
            code = "00000000000000000000", secondsLeft = 60, problem = problem, busy = busy, error = error)
    private fun content(current: State<PaymentUiState>, dark: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ThemeSettings(context)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    PaymentPage(current.value, settings, 0.dp, onRefresh = {})
                }
            }
        }
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun screenshot(name: String) {
        val file = File("../tools/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun sameBounds(expected: Rect, actual: Rect) {
        assertEquals("left", expected.left, actual.left, 1f)
        assertEquals("top", expected.top, actual.top, 1f)
        assertEquals("width", expected.width, actual.width, 1f)
        assertEquals("height", expected.height, actual.height, 1f)
    }
    private fun assertStableTransitions(capture: Boolean = false) {
        val current = mutableStateOf(state())
        content(current)
        val card = bounds("payment-code-card")
        val body = bounds("payment-code-content")
        val accountTop = compose.onNodeWithText("统一账户").fetchSemanticsNode().boundsInRoot.top
        if (capture) screenshot("payment-card-normal-stable")
        for ((name, next) in listOf(
            "verifying" to state(PaymentCodeProblem.Lost, busy = true),
            "lost" to state(PaymentCodeProblem.Lost),
            "unlost-loading" to state(busy = true),
            "frozen" to state(PaymentCodeProblem.Frozen),
            "unverified" to state(PaymentCodeProblem.StatusUnverified),
            "normal" to state(),
            "verifying-again" to state(busy = true),
            "normal-again" to state(),
            "waiting-for-code" to PaymentUiState(needsLogin = false, username = "fake-user", account = account),
            "code-error" to PaymentUiState(needsLogin = false, username = "fake-user", account = account, error = "示例服务端错误"),
            "network-error" to state(error = "网络连接失败"),
        )) {
            compose.runOnIdle { current.value = next }
            compose.waitForIdle()
            sameBounds(card, bounds("payment-code-card"))
            sameBounds(body, bounds("payment-code-content"))
            assertEquals("Account card must not move in $name", accountTop,
                compose.onNodeWithText("统一账户").fetchSemanticsNode().boundsInRoot.top, 1f)
            if (next.problem != null || next.busy || next.error != null) {
                compose.onNodeWithContentDescription("付款条形码").assertDoesNotExist()
                compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
            }
            if (next.error != null || next.problem == PaymentCodeProblem.StatusUnverified) {
                val group = bounds("payment-status-group")
                assertEquals(body.center.x, group.center.x, 1f)
                assertEquals(body.center.y, group.center.y, 1f)
                assertTrue(group.top >= body.top && group.bottom <= body.bottom)
                compose.onNodeWithText("解挂").assertDoesNotExist()
                compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
            }
            if (capture && name == "verifying") screenshot("payment-card-$name-stable")
        }
    }
    @Test fun cardAndBalanceKeepIdenticalBoundsAcrossNormalLostAndVerificationStates() = assertStableTransitions(capture = true)
    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun largeFontsAndNarrowWidthDoNotChangeHeightBetweenStates() = assertStableTransitions()

    @Test fun verificationGroupIsCenteredOnBothAxes() {
        val current = mutableStateOf(state(busy = true))
        content(current)
        val body = bounds("payment-code-content")
        val group = bounds("payment-status-group")
        assertEquals(body.center.x, group.center.x, 1f)
        assertEquals(body.center.y, group.center.y, 1f)
        val title = compose.onNodeWithText("正在确认卡状态").fetchSemanticsNode().boundsInRoot
        assertEquals(body.center.x, title.center.x, 1f)
        assertTrue(group.top >= body.top && group.bottom <= body.bottom)
        compose.onNodeWithText("解挂").assertDoesNotExist()
    }
    @Test fun unlostButtonUsesLowerSlotInsteadOfLeavingConcentratedBottomWhitespace() {
        val current = mutableStateOf(state(PaymentCodeProblem.Lost))
        content(current)
        val card = bounds("payment-code-card")
        val body = bounds("payment-code-content")
        val button = compose.onNode(hasText("解挂") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertEquals("Button should have only the planned inner bottom padding", 16f * density, body.bottom - button.bottom, 2f)
        assertEquals("Card outer padding must remain consistent", 36f * density, card.bottom - button.bottom, 2f)
        val summary = compose.onNodeWithText("校园卡已挂失，当前无法使用付款码\n找回卡片后，可通过解挂恢复使用").fetchSemanticsNode().boundsInRoot
        assertTrue("Description and button should not be stacked at the top", button.top - summary.bottom >= 16f * density)
        compose.onNodeWithText("解挂").assertIsDisplayed()
        screenshot("payment-card-lost-stable")
    }
    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun largeFontVerificationStillCentersWrappedTextInsideTheSharedCard() {
        val current = mutableStateOf(state(busy = true))
        content(current, dark = true)
        val body = bounds("payment-code-content")
        val group = bounds("payment-status-group")
        assertTrue(abs(body.center.x - group.center.x) <= 1f)
        assertTrue(abs(body.center.y - group.center.y) <= 1f)
        screenshot("payment-card-verifying-dark")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h800dp-xxhdpi", fontScale = 1.5f)
    fun largeFontReadFailureUsesSingleLineStatusAndRetainsTheBalanceInDarkTheme() {
        content(mutableStateOf(state(error = "网络连接失败").copy(errorKind = ReadFailureKind.Network)), dark = true)
        val body = bounds("payment-code-content")
        val group = bounds("payment-status-group")
        assertEquals(body.center.y, group.center.y, 1f)
        for (text in listOf("付款码暂不可用", "网络连接失败，请下拉刷新")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {
                it(layouts)
            }
            assertEquals("Read failure text must remain single-line", 1, layouts.single().lineCount)
        }
        compose.onNodeWithText("¥ 128.60").assertIsDisplayed()
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        screenshot("payment-card-network-error-dark")
    }
}
