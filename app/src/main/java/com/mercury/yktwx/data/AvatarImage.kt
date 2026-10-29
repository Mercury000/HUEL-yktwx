package com.mercury.yktwx.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal data class AvatarCrop(val zoom: Float = 1f, val centerX: Float = .5f, val centerY: Float = .5f) {
    fun clamped(width: Int, height: Int): AvatarCrop {
        val scale = zoom.coerceIn(1f, 4f)
        val half = min(width, height) / scale / 2f
        return AvatarCrop(scale, centerX.coerceIn(half / width, 1f - half / width),
            centerY.coerceIn(half / height, 1f - half / height))
    }

    fun transform(width: Int, height: Int, viewport: Float, panX: Float, panY: Float,
        zoomChange: Float, focusX: Float, focusY: Float): AvatarCrop {
        if (viewport <= 0f) return this
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
        val base = viewport / min(width, height)
        val dx = focusX - viewport / 2f
        val dy = focusY - viewport / 2f
        return AvatarCrop(nextZoom,
            centerX + (dx / (base * zoom) - (dx + panX) / (base * nextZoom)) / width,
            centerY + (dy / (base * zoom) - (dy + panY) / (base * nextZoom)) / height).clamped(width, height)
    }
}

internal object AvatarImage {
    fun decode(resolver: ContentResolver, uri: Uri): Bitmap {
        val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readNBytes(20 * 1024 * 1024 + 1) }
        require(bytes.size <= 20 * 1024 * 1024)
        return decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    }

    internal fun decode(source: ImageDecoder.Source): Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val width = info.size.width
        val height = info.size.height
        require(width > 0 && height > 0)
        val scale = min(1.0, 2048.0 / max(width, height))
        decoder.setTargetSize(max(1, (width * scale).roundToInt()), max(1, (height * scale).roundToInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        decoder.setOnPartialImageListener { false }
    }

    fun encode(bitmap: Bitmap, crop: AvatarCrop): ByteArray {
        val safe = crop.clamped(bitmap.width, bitmap.height)
        val side = min(bitmap.width, bitmap.height) / safe.zoom
        val left = safe.centerX * bitmap.width - side / 2
        val top = safe.centerY * bitmap.height - side / 2
        val result = createBitmap(512, 512)
        try {
            val matrix = Matrix().apply { setRectToRect(RectF(left, top, left + side, top + side),
                RectF(0f, 0f, 512f, 512f), Matrix.ScaleToFit.FILL) }
            Canvas(result).drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            for (quality in listOf(90, 80, 65, 45)) {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(result.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, output))
                    output.toByteArray()
                }
                if (bytes.size <= LocalAvatarStore.MaxBytes) return bytes
            }
            error("Image is too large")
        } finally { result.recycle() }
    }
}
