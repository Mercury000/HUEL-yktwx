package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth

data class MyUiState(
    val profile: PersonalProfile? = null, val avatar: ByteArray? = null,
    val summary: BillSummary? = null, val counts: BillCounts? = null,
    val month: YearMonth = YearMonth.now(), val loading: Boolean = false,
    val profileError: String? = null, val summaryError: String? = null,
    val needsLogin: Boolean = false,
    val homeIdentity: HomeCardIdentity? = null,
    val homeIdentityError: String? = null,
    val loaded: Boolean = false,
    val profileLoaded: Boolean = loaded,
    val summaryLoaded: Boolean = loaded,
    val countsLoaded: Boolean = loaded,
    val homeIdentityLoaded: Boolean = loaded,
    val countsError: String? = null,
    val avatarError: String? = null,
    val displayRestored: Boolean = false,
) { override fun toString() = "MyUiState([redacted])" }

class MyViewModel internal constructor(app: Application, private val repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(MyUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var task: Job? = null
    private var displayTask: Job? = null
    private var displayPreparedMonth: YearMonth? = null
    private var refreshedAt = 0L
    private fun period() = BillDateFilter.month(YearMonth.now())
    private fun summaryKey() = DisplayKeys.summary(period().timeFrom, period().timeTo)
    private fun countsKey() = DisplayKeys.counts(period().timeFrom, period().timeTo)
    private fun transactionPending(): Boolean = !preview && !mutable.value.needsLogin && transactionRefresh.affects(period()) &&
        (transactionRefresh.pending(summaryKey()) || mutable.value.summaryError != null ||
            transactionRefresh.pending(countsKey()) || mutable.value.countsError != null)
    private val transactionRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { transactionPending() }, isReading = { task?.isActive == true },
        retry = { refreshData(metricsOnly = true, silent = true) })
    fun accountChanged(username: String, uiPreview: Boolean = false) {
        val enabledPreview = UiPreview.enabled && uiPreview
        if (owner == username && preview == enabledPreview) return
        generation++; task?.cancel(); task = null; displayTask?.cancel(); refreshedAt = 0L
        transactionRefresh.clear()
        owner = username; preview = enabledPreview
        displayPreparedMonth = null
        mutable.value = MyUiState()
        if (UiPreview.enabled && preview && username.isNotBlank()) {
            mutable.value = PreviewData.myState()
            refreshedAt = ReadCachePolicy.now()
        } else if (username.isNotBlank()) {
            val attempt = generation
            displayTask = viewModelScope.launch { restoreDisplay(username, YearMonth.now(), attempt) }
        }
    }
    fun invalidate() {
        transactionRefresh.invalidate()
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        mutable.value = mutable.value.copy(loading = false)
    }

    fun setPageVisible(visible: Boolean) {
        transactionRefresh.setVisible(visible && !preview && owner.isNotBlank())
        if (visible) onActivate()
        else if (transactionPending()) {
            generation++; task?.cancel(); task = null
            mutable.value = mutable.value.copy(loading = false)
        }
    }

    fun onActivate() {
        val current = mutable.value
        if (transactionPending()) {
            refreshData(metricsOnly = current.profileLoaded && current.homeIdentityLoaded && current.profileError == null &&
                current.homeIdentityError == null && current.avatarError == null)
            return
        }
        if (!ReadCachePolicy.isFresh(refreshedAt) || current.month != YearMonth.now() ||
            current.profileError != null || current.homeIdentityError != null || current.avatarError != null ||
            current.summaryError != null || current.countsError != null) refresh()
    }
    fun refresh() = refreshData()

    private fun refreshData(metricsOnly: Boolean = false, silent: Boolean = false) {
        if (owner.isBlank() || preview || task?.isActive == true) return
        val attempt = generation
        val username = owner
        val month = YearMonth.now()
        val current = mutable.value
        val read = transactionRefresh.started()
        val summaryKey = DisplayKeys.summary(month.atDay(1).toString(), month.atEndOfMonth().toString())
        val countsKey = DisplayKeys.counts(month.atDay(1).toString(), month.atEndOfMonth().toString())
        val readSummary = !metricsOnly || transactionRefresh.pending(summaryKey) || current.summaryError != null || current.summary == null
        val readCounts = !metricsOnly || transactionRefresh.pending(countsKey) || current.countsError != null || current.counts == null
        val sameMonth = current.month == month
        mutable.value = current.copy(
            month = month, loading = !silent, needsLogin = false,
            summary = current.summary.takeIf { sameMonth }, counts = current.counts.takeIf { sameMonth },
            profileError = current.profileError.takeIf { metricsOnly }, homeIdentityError = current.homeIdentityError.takeIf { metricsOnly },
            avatarError = current.avatarError.takeIf { metricsOnly },
            summaryError = current.summaryError.takeUnless { readSummary }, countsError = current.countsError.takeUnless { readCounts },
            profileLoaded = if (metricsOnly) current.profileLoaded else current.profile != null,
            homeIdentityLoaded = if (metricsOnly) current.homeIdentityLoaded else current.homeIdentity != null,
            summaryLoaded = sameMonth && current.summary != null, countsLoaded = sameMonth && current.counts != null)
        task = viewModelScope.launch {
            displayTask?.join()
            if (attempt != generation) return@launch
            if (mutable.value.profile == null && mutable.value.summary == null && mutable.value.counts == null)
                restoreDisplay(username, month, attempt)
            val restored = mutable.value
            // Each branch updates the latest state on Main, so a late avatar cannot undo a summary.
            fun publish(change: (MyUiState) -> MyUiState) {
                if (attempt == generation) mutable.value = change(mutable.value)
            }
            coroutineScope {
                if (!metricsOnly) launch identity@{
                    val fresh = try { repository.personalProfile(username) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        publish { it.copy(profileError = message(e), profileLoaded = true, homeIdentityLoaded = true,
                            needsLogin = it.needsLogin || isAuthenticationFailure(e)) }
                        return@identity
                    }
                    if (attempt != generation) return@identity
                    publish { it.copy(profile = fresh, profileError = null, profileLoaded = true,
                        // Never associate an older student's card identity with a new profile.
                        homeIdentity = it.homeIdentity.takeIf { old -> old?.sno == fresh.sno },
                        homeIdentityLoaded = it.homeIdentityLoaded && restored.profile?.sno == fresh.sno,
                        avatar = it.avatar.takeIf { restored.profile?.sno == fresh.sno && restored.profile.avatarPath == fresh.avatarPath }) }
                    repository.saveDisplay(username, DisplayKeys.Profile, fresh, DisplayCodecs.Profile)
                    coroutineScope {
                        launch {
                            try {
                                val avatar = fresh.avatarPath?.let { repository.profileAvatar(it) }
                                publish { latest ->
                                    val samePath = restored.profile?.sno == fresh.sno && restored.profile.avatarPath == fresh.avatarPath
                                    latest.copy(avatar = if (samePath && (avatar == null || avatar.contentEquals(latest.avatar))) latest.avatar else avatar,
                                        avatarError = if (avatar == null && fresh.avatarPath != null) "头像暂时无法更新" else null)
                                }
                                if (avatar != null) repository.saveDisplay(username, DisplayKeys.avatar(fresh.sno, requireNotNull(fresh.avatarPath)), avatar, DisplayCodecs.Avatar)
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(avatarError = message(e),
                                needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                        }
                        launch {
                            try {
                                val identity = repository.homeIdentity(username, fresh.sno)
                                publish { it.copy(homeIdentity = identity, homeIdentityError = null, homeIdentityLoaded = true) }
                                repository.saveDisplay(username, DisplayKeys.identity(fresh.sno), identity, DisplayCodecs.Identity)
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(homeIdentityError = message(e), homeIdentityLoaded = true,
                                needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                        }
                    }
                }
                if (readSummary) launch {
                    try {
                        val summary = repository.billSummary(month.atDay(1).toString(), month.atEndOfMonth().toString(), username)
                        transactionRefresh.observe(read, summaryKey, summary)
                        publish { it.copy(summary = summary, summaryError = null, summaryLoaded = true) }
                        repository.saveDisplay(username, DisplayKeys.summary(month.atDay(1).toString(), month.atEndOfMonth().toString()), summary, DisplayCodecs.Summary)
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { publish { it.copy(summaryError = message(e), summaryLoaded = true,
                        needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                }
                if (readCounts) launch {
                    try {
                        val counts = repository.billCounts(month.atDay(1).toString(), month.atEndOfMonth().toString(), username)
                        transactionRefresh.observe(read, countsKey, counts)
                        publish { it.copy(counts = counts, countsError = null, countsLoaded = true) }
                        repository.saveDisplay(username, DisplayKeys.counts(month.atDay(1).toString(), month.atEndOfMonth().toString()), counts, DisplayCodecs.Counts)
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { publish { it.copy(countsError = message(e), countsLoaded = true,
                        needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                }
            }
            if (attempt != generation) return@launch
            publish { it.copy(loading = false, loaded = true) }
            if (mutable.value.profileError == null && mutable.value.homeIdentityError == null && mutable.value.summaryError == null && mutable.value.countsError == null)
                refreshedAt = if (transactionPending()) 0L else ReadCachePolicy.now()
            transactionRefresh.finished(read)
        }
    }
    /** Prepare local display only; activation still owns every network read and freshness check. */
    private suspend fun restoreDisplay(username: String, month: YearMonth, attempt: Long) {
        if (displayPreparedMonth == month) return
        val profile = repository.cachedDisplay(username, DisplayKeys.Profile, DisplayCodecs.Profile)
        val identity = profile?.let { repository.cachedDisplay(username, DisplayKeys.identity(it.sno), DisplayCodecs.Identity) }
        val summary = repository.cachedDisplay(username, DisplayKeys.summary(month.atDay(1).toString(), month.atEndOfMonth().toString()), DisplayCodecs.Summary)
        val avatar = profile?.avatarPath?.let { repository.cachedDisplay(username, DisplayKeys.avatar(profile.sno, it), DisplayCodecs.Avatar) }
        val counts = repository.cachedDisplay(username, DisplayKeys.counts(month.atDay(1).toString(), month.atEndOfMonth().toString()), DisplayCodecs.Counts)
        if (attempt != generation || mutable.value.month != month) return
        displayPreparedMonth = month
        mutable.value = mutable.value.copy(profile = profile, avatar = avatar, homeIdentity = identity, summary = summary, counts = counts,
            profileLoaded = profile != null, homeIdentityLoaded = identity != null, summaryLoaded = summary != null, countsLoaded = counts != null,
            displayRestored = profile != null || summary != null || counts != null)
    }
    private fun message(e: Exception) = (e as? YktException)?.message ?: "读取失败，请稍后重试"
    private fun isAuthenticationFailure(e: Exception) = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
}
