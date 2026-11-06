package com.mercury.yktwx.ui.components

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter

/** Direct writers let R8 discard encoders for formats the payment page never displays. */
internal fun encodePaymentCode(code: String, format: BarcodeFormat, width: Int, height: Int): BitMatrix {
    val hints = mapOf(EncodeHintType.MARGIN to if (format == BarcodeFormat.QR_CODE) 4 else 12)
    return when (format) {
        BarcodeFormat.QR_CODE -> QRCodeWriter().encode(code, format, width, height, hints)
        BarcodeFormat.CODE_128 -> Code128Writer().encode(code, format, width, height, hints)
        else -> throw IllegalArgumentException("Unsupported payment code format: $format")
    }
}
