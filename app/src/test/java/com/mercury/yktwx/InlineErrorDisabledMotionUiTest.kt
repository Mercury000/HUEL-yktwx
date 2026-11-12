package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.InlineErrorGroup
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineErrorDisabledMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })
    @Test fun disabledAnimationsShowAndClearFeedbackWithoutLeavingAnEmptySlot() {
        var error by mutableStateOf<String?>(null)
        compose.setContent {
            AppTheme { InlineErrorGroup(error, 18.dp) { Box(Modifier.size(200.dp, 48.dp).testTag("action")) } }
        }
        val empty = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { error = "密码错误" }
        compose.onNodeWithText("密码错误").assertIsDisplayed()
        compose.runOnIdle { error = null }
        compose.onNodeWithText("密码错误").assertDoesNotExist()
        assertEquals(empty, compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
