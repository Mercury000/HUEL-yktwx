package com.mercury.yktwx

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.nav.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerDeviceNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stack = navBackStackOf(AppRoute.Main)
    private var style by mutableIntStateOf(0)
    private var swipeEnabled by mutableStateOf(true)
    private var origin by mutableStateOf(LifeEntryOrigin.Scanner)
    private val device get() = compose.onNodeWithTag("device")
    private val main get() = compose.onNodeWithTag("main")
    private val routes = listOf(
        AppRoute.LifeDevice(deviceId = "water"),
        AppRoute.LifeDevice(deviceId = "shower"),
        AppRoute.LifeDevice(provider = LifeProvider.Hui798, deviceId = "hui798-water"),
        AppRoute.LifeDevice(provider = LifeProvider.Hellobike, feature = LifeFeature.Ride, deviceId = "bike"),
    )

    @Before fun setup() {
        compose.setContent {
            AppTheme {
                val pageTransition = miuixNavigationTransition(style)
                val lifeTransition = remember(pageTransition, origin) {
                    when (origin) {
                        LifeEntryOrigin.Scanner, LifeEntryOrigin.Restored -> scannerNavigationTransition(pageTransition)
                        LifeEntryOrigin.User -> pageTransition
                    }
                }
                val swipeDirection = if (swipeEnabled) NavSwipeDirection.LeftToRight else NavSwipeDirection.None
                NavDisplay(stack, onBack = { stack.removeLastOrNull() }, transition = pageTransition) {
                    appEntries(swipeDirection, lifeTransition) { route ->
                        Box(Modifier.fillMaxSize().background(if (route == AppRoute.Main) Color.Blue else Color.Red)
                            .testTag(if (route == AppRoute.Main) "main" else "device"))
                    }
                }
            }
        }
    }

    private fun left(node: SemanticsNodeInteraction): Float =
        node.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).x

    private fun drawnWidth(): Float {
        val coordinates = device.fetchSemanticsNode().layoutInfo.coordinates
        return coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), 0f)).x -
            coordinates.localToRoot(Offset.Zero).x
    }

    private fun open(selectedStyle: Int, route: AppRoute.LifeDevice = routes.first(),
        entryOrigin: LifeEntryOrigin = LifeEntryOrigin.Scanner) {
        compose.runOnIdle { style = selectedStyle; origin = entryOrigin; stack.add(route) }
        advanceFrames(600)
        compose.waitForIdle()
    }

    private fun advanceFrames(millis: Int) {
        // Deliver Android layout and snapshot changes between Compose animation frames.
        repeat((millis + 15) / 16) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    private fun startBack() {
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.apply {
                dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            }
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    @Test fun scannerArrivalShowsTheDeviceWithoutMovingEitherPage() {
        for (selectedStyle in 0..1) {
            compose.runOnIdle { style = selectedStyle; origin = LifeEntryOrigin.Scanner }
            val originalLeft = left(main)
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { stack.add(routes.first()) }
                advanceFrames(64)
                device.assertIsDisplayed()
                assertEquals("The scanner window already animates the arrival", originalLeft, left(device), 1f)
                assertEquals("The underlying page must not start another slide", originalLeft, left(main), 1f)
                advanceFrames(160)
                assertEquals(originalLeft, left(device), 1f)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.runOnIdle { stack.removeLastOrNull() }
            compose.waitForIdle()
        }
    }

    @Test fun islandArrivalUsesThePageTransition() {
        for (selectedStyle in 0..1) {
            compose.runOnIdle { style = selectedStyle; origin = LifeEntryOrigin.User }
            val originalLeft = left(main)
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnIdle { stack.add(routes.first()) }
                advanceFrames(64)
                device.assertIsDisplayed()
                assertTrue("An island entry must animate the device page: style=$selectedStyle",
                    left(device) - originalLeft > 1f)
            } finally {
                compose.mainClock.autoAdvance = true
            }
            advanceFrames(600)
            compose.waitForIdle()
            compose.runOnIdle { stack.removeLastOrNull() }
            compose.waitForIdle()
        }
    }

    @Test fun ordinaryBackAnimatesEveryDeviceRouteInBothStyles() {
        for (selectedStyle in 0..1) for (entryOrigin in listOf(LifeEntryOrigin.User, LifeEntryOrigin.Scanner))
            for (route in routes) {
                open(selectedStyle, route, entryOrigin)
                val originalLeft = left(device)
                val width = drawnWidth()
                compose.mainClock.autoAdvance = false
                try {
                    compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                    advanceFrames(144)
                    val distance = left(device) - originalLeft
                    assertTrue("Device return must move before it completes: $selectedStyle/$entryOrigin/$route",
                        distance > 1f)
                    assertTrue("The departing device must remain visible during return", distance < width)
                } finally {
                    compose.mainClock.autoAdvance = true
                }
                compose.waitForIdle()
                device.assertDoesNotExist()
                main.assertIsDisplayed()
                compose.runOnIdle { assertEquals(listOf(AppRoute.Main), stack.toList()) }
            }
    }

    @Test fun predictiveBackAnimatesCancelsAndCommitsInBothStyles() {
        for (selectedStyle in 0..1) for (entryOrigin in listOf(LifeEntryOrigin.User, LifeEntryOrigin.Scanner)) {
            open(selectedStyle, entryOrigin = entryOrigin)
            val originalLeft = left(device)
            val originalWidth = drawnWidth()
            compose.mainClock.autoAdvance = false
            try {
                startBack()
                if (selectedStyle == 0) {
                    assertEquals(originalWidth * .5f, left(device) - originalLeft, 1f)
                } else {
                    assertTrue("AOSP back must shrink the device into a card", drawnWidth() < originalWidth * .95f)
                }
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            assertEquals(originalLeft, left(device), 1f)
            assertEquals(originalWidth, drawnWidth(), 1f)
            compose.runOnIdle { assertEquals(routes.first(), stack.last()) }
            compose.mainClock.autoAdvance = false
            try {
                startBack()
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            device.assertDoesNotExist()
            main.assertIsDisplayed()
        }
    }

    @Test fun deviceSideSwipeFollowsTheSwipeBackSetting() {
        for (selectedStyle in 0..1) {
            compose.runOnIdle { swipeEnabled = false }
            open(selectedStyle)
            device.assertIsDisplayed()
            fun swipe() {
                compose.onRoot().performTouchInput {
                    swipe(start = center.copy(x = width * .1f), end = center.copy(x = width * .9f), durationMillis = 400)
                }
                advanceFrames(600)
            }
            swipe()
            compose.runOnIdle { assertEquals("Disabled side swipe must keep the device: style=$selectedStyle", routes.first(), stack.last()) }
            device.assertIsDisplayed()
            compose.runOnIdle { swipeEnabled = true }
            advanceFrames(32)
            swipe()
            device.assertDoesNotExist()
            main.assertIsDisplayed()
        }
    }
}
