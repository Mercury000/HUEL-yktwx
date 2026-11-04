package com.mercury.yktwx

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.mercury.yktwx.ui.components.DeviceQrDecoder
import com.mercury.yktwx.data.life.DeviceSerial
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class DeviceQrDecoderTest {
    @Test fun realLcdDevicePhotoDecodesWhereOriginalDecoderFails() {
        val photo = File("D:/mercu/Downloads/1793767036564.jpg")
        assumeTrue("Optional user-supplied QR photo", photo.isFile)
        val original = ImageIO.read(photo)
        for (width in listOf(480, 960)) {
            val image = BufferedImage(width, width * original.height / original.width, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(original, 0, 0, image.width, image.height, null)
                dispose()
            }
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            val source = RGBLuminanceSource(image.width, image.height, pixels)
            val originalResult = runCatching {
                MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)),
                    mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))).text
            }.getOrNull()
            assertNull("Original decoder must reproduce the reported LCD failure", originalResult)
            val decoded = DeviceQrDecoder().decode(source)
            assertEquals("https://h5.qiekj.com/skip?SN=867369079842630", decoded)
            assertEquals("867369079842630", DeviceSerial.parse(checkNotNull(decoded)))
        }
    }

    @Test fun regularQrAtAllFourRotationsAndEmptyFramesStillWork() {
        val text = "https://h5.qiekj.com/skip?SN=867369079842630"
        val qr = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240)
        var pixels = IntArray(240 * 240) { if (qr[it % 240, it / 240]) 0 else 0xFFFFFF }
        val decoder = DeviceQrDecoder()
        repeat(4) {
            assertEquals(text, decoder.decode(RGBLuminanceSource(240, 240, pixels)))
            val previous = pixels
            pixels = IntArray(previous.size) { i -> previous[(239 - i % 240) * 240 + i / 240] }
        }
        assertNull(decoder.decode(RGBLuminanceSource(240, 240, IntArray(240 * 240) { 0xFFFFFF })))
    }
}
