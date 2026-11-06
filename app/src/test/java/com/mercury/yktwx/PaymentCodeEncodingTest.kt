package com.mercury.yktwx

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mercury.yktwx.ui.components.encodePaymentCode
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentCodeEncodingTest {
    @Test fun paymentCodesMatchPreviousMatricesAndDecodeAtNormalAndEnlargedSizes() {
        val codes = listOf(
            "00000000000000000000",
            "00123456789012345678",
            "12345678901234567890",
            "1234567890123456789",
            "987654321098765432109876543210",
        )
        val sizes = listOf(
            Triple(BarcodeFormat.QR_CODE, 900, 900),
            Triple(BarcodeFormat.QR_CODE, 1200, 1200),
            Triple(BarcodeFormat.CODE_128, 1440, 330),
            Triple(BarcodeFormat.CODE_128, 1440, 480),
        )
        for (code in codes) for ((format, width, height) in sizes) {
            val context = "$format ${width}x$height code=$code"
            val previous = MultiFormatWriter().encode(code, format, width, height,
                mapOf(EncodeHintType.MARGIN to if (format == BarcodeFormat.QR_CODE) 4 else 12))
            val matrix = encodePaymentCode(code, format, width, height)
            assertEquals("The code and quiet zone must stay pixel-identical: $context", previous, matrix)

            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(matrix.width, matrix.height, pixels))))
            assertEquals(context, code, decoded.text)
            assertEquals(context, format, decoded.barcodeFormat)
        }
    }
}
