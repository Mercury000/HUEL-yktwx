package com.mercury.yktwx.data

import android.content.Context
import android.annotation.SuppressLint
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
    fun saveAccount(credentials: Credentials, session: Session) {
        saveCredentials(credentials)
        saveSession(session)
    }
    fun clearSession()
    fun clear()
}

/** AES-256-GCM, randomized IV and a non-exportable Android Keystore key. Backups are disabled. */
class SecureVault(context: Context, storageName: String = "encrypted_account",
    private val keyAlias: String = "yktwx.account.aes.v1") : Vault {
    private val prefs = context.getSharedPreferences(storageName, Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val encryptionKey: SecretKey by lazy {
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(name: String, plain: String): String {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey); updateAAD(name.toByteArray()) }
            val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
        } catch (_: Exception) { throw YktException("无法加密保存账户，请检查设备安全设置") }
    }
    // Login reports persistence failure to the user. Keep one checked, atomic commit on IO.
    @SuppressLint("ApplySharedPref")
    @Synchronized internal fun write(values: Map<String, String>) {
        val encrypted = values.mapValues { (name, plain) -> encrypt(name, plain) }
        val editor = prefs.edit()
        encrypted.forEach { (name, value) -> editor.putString(name, value) }
        if (!editor.commit()) throw YktException("无法加密保存账户，请检查设备安全设置")
    }
    @Synchronized internal fun read(name: String): JSONObject? {
        val saved = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(saved, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD(name.toByteArray())
            }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) {
            prefs.edit().remove(name).apply()
            null // Key lost, corrupt data or restoring on a new device: ask for login.
        }
    }

    override fun credentials() = read("credentials")?.let { Credentials(it.optString("username"), it.optString("password")) }
    override fun session() = read("session")?.let { Session(it.optString("token"), it.optLong("expires")) }
    private fun credentialsJson(value: Credentials) = JSONObject().put("username", value.username).put("password", value.password).toString()
    private fun sessionJson(value: Session) = JSONObject().put("token", value.accessToken).put("expires", value.expiresAtMillis).toString()
    override fun saveCredentials(value: Credentials) = write(mapOf("credentials" to credentialsJson(value)))
    override fun saveSession(value: Session) = write(mapOf("session" to sessionJson(value)))
    override fun saveAccount(credentials: Credentials, session: Session) =
        write(mapOf("credentials" to credentialsJson(credentials), "session" to sessionJson(session)))
    @Synchronized override fun clearSession() { prefs.edit().remove("session").apply() }
    @Synchronized override fun clear() { prefs.edit().clear().apply() }
    @Synchronized internal fun remove(name: String) { prefs.edit().remove(name).apply() }
}
