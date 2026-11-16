package com.mercury.yktwx.data

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.SerializationException
import java.security.GeneralSecurityException

internal sealed interface PreferencesLoadState {
    data object Loading : PreferencesLoadState
    data class Ready(val preferences: AppPreferences) : PreferencesLoadState
    data class Failed(val failure: PreferencesFailure) : PreferencesLoadState
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
    private val writeFailures = MutableSharedFlow<PreferencesFailure>(extraBufferCapacity = 1)
    val failures = writeFailures.asSharedFlow()
    private val queuedWrites = Channel<(AppPreferences) -> AppPreferences>(Channel.UNLIMITED)
    private var observer: Job? = null

    init {
        retry()
        scope.launch {
            for (transform in queuedWrites) {
                try { update(transform) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { writeFailures.emit(reportFailure("write", error)) }
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
            catch (error: Exception) { publish(PreferencesLoadState.Failed(reportFailure("read", error))) }
        }
    }

    fun readyOrNull(): AppPreferences? = (state.value as? PreferencesLoadState.Ready)?.preferences
    fun snapshot(): AppPreferences = checkNotNull(readyOrNull()) { "设置尚未准备完成" }
    suspend fun awaitReady(): AppPreferences = when (val value = state.first { it !is PreferencesLoadState.Loading }) {
        is PreferencesLoadState.Ready -> value.preferences
        is PreferencesLoadState.Failed -> throw PreferencesDataException(value.failure)
        PreferencesLoadState.Loading -> error("设置尚未准备完成")
    }

    suspend fun update(transform: (AppPreferences) -> AppPreferences): AppPreferences {
        awaitReady()
        val persisted = store.updateData { current ->
            requirePreferences(current.writeRevision < Long.MAX_VALUE, PreferencesFailureKind.Record,
                "设置写入序号已达上限（writeRevision），无法保存")
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
            val label = policyChannelLabel(key)
            fun checkPolicy(condition: Boolean, detail: String) = requirePreferences(condition,
                PreferencesFailureKind.Policy, "$label：$detail")
            checkPolicy(channel in setOf("stable", "beta"), "更新策略渠道无效")
            val (release, digest) = try { verifyManifest(policy.signedManifest, channel) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val detail = when {
                    generateSequence<Throwable>(error) { it.cause }.take(16).any { it is GeneralSecurityException } ->
                        "保存的更新清单签名验证失败"
                    error is SerializationException -> "保存的更新签名清单 JSON 格式或必填字段无效"
                    error is IllegalArgumentException && error.message == "更新渠道不匹配" ->
                        "签名清单渠道与本机记录不一致"
                    else -> "保存的更新签名清单格式或字段无效（${error.javaClass.simpleName}）"
                }
                throw PreferencesDataException(PreferencesFailure(PreferencesFailureKind.Policy, "$label：$detail"), error)
            }
            checkPolicy(release.packageName == key.substringBeforeLast(':'), "签名清单包名与本机记录不一致")
            checkPolicy(digest == policy.digest, "签名清单摘要与本机记录不一致（digest）")
            checkPolicy(release.policyRevision == policy.revision, "签名清单修订号与本机记录不一致（revision）")
            checkPolicy(release.versionCode == policy.versionCode, "签名清单版本号与本机记录不一致（versionCode）")
            checkPolicy(release.maxForcedVersionCode == policy.forcedBoundary,
                "签名清单强制更新边界与本机记录不一致（forcedBoundary）")
        }
    }

    private fun reportFailure(operation: String, error: Exception): PreferencesFailure = PreferencesFailure.from(error).also {
        // Exception messages/stacks may contain stored JSON, account keys or signed payloads.
        Log.w("AppPreferences", "$operation failed kind=${it.kind} detail=${it.detail} error=${error.javaClass.simpleName}")
    }
}
