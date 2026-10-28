package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.CampusCard
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.CardPasswordDialog
import com.mercury.yktwx.ui.pages.LoginPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InputFocusDismissalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val username get() = compose.onNode(hasText("学号 / 工号") and hasSetTextAction())
    private val password get() = compose.onNode(hasText("密码") and hasSetTextAction())

    private fun content() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { LoginPage(PaymentUiState(), { _, _ -> }) }
            }
        }
    }

    private fun fill() {
        username.performTextInput("202400123456")
        password.performTextInput("123456")
    }

    @Test fun outsideTapClearsFocusAndInsideTapKeepsEditingAndIconWorking() {
        content(); fill()
        password.assertIsFocused()
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        password.assertIsFocused()
        username.performClick().assertIsFocused()
        password.performClick().assertIsFocused()
        compose.onNodeWithText("河南财经政法大学").performTouchInput { click() }
        password.assertIsNotFocused()
        password.assertTextContains("123456")
        password.performClick().assertIsFocused()
    }

    @Test fun dialogInputsAlsoDismissFocusOutsideAndKeepFieldSwitching() {
        val card = CampusCard("test-card", "校园卡", "202400123456", false, false, 0)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    top.yukonga.miuix.kmp.basic.Scaffold {
                        CardPasswordDialog(CardPasswordUiState(visible = true, selectedCard = card, cards = listOf(card)),
                            {}, {}, { _, _, _ -> })
                    }
                }
            }
        }
        val old = compose.onNode(hasText("原查询密码") and hasSetTextAction())
        val new = compose.onNode(hasText("新查询密码（6 位数字）") and hasSetTextAction())
        old.performTextInput("123456")
        old.assertIsFocused()
        new.performTextInput("654321")
        new.assertIsFocused()
        old.assertIsNotFocused()
        compose.onNodeWithText("修改查询密码").performTouchInput { click() }
        new.assertIsNotFocused()
        old.performClick().assertIsFocused()
        old.assertTextContains("123456")
        new.assertTextContains("654321")
    }
}
