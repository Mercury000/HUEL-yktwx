package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BottomSheetInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun bottomSafeAreaReservesNavigationBarAndUsesLargerImeInsteadOfStacking() {
        val bottom = mutableStateOf(24.dp)
        compose.setContent {
            AppTheme {
                val navigation = WindowInsets(bottom = 24.dp)
                val ime = WindowInsets(bottom = bottom.value)
                Box(Modifier.testTag("outer").bottomSheetSafeArea(navigation.union(ime))) {
                    Box(Modifier.size(100.dp).testTag("content"))
                }
            }
        }
        var outer = compose.onNodeWithTag("outer").fetchSemanticsNode().boundsInRoot
        var content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(124f / 100f, outer.height / content.height, 0.01f)
        compose.runOnIdle { bottom.value = 80.dp }
        outer = compose.onNodeWithTag("outer").fetchSemanticsNode().boundsInRoot
        content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(180f / 100f, outer.height / content.height, 0.01f)
    }
    @Test fun consumedNavigationInsetsAreNotAddedTwiceAndHorizontalInsetsAreRespected() {
        compose.setContent {
            AppTheme {
                val safe = WindowInsets(left = 12.dp, bottom = 24.dp, right = 16.dp)
                Box(Modifier.testTag("outer").bottomSheetSafeArea(safe)) {
                    Box(Modifier.bottomSheetSafeArea(safe)) {
                        Box(Modifier.size(100.dp).testTag("content"))
                    }
                }
            }
        }
        val outer = compose.onNodeWithTag("outer").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        assertEquals(124f / 100f, outer.height / content.height, 0.01f)
        assertEquals(128f / 100f, outer.width / content.width, 0.01f)
    }
}
