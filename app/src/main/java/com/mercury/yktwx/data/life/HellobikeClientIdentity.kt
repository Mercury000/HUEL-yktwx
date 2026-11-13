package com.mercury.yktwx.data.life

import android.content.Context
import android.webkit.WebSettings
import java.util.UUID

/** Official 7.0.75 uses the device WebView UA and an independently persisted 32-character SSID. */
internal class HellobikeClientIdentity(context: Context) {
    private val app = context.applicationContext
    private val agent by lazy { WebSettings.getDefaultUserAgent(app) }
    private val sessionId by lazy {
        val preferences = app.getSharedPreferences("hellobike_client_identity", Context.MODE_PRIVATE)
        preferences.getString("ssid", null)?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
            ?: UUID.randomUUID().toString().replace("-", "").also {
                preferences.edit().putString("ssid", it).apply()
            }
    }
    fun userAgent(): String = agent
    fun ssid(): String = sessionId
}
