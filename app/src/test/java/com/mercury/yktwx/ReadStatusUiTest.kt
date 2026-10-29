package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
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
class ReadStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val file = File("../tools/read-status-review/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    private fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }

    @Test fun localSnapshotAppearsAtFullOpacityWithoutReplayingSkeletonEntry() {
        var restored by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                InitialReadTransition(null, displayRestored = restored) {
                    ContentFade(restored) { cached ->
                        Box(Modifier.size(100.dp).background(if (cached) Color.Black else Color.White))
                    }
                }
            }
        }
        compose.runOnIdle { restored = true }
        repeat(2) { frame() }
        assertEquals(android.graphics.Color.BLACK, compose.onRoot().captureToImage().asAndroidBitmap().getPixel(30, 30))
    }

    @Test fun pendingReadCompletesAndCollapsesWithoutChangingCachedContent() {
        var loading by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme { PageReadNotice(null, 12.dp, loading) {
            Box(Modifier.size(100.dp).testTag("content").background(Color.Black))
        } } }
        fun top() = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        val pendingTop = top()
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        compose.runOnIdle { loading = false }
        repeat(2) { frame() }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithTag("page-read-progress").assertExists()
        assertEquals(pendingTop, top(), 1f)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        assertEquals(0f, top(), 1f)
        val bitmap = compose.onNodeWithTag("content").captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(30, 30))
    }

    @Test fun failureUsesTheSameRegionAndRetryDoesNotCloseThenReopenIt() {
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            val displayed = rememberPageNetworkError(error, loading, true)
            PageReadNotice(displayed, 12.dp, loading) { Box(Modifier.size(100.dp).testTag("content")) }
        } }
        fun top() = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot.top
        val pendingTop = top()
        compose.runOnIdle { loading = false; error = "水电费、校园网更新失败，请下拉刷新" }
        repeat(3) { frame() }
        repeat(5) {
            compose.mainClock.advanceTimeBy(48)
            assertTrue("Failure must expand from the read region without collapsing it", top() >= pendingTop - 1)
        }
        compose.mainClock.advanceTimeBy(400)
        val failureTop = top()
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
        compose.runOnIdle { loading = true; error = null }
        repeat(3) { frame() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(failureTop, top(), 1f)
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        capture("retry")
    }

    @Test fun actualPullOwnsProgressAndFailureStillAppearsAfterIt() {
        val ready = PreviewData.hallState()
        var state by mutableStateOf(ready)
        var refreshes = 0
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            HallPage(settings, 0.dp, dorm = state, onRefreshDorm = {
                refreshes++
                state = DormUiState(ready.rooms, loading = true, loaded = true, network = ready.network)
            })
        } } }
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertEquals(1, refreshes)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.runOnIdle { state = DormUiState(ready.rooms, loaded = true, network = ready.network,
            error = "网络连接失败", networkError = "网络连接失败") }
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
    }

    @Test fun cachedQueryShowsLocalProgressAndLeavesOverviewPixelsAndBoundsUnchanged() {
        var state by mutableStateOf(PreviewData.billState())
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            BillPage(state, settings, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
        } } }
        val before = compose.onNodeWithTag("bill-summary").captureToImage().asAndroidBitmap()
        val bounds = compose.onNodeWithTag("bill-summary").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(info = "食堂", loading = true, listLoading = true,
            listOnlyRead = true, listAvailable = true) }
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("bill-summary").fetchSemanticsNode().boundsInRoot)
        assertTrue(before.sameAs(compose.onNodeWithTag("bill-summary").captureToImage().asAndroidBitmap()))
        capture("query")
    }

    @Test fun paymentCircleTracksOnlyBalanceReadAndReservesSpaceAtNarrowLargeText() {
        var state by mutableStateOf(PreviewData.paymentState(false).copy(busy = true, code = null, balanceLoading = true))
        var dark by mutableStateOf(false)
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density, if (dark) 1.5f else 1f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Box(Modifier.width(if (dark) 320.dp else 411.dp).fillMaxHeight()) {
                        PaymentPage(state, settings, 0.dp, {}, onRecharge = {})
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night; state = state.copy(balanceLoading = true, busy = true) }
            val slot = compose.onNodeWithTag("balance-read-slot").fetchSemanticsNode().boundsInRoot
            val recharge = compose.onNodeWithText("充值").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("balance-read-progress").assertExists()
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            capture(if (night) "payment-narrow-large-dark" else "payment-light")
            compose.runOnIdle { state = state.copy(balanceLoading = false) }
            compose.onNodeWithTag("balance-read-progress").assertDoesNotExist()
            assertEquals(slot, compose.onNodeWithTag("balance-read-slot").fetchSemanticsNode().boundsInRoot)
            assertEquals(recharge, compose.onNodeWithText("充值").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { state = state.copy(busy = false, secondsLeft = 42) }
            compose.onNodeWithTag("balance-read-progress").assertDoesNotExist()
        }
    }

    @Test fun cachedDashboardReadsUseOneQuietNoticeInLightAndNarrowLargeDarkLayouts() {
        var page by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        val hall = PreviewData.hallState()
        val bill = PreviewData.billState()
        val my = PreviewData.myState()
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density, if (dark) 1.5f else 1f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Box(Modifier.width(if (dark) 320.dp else 411.dp).fillMaxHeight()) {
                        key(page, dark) {
                            when (page) {
                                0 -> HallPage(settings, 0.dp, dorm = DormUiState(hall.rooms, loading = true,
                                    network = hall.network, roomsAvailable = true, displayRestored = true))
                                1 -> MyPage(my.copy(loading = true, displayRestored = true), PreviewData.paymentState(false),
                                    settings, 0.dp, true, {}, {}, {}, {}, {}, {}, false, {}, false, false, {}, {})
                                2 -> BillPage(bill.copy(statsVisible = true,
                                    stats = bill.stats.copy(loading = true, displayRestored = true)), settings,
                                    {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                            }
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (index in 0..2) {
            compose.runOnIdle { dark = night; page = index }
            compose.onAllNodesWithTag("page-read-progress").assertCountEquals(1)
            compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
            compose.onNodeWithTag("page-network-error").assertDoesNotExist()
            compose.onNodeWithText("更新中").assertDoesNotExist()
            capture("cached-$index-${if (night) "narrow-large-dark" else "light"}")
        }
    }
}
