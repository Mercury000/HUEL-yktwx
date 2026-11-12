package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.BillPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillHeaderScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun capture(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File("../tools/bill-content-motion/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun settledModeAndTypeSwitchesAlwaysReleaseVerticalScrolling() {
        var state by mutableStateOf(PreviewData.billState())
        var generation by mutableIntStateOf(0)
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    key(generation) {
                        BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {},
                            onOpenStats = { state = state.copy(statsVisible = true) }, onOpenDetail = {},
                            onShowDetails = { state = state.copy(statsVisible = false) },
                            onStatsTypeChange = { state = state.copy(stats = PreviewData.billStats(state.dateFilter, it)) },
                            showBack = false)
                    }
                }
            }
        }
        fun click(label: String) = compose.onNode(hasText(label) and hasClickAction()).performClick()
        for ((index, sequence) in listOf(listOf("统计"), listOf("收入"), listOf("明细"),
            listOf("统计", "明细", "统计"), listOf("收入", "支出", "收入"),
            listOf("收入", "明细", "统计", "支出")).withIndex()) {
            compose.runOnIdle {
                state = PreviewData.billState().copy(statsVisible = index != 0 && index != 3)
                generation++
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            for (label in sequence) {
                click(label)
                repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = true
            val surface = compose.onNodeWithTag("bill-scroll-surface")
            surface.performTouchInput { swipeUp() }
            compose.waitForIdle()
            val forward = surface.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Scrolling must resume after $sequence without another state update", forward > 0f)
            surface.performTouchInput {
                swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
            }
            compose.waitForIdle()
            assertTrue("Reverse scrolling must resume after $sequence",
                surface.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() < forward)
        }
    }

    @Test fun loadingFailureRetryAndReactivationNeverLeaveScrollingLocked() {
        var state by mutableStateOf(PreviewData.billState())
        var active by mutableStateOf(true)
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {},
                        onOpenStats = { state = state.copy(statsVisible = true,
                            stats = BillStatsState(period = state.dateFilter, type = 2, loading = true)) },
                        onOpenDetail = {}, onShowDetails = { state = state.copy(statsVisible = false) },
                        onStatsTypeChange = { state = state.copy(stats = BillStatsState(
                            period = state.dateFilter, type = it, loading = true)) },
                        isActive = active, showBack = false)
                }
            }
        }
        fun assertScrollable(stage: String) {
            val surface = compose.onNodeWithTag("bill-scroll-surface")
            val range = { surface.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() }
            val before = range()
            surface.performTouchInput {
                swipe(start = center.copy(y = height * .7f), end = center.copy(y = height * .4f), durationMillis = 500)
            }
            compose.waitForIdle()
            assertTrue("$stage must allow an upward drag", range() > before)
            surface.performTouchInput {
                swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
            }
            compose.waitForIdle()
            assertEquals("$stage must allow returning to the top", before, range(), 2f)
        }
        compose.onNode(hasText("统计") and hasClickAction()).performClick()
        compose.waitForIdle()
        assertScrollable("A slow first read after the mode transition")
        compose.runOnIdle { state = state.copy(stats = state.stats.copy(loading = false, error = "读取失败")) }
        compose.waitForIdle()
        assertScrollable("First-read failure")
        compose.runOnIdle { state = state.copy(stats = BillStatsState(period = state.dateFilter, type = 2, loading = true)) }
        compose.waitForIdle()
        assertScrollable("Retry while the failure stays visible")
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 2).copy(displayRestored = true)) }
        compose.waitForIdle()
        assertScrollable("Cache restoration after retry")
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("收入") and hasClickAction()).performClick()
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { active = false }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { active = true }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertScrollable("Reactivation after interrupting a transition")
    }

    @Test fun pullingAndReversingKeepBothControlLayersAndCardsOnOneSurface() {
        var statistics by mutableStateOf(false)
        val settings = themeSettingsFixture(compose.activity.application).apply { setBlurTop(false) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    key(statistics) {
                        BillPage(PreviewData.billState().copy(statsVisible = statistics), settings,
                            {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                    }
                }
            }
        }
        for (stats in listOf(false, true)) {
            compose.runOnIdle { statistics = stats }
            val controls = compose.onNodeWithTag("bill-mode-tabs")
            val date = compose.onNodeWithTag("bill-date-selector")
            val card = compose.onNodeWithTag(if (stats) "bill-stats-overview" else "bill-summary")
            val surface = compose.onNodeWithTag("bill-scroll-surface")
            val controlsTop = controls.fetchSemanticsNode().boundsInRoot.top
            val dateGap = date.fetchSemanticsNode().boundsInRoot.top - controlsTop
            val cardGap = card.fetchSemanticsNode().boundsInRoot.top - controlsTop
            val typeGap = if (stats) compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode()
                .boundsInRoot.top - controlsTop else 0f
            fun assertOneSurface() {
                val top = controls.fetchSemanticsNode().boundsInRoot.top
                assertEquals("The date must move with the mode selector during pull and reversal",
                    dateGap, date.fetchSemanticsNode().boundsInRoot.top - top, 1f)
                assertEquals("Cards must not rebound separately below the control layers",
                    cardGap, card.fetchSemanticsNode().boundsInRoot.top - top, 1f)
                if (stats) assertEquals("The child selector must share the page's rebound",
                    typeGap, compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot.top - top, 1f)
            }
            surface.performTouchInput { down(center); moveBy(Offset(0f, 120f), delayMillis = 32) }
            compose.waitForIdle()
            assertTrue("A pull must move the controls as well as the cards",
                controls.fetchSemanticsNode().boundsInRoot.top > controlsTop + 1f)
            assertOneSurface()
            capture(if (stats) "statistics-unified-pull" else "details-unified-pull")
            surface.performTouchInput { moveBy(Offset(0f, -60f), delayMillis = 32) }
            compose.waitForIdle()
            assertOneSurface()
            surface.performTouchInput { moveBy(Offset(0f, -100f), delayMillis = 64) }
            compose.waitForIdle()
            assertOneSurface()
            compose.mainClock.autoAdvance = false
            surface.performTouchInput { up() }
            repeat(25) {
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                assertOneSurface()
            }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun flingFromTheChildSelectorContinuesThroughBothHeadersIntoTheBody() {
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(PreviewData.billState().copy(statsVisible = true), settings,
                        {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        val type = compose.onNodeWithTag("bill-type-tabs")
        val list = compose.onNodeWithTag("bill-stats-list")
        compose.mainClock.autoAdvance = false
        // The drag itself does not cover the two headers; its momentum must reach the body.
        type.performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 320f), durationMillis = 80)
        }
        var bodyMoved = false
        repeat(35) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f) bodyMoved = true
        }
        assertTrue("One fling must continue from the selector into the list without a second swipe", bodyMoved)
        assertEquals(0f, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(0f, type.fetchSemanticsNode().boundsInRoot.height, 1f)
        compose.mainClock.autoAdvance = true
    }

    @Test fun unifiedBodyScrollingStillDismissesSearchAndRequestsTheNextPage() {
        var loads = 0
        var state by mutableStateOf(PreviewData.billState().copy(currentPage = 1, pages = 2))
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(state, settings, {}, {}, { loads++; state = state.copy(loadingMore = true) },
                        {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        val search = compose.onNodeWithTag("bill-search")
        search.performClick().assertIsFocused()
        compose.onNodeWithTag("bill-list").performTouchInput { swipeUp() }
        compose.waitForIdle()
        search.assertIsNotFocused()
        assertEquals("The page's shared gesture must still drive pagination", 1, loads)
    }

    @Test fun modeDateAndTypeControlsScrollAwayAndPullRestoresThem() {
        var statistics by mutableStateOf(false)
        var refreshes = 0
        val settings = themeSettingsFixture(compose.activity.application).apply { setBlurTop(false) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    key(statistics) {
                        BillPage(PreviewData.billState().copy(statsVisible = statistics), settings, {},
                            { refreshes++ }, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                    }
                }
            }
        }
        for (stats in listOf(false, true)) {
            compose.runOnIdle { statistics = stats }
            val tabsTop = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot.top
            val dateTop = compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot.top
            val list = compose.onNodeWithTag(if (stats) "bill-stats-list" else "bill-list")
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertTrue("Mode controls must scroll vertically", compose.onNodeWithTag("bill-mode-tabs")
                .fetchSemanticsNode().boundsInRoot.top < tabsTop)
            assertTrue("The date must scroll with the mode controls", compose.onNodeWithTag("bill-date-selector")
                .fetchSemanticsNode().boundsInRoot.top < dateTop)
            assertEquals("The content must reach the window top beneath the blurred bar", 0f,
                list.fetchSemanticsNode().boundsInRoot.top, 1f)
            if (stats) assertEquals("Type controls must scroll away too", 0f,
                compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot.height, 1f)
            capture(if (stats) "statistics-controls-scrolled" else "details-controls-scrolled")
            list.performScrollToIndex(0)
            val before = refreshes
            repeat(3) {
                // Start below the app bar; gestures on its title never reach the scrolling content.
                list.performTouchInput {
                    swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
                }
                compose.waitForIdle()
            }
            assertEquals("Mode controls return to their original position", tabsTop,
                compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot.top, 2f)
            assertEquals("Date controls return without a persistent gap", dateTop,
                compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot.top, 2f)
            assertTrue("Pull refresh must still work after both control layers expand", refreshes > before)
        }
    }

    @Test fun progressiveTopBlurSamplesScrollingBillsInBothThemes() {
        var dark by mutableStateOf(false)
        val settings = themeSettingsFixture(compose.activity.application).apply { setBlurTop(false) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    key(dark) {
                        BillPage(PreviewData.billState(), settings, {}, {}, {}, {}, { _, _, _ -> },
                            {}, {}, {}, showBack = false)
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; settings.setBlurTop(false) }
            val list = compose.onNodeWithTag("bill-list")
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
            // Put a populated card beneath the whole top bar, rather than comparing an empty backdrop.
            list.performScrollToIndex(2)
            val bounds = compose.onNodeWithTag("bill-top-bar").fetchSemanticsNode().boundsInRoot
            val opaque = compose.onNodeWithTag("bill-top-bar").captureToImage().asAndroidBitmap()
            compose.runOnIdle { settings.setBlurTop(true) }
            compose.waitForIdle()
            val blurred = compose.onNodeWithTag("bill-top-bar").captureToImage().asAndroidBitmap()
            var changed = 0
            for (y in 0 until opaque.height) for (x in 0 until opaque.width)
                if (opaque.getPixel(x, y) != blurred.getPixel(x, y)) changed++
            assertTrue("Progressive blur must sample the card scrolling beneath the top bar (dark=$night)",
                changed > opaque.width * opaque.height / 100)
            assertEquals("The blur toggle must preserve bar geometry", bounds,
                compose.onNodeWithTag("bill-top-bar").fetchSemanticsNode().boundsInRoot)
            capture(if (night) "dark-scrolled-top-blur" else "light-scrolled-top-blur")
            compose.runOnIdle { settings.setBlurTop(false) }
            assertTrue("Disabling blur must restore the opaque bar", opaque.sameAs(
                compose.onNodeWithTag("bill-top-bar").captureToImage().asAndroidBitmap()))
        }
    }

    @Test fun shortAndFailedPagesRestoreControlsAndKeepPullRefresh() {
        val empty = PreviewData.billState().copy(items = emptyList())
        val failed = empty.copy(statsVisible = true, stats = BillStatsState(
            period = empty.dateFilter, type = 2, error = "首次读取失败"))
        var state by mutableStateOf(empty)
        var refreshes = 0
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    key(state.statsVisible) {
                        BillPage(state, settings, {}, { refreshes++ }, {}, {}, { _, _, _ -> },
                            {}, {}, {}, showBack = false)
                    }
                }
            }
        }
        for (page in listOf(empty, failed)) {
            compose.runOnIdle { state = page }
            val tabs = compose.onNodeWithTag("bill-mode-tabs")
            val original = tabs.fetchSemanticsNode().boundsInRoot
            val list = compose.onNodeWithTag(if (page.statsVisible) "bill-stats-list" else "bill-list")
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
            assertTrue("Even a short or failed page must let its controls scroll away",
                tabs.fetchSemanticsNode().boundsInRoot.top < original.top)
            list.performScrollToIndex(0)
            val before = refreshes
            repeat(3) {
                list.performTouchInput {
                    swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
                }
                compose.waitForIdle()
            }
            assertEquals("Restoring a short or failed page must leave no extra header gap", original,
                tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue("Short and failed pages must remain refreshable", refreshes > before)
            if (page.statsVisible) compose.onNodeWithText("暂时无法加载，请下拉刷新").assertIsDisplayed()
            else compose.onNodeWithText("当前时间段还没有账单").assertIsDisplayed()
        }
    }
}
