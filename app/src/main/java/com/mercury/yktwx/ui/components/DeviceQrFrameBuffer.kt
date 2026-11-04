package com.mercury.yktwx.ui.components

import java.nio.ByteBuffer

/** Camera-worker storage reused until the frame dimensions require a different capacity. */
internal class DeviceQrFrameBuffer {
    private var pixels = ByteArray(0)

    fun copy(source: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0)
        val size = width.toLong() * height
        val rowBytes = (width - 1L) * pixelStride + 1L
        val required = (height - 1L) * rowStride + rowBytes
        require(size <= Int.MAX_VALUE && rowBytes <= rowStride && required <= source.remaining()) {
            "Incomplete QR luminance plane"
        }
        if (pixels.size != size.toInt()) pixels = ByteArray(size.toInt())
        val buffer = source.duplicate()
        val start = buffer.position()
        if (pixelStride == 1 && rowStride == width) {
            buffer.get(pixels)
        } else for (y in 0 until height) {
            val row = start + y * rowStride
            if (pixelStride == 1) {
                buffer.position(row)
                buffer.get(pixels, y * width, width)
            } else for (x in 0 until width) {
                pixels[y * width + x] = buffer.get(row + x * pixelStride)
            }
        }
        return pixels
    }
}
