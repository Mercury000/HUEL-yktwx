package com.mercury.yktwx.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Each input/size owns its result, so a cancelled decode cannot restore an older image. */
@Composable
internal fun rememberSampledImage(bytes: ByteArray?, width: Dp, height: Dp, crop: Boolean = false): ImageBitmap? =
    rememberSampledImageResult(bytes, width, height, crop).image

internal data class SampledImageResult(val image: ImageBitmap? = null, val loading: Boolean = false)

@Composable
internal fun rememberSampledImageResult(bytes: ByteArray?, width: Dp, height: Dp, crop: Boolean = false): SampledImageResult {
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx().coerceAtLeast(1) }
    val heightPx = with(density) { height.roundToPx().coerceAtLeast(1) }
    val result = remember(bytes, widthPx, heightPx, crop) { mutableStateOf(SampledImageResult(loading = bytes != null)) }
    LaunchedEffect(result) {
        if (bytes != null) result.value = SampledImageResult(decodeSampledImage(bytes, widthPx, heightPx, crop))
    }
    return result.value
}

internal suspend fun decodeSampledImage(bytes: ByteArray, width: Int, height: Int, crop: Boolean = false): ImageBitmap? =
    withContext(Dispatchers.Default) {
        require(width > 0 && height > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (sample < (1 shl 30)) {
            val next = sample * 2
            val wideEnough = bounds.outWidth / next >= width
            val tallEnough = bounds.outHeight / next >= height
            if (!(if (crop) wideEnough && tallEnough else wideEnough || tallEnough)) break
            sample = next
        }
        // Compressed-byte limits do not bound pixels, especially for extreme aspect ratios.
        fun sampledPixels() = ((bounds.outWidth.toLong() + sample - 1) / sample) *
            ((bounds.outHeight.toLong() + sample - 1) / sample)
        while (sampledPixels() > 1_048_576 && sample < (1 shl 30)) sample *= 2
        ensureActive()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
        ensureActive()
        bitmap?.asImageBitmap()
    }
