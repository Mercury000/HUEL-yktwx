package com.mercury.yktwx.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mercury.yktwx.data.UsageNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Independent of display caches and UI settings; logout explicitly revokes this record. */
class UsageConsent internal constructor(private val prefs: SharedPreferences, private val hash: String, private val previousLogin: Boolean = false,
    private val onRevoked: () -> Unit = {}) {
    // This history explains why confirmation is needed; it never authorizes business requests.
    private var previousHash by mutableStateOf(prefs.getString("accepted_hash", null) ?: prefs.getString("known_hash", null))
    val confirmationHint: String?
        get() = when {
            accepted -> null
            previousHash != null && previousHash != hash -> "使用须知已更新，请重新阅读并同意后使用。"
            previousHash == null && previousLogin -> "使用前需阅读并同意使用须知。"
            else -> null
        }
    var accepted by mutableStateOf(prefs.getString("accepted_hash", null) == hash)
        private set

    // Keep the commit result: consent is valid only after a successful disk write.
    @SuppressLint("UseKtx")
    suspend fun accept() {
        val saved = withContext(Dispatchers.IO) {
            prefs.edit().putString("accepted_hash", hash).putString("known_hash", hash)
                .putLong("accepted_at", System.currentTimeMillis()).commit()
        }
        check(saved) { "无法保存确认记录，请重试" }
        previousHash = hash
        accepted = true
    }

    fun revoke() {
        accepted = false
        onRevoked()
        prefs.edit { remove("accepted_hash"); remove("accepted_at") }
    }
}
