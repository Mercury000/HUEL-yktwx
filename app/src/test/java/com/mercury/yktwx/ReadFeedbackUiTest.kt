package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.allowOnlineEntry
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val networkError = "网络连接失败，请检查网络后刷新"
    private val verticalList = hasScrollToIndexAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun capture(name: String) {
        val file = File("../tools/read-feedback-review/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun profileFirstFailureStaysVisibleDuringRetryAndReallyPullsToRefresh() {
        var state by mutableStateOf(MyUiState(loaded = true, profileLoaded = true, profileError = networkError))
        var refreshes = 0
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PersonalProfilePage(state, settings, {}, { refreshes++; state = state.copy(loading = true, profileError = null) })
                }
            }
        }
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.onNode(verticalList).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue(refreshes > 0)
        compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
        compose.onNodeWithText("未提供").assertDoesNotExist()
    }

    private fun transitionFixture(error: State<String?>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                InitialReadTransition(error.value) { shownError ->
                    Box(Modifier.fillMaxSize()) {
                        if (shownError == null) ContentPlaceholder(Modifier.size(100.dp).testTag("skeleton"),
                            color = Color.Black)
                        else Box(Modifier.size(100.dp).background(Color.Red).testTag("failure"))
                    }
                }
            }
        }
    }

    private fun samplePixel(): Int {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        return bitmap.getPixel(30, 30)
    }

    @Test fun fastFailureCancelsSkeletonAppearanceEvenWhileTheFailureFadesIn() {
        val error = mutableStateOf<String?>(null)
        transitionFixture(error)
        compose.mainClock.advanceTimeBy(80)
        assertEquals(android.graphics.Color.WHITE, samplePixel())
        compose.runOnIdle { error.value = networkError }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(80)
        // Outgoing skeleton stays in the crossfade, but must never start painting now.
        val color = samplePixel()
        assertEquals(255, android.graphics.Color.red(color))
        assertTrue(android.graphics.Color.green(color) < 255)
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("skeleton").assertDoesNotExist()
        assertEquals(android.graphics.Color.RED, samplePixel())
    }

    @Test fun slowFailureCrossfadesTheVisibleSkeletonWithoutHoldingTheResult() {
        val error = mutableStateOf<String?>(null)
        transitionFixture(error)
        compose.mainClock.advanceTimeBy(650)
        assertEquals(android.graphics.Color.BLACK, samplePixel())
        compose.runOnIdle { error.value = networkError }
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("skeleton").assertExists()
        compose.onNodeWithTag("failure").assertExists()
        val color = samplePixel()
        assertTrue(android.graphics.Color.red(color) > android.graphics.Color.green(color))
        assertTrue(android.graphics.Color.red(color) < 255)
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("skeleton").assertDoesNotExist()
        assertEquals(android.graphics.Color.RED, samplePixel())
    }

    @Test fun allFourFirstReadFailuresHaveOneFeedbackAndReallyPullToRefresh() {
        val settings = ThemeSettings(compose.activity.application)
        var page by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        var refreshes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, if (dark) 1.5f else 1f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    key(page, dark) {
                        when (page) {
                            0 -> PaymentPage(PaymentUiState(needsLogin = false, error = networkError),
                                settings, 0.dp, { refreshes++ })
                            1 -> HallPage(settings, 0.dp, dorm = DormUiState(loaded = true,
                                error = networkError, networkError = networkError, roomsAvailable = false),
                                onRefreshDorm = { refreshes++ })
                            2 -> BillPage(BillUiState(loaded = true, error = networkError,
                                summaryError = networkError, countsError = networkError), settings,
                                {}, {}, { refreshes++ }, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                            3 -> MyPage(MyUiState(loaded = true, profileError = networkError,
                                homeIdentityError = networkError, summaryError = networkError, countsError = networkError),
                                PaymentUiState(needsLogin = false), settings, 0.dp, false, {}, { refreshes++ },
                                {}, {}, {}, {}, false, {}, false, false, {}, {})
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (index in 0..3) {
            compose.runOnIdle { dark = night; page = index }
            compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
            compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
            compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
            compose.onNodeWithText("重试").assertDoesNotExist()
            compose.onNodeWithText("未更新").assertDoesNotExist()
            capture("first-$index-${if (night) "large-dark" else "light"}")
            val before = refreshes
            compose.onNode(verticalList).performTouchInput {
                swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
            }
            compose.runOnIdle { assertEquals("Page $index must receive a real pull", before + 1, refreshes) }
        }
    }

    @Test fun failedRetryKeepsTheErrorUntilActualContentArrives() {
        val settings = ThemeSettings(compose.activity.application)
        var state by mutableStateOf(DormUiState(loaded = true, error = networkError,
            networkError = networkError, roomsAvailable = false))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { HallPage(settings, 0.dp, dorm = state) }
            }
        }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.runOnIdle { state = DormUiState(loading = true, loaded = true, roomsAvailable = false) }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("hall-utility-card").assertDoesNotExist()
        compose.runOnIdle { state = state.let { DormUiState(loaded = true, error = networkError,
            networkError = networkError, roomsAvailable = false) } }
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.runOnIdle { state = PreviewData.hallState() }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("hall-utility-card").assertIsDisplayed()
    }

    @Test fun cachedHallHasOneNoticeForBothMetersAndKeepsActualNumbers() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        val room = ready.rooms.single()
        val failed = DormUiState(rooms = listOf(DormRoom(room.project, room.binding,
            room.meters.map { DormMeter(it.kind, it.room, it.query, networkError, it.project) }, room.updatedAtMillis)),
            loaded = true, error = networkError, network = ready.network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { HallPage(settings, 0.dp, dorm = failed) } }
        }
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(0)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        compose.onNodeWithText("网络连接失败，下拉刷新更新").assertIsDisplayed()
        room.meters.forEach { meter ->
            compose.onAllNodesWithText(meter.readings.first().value).assertCountEquals(1)
        }
        capture("hall-cache")
    }

    @Test fun hallIndependentFailuresUseTheSameComponentAndCachedNetworkUsesOnlyOneNotice() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        var state by mutableStateOf(DormUiState(rooms = ready.rooms, loaded = true,
            networkError = networkError, roomsAvailable = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { HallPage(settings, 0.dp, dorm = state) } }
        }
        val unavailableSize = compose.onNodeWithTag("read-unavailable").fetchSemanticsNode().boundsInRoot.size
        compose.onNodeWithText("校园网").assertIsDisplayed()
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        capture("hall-network-first-failure")
        compose.runOnIdle { state = DormUiState(rooms = ready.rooms, loaded = true,
            network = ready.network, networkError = networkError) }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        capture("hall-network-cache-failure")
        compose.runOnIdle { state = DormUiState(loaded = true, error = networkError,
            network = ready.network, roomsAvailable = false) }
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        assertEquals(unavailableSize.height, compose.onNodeWithTag("read-unavailable").fetchSemanticsNode().boundsInRoot.height, 1f)
        capture("hall-utility-first-failure")
        compose.runOnIdle { state = ready }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("read-notice").assertDoesNotExist()
    }

    @Test fun billFailedRetryDoesNotMistakeCompletedFailedListForAvailableContent() {
        val settings = ThemeSettings(compose.activity.application)
        var state by mutableStateOf(BillUiState(loaded = true, error = networkError,
            summaryError = networkError, countsError = networkError))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state, settings, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false) }
            }
        }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(refreshing = true, error = null, summaryError = null, countsError = null) }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(refreshing = false, summary = BillSummary(0, 0)) }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
    }

    @Test fun summaryAndCountsFailureUseOneNoticeInTheSummaryCard() {
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(PreviewData.billState().copy(summaryError = networkError, countsError = networkError),
                        settings, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        capture("bill-cache")
    }

    @Test fun inlineErrorIsSingleLineAndDoesNotReserveAnEmptyFormRow() {
        var error by mutableStateOf<String?>(null)
        compose.setContent {
            AppTheme {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("输入区域", Modifier.testTag("field"))
                    if (!error.isNullOrBlank()) InlineError(error)
                    Text("主操作", Modifier.testTag("action"))
                }
            }
        }
        val before = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { error = "网络连接失败\n充值请求未发出" }
        compose.onNodeWithText("网络连接失败，充值请求未发出").assertExists()
        compose.onNodeWithText("重试").assertDoesNotExist()
        capture("inline-error")
        compose.runOnIdle { error = null }
        val after = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, after.top, .5f)
    }

    @Test fun knownOfflineEntryReturnsOnceWithoutRunningTheAction() {
        val context = compose.activity
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(null)
        ShadowToast.reset()
        assertFalse(allowOnlineEntry(context))
        assertEquals(1, ShadowToast.shownToastCount())
        assertEquals("网络连接失败", ShadowToast.getTextOfLatestToast())
        // A connected network without Android's validation may still reach campus services.
        shadowOf(connectivity).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        assertTrue(allowOnlineEntry(context))
        assertEquals(1, ShadowToast.shownToastCount())
    }
}
