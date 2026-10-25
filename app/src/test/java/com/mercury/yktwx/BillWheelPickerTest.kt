package com.mercury.yktwx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.BillWheelPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class BillWheelPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun futureValuesAreVisibleButCannotBecomeSelected() {
        var value by mutableIntStateOf(25)
        compose.setContent {
            AppTheme {
                BillWheelPicker(
                    value = value,
                    onValueChange = { value = it },
                    selectableRange = 1..25,
                    displayRange = 1..31,
                    label = { "%02d".format(it) },
                )
            }
        }

        compose.onNodeWithText("26").assertExists()
        compose.onNodeWithContentDescription("25, 1 - 25")
            .performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(25, value) }
        compose.onNodeWithContentDescription("25, 1 - 25").assertExists()

        compose.onNodeWithContentDescription("25, 1 - 25")
            .performTouchInput {
                swipe(center, center + Offset(0f, 220f), durationMillis = 180)
            }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(value in 1..24) }
    }

    @Test fun scrollingFromEarlierValueStopsAtMaximum() {
        var value by mutableIntStateOf(21)
        compose.setContent {
            AppTheme {
                BillWheelPicker(
                    value = value,
                    onValueChange = { value = it },
                    selectableRange = 1..25,
                    displayRange = 1..31,
                    label = { "%02d".format(it) },
                )
            }
        }

        compose.onNodeWithContentDescription("21, 1 - 25")
            .performTouchInput {
                swipe(
                    start = center + Offset(0f, 200f),
                    end = center + Offset(0f, -200f),
                    durationMillis = 160,
                )
            }
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(25, value) }
        compose.onNodeWithContentDescription("25, 1 - 25").assertExists()
    }
}
