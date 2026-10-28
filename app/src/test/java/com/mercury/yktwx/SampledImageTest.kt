package com.mercury.yktwx

import android.graphics.Bitmap
import com.mercury.yktwx.ui.components.decodeSampledImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SampledImageTest {
    private fun image(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    @Test fun largeAvatarIsSampledNearItsDisplaySize() = runBlocking {
        val decoded = decodeSampledImage(image(2048, 1536), 66, 66, crop = true)!!
        assertEquals(128, decoded.width)
        assertEquals(96, decoded.height)
    }

    @Test fun captchaFitRetainsItsAspectRatioWithoutFullResolution() = runBlocking {
        val decoded = decodeSampledImage(image(1792, 448), 112, 56)!!
        // Fit uses the width constraint: the displayed image occupies 112 x 28 pixels.
        assertEquals(112, decoded.width)
        assertEquals(28, decoded.height)
    }

    @Test fun portraitFitUsesTheHeightConstraint() = runBlocking {
        val decoded = decodeSampledImage(image(448, 1792), 112, 56)!!
        assertEquals(14, decoded.width)
        assertEquals(56, decoded.height)
    }

    @Test fun extremeAspectRatioStillHasABoundedPixelAllocation() = runBlocking {
        val decoded = decodeSampledImage(image(2048, 1024), 1, 1024, crop = true)!!
        assertTrue(decoded.width.toLong() * decoded.height <= 1_048_576)
    }

    @Test fun corruptBytesUseTheExistingPlaceholder() = runBlocking {
        assertNull(decodeSampledImage(byteArrayOf(1, 2, 3), 112, 56))
    }
}
