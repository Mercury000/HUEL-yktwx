package com.mercury.yktwx.data

import android.content.Context
import android.util.AtomicFile
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** User-chosen images are settings, separate from disposable network snapshots. */
internal class LocalAvatarStore(private val directory: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "avatars-v1"))

    private fun file(owner: String): File {
        require(owner.isNotBlank())
        val name = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$name.webp")
    }

    @Synchronized fun read(owner: String): ByteArray? {
        val target = file(owner)
        return try {
            val atomic = AtomicFile(target)
            val bytes = atomic.openRead().use { it.readNBytes(MaxBytes + 1) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes.size <= MaxBytes) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val validBounds = bytes.isNotEmpty() && bytes.size <= MaxBytes && bounds.outWidth == 512 &&
                bounds.outHeight == 512 && bounds.outMimeType == "image/webp"
            val image = if (validBounds) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
            if (image != null) { image.recycle(); bytes } else { atomic.delete(); null }
        } catch (_: Exception) { null }
    }

    @Synchronized fun write(owner: String, bytes: ByteArray, checkActive: () -> Unit = {}) {
        require(bytes.isNotEmpty() && bytes.size <= MaxBytes)
        check(directory.isDirectory || directory.mkdirs())
        val atomic = AtomicFile(file(owner))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            checkActive()
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    @Synchronized fun remove(owner: String) {
        val target = file(owner)
        AtomicFile(target).delete()
        check(!target.exists())
    }

    companion object { const val MaxBytes = 200 * 1024 }
}
