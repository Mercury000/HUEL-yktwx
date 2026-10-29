package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import com.mercury.yktwx.data.AvatarCrop
import com.mercury.yktwx.data.AvatarImage
import com.mercury.yktwx.data.LocalAvatarStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarImageTest {
    private fun splitImage() = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
        Canvas(this).drawRect(512f, 0f, 1024f, 512f, Paint().apply { color = Color.BLUE })
    }

    @Test fun exportedCropMatchesThePannedZoomedPreviewAndHasBoundedStorage() {
        val source = splitImage()
        val bytes = AvatarImage.encode(source, AvatarCrop(2f, .75f, .5f))
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(512, image.width); assertEquals(512, image.height)
        assertTrue(bytes.size <= LocalAvatarStore.MaxBytes)
        assertTrue(Color.blue(image.getPixel(20, 256)) > 240)
        assertTrue(Color.red(image.getPixel(490, 256)) < 20)
        image.recycle(); source.recycle()
    }

    @Test fun unadjustedCropIsCenteredAndNeverStretchesTheImage() {
        val source = splitImage()
        val bytes = AvatarImage.encode(source, AvatarCrop())
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertTrue(Color.red(image.getPixel(30, 256)) > 240)
        assertTrue(Color.blue(image.getPixel(480, 256)) > 240)
        image.recycle(); source.recycle()
    }

    @Test fun panAndZoomStayInsideTheSourceAndPreserveThePinchAnchor() {
        assertEquals(AvatarCrop(1f, .25f, .5f), AvatarCrop(.5f, -1f, 1f).clamped(1024, 512))
        val crop = AvatarCrop().transform(1024, 512, 300f, -150f, 0f, 1f, 150f, 150f)
        assertEquals(.75f, crop.centerX, .001f)
        assertEquals(.5f, crop.centerY, .001f)
        val zoom = AvatarCrop().transform(1024, 512, 300f, 0f, 0f, 2f, 225f, 150f)
        assertEquals(.5625f, zoom.centerX, .001f)
        assertEquals(2f, zoom.zoom, .001f)
    }

    @Test fun largeImportsAreSampledBeforeAllocatingTheEditingBitmap() {
        val source = Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        source.recycle()
        val image = AvatarImage.decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        assertEquals(2048, image.width); assertEquals(512, image.height)
        image.recycle()
    }

    @Test fun detailedImageStillFitsThePerAccountBudget() {
        val source = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(1)
        source.setPixels(IntArray(512 * 512) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
            0, 512, 0, 0, 512, 512)
        assertTrue(AvatarImage.encode(source, AvatarCrop()).size <= LocalAvatarStore.MaxBytes)
        source.recycle()
    }

    @Test fun malformedImageIsRejected() {
        assertThrows(Exception::class.java) { AvatarImage.decode(ImageDecoder.createSource(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))) }
    }
}
