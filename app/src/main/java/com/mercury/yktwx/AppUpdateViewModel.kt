package com.mercury.yktwx

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.launch

internal enum class UpdateFeedback { Current, NoRelease, Incompatible, Network, Failed, Preview }
internal data class AppUpdateUiState(
    val checking: Boolean = false,
    val release: UpdateRelease? = null, val visible: Boolean = false,
    val noticeVisible: Boolean = false,
    val detailsPending: Boolean = false,
)

internal class AppUpdateViewModel internal constructor(
    app: Application, private val store: UpdateCheckStore,
    private val checkUpdate: suspend () -> UpdateCheckResult,
    private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, deviceRequest())
    private constructor(app: Application, request: UpdateRequest) : this(app,
        UpdateCheckStore(app, request), { AppUpdateApi().check(request) })

    private val mutable = MutableStateFlow(AppUpdateUiState(release = store.cachedRelease()))
    val state = mutable.asStateFlow()
    private val feedback = MutableSharedFlow<UpdateFeedback>(extraBufferCapacity = 1)
    val messages = feedback.asSharedFlow()
    private var job: Job? = null
    private var generation = 0L
    private var automaticEnabled = false
    private var networkAllowed = false
    private var foreground = false
    private var manualRequest = false

    fun configure(automatic: Boolean, allowed: Boolean, inForeground: Boolean) {
        automaticEnabled = automatic
        networkAllowed = allowed
        foreground = inForeground
        if (!allowed || !inForeground || !automatic && !manualRequest) {
            generation++
            job?.cancel(); job = null
            manualRequest = false
            mutable.value = state.value.copy(checking = false, visible = false, detailsPending = false)
        }
        refreshNotice()
        if (allowed && inForeground && automatic && job == null && store.automaticDue(now())) check(manual = false)
    }

    fun checkManually() {
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
        // Reserve the short cooldown while a request is pending or cancelled.
        store.checked(now(), successful = false)
        mutable.value = state.value.copy(checking = manual, visible = false, detailsPending = false)
        viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = checkUpdate()
                currentCoroutineContext().ensureActive()
                if (generation != attempt) return@launch
                store.checked(now(), successful = true)
                store.cacheRelease(result.release)
                // Only a user-requested check can open a dialog. Automatic checks update the inline notice.
                val message = if (!manualRequest) null else when (result.reason) {
                    UpdateCheckReason.CurrentOrNewer -> UpdateFeedback.Current
                    UpdateCheckReason.NoPublishedRelease -> UpdateFeedback.NoRelease
                    UpdateCheckReason.NoCompatibleRelease -> UpdateFeedback.Incompatible
                    UpdateCheckReason.UpdateAvailable -> null
                }
                mutable.value = AppUpdateUiState(release = result.release,
                    detailsPending = result.release != null && manualRequest)
                refreshNotice()
                message?.let { feedback.tryEmit(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == attempt) {
                    store.checked(now(), successful = false)
                    mutable.value = state.value.copy(checking = false, visible = false)
                    if (manualRequest) feedback.tryEmit(if (error is java.io.IOException) UpdateFeedback.Network else UpdateFeedback.Failed)
                }
            } finally {
                if (generation == attempt) { job = null; manualRequest = false }
            }
        }.also { job = it; it.start() }
    }

    fun presentIfAllowed(safeToPrompt: Boolean) {
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
        state.value.release?.let { store.dismissNotice(it.versionCode, now()) }
        refreshNotice()
    }

    private fun refreshNotice() {
        mutable.value = state.value.copy(noticeVisible = automaticEnabled && networkAllowed &&
            state.value.release?.let { store.canNotify(it.versionCode, now()) } == true)
    }

    fun dismiss() {
        mutable.value = state.value.copy(visible = false, detailsPending = false)
    }

    companion object {
        // GitHub prereleases use the preview channel; Android UI preview builds do not select it.
        private fun deviceRequest() = UpdateRequest(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE,
            Build.VERSION.SDK_INT)
    }
}
