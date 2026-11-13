package com.mercury.yktwx

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.AmountTextField
import com.mercury.yktwx.ui.pages.*
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
class InputCursorUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.setContent { AppTheme { Scaffold { block() } } }
    }
    private fun field(label: String): SemanticsNodeInteraction {
        // Placeholder labels disappear after the first character; follow the input itself.
        val id = compose.onNode(hasSetTextAction() and hasText(label)).fetchSemanticsNode().id
        return compose.onNode(SemanticsMatcher("input $label") { it.id == id })
    }
    private fun SemanticsNodeInteraction.cursor(offset: Int) {
        assertEquals(TextRange(offset), fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    private fun SemanticsNodeInteraction.typeSequentially(text: String) {
        for ((index, character) in text.withIndex()) {
            performTextInput(character.toString())
            cursor(index + 1)
        }
    }
    private fun editNumericPassword(label: String) {
        val input = field(label)
        input.typeSequentially("1234")
        input.performTextInputSelection(TextRange(2))
        input.performTextInput("a9 ")
        input.cursor(3)
        input.performTextReplacement("1234567")
        input.cursor(6)
    }

    @Test fun loginUsernameAndPasswordKeepCursorDuringTypingAndVisibilityChanges() {
        var state by mutableStateOf(PaymentUiState(needsLogin = true))
        var submitted: Pair<String, String>? = null
        content { LoginPage(state, onInputEdited = { state = state.copy(error = null) },
            onLogin = { username, password -> submitted = username to password }, consent = null) }
        val username = field("学号 / 工号")
        username.typeSequentially("2026")
        username.performTextInputSelection(TextRange(2))
        username.performTextInput(" a3-X ")
        username.assertTextContains("20326")
        username.cursor(3)
        val password = field("密码")
        password.typeSequentially("abCD")
        password.performTextInputSelection(TextRange(2))
        compose.onNodeWithContentDescription("显示密码").performClick()
        password.cursor(2)
        password.performTextInput("$!")
        password.cursor(4)
        compose.onNodeWithContentDescription("隐藏密码").performClick()
        password.cursor(4)
        compose.onNodeWithText("登录").performClick()
        assertEquals("20326" to "ab$!CD", submitted)
    }

    @Test fun allQueryPasswordFieldsKeepCursorAndSubmitFilteredValues() {
        val card = CampusCard("card", "校园卡", "202600000001", false, false, 0)
        var submitted: List<String>? = null
        content { CardPasswordDialog(CardPasswordUiState(visible = true, cards = listOf(card), selectedCard = card),
            {}, {}, { old, new, confirmation -> submitted = listOf(old, new, confirmation) }) }
        for (label in listOf("原查询密码", "新查询密码（6 位数字）", "确认新查询密码")) editNumericPassword(label)
        compose.onNodeWithText("确认修改").performClick()
        assertEquals(listOf("123456", "123456", "123456"), submitted)
        field("原查询密码").cursor(0)
        field("新查询密码（6 位数字）").cursor(0)
        field("确认新查询密码").cursor(0)
    }

    @Test fun unlostPasswordKeepsCursorAndClearsAfterSubmit() {
        val card = CampusCard("card", "校园卡", "202600000001", true, false, 0)
        var submitted: String? = null
        content { CardServiceDialog(CardServiceUiState(visible = true, cards = listOf(card), selectedCard = card),
            {}, {}, { submitted = it }, {}, {}, { _, _, _ -> }, {}, {}) }
        editNumericPassword("校园卡查询密码")
        compose.onNodeWithText("确定").performClick()
        assertEquals("123456", submitted)
        field("校园卡查询密码").cursor(0)
    }

    @Test fun paymentPasswordKeepsCursorAndClearsAfterSubmit() {
        val preparation = FeeAccountPreparation("order", "method", listOf(FeeAccount("1", "校园卡", 10000)), "uuid", "123456")
        var submitted: Pair<String, String>? = null
        content { FeePasswordDialog(true, 100, preparation, false, null, {}, { type, password -> submitted = type to password }) }
        editNumericPassword("六位支付密码")
        compose.onNodeWithText("确定支付").performClick()
        assertEquals("1" to "123456", submitted)
        field("六位支付密码").cursor(0)
    }

    @Test fun recipientCaptchaKeepsCursorDuringTypingTruncationAndRefresh() {
        var refreshes = 0
        content { CardServiceDialog(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge),
            {}, {}, {}, { refreshes++ }, {}, { _, _, _ -> }, {}, {}) }
        val captcha = field("验证码")
        captcha.typeSequentially("aB7K")
        captcha.performTextInputSelection(TextRange(2))
        captcha.performTextInput("X")
        captcha.assertTextContains("aBX7K")
        captcha.cursor(3)
        captcha.performTextReplacement("a".repeat(20))
        captcha.assertTextContains("a".repeat(16))
        captcha.cursor(16)
        compose.onNodeWithText("点击重试").performClick()
        captcha.cursor(0)
        assertEquals(1, refreshes)
    }

    @Test fun amountRejectionPreservesCursorAndExternalPresetsMoveItToTheEnd() {
        var decimal by mutableStateOf("")
        var integer by mutableStateOf("")
        content { Column {
            AmountTextField(decimal, { decimal = it }, "金额")
            AmountTextField(integer, { integer = it }, "整数金额", integerOnly = true, useLabelAsPlaceholder = false)
        } }
        val amount = field("金额")
        amount.typeSequentially("12.34")
        amount.performTextInputSelection(TextRange(2))
        amount.performTextInput("x")
        assertEquals("12.34", decimal)
        amount.cursor(2)
        amount.performTextInput("5")
        assertEquals("125.34", decimal)
        amount.cursor(3)
        compose.runOnIdle { decimal = "50" }
        amount.cursor(2)
        val whole = field("整数金额")
        whole.typeSequentially("123")
        whole.performTextInputSelection(TextRange(1))
        whole.performTextInput(".")
        assertEquals("123", integer)
        whole.cursor(1)
        whole.performTextInput("9")
        assertEquals("1923", integer)
        whole.cursor(2)
    }
}
