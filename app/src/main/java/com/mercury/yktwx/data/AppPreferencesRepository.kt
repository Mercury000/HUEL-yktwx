package com.mercury.yktwx.data

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal sealed interface PreferencesLoadState {
    data object Loading : PreferencesLoadState
    data class Ready(val preferences: AppPreferences) : PreferencesLoadState
    data object Failed : PreferencesLoadState
}

/** A persisted snapshot is published only after migration, signature checks and durable writes. */
internal class AppPreferencesRepository internal constructor(
    private val store: DataStore<AppPreferences>,
    internal val scope: CoroutineScope,
    private val verifyManifest: (String, String) -> Pair<UpdateRelease, String>,
) {
    private val mutable = MutableStateFlow<PreferencesLoadState>(PreferencesLoadState.Loading)
    val state = mutable.asStateFlow()
    private val composeSnapshot = mutableStateOf<PreferencesLoadState>(PreferencesLoadState.Loading)
    val composeState: State<PreferencesLoadState> = composeSnapshot
    private var publishedRevision = -1L
    private val publicationLock = Any()

    private fun publish(value: PreferencesLoadState) = synchronized(publicationLock) {
        if (value is PreferencesLoadState.Ready) {
            if (value.preferences.writeRevision < publishedRevision) return@synchronized
            publishedRevision = value.preferences.writeRevision
        }
        mutable.value = value
        composeSnapshot.value = value
    }
    private val writeFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val failures = writeFailures.asSharedFlow()
    private val queuedWrites = Channel<(AppPreferences) -> AppPreferences>(Channel.UNLIMITED)
    private var observer: Job? = null

    init {
        retry()
        scope.launch {
            for (transform in queuedWrites) {
                try { update(transform) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { writeFailures.emit(Unit) }
            }
        }
    }

    @Synchronized fun retry() {
        if (observer?.isActive == true) return
        publish(PreferencesLoadState.Loading)
        observer = scope.launch {
            try {
                store.data.collect { preferences ->
                    validatePolicies(preferences)
                    publish(PreferencesLoadState.Ready(preferences))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(PreferencesLoadState.Failed) }
        }
    }

    fun readyOrNull(): AppPreferences? = (state.value as? PreferencesLoadState.Ready)?.preferences
    fun snapshot(): AppPreferences = checkNotNull(readyOrNull()) { "设置尚未准备完成" }
    suspend fun awaitReady(): AppPreferences = when (val value = state.first { it !is PreferencesLoadState.Loading }) {
        is PreferencesLoadState.Ready -> value.preferences
        else -> error("无法读取设置或更新策略，请重试")
    }

    suspend fun update(transform: (AppPreferences) -> AppPreferences): AppPreferences {
        awaitReady()
        val persisted = store.updateData { current ->
            check(current.writeRevision < Long.MAX_VALUE)
            transform(current).copy(writeRevision = current.writeRevision + 1).also(::validatePolicies)
        }
        // Publishing the returned persisted snapshot also gives callers read-after-write consistency.
        publish(PreferencesLoadState.Ready(persisted))
        return persisted
    }

    fun enqueueUpdate(transform: (AppPreferences) -> AppPreferences) {
        check(queuedWrites.trySend(transform).isSuccess)
    }

    private fun validatePolicies(preferences: AppPreferences) {
        preferences.validate()
        preferences.policies.forEach { (key, policy) ->
            val channel = key.substringAfterLast(':')
            require(channel in setOf("stable", "beta"))
            val (release, digest) = verifyManifest(policy.signedManifest, channel)
            require(release.packageName == key.substringBeforeLast(':') && digest == policy.digest)
            require(release.policyRevision == policy.revision && release.versionCode == policy.versionCode &&
                release.maxForcedVersionCode == policy.forcedBoundary) { "更新策略与签名记录不一致" }
        }
    }

}
