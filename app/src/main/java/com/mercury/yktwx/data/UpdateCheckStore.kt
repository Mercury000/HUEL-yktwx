package com.mercury.yktwx.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Throttling belongs to the installed version, independent of account and login caches. */
internal class UpdateCheckStore(private val prefs: SharedPreferences, private var key: String,
    private val policyKey: String = key.substringBeforeLast(':'),
    private val installedVersion: Int = com.mercury.yktwx.BuildConfig.VERSION_CODE,
    private val now: () -> java.time.Instant = java.time.Instant::now,
    private val verifyCached: (String, String) -> UpdateRelease = { body, channel ->
        UpdateManifestVerifier().manifest(body, channel).first
    }) {
    constructor(context: Context, request: UpdateRequest) : this(
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE),
        "${request.packageName}:${request.channel}:${request.versionCode}",
        request.packageName, request.versionCode,
    )

    val channel: String get() = if (key.contains(":beta:")) "beta" else "stable"

    init {
        prefs.getString("$policyKey:channel", null)?.takeIf { it in listOf("stable", "beta") }?.let {
            if (it != channel) key = "$policyKey:$it:$installedVersion"
        }
        if (!prefs.contains("$policyKey:channel")) prefs.edit().putString("$policyKey:channel", channel).apply()
    }

    fun selectChannel(value: String) {
        require(value in listOf("stable", "beta"))
        key = "$policyKey:$value:$installedVersion"
        prefs.edit().putString("$policyKey:channel", value).apply()
    }

    fun forcedBoundary(): Int = listOf("stable", "beta").maxOf {
        prefs.getInt("$policyKey:policy:$it:forced", 0)
    }

    fun automaticDue(now: Long): Boolean = elapsed(prefs.getLong("$key:checked", 0), now,
        prefs.getLong("$key:check_interval", DAY))
    fun checked(now: Long, successful: Boolean) {
        prefs.edit().putLong("$key:checked", now)
            .putLong("$key:check_interval", if (successful) DAY else HOUR).apply()
    }
    fun canNotify(version: Int, now: Long): Boolean =
        prefs.getInt("$policyKey:skipped_version", 0) != version &&
        (prefs.getInt("$key:dismissed_version", 0) != version || elapsed(prefs.getLong("$key:dismissed_at", 0), now))
    fun skip(version: Int) { prefs.edit().putInt("$policyKey:skipped_version", version).apply() }
    fun dismissNotice(version: Int, now: Long) {
        prefs.edit().putInt("$key:dismissed_version", version).putLong("$key:dismissed_at", now).apply()
    }

    @Synchronized fun acceptPolicy(channel: String, release: UpdateRelease, digest: String) {
        val prefix = "$policyKey:policy:$channel"
        val previous = prefs.getLong("$prefix:revision", 0)
        require(release.policyRevision >= previous) { "拒绝过期更新策略" }
        if (release.policyRevision == previous) require(prefs.getString("$prefix:digest", null) == digest) { "更新策略存在冲突" }
        require(release.maxForcedVersionCode >= prefs.getInt("$prefix:forced", 0)) { "强制更新边界不能回退" }
        require(release.versionCode >= prefs.getInt("$prefix:version", 0)) { "更新版本不能回退" }
        check(prefs.edit().putLong("$prefix:revision", release.policyRevision).putString("$prefix:digest", digest)
            .putInt("$prefix:forced", release.maxForcedVersionCode).putInt("$prefix:version", release.versionCode)
            .putString("$prefix:manifest", release.signedManifest).commit()) { "无法保存更新策略" }
    }

    fun cachedRelease(): UpdateRelease? {
        val signed = listOf("stable", "beta").mapNotNull { channel -> runCatching {
            prefs.getString("$policyKey:policy:$channel:manifest", null)?.let {
                verifyCached(it, channel)
            }
        }.getOrNull() }.filter { it.versionCode > installedVersion &&
            (it.expiresAt?.isAfter(now()) == true || it.required(installedVersion)) &&
            (it.minSdk <= android.os.Build.VERSION.SDK_INT || it.required(installedVersion)) &&
            (it.channel == "stable" || channel == "beta" || it.required(installedVersion)) }
        signed.maxByOrNull { it.versionCode }?.let { best ->
            return best.copy(maxForcedVersionCode = forcedBoundary().coerceAtMost(best.versionCode - 1))
        }
        return runCatching { prefs.getString("$key:release", null)?.let { body ->
            val json = JSONObject(body)
            // Unsigned legacy caches cannot introduce forced policy or installable APKs.
            UpdateRelease(json.getInt("versionCode"), json.getString("versionName"), json.getString("notes"),
                json.getString("downloadPageUrl"))
        } }.getOrNull()
    }

    fun cacheRelease(release: UpdateRelease?) {
        val editor = prefs.edit()
        if (release == null) editor.remove("$key:release")
        else if (release.signedManifest.isEmpty()) editor.putString("$key:release", JSONObject().put("versionCode", release.versionCode)
                .put("versionName", release.versionName).put("notes", release.notes)
                .put("downloadPageUrl", release.downloadPageUrl).toString())
        else editor.remove("$key:release")
        editor.apply()
    }

    private fun elapsed(previous: Long, now: Long, interval: Long = DAY): Boolean =
        previous == 0L || now < previous || now - previous >= interval
    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
