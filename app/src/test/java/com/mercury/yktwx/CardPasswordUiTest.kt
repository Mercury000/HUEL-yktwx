package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.CampusCard
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.CardPasswordDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardPasswordUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val card = CampusCard("fake-card", "校园卡", "202600000001", false, false, 0)
    private val state = mutableStateOf(CardPasswordUiState(visible = true, cards = listOf(card), selectedCard = card))
    private var submitted: List<String>? = null
    private fun dialog(mode: ColorSchemeMode = ColorSchemeMode.Light) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode) { Scaffold {
                    CardPasswordDialog(state.value, { state.value = state.value.copy(visible = false) }, {},
                        { old, new, confirmation -> submitted = listOf(old, new, confirmation) })
                } }
            }
        }
    }
    private fun screenshot(name: String) {
        val output = File("../tools/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun lightDialogRendersThreeFieldsWithoutExitWarning() {
        dialog()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(3)
        screenshot("card-password-light")
    }
    @Test fun requiresSixDigitsAndMatchingConfirmationAndClearsAllPasswordsOnSubmit() {
        dialog()
        compose.onNodeWithText("修改成功后将退出登录，请重新登录").assertDoesNotExist()
        compose.onNodeWithText("确认修改").assertIsNotEnabled()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("123456")
        fields[1].performTextInput("654321")
        fields[2].performTextInput("111111")
        compose.onNodeWithText("两次输入的新密码不一致").assertExists()
        compose.onNodeWithText("确认修改").assertIsNotEnabled()
        fields[2].performTextReplacement("654321")
        compose.onNodeWithText("确认修改").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("123456", "654321", "654321"), submitted) }
        for (index in 0..2) fields[index].assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("确认修改").assertIsNotEnabled()
    }
    @Test fun exactServerFailureRemainsVisibleAndPreviewDisablesSubmission() {
        val message = "服务端原始错误消息"
        state.value = state.value.copy(error = message, preview = true)
        dialog(ColorSchemeMode.Dark)
        compose.onNodeWithText(message).assertExists()
        compose.onNodeWithText("请重试").assertDoesNotExist()
        compose.onNodeWithText("预览模式不会修改密码").assertExists()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("123456"); fields[1].performTextInput("654321"); fields[2].performTextInput("654321")
        compose.onNodeWithText("确认修改").assertIsNotEnabled()
        assertNull(submitted)
        screenshot("card-password-dark-error")
    }
    @Test fun failureUsesOneLineAndDoesNotAppendRetryText() {
        val original = "  official error\nsecond line  "
        state.value = state.value.copy(error = original)
        dialog()
        compose.onNodeWithText("official error，second line").assertExists()
        compose.onNodeWithText("请重试").assertDoesNotExist()
        compose.onNodeWithText("修改成功后将退出登录，请重新登录").assertDoesNotExist()
    }
    @Test fun reopeningDoesNotRestorePasswords() {
        dialog()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("123456")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(visible = true) }
        compose.onAllNodes(hasSetTextAction())[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
}
