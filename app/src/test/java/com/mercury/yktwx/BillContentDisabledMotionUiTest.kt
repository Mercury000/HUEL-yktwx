package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.BillQueryMotion
import com.mercury.yktwx.ui.pages.rememberBillQueryMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillContentDisabledMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>(effectContext = object : MotionDurationScale {
        override val scaleFactor = 0f
    })

    @Test fun disabledMotionReplacesQueryResultsWithoutAnOutgoingSnapshot() {
        val base = PreviewData.billState()
        var state by mutableStateOf(base)
        lateinit var motion: BillQueryMotion
        compose.setContent { AppTheme {
            motion = rememberBillQueryMotion(state, rememberLazyListState(), true, false)
        } }
        compose.runOnIdle { state = base.copy(info = "餐厅", items = emptyList(), listLoaded = false) }
        compose.waitForIdle()
        assertEquals(state, motion.displayed)
        assertTrue(motion.accepts(state))
        assertEquals(1f, motion.opacity.value, 0f)
        compose.runOnIdle { state = base.copy(info = "餐厅", items = base.items.take(1)) }
        compose.waitForIdle()
        assertEquals(state, motion.displayed)
        assertFalse(motion.running)
    }

    @Test fun disabledMotionSwitchesImmediatelyWithoutRetainingTheOldContents() {
        var state by mutableStateOf(PreviewData.billState())
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {},
                        onOpenStats = { state = state.copy(statsVisible = true) }, onOpenDetail = {},
                        onShowDetails = { state = state.copy(statsVisible = false) },
                        onStatsTypeChange = { state = state.copy(stats = PreviewData.billStats(state.dateFilter, it)) })
                }
            }
        }
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
        compose.onNodeWithText("收入").performClick()
        compose.onNodeWithText("本月收入合计").assertIsDisplayed()
        compose.onNodeWithText("本月支出合计").assertDoesNotExist()
        compose.onNodeWithText("明细").performClick()
        compose.onNodeWithTag("bill-search").assertIsDisplayed()
        compose.onNodeWithTag("bill-stats-overview").assertDoesNotExist()
    }
}
