package com.mercury.yktwx.analytics

import android.content.Context

internal class UmengAnalyticsBackend(@Suppress("UNUSED_PARAMETER") app: Context) : AnalyticsBackend {
    override fun initialize() = false
    override fun resume(context: Context) {}
    override fun pause(context: Context) {}
    override fun disable() {}
    override fun event(id: String, properties: Map<String, String>) {}
}
