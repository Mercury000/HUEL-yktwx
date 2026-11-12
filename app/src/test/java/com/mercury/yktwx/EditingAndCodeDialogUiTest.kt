package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mercury.yktwx.data.CampusCard
import com.mercury.yktwx.data.CampusCardAction
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.trackInputFocus
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField

/** Real pointer gestures and intermediate animation frames, rather than just final focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditingAndCodeDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private var hides = 0
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() = Unit
        override fun hide() { hides++ }
    }
    private fun content(block: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalSoftwareKeyboardController provides keyboard) {
                AppTheme { block() }
            }
        }
    }
    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction())
    private fun imeVisible() {
        compose.runOnIdle {
            val height = (250 * compose.activity.resources.displayMetrics.density).toInt()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
    private fun switchWithoutHiding(target: SemanticsNodeInteraction) {
        val before = hides
        // Text selection's long-press magnifier is not used for a focus-switch tap.
        target.performTouchInput { down(center); advanceEventTime(16); up() }
        target.assertIsFocused()
        assertEquals("Switching fields must never explicitly hide the keyboard", before, hides)
    }

    @Test fun loginChangesBetweenNumericAndPasswordFieldsWithoutHidingKeyboard() {
        content { LoginPage(PaymentUiState(needsLogin = true)) { _, _ -> } }
        field("学号 / 工号").performTouchInput { click(center) }
        imeVisible()
        repeat(3) {
            switchWithoutHiding(field("密码"))
            switchWithoutHiding(field("学号 / 工号"))
        }
        field("学号 / 工号").performImeAction()
        field("密码").assertIsFocused()
        val before = hides
        compose.onNodeWithContentDescription("显示密码").performClick()
        field("密码").assertIsFocused()
        assertEquals(before, hides)
        compose.onNodeWithText("河南财经政法大学").performTouchInput { click(center) }
        field("密码").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun billFilterMovesFocusBothWaysWithoutDismissingKeyboardOrMovingTheSheet() {
        content { Scaffold {
            BillPage(PreviewData.billState(), themeSettingsFixture(compose.activity.application), {}, {}, {}, {},
                { _, _, _ -> }, {}, {}, {}, showBack = false)
        } }
        compose.onNodeWithContentDescription("筛选账单").performClick()
        field("最低").performTouchInput { click(center) }
        imeVisible()
        val titleBounds = compose.onNodeWithText("筛选账单").fetchSemanticsNode().boundsInRoot
        repeat(3) {
            switchWithoutHiding(field("最高"))
            assertEquals(titleBounds, compose.onNodeWithText("筛选账单").fetchSemanticsNode().boundsInRoot)
            switchWithoutHiding(field("最低"))
        }
        val before = hides
        field("最低").performImeAction()
        field("最高").assertIsFocused()
        assertEquals(before, hides)
        field("最高").performImeAction()
        field("最高").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun queryPasswordDialogTransfersFocusWithoutHidingAndOutsideTapStillDismisses() {
        val card = CampusCard("fake-card", "校园卡", "202600000001", false, false, 0)
        content { Scaffold { CardPasswordDialog(CardPasswordUiState(visible = true, cards = listOf(card), selectedCard = card),
            {}, {}, { _, _, _ -> }) } }
        field("原查询密码").performTouchInput { click(center) }
        imeVisible()
        switchWithoutHiding(field("新查询密码（6 位数字）"))
        switchWithoutHiding(field("确认新查询密码"))
        switchWithoutHiding(field("原查询密码"))
        val before = hides
        compose.onNodeWithText("修改查询密码").performTouchInput { click(center) }
        field("原查询密码").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun removedInputDoesNotLeaveAHitRegionThatPreventsOutsideDismissal() {
        var second by mutableStateOf(true)
        content { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField("", {}, label = "第一个", modifier = Modifier.trackInputFocus().fillMaxWidth())
            if (second) TextField("", {}, label = "第二个", modifier = Modifier.trackInputFocus().fillMaxWidth())
        } }
        val oldBounds = field("第二个").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { second = false }
        field("第一个").performTouchInput { click(center) }
        val before = hides
        compose.onRoot().performTouchInput { click(oldBounds.center) }
        field("第一个").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun disabledFieldIsNotConsideredAnEditableFocusDestination() {
        content { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField("", {}, label = "可输入", modifier = Modifier.trackInputFocus().fillMaxWidth())
            TextField("", {}, label = "不可输入", enabled = false, modifier = Modifier.trackInputFocus(false).fillMaxWidth())
        } }
        field("可输入").performTouchInput { click(center) }
        val before = hides
        compose.onNodeWithText("不可输入").performTouchInput { click(center) }
        field("可输入").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun recipientAndCaptchaFieldsTransferFocusWithoutClosingKeyboard() {
        content { Scaffold {
            CardServiceDialog(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge),
                {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {})
        } }
        field("学工号").performTouchInput { click(center) }
        imeVisible()
        switchWithoutHiding(field("验证码"))
        switchWithoutHiding(field("学工号"))
        val before = hides
        compose.onNodeWithText("代人充值").performTouchInput { click(center) }
        field("学工号").assertIsNotFocused()
        assertEquals(before + 1, hides)
    }

    @Test fun bothCodeFormatsCanCollapseAndBackOutDuringExitAndNeverKeepAnInvalidCode() {
        var state by mutableStateOf(PreviewData.paymentState(false))
        content { Scaffold { PaymentPage(state, themeSettingsFixture(compose.activity.application), 0.dp, {}) } }
        fun artwork(description: String) {
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        }
        for (format in listOf("条形码", "二维码")) {
            repeat(2) { attempt ->
                artwork("付款$format")
                compose.onNodeWithContentDescription("付款$format").performClick()
                artwork("放大的付款$format")
                compose.onNodeWithContentDescription("放大的付款$format").assertExists()
                compose.mainClock.autoAdvance = false
                if (attempt == 0) compose.onNodeWithText("收起").performClick()
                else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                // The root popup handles Back and receives host state on subsequent frames.
                repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.onNodeWithText("收起").assertIsNotEnabled()
                repeat(24) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                compose.mainClock.autoAdvance = true
                compose.onNodeWithContentDescription("放大的付款$format").assertDoesNotExist()
                compose.onNodeWithContentDescription("付款$format").assertExists()
            }
        }
        compose.onNodeWithContentDescription("付款二维码").performClick()
        artwork("放大的付款二维码")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("收起").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnIdle { state = state.copy(code = null, error = "网络连接失败") }
        // Publish the new validity through the host and root popup composition owners.
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        repeat(24) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
    }
}
