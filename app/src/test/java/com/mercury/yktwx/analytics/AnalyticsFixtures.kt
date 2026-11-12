package com.mercury.yktwx.analytics

import android.content.Context

/** Recording bindings and no-op fallback belong only to direct unit fixtures. */
internal object AnalyticsFixtures {
    var instance: AppAnalytics? = null
    fun from(@Suppress("UNUSED_PARAMETER") context: Context): AppAnalytics = instance ?: AppAnalytics(object : AnalyticsBackend {
        override fun initialize() = false
        override fun resume(context: Context) {}
        override fun pause(context: Context) {}
        override fun disable() {}
        override fun event(id: String, properties: Map<String, String>) {}
    }).also { instance = it }
}
