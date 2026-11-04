package com.mercury.yktwx

import android.graphics.Rect
import android.util.Range
import com.mercury.yktwx.ui.components.DeviceCameraZoom
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceCameraZoomTest {
    @Test fun pinchUsesCameraRatioRangeAndStopsAtBothLimits() {
        val zoom = DeviceCameraZoom(Rect(0, 0, 4000, 3000), Range(.5f, 8f), 4f)
        assertTrue(zoom.usesZoomRatio)
        assertEquals(1f, zoom.ratio, 0f)
        assertTrue(zoom.scaleBy(2f))
        assertEquals(2f, zoom.ratio, 0f)
        assertTrue(zoom.scaleBy(10f))
        assertEquals(8f, zoom.ratio, 0f)
        assertFalse(zoom.scaleBy(2f))
        assertTrue(zoom.scaleBy(.01f))
        assertEquals(1f, zoom.ratio, 0f)
        assertFalse(zoom.scaleBy(.5f))
        assertNull(zoom.cropRegion())
    }

    @Test fun fallbackCropStaysCenteredInOffsetSensorCoordinates() {
        val bounds = Rect(100, 200, 4100, 3200)
        val zoom = DeviceCameraZoom(bounds, maxDigitalZoom = 4f)
        assertFalse(zoom.usesZoomRatio)
        assertEquals(bounds, zoom.cropRegion())
        assertTrue(zoom.scaleBy(2f))
        assertEquals(Rect(1100, 950, 3100, 2450), zoom.cropRegion())
        assertTrue(zoom.scaleBy(10f))
        assertEquals(Rect(1600, 1325, 2600, 2075), zoom.cropRegion())
        assertTrue(zoom.scaleBy(.01f))
        assertEquals(bounds, zoom.cropRegion())
    }

    @Test fun camerasWithoutZoomOrUsableSensorBoundsStayAtOne() {
        for (zoom in listOf(DeviceCameraZoom(), DeviceCameraZoom(maxDigitalZoom = 4f),
            DeviceCameraZoom(Rect(), maxDigitalZoom = 4f),
            DeviceCameraZoom(Rect(0, 0, 4000, 3000), maxDigitalZoom = 1f))) {
            assertFalse(zoom.scaleBy(2f))
            assertEquals(1f, zoom.ratio, 0f)
        }
    }

    @Test fun invalidGestureFactorsLeaveExistingZoomIntact() {
        val zoom = DeviceCameraZoom(zoomRatioRange = Range(1f, 8f))
        zoom.scaleBy(2f)
        for (factor in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFalse(zoom.scaleBy(factor))
            assertEquals(2f, zoom.ratio, 0f)
        }
    }

    @Test fun quickZoomTogglesAndRestoresOneAfterPinching() {
        val zoom = DeviceCameraZoom(zoomRatioRange = Range(1f, 8f))
        assertTrue(zoom.toggleQuickZoom())
        assertEquals(2f, zoom.ratio, 0f)
        assertTrue(zoom.toggleQuickZoom())
        assertEquals(1f, zoom.ratio, 0f)
        zoom.scaleBy(3f)
        assertTrue(zoom.toggleQuickZoom())
        assertEquals(1f, zoom.ratio, 0f)
    }

    @Test fun quickZoomRespectsSmallMaximumAndUnsupportedCameras() {
        val zoom = DeviceCameraZoom(Rect(100, 200, 4100, 3200), maxDigitalZoom = 1.5f)
        assertTrue(zoom.toggleQuickZoom())
        assertEquals(1.5f, zoom.ratio, 0f)
        assertTrue(zoom.toggleQuickZoom())
        assertEquals(1f, zoom.ratio, 0f)
        assertFalse(DeviceCameraZoom().toggleQuickZoom())
    }
}
