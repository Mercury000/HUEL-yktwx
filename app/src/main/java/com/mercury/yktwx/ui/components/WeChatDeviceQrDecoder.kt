package com.mercury.yktwx.ui.components

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.mercury000.wechatqrcode.WeChatQrDecoder
import java.io.File

/** Sole camera QR decoder, lazily initialized and reused on the camera worker. */
internal class WeChatDeviceQrDecoder(context: Context, private val onUnavailable: () -> Unit) : AutoCloseable {
    private val appContext = context.applicationContext
    private var decoder: WeChatQrDecoder? = null
    private var initialized = false
    private var closed = false

    @Synchronized
    fun decode(grayscale: ByteArray, width: Int, height: Int): String? {
        if (closed || width <= 0 || height <= 0 || width.toLong() * height > grayscale.size) return null
        if (!initialized) {
            initialized = true
            decoder = try {
                WeChatQrDecoder(prepareWeChatQrModels(appContext).absolutePath)
            } catch (error: LinkageError) {
                Log.w("DeviceScanner", "WeChat QR native library unavailable", error)
                null
            } catch (error: Exception) {
                Log.w("DeviceScanner", "Unable to initialize WeChat QR models", error)
                null
            }
            if (decoder == null) onUnavailable()
        }
        return try {
            decoder?.decode(grayscale, width, height)?.firstOrNull()
        } catch (error: Exception) {
            Log.w("DeviceScanner", "WeChat QR recognition failed", error)
            null
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        decoder?.close()
        decoder = null
    }
}

/** Versioned, atomic copies keep model initialization independent of APK asset storage. */
@Synchronized
internal fun prepareWeChatQrModels(context: Context): File {
    val directory = File(context.filesDir, "wechat-qrcode-lite/0.1.0")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create QR model directory" }
    val models = mapOf("detect.caffemodel" to 965430L, "detect.prototxt" to 42656L,
        "sr.caffemodel" to 23929L, "sr.prototxt" to 5984L)
    for ((name, size) in models) {
        val target = File(directory, name)
        if (target.isFile && target.length() == size) continue
        if (target.exists()) check(target.delete()) { "Cannot replace incomplete QR model: $name" }
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            context.assets.open("wechat_qrcode_lite/$name").use { it.copyTo(output) }
            check(output.channel.size() == size) { "Incomplete QR model: $name" }
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        check(target.isFile && target.length() == size) { "Cannot install QR model: $name" }
    }
    return directory
}
