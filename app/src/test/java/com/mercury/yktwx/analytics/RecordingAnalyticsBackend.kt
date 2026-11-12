package com.mercury.yktwx.analytics

import com.mercury.yktwx.analytics.AnalyticsFixtures
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

internal class RecordingAnalyticsBackend : AnalyticsBackend {
    var available = true
    var initializations = 0
    var resumes = 0
    var pauses = 0
    var disables = 0
    var failEvents = false
    val events = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    override fun initialize(): Boolean { initializations++; return available }
    override fun resume(context: Context) { resumes++ }
    override fun pause(context: Context) { pauses++ }
    override fun disable() { disables++ }
    override fun event(id: String, properties: Map<String, String>) {
        if (failEvents) error("Statistics unavailable")
        events += id to properties.toMap()
    }
    fun start(context: Context): AppAnalytics = AppAnalytics(this).also {
        AnalyticsFixtures.instance = it
        it.resume(context, true)
    }
}
