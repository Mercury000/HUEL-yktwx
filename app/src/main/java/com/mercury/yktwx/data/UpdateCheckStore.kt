package com.mercury.yktwx.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Throttling belongs to the installed version, independent of account and login caches. */
internal class UpdateCheckStore(private val prefs: SharedPreferences, private val key: String) {
    constructor(context: Context, request: UpdateRequest) : this(
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE),
        "${request.packageName}:${request.channel}:${request.versionCode}",
    )

    fun automaticDue(now: Long): Boolean = elapsed(prefs.getLong("$key:checked", 0), now,
        prefs.getLong("$key:check_interval", DAY))
    fun checked(now: Long, successful: Boolean) {
        prefs.edit().putLong("$key:checked", now)
            .putLong("$key:check_interval", if (successful) DAY else HOUR).apply()
    }
    fun canNotify(version: Int, now: Long): Boolean =
        prefs.getInt("$key:dismissed_version", 0) != version || elapsed(prefs.getLong("$key:dismissed_at", 0), now)
    fun dismissNotice(version: Int, now: Long) {
        prefs.edit().putInt("$key:dismissed_version", version).putLong("$key:dismissed_at", now).apply()
    }

    fun cachedRelease(): UpdateRelease? = runCatching {
        prefs.getString("$key:release", null)?.let { AppUpdateApi.parse(it).release }
    }.getOrNull()

    fun cacheRelease(release: UpdateRelease?) {
        val editor = prefs.edit()
        if (release == null) editor.remove("$key:release")
        else editor.putString("$key:release", JSONObject()
            .put("schemaVersion", 1).put("updateAvailable", true).put("reason", "updateAvailable")
            .put("latest", JSONObject().put("versionCode", release.versionCode)
                .put("versionName", release.versionName).put("notes", release.notes)
                .put("downloadPageUrl", release.downloadPageUrl)).toString())
        editor.apply()
    }

    private fun elapsed(previous: Long, now: Long, interval: Long = DAY): Boolean =
        previous == 0L || now < previous || now - previous >= interval
    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
