package com.mercury.yktwx.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Vault {
    fun credentials(): Credentials?
    fun session(): Session?
    fun saveCredentials(value: Credentials)
    fun saveSession(value: Session)
    fun clearSession()
    fun clear()
}

/** AES-256-GCM, randomized IV and a non-exportable Android Keystore key. Backups are disabled. */
class SecureVault(context: Context) : Vault {
    private val prefs = context.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized private fun write(name: String, plain: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(name.toByteArray()) }
            val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val value = Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
            if (!prefs.edit().putString(name, value).commit()) throw IllegalStateException()
        } catch (_: Exception) { throw YktException("无法加密保存账户，请检查设备安全设置") }
    }
    @Synchronized private fun read(name: String): JSONObject? {
        val saved = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(name.toByteArray())
            }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            prefs.edit().remove(name).commit()
            null // Key lost, corrupt data or restoring on a new device: ask for login.
        }
    }

    override fun credentials() = read("credentials")?.let { Credentials(it.optString("username"), it.optString("password")) }
    override fun session() = read("session")?.let { Session(it.optString("token"), it.optLong("expires")) }
    override fun saveCredentials(value: Credentials) = write("credentials", JSONObject().put("username", value.username).put("password", value.password).toString())
    override fun saveSession(value: Session) = write("session", JSONObject().put("token", value.accessToken).put("expires", value.expiresAtMillis).toString())
    override fun clearSession() { prefs.edit().remove("session").commit() }
    override fun clear() { prefs.edit().clear().commit() }
    private companion object { const val ALIAS = "yktwx.account.aes.v1" }
}
