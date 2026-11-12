package com.mercury.yktwx

import com.mercury.yktwx.data.yktApiFixture
import com.mercury.yktwx.ui.themeSettingsFixture
import com.mercury.yktwx.data.accountRepositoryFixture
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.ProfileAvatar
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private fun frames(count: Int = 20) { repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }

    @Test fun statisticsReuseShowsNoProgressUntilExpiredAndKeepsTheModeTabsStill() {
        var now = 10_000L
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var expenseCents = 800L
        val service = object : YktService by yktApiFixture({ error("Unexpected live request") }) {
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?) =
                BillPageData(emptyList(), 1, 0)
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(0, 0)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = 0
            override suspend fun billTypes(token: String) = emptyList<BillType>()
            override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> {
                gate.await(); return PreviewData.billStats(filter, type).daily
            }
            override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
                gate.await(); return listOf(BillCategory(if (type == 2) "支出" else "收入", if (type == 2) expenseCents else 1200))
            }
            override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
                gate.await(); return emptyList()
            }
        }
        val vault = object : Vault {
            override fun credentials() = Credentials("fictional-user", "fictional-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val model = billViewModelFixture(compose.activity.application, accountRepositoryFixture(service, vault), { now })
        val store = ViewModelStore().apply { put("bill", model) }
        val settings = themeSettingsFixture(compose.activity.application)
        fun selectTab(label: String) { compose.onNode(hasText(label) and hasClickAction()).performClick() }
        fun waitForRead(stats: Boolean) {
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                if (stats) model.state.value.stats.loaded && !model.state.value.stats.loading
                else model.state.value.loaded && !model.state.value.refreshing
            }
            compose.waitForIdle()
        }
        try {
            model.initialize(); model.refresh()
            compose.setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AppTheme { BillPage(state, settings, {}, model::refresh,
                        model::loadMore, model::selectDate, model::applyFilters, model::search,
                        model::openStats, model::openDetail, onStatsTypeChange = model::selectStatsType) }
                }
            }
            waitForRead(false)
            selectTab("统计"); waitForRead(true)
            val expenseAmount = compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()
            selectTab("收入"); waitForRead(true)
            val incomeAmount = compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()
            compose.mainClock.autoAdvance = false
            val tabs = compose.onNodeWithTag("bill-mode-tabs")
            val pixels = tabs.captureToImage().asAndroidBitmap()
            val tabNode = tabs.fetchSemanticsNode()
            selectTab("支出")
            repeat(25) {
                frames(1)
                compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
                assertTrue(pixels.sameAs(tabs.captureToImage().asAndroidBitmap()))
            }
            assertTrue(pixels.sameAs(tabs.captureToImage().asAndroidBitmap()))
            assertTrue("Cached expenses settle at the final amount without a second number animation",
                expenseAmount.sameAs(compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()))
            assertEquals(800L, model.state.value.stats.categories.single().amountCents)
            selectTab("收入")
            repeat(25) {
                frames(1)
                compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            }
            assertTrue("Cached income settles at the final amount without a second number animation",
                incomeAmount.sameAs(compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()))
            compose.runOnIdle { now += 60_000; expenseCents = 900; gate = CompletableDeferred() }
            selectTab("支出")
            frames(25)
            compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
            assertEquals(800L, model.state.value.stats.categories.single().amountCents)
            // Revalidation paints progress in the existing gap without recreating or moving the tabs.
            val refreshingTabs = tabs.fetchSemanticsNode()
            assertEquals(tabNode.id, refreshingTabs.id)
            assertEquals(tabNode.boundsInRoot.width, refreshingTabs.boundsInRoot.width, 1f)
            assertEquals(tabNode.boundsInRoot.height, refreshingTabs.boundsInRoot.height, 1f)
            assertEquals(tabNode.boundsInRoot.top, refreshingTabs.boundsInRoot.top, 1f)
            compose.runOnIdle { gate.complete(Unit) }; waitForRead(true)
            frames(2)
            val updating = compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()
            compose.onNodeWithTag("bill-stats-amount").assertTextEquals("¥ 9.00")
            frames(25)
            assertFalse("A real amount update within the same query must still animate",
                updating.sameAs(compose.onNodeWithTag("bill-stats-amount").captureToImage().asAndroidBitmap()))
        } finally {
            gate.complete(Unit); store.clear(); compose.mainClock.autoAdvance = true
        }
    }

    @Test fun cachedStatisticsTypeSwitchKeepsModeTabsUnchangedInEveryFrame() {
        var state by mutableStateOf(PreviewData.billState().copy(statsVisible = true))
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {},
                    onStatsTypeChange = { state = state.copy(stats = BillStatsState(period = state.stats.period, type = it, loading = true)) }) }
            }
        }
        compose.mainClock.autoAdvance = false
        val tabs = compose.onNodeWithTag("bill-mode-tabs")
        val baseline = tabs.captureToImage().asAndroidBitmap()
        val bounds = tabs.fetchSemanticsNode().boundsInRoot
        val nodeId = tabs.fetchSemanticsNode().id
        for (type in listOf(1, 2, 1, 2)) {
            compose.onNodeWithText(if (type == 1) "收入" else "支出").performClick()
            frames(2)
            compose.runOnIdle {
                state = state.copy(stats = PreviewData.billStats(state.stats.period, type).copy(displayRestored = true))
            }
            repeat(16) { frame ->
                frames(1)
                compose.onAllNodesWithTag("bill-mode-tabs").assertCountEquals(1)
                assertEquals(nodeId, tabs.fetchSemanticsNode().id)
                assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
                assertTrue("Mode selector must stay opaque and still during type $type at frame $frame",
                    baseline.sameAs(tabs.captureToImage().asAndroidBitmap()))
            }
            compose.runOnIdle { state = state.copy(stats = state.stats.copy(loading = false)) }
            frames(30)
            assertEquals(bounds, tabs.fetchSemanticsNode().boundsInRoot)
            assertTrue("Mode tabs settle at their original appearance after type $type",
                baseline.sameAs(tabs.captureToImage().asAndroidBitmap()))
        }
    }

    @Test fun statisticsTabsSurviveRapidPendingEmptyAndLoadedTransitions() {
        var state by mutableStateOf(PreviewData.billState().copy(statsVisible = true))
        val period = state.dateFilter
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state = state, settings = settings, onBack = {},
                    onRefresh = {}, onLoadMore = {}, onDateSelected = {}, onFiltersApplied = { _, _, _ -> },
                    onSearch = {}, onOpenStats = {}, onOpenDetail = {},
                    onStatsTypeChange = { state = state.copy(stats = BillStatsState(period = period, type = it, loading = true)) }) }
            }
        }
        compose.mainClock.autoAdvance = false
        repeat(4) { iteration ->
            compose.onNodeWithText("收入").performClick()
            frames(2)
            compose.runOnIdle {
                state = state.copy(stats = state.stats.copy(daily = listOf(BillDailyPoint(period.start, 300)),
                    categories = listOf(BillCategory("充值", 300)), dailyLoaded = true, categoriesLoaded = true, loaded = true, loading = false))
            }
            frames(2)
            compose.onNodeWithText("支出").performClick()
            frames(2)
            compose.runOnIdle { state = state.copy(stats = state.stats.copy(dailyLoaded = true, categoriesLoaded = true, loaded = true, loading = false)) }
            frames(2)
            compose.runOnIdle { state = state.copy(stats = state.stats.copy(daily = listOf(BillDailyPoint(period.start, 100)), categories = listOf(BillCategory("餐饮", 100)))) }
            frames(2)
            // A query/period change during an unfinished transition must drop the previous geometry.
            if (iteration == 3) compose.runOnIdle { state = state.copy(stats = BillStatsState(period = BillDateFilter.year(2026), type = 2, loading = true)) }
        }
        frames()
        compose.onNodeWithTag("bill-stats-trend").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun directChartHandlesEmptyToSinglePointAndInterruptedDataChanges() {
        var points by mutableStateOf(emptyList<BillDailyPoint>())
        var categories by mutableStateOf(emptyList<BillCategory>())
        compose.setContent { AppTheme { Column { DailyBillChart(points, BillDateMode.Month); CategoryDonutChart(categories, 2) } } }
        compose.mainClock.autoAdvance = false
        val date = java.time.LocalDate.of(2026, 10, 1)
        repeat(5) {
            compose.runOnIdle { points = listOf(BillDailyPoint(date, 100)); categories = listOf(BillCategory("餐饮", 100)) }
            frames(1)
            compose.runOnIdle { points = listOf(BillDailyPoint(date, 900), BillDailyPoint(date.plusDays(1), 300)); categories = listOf(BillCategory("超市", 400)) }
            frames(2)
            compose.runOnIdle { points = emptyList(); categories = emptyList() }
            frames(1)
        }
        frames()
        compose.onNodeWithText("暂无分类数据").assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun unchangedAvatarBytesNeverReturnToPlaceholderOrMoveDuringRefresh() {
        val png = ByteArrayOutputStream().also { stream ->
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }.toByteArray()
        var bytes by mutableStateOf<ByteArray?>(png)
        compose.setContent { AppTheme { Box(Modifier.testTag("avatar")) { ProfileAvatar(bytes, 64.dp) } } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("校园系统头像").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        val initial = compose.onNodeWithTag("avatar").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        repeat(4) {
            compose.runOnIdle { bytes = png.copyOf() }
            frames(2)
            compose.onNodeWithContentDescription("头像").assertDoesNotExist()
            assertEquals(initial, compose.onNodeWithTag("avatar").fetchSemanticsNode().boundsInRoot)
            val image = compose.onNodeWithTag("avatar").captureToImage().asAndroidBitmap()
            assertEquals(android.graphics.Color.RED, image.getPixel(image.width / 2, image.height / 2))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun invalidAvatarFinishesDecodingAndShowsTheFallbackInsteadOfStayingPending() {
        compose.setContent { AppTheme { ProfileAvatar(byteArrayOf(1, 2, 3), 64.dp) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("头像").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription("头像").assertIsDisplayed()
        compose.onNodeWithContentDescription("校园系统头像").assertDoesNotExist()
    }

    @Test fun sharedNumberTransitionsKeepLayoutAndExposeOnlyTheActualValue() {
        var value by mutableStateOf("186.50")
        compose.setContent { AppTheme { UpdatedText(value, Modifier.testTag("number"), prefix = "¥ ", fontSize = 30.sp) } }
        compose.mainClock.autoAdvance = false
        val initial = compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { value = "178.50" }
        frames(2)
        compose.onNodeWithText("¥ 178.50").assertExists()
        compose.onNodeWithText("¥ 186.50").assertDoesNotExist()
        assertEquals(initial.height, compose.onNodeWithTag("number").fetchSemanticsNode().boundsInRoot.height, 1f)
        frames()
        val out = File("../tools/content-motion/number-updated.png").absoluteFile
        out.parentFile?.mkdirs()
        out.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.mainClock.autoAdvance = true
    }
}
