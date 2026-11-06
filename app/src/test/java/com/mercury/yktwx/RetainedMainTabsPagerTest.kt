package com.mercury.yktwx

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.MainTabsPagerState
import com.mercury.yktwx.ui.RetainedMainTabsPager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetainedMainTabsPagerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val created = IntArray(4)
    private val disposed = IntArray(4)
    private var firstDraw: IntArray? = null
    private lateinit var tabs: MainTabsPagerState

    private fun content(prewarm: Boolean) {
        compose.setContent {
            val pager = rememberPagerState(pageCount = { 4 })
            val scope = rememberCoroutineScope()
            tabs = remember(pager, scope) { MainTabsPagerState(pager, scope) }
            LaunchedEffect(pager.currentPage) { tabs.syncPage() }
            RetainedMainTabsPager(pager, tabs.selectedPage, prewarm && !tabs.isNavigating,
                Modifier.size(300.dp, 200.dp).testTag("pager")) { page ->
                var clicks by remember { mutableIntStateOf(0) }
                DisposableEffect(Unit) {
                    created[page]++
                    onDispose { disposed[page]++ }
                }
                Box(Modifier.fillMaxSize().testTag("root-$page").clickable { clicks++ }
                    .drawWithContent {
                        if (firstDraw == null) firstDraw = created.copyOf()
                        drawContent()
                    }) {
                    BasicText("page $page clicks $clicks")
                }
            }
        }
    }

    @Test fun firstDrawPrecedesOffscreenPreparationAndEachRootIsKept() {
        content(prewarm = true)
        // Layout assertions alone do not request a native draw in Robolectric.
        compose.onNodeWithTag("pager").captureToImage()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitUntil(5_000) { created.all { it == 1 } }
        compose.onNodeWithTag("root-0").assertIsDisplayed()
        compose.runOnIdle {
            assertArrayEquals(intArrayOf(1, 0, 0, 0), firstDraw)
            assertArrayEquals(intArrayOf(1, 1, 1, 1), created)
            assertArrayEquals(IntArray(4), disposed)
        }
    }

    @Test fun navigatingBeforePrewarmAndReversingKeepsRealContentAndRememberedState() {
        content(prewarm = false)
        compose.onNodeWithTag("root-0").performClick()
        compose.runOnIdle { assertArrayEquals(intArrayOf(1, 0, 0, 0), created) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { tabs.animateToPage(3) }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle {
            assertEquals(1, created[3])
            tabs.animateToPage(1)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("root-1").assertIsDisplayed()
        compose.runOnIdle { tabs.animateToPage(0) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("page 0 clicks 1").assertIsDisplayed()
        compose.runOnIdle {
            assertArrayEquals(IntArray(4), disposed)
            assertTrue(created.all { it <= 1 })
            assertEquals(1, created[0])
        }
    }

    @Test fun swipingToAnUnpreparedNeighborShowsItsContentAndKeepsThePreviousPage() {
        content(prewarm = false)
        compose.onNodeWithTag("root-0").performClick()
        compose.onNodeWithTag("pager").performTouchInput { swipeLeft(durationMillis = 300) }
        compose.onNodeWithTag("root-1").assertIsDisplayed()
        compose.runOnIdle { tabs.animateToPage(0) }
        compose.onNodeWithText("page 0 clicks 1").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, created[0])
            assertEquals(1, created[1])
            assertArrayEquals(IntArray(4), disposed)
        }
    }
}
