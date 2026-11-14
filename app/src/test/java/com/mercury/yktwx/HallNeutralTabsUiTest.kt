package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.FormField
import com.mercury.yktwx.data.RechargeConfiguration
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.DashboardPalette
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HallNeutralTabsUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun frames(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun content(mode: State<ColorSchemeMode> = mutableStateOf(ColorSchemeMode.Light),
        block: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode.value) { Column(Modifier.fillMaxWidth().padding(20.dp)) { block() } }
            }
        }
        frames(30)
    }

    @Test fun actualChoicesKeepNativeSlidingIndicatorAndStableLayoutInBothThemes() {
        val mode = mutableStateOf(ColorSchemeMode.Light)
        val choices = mutableStateOf(listOf("一卡通", "微信"))
        var selected by mutableIntStateOf(1)
        var selectionColor = 0
        content(mode) {
            selectionColor = DashboardPalette.card.toArgb()
            key(choices.value) {
                Box(Modifier.fillMaxWidth().testTag("tabs")) {
                    HallNeutralTabs(choices.value, selected) { selected = it }
                }
            }
        }
        val host = compose.onNodeWithTag("tabs")
        fun leftEdge(): Int {
            val bitmap = host.captureToImage().asAndroidBitmap()
            return (0 until bitmap.width).first { bitmap.getPixel(it, bitmap.height / 5) == selectionColor }
        }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            compose.runOnIdle { mode.value = theme }
            for (labels in listOf(listOf("一卡通", "微信"), listOf("房间管理", "临时缴费"),
                listOf("照明", "空调", "电表"), listOf("电费", "水费"))) {
                compose.runOnIdle { choices.value = labels; selected = 1 }
                frames(30)
                val initialBounds = host.fetchSemanticsNode().boundsInRoot
                val tabs = labels.map { compose.onNode(hasText(it) and hasClickAction()) }
                val identities = tabs.map { it.fetchSemanticsNode().id }
                tabs[1].assertIsSelected()
                assertTrue("Initially selected second tab must paint on the right", leftEdge() > initialBounds.width / labels.size / 2)
                tabs.forEachIndexed { index, tab ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNode(hasText(labels[index]) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                        useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    // Intrinsic width can round up past the integer layout width without hiding ink.
                    assertFalse("$theme ${labels[index]} must not be ellipsized", layout.isLineEllipsized(0))
                    assertEquals(labels[index].length, layout.getLineEnd(0, visibleEnd = true))
                    assertTrue("The last glyph must fit", layout.getBoundingBox(labels[index].lastIndex).right <= layout.size.width + .5f)
                    assertFalse(layout.didOverflowHeight)
                    tab.assertIsEnabled()
                }
                for (target in listOf(0, labels.lastIndex, 1)) {
                    if (target == selected) continue
                    val before = leftEdge()
                    tabs[target].performClick()
                    val positions = (0 until 18).map {
                        frames()
                        assertEquals("Selection must not resize the selector", initialBounds, host.fetchSemanticsNode().boundsInRoot)
                        assertEquals("Selection must keep tabs mounted", identities, tabs.map { it.fetchSemanticsNode().id })
                        leftEdge()
                    }
                    val after = positions.last()
                    assertTrue("Indicator must slide through intermediate positions: $positions",
                        positions.any { it > minOf(before, after) + 1 && it < maxOf(before, after) - 1 })
                    assertEquals(target, selected)
                    tabs[target].assertIsSelected()
                }
            }
        }
    }

    @Test fun lightweightMethodSkeletonMatchesOneAndTwoTabsAcrossFontScales() {
        var scale by mutableFloatStateOf(1f)
        val choices = mutableStateOf(listOf("微信"))
        content {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Box(Modifier.fillMaxWidth().testTag("tabs")) { HallNeutralTabs(choices.value, 0) {} }
                Box(Modifier.fillMaxWidth().testTag("placeholder")) { HallNeutralTabsPlaceholder() }
            }
        }
        for (labels in listOf(listOf("微信"), listOf("一卡通", "微信"))) for (fontScale in listOf(1f, 1.3f, 1.8f)) {
            compose.runOnIdle { choices.value = labels; scale = fontScale }
            frames(24)
            val tabs = compose.onNodeWithTag("tabs").getUnclippedBoundsInRoot()
            val placeholder = compose.onNodeWithTag("placeholder").getUnclippedBoundsInRoot()
            assertEquals(tabs.right - tabs.left, placeholder.right - placeholder.left)
            assertEquals(tabs.bottom - tabs.top, placeholder.bottom - placeholder.top)
            compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("placeholder")), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    @Test fun absentOrStaleSelectionKeepsAllMethodsUnselectedUntilClicked() {
        val choices = mutableStateOf(emptyList<String>())
        var selected by mutableIntStateOf(-1)
        content { Box(Modifier.fillMaxWidth().testTag("tabs")) { HallNeutralTabs(choices.value, selected) { selected = it } } }
        compose.runOnIdle { choices.value = listOf("支付宝", "哈啰余额") }
        frames(24)
        val bounds = compose.onNodeWithTag("tabs").getUnclippedBoundsInRoot()
        compose.onNode(hasText("支付宝") and hasClickAction()).assertIsNotSelected().performClick()
        frames(24)
        compose.onNode(hasText("支付宝") and hasClickAction()).assertIsSelected()
        assertEquals(bounds, compose.onNodeWithTag("tabs").getUnclippedBoundsInRoot())
        compose.runOnIdle { selected = 9 }
        frames(24)
        compose.onNode(hasText("支付宝") and hasClickAction()).assertIsNotSelected()
        compose.onNode(hasText("哈啰余额") and hasClickAction()).assertIsNotSelected().performClick()
        frames(24)
        compose.onNode(hasText("哈啰余额") and hasClickAction()).assertIsSelected()
    }

    @Test fun paymentSkeletonReplacesWithMatchingHeightAndNoHiddenInteractiveMethods() {
        var pending by mutableStateOf(true)
        val config = RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), null, null, true, false)
        content {
            PaymentFormContent(pending) {
                PaymentAmountForm(config, "", {}, listOf("一卡通", "微信"), 0, {}, false, true, null, {})
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        val form = compose.onNodeWithTag("payment-amount-form")
        val before = form.getUnclippedBoundsInRoot()
        compose.runOnIdle { pending = false }
        frames(30)
        val after = form.getUnclippedBoundsInRoot()
        assertEquals("Default form must not jump when skeleton is replaced", before.bottom - before.top, after.bottom - after.top)
        compose.onNode(hasText("一卡通") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("正在读取充值信息").assertDoesNotExist()
    }

    @Test fun methodSwitchKeepsCustomAmountAndInputIdentityIncludingRapidReversal() {
        val config = RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false)
        var amount by mutableStateOf("")
        var selected by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        val edits = mutableListOf<FormField>()
        content {
            Column(Modifier.fillMaxWidth().testTag("form"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PaymentAmountForm(config, amount, { amount = it }, listOf("一卡通", "微信"), selected,
                    { selected = it }, busy, true, null, {}, onInputEdited = { edits += it })
            }
        }
        val input = compose.onNode(hasSetTextAction())
        input.performTextReplacement("37.25")
        frames(30)
        val identity = input.fetchSemanticsNode().id
        val before = compose.onNodeWithTag("form").getUnclippedBoundsInRoot()
        val card = compose.onNode(hasText("一卡通") and hasClickAction())
        val wechat = compose.onNode(hasText("微信") and hasClickAction())
        for (tab in listOf(wechat, card, wechat)) {
            tab.performClick()
            frames(3)
            assertEquals("37.25", amount)
            assertEquals(identity, input.fetchSemanticsNode().id)
            assertEquals(before, compose.onNodeWithTag("form").getUnclippedBoundsInRoot())
        }
        frames(24)
        wechat.assertIsSelected()
        assertEquals(3, edits.count { it == FormField.PaymentMethod })
        wechat.performClick()
        compose.runOnIdle { busy = true }
        card.performClick()
        frames(24)
        assertEquals("A repeated or blocked selection must not count as editing",
            3, edits.count { it == FormField.PaymentMethod })
        assertEquals(1, selected)
        assertEquals("37.25", amount)
    }
}
