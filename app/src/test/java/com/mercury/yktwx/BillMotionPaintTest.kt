package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.BillContentMotion
import com.mercury.yktwx.ui.pages.BillContentTransition
import com.mercury.yktwx.ui.pages.rememberBillContentMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillMotionPaintTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bothOpaqueContentsShareTheMidpointWhileTheControlLayerStaysStill() {
        var state by mutableStateOf(PreviewData.billState())
        lateinit var motion: BillContentMotion
        val composed = mutableSetOf<Boolean>()
        compose.setContent {
            AppTheme {
                motion = rememberBillContentMotion(state, true)
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(60.dp).background(Color.White).testTag("fixed-controls"))
                    BillContentTransition(state.statsVisible, if (state.statsVisible) 1 else 0, motion, Modifier.weight(1f)) { stats ->
                        DisposableEffect(stats) {
                            composed += stats
                            onDispose { composed -= stats }
                        }
                        Box(Modifier.fillMaxSize().background(if (stats) Color.Red else Color.Blue))
                    }
                }
            }
        }
        compose.waitForIdle()
        val controls = compose.onNodeWithTag("fixed-controls")
        val bounds = controls.fetchSemanticsNode().boundsInRoot
        val initial = controls.captureToImage().asAndroidBitmap()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { motion.select(1) { state = state.copy(statsVisible = true) } }
        repeat(11) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        assertTrue("The content must still be moving at halfway", motion.running)
        assertEquals(setOf(false, true), composed)
        assertEquals(bounds, controls.fetchSemanticsNode().boundsInRoot)
        assertTrue(initial.sameAs(controls.captureToImage().asAndroidBitmap()))
        val midway = compose.onRoot().captureToImage().asAndroidBitmap()
        val y = (bounds.bottom + 100).toInt()
        assertEquals("Outgoing paint stays opaque", Color.Blue.toArgb(), midway.getPixel(midway.width / 10, y))
        assertEquals("Incoming paint stays opaque", Color.Red.toArgb(), midway.getPixel(midway.width * 9 / 10, y))
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        assertFalse(motion.running)
        assertEquals("Outgoing page content must be released after movement", setOf(true), composed)
        val final = compose.onRoot().captureToImage().asAndroidBitmap()
        assertEquals(Color.Red.toArgb(), final.getPixel(final.width / 10, y))
    }
}
