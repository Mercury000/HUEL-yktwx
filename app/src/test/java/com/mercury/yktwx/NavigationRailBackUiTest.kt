package com.mercury.yktwx

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w700dp-h500dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationRailBackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun collapsedRailStaysContinuousThroughPredictiveCancelAndCommit() = verifyPredictiveReturn()

    @Test
    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
    fun expandedRailStaysContinuousThroughPredictiveCancelAndCommit() = verifyPredictiveReturn()

    private fun verifyPredictiveReturn() {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(compose.activity)
        val payments = PaymentViewModel(compose.activity.application).apply { initialize(true) }
        val bills = BillViewModel(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        val rail = compose.onNodeWithTag("main-navigation-rail")
        val originalId = rail.fetchSemanticsNode().id
        // Unclipped coordinates reveal the whole rail even while it moves off the left edge.
        fun railCorners(): Pair<Offset, Offset> {
            val node = rail.fetchSemanticsNode()
            assertEquals("The rail must stay composed during navigation", originalId, node.id)
            val coordinates = node.layoutInfo.coordinates
            return coordinates.localToRoot(Offset.Zero) to coordinates.localToRoot(
                Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()),
            )
        }
        val (initialTop, initialBottom) = railCorners()
        fun assertFullHeightRail() {
            val (top, bottom) = railCorners()
            assertEquals("Wide-screen navigation must not shrink or vertically shift the rail",
                initialTop.y, top.y, 1f)
            assertEquals("The rail must retain its full height", initialBottom.y, bottom.y, 1f)
        }
        fun frames(count: Int) {
            repeat(count) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertFullHeightRail()
            }
        }
        fun settle() {
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertFullHeightRail()
        }
        fun startBack() {
            compose.runOnIdle {
                compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 250f, 0f, BackEventCompat.EDGE_LEFT),
                )
            }
        }
        fun progress(value: Float) {
            compose.runOnIdle {
                compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                    BackEventCompat(100f, 250f, value, BackEventCompat.EDGE_LEFT),
                )
            }
            frames(2)
        }
        fun assertVisibleRailTab() {
            val bitmap = compose.onNode(hasText("我的") and isSelectable()).captureToImage().asAndroidBitmap()
            var ink = 0
            // The left half cannot be confused with the neighboring pager's captured pixels.
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width / 2) {
                val pixel = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)
                if (maxOf(r, g, b) < 160 || maxOf(r, g, b) - minOf(r, g, b) > 70) ink++
            }
            assertTrue("The visible rail must retain rendered icon/label pixels under the return scrim", ink > 20)
        }

        val myListId = compose.onNode(hasScrollToIndexAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            hasAnyDescendant(hasTestTag("my-balance-card")))
            .fetchSemanticsNode().id
        // Identify the list itself: its balance card leaves composition as this short screen scrolls.
        compose.onNode(SemanticsMatcher("My page list") { it.id == myListId })
            .performScrollToNode(hasText("应用设置"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        frames(8)
        settle()
        val coveredLeft = railCorners().first.x
        assertTrue("Rail and page must move left together behind the secondary page", coveredLeft < initialTop.x)
        startBack()
        progress(.35f)
        val earlierLeft = railCorners().first.x
        progress(.8f)
        assertTrue("Returning must continuously reveal the rail", railCorners().first.x > earlierLeft)
        progress(.55f)
        // Slow movement and the almost-complete boundary must keep the live rail visible.
        for (value in (75..95).map { it / 100f } + listOf(.975f, .99f, .995f, .999f)) {
            progress(value)
            assertVisibleRailTab()
        }
        val heldPixels = rail.captureToImage().asAndroidBitmap()
        repeat(4) {
            frames(2)
            assertTrue("A held return gesture must not flicker the visible rail",
                heldPixels.sameAs(rail.captureToImage().asAndroidBitmap()))
        }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        frames(8)
        settle()
        assertEquals("Cancelling restores the covered position", coveredLeft, railCorners().first.x, 1f)
        compose.onNodeWithText("主题").assertIsDisplayed()
        startBack()
        progress(.9f)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        frames(20)
        settle()
        assertEquals("Completing restores the original rail position", initialTop.x, railCorners().first.x, 1f)
        compose.onNodeWithText("主题").assertDoesNotExist()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        compose.mainClock.autoAdvance = true
    }
}
