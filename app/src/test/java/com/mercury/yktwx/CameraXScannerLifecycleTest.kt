package com.mercury.yktwx

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.DeviceCameraBackend
import com.mercury.yktwx.ui.components.DeviceScanner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraXScannerLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private enum class Page : NavKey { Main, Scanner }
    private val cameras = mutableListOf<Backend>()
    private val factory: (Context) -> DeviceCameraBackend = { Backend().also(cameras::add) }
    private class Backend : DeviceCameraBackend {
        lateinit var events: DeviceCameraBackend.Events
        var view: View? = null
        var closes = 0
        var recognition = 0
        override fun start(events: DeviceCameraBackend.Events) { this.events = events }
        override fun createPreviewView(context: Context): View = FrameLayout(context).also { view = it }
        override fun enableRecognition() { recognition++ }
        override fun torch(enabled: Boolean) {}
        override fun zoomBy(factor: Float) {}
        override fun toggleQuickZoom() {}
        override fun close() { closes++ }
    }
    private fun previews() = cameras.mapNotNull { it.view }.filter { it.isAttachedToWindow }
    private fun advanceCameraTime(millis: Long) {
        // Camera deadlines run on Android's main dispatcher, outside Compose's animation clock.
        compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) }
        compose.waitForIdle()
    }
    @Before fun permission() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
    }
    @Test fun inactiveScannerDoesNotCreateOrLeakACameraBackend() {
        var active by mutableStateOf(false)
        compose.setContent { AppTheme { DeviceScanner(active, {}, immersive = true, cameraBackendFactory = factory) } }
        compose.waitForIdle()
        assertTrue(cameras.isEmpty())
        compose.runOnIdle { active = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, cameras.size)
            assertEquals(1, previews().size)
        }
    }
    @Test fun previewNeverWaitsForNavigationAndRecognitionAvoidsEntryMotion() {
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true,
            recognitionStartAllowed = false, cameraBackendFactory = factory) } }
        compose.runOnIdle {
            assertEquals(1, previews().size)
            assertEquals(0, cameras.single().recognition)
            cameras.single().events.previewReady()
        }
        compose.onNodeWithText("正在启动相机…").assertDoesNotExist()
        advanceCameraTime(1_100)
        compose.runOnIdle { assertEquals(1, cameras.single().recognition) }
        advanceCameraTime(8_100)
        compose.onNodeWithText("相机启动超时，请重试或输入设备编号").assertDoesNotExist()
    }
    @Test fun fiftyQuickEntriesCancelOldOwnersAndTheirCallbacksAndDeadlines() {
        var active by mutableStateOf(true)
        compose.setContent { AppTheme { DeviceScanner(active, {}, immersive = true,
            recognitionStartAllowed = false, cameraBackendFactory = factory) } }
        repeat(50) {
            compose.runOnIdle { active = false }
            compose.runOnIdle { assertEquals(1, cameras.last().closes); active = true }
        }
        compose.runOnIdle {
            assertEquals(51, cameras.size)
            cameras.dropLast(1).forEach {
                it.events.failed("旧相机会话错误")
                it.events.previewReady()
            }
            cameras.last().events.previewReady()
        }
        compose.onNodeWithText("正在启动相机…").assertDoesNotExist()
        compose.onNodeWithText("旧相机会话错误").assertDoesNotExist()
        advanceCameraTime(8_100)
        compose.onNodeWithText("相机启动超时，请重试或输入设备编号").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, cameras.last().closes)
            assertTrue(cameras.dropLast(1).all { it.recognition == 0 && it.closes == 1 })
            assertEquals(1, previews().size)
        }
    }
    @Test fun committedPopClosesTheOwnerWhileExitMotionRetainsItsView() {
        val stack = navBackStackOf(Page.Main)
        compose.setContent { AppTheme {
            NavDisplay(stack) { entry<Page> { page ->
                if (page == Page.Scanner) DeviceScanner(stack.lastOrNull() == Page.Scanner, {}, immersive = true,
                    recognitionStartAllowed = !LocalNavTransitionScope.current.isRunning, cameraBackendFactory = factory)
                else Box(Modifier.fillMaxSize())
            } }
        } }
        compose.runOnIdle { stack.add(Page.Scanner) }
        compose.waitForIdle()
        lateinit var initial: View
        compose.runOnIdle { initial = previews().single(); assertEquals(0, cameras.single().closes) }
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { stack.removeLastOrNull() }
            repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle { assertSame(initial, previews().single()); assertEquals(1, cameras.single().closes) }
            repeat(50) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle { assertTrue(previews().isEmpty()) }
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun recognitionMotionChangesRetainTheReadyCameraAndView() {
        var allowed by mutableStateOf(true)
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true,
            recognitionStartAllowed = allowed, cameraBackendFactory = factory) } }
        lateinit var initial: View
        compose.runOnIdle { initial = previews().single(); cameras.single().events.previewReady(); allowed = false }
        compose.runOnIdle {
            assertSame(initial, previews().single())
            assertEquals(1, cameras.size)
            assertEquals(1, cameras.single().recognition)
            assertEquals(0, cameras.single().closes)
            allowed = true
        }
        compose.runOnIdle { assertSame(initial, previews().single()); assertEquals(1, cameras.single().recognition) }
    }
    @Test fun missingFirstFrameTimesOutAndRetryHasItsOwnStateAndOwner() {
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true, cameraBackendFactory = factory) } }
        advanceCameraTime(8_100)
        compose.onNodeWithText("相机启动超时，请重试或输入设备编号").assertExists()
        compose.runOnIdle { assertTrue(previews().isEmpty()); assertEquals(1, cameras.single().closes) }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("正在启动相机…").assertExists()
        compose.runOnIdle {
            assertEquals(2, cameras.size)
            cameras.first().events.failed("迟到的旧错误")
            cameras.last().events.previewReady()
        }
        compose.onNodeWithText("迟到的旧错误").assertDoesNotExist()
        compose.onNodeWithText("正在启动相机…").assertDoesNotExist()
    }
    @Test fun backgroundClosesTheOwnerAndResumeUsesANewCamera() {
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true, cameraBackendFactory = factory) } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertTrue(previews().isEmpty())
        assertEquals(1, cameras.single().closes)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(2, cameras.size); assertEquals(1, previews().size) }
    }
}
