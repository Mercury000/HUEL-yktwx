package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.UpdatedText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NumberFittingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun everyDecimalDigitFitsActualTextAndParentBoundsAcrossWidthsAndFontScales() {
        val number = mutableStateOf("-30138.02")
        val width = mutableStateOf(136.dp)
        val density = mutableStateOf(Density(2.625f))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides density.value) {
                AppTheme {
                    Row(Modifier.width(width.value).height(IntrinsicSize.Max).testTag("number-row")) {
                        UpdatedText(number.value, Modifier.weight(1f, fill = false).alignByBaseline(),
                            fontSize = 29.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
                        Text("度", Modifier.padding(start = 3.dp).alignByBaseline(), fontSize = 11.sp)
                    }
                }
            }
        }
        for (scale in listOf(.85f, 1f, 1.3f)) for (size in listOf(136, 120, 103, 87)) {
            compose.runOnIdle { density.value = Density(2.625f, scale); width.value = size.dp }
            for (value in listOf("-30138.02", "-30136.69", "123456789.1234", "86.4")) {
                compose.runOnIdle { number.value = value }
                repeat(24) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
                val node = compose.onNode(hasText(value) and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                    .fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                val layout = layouts.single()
                val context = "$value at ${size}dp, font scale $scale"
                assertEquals(context, 1, layout.lineCount)
                assertEquals("Last digit must be laid out: $context", value.length, layout.getLineEnd(0))
                assertFalse("Text bounds must contain every digit: $context", layout.hasVisualOverflow)
                assertTrue("Last glyph must fit text bounds: $context",
                    layout.getBoundingBox(value.lastIndex).right <= layout.size.width + .5f)
                val row = compose.onNodeWithTag("number-row").fetchSemanticsNode().boundsInRoot
                val unit = compose.onNodeWithText("度", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("Parent must contain the full number: $context", node.boundsInRoot.right <= row.right + .5f)
                assertTrue("Unit must follow the complete number: $context", node.boundsInRoot.right <= unit.left + .5f)
            }
        }
    }
}
