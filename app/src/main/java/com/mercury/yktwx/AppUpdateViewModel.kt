package com.mercury.yktwx

import android.app.Activity
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.state.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class UpdateFeedback { Current, NoRelease, Incompatible, Network, Failed, Preview }
internal data class AppUpdateUiState(
    val ready: Boolean = false,
    val loadFailed: Boolean = false,
    val checking: Boolean = false,
    val release: UpdateRelease? = null, val visible: Boolean = false,
    val noticeVisible: Boolean = false,
    val detailsPending: Boolean = false,
    val required: Boolean = false,
    val download: UpdateDownloadState = UpdateDownloadState(),
)

@HiltViewModel
internal class AppUpdateViewModel internal constructor(
    private val store: UpdateCheckStore,
    private val checkUpdate: suspend () -> UpdateCheckResult,
    private val installer: AppUpdateInstaller,
    private val now: () -> Long = System::currentTimeMillis,
    private val analytics: AppAnalytics,
    savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    @Inject internal constructor(store: UpdateCheckStore, api: AppUpdateApi, installer: AppUpdateInstaller, analytics: AppAnalytics,
        savedState: SavedStateHandle) : this(store, { api.check(deviceRequest().copy(channel = store.channel), store) }, installer,
            analytics = analytics, savedState = savedState)

    private fun restoredState(): AppUpdateUiState {
        if (store.repository.readyOrNull() == null) return AppUpdateUiState()
        val release = store.cachedRelease()
        return AppUpdateUiState(ready = true, release = release, required = release?.required(BuildConfig.VERSION_CODE) == true)
    }
    private val mutable = MutableStateFlow(restoredState())
    val state = mutable.asStateFlow()
    private var restoredDetails: UpdateOverlaySavedState? = null
    private val overlays = RestorableState(savedState, "update-details", UpdateOverlaySavedState.serializer(), { it.valid }) {
        restoredDetails ?: UpdateOverlaySavedState(state.value.visible || state.value.detailsPending,
            activeChannel ?: "stable", state.value.release?.versionCode ?: 0, state.value.release?.sha256.orEmpty(),
            state.value.download.phase != UpdateDownloadPhase.Idle)
    }
    private fun restoreDetails() {
        if (!state.value.ready) return
        val restored = overlays.bind("${BuildConfig.APPLICATION_ID}:${BuildConfig.VERSION_CODE}") ?: return
        if (!restored.visible || restored.channel != store.channel) return
        restoredDetails = restored
    }
    private val feedback = MutableSharedFlow<UpdateFeedback>(extraBufferCapacity = 1)
    val messages = feedback.asSharedFlow()
    private var job: Job? = null
    private var generation = 0L
    private var remindersEnabled = false
    private var networkAllowed = false
    private var foreground = false
    private var manualRequest = false
    private var downloadJob: Job? = null
    @Volatile private var downloadGeneration = 0L
    private var awaitingAuthorization = false
    private var pendingInstallLauncher: ((android.content.Intent) -> Unit)? = null
    private var reopenDownloadDetails = false
    private var activeChannel = store.repository.readyOrNull()?.updateChannel
    private var knownPolicies = store.repository.readyOrNull()?.policies

    init {
        restoreDetails()
        viewModelScope.launch {
            store.repository.state.collect { value ->
                when (value) {
                    PreferencesLoadState.Loading, PreferencesLoadState.Failed -> {
                        generation++; job?.cancel(); job = null
                        downloadGeneration++; downloadJob?.cancel(); downloadJob = null
                        pendingInstallLauncher = null; awaitingAuthorization = false
                        mutable.value = state.value.copy(ready = false, loadFailed = value is PreferencesLoadState.Failed,
                            checking = false, visible = false, noticeVisible = false)
                    }
                    is PreferencesLoadState.Ready -> {
                        val firstReady = !state.value.ready
                        val channelChanged = activeChannel != value.preferences.updateChannel
                        if (firstReady) {
                            mutable.value = restoredState()
                            activeChannel = store.channel
                            restoreDetails()
                        } else if (knownPolicies != value.preferences.policies) {
                            store.cachedRelease()?.takeIf { it.required(BuildConfig.VERSION_CODE) }?.let {
                                mutable.value = state.value.copy(release = it, required = true)
                            }
                        }
                        knownPolicies = value.preferences.policies
                        // A settings write is not a foreground transition or a request to check again.
                        if (firstReady || channelChanged) configure(remindersEnabled, networkAllowed, foreground)
                        else refreshNotice()
                    }
                }
            }
        }
    }
    fun retryLoading() = store.repository.retry()

    fun configure(reminders: Boolean, allowed: Boolean, inForeground: Boolean) {
        if (!state.value.ready) {
            remindersEnabled = reminders; networkAllowed = allowed; foreground = inForeground
            return
        }
        val channel = store.channel
        val channelChanged = activeChannel != channel
        if (channelChanged) {
            restoredDetails = null
            generation++
            job?.cancel(); job = null
            manualRequest = false
            downloadGeneration++
            downloadJob?.cancel(); downloadJob = null
            pendingInstallLauncher = null
            awaitingAuthorization = false
            reopenDownloadDetails = false
            activeChannel = channel
            val retained = state.value.release?.takeIf { it.required(BuildConfig.VERSION_CODE) }
                ?: store.cachedRelease()
            mutable.value = AppUpdateUiState(ready = true, release = retained,
                required = retained?.required(BuildConfig.VERSION_CODE) == true)
        }
        if (!allowed) {
            downloadGeneration++; downloadJob?.cancel(); downloadJob = null; pendingInstallLauncher = null
            if (state.value.download.busy) mutable.value = state.value.copy(download = UpdateDownloadState(
                UpdateDownloadPhase.Failed, error = "更新已暂停，请重试"))
        }
        if (!inForeground && foreground && state.value.visible && state.value.download.phase != UpdateDownloadPhase.Idle)
            reopenDownloadDetails = true
        remindersEnabled = reminders
        networkAllowed = allowed
        foreground = inForeground
        if (!allowed || !inForeground) {
            generation++
            job?.cancel(); job = null
            manualRequest = false
            mutable.value = state.value.copy(checking = false,
                visible = allowed && inForeground && state.value.visible && state.value.download.busy,
                detailsPending = false)
        }
        refreshNotice()
        if (allowed && inForeground && reopenDownloadDetails && state.value.release != null) {
            reopenDownloadDetails = false
            mutable.value = state.value.copy(visible = true)
        }
        if (allowed && inForeground) pendingInstallLauncher?.let { launch ->
            pendingInstallLauncher = null
            downloadAndInstall(launch)
        }
        if (allowed && inForeground && restoredDetails != null) {
            val restored = restoredDetails!!
            restoredDetails = null
            val release = state.value.release
            if (release != null && release.versionCode == restored.version && release.sha256 == restored.digest) {
                mutable.value = state.value.copy(detailsPending = true,
                    download = if (restored.interrupted) UpdateDownloadState(UpdateDownloadPhase.Failed,
                        error = "更新操作已中断，请重新下载") else UpdateDownloadState())
            } else check(manual = true)
        } else if (allowed && inForeground && channelChanged) check(manual = true)
        else if (allowed && inForeground && job == null && store.automaticDue(now())) check(manual = false)
    }

    fun checkManually() {
        if (!state.value.ready) return
        if (!networkAllowed) {
            feedback.tryEmit(UpdateFeedback.Preview)
            return
        }
        if (!foreground) return
        check(manual = true)
    }

    private fun check(manual: Boolean) {
        if (job != null) {
            // A manual tap joins an automatic check and receives its result.
            if (manual) {
                manualRequest = true
                mutable.value = state.value.copy(checking = true)
            }
            return
        }
        manualRequest = manual
        val attempt = ++generation
        val analyticsAttempt = analytics.begin()
        val analyticsChannel = store.channel
        mutable.value = state.value.copy(checking = manual,
            visible = state.value.visible && state.value.download.busy, detailsPending = false)
        viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Persist the short cooldown before issuing a request, including cancellation paths.
                store.checked(now(), successful = false, channel = analyticsChannel)
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                val result = checkUpdate()
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                if (result.reason == UpdateCheckReason.UpdateAvailable) result.release?.let {
                    analytics.updateAvailable(analyticsAttempt, it.versionName, analyticsChannel)
                }
                store.checked(now(), successful = true, channel = analyticsChannel, result = result)
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                val known = state.value.release
                val selected = if (known?.required(BuildConfig.VERSION_CODE) == true &&
                    result.release?.required(BuildConfig.VERSION_CODE) != true) known else result.release
                val sameDownload = selected?.let { it.versionCode to it.sha256 } == known?.let { it.versionCode to it.sha256 }
                if (!sameDownload) {
                    downloadGeneration++
                    downloadJob?.cancel(); downloadJob = null
                    awaitingAuthorization = false
                    pendingInstallLauncher = null
                    reopenDownloadDetails = false
                }
                // Only a user-requested check can open a dialog. Automatic checks update the inline notice.
                val message = if (!manualRequest) null else when (result.reason) {
                    UpdateCheckReason.CurrentOrNewer -> UpdateFeedback.Current
                    UpdateCheckReason.NoPublishedRelease -> UpdateFeedback.NoRelease
                    UpdateCheckReason.NoCompatibleRelease -> UpdateFeedback.Incompatible
                    UpdateCheckReason.UpdateAvailable -> null
                }
                mutable.value = AppUpdateUiState(ready = true, release = selected,
                    visible = sameDownload && state.value.visible && state.value.download.busy,
                    download = if (sameDownload) state.value.download else UpdateDownloadState(),
                    detailsPending = selected != null && manualRequest)
                refreshNotice()
                message?.let { feedback.tryEmit(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == attempt) {
                    runCatching { store.checked(now(), successful = false, channel = analyticsChannel) }
                    val forced = store.cachedRelease()?.takeIf { it.required(BuildConfig.VERSION_CODE) }
                    mutable.value = state.value.copy(checking = false,
                        visible = state.value.visible && state.value.download.busy,
                        release = forced ?: state.value.release,
                        download = if (forced != null && !state.value.download.busy) UpdateDownloadState(UpdateDownloadPhase.Failed,
                            error = "更新检查失败，请重试；当前版本仍需要更新") else state.value.download)
                    refreshNotice()
                    if (manualRequest) feedback.tryEmit(if (error is java.io.IOException) UpdateFeedback.Network else UpdateFeedback.Failed)
                }
            } finally {
                if (generation == attempt) { job = null; manualRequest = false }
            }
        }.also { job = it; it.start() }
    }

    fun presentIfAllowed(safeToPrompt: Boolean) {
        if (state.value.required) {
            refreshNotice()
            return
        }
        if (!safeToPrompt) {
            mutable.value = state.value.copy(visible = false)
            return
        }
        val release = state.value.release ?: return
        if (!foreground || !networkAllowed || !state.value.detailsPending) return
        mutable.value = state.value.copy(visible = true, detailsPending = false)
    }

    fun openDetails() {
        if (!foreground || !networkAllowed || !state.value.noticeVisible) return
        mutable.value = state.value.copy(visible = true, detailsPending = false)
    }

    fun dismissNotice() {
        if (state.value.required) return
        val version = state.value.release?.versionCode ?: return
        persistReminder { store.dismissNotice(version, now()) }
    }

    private fun refreshNotice() {
        if (!state.value.ready) return
        val release = state.value.release
        if (release != null && !release.required(BuildConfig.VERSION_CODE) &&
            release.expiresAt?.isAfter(java.time.Instant.ofEpochMilli(now())) == false) {
            downloadGeneration++
            downloadJob?.cancel(); downloadJob = null
            awaitingAuthorization = false; pendingInstallLauncher = null; reopenDownloadDetails = false
            mutable.value = state.value.copy(release = null, visible = false, detailsPending = false,
                download = UpdateDownloadState())
        }
        val required = state.value.release?.required(BuildConfig.VERSION_CODE) == true
        mutable.value = state.value.copy(required = required,
            visible = if (required) networkAllowed && foreground else state.value.visible,
            noticeVisible = !required && remindersEnabled && networkAllowed &&
                state.value.release?.let { store.canNotify(it.versionCode, now()) } == true)
    }

    fun dismiss() {
        if (state.value.required || state.value.download.busy) return
        mutable.value = state.value.copy(visible = false, detailsPending = false)
    }

    fun ignore() {
        if (state.value.required || state.value.download.busy) return
        dismiss(); refreshNotice()
    }

    fun skip() {
        if (state.value.required || state.value.download.busy) return
        val version = state.value.release?.versionCode ?: return
        persistReminder { store.skip(version); dismiss() }
    }

    private fun persistReminder(write: suspend () -> Unit) {
        viewModelScope.launch {
            try { write(); refreshNotice() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { feedback.tryEmit(UpdateFeedback.Failed) }
        }
    }

    fun downloadAndInstall(launch: (android.content.Intent) -> Unit) {
        if (!networkAllowed || !foreground || downloadJob != null) return
        val release = state.value.release ?: return
        if (release.expiresAt?.isAfter(java.time.Instant.ofEpochMilli(now())) == false) {
            mutable.value = state.value.copy(download = UpdateDownloadState(UpdateDownloadPhase.Failed,
                error = "更新信息已过期，正在重新检查，请稍后重试"))
            checkManually()
            return
        }
        val downloadAttempt = ++downloadGeneration
        val analyticsAttempt = analytics.begin()
        var downloadStarted = false
        val binding = release.versionCode to release.sha256
        fun current() = downloadAttempt == downloadGeneration &&
            state.value.release?.let { it.versionCode to it.sha256 } == binding
        fun progress(value: UpdateDownloadState) {
            if (value.phase in listOf(UpdateDownloadPhase.SelectingSource, UpdateDownloadPhase.Downloading)) downloadStarted = true
            mutable.update { latest ->
                if (downloadAttempt == downloadGeneration && latest.release?.let { it.versionCode to it.sha256 } == binding)
                    latest.copy(download = value) else latest
            }
        }
        viewModelScope.launch(start = CoroutineStart.LAZY) {
            var readyForInstall = false
            try {
                progress(UpdateDownloadState(if (installer.apk(release).isFile)
                    UpdateDownloadPhase.Verifying else UpdateDownloadPhase.SelectingSource))
                installer.download(release) { value -> if (current()) progress(value) }
                if (!current() || !networkAllowed) return@launch
                val currentRelease = state.value.release ?: return@launch
                installer.verify(currentRelease)
                if (!current()) return@launch
                require(currentRelease.expiresAt?.isAfter(java.time.Instant.ofEpochMilli(now())) != false) {
                    "更新信息已过期，请重新检查"
                }
                if (downloadStarted) analytics.downloadResult(analyticsAttempt)
                readyForInstall = true
                progress(UpdateDownloadState(UpdateDownloadPhase.Ready, 100))
                // A background download never opens another app unexpectedly.
                if (!foreground) return@launch
                if (!installer.authorized()) {
                    awaitingAuthorization = true
                    progress(UpdateDownloadState(UpdateDownloadPhase.NeedsAuthorization, 100))
                    launch(installer.authorizationIntent())
                } else {
                    progress(UpdateDownloadState(UpdateDownloadPhase.Installing, 100))
                    launch(installer.installIntent(currentRelease))
                    analytics.installHandoff()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) {
                if (current() && downloadStarted && !readyForInstall) {
                    val causes = generateSequence<Throwable>(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8)
                    val kind = if (causes.any { it is IllegalArgumentException || it is IllegalStateException })
                        AnalyticsError.Integrity else AnalyticsError.from(error)
                    analytics.downloadResult(analyticsAttempt, kind)
                }
                if (current()) progress(UpdateDownloadState(UpdateDownloadPhase.Failed,
                    error = if (readyForInstall) "无法打开安装界面，请重试" else error.message ?: "更新失败，请重试",
                    retryInstallation = readyForInstall))
            } finally { if (downloadAttempt == downloadGeneration) downloadJob = null }
        }.also { downloadJob = it; it.start() }
    }

    fun authorizationReturned(launch: (android.content.Intent) -> Unit) {
        if (!awaitingAuthorization) return
        awaitingAuthorization = false
        if (installer.authorized()) {
            if (foreground) downloadAndInstall(launch) else pendingInstallLauncher = launch
        }
        else mutable.value = state.value.copy(download = UpdateDownloadState(UpdateDownloadPhase.NeedsAuthorization,
            100, "请允许安装此来源的应用后重试"))
    }

    fun installationReturned(resultCode: Int) {
        if (state.value.download.phase != UpdateDownloadPhase.Installing) return
        // An installer may return a generic cancellation while handing off to another UI.
        // Only its explicit success/failure result can establish an installation outcome.
        mutable.value = when (resultCode) {
            Activity.RESULT_OK -> {
                reopenDownloadDetails = false
                state.value.copy(checking = false, release = null, visible = false, noticeVisible = false,
                    detailsPending = false, required = false, download = UpdateDownloadState())
            }
            Activity.RESULT_FIRST_USER -> state.value.copy(download = UpdateDownloadState(UpdateDownloadPhase.Failed,
                100, "系统安装失败，请重试", retryInstallation = true))
            else -> state.value.copy(download = UpdateDownloadState(UpdateDownloadPhase.Ready, 100))
        }
    }

    companion object {
        // Release prereleases use beta; Android UI preview builds never check release metadata.
        private fun deviceRequest() = UpdateRequest(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE,
            Build.VERSION.SDK_INT)
    }
}
