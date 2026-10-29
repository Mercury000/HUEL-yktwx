package com.mercury.yktwx

import android.graphics.Bitmap
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContentMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun frames(count: Int = 20) { repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }

    @Test fun statisticsTabsSurviveRapidPendingEmptyAndLoadedTransitions() {
        var state by mutableStateOf(PreviewData.billState().copy(statsVisible = true))
        val period = state.dateFilter
        val settings = ThemeSettings(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { BillPage(state = state, settings = settings, onBack = {}, onActivate = {},
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
