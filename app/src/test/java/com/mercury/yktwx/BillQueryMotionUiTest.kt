package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillQueryMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun frame(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun capture(name: String) {
        val file = File("../tools/bill-query-motion/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun filtersExpandReflowAndCollapseWithoutMovingTheSearchField() {
        var state by mutableStateOf(PreviewData.billState())
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                BillPage(state, ThemeSettings(compose.activity.application), {}, {}, {}, {},
                    { type, from, to -> state = state.copy(typeId = type, amountFromCents = from, amountToCents = to) },
                    {}, {}, {})
            } }
        }
        val search = compose.onNodeWithTag("bill-search")
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(typeId = "4", amountToCents = 1000) }
        frame(5)
        val midwayHeight = compose.onNodeWithTag("bill-conditions").fetchSemanticsNode().boundsInRoot.height
        assertTrue(midwayHeight > 0)
        assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
        frame(25)
        val fullHeight = compose.onNodeWithTag("bill-conditions").fetchSemanticsNode().boundsInRoot.height
        assertTrue("The condition slot must expand through intermediate heights", fullHeight > midwayHeight)
        val amount = compose.onNodeWithContentDescription("移除条件：≤ ¥ 10")
        val initialX = amount.fetchSemanticsNode().boundsInRoot.left
        capture("filters")
        compose.runOnIdle { state = state.copy(typeId = null) }
        frame(5)
        val midwayX = amount.fetchSemanticsNode().boundsInRoot.left
        assertTrue(midwayX < initialX)
        frame(25)
        assertTrue("Remaining chips must move gradually into the removed chip's space",
            amount.fetchSemanticsNode().boundsInRoot.left < midwayX)
        amount.performClick()
        frame(5)
        capture("removing-last-filter")
        frame(25)
        compose.onNodeWithContentDescription("移除条件：≤ ¥ 10").assertDoesNotExist()
        assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { state = state.copy(info = "餐厅") }
        frame(25)
        compose.onNodeWithContentDescription("移除条件：搜索：餐厅").assertDoesNotExist()
        assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
        capture("search-only")
        compose.mainClock.autoAdvance = true
    }

    @Test fun interruptedQueriesFadeFromCurrentPaintAndNeverAcceptOldResults() {
        val base = PreviewData.billState()
        var state by mutableStateOf(base)
        lateinit var motion: BillQueryMotion
        compose.setContent { AppTheme {
            motion = rememberBillQueryMotion(state, rememberLazyListState(), true, false)
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = base.copy(info = "餐厅", items = emptyList(), listLoaded = false) }
        frame(5)
        assertTrue("Old results must fade instead of being cleared in one frame", motion.opacity.value in 0f..0.99f)
        assertEquals(base.items, motion.displayed.items)
        assertFalse(motion.accepts(state))
        val opacity = motion.opacity.value
        compose.runOnIdle { state = base.copy(info = "超市", items = emptyList(), listLoaded = false) }
        frame()
        assertTrue("A new search must not restart the old paint at full opacity", motion.opacity.value <= opacity)
        frame(15)
        assertEquals("超市", motion.displayed.info)
        assertTrue(motion.displayed.items.isEmpty())
        compose.runOnIdle { state = base.copy(info = "超市", items = base.items.take(1)) }
        frame(30)
        assertEquals(state.items, motion.displayed.items)
        assertEquals(1f, motion.opacity.value, 0.001f)
        assertTrue(motion.accepts(state))
        compose.mainClock.autoAdvance = true
    }

    @Test fun slowQueryKeepsSearchFocusAndOnlyDisplaysTheLatestResponse() {
        val base = PreviewData.billState()
        var state by mutableStateOf(base)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                BillPage(state, ThemeSettings(compose.activity.application), {}, {}, {}, {}, { _, _, _ -> },
                    { state = base.copy(info = it, items = emptyList(), listLoaded = false,
                        listAvailable = false, loaded = false, listLoading = true, loading = true, listOnlyRead = true) }, {}, {})
            } }
        }
        val field = compose.onNodeWithTag("bill-search")
        val bounds = field.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        field.performTextInput("餐厅")
        frame(40)
        field.assertIsFocused()
        assertEquals(bounds, field.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("正在加载账单").assertIsDisplayed()
        capture("slow-query")
        compose.runOnIdle { state = base.copy(info = "餐厅", items = base.items.take(1), listUpdate = BillListUpdate.Query) }
        frame(25)
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        field.assertIsFocused()
        assertEquals(bounds, field.fetchSemanticsNode().boundsInRoot)
        compose.mainClock.autoAdvance = true
    }
}
