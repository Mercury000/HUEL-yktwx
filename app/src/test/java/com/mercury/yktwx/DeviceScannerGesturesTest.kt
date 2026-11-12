package com.mercury.yktwx

import android.util.Range
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.components.DeviceCameraZoom
import com.mercury.yktwx.ui.components.deviceScannerZoomGestures
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceScannerGesturesTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun doubleTapAndPinchCoexistWithoutPinchTriggeringQuickZoom() {
        val zoom = DeviceCameraZoom(zoomRatioRange = Range(1f, 8f))
        var quickZoomCount = 0
        compose.setContent {
            Box(Modifier.size(240.dp).testTag("preview").deviceScannerZoomGestures(true,
                { zoom.scaleBy(it) }, { quickZoomCount++; zoom.toggleQuickZoom() }))
        }
        val preview = compose.onNodeWithTag("preview")
        preview.performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, quickZoomCount)
            assertEquals(2f, zoom.ratio, 0f)
        }
        preview.performTouchInput {
            advanceEventTime(500)
            pinch(Offset(width * .4f, height / 2f), Offset(width * .6f, height / 2f),
                Offset(width * .1f, height / 2f), Offset(width * .9f, height / 2f))
        }
        compose.runOnIdle {
            assertTrue(zoom.ratio > 2f)
            assertEquals(1, quickZoomCount)
        }
        preview.performTouchInput { advanceEventTime(500); doubleClick() }
        compose.runOnIdle {
            assertEquals(2, quickZoomCount)
            assertEquals(1f, zoom.ratio, 0f)
        }
    }

    @Test fun disabledPreviewIgnoresZoomGestures() {
        var calls = 0
        compose.setContent {
            Box(Modifier.size(240.dp).testTag("preview").deviceScannerZoomGestures(false,
                { calls++ }, { calls++ }))
        }
        compose.onNodeWithTag("preview").performTouchInput {
            doubleClick()
            advanceEventTime(500)
            pinch(Offset(width * .4f, height / 2f), Offset(width * .6f, height / 2f),
                Offset(width * .1f, height / 2f), Offset(width * .9f, height / 2f))
        }
        compose.runOnIdle { assertEquals(0, calls) }
    }
}
