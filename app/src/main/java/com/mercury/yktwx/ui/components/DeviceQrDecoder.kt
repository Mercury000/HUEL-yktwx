package com.mercury.yktwx.ui.components

import com.google.zxing.*
import com.google.zxing.common.BitArray
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** LCD modules can be separated by a one-pixel grid after camera downsampling. */
internal class DeviceQrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(source: LuminanceSource): String? {
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        read(bitmap)?.let { return it }
        var matrix = try { bitmap.blackMatrix } catch (_: NotFoundException) { return null }
        // Keep the original image first. Only failed frames receive bounded gap compensation.
        repeat(2) {
            matrix = expandBlackDots(matrix)
            read(BinaryBitmap(MatrixBinarizer(source, matrix)))?.let { return it }
        }
        return null
    }

    private fun read(bitmap: BinaryBitmap): String? = try {
        reader.decode(bitmap, hints).text
    } catch (_: ReaderException) {
        null
    } finally {
        reader.reset()
    }

    private fun expandBlackDots(input: BitMatrix): BitMatrix {
        val result = BitMatrix(input.width, input.height)
        for (y in 1 until input.height - 1) for (x in 1 until input.width - 1) {
            if (input[x, y] || input[x - 1, y] || input[x + 1, y] ||
                input[x, y - 1] || input[x - 1, y - 1] || input[x + 1, y - 1] ||
                input[x, y + 1] || input[x - 1, y + 1] || input[x + 1, y + 1]) result.set(x, y)
        }
        return result
    }

    private class MatrixBinarizer(source: LuminanceSource, private val matrix: BitMatrix) : Binarizer(source) {
        override fun getBlackMatrix() = matrix
        override fun getBlackRow(y: Int, row: BitArray?) = matrix.getRow(y, row)
        override fun createBinarizer(source: LuminanceSource): Binarizer = HybridBinarizer(source)
    }
}
