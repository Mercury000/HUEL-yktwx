package com.mercury.yktwx.ui.components

import android.graphics.Rect
import android.util.Range

/** Camera2 zoom bounds, with centered sensor cropping for cameras without ratio control. */
internal class DeviceCameraZoom(activeArray: Rect? = null, zoomRatioRange: Range<Float>? = null,
    maxDigitalZoom: Float? = null) {
    private val sensor = activeArray?.takeIf { it.width() > 0 && it.height() > 0 }?.let(::Rect)
    val usesZoomRatio = zoomRatioRange != null
    private val maximum = (if (usesZoomRatio) zoomRatioRange?.upper else if (sensor != null) maxDigitalZoom else null)
        ?.takeIf { it.isFinite() && it >= 1f } ?: 1f
    var ratio = 1f
        private set

    fun scaleBy(factor: Float): Boolean {
        if (!factor.isFinite() || factor <= 0f) return false
        return setRatio(ratio * factor)
    }

    fun toggleQuickZoom(): Boolean = setRatio(if (ratio > 1f) 1f else 2f)

    private fun setRatio(value: Float): Boolean {
        val next = value.coerceIn(1f, maximum)
        if (next == ratio) return false
        ratio = next
        return true
    }

    fun cropRegion(): Rect? {
        if (usesZoomRatio) return null
        val bounds = sensor ?: return null
        val width = (bounds.width() / ratio).toInt().coerceAtLeast(1)
        val height = (bounds.height() / ratio).toInt().coerceAtLeast(1)
        val left = bounds.left + (bounds.width() - width) / 2
        val top = bounds.top + (bounds.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }
}
