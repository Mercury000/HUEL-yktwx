package com.mercury.yktwx.data

import com.mercury.yktwx.BuildConfig
import java.time.Instant

/** One installed-version view of the shared DataStore; channel is never cached separately. */
internal class UpdateCheckStore(
    internal val repository: AppPreferencesRepository,
    private val packageName: String = BuildConfig.APPLICATION_ID,
    private val installedVersion: Int = BuildConfig.VERSION_CODE,
    private val now: () -> Instant = Instant::now,
    private val verifyCached: (String, String) -> UpdateRelease,
) {
    val channel: String get() = repository.snapshot().updateChannel
    private fun key(channel: String = this.channel) = "$packageName:$channel:$installedVersion"
    private fun reminder() = repository.snapshot().reminders[key()] ?: UpdateReminder()
    fun forcedBoundary(): Int = repository.snapshot().policies
        .filterKeys { it.substringBeforeLast(':') == packageName }.values.maxOfOrNull { it.forcedBoundary } ?: 0
    fun automaticDue(now: Long): Boolean = reminder().let { elapsed(it.checkedAt, now, it.checkInterval) }
    suspend fun checked(now: Long, successful: Boolean, channel: String = this.channel) {
        val key = key(channel)
        repository.update { data ->
            val old = data.reminders[key] ?: UpdateReminder()
            data.copy(reminders = data.reminders + (key to old.copy(checkedAt = now, checkInterval = if (successful) DAY else HOUR)))
        }
    }
    fun canNotify(version: Int, now: Long): Boolean = repository.snapshot().skippedVersions[packageName] != version &&
        reminder().let { it.dismissedVersion != version || elapsed(it.dismissedAt, now) }
    suspend fun skip(version: Int) { repository.update { it.copy(skippedVersions = it.skippedVersions + (packageName to version)) } }
    suspend fun dismissNotice(version: Int, now: Long) {
        val key = key()
        repository.update { data ->
            val old = data.reminders[key] ?: UpdateReminder()
            data.copy(reminders = data.reminders + (key to old.copy(dismissedVersion = version, dismissedAt = now)))
        }
    }

    suspend fun acceptPolicy(channel: String, release: UpdateRelease, digest: String) {
        val key = "$packageName:$channel"
        require(channel in setOf("stable", "beta") && release.channel == channel && release.packageName == packageName)
        repository.update { data ->
            val previous = data.policies[key]
            if (previous != null) {
                require(release.policyRevision >= previous.revision) { "拒绝过期更新策略" }
                if (release.policyRevision == previous.revision) require(previous.digest == digest) { "更新策略存在冲突" }
                require(release.maxForcedVersionCode >= previous.forcedBoundary) { "强制更新边界不能回退" }
                require(release.versionCode >= previous.versionCode) { "更新版本不能回退" }
            }
            data.copy(policies = data.policies + (key to AcceptedUpdatePolicy(release.policyRevision, digest,
                release.maxForcedVersionCode, release.versionCode, release.signedManifest)))
        }
    }

    fun cachedRelease(): UpdateRelease? {
        val preferences = repository.snapshot()
        val signed = preferences.policies.filterKeys { it.substringBeforeLast(':') == packageName }.map { (key, policy) ->
            verifyCached(policy.signedManifest, key.substringAfterLast(':'))
        }.filter { it.versionCode > installedVersion &&
            (it.expiresAt?.isAfter(now()) == true || it.required(installedVersion)) &&
            (it.minSdk <= android.os.Build.VERSION.SDK_INT || it.required(installedVersion)) &&
            (it.channel == "stable" || channel == "beta" || it.required(installedVersion)) }
        return signed.maxByOrNull { it.versionCode }?.let { best ->
            best.copy(maxForcedVersionCode = forcedBoundary().coerceAtMost(best.versionCode - 1))
        }
    }

    private fun elapsed(previous: Long, now: Long, interval: Long = DAY): Boolean =
        previous == 0L || now < previous || now - previous >= interval
    companion object {
        const val HOUR = 60 * 60 * 1000L
        const val DAY = 24 * HOUR
    }
}
