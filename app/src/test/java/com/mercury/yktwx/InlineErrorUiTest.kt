package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineErrorUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private fun fixture(message: State<String?>, managed: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                val body: @Composable () -> Unit = {
                    Column(Modifier.width(320.dp).background(Color.White).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Box(Modifier.fillMaxWidth().height(32.dp).background(Color.Gray).testTag("input"))
                        InlineErrorGroup(message.value, 18.dp) {
                            Box(Modifier.fillMaxWidth().height(48.dp).background(Color.Blue).testTag("action"))
                        }
                    }
                }
                if (managed) ReadLayout { body() } else body()
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }
    private fun actionTop() = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot.top
    private fun advance(ms: Long) {
        // Visibility expansion obtains its target height during Android's measure pass.
        // Give each animation frame a real measure/draw pass, including interrupted exits.
        repeat(((ms + 15) / 16).toInt()) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
    private fun capture(name: String) {
        val file = File("../tools/inline-error-review/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun assertMotion(managed: Boolean) {
        val error = mutableStateOf<String?>(null)
        fixture(error, managed)
        val empty = actionTop()
        capture(if (managed) "managed-hidden" else "hidden")
        compose.runOnIdle { error.value = "密码错误，请重新输入" }
        advance(80)
        val entering = actionTop()
        capture(if (managed) "managed-entering" else "entering")
        advance(400)
        val shown = actionTop()
        assertTrue("The action should move during entry: $empty < $entering < $shown", entering > empty && entering < shown)
        capture(if (managed) "managed-visible" else "visible")
        compose.runOnIdle { error.value = null }
        advance(80)
        val exiting = actionTop()
        assertTrue("The action should move during exit", exiting > empty && exiting < shown)
        advance(400)
        assertEquals("A hidden notice must leave no extra parent spacing", empty, actionTop(), 1f)
        compose.onNodeWithText("密码错误，请重新输入").assertDoesNotExist()
    }
    @Test fun noticeAndSpacingMoveTogether() = assertMotion(false)
    @Test fun parentReadLayoutDoesNotAddAnotherHeightAnimation() = assertMotion(true)
    @Test fun restoredOrExistingErrorsDoNotReplayAndTextReplacementKeepsHeight() {
        val error = mutableStateOf<String?>("旧错误")
        fixture(error)
        val top = actionTop()
        compose.runOnIdle { error.value = "新的错误提示" }
        advance(64)
        assertEquals(top, actionTop(), 1f)
        advance(400)
        compose.onNodeWithText("旧错误").assertDoesNotExist()
        compose.onNodeWithText("新的错误提示").assertExists()
        assertEquals(top, actionTop(), 1f)
    }
    @Test fun interruptedDisappearanceRetainsTheNewestErrorAndEventuallyRemovesSpacing() {
        val error = mutableStateOf<String?>("旧错误")
        fixture(error, managed = true)
        compose.runOnIdle { error.value = null }
        advance(64)
        compose.runOnIdle { error.value = "新的错误" }
        advance(400)
        compose.onNodeWithText("新的错误").assertExists()
        compose.onNodeWithText("旧错误").assertDoesNotExist()
        compose.runOnIdle { error.value = null }
        advance(500)
        val empty = actionTop()
        advance(500)
        assertEquals(empty, actionTop(), 1f)
    }
    @Test fun cacheRestorationShowsTheErrorAtItsFinalHeightImmediately() {
        var error by mutableStateOf<String?>(null)
        var restored by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalReadSnapshotRestored provides restored) {
                    InlineErrorGroup(error, 18.dp) { Box(Modifier.size(100.dp, 48.dp).testTag("action")) }
                }
            }
        }
        advance(48)
        val empty = actionTop()
        compose.runOnIdle { error = "缓存中的错误"; restored = true }
        advance(48)
        val shown = actionTop()
        assertTrue(shown > empty)
        advance(64)
        assertEquals("Restoration must not run a visibility animation", shown, actionTop(), 1f)
    }
    @Test fun loginOnlyReportsEffectiveUserEditsAndNeverClearsOnFocusOrVisibilityToggle() {
        val edited = mutableListOf<FormField>()
        compose.setContent {
            AppTheme { LoginPage(PaymentUiState(error = "学号或密码无效"), null, edited::add) { _, _ -> } }
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performClick()
        assertTrue(edited.isEmpty())
        fields[0].performTextInput("abc")
        assertTrue(edited.isEmpty())
        fields[0].performTextInput("123")
        assertEquals(listOf(FormField.Username), edited)
        fields[1].performTextInput("secret")
        assertEquals(listOf(FormField.Username, FormField.Password), edited)
        compose.onNodeWithContentDescription("显示密码").performClick()
        assertEquals(2, edited.size)
    }
    @Test fun recipientCaptchaEditsDoNotResetLookupOrRefreshTheImage() {
        val edited = mutableListOf<FormField>()
        var queriesReset = 0
        var captchaRefreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { top.yukonga.miuix.kmp.basic.Scaffold {
                    CardServiceDialog(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge,
                        captcha = RecipientCaptcha("test-key", byteArrayOf()), error = "验证码有误"),
                        {}, {}, {}, { captchaRefreshes++ }, { queriesReset++ }, { _, _, _ -> }, {}, {},
                        onInputEdited = edited::add)
                } }
            }
        }
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("new")
        assertEquals(listOf(FormField.Captcha), edited)
        assertEquals(0, queriesReset)
        assertEquals(0, captchaRefreshes)
    }
    @Test fun amountValidationRechecksOnBlurAndEffectiveChangesInvalidateOldAmountRejection() {
        var amount by mutableStateOf("5")
        var serverError by mutableStateOf<String?>("上次金额被拒绝")
        val edited = mutableListOf<FormField>()
        val config = RechargeConfiguration("fee", "target", listOf(1000), 1000, 5000, true, false)
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, false, true,
                        serverError, {}, onInputEdited = { edited += it; if (it == FormField.Amount) serverError = null })
                    top.yukonga.miuix.kmp.basic.TextField(value = "", onValueChange = {}, label = "其他字段")
                }
            }
        }
        val fields = compose.onAllNodes(hasSetTextAction())
        fun assertErrorBelowInput(message: String) {
            val input = fields[0].getUnclippedBoundsInRoot()
            val feedback = compose.onNodeWithText(message).getUnclippedBoundsInRoot()
            val next = compose.onNodeWithText("支付方式").getUnclippedBoundsInRoot()
            assertTrue("The error follows the last input", input.bottom <= feedback.top)
            assertTrue("The following controls stay below the error", feedback.bottom <= next.top)
            compose.onAllNodesWithText(message).assertCountEquals(1)
        }
        fields[0].performClick()
        compose.onNodeWithText("上次金额被拒绝").assertExists()
        assertErrorBelowInput("上次金额被拒绝")
        capture("amount-server-error")
        assertTrue(edited.isEmpty())
        fields[0].performTextReplacement("6")
        compose.onNodeWithText("上次金额被拒绝").assertDoesNotExist()
        fields[1].performClick()
        compose.onNodeWithText("最低充值 ¥ 10").assertExists()
        assertErrorBelowInput("最低充值 ¥ 10")
        capture("amount-validation-error")
        compose.onNodeWithText("充值").assertIsNotEnabled()
        fields[0].performTextReplacement("20")
        fields[1].performClick()
        compose.onNodeWithText("最低充值 ¥ 10").assertDoesNotExist()
        compose.onNodeWithText("上次金额被拒绝").assertDoesNotExist()
        compose.onNodeWithText("充值 ¥ 20.00").assertIsEnabled()
        capture("amount-corrected")
    }

    @Test fun clearingFormErrorRestoresTheOriginalInputToFollowingControlsSpacing() {
        var error by mutableStateOf<String?>(null)
        val config = RechargeConfiguration("fee", "target", emptyList(), 1000, 5000, true, false)
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PaymentAmountForm(config, "20", {}, listOf("微信"), 0, {}, false, true, error, {})
                }
            }
        }
        fun gap(): Float {
            val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
            val following = compose.onNodeWithText("支付方式").getUnclippedBoundsInRoot()
            return (following.top - field.bottom).value
        }
        val originalGap = gap()
        compose.runOnIdle { error = "服务暂不可用" }
        assertTrue(gap() > originalGap)
        compose.runOnIdle { error = null }
        compose.onNodeWithText("服务暂不可用").assertDoesNotExist()
        assertEquals("Hidden feedback leaves no extra gap", originalGap, gap(), .01f)
    }
}
