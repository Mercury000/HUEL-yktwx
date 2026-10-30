package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.FeeQuery
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.HallPage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkPackageMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ready = PreviewData.hallState()
    private val twoLines = "示例套餐：校园网月套餐，教学区与宿舍区通用，包含基础上网服务"
    private val threeLines = "示例套餐：校园网学期套餐，支持教学区与宿舍区使用；套餐说明较长时完整展示，具体资费和适用范围以官方信息为准"

    private fun state(name: String?, loading: Boolean = false, restored: Boolean = false) = DormUiState(
        rooms = ready.rooms, loaded = true, loading = loading,
        network = name?.let { FeeQuery(display = listOf("用户套餐" to it)) },
        networkLoaded = name != null, displayRestored = restored,
    )

    private fun content(current: State<DormUiState>, settleMillis: Long = 800) {
        val settings = ThemeSettings(compose.activity.application)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { HallPage(settings, 0.dp, dorm = current.value) }
            }
        }
        frames(3)
        compose.mainClock.advanceTimeBy(settleMillis)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("network-package-name", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun frames(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun capture(name: String) {
        val file = File("../tools/network-package-motion/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun checkExpansion(name: String, expectedLines: Int) {
        val current = mutableStateOf(state(null))
        content(current)
        val before = bounds("hall-network-card")
        val skeleton = bounds("network-package-placeholder")
        val followingTop = compose.onNodeWithText("代人充值").fetchSemanticsNode().boundsInRoot.top
        capture("${expectedLines}-lines-before")
        compose.runOnIdle { current.value = state(name) }
        val heights = mutableListOf<Float>()
        val widths = mutableListOf<Float>()
        repeat(16) { frame ->
            frames()
            heights += bounds("hall-network-card").height
            if (compose.onAllNodesWithTag("network-package-name", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                widths += textLayout().size.width.toFloat()
                assertEquals("Text must keep its final line breaks during expansion", expectedLines, textLayout().lineCount)
            }
            if (frame == 0) {
                assertEquals("The first frame must not jump to the expanded height", before.height, heights.last(), 1f)
                assertEquals("The outgoing skeleton must stay one line high", skeleton.height,
                    bounds("network-package-placeholder").height, 1f)
            }
            capture("${expectedLines}-lines-frame-$frame")
        }
        frames(12)
        val after = bounds("hall-network-card")
        assertTrue("This fixture must exercise a height change", after.height > before.height + 1f)
        assertTrue("Card height must pass through intermediate sizes: $heights",
            heights.any { it > before.height + 1f && it < after.height - 1f })
        assertTrue("Card height must not bounce", heights.zipWithNext().all { (a, b) -> b >= a - 1f })
        assertTrue("Wrapping width must remain stable", widths.all { it == widths.first() })
        assertEquals("Following content must move with the card instead of jumping separately", after.height - before.height,
            compose.onNodeWithText("代人充值").fetchSemanticsNode().boundsInRoot.top - followingTop, 1f)
        assertFalse("The full name must remain readable", textLayout().hasVisualOverflow)
        compose.onNodeWithTag("network-package-placeholder", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("network-recharge").assertIsDisplayed().assertIsEnabled()
        capture("${expectedLines}-lines-after")
        File("../tools/network-package-motion/${expectedLines}-lines-bounds.txt").writeText(
            "${before.left},${before.top},${after.right},${after.bottom}\nheights=$heights")
    }

    @Test fun oneLineSkeletonExpandsContinuouslyIntoTwoLines() = checkExpansion(twoLines, 2)

    @Test fun oneLineSkeletonExpandsContinuouslyIntoThreeLines() = checkExpansion(threeLines, 3)

    @Test fun fastLoadDoesNotStartPaintingAnOutgoingSkeleton() {
        val current = mutableStateOf(state(null))
        content(current, settleMillis = 160)
        val placeholder = bounds("network-package-placeholder")
        // Beyond the short name but inside the old skeleton's bar.
        val x = (placeholder.left + placeholder.width * .5f).toInt()
        val y = placeholder.center.y.toInt()
        fun pixel() = compose.onRoot().captureToImage().asAndroidBitmap().getPixel(x, y)
        val blank = pixel()
        assertEquals(android.graphics.Color.WHITE, blank)
        compose.runOnIdle { current.value = state("示例套餐") }
        repeat(20) {
            frames()
            assertEquals("A late skeleton must not flash after content has arrived", blank, pixel())
        }
        compose.onNodeWithTag("network-package-placeholder", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(1, textLayout().lineCount)
    }

    @Test fun cachedNameDoesNotReturnToSkeletonDuringRefresh() {
        val current = mutableStateOf(state(threeLines, restored = true))
        content(current)
        val baseline = compose.onNodeWithTag("hall-network-card").captureToImage().asAndroidBitmap()
        compose.runOnIdle { current.value = state(threeLines, loading = true, restored = true) }
        repeat(16) {
            frames()
            compose.onNodeWithTag("network-package-placeholder", useUnmergedTree = true).assertDoesNotExist()
            assertTrue("An unchanged cached name must not replay its entry",
                baseline.sameAs(compose.onNodeWithTag("hall-network-card").captureToImage().asAndroidBitmap()))
        }
        compose.runOnIdle { current.value = state(threeLines, restored = true) }
        frames(20)
        assertTrue(baseline.sameAs(compose.onNodeWithTag("hall-network-card").captureToImage().asAndroidBitmap()))
    }

    @Test fun restoringCacheSnapsToTheNameAndInterruptedUpdatesKeepLatestText() {
        val current = mutableStateOf(state(null))
        content(current)
        compose.runOnIdle { current.value = state(threeLines, restored = true) }
        frames(5)
        val cachedHeight = bounds("hall-network-card").height
        assertEquals(3, textLayout().lineCount)
        compose.onNodeWithTag("network-package-placeholder", useUnmergedTree = true).assertDoesNotExist()
        frames(20)
        assertEquals("Disk restoration must not animate from an empty skeleton", cachedHeight,
            bounds("hall-network-card").height, 1f)
        compose.runOnIdle { current.value = state(twoLines, restored = true) }
        frames(4)
        compose.runOnIdle { current.value = state("示例套餐", restored = true) }
        frames(30)
        compose.onNodeWithText(twoLines).assertDoesNotExist()
        compose.onNodeWithText(threeLines).assertDoesNotExist()
        assertEquals("示例套餐", textLayout().layoutInput.text.text)
        assertEquals(1, textLayout().lineCount)
        assertFalse(textLayout().hasVisualOverflow)
    }
}
