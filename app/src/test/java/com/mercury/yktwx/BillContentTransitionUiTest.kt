package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.ReadFailureKind
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
class BillContentTransitionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var state by mutableStateOf(PreviewData.billState())
    private var active by mutableStateOf(true)
    private var onStats: () -> Unit = { state = state.copy(statsVisible = true) }
    private var onType: (Int) -> Unit = { type ->
        state = state.copy(stats = PreviewData.billStats(state.dateFilter, type).copy(displayRestored = true))
    }

    private fun frame(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performClick()

    private fun content(dark: Boolean = false, narrow: Boolean = false) {
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, if (narrow) 1.5f else 1f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier) {
                        BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {},
                            onOpenStats = { onStats() }, onOpenDetail = {},
                            onShowDetails = { state = state.copy(statsVisible = false) },
                            onStatsTypeChange = { onType(it) }, isActive = active, showBack = false)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun capture(name: String) {
        val output = File("../tools/bill-content-motion/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun failedStats(type: Int) = PreviewData.billStats(state.dateFilter, type).copy(
        displayRestored = true,
        dailyError = "网络连接失败", dailyErrorKind = ReadFailureKind.Network,
        categoriesError = "网络连接失败", categoriesErrorKind = ReadFailureKind.Network,
        rankingError = "网络连接失败", rankingErrorKind = ReadFailureKind.Network,
    )

    @Test fun cachedNetworkNoticeStaysContinuousAcrossTypeRetriesAndClearsOnSuccess() {
        state = state.copy(statsVisible = true, stats = failedStats(2))
        onType = { type -> state = state.copy(stats = PreviewData.billStats(state.dateFilter, type)
            .copy(loading = true, displayRestored = true)) }
        content()
        frame(20)
        val notice = compose.onNodeWithTag("page-network-error")
        val noticeImage = notice.captureToImage().asAndroidBitmap()
        val noticeBounds = notice.fetchSemanticsNode().boundsInRoot
        val modeBounds = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot
        val typeBounds = compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot
        // Fail before, near, and after the slide settles, then keep one retry pending for longer.
        for (failureFrame in listOf(1, 16, 35, 70)) {
            val nextType = if (state.stats.type == 2) 1 else 2
            click(if (nextType == 1) "收入" else "支出")
            repeat(failureFrame + 25) { index ->
                if (index == failureFrame) compose.runOnIdle { state = state.copy(stats = failedStats(nextType)) }
                frame()
                compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
                assertEquals("Retry must not collapse the common controls at frame $index",
                    modeBounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
                assertEquals(typeBounds, compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot)
                assertEquals(noticeBounds, notice.fetchSemanticsNode().boundsInRoot)
                assertTrue("A repeated network failure must not exit and re-enter at frame $index",
                    noticeImage.sameAs(notice.captureToImage().asAndroidBitmap()))
            }
        }
        click("收入")
        frame(35)
        assertEquals(modeBounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 1)) }
        frame(25)
        notice.assertDoesNotExist()
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        val settledBounds = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot
        frame(10)
        assertEquals(settledBounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun delayedCacheRestorationAndRapidReversalsKeepOneNetworkNotice() {
        state = state.copy(statsVisible = true, stats = failedStats(2))
        onType = { type -> state = state.copy(stats = BillStatsState(period = state.dateFilter,
            type = type, loading = true)) }
        content(dark = true, narrow = true)
        frame(20)
        val notice = compose.onNodeWithTag("page-network-error")
        val image = notice.captureToImage().asAndroidBitmap()
        val bounds = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot
        fun assertStable() {
            compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
            assertTrue(image.sameAs(notice.captureToImage().asAndroidBitmap()))
            assertEquals(bounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
        }
        repeat(3) {
            click("收入")
            repeat(4) { frame(); assertStable() }
            compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 1)
                .copy(loading = true, displayRestored = true)) }
            repeat(3) { frame(); assertStable() }
            click("支出")
            frame()
            compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 2)
                .copy(loading = true, displayRestored = true)) }
            repeat(35) { frame(); assertStable() }
            compose.runOnIdle { state = state.copy(stats = failedStats(2)) }
            repeat(20) { frame(); assertStable() }
        }
    }

    @Test fun networkRetryNoticeDoesNotLeakIntoUncachedOrDifferentPeriodReads() {
        state = state.copy(statsVisible = true, stats = failedStats(2))
        onType = { type -> state = state.copy(stats = BillStatsState(period = state.dateFilter,
            type = type, loading = true)) }
        content()
        click("收入")
        frame(50)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在加载统计").assertIsDisplayed()
        compose.onAllNodesWithText("显示上次数据", substring = true).assertCountEquals(0)
        compose.runOnIdle { state = state.copy(stats = state.stats.copy(loading = false, loaded = true,
            dailyError = "网络连接失败", dailyErrorKind = ReadFailureKind.Network,
            categoriesError = "网络连接失败", categoriesErrorKind = ReadFailureKind.Network,
            rankingError = "网络连接失败", rankingErrorKind = ReadFailureKind.Network)) }
        frame(25)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(stats = failedStats(1)) }
        frame(25)
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
        val newPeriod = BillDateFilter.year(2025)
        compose.runOnIdle { state = state.copy(dateFilter = newPeriod,
            stats = PreviewData.billStats(newPeriod, 1).copy(loading = true, displayRestored = true)) }
        frame(25)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(stats = failedStats(1)) }
        frame(25)
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
        compose.runOnIdle { state = BillUiState(needsLogin = true, statsVisible = true,
            dateFilter = newPeriod, stats = BillStatsState(period = newPeriod, type = 1)) }
        frame(25)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
    }

    @Test fun modeSwitchKeepsOneSelectorAndRestoresIndependentReadingPositions() {
        content()
        val selectorId = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().id
        val selectorBounds = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot
        val dateBounds = compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot
        val viewportBounds = compose.onNodeWithTag("bill-content-viewport").fetchSemanticsNode().boundsInRoot
        val summaryBounds = compose.onNodeWithTag("bill-summary").fetchSemanticsNode().boundsInRoot
        assertEquals("Details start after the normal 12dp gap without a type-tab placeholder",
            viewportBounds.top + 12f * compose.activity.resources.displayMetrics.density, summaryBounds.top, 1f)
        compose.onNodeWithTag("bill-type-tabs").assertDoesNotExist()
        var midwayTypeLeft: Float? = null
        click("统计")
        repeat(25) {
            frame()
            compose.onAllNodesWithTag("bill-mode-tabs").assertCountEquals(1)
            assertEquals(selectorId, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().id)
            assertEquals(selectorBounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
            assertEquals(dateBounds, compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot)
            assertEquals("Mode switches must keep the same content viewport in every frame", viewportBounds,
                compose.onNodeWithTag("bill-content-viewport").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            if (it == 10) {
                midwayTypeLeft = compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot.left
                capture("light-mode-middle")
            }
        }
        val typeBounds = compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot
        assertTrue("The child tabs must travel with the statistics page during a mode switch",
            checkNotNull(midwayTypeLeft) > typeBounds.left + 10f)
        capture("light-statistics")
        val statsList = compose.onNodeWithTag("bill-stats-list")
        statsList.performScrollToIndex(2)
        frame(25)
        val before = statsList.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        click("明细")
        frame(25)
        compose.onNodeWithTag("bill-search").assertIsDisplayed()
        compose.onNodeWithTag("bill-type-tabs").assertDoesNotExist()
        assertEquals(viewportBounds, compose.onNodeWithTag("bill-content-viewport").fetchSemanticsNode().boundsInRoot)
        assertEquals(summaryBounds, compose.onNodeWithTag("bill-summary").fetchSemanticsNode().boundsInRoot)
        click("统计")
        frame(25)
        assertEquals(before, compose.onNodeWithTag("bill-stats-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
    }

    @Test fun cachedTypeSwitchKeepsSelectorsStillAndSlidesOpaqueCardPaint() {
        state = state.copy(statsVisible = true)
        content()
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val tabsImage = tabs.captureToImage().asAndroidBitmap()
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        val dateBounds = compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot
        val typeBounds = compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot
        val overview = compose.onNodeWithTag("bill-stats-overview")
        val before = overview.captureToImage().asAndroidBitmap()
        val cardBounds = overview.fetchSemanticsNode().boundsInRoot
        val rootBefore = compose.onRoot().captureToImage().asAndroidBitmap()
        click("收入")
        var changed = false
        var midway: Bitmap? = null
        repeat(25) { index ->
            frame()
            val current = compose.onAllNodesWithTag("bill-stats-overview").fetchSemanticsNodes().singleOrNull()
            assertEquals(dateBounds, compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot)
            assertEquals(typeBounds, compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot)
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue("The unchanged mode selector must keep the same paint in every frame",
                tabsImage.sameAs(tabs.captureToImage().asAndroidBitmap()))
            val root = compose.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("Incoming content must be composed within the first three frames", current != null || index < 3)
            val sampleX = (current?.boundsInRoot?.left ?: cardBounds.left).toInt() + 70
            val sampleY = cardBounds.top.toInt() + 25
            if (sampleX in 0 until root.width) {
                assertEquals("Sliding cards must keep their opaque fill", before.getPixel(70, 25), root.getPixel(sampleX, sampleY))
            }
            if (!rootBefore.sameAs(root)) changed = true
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            if (index == 10) {
                midway = root
                capture("light-type-middle")
            }
        }
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
        assertTrue(tabsImage.sameAs(tabs.captureToImage().asAndroidBitmap()))
        assertEquals(cardBounds, overview.fetchSemanticsNode().boundsInRoot)
        assertTrue("The content switch must be visibly animated", changed)
        val final = compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()
        assertFalse("During the slide the old and new contents must both be visible",
            checkNotNull(midway).sameAs(compose.onRoot().captureToImage().asAndroidBitmap()))
        repeat(10) { frame(); assertTrue("Cached content must not replay its entrance", final.sameAs(compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap())) }
        capture("light-income")
    }

    @Test fun snapshotRestoringDuringSwitchWaitsForStableSkeletonThenFadesOnce() {
        state = state.copy(statsVisible = true)
        onType = { type -> state = state.copy(stats = BillStatsState(period = state.dateFilter, type = type, loading = true)) }
        content()
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val baseline = tabs.captureToImage().asAndroidBitmap()
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        click("收入")
        frame(4)
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 1).copy(displayRestored = true)) }
        repeat(12) {
            frame()
            compose.onNodeWithTag("bill-stats-amount").assertDoesNotExist()
            compose.onNodeWithContentDescription("正在加载统计").assertExists()
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue(baseline.sameAs(tabs.captureToImage().asAndroidBitmap()))
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        }
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
        assertTrue(baseline.sameAs(tabs.captureToImage().asAndroidBitmap()))
        frame(6)
        val entering = compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()
        frame(14)
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        val overview = compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()
        assertFalse("A snapshot first seen after the slide must crossfade from the visible skeleton", entering.sameAs(overview))
        repeat(10) { frame(); assertTrue(overview.sameAs(compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap())) }
    }

    @Test fun slowReadKeepsTheSkeletonAndThenFadesOnlyTheLoadedContent() {
        state = state.copy(statsVisible = true)
        onType = { type -> state = state.copy(stats = BillStatsState(period = state.dateFilter, type = type, loading = true)) }
        content()
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        click("收入")
        val skeletonSamples = mutableListOf<Int>()
        val density = compose.activity.resources.displayMetrics.density
        repeat(25) { index ->
            frame()
            val card = compose.onAllNodesWithTag("bill-stats-overview").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            assertTrue("Incoming skeleton must be composed within the first three frames", card != null || index < 3)
            val root = compose.onRoot().captureToImage().asAndroidBitmap()
            // Prefetched pages have nodes before any of their bounds enter the viewport.
            if (card == null || card.width <= 40f * density || card.height <= 65f * density) return@repeat
            val x = (card.left + 40f * density).toInt()
            val y = (card.top + 65f * density).toInt()
            if (x in 0 until root.width && y in 0 until root.height) skeletonSamples += root.getPixel(x, y)
        }
        capture("light-slow-read")
        assertTrue("The entering skeleton must paint at full opacity throughout the slide: $skeletonSamples",
            skeletonSamples.size > 8 && skeletonSamples.all { it == skeletonSamples.last() })
        compose.onNodeWithContentDescription("正在加载统计").assertIsDisplayed()
        compose.onNodeWithTag("bill-stats-amount").assertDoesNotExist()
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        capture("light-slow-read")
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 1)) }
        frame(2)
        val entering = compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()
        frame(25)
        assertFalse("A result arriving after the tab switch retains its existing content fade",
            entering.sameAs(compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()))
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun firstModeReadKeepsPartialResponsesOutOfTheSlideAndRestoresNormalUpdates() {
        onStats = { state = state.copy(statsVisible = true,
            stats = BillStatsState(period = state.dateFilter, type = 2, loading = true)) }
        content()
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        val dateBounds = compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot
        click("统计")
        frame(5)
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 2)
            .copy(categoriesLoaded = false, rankingLoaded = false, loading = true)) }
        frame(6)
        capture("light-first-mode-partial-middle")
        compose.runOnIdle { state = state.copy(stats = state.stats.copy(categoriesLoaded = true)) }
        repeat(5) {
            frame()
            compose.onNodeWithTag("bill-stats-amount").assertDoesNotExist()
            compose.onNodeWithContentDescription("正在加载统计").assertExists()
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertEquals(dateBounds, compose.onNodeWithTag("bill-date-selector").fetchSemanticsNode().boundsInRoot)
        }
        frame(20)
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        compose.onNodeWithTag("bill-stats-list").performScrollToIndex(3)
        frame(2)
        compose.onNodeWithContentDescription("正在加载单笔排行").assertExists()
        compose.onNodeWithTag("bill-stats-list").performScrollToIndex(0)
        frame(2)
        val overview = compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()
        compose.runOnIdle { state = state.copy(stats = state.stats.copy(rankingLoaded = true, loading = false)) }
        frame(20)
        assertTrue("Unrelated partial responses must not replay the overview entrance",
            overview.sameAs(compose.onNodeWithTag("bill-stats-overview").captureToImage().asAndroidBitmap()))
        capture("light-first-mode-loaded")
    }

    @Test fun firstReadFailureWaitsForSlideAndRetryKeepsFailureUntilContentArrives() {
        state = state.copy(statsVisible = true)
        onType = { type -> state = state.copy(stats = BillStatsState(period = state.dateFilter, type = type, loading = true)) }
        content()
        val bounds = compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot
        click("收入")
        frame(4)
        compose.runOnIdle { state = state.copy(stats = state.stats.copy(loading = false,
            error = "首次读取失败", errorKind = ReadFailureKind.Other)) }
        repeat(12) {
            frame()
            compose.onNodeWithContentDescription("正在加载统计").assertExists()
            compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
            assertEquals(bounds, compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot)
        }
        frame(20)
        compose.onNodeWithText("暂时无法加载，请下拉刷新").assertIsDisplayed()
        val failure = compose.onNodeWithTag("read-unavailable").captureToImage().asAndroidBitmap()
        compose.runOnIdle { state = state.copy(stats = BillStatsState(period = state.dateFilter, type = 1, loading = true)) }
        frame(20)
        assertTrue("Retry must retain the failure instead of flashing back to skeleton",
            failure.sameAs(compose.onNodeWithTag("read-unavailable").captureToImage().asAndroidBitmap()))
        compose.runOnIdle { state = state.copy(stats = PreviewData.billStats(state.dateFilter, 1)) }
        frame(20)
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("bill-type-tabs").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun switchingAwayFromAQueryReadRemovesItsProgressWithoutReplayingCompletion() {
        state = state.copy(info = "食堂", listLoading = true, loading = true, listOnlyRead = true, listAvailable = true)
        content()
        frame(30)
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        click("统计")
        frame(25)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.onNodeWithTag("bill-stats-overview").assertIsDisplayed()
    }

    @Test fun pullRefreshBelongsToTheModeThatRequestedIt() {
        var requested = false
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(state, settings, {}, {
                        requested = true
                        state = state.copy(refreshing = true, loading = true)
                    }, {}, {}, { _, _, _ -> }, {},
                        onOpenStats = { state = state.copy(statsVisible = true) }, onOpenDetail = {},
                        onShowDetails = { state = state.copy(statsVisible = false) }, showBack = false)
                }
            }
        }
        compose.onNodeWithTag("bill-list").performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue(requested)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(statsVisible = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("bill-stats-overview").assertIsDisplayed()
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.onNodeWithText("正在刷新").assertDoesNotExist()
    }

    @Test fun expiredCacheProgressDoesNotMoveSelectorsOrFinishTheQueryThatWasLeft() {
        state = state.copy(statsVisible = true)
        onType = { type -> state = state.copy(stats = PreviewData.billStats(state.dateFilter, type)
            .copy(loading = true, displayRestored = true)) }
        content()
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        click("收入")
        frame(25)
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
        click("明细")
        repeat(25) {
            frame()
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue(compose.onAllNodesWithTag("page-read-progress").fetchSemanticsNodes().size <= 1)
        }
        compose.onNodeWithTag("bill-search").assertIsDisplayed()
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
    }

    @Test fun rapidReversalsErrorsAndLeavingDiscardOutgoingData() {
        state = state.copy(statsVisible = true)
        content()
        repeat(3) {
            click("收入"); frame(1)
            click("支出"); frame(2)
        }
        click("收入"); frame(4)
        click("明细"); frame(4)
        click("统计"); frame(25)
        click("支出")
        frame(25)
        assertEquals(2, state.stats.type)
        capture("light-rapid-mode")
        compose.onNodeWithText("本月支出合计").assertIsDisplayed()
        click("收入"); frame(4)
        compose.runOnIdle {
            state = state.copy(stats = BillStatsState(period = state.dateFilter, type = 1,
                error = "暂时无法加载", errorKind = ReadFailureKind.Other))
        }
        frame(24)
        compose.onAllNodesWithTag("bill-mode-tabs").assertCountEquals(1)
        click("支出"); frame(1)
        compose.runOnIdle {
            active = false
            state = state.copy(stats = PreviewData.billStats(BillDateFilter.year(2026), 2))
        }
        frame(2)
        compose.onNodeWithTag("bill-stats-amount").assertExists()
        compose.onNodeWithText("暂时无法加载，请下拉刷新").assertDoesNotExist()
    }

    @Test fun darkNarrowLargeTextKeepsTheSameCardFillAndUnchangedModeSelector() {
        state = state.copy(statsVisible = true)
        content(dark = true, narrow = true)
        capture("dark-narrow-expenses")
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val image = tabs.captureToImage().asAndroidBitmap()
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        click("收入")
        repeat(25) {
            frame()
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue(image.sameAs(tabs.captureToImage().asAndroidBitmap()))
            if (it == 7) capture("dark-narrow-type-middle")
        }
        assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
        assertTrue(image.sameAs(tabs.captureToImage().asAndroidBitmap()))
        capture("dark-narrow-income")
        click("明细")
        frame(25)
        compose.onNodeWithTag("bill-search").assertIsDisplayed()
        capture("dark-narrow-details")
    }
}
