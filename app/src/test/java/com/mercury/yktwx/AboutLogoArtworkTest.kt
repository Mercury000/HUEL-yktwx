package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutLogoArtworkTest {
    private fun renderMask(): Bitmap {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = requireNotNull(ContextCompat.getDrawable(context, R.drawable.about_logo_mark))
        return Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
    }

    @Test fun roundedBadgeAndFoldedHouseSurviveAlphaMasking() {
        val bitmap = renderMask()
        // Inverted launcher alpha preserves the QR windows and roof separation.
        assertEquals(255, Color.alpha(bitmap.getPixel(54, 16)))
        assertEquals(255, Color.alpha(bitmap.getPixel(16, 54)))
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertEquals(255, Color.alpha(bitmap.getPixel(45, 59)))
        assertEquals(0, Color.alpha(bitmap.getPixel(46, 36)))
        assertEquals(0, Color.alpha(bitmap.getPixel(67, 68)))
    }

    @Test fun glassArtworkContainsOnlyWhiteMaskPixels() {
        val bitmap = renderMask()
        var visiblePixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) == 0) continue
                visiblePixels++
                assertEquals("Mask RGB at ($x, $y)", Color.WHITE, pixel or 0xFF000000.toInt())
            }
        }
        assertTrue("Badge should occupy most of the artwork, not only its center", visiblePixels > 7000)
    }
}
