package com.mercury.yktwx.data

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import com.mercury.yktwx.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.WeakHashMap

internal class MemoryPreferences(initial: AppPreferences = AppPreferences(legacyMigrated = true)) : DataStore<AppPreferences> {
    val values = MutableStateFlow(initial)
    private val lock = Mutex()
    var failure: Exception? = null
    override val data: Flow<AppPreferences> = values
    override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences): AppPreferences = lock.withLock {
        failure?.let { throw it }
        transform(values.value).also { values.value = it }
    }
}

// Existing update tests use isolated typed fixtures. SharedPreferences is only an identity token,
// never a persistence reader or an implementation of the retired storage format.
private val updateFixtures = WeakHashMap<SharedPreferences, AppPreferencesRepository>()
private val manifests = mutableMapOf<String, UpdateRelease>()
internal fun resetUpdateFixture(identity: SharedPreferences) { updateFixtures.remove(identity)?.scope?.cancel() }
internal fun resetUpdateFixtures() {
    updateFixtures.values.forEach { it.scope.cancel() }
    updateFixtures.clear(); manifests.clear()
}
internal fun updateStoreFixture(
    identity: SharedPreferences, key: String,
    policyKey: String = BuildConfig.APPLICATION_ID,
    installedVersion: Int = BuildConfig.VERSION_CODE,
    now: () -> Instant = Instant::now,
    verifyCached: ((String, String) -> UpdateRelease)? = null,
): UpdateCheckStore {
    val verifier: (String, String) -> UpdateRelease = verifyCached ?: { body, _ -> checkNotNull(manifests[body]) }
    val owner = if (policyKey == "package") BuildConfig.APPLICATION_ID else policyKey
    val repository = updateFixtures.getOrPut(identity) {
        AppPreferencesRepository(MemoryPreferences(AppPreferences(legacyMigrated = true,
            updateChannel = if (key.contains(":beta:")) "beta" else "stable")),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { body, channel ->
                verifier(body, channel) to if (body in manifests) sha256(body.toByteArray()) else
                    sha256(java.util.Base64.getDecoder().decode(parseObject(body).stringValue("payload")))
            }
    }
    val version = Regex("^v([1-9][0-9]*)$").matchEntire(key)?.groupValues?.get(1)?.toInt() ?: installedVersion
    return UpdateCheckStore(repository, owner, version, now, verifier)
}

/** Inject a current policy into UI fixtures without depending on old unsigned caches. */
internal fun UpdateCheckStore.cacheRelease(release: UpdateRelease?) = runBlocking {
    if (release == null) return@runBlocking
    val body = "ui-fixture-${System.nanoTime()}"
    val normalized = release.copy(packageName = BuildConfig.APPLICATION_ID, policyRevision = 1,
        signedManifest = body, expiresAt = Instant.parse("2099-01-01T00:00:00Z"))
    manifests[body] = normalized
    acceptPolicy(normalized.channel, normalized, sha256(body.toByteArray()))
}
