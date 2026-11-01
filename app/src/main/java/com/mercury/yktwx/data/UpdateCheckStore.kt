package com.mercury.yktwx.data

import android.content.Context
import android.content.SharedPreferences

/** Throttling belongs to the installed version, independent of account and login caches. */
internal class UpdateCheckStore(private val prefs: SharedPreferences, private val key: String) {
    constructor(context: Context, request: UpdateRequest) : this(
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE),
        "${request.packageName}:${request.channel}:${request.versionCode}",
    )

    fun automaticDue(now: Long): Boolean = elapsed(prefs.getLong("$key:checked", 0), now)
    fun checked(now: Long) { prefs.edit().putLong("$key:checked", now).apply() }
    fun canPrompt(version: Int, now: Long): Boolean =
        prefs.getInt("$key:prompt_version", 0) != version || elapsed(prefs.getLong("$key:prompt_at", 0), now)
    fun prompted(version: Int, now: Long) {
        prefs.edit().putInt("$key:prompt_version", version).putLong("$key:prompt_at", now).apply()
    }

    private fun elapsed(previous: Long, now: Long): Boolean = previous == 0L || now < previous || now - previous >= DAY
    companion object { const val DAY = 24 * 60 * 60 * 1000L }
}
