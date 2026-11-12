package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.MyPage
import com.mercury.yktwx.ui.pages.billStatsContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

/** Compare downstream card positions across real loading transitions, including large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun settings(): ThemeSettings {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return themeSettingsFixture(app)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun stable(expected: Rect, tag: String) {
        val actual = bounds(tag)
        assertEquals("$tag moved vertically", expected.top, actual.top, 1f)
        assertEquals("$tag changed height", expected.height, actual.height, 1f)
    }
    private fun capture(name: String) {
        val file = File("../tools/dashboard-loading/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hallKeepsCardPositionsBeforeDuringAndAfterReadingRooms() {
        val settings = settings()
        val ready = PreviewData.hallState()
        var state by mutableStateOf(DormUiState())
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density, scale)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    HallPage(settings, 0.dp, dorm = state)
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale; dark = fontScale > 1f; state = DormUiState() }
            val network = bounds("hall-network-card")
            val others = bounds("hall-others-card")
            compose.onNodeWithText("尚未添加").assertDoesNotExist()
            if (fontScale == 1f) capture("hall-initial")
            compose.runOnIdle { state = DormUiState(loading = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.runOnIdle { state = DormUiState(loading = true, network = ready.network, networkLoaded = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            val partialRoom = ready.rooms.single()
            compose.runOnIdle { state = DormUiState(listOf(DormRoom(partialRoom.project, partialRoom.binding,
                partialRoom.meters.takeLast(1), 0,
                pendingMeters = listOf(DormMeterSlot(partialRoom.meters.first().kind, partialRoom.project)))),
                loading = true, network = ready.network, networkLoaded = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.onNodeWithText("尚未添加").assertDoesNotExist()
            if (fontScale == 1f) capture("hall-bound-only")
            compose.runOnIdle { state = ready }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            capture(if (fontScale == 1f) "hall-loaded" else "hall-loaded-large-dark")
            compose.runOnIdle { state = DormUiState(ready.rooms, loading = true, loaded = true, network = ready.network) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("page-read-progress").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            val room = ready.rooms.single()
            compose.runOnIdle { state = DormUiState(listOf(DormRoom(room.project, room.binding, room.meters.take(1), 0)),
                loaded = true, network = ready.network) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            val boundUtility = bounds("hall-utility-card")
            val beforeNetwork = bounds("hall-network-card").top
            val beforeOthers = bounds("hall-others-card").top
            compose.runOnIdle { state = DormUiState(loaded = true) }
            // The unbound panels can need a few more pixels at large font scales. That growth
            // is size-animated by ReadLayout; following cards may only follow its delta.
            val grown = bounds("hall-utility-card").height - boundUtility.height
            assertEquals("Following cards must follow the utility card height", grown,
                bounds("hall-network-card").top - beforeNetwork, 1f)
            assertEquals("Following cards must follow the utility card height", grown,
                bounds("hall-others-card").top - beforeOthers, 1f)
            compose.onNodeWithText("尚未添加").assertExists()
        }
    }

    @Test fun mineKeepsCardPositionsAsIdentityAndAmountsArriveAndRechargeLoads() {
        val settings = settings()
        var state by mutableStateOf(MyUiState())
        var payment by mutableStateOf(PaymentUiState(username = "202600000001"))
        var rechargeLoading by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        val ready = MyUiState(profile = PersonalProfile("示例同学", "202600000001", "示例部门", "示例专业", null),
            homeIdentity = HomeCardIdentity("示例同学", "202600000001", "示例书院", "示例专业"),
            summary = BillSummary(20000, 20540), counts = BillCounts(2, 6), loaded = true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density, scale)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    MyPage(state, payment, settings, 0.dp, false, {}, {}, {}, {}, {}, rechargeLoading, {},
                        false, false, {}, {})
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale; dark = fontScale > 1f; state = MyUiState(); rechargeLoading = false
                payment = PaymentUiState(username = "202600000001") }
            val balance = bounds("my-balance-card")
            val summary = bounds("my-summary-card")
            if (fontScale == 1f) capture("mine-initial")
            compose.runOnIdle { state = MyUiState(loading = true) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            compose.runOnIdle { state = MyUiState(ready.profile, loading = true, profileLoaded = true) }
            compose.onNodeWithText("示例同学").assertExists()
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            compose.runOnIdle { state = state.copy(summary = ready.summary, summaryLoaded = true)
                payment = PaymentUiState(busy = true, account = PaymentAccount("demo", "demo", "1", "统一账户", 18650)) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            if (fontScale == 1f) capture("mine-partial")
            compose.runOnIdle { state = ready
                payment = PaymentUiState(account = PaymentAccount("demo", "demo", "1", "统一账户", 18650)) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            capture(if (fontScale == 1f) "mine-loaded" else "mine-loaded-large-dark")
            compose.runOnIdle { rechargeLoading = true
                state = MyUiState(ready.profile, summary = ready.summary, counts = ready.counts,
                    homeIdentity = ready.homeIdentity, loaded = true, loading = true) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("page-read-progress").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            compose.onNodeWithText("+ 充值").assertIsNotEnabled()
            compose.runOnIdle { state = MyUiState(loaded = true) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
        }
    }

    @Test fun statisticsUseActualChartHeightAndDoNotUseTheTrendAsAnUnfinishedTotal() {
        val period = BillDateFilter.month(java.time.YearMonth.of(2026, 10))
        var state by mutableStateOf(BillStatsState(period = period, loading = true))
        var scale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density, scale)) {
                AppTheme(if (scale > 1f) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        billStatsContent(state, {}, {}, {})
                    }
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale; state = BillStatsState(period = period, loading = true) }
            val overview = bounds("bill-stats-overview")
            val category = bounds("bill-stats-category")
            compose.onNodeWithContentDescription("正在加载统计").assertExists()
            compose.onNodeWithText("暂无分类数据").assertDoesNotExist()
            compose.runOnIdle { state = state.copy(daily = listOf(BillDailyPoint(period.start, 900)), dailyLoaded = true) }
            compose.onNodeWithContentDescription("正在加载统计").assertExists()
            compose.onNodeWithText("¥ 9.00").assertDoesNotExist()
            stable(overview, "bill-stats-overview")
            assertTrue("A real trend must not inherit the hidden placeholder height", bounds("bill-stats-category").top <= category.top)
            if (fontScale == 1f) capture("stats-trend-only")
            compose.runOnIdle { state = state.copy(categories = listOf(BillCategory("餐饮", 700),
                BillCategory("超市", 200), BillCategory("其他", 100)), categoriesLoaded = true) }
            compose.onNodeWithContentDescription("正在加载统计").assertDoesNotExist()
            compose.onAllNodesWithText("¥ 10.00").assertCountEquals(2)
            stable(overview, "bill-stats-overview")
            assertTrue("Loaded categories must not reserve skeleton whitespace", bounds("bill-stats-category").height <= category.height)
            compose.runOnIdle { state = state.copy(daily = emptyList(), categories = emptyList(), rankingLoaded = true,
                loading = false, loaded = true) }
            stable(overview, "bill-stats-overview")
            assertTrue("Empty categories must shrink to their content", bounds("bill-stats-category").height < category.height)
            compose.onAllNodesWithText("¥ 0.00").assertCountEquals(2)
            capture(if (fontScale == 1f) "stats-empty" else "stats-empty-large-dark")
        }
    }
}
