package com.mercury.yktwx

import android.graphics.Matrix
import com.mercury.yktwx.ui.components.devicePreviewTransform
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DevicePreviewTransformTest {
    @Test fun sensorRotationIsNotAppliedTwiceAndDisplayRotationKeepsAspectAndFillsView() {
        for (sensor in listOf(0, 90, 270)) for (display in listOf(0, 90, 180, 270)) {
            val width = if (display % 180 == 0) 411 else 839
            val height = if (display % 180 == 0) 839 else 411
            val matrix = devicePreviewTransform(width, height, 640, 480, sensor, display)
            val values = FloatArray(9).also(matrix::getValues)
            // In native portrait orientation, camera output must remain upright.
            if (display == 0) {
                assertEquals(0f, values[Matrix.MSKEW_X], .001f)
                assertEquals(0f, values[Matrix.MSKEW_Y], .001f)
                assertTrue(values[Matrix.MSCALE_X] > 0)
                assertTrue(values[Matrix.MSCALE_Y] > 0)
            }
            val points = floatArrayOf(0f, 0f, width.toFloat(), 0f, 0f, height.toFloat(), width / 2f, height / 2f)
            matrix.mapPoints(points)
            assertEquals(width / 2f, points[6], .001f)
            assertEquals(height / 2f, points[7], .001f)
            val nativeWidth = if (sensor % 180 == 0) 640 else 480
            val nativeHeight = if (sensor % 180 == 0) 480 else 640
            val xLength = kotlin.math.hypot(points[2] - points[0], points[3] - points[1])
            val yLength = kotlin.math.hypot(points[4] - points[0], points[5] - points[1])
            assertEquals(xLength / nativeWidth, yLength / nativeHeight, .001f)
            assertTrue(abs(points[2] - points[0]) + abs(points[4] - points[0]) >= width - .01f)
            assertTrue(abs(points[3] - points[1]) + abs(points[5] - points[1]) >= height - .01f)
            if (display == 90) assertTrue(points[3] < points[1])
            if (display == 180) assertTrue(points[2] < points[0])
            if (display == 270) assertTrue(points[3] > points[1])
        }
    }
}
