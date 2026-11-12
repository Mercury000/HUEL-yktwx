package com.mercury.yktwx

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth

data class MyUiState(
    val profile: PersonalProfile? = null, val avatar: ByteArray? = null,
    val summary: BillSummary? = null, val counts: BillCounts? = null,
    val month: YearMonth = YearMonth.now(), val loading: Boolean = false,
    val profileError: String? = null, val summaryError: String? = null,
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
    val profileErrorKind: ReadFailureKind? = null,
    val summaryErrorKind: ReadFailureKind? = null,
    val homeIdentityErrorKind: ReadFailureKind? = null,
    val countsErrorKind: ReadFailureKind? = null,
    val avatarErrorKind: ReadFailureKind? = null,
    val restoringDisplay: Boolean = false,
) { override fun toString() = "MyUiState([redacted])" }

@HiltViewModel
class MyViewModel internal constructor(private val repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.My)) : ViewModel() {
    @Inject internal constructor(repository: AccountRepository) : this(repository, initialEnvironment = ReadEnvironment.Inactive)
    private val mutable = MutableStateFlow(MyUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var task: Job? = null
    private var displayTask: Job? = null
    private var displayPreparedMonth: YearMonth? = null
    private var refreshedAt = 0L
    private val reads = ReadController(viewModelScope, MY_PAGES, initialEnvironment, ::onActivate)
    private fun period() = BillDateFilter.month(YearMonth.now())
    private var ledgerVersion = 0L
    private var invalidatingLocally = false
    private fun transactionPending(): Boolean = !preview && owner.isNotBlank() && repository.billOverview(owner, period()).pending
    private val transactionRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { transactionPending() }, isReading = { task?.isActive == true },
        retry = { refreshData(metricsOnly = true, silent = true, trigger = ReadTrigger.Transaction) })
    init {
        repository.observeAccount(viewModelScope) { username, renewed ->
            if (!preview) accountChanged(username.orEmpty(), renewed = renewed)
        }
        repository.dataChanges.observe(viewModelScope) { resources ->
            if (!preview && owner.isNotBlank()) {
                val ledger = AccountResource.Data.IncomeBills in resources || AccountResource.Data.ExpenseBills in resources
                val profile = AccountResource.Data.Profile in resources
                val invalidated = if (ledger) applyInvalidation() else if (profile) { invalidateProfile(); true } else false
                if (invalidated && !invalidatingLocally && reads.isVisible && (profile || ledger && reads.environment.page == ReadPage.My)) onActivate()
            }
        }
        viewModelScope.launch { repository.overviewReads.revision.collect { syncOverview() } }
    }

    private fun syncOverview() {
        if (preview || owner.isBlank()) return
        val overview = repository.billOverview(owner, BillDateFilter.month(mutable.value.month))
        val current = mutable.value
        mutable.value = current.copy(summary = overview.summary.value ?: current.summary, counts = overview.counts.value ?: current.counts,
            summaryLoaded = current.summaryLoaded || overview.summary.value != null, countsLoaded = current.countsLoaded || overview.counts.value != null,
            summaryError = current.summaryError.takeIf { overview.summary.value == null || overview.summary.error != null },
            summaryErrorKind = current.summaryErrorKind.takeIf { overview.summary.value == null || overview.summary.error != null },
            countsError = current.countsError.takeIf { overview.counts.value == null || overview.counts.error != null },
            countsErrorKind = current.countsErrorKind.takeIf { overview.counts.value == null || overview.counts.error != null })
    }

    fun accountChanged(username: String, uiPreview: Boolean = false, renewed: Boolean = false) {
        val enabledPreview = UiPreview.enabled && uiPreview
        if (!renewed && owner == username && preview == enabledPreview) return
        generation++; task?.cancel(); task = null; displayTask?.cancel(); refreshedAt = 0L
        transactionRefresh.clear()
        reads.clear()
        ledgerVersion = 0L
        owner = username; preview = enabledPreview
        displayPreparedMonth = null
        mutable.value = MyUiState(restoringDisplay = username.isNotBlank() && !preview)
        if (UiPreview.enabled && preview && username.isNotBlank()) {
            mutable.value = PreviewData.myState()
            refreshedAt = ReadCachePolicy.now()
        } else if (username.isNotBlank()) {
            displayTask = viewModelScope.launch { restoreDisplay(username, YearMonth.now()) }
        }
    }
    fun invalidate() {
        invalidatingLocally = true
        try {
            repository.dataChanges.invalidate(repository.identity(owner), setOf(AccountResource.Data.IncomeBills, AccountResource.Data.ExpenseBills))
            applyInvalidation()
        } finally { invalidatingLocally = false }
    }
    private fun applyInvalidation(): Boolean {
        val versions = repository.dataChanges.state.value.versions
        val version = maxOf(versions[AccountResource.Data.IncomeBills] ?: 0L, versions[AccountResource.Data.ExpenseBills] ?: 0L)
        if (version != 0L && ledgerVersion == version) return false
        ledgerVersion = version
        transactionRefresh.invalidate()
        invalidateProfile()
        return true
    }

    fun invalidateProfile() {
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        reads.cancel(readQuery())
        mutable.value = mutable.value.copy(loading = false)
    }

    fun onReadEnvironment(environment: ReadEnvironment) {
        reads.update(environment)
        val visible = reads.isVisible
        transactionRefresh.setVisible(visible && environment.online != false && !preview && owner.isNotBlank())
        if (!visible) {
            if (transactionPending()) {
                generation++; reads.cancel(readQuery()); task?.cancel(); task = null
                mutable.value = mutable.value.copy(loading = false)
            }
            return
        }
        if (environment.online == false) {
            prepareDisplay()
            return
        }
        onActivate()
    }

    fun setPageVisible(visible: Boolean, online: Boolean = true, recoveryId: Long = 0) {
        onReadEnvironment(ReadEnvironment(
            page = if (visible) ReadPage.My else null,
            foreground = true,
            online = online,
            epoch = recoveryId,
        ))
    }

    fun onActivate() {
        prepareDisplay()
        val current = mutable.value
        if (transactionPending()) {
            refreshData(metricsOnly = current.profileLoaded && current.homeIdentityLoaded && current.profileError == null &&
                current.homeIdentityError == null && current.avatarError == null, trigger = ReadTrigger.Visible)
            return
        }
        if (!ReadCachePolicy.isFresh(refreshedAt) || current.month != YearMonth.now() ||
            current.profileError != null || current.homeIdentityError != null || current.avatarError != null ||
            current.summaryError != null || current.countsError != null) refreshData(trigger = ReadTrigger.Visible)
    }
    fun refresh() = refreshData(trigger = ReadTrigger.Pull)

    private fun prepareDisplay() {
        if (owner.isBlank() || preview) return
        val month = YearMonth.now()
        if (mutable.value.month == month && (displayPreparedMonth == month || displayTask?.isActive == true)) return
        if (mutable.value.month != month) {
            generation++; task?.cancel(); task = null; displayTask?.cancel(); refreshedAt = 0L
            reads.cancel(readQuery())
            mutable.value = mutable.value.copy(month = month, summary = null, counts = null, loaded = false,
                summaryLoaded = false, countsLoaded = false, summaryError = null, summaryErrorKind = null,
                countsError = null, countsErrorKind = null, loading = false)
        }
        mutable.value = mutable.value.copy(restoringDisplay = true)
        val username = owner
        displayTask = viewModelScope.launch { restoreDisplay(username, month) }
    }

    fun recoverRead(recoveryId: Long) = reads.recover(readQuery(), recoveryId) {
        refreshData(trigger = ReadTrigger.Reconnect, silent = true)
    }

    private fun refreshData(metricsOnly: Boolean = false, silent: Boolean = false,
        trigger: ReadTrigger = ReadTrigger.Visible) {
        prepareDisplay()
        if (owner.isBlank() || preview) return
        val query = readQuery()
        val readTrigger = if (trigger.automatic && transactionPending()) ReadTrigger.Transaction else trigger
        if (!reads.canStart(query, readTrigger)) return

        val attempt = generation
        val username = owner
        val month = YearMonth.now()
        val current = mutable.value
        val read = transactionRefresh.started()
        val overview = repository.billOverview(username, BillDateFilter.month(month))
        val forceOverview = trigger == ReadTrigger.Pull
        val readSummary = forceOverview || !overview.summaryFresh
        val readCounts = forceOverview || !overview.countsFresh
        val sameMonth = current.month == month
        mutable.value = current.copy(
            month = month, loading = !silent,
            summary = current.summary.takeIf { sameMonth }, counts = current.counts.takeIf { sameMonth },
            profileErrorKind = current.profileErrorKind.takeIf { metricsOnly }, profileError = current.profileError.takeIf { metricsOnly }, homeIdentityErrorKind = current.homeIdentityErrorKind.takeIf { metricsOnly }, homeIdentityError = current.homeIdentityError.takeIf { metricsOnly },
            avatarErrorKind = current.avatarErrorKind.takeIf { metricsOnly }, avatarError = current.avatarError.takeIf { metricsOnly },
            summaryErrorKind = current.summaryErrorKind.takeUnless { readSummary }, summaryError = current.summaryError.takeUnless { readSummary }, countsErrorKind = current.countsErrorKind.takeUnless { readCounts }, countsError = current.countsError.takeUnless { readCounts },
            profileLoaded = if (metricsOnly) current.profileLoaded else current.profile != null,
            homeIdentityLoaded = if (metricsOnly) current.homeIdentityLoaded else current.homeIdentity != null,
            summaryLoaded = sameMonth && current.summary != null, countsLoaded = sameMonth && current.counts != null)
        task = reads.launch(query, readTrigger, retry = { refreshData(trigger = ReadTrigger.Retry, silent = true) }) {
            displayTask?.join()
            if (attempt != generation) return@launch
            if (mutable.value.profile == null && mutable.value.summary == null && mutable.value.counts == null)
                restoreDisplay(username, month)
            if (!startNetwork()) {
                if (attempt == generation) mutable.value = mutable.value.copy(loading = false,
                    profileError = current.profileError, profileErrorKind = current.profileErrorKind,
                    homeIdentityError = current.homeIdentityError, homeIdentityErrorKind = current.homeIdentityErrorKind,
                    avatarError = current.avatarError, avatarErrorKind = current.avatarErrorKind,
                    summaryError = current.summaryError, summaryErrorKind = current.summaryErrorKind,
                    countsError = current.countsError, countsErrorKind = current.countsErrorKind)
                return@launch
            }
            var failure: ReadFailureKind? = null
            var cancelled = true
            try {
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
                        publish { it.copy(profileErrorKind = e.readFailureKind(), profileError = message(e), profileLoaded = true, homeIdentityLoaded = true) }
                        return@identity
                    }
                    if (attempt != generation) return@identity
                    publish { it.copy(profile = fresh, profileErrorKind = null, profileError = null, profileLoaded = true,
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
                                        avatarErrorKind = ReadFailureKind.Other.takeIf { avatar == null && fresh.avatarPath != null },
                                        avatarError = if (avatar == null && fresh.avatarPath != null) "头像暂时无法更新" else null)
                                }
                                if (avatar != null) repository.saveDisplay(username, DisplayKeys.avatar(fresh.sno, requireNotNull(fresh.avatarPath)), avatar, DisplayCodecs.Avatar)
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(avatarErrorKind = e.readFailureKind(), avatarError = message(e)) } }
                        }
                        launch {
                            try {
                                val identity = repository.homeIdentity(username, fresh.sno)
                                publish { it.copy(homeIdentity = identity, homeIdentityErrorKind = null, homeIdentityError = null, homeIdentityLoaded = true) }
                                repository.saveDisplay(username, DisplayKeys.identity(fresh.sno), identity, DisplayCodecs.Identity)
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(homeIdentityErrorKind = e.readFailureKind(), homeIdentityError = message(e), homeIdentityLoaded = true) } }
                        }
                    }
                }
                if (readSummary) launch {
                    try {
                        val summary = repository.readBillSummary(username, BillDateFilter.month(month), forceOverview)
                        publish { it.copy(summary = summary, summaryErrorKind = null, summaryError = null, summaryLoaded = true) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        publish { it.copy(summaryErrorKind = e.readFailureKind(), summaryError = message(e), summaryLoaded = true) }
                    }
                }
                if (readCounts) launch {
                    try {
                        val counts = repository.readBillCounts(username, BillDateFilter.month(month), forceOverview)
                        publish { it.copy(counts = counts, countsErrorKind = null, countsError = null, countsLoaded = true) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        publish { it.copy(countsErrorKind = e.readFailureKind(), countsError = message(e), countsLoaded = true) }
                    }
                }
            }
            if (attempt != generation) return@launch
            publish { it.copy(loading = false, loaded = true) }
            if (mutable.value.profileError == null && mutable.value.homeIdentityError == null && mutable.value.summaryError == null && mutable.value.countsError == null)
                refreshedAt = if (transactionPending() || epoch != reads.currentEpoch) 0L else ReadCachePolicy.now()
            failure = readFailureKind(mutable.value.profileErrorKind, mutable.value.homeIdentityErrorKind,
                mutable.value.avatarErrorKind, mutable.value.summaryErrorKind, mutable.value.countsErrorKind)
            cancelled = false
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!cancelled) complete(failure)
                transactionRefresh.finished(read)
            }
        }
    }

    private fun readQuery() = ReadQuery(owner, ReadQuery.Resource.Profile, listOf(mutable.value.month))
    /** Prepare local display only; activation still owns every network read and freshness check. */
    private suspend fun restoreDisplay(username: String, month: YearMonth) {
        if (displayPreparedMonth == month) return
        val profile = repository.cachedDisplay(username, DisplayKeys.Profile, DisplayCodecs.Profile)
        val identity = profile?.let { repository.cachedDisplay(username, DisplayKeys.identity(it.sno), DisplayCodecs.Identity) }
        val summary = repository.billOverview(username, BillDateFilter.month(month)).summary.value ?: repository.cachedDisplay(username, DisplayKeys.summary(month.atDay(1).toString(), month.atEndOfMonth().toString()), DisplayCodecs.Summary)
        val avatar = profile?.avatarPath?.let { repository.cachedDisplay(username, DisplayKeys.avatar(profile.sno, it), DisplayCodecs.Avatar) }
        val counts = repository.billOverview(username, BillDateFilter.month(month)).counts.value ?: repository.cachedDisplay(username, DisplayKeys.counts(month.atDay(1).toString(), month.atEndOfMonth().toString()), DisplayCodecs.Counts)
        currentCoroutineContext().ensureActive()
        if (owner != username || mutable.value.month != month) return
        displayPreparedMonth = month
        mutable.value = mutable.value.copy(profile = profile, avatar = avatar, homeIdentity = identity, summary = summary, counts = counts,
            profileLoaded = profile != null, homeIdentityLoaded = identity != null, summaryLoaded = summary != null, countsLoaded = counts != null,
            displayRestored = profile != null || summary != null || counts != null, restoringDisplay = false)
    }
    private fun message(e: Exception) = (e as? YktException)?.message ?: "读取失败，请稍后重试"

    private companion object {
        val MY_PAGES = setOf(ReadPage.My, ReadPage.Profile)
    }
}
