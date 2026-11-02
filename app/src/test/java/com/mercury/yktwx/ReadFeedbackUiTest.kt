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
    private val cachedOffline = "网络连接失败，显示上次数据，请下拉刷新"
    private val networkError = "服务暂时无法访问"
    private val verticalList = hasScrollToIndexAction() and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun capture(name: String) {
        val file = File("../tools/read-feedback-review/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun pageNetworkMessagesDistinguishFullPartialAndCachedFailures() {
        val error = ReadError(networkError, ReadFailureKind.Network)
        assertNull(FeedbackText.pageNetworkError(ReadRegion("余额", null, true)))
        assertEquals(FeedbackText.PageOffline, FeedbackText.pageNetworkError(ReadRegion("余额", error, false)))
        assertEquals(cachedOffline, FeedbackText.pageNetworkError(ReadRegion("余额", error, true)))
        assertEquals("余额获取失败，请下拉刷新", FeedbackText.pageNetworkError(
            ReadRegion("余额", error, false), ReadRegion("资料", null, true)))
        assertEquals("余额、本月概览更新失败，显示上次数据，请下拉刷新", FeedbackText.pageNetworkError(
            ReadRegion("余额", error, true), ReadRegion("本月概览", error, true), ReadRegion("资料", null, true)))
        assertEquals("余额、本月概览获取失败，请下拉刷新", FeedbackText.pageNetworkError(
            ReadRegion("余额", error, false), ReadRegion("本月概览", error, false), ReadRegion("资料", null, true)))
    }

    @Test fun partialCachedStatisticsNameFailedRegionsAndWrapAtLargeDarkText() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.billState()
        val stats = PreviewData.billStats(ready.dateFilter, 2).copy(
            dailyError = networkError, dailyErrorKind = ReadFailureKind.Network,
            categoriesError = networkError, categoriesErrorKind = ReadFailureKind.Network)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme(ColorSchemeMode.Dark) {
                    BillPage(ready.copy(statsVisible = true, stats = stats), settings, {}, {}, {}, {},
                        { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        val message = "每日趋势、分类统计更新失败，显示上次数据，请下拉刷新"
        assertSingleNetworkFeedback(message)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(message).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        capture("stats-partial-cache-large-dark")
    }

    @Test fun wrappedNetworkNoticeReusesReadAndCachedContentHeightTransitions() {
        var cached by mutableStateOf(false)
        var loading by mutableStateOf(true)
        var message by mutableStateOf<String?>(null)
        val short = FeedbackText.PageOffline
        val long = "资料、余额、卡状态、本月概览更新失败，显示上次数据，请下拉刷新"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme {
                    key(cached) {
                        Box(Modifier.width(240.dp)) {
                            PageReadNotice(message, 12.dp, loading, errorMaxLines = Int.MAX_VALUE) {
                                Box(Modifier.size(100.dp).testTag("notice-content"))
                            }
                        }
                    }
                }
            }
        }
        fun top() = compose.onNodeWithTag("notice-content").fetchSemanticsNode().boundsInRoot.top
        for (hasCache in listOf(false, true)) {
            compose.runOnIdle { cached = hasCache; loading = false; message = null }
            val empty = top()
            compose.runOnIdle { loading = !hasCache }
            compose.runOnIdle { loading = false; message = short }
            val oneLine = top()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { message = long }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(oneLine, top(), 1f)
            compose.mainClock.advanceTimeBy(64)
            val expanding = top()
            compose.mainClock.advanceTimeBy(400)
            val wrapped = top()
            assertTrue("Both read and cached notices resize gradually", expanding > oneLine && expanding < wrapped)
            compose.runOnIdle { message = null }
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(400)
            assertEquals(empty, top(), 1f)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun profileFirstFailureStaysVisibleDuringRetryAndReallyPullsToRefresh() {
        var state by mutableStateOf(MyUiState(loaded = true, profileLoaded = true, profileError = networkError, profileErrorKind = ReadFailureKind.Network))
        var refreshes = 0
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PersonalProfilePage(state, settings, {}, { refreshes++; state = state.copy(loading = true, profileError = null, profileErrorKind = null) })
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

    @Test fun identicalMessagesWithDifferentKindsKeepTheBusinessNoticeDuringNetworkAggregation() {
        val message = "网络异常，请重新登录后选择套餐"
        val state = PreviewData.myState().copy(profileError = message, profileErrorKind = ReadFailureKind.Network,
            avatarError = message, avatarErrorKind = ReadFailureKind.Other,
            summaryError = message, summaryErrorKind = ReadFailureKind.Network)
        val payment = PreviewData.paymentState(false).copy(accountError = message, accountErrorKind = ReadFailureKind.Network)
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { MyPage(state, payment, settings, 0.dp, false, {}, {}, {}, {}, {},
                    false, {}, false, false, {}, {}) }
            }
        }
        compose.onNodeWithTag("page-network-error").assertIsDisplayed()
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        compose.onNodeWithText("暂时无法更新，显示上次数据").assertIsDisplayed()
        compose.onNodeWithText("登录已失效，请重新登录").assertDoesNotExist()
    }

    @Test fun authenticationFirstFailureRetainsItsKindWhenRetryClearsBothFields() {
        var state by mutableStateOf(MyUiState(loaded = true, profileError = "凭证过期",
            profileErrorKind = ReadFailureKind.Authentication))
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { PersonalProfilePage(state, settings, {}, {}) }
            }
        }
        compose.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = true, profileError = null, profileErrorKind = null) }
        compose.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
    }

    private fun transitionFixture(error: State<String?>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                InitialReadTransition(readError(error.value, ReadFailureKind.Network)) { shownError ->
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
        val updateHint: @Composable () -> Unit = {
            InlineUpdateNotice(AppUpdateUiState(noticeVisible = true,
                release = UpdateRelease(5, "1.3.0", "", "https://example.com/update")), {}, {})
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, if (dark) 1.5f else 1f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    key(page, dark) {
                        when (page) {
                            0 -> PaymentPage(PaymentUiState(needsLogin = false, error = networkError, errorKind = ReadFailureKind.Network),
                                settings, 0.dp, { refreshes++ }, updateNotice = updateHint)
                            1 -> HallPage(settings, 0.dp, dorm = DormUiState(loaded = true,
                                error = networkError, errorKind = ReadFailureKind.Network, networkError = networkError, networkErrorKind = ReadFailureKind.Network, roomsAvailable = false),
                                onRefreshDorm = { refreshes++ }, updateNotice = updateHint)
                            2 -> BillPage(BillUiState(loaded = true, error = networkError, errorKind = ReadFailureKind.Network,
                                summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network), settings,
                                {}, { refreshes++ }, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false, updateNotice = updateHint)
                            3 -> MyPage(MyUiState(loaded = true, profileError = networkError, profileErrorKind = ReadFailureKind.Network,
                                homeIdentityError = networkError, homeIdentityErrorKind = ReadFailureKind.Network, summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network),
                                PaymentUiState(needsLogin = false), settings, 0.dp, false, { refreshes++ },
                                {}, {}, {}, {}, false, {}, false, false, {}, {}, updateNotice = updateHint)
                        }
                    }
                }
            }
        }
        for (night in listOf(false, true)) for (index in 0..3) {
            compose.runOnIdle { dark = night; page = index }
            compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
            compose.onAllNodesWithTag("page-network-error").assertCountEquals(0)
            compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
            compose.onNodeWithText(FeedbackText.PageOffline).assertIsDisplayed()
            compose.onNodeWithTag("read-unavailable").assert(!hasClickAction())
            assertTrue(compose.onNodeWithTag("app-update-notice").fetchSemanticsNode().boundsInRoot.bottom <=
                compose.onNodeWithTag("read-unavailable").fetchSemanticsNode().boundsInRoot.top)
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

    @Test fun cachedMainPagesKeepNetworkNoticeAboveUpdatesAndContent() {
        val settings = ThemeSettings(compose.activity.application)
        var page by mutableIntStateOf(0)
        val updateHint: @Composable () -> Unit = {
            InlineUpdateNotice(AppUpdateUiState(noticeVisible = true,
                release = UpdateRelease(5, "1.3.0", "", "https://example.com/update")), {}, {})
        }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    key(page) {
                        when (page) {
                            0 -> PaymentPage(PreviewData.paymentState(false), settings, 0.dp, {}, updateNotice = updateHint)
                            1 -> HallPage(settings, 0.dp, dorm = PreviewData.hallState(), updateNotice = updateHint)
                            2 -> BillPage(PreviewData.billState(), settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {},
                                showBack = false, updateNotice = updateHint)
                            3 -> MyPage(PreviewData.myState(), PreviewData.paymentState(false), settings, 0.dp, false, {},
                                {}, {}, {}, {}, false, {}, false, false, {}, {}, updateNotice = updateHint)
                        }
                    }
                }
            }
        }
        for (index in 0..3) {
            compose.runOnIdle { page = index }
            assertSingleNetworkFeedback(cachedOffline)
            val contentTag = listOf("payment-code-card", "hall-utility-card", "bill-mode-tabs", "my-balance-card")[index]
            val notice = compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot
            val update = compose.onNodeWithTag("app-update-notice").fetchSemanticsNode().boundsInRoot
            assertTrue(notice.bottom <= update.top)
            assertTrue(update.bottom <= compose.onNodeWithTag(contentTag).fetchSemanticsNode().boundsInRoot.top)
            capture("cached-$index-with-update")
        }
    }

    @Test fun billPaginationNetworkFailureSharesTopNoticeAndKeepsLoadMoreGesture() {
        val settings = ThemeSettings(compose.activity.application)
        val state = PreviewData.billState().copy(currentPage = 1, pages = 2,
            loadMoreError = networkError, loadMoreErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false) }
            }
        }
        assertSingleNetworkFeedback("账单明细更新失败，显示上次数据，请下拉刷新")
        compose.onNodeWithTag("bill-list").performScrollToNode(hasText("继续上拉加载更多"))
        compose.onNodeWithText("继续上拉加载更多").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，更多账单未加载，请再次上拉").assertDoesNotExist()
        compose.onNodeWithText("没有更多了").assertDoesNotExist()
    }

    @Test fun failedRetryKeepsTheErrorUntilActualContentArrives() {
        val settings = ThemeSettings(compose.activity.application)
        var state by mutableStateOf(DormUiState(loaded = true, error = networkError, errorKind = ReadFailureKind.Network,
            networkError = networkError, networkErrorKind = ReadFailureKind.Network, roomsAvailable = false))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { HallPage(settings, 0.dp, dorm = state) }
            }
        }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.runOnIdle { state = DormUiState(loading = true, loaded = true, roomsAvailable = false) }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithTag("hall-utility-card").assertDoesNotExist()
        compose.runOnIdle { state = state.let { DormUiState(loaded = true, error = networkError, errorKind = ReadFailureKind.Network,
            networkError = networkError, networkErrorKind = ReadFailureKind.Network, roomsAvailable = false) } }
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
            room.meters.map { DormMeter(it.kind, it.room, it.query, networkError, it.project, errorKind = ReadFailureKind.Network) }, room.updatedAtMillis)),
            loaded = true, error = networkError, errorKind = ReadFailureKind.Network, network = ready.network, networkError = networkError, networkErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { HallPage(settings, 0.dp, dorm = failed) } }
        }
        assertSingleNetworkFeedback(cachedOffline)
        room.meters.forEach { meter ->
            compose.onAllNodesWithText(meter.readings.first().value).assertCountEquals(1)
        }
        capture("hall-cache")
    }

    @Test fun hallSingleNetworkFailuresUseTopNoticeAndKeepHealthyRegions() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        var state by mutableStateOf(DormUiState(rooms = ready.rooms, loaded = true,
            networkError = networkError, networkErrorKind = ReadFailureKind.Network, roomsAvailable = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { HallPage(settings, 0.dp, dorm = state) } }
        }
        compose.onNodeWithText("校园网").assertIsDisplayed()
        assertSingleNetworkFeedback("校园网信息获取失败，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("hall-network-card").fetchSemanticsNode().boundsInRoot.top)
        capture("hall-network-first-failure")
        compose.runOnIdle { state = DormUiState(rooms = ready.rooms, loaded = true,
            network = ready.network, networkError = networkError, networkErrorKind = ReadFailureKind.Network) }
        assertSingleNetworkFeedback("校园网信息更新失败，显示上次数据，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("hall-network-card").fetchSemanticsNode().boundsInRoot.top)
        capture("hall-network-cache-failure")
        compose.runOnIdle { state = DormUiState(loaded = true, error = networkError, errorKind = ReadFailureKind.Network,
            network = ready.network, roomsAvailable = false) }
        assertSingleNetworkFeedback("水电费获取失败，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("未绑定").assertDoesNotExist()
        capture("hall-utility-first-failure")
        compose.runOnIdle { state = ready }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("read-notice").assertDoesNotExist()
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
    }

    @Test fun myBalanceReadUsesSkeletonThenTopNoticeUntilBalanceReturns() {
        val settings = ThemeSettings(compose.activity.application)
        var payment by mutableStateOf(PaymentUiState(needsLogin = false, accountLoading = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    MyPage(PreviewData.myState(), payment, settings, 0.dp, false, {},
                        {}, {}, {}, {}, false, {}, false, false, {}, {})
                }
            }
        }
        compose.onNodeWithText("¥ —").assertDoesNotExist()
        compose.runOnIdle { payment = payment.copy(accountLoading = false, accountError = networkError, accountErrorKind = ReadFailureKind.Network) }
        assertSingleNetworkFeedback("余额获取失败，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("my-balance-card").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("my-summary-card").assertExists()
        capture("my-balance-first-failure")
        compose.runOnIdle { payment = payment.copy(accountLoading = true, accountError = null) }
        compose.onNodeWithText("余额获取失败，请下拉刷新").assertIsDisplayed()
        val account = PreviewData.paymentState(false).account!!
        compose.runOnIdle { payment = PaymentUiState(needsLogin = false, account = account, accountError = networkError, accountErrorKind = ReadFailureKind.Network) }
        assertSingleNetworkFeedback("余额更新失败，显示上次数据，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("my-balance-card").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("¥ ${money(account.balanceCents)}").assertIsDisplayed()
        capture("my-balance-cache-failure")
    }

    @Test fun accountDetailFirstFailureReallyPullsToRefreshAndRetainsTheFailureWhileReading() {
        val settings = ThemeSettings(compose.activity.application)
        var payment by mutableStateOf(PaymentUiState(needsLogin = false, accountError = networkError, accountErrorKind = ReadFailureKind.Network))
        var refreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    AccountDetailPage(payment, settings, {}, {}, onRefresh = {
                        refreshes++; payment = payment.copy(accountLoading = true, accountError = null)
                    })
                }
            }
        }
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
        compose.onNodeWithText("未提供").assertDoesNotExist()
        compose.onNode(verticalList).performTouchInput {
            swipe(start = center.copy(y = height * .15f), end = center.copy(y = height * .8f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue(refreshes > 0)
        compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
        val account = PreviewData.paymentState(false).account!!
        compose.runOnIdle { payment = PaymentUiState(needsLogin = false, account = account) }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithText("¥ ${money(account.balanceCents)}").assertIsDisplayed()
    }

    @Test fun billFailedRetryDoesNotMistakeCompletedFailedListForAvailableContent() {
        val settings = ThemeSettings(compose.activity.application)
        var state by mutableStateOf(BillUiState(loaded = true, error = networkError, errorKind = ReadFailureKind.Network,
            summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false) }
            }
        }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(refreshing = true, error = null, summaryError = null, countsError = null) }
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(refreshing = false, summary = BillSummary(0, 0)) }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
    }

    @Test fun cachedBillListSummaryAndCountsFailuresUseOnePageInlineError() {
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(PreviewData.billState().copy(error = networkError, errorKind = ReadFailureKind.Network,
                        summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network),
                        settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        assertSingleNetworkFeedback(cachedOffline)
        capture("bill-cache")
    }

    @Test fun failedSummaryAndCountsUseOneTopNotice() {
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(PreviewData.billState().copy(summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network),
                        settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        assertSingleNetworkFeedback("账单概览更新失败，显示上次数据，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot.top)
        capture("bill-summary-only-network-error")
    }

    @Test fun bothMeterErrorsUseTopNoticeWhenCampusNetworkIsHealthy() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        val room = ready.rooms.single()
        val failed = DormUiState(rooms = listOf(DormRoom(room.project, room.binding,
            room.meters.map { DormMeter(it.kind, it.room, it.query, networkError, it.project, errorKind = ReadFailureKind.Network) }, room.updatedAtMillis)),
            network = ready.network, loaded = true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { HallPage(settings, 0.dp, dorm = failed) } }
        }
        assertSingleNetworkFeedback("水电费更新失败，显示上次数据，请下拉刷新")
        assertTrue(compose.onNodeWithTag("page-network-error").fetchSemanticsNode().boundsInRoot.bottom <=
            compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top)
        room.meters.forEach { compose.onNodeWithText(it.readings.first().value).assertIsDisplayed() }
        capture("hall-utility-only-network-error")
    }

    @Test fun multipleFailedUnreadRegionsUseOneTopNoticeAndKeepValuesUnknown() {
        val settings = ThemeSettings(compose.activity.application)
        val my = PreviewData.myState().copy(summary = null, counts = null,
            summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network)
        val payment = PaymentUiState(needsLogin = false, accountError = networkError, accountErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { MyPage(my, payment, settings, 0.dp, false, {}, {}, {}, {}, {},
                    false, {}, false, false, {}, {}) }
            }
        }
        assertSingleNetworkFeedback("余额、本月概览获取失败，请下拉刷新")
        compose.onNodeWithText("¥ 0.00").assertDoesNotExist()
        capture("my-partial-unread-network-error")
    }

    private fun assertSingleNetworkFeedback(message: String = FeedbackText.PageOffline) {
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(0)
        compose.onAllNodesWithTag("read-unavailable").assertCountEquals(0)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onAllNodesWithText(message).assertCountEquals(1)
        compose.onNodeWithTag("page-network-error").assert(!hasClickAction())
        compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("page-network-error"))).assertDoesNotExist()
    }

    @Test fun cachedMyIdentityBalanceAndSummaryNetworkFailuresShareOneInlineError() {
        val settings = ThemeSettings(compose.activity.application)
        val my = PreviewData.myState().copy(profileError = networkError, profileErrorKind = ReadFailureKind.Network, avatarError = networkError, avatarErrorKind = ReadFailureKind.Network,
            homeIdentityError = networkError, homeIdentityErrorKind = ReadFailureKind.Network, summaryError = networkError, summaryErrorKind = ReadFailureKind.Network, countsError = networkError, countsErrorKind = ReadFailureKind.Network)
        val payment = PreviewData.paymentState(false).copy(accountError = networkError, accountErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { MyPage(my, payment, settings, 0.dp, false, {}, {}, {}, {}, {},
                    false, {}, false, false, {}, {}) }
            }
        }
        assertSingleNetworkFeedback("资料、余额、本月概览更新失败，显示上次数据，请下拉刷新")
        compose.onNodeWithText("¥ ${money(payment.account!!.balanceCents)}").assertIsDisplayed()
        compose.onNodeWithTag("my-summary-card").assertExists()
        capture("my-cache-network-error")
    }

    @Test fun paymentNetworkFailureKeepsBalanceAndUsesCenteredPaymentStatus() {
        val settings = ThemeSettings(compose.activity.application)
        var payment by mutableStateOf(PreviewData.paymentState(false).copy(error = networkError, errorKind = ReadFailureKind.Network))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                AppTheme { PaymentPage(payment, settings, 0.dp, {}) }
            }
        }
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithTag("payment-status-group").assertIsDisplayed()
        compose.onNodeWithTag("payment-status-group").assert(hasAnyAncestor(hasTestTag("payment-code-card")))
        compose.onNodeWithText("付款码暂不可用").assertIsDisplayed()
        compose.onAllNodesWithText(FeedbackText.PageOffline).assertCountEquals(1)
        compose.onNodeWithContentDescription("付款条形码").assertDoesNotExist()
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        compose.onNodeWithText("解挂").assertDoesNotExist()
        compose.onNodeWithTag("payment-code-card").assertExists()
        compose.onNodeWithText("¥ ${money(payment.account!!.balanceCents)}").assertIsDisplayed()
        capture("payment-cache-network-error")
        compose.runOnIdle { payment = payment.copy(busy = true, refreshing = true, error = null, errorKind = null, code = null) }
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithTag("payment-status-group").assertIsDisplayed()
        compose.onNodeWithText("正在确认卡状态").assertIsDisplayed()
        compose.onNodeWithText("¥ ${money(payment.account!!.balanceCents)}").assertIsDisplayed()
        compose.runOnIdle { payment = payment.copy(busy = false, refreshing = false, error = networkError, errorKind = ReadFailureKind.Network) }
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        compose.onNodeWithText("付款码暂不可用").assertIsDisplayed()
    }

    @Test fun pageNetworkNoticeMovesCachedContentGraduallyAndLeavesNoGapAfterRecovery() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        var state by mutableStateOf(ready)
        val failed = DormUiState(rooms = ready.rooms, network = ready.network, loaded = true,
            error = networkError, errorKind = ReadFailureKind.Network, networkError = networkError, networkErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) { HallPage(settings, 0.dp, dorm = state) }
            }
        }
        fun cardTop() = compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top
        val before = cardTop()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = failed }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val start = cardTop()
        compose.mainClock.advanceTimeBy(64)
        val entering = cardTop()
        capture("hall-network-notice-entering")
        compose.mainClock.advanceTimeBy(240)
        val shown = cardTop()
        assertEquals("Inserting the notice must not jump the first card", before, start, 1f)
        assertTrue("The notice and its spacing should expand gradually", entering > before && entering < shown)
        compose.onNodeWithText(cachedOffline).assertIsDisplayed()

        compose.runOnIdle { state = ready }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        capture("hall-network-notice-exiting")
        val exiting = cardTop()
        assertTrue("Recovery should shrink the notice gradually: before=$before, shown=$shown, exiting=$exiting",
            exiting > before && exiting < shown)
        compose.onNodeWithText(cachedOffline).assertExists()
        // A new failure while the old notice exits must resume without an empty banner.
        compose.runOnIdle { state = failed }
        compose.waitForIdle()
        // Compose uses a spring when an in-flight size transition reverses; let it settle.
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(shown, cardTop(), 1f)
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.runOnIdle { state = ready }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(320)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        assertEquals("Recovery must leave no reserved notice row or spacing", before, cardTop(), 1f)
    }

    @Test fun cachedHallNoticeStaysStillAcrossReactivationAndRepeatedOfflineFailures() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        val failed = DormUiState(rooms = ready.rooms, network = ready.network, loaded = true,
            error = networkError, errorKind = ReadFailureKind.Network, networkError = networkError, networkErrorKind = ReadFailureKind.Network)
        val pending = DormUiState(rooms = ready.rooms, network = ready.network, loaded = true, loading = true)
        var state by mutableStateOf(failed)
        var active by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    LaunchedEffect(active) { if (active) state = pending }
                    HallPage(settings, 0.dp, dorm = state, isActive = active)
                }
            }
        }
        fun cardTop() = compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top
        val original = cardTop()
        compose.mainClock.autoAdvance = false
        for (attempt in 1..2) {
            compose.runOnIdle { active = true }
            compose.waitForIdle()
            for (step in 1..5) {
                compose.mainClock.advanceTimeBy(64)
                assertEquals("Retry must retain the cached notice in frame $step", original, cardTop(), 1f)
                assertSingleNetworkFeedback(cachedOffline)
            }
            compose.runOnIdle { state = failed }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(320)
            assertEquals("The same failure must not animate again", original, cardTop(), 1f)
            compose.runOnIdle { active = false }
            compose.waitForIdle()
        }
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle { state = ready }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        val exiting = cardTop()
        assertTrue("A successful retry must animate the notice out", exiting < original)
        compose.mainClock.advanceTimeBy(320)
        assertTrue(cardTop() < exiting)
        compose.onNodeWithTag("page-network-error").assertDoesNotExist()
        capture("hall-network-notice-recovered")
    }

    @Test fun networkAggregationKeepsIndependentBusinessFailureVisible() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.hallState()
        val room = ready.rooms.single()
        val failed = DormUiState(rooms = listOf(DormRoom(room.project, room.binding,
            room.meters.map { DormMeter(it.kind, it.room, it.query, networkError, it.project, errorKind = ReadFailureKind.Network) }, room.updatedAtMillis)),
            network = ready.network, loaded = true,
            error = "网络异常套餐不允许解绑，请核实", errorKind = ReadFailureKind.Other,
            networkError = networkError, networkErrorKind = ReadFailureKind.Network)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { HallPage(settings, 0.dp, dorm = failed) }
            }
        }
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        compose.onNodeWithText("暂时无法更新，显示上次数据").assertIsDisplayed()
    }

    @Test fun cachedStatisticsNetworkFailuresShareOneInlineErrorInLargeDarkText() {
        val settings = ThemeSettings(compose.activity.application)
        val ready = PreviewData.billState()
        val stats = PreviewData.billStats(ready.dateFilter, 2).copy(dailyError = networkError, dailyErrorKind = ReadFailureKind.Network,
            categoriesError = networkError, categoriesErrorKind = ReadFailureKind.Network, rankingError = networkError, rankingErrorKind = ReadFailureKind.Network)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme(ColorSchemeMode.Dark) {
                    BillPage(ready.copy(statsVisible = true, stats = stats), settings, {}, {}, {}, {},
                        { _, _, _ -> }, {}, {}, {}, showBack = false)
                }
            }
        }
        assertSingleNetworkFeedback(cachedOffline)
        compose.onNodeWithTag("bill-stats-overview").assertIsDisplayed()
        capture("stats-cache-network-large-dark")
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

    private enum class SkeletonSheetPage { Amount }

    @Test fun sheetPlaceholdersPaintImmediatelyWhilePageSkeletonsStillWait() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                Column {
                    SheetPageContent(SkeletonSheetPage.Amount, Modifier.size(100.dp).testTag("sheet-skeleton")) { _, _ ->
                        ContentPlaceholder(Modifier.fillMaxSize(), color = Color.Black)
                    }
                    Box(Modifier.size(100.dp).testTag("page-skeleton")) {
                        InitialReadTransition(null) { ContentPlaceholder(Modifier.fillMaxSize(), color = Color.Black) }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        val before = compose.onRoot().captureToImage().asAndroidBitmap()
        val sheet = compose.onNodeWithTag("sheet-skeleton").fetchSemanticsNode().boundsInRoot.center
        val page = compose.onNodeWithTag("page-skeleton").fetchSemanticsNode().boundsInRoot.center
        assertEquals(android.graphics.Color.BLACK, before.getPixel(sheet.x.toInt(), sheet.y.toInt()))
        assertEquals(android.graphics.Color.WHITE, before.getPixel(page.x.toInt(), page.y.toInt()))
        compose.mainClock.advanceTimeBy(500)
        val after = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(android.graphics.Color.BLACK, after.getPixel(page.x.toInt(), page.y.toInt()))
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
