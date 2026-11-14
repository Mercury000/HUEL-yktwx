package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.IntSize
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
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun awaitMainNavigation() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("main-navigation-rail").fetchSemanticsNodes().size == 1
        }
    }

    private fun openSettings() {
        compose.onNodeWithTag("my-page-list").performScrollToNode(hasText("应用设置"))
        compose.onNodeWithText("应用设置").performClick()
    }

    @Test fun collapsedRailStaysContinuousThroughPredictiveCancelAndCommit() = verifyPredictiveReturn()

    @Test fun aospCardReturnKeepsTheRailFixedThroughCancelAndCommit() = verifyPredictiveReturn(style = 1)

    @Test
    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
    fun expandedRailStaysContinuousThroughPredictiveCancelAndCommit() = verifyPredictiveReturn()

    @Test fun railSelectionReturnsFromNestedPagesBeforeSwitchingTabs() = verifyRailSelection()

    @Test fun aospRailSelectionReturnsFromNestedPagesBeforeSwitchingTabs() = verifyRailSelection(style = 1)

    private fun verifyRailSelection(style: Int = 0) {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = themeSettingsFixture(compose.activity).apply { setNavTransitionStyle(style) }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        awaitMainNavigation()
        val rail = compose.onNodeWithTag("main-navigation-rail")
        val originalId = rail.fetchSemanticsNode().id
        for (label in listOf("我的", "付款")) {
            compose.onNode(hasText("我的") and isSelectable()).performClick()
            openSettings()
            compose.onNodeWithText("主题").performClick()
            compose.onNodeWithText(compose.activity.getString(R.string.swipe_back)).assertExists()
            compose.onNode(hasText(label) and isSelectable()).performClick()
            compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
            compose.onNodeWithText(compose.activity.getString(R.string.swipe_back)).assertDoesNotExist()
            compose.onNodeWithTag("settings-page-list").assertDoesNotExist()
            assertEquals("Selecting a tab must retain the rail", originalId, rail.fetchSemanticsNode().id)
            if (label == "我的") {
                compose.onNodeWithText("应用设置").assertIsDisplayed()
            } else {
                compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
                compose.runOnIdle {
                    assertFalse("Rail selection must remove the entire secondary stack",
                        compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
                }
            }
        }
    }

    @Test fun resizingWithASecondaryPageKeepsItsScrollAndComposition() {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = themeSettingsFixture(compose.activity)
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        var windowSize by mutableStateOf<IntSize?>(null)
        var density = 1f
        compose.setContent {
            val actualWindow = LocalWindowInfo.current
            density = LocalDensity.current.density
            val window = remember(actualWindow) {
                object : WindowInfo by actualWindow {
                    override val containerSize get() = windowSize ?: actualWindow.containerSize
                }
            }
            CompositionLocalProvider(LocalInspectionMode provides true, LocalWindowInfo provides window) {
                YktApp(settings, payments, bills)
            }
        }
        awaitMainNavigation()
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        openSettings()
        val page = compose.onNodeWithTag("settings-page-list")
        page.performScrollToIndex(2)
        val originalId = page.fetchSemanticsNode().id
        val originalScroll = page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The fixture must have scrolled before resizing", originalScroll > 0f)
        for (width in listOf(500, 1280, 900, 700)) {
            compose.runOnIdle { windowSize = IntSize((width * density).toInt(), (500 * density).toInt()) }
            if (width >= 600) compose.onNodeWithTag("main-navigation-rail").assertIsDisplayed()
            else compose.onNodeWithTag("main-navigation-rail").assertDoesNotExist()
            assertEquals("Resizing must retain the secondary page", originalId, page.fetchSemanticsNode().id)
            assertEquals("Resizing must retain the scroll position", originalScroll,
                page.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        compose.onNodeWithText("应用设置").assertIsDisplayed()
    }

    private fun verifyPredictiveReturn(style: Int = 0) {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = themeSettingsFixture(compose.activity).apply { setNavTransitionStyle(style) }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        awaitMainNavigation()
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
            assertEquals("The demo rail stays outside the transition pane", initialTop.x, top.x, 1f)
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
        assertEquals("Opening a page leaves the demo rail fixed", initialTop.x, coveredLeft, 1f)
        startBack()
        progress(.35f)
        val earlierLeft = railCorners().first.x
        progress(.8f)
        assertEquals("Predictive back leaves the demo rail fixed", earlierLeft, railCorners().first.x, 1f)
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
