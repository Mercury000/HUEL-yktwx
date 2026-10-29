package com.mercury.yktwx.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface DisplayCodec<T> {
    fun encode(value: T): JSONObject
    fun decode(json: JSONObject): T
}

/** Disposable, bounded display snapshots. Never stores credentials, codes or payment authority. */
class DisplayCache internal constructor(
    private val directory: File,
    private val seal: (ByteArray, ByteArray) -> ByteArray,
    private val open: (ByteArray, ByteArray) -> ByteArray,
    private val limitBytes: Long = 5 * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    internal constructor(context: Context) : this(File(context.cacheDir, "display-v1"),
        DisplayCipher()::seal, DisplayCipher()::open)

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(owner: String, key: String) = File(directory, "${digest(owner)}-${digest(key)}.cache")

    @Synchronized internal fun <T> read(owner: String, key: String, codec: DisplayCodec<T>): T? {
        val target = file(owner, key)
        if (!target.isFile) return null
        return try {
            require(target.length() <= limitBytes)
            val json = JSONObject(String(open(target.readBytes(), target.name.toByteArray()), Charsets.UTF_8))
            require(json.getInt("version") == 1 && json.getLong("successAt") > 0)
            codec.decode(json.getJSONObject("value")).also { target.setLastModified(clock()) }
        } catch (_: Exception) { target.delete(); null }
    }

    @Synchronized internal fun <T> write(owner: String, key: String, value: T, codec: DisplayCodec<T>) {
        if (owner.isBlank()) return
        try {
            val target = file(owner, key)
            val json = JSONObject().put("version", 1).put("successAt", clock()).put("value", codec.encode(value))
            val encrypted = seal(json.toString().toByteArray(Charsets.UTF_8), target.name.toByteArray())
            if (encrypted.size > minOf(limitBytes / 2, 512 * 1024)) return
            if (!directory.isDirectory && !directory.mkdirs()) return
            // Evict before allocating the replacement so disk usage remains bounded.
            var size = directory.listFiles().orEmpty().sumOf { it.length() }
            directory.listFiles().orEmpty().filter { it != target }.sortedBy { it.lastModified() }.forEach {
                if (size + encrypted.size > limitBytes) { val bytes = it.length(); if (it.delete()) size -= bytes }
            }
            if (size + encrypted.size > limitBytes) return
            val temporary = File(directory, target.name + ".tmp")
            try {
                FileOutputStream(temporary).use { it.write(encrypted); it.fd.sync() }
                try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            } finally { temporary.delete() }
        } catch (_: Exception) { /* Cache failures never fail a successful network read. */ }
    }

    @Synchronized internal fun clear(owner: String) {
        val prefix = digest(owner) + "-"
        directory.listFiles().orEmpty().filter { it.name.startsWith(prefix) }.forEach { it.delete() }
    }
    @Synchronized internal fun remove(owner: String, key: String) { file(owner, key).delete() }
}

private class DisplayCipher {
    private val key: SecretKey get() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("yktwx.display.aes.v1", null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("yktwx.display.aes.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    fun seal(bytes: ByteArray, aad: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key); updateAAD(aad); iv + doFinal(bytes)
    }
    fun open(bytes: ByteArray, aad: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        require(bytes.size > 28)
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        updateAAD(aad); doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
