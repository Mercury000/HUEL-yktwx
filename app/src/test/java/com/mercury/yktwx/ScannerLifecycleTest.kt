package com.mercury.yktwx

import android.Manifest
import android.app.Application
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
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
import com.mercury.yktwx.ui.components.DeviceScanner
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.StreamConfigurationMapBuilder
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScannerLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private enum class Page : NavKey { Main, Scanner }

    @Before fun permission() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadow.extract<ShadowCameraCharacteristics>(characteristics).apply {
            set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
            set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
                StreamConfigurationMapBuilder.newBuilder().addOutputSize(Size(1280, 720))
                    .addOutputSize(ImageFormat.YUV_420_888, Size(1280, 720)).build())
        }
        Shadow.extract<ShadowCameraManager>(application.getSystemService(CameraManager::class.java))
            .addCamera("0", characteristics)
    }

    private fun previews(view: View = compose.activity.window.decorView): List<TextureView> =
        if (view is TextureView) listOf(view)
        else if (view is ViewGroup) (0 until view.childCount).flatMap { previews(view.getChildAt(it)) }
        else emptyList()

    @Test fun previewWaitsForEntryMotionAndReopensAfterLeavingBeforeFirstFrame() {
        val stack = navBackStackOf(Page.Main)
        compose.setContent { AppTheme {
            NavDisplay(stack) {
                entry<Page> { page ->
                    if (page == Page.Scanner) DeviceScanner(stack.lastOrNull() == Page.Scanner, {}, immersive = true,
                        previewStartAllowed = !LocalNavTransitionScope.current.isRunning)
                    else Box(Modifier.fillMaxSize())
                }
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            fun frames(count: Int) = repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            repeat(3) {
                compose.runOnIdle { stack.add(Page.Scanner) }
                frames(3)
                compose.runOnIdle {
                    assertTrue("Do not create camera streams during navigation motion", previews().isEmpty())
                }
                // No camera frame has arrived; leave before the entry animation settles.
                compose.runOnIdle { stack.removeLastOrNull() }
                frames(50)
                compose.runOnIdle { assertTrue(previews().isEmpty()) }
            }
            compose.runOnIdle { stack.add(Page.Scanner) }
            frames(50)
            compose.runOnIdle { assertEquals(1, previews().size) }
            compose.onNodeWithText("正在启动相机…").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun interruptedBackMotionRetainsTheStartedPreview() {
        var allowed by mutableStateOf(false)
        var active by mutableStateOf(true)
        compose.setContent { AppTheme { DeviceScanner(active, {}, immersive = true, previewStartAllowed = allowed) } }
        compose.runOnIdle { assertTrue(previews().isEmpty()); allowed = true }
        lateinit var initial: TextureView
        compose.runOnIdle { initial = previews().single(); allowed = false }
        compose.runOnIdle { assertSame(initial, previews().single()); allowed = true }
        compose.runOnIdle { assertSame(initial, previews().single()); active = false }
        compose.runOnIdle { assertTrue(previews().isEmpty()); active = true }
        compose.runOnIdle { assertNotSame(initial, previews().single()) }
    }

    @Test fun missingFirstFrameTimesOutAndRetryCreatesAFreshPreview() {
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true) } }
        lateinit var initial: TextureView
        compose.runOnIdle { initial = previews().single() }
        compose.mainClock.advanceTimeBy(8_100)
        compose.onNodeWithText("相机启动超时，请重试或输入设备编号").assertExists()
        compose.runOnIdle { assertTrue(previews().isEmpty()) }
        compose.onNodeWithText("重试").performClick()
        compose.onNodeWithText("正在启动相机…").assertExists()
        compose.runOnIdle { assertNotSame(initial, previews().single()) }
    }

    @Test fun cameraClosesInBackgroundAndGetsANewPreviewOnResume() {
        compose.setContent { AppTheme { DeviceScanner(true, {}, immersive = true) } }
        lateinit var initial: TextureView
        compose.runOnIdle { initial = previews().single() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        assertTrue(previews().isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertNotSame(initial, previews().single()) }
    }
}
