package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillArrivalMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun frames(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun row(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun paint(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        .copy(Bitmap.Config.ARGB_8888, false)

    @Test fun refreshedAndAppendedRecordsDoNotReplayAfterPageDisposalAndLaterUpdatesStillAnimate() {
        val base = PreviewData.billState().let { it.copy(items = it.items.take(1)) }
        val record = base.items.single()
        var state by mutableStateOf(base)
        var showBills by mutableStateOf(true)
        var creations = 0
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                if (showBills) holder.SaveableStateProvider("bill") {
                    DisposableEffect(Unit) { creations++; onDispose {} }
                    BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {})
                }
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("bill-list").performScrollToIndex(2)
        row(record.resume).assertIsDisplayed()
        val settledPage = paint()
        val settledRow = row(record.resume).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        val bounds = row(record.resume).fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        // Publish real update events into an already displayed list. Both insertion
        // styles must still animate once, then survive the pager's saved-state reuse.
        for ((index, update) in listOf(BillListUpdate.Refresh, BillListUpdate.Append, BillListUpdate.Initial).withIndex()) {
            val label = record.resume
            if (index == 2) {
                compose.runOnIdle { showBills = false }
                frames(2)
            }
            compose.runOnIdle {
                val next = record.copy(orderId = "arrival-$index", resume = label)
                state = base.copy(items = listOf(next), listUpdate = update, listUpdateId = index + 1L,
                    animatedOrderIds = setOf(next.stableId))
                showBills = true
            }
            frames(6)
            val entering = paint()
            assertFalse("A fresh $update event must animate its new row", entering.sameAs(settledPage))
            // Leave before the insertion/highlight finishes and return within the
            // original event window. The saved deadline alone cannot prevent replay.
            compose.runOnIdle { showBills = false }
            frames(2)
            compose.onNodeWithTag("bill-list").assertDoesNotExist()
            compose.runOnIdle { showBills = true }
            frames(2)
            assertEquals(index + 2 + if (index == 2) 1 else 0, creations)
            assertEquals("Returning must immediately restore the full row height", bounds, row(label).fetchSemanticsNode().boundsInRoot)
            assertTrue("Returning must not restart $update opacity, lift or highlight", settledRow.sameAs(
                row(label).captureToImage().asAndroidBitmap()))
            frames(4)
            assertTrue("Restored rows must remain static", settledRow.sameAs(row(label).captureToImage().asAndroidBitmap()))
        }
        compose.mainClock.autoAdvance = true
    }
}
