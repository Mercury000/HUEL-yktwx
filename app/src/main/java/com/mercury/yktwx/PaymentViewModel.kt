package com.mercury.yktwx

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PaymentUiState(
    val needsLogin: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val username: String = "",
    val code: String? = null,
    val secondsLeft: Long = 0,
    val preview: Boolean = false,
    val refreshing: Boolean = false,
    val restoringLogin: Boolean = false,
    // Account presentation outlives a code batch; never keep old payment codes
    // merely to preserve the card while a refresh is in flight.
    val account: PaymentAccount? = null,
    val problem: PaymentCodeProblem? = null,
    val accountLoading: Boolean = false,
    val accountError: String? = null,
    val accountFromCache: Boolean = false,
    val balanceLoading: Boolean = false,
    // Account screens verify the physical card independently of barcode availability.
    val accountCard: CampusCard? = null,
    val cardStatusLoading: Boolean = false,
    val cardStatusError: String? = null,
    val errorFields: Set<FormField> = emptySet(),
    val errorKind: ReadFailureKind? = null,
    val accountErrorKind: ReadFailureKind? = null,
    val cardStatusErrorKind: ReadFailureKind? = null,
) {
    override fun toString() = "PaymentUiState([redacted])"
}

class PaymentViewModel internal constructor(app: Application, private val repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.Payment),
    private val analytics: AppAnalytics = AppAnalytics.from(app)) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app), initialEnvironment = ReadEnvironment.Inactive)
    private val mutable = MutableStateFlow(PaymentUiState(restoringLogin = true))
    val state = mutable.asStateFlow()
    val sessionState = repository.sessionState
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var initialized = false
    private var foreground = false
    private var pageVisible = true
    private var pageCovered = false
    private var codeExpiresAt = 0L
    private var resumeNeedsRefresh = false
    private var ticker: Job? = null
    private var request: Job? = null
    private var paymentCardRequest: Job? = null
    private var accountRequest: Job? = null
    private var accountReadVersion = 0L
    private var accountReadAt = 0L
    private var accountPageVisible = false
    private var logoutJob: Job? = null
    private var preview = false
    private var previewLost = false
    private var requestVersion = 0L
    // Local restoration survives pauses and consent gating; account revocation still invalidates it.
    private var restorationVersion = 0L
    private var loginInProgress = false
    private var handledLoginGeneration: Long? = null
    private val mutableSuccess = MutableStateFlow<Long?>(null)
    val paymentSuccess = mutableSuccess.asStateFlow()
    private data class Balance(val username: String, val account: String, val payacc: String,
        val paytype: String, val cents: Long)
    private var lastLiveBalance: Balance? = null
    private var readPageVisible = true
    private val reads = ReadController(viewModelScope, PAYMENT_PAGES, initialEnvironment, ::resumeRead)
    private val environment get() = reads.environment
    private val networkReadAllowed get() = reads.canRead && foreground
    private fun paymentReadKey() = ReadQuery(mutable.value.username, ReadQuery.Resource.Payment)
    private fun accountReadKey() = ReadQuery(mutable.value.username, ReadQuery.Resource.Account)
    private fun motionReadKey() = ReadQuery(mutable.value.username, ReadQuery.Resource.Motion)
    private fun balanceKey(account: PaymentAccount) = "${account.account}:${account.payacc}:${account.paytype}"
    private fun balancePending(): Boolean = mutable.value.account?.let { balanceRefresh.needsTransactionRead(balanceKey(it)) } ?: false
    private val balanceRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { balancePending() },
        isReading = { mutable.value.busy || accountRequest?.isActive == true },
        retry = { refreshAccount(silent = true, balanceOnly = true, trigger = ReadTrigger.Transaction) })
    private var motionActive = false
    private var balanceCheck: Job? = null
    private var detectionVersion = 0L
    private data class PaymentTurn(val username: String, val account: PaymentAccount, val version: Long, val identity: AccountIdentity?)
    private var paymentTurn: PaymentTurn? = null

    init {
        viewModelScope.launch {
            var previous: SessionState? = null
            sessionState.collect { session ->
                if (!session.restored || preview) return@collect
                val old = previous
                previous = session
                if (session.username == null) {
                    if (old == null && session.endReason == null) return@collect
                    val preserveLogin = loginInProgress && session.endReason != SessionEndReason.Logout
                    val ownLoginResult = session.generation == handledLoginGeneration
                    if (!ownLoginResult && old?.generation != session.generation) clearPaymentData(cancelLogin = !preserveLogin)
                    val username = mutable.value.username.takeUnless { session.endReason == SessionEndReason.Logout }.orEmpty()
                    // A late lifecycle emission must preserve the submitted form's specific error.
                    mutable.value = if (ownLoginResult) mutable.value.copy(needsLogin = true,
                        busy = session.clearing, restoringLogin = false)
                    else PaymentUiState(needsLogin = true, busy = session.clearing || preserveLogin,
                        username = username, error = session.endReason?.message, errorKind = session.endReason?.errorKind)
                    if (session.endReason == SessionEndReason.PasswordChanged && old?.generation != session.generation)
                        notifications.trySend(session.endReason.message!!)
                } else if (old != null && old.generation != session.generation && !loginInProgress &&
                    session.generation != handledLoginGeneration) {
                    clearPaymentData()
                    mutable.value = PaymentUiState(needsLogin = false, username = session.username)
                    if (initialized && foreground && pageVisible) refresh(trigger = ReadTrigger.Visible)
                    else if (initialized && foreground && accountPageVisible) refreshAccount(trigger = ReadTrigger.Visible)
                }
            }
        }
        repository.dataChanges.observe(viewModelScope) { resources ->
            if (!preview && !mutable.value.needsLogin) {
                if (AccountResource.Data.Cards in resources) cardStateChanged()
                else if (AccountResource.Data.Balance in resources) invalidateBalance()
            }
        }
        viewModelScope.launch { repository.cardReads.revision.collect { syncCard() } }
    }

    private fun syncCard() {
        val state = mutable.value
        val account = state.account ?: return
        if (preview || state.needsLogin) return
        val snapshot = repository.cardSnapshot(state.username, account.account)
        val card = snapshot.value ?: return
        if (!snapshot.fresh() || card.status < 0) return
        mutable.value = state.copy(accountCard = card, cardStatusErrorKind = null, cardStatusError = null)
    }

    private fun clearPaymentData(cancelLogin: Boolean = true) {
        restorationVersion++
        accountReadAt = 0L
        balanceRefresh.clear(); lastLiveBalance = null
        reads.clear()
        cancelAccountRead()
        cancelPaymentCardRead()
        setPaymentMotionActive(false); mutableSuccess.value = null
        ticker?.cancel(); codeExpiresAt = 0L; resumeNeedsRefresh = false
        if (cancelLogin) {
            requestVersion++; request?.cancel(); request = null; loginInProgress = false
        }
    }

    internal fun setPaymentMotionActive(active: Boolean) {
        motionActive = active
        if (!active) cancelPaymentDetection()
    }
    internal fun cancelPaymentDetection() {
        detectionVersion++
        reads.cancel(motionReadKey())
        balanceCheck?.cancel(); balanceCheck = null; paymentTurn = null
    }
    internal fun paymentTurnStarted() {
        val current = mutable.value
        if (!networkReadAllowed || !foreground || !motionActive || preview || current.needsLogin || current.busy ||
            current.code == null || current.accountFromCache || current.problem != null || mutableSuccess.value != null || balanceCheck?.isActive == true) return
        val account = current.account ?: return
        paymentTurn = PaymentTurn(current.username, account, ++detectionVersion, repository.identity(current.username))
    }
    internal fun paymentTurnReturned() {
        val turn = paymentTurn ?: return
        if (balanceCheck?.isActive == true || !isCurrent(turn)) return
        val query = motionReadKey()
        balanceCheck = reads.launch(query, ReadTrigger.Motion, retry = {}) {
            if (!startNetwork()) return@launch
            try {
                // Bounded silent retries cover a balance endpoint that lags behind the scanner.
                for (wait in longArrayOf(0, 350, 700)) {
                    delay(wait)
                    if (!isCurrent(turn)) return@launch
                    val updated = try {
                        withContext(Dispatchers.IO) { repository.paymentAccount(turn.username) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    if (updated == null) continue
                    if (!isCurrent(turn)) return@launch
                    val baseline = turn.account
                    if (updated.account != baseline.account || updated.payacc != baseline.payacc ||
                        updated.paytype != baseline.paytype) return@launch
                    if (updated.balanceCents < 0 || baseline.balanceCents < 0) return@launch
                    if (updated.balanceCents < baseline.balanceCents) {
                        paymentTurn = null
                        mutableSuccess.value = baseline.balanceCents - updated.balanceCents
                        // Preserve the confirmed balance even if the following barcode request fails.
                        recordBalance(turn.username, updated, balanceRefresh.started(), notify = false)
                        mutable.value = mutable.value.copy(account = updated, accountFromCache = false)
                        viewModelScope.launch { repository.saveDisplay(turn.username, DisplayKeys.Account, updated, DisplayCodecs.Account) }
                        repository.dataChanges.transaction(turn.identity, TransactionUpdate(turn.username, balance = false, income = false))
                        requestVersion++; request?.cancel(); ticker?.cancel()
                        mutable.value = mutable.value.copy(busy = false)
                        refresh(trigger = ReadTrigger.Transaction)
                        return@launch
                    }
                }
            } finally {
                complete(null)
                if (turn.version == detectionVersion) paymentTurn = null
            }
        }
    }
    private fun isCurrent(turn: PaymentTurn): Boolean {
        val current = mutable.value
        val account = current.account ?: return false
        return turn.version == detectionVersion && networkReadAllowed && foreground && motionActive && !current.needsLogin &&
            current.username == turn.username && current.problem == null && mutableSuccess.value == null &&
            account.account == turn.account.account && account.payacc == turn.account.payacc && account.paytype == turn.account.paytype
    }
    fun dismissPaymentSuccess() { mutableSuccess.value = null }

    fun initialize(uiPreview: Boolean = false, previewLost: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = UiPreview.enabled && uiPreview
        this.previewLost = previewLost
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.paymentState(previewLost)
            return
        }
        val initializationVersion = restorationVersion
        viewModelScope.launch {
            try {
                val username = withContext(Dispatchers.IO) { repository.username() }
                val exists = username.isNotBlank()
                val cached = if (exists) repository.cachedDisplay(username, DisplayKeys.Account, DisplayCodecs.Account) else null
                if (initializationVersion != restorationVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = !exists, username = username, account = cached,
                    accountFromCache = cached != null, problem = PaymentCodeProblem.StatusUnverified.takeIf { cached != null })
                if (exists && foreground && pageVisible) refresh(trigger = ReadTrigger.Visible)
                else if (exists && foreground && accountPageVisible) refreshAccount(trigger = ReadTrigger.Visible)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (initializationVersion != restorationVersion) return@launch
                mutable.value = PaymentUiState(errorKind = ReadFailureKind.Other, error = "无法读取本机登录信息，请重新登录")
            }
        }
    }
    fun onForeground() {
        foreground = true
        reads.update(environment.copy(foreground = true, resumeEpoch = environment.resumeEpoch + 1))
        updateBalanceVisibility()
        if (UiPreview.enabled && preview && pageVisible) mutable.value = PreviewData.paymentState(previewLost)
        else if (pageVisible && initialized && !preview && !mutable.value.restoringLogin && !mutable.value.needsLogin && !mutable.value.busy) refresh(trigger = ReadTrigger.Visible)
        else if (accountPageVisible) refreshAccount(trigger = ReadTrigger.Visible)
    }
    /** A modal keeps the payment page live behind it; an offscreen page discards its code. */
    fun setPageVisible(visible: Boolean, preserveDisplay: Boolean = false) {
        val covered = !visible && preserveDisplay
        if (pageVisible == visible && pageCovered == covered) return
        val wasDisplayed = paymentDisplayVisible()
        pageVisible = visible
        pageCovered = covered
        updateBalanceVisibility()
        if (!paymentDisplayVisible()) pausePaymentDisplay()
        else if (covered) {
            // The code and its expiry keep advancing behind a root overlay, but
            // turning the phone must not trigger payment-success detection there.
            setPaymentMotionActive(false)
            if (!wasDisplayed && foreground && initialized) resumePaymentDisplay()
        }
        else if (!wasDisplayed && UiPreview.enabled && preview) mutable.value = PreviewData.paymentState(previewLost)
        else if (foreground && initialized && (!wasDisplayed || mutable.value.code == null || balancePending())) resumePaymentDisplay()
    }
    fun onBackground() {
        foreground = false
        reads.update(environment.copy(foreground = false))
        updateBalanceVisibility()
        cancelAccountRead()
        pausePaymentDisplay()
    }
    /** Balance and physical card state belong to account screens; never fetch a barcode here. */
    fun setAccountPageVisible(visible: Boolean) {
        if (accountPageVisible == visible) return
        accountPageVisible = visible
        updateBalanceVisibility()
        if (visible) refreshAccount(trigger = ReadTrigger.Visible) else cancelAccountRead()
    }
    /**
     * The shell publishes one immutable snapshot of visibility, lifecycle and connectivity.
     * Every read decision is derived from it; the ViewModel never probes the network itself.
     */
    fun onReadEnvironment(environment: ReadEnvironment) {
        reads.update(environment)
        foreground = environment.foreground
        readPageVisible = reads.isVisible
        setPageVisible(environment.paymentVisible, preserveDisplay = environment.paymentCovered)
        setAccountPageVisible(environment.accountVisible)
        updateBalanceVisibility()
        if (!networkReadAllowed) { cancelPaymentDetection(); return }
        resumeRead()
    }

    /** Re-evaluate what the current page still needs; a finished request may have satisfied it. */
    private fun resumeRead() {
        if (!foreground || !readPageVisible || environment.online == false) return
        if (paymentDisplayVisible() && (resumeNeedsRefresh || mutable.value.code == null || balancePending())) refresh(trigger = ReadTrigger.Visible)
        else if (accountPageVisible) refreshAccount(trigger = ReadTrigger.Visible)
    }

    /** Compatibility wrapper for callers that publish the eligibility bit directly. */
    fun setReadContext(paymentVisible: Boolean, paymentCovered: Boolean, accountVisible: Boolean,
        eligible: Boolean, online: Boolean, recoveryId: Long) {
        onReadEnvironment(ReadEnvironment(
            page = if (eligible) ReadPage.Payment else null,
            foreground = true,
            online = online,
            epoch = recoveryId,
            paymentVisible = paymentVisible,
            paymentCovered = paymentCovered,
            accountVisible = accountVisible,
        ))
    }

    fun setReadPageVisible(visible: Boolean, online: Boolean = true, recoveryId: Long = 0) {
        onReadEnvironment(environment.copy(
            page = if (!visible) null else if (accountPageVisible) ReadPage.Account else ReadPage.Payment,
            foreground = foreground, online = online, epoch = recoveryId,
            paymentVisible = pageVisible, paymentCovered = pageCovered, accountVisible = accountPageVisible,
        ))
    }
    fun recoverRead(recoveryId: Long) {
        if (pageVisible) reads.recover(paymentReadKey(), recoveryId) { refresh(trigger = ReadTrigger.Reconnect) }
        else if (accountPageVisible) reads.recover(accountReadKey(), recoveryId) {
            refreshAccount(silent = true, trigger = ReadTrigger.Reconnect)
        }
    }
    private fun updateBalanceVisibility() = balanceRefresh.setVisible(
        foreground && readPageVisible && (reads.onlineOrNull != false) && !pageCovered && (pageVisible || accountPageVisible))
    fun invalidateBalance() {
        balanceRefresh.invalidate()
        cancelAccountRead()
        accountReadAt = 0L
        resumeNeedsRefresh = true
        if (mutable.value.busy) {
            requestVersion++; request?.cancel(); ticker?.cancel()
            reads.cancel(paymentReadKey())
            mutable.value = mutable.value.copy(busy = false, refreshing = false, balanceLoading = false)
        }
        if (foreground && readPageVisible && !pageCovered) {
            if (pageVisible) refresh(trigger = ReadTrigger.Transaction)
            else if (accountPageVisible) refreshAccount(trigger = ReadTrigger.Transaction)
        }
    }

    fun refreshAccount(manual: Boolean = false, silent: Boolean = false, balanceOnly: Boolean = false,
        trigger: ReadTrigger = if (manual) ReadTrigger.Pull else ReadTrigger.Action) {
        val current = mutable.value
        if (!networkReadAllowed || !(accountPageVisible || balanceOnly && pageVisible) || !foreground || preview || current.needsLogin || current.restoringLogin ||
            current.busy) return
        val readBalance = manual || current.account == null || current.accountError != null || balanceRefresh.needsRead(balanceKey(current.account)) ||
            !ReadCachePolicy.isFresh(accountReadAt)
        val readCard = !balanceOnly && (manual || current.accountCard == null || current.accountCard.account != current.account?.account ||
            current.cardStatusError != null || !repository.cardSnapshot(current.username, current.account.account).fresh())
        if (!readBalance && !readCard) return
        val query = accountReadKey()
        val readTrigger = if (trigger.automatic && balancePending()) ReadTrigger.Transaction else trigger
        if (!reads.canStart(query, readTrigger, replace = manual && !current.accountLoading && !current.cardStatusLoading) {
            refreshAccount(manual, silent, balanceOnly, trigger)
        }) return
        val username = current.username
        val version = ++accountReadVersion
        val transactionRead = balanceRefresh.started()
        mutable.value = current.copy(accountLoading = readBalance && !silent, accountErrorKind = null, accountError = null,
            cardStatusLoading = readCard && !silent, cardStatusErrorKind = current.cardStatusErrorKind.takeUnless { readCard }, cardStatusError = current.cardStatusError.takeUnless { readCard })
        accountRequest = reads.launch(query, readTrigger, retry = { refreshAccount(silent = true, trigger = ReadTrigger.Retry) }) {
            fun isCurrent() = version == accountReadVersion && !mutable.value.needsLogin && mutable.value.username == username
            var balanceObtained = !readBalance
            var failure: ReadFailureKind? = null
            var cancelled = true
            try {
                if (!startNetwork()) return@launch
                val account = if (readBalance) withContext(Dispatchers.IO) { repository.paymentAccount(username) }
                    else requireNotNull(current.account)
                if (!isCurrent() || !repository.ownsAccount(username)) return@launch
                balanceObtained = true
                val sameAccount = current.account?.let { it.account == account.account &&
                    it.payacc == account.payacc && it.paytype == account.paytype } == true
                // A balance read does not verify card status or authorize displaying a code.
                if (readBalance) recordBalance(username, account, transactionRead)
                mutable.value = mutable.value.copy(account = account, accountFromCache = false, accountLoading = false,
                    accountCard = mutable.value.accountCard.takeIf { sameAccount },
                    cardStatusLoading = !silent && (readCard || !sameAccount),
                    problem = if (sameAccount) mutable.value.problem else PaymentCodeProblem.StatusUnverified)
                if (readBalance) repository.saveDisplay(username, DisplayKeys.Account, account, DisplayCodecs.Account)
                if (readCard || !sameAccount && !balanceOnly) {
                    val card = withContext(Dispatchers.IO) { repository.paymentCard(username, account.account, force = manual) }
                    if (!isCurrent() || !repository.ownsAccount(username)) return@launch
                    if (card.account != account.account || card.status < 0) throw YktException("校园卡状态数据异常")
                    mutable.value = mutable.value.copy(accountCard = card, cardStatusLoading = false, cardStatusErrorKind = null, cardStatusError = null)
                }
                cancelled = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isCurrent()) return@launch
                cancelled = false
                failure = e.readFailureKind()
                if (balanceObtained) mutable.value = mutable.value.copy(accountCard = null,
                    cardStatusErrorKind = e.readFailureKind(), cardStatusError = (e as? YktException)?.message ?: "校园卡状态暂时无法加载")
                else {
                    current.account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                    mutable.value = mutable.value.copy(accountLoading = false,
                        accountErrorKind = e.readFailureKind(), accountError = (e as? YktException)?.message ?: "余额暂时无法加载")
                }
            } finally {
                if (!cancelled) complete(failure)
                if (version == accountReadVersion) mutable.value = mutable.value.copy(accountLoading = false, cardStatusLoading = false)
                balanceRefresh.finished(transactionRead)
            }
        }
    }
    private fun cancelAccountRead() {
        balanceRefresh.pause()
        reads.cancel(accountReadKey())
        accountReadVersion++
        accountRequest?.cancel(); accountRequest = null
        mutable.value = mutable.value.copy(accountLoading = false, cardStatusLoading = false)
    }
    private fun paymentDisplayVisible() = pageVisible || pageCovered
    private fun pausePaymentDisplay() {
        setPaymentMotionActive(false)
        mutableSuccess.value = null
        requestVersion++
        cancelPaymentCardRead()
        ticker?.cancel(); request?.cancel()
        // The request is cancelled here rather than failed. Forget its single-flight slot so a
        // return to the page can start a fresh read instead of seeing a stale in-flight marker.
        reads.cancel(paymentReadKey())
        val old = mutable.value
        codeExpiresAt = 0L
        mutable.value = PaymentUiState(
            old.needsLogin, logoutJob?.isActive == true, old.error, old.username,
            errorKind = old.errorKind,
            preview = preview, restoringLogin = old.restoringLogin,
            account = old.account.takeUnless { old.needsLogin },
            accountFromCache = old.accountFromCache,
            problem = old.problem.takeUnless { old.needsLogin },
            accountLoading = old.accountLoading, accountErrorKind = old.accountErrorKind.takeUnless { old.needsLogin }, accountError = old.accountError.takeUnless { old.needsLogin },
            accountCard = old.accountCard.takeUnless { old.needsLogin },
            cardStatusLoading = old.cardStatusLoading, cardStatusErrorKind = old.cardStatusErrorKind.takeUnless { old.needsLogin }, cardStatusError = old.cardStatusError.takeUnless { old.needsLogin },
        )
    }
    private fun resumePaymentDisplay() {
        val old = mutable.value
        if (!old.needsLogin && !old.restoringLogin) {
            val remaining = codeExpiresAt - SystemClock.elapsedRealtime()
            if (!resumeNeedsRefresh && old.code != null && old.problem == null && !old.busy && remaining > 0) {
                startTicker(requestVersion)
            } else {
                mutable.value = old.copy(busy = logoutJob?.isActive == true, refreshing = false)
                refresh(trigger = ReadTrigger.Visible)
            }
        }
    }
    fun loginInputEdited(field: FormField) {
        val s = mutable.value
        if (s.needsLogin && !s.busy && field in s.errorFields)
            mutable.value = s.copy(errorKind = null, error = null, errorFields = emptySet())
    }
    fun login(username: String, password: String) {
        if (mutable.value.busy || mutable.value.restoringLogin || logoutJob?.isActive == true) return
        cancelAccountRead()
        cancelPaymentDetection(); mutableSuccess.value = null
        balanceRefresh.clear(); lastLiveBalance = null
        reads.clear()
        val old = mutable.value
        val analyticsAttempt = if (!preview) analytics.begin() else null
        val codeAttempt = if (!preview) analytics.begin() else null
        val version = ++requestVersion
        val transactionRead = balanceRefresh.started()
        loginInProgress = true
        handledLoginGeneration = null
        mutable.value = PaymentUiState(true, true, username = username)
        request = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { repository.login(username, password) }
                if (version != requestVersion) return@launch
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Campus)
                handledLoginGeneration = sessionState.value.generation
                notifications.send("登录成功")
                show(batch, username.trim(), version, transactionRead, codeAttempt)
                readPaymentCard(username.trim(), batch.account.account, version)
                repository.saveDisplay(username.trim(), DisplayKeys.Account, batch.account, DisplayCodecs.Account)
            } catch (e: CancellationException) { throw e
            } catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Campus)
                handledLoginGeneration = sessionState.value.generation
                // Credentials were accepted; an unavailable barcode must not hide the account.
                notifications.send("登录成功")
                mutable.value = PaymentUiState(needsLogin = false, username = username.trim(), account = e.account, errorKind = e.readFailureKind(), error = e.message, problem = e.problem)
                readPaymentCard(username.trim(), e.account.account, version)
                recordBalance(username.trim(), e.account, transactionRead)
                repository.saveDisplay(mutable.value.username, DisplayKeys.Account, e.account, DisplayCodecs.Account)
            } catch (e: YktException) {
                if (version != requestVersion) return@launch
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Campus,
                    AnalyticsError.from(e).takeIf { sessionState.value.username == null })
                handledLoginGeneration = sessionState.value.generation
                mutable.value = PaymentUiState(needsLogin = sessionState.value.username == null, username = username,
                    errorKind = e.readFailureKind(), error = e.message ?: "登录失败，请重试", errorFields = inputErrorFields(e))
            } catch (e: Exception) {
                if (version != requestVersion) return@launch
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Campus,
                    AnalyticsError.from(e).takeIf { sessionState.value.username == null })
                handledLoginGeneration = sessionState.value.generation
                mutable.value = PaymentUiState(needsLogin = sessionState.value.username == null, username = old.username,
                    errorKind = ReadFailureKind.Other, error = "登录失败，请稍后重试")
            } finally {
                if (version == requestVersion) loginInProgress = false
            }
        }
    }
    fun refresh(manual: Boolean = false,
        trigger: ReadTrigger = if (manual) ReadTrigger.Pull else ReadTrigger.Action) {
        if (!networkReadAllowed || !foreground) {
            resumeNeedsRefresh = true
            return
        }
        if (!manual && !paymentDisplayVisible()) {
            resumeNeedsRefresh = true
            return
        }
        if (preview || mutable.value.restoringLogin || mutable.value.busy || mutable.value.needsLogin) return
        val query = paymentReadKey()
        val readTrigger = if (trigger.automatic && balancePending()) ReadTrigger.Transaction else trigger
        if (!reads.canStart(query, readTrigger, replace = !trigger.automatic) { refresh(manual, trigger) }) return
        if (manual) analytics.retryPaymentCode()
        // A newer payment refresh owns the account result, superseding any older balance read.
        cancelAccountRead()
        cancelPaymentCardRead()
        resumeNeedsRefresh = false
        codeExpiresAt = 0L
        ticker?.cancel()
        val version = ++requestVersion
        val transactionRead = balanceRefresh.started()
        val username = mutable.value.username
        val account = mutable.value.account
        mutable.value = PaymentUiState(needsLogin = false, busy = true, username = username, refreshing = manual, account = account,
            accountFromCache = mutable.value.accountFromCache, balanceLoading = true,
            accountCard = mutable.value.accountCard)
        request = reads.launch(query, readTrigger, retry = { refresh(trigger = ReadTrigger.Retry) }) {
            var accountObtained = false
            var failure: ReadFailureKind? = null
            var cancelled = true
            try {
                if (!startNetwork()) return@launch
                val codeAttempt = analytics.begin()
                val batch = withContext(Dispatchers.IO) { repository.refresh { fresh ->
                    withContext(Dispatchers.Main.immediate) {
                        if (version == requestVersion && repository.ownsAccount(username) && !mutable.value.needsLogin) {
                            accountObtained = true
                            recordBalance(username, fresh, transactionRead)
                            mutable.value = mutable.value.copy(account = fresh, accountFromCache = false, balanceLoading = false)
                            readPaymentCard(username, fresh.account, version)
                            viewModelScope.launch {
                                if (version == requestVersion) repository.saveDisplay(username, DisplayKeys.Account, fresh, DisplayCodecs.Account)
                            }
                        }
                    }
                } }
                show(batch, username, version, transactionRead, codeAttempt)
                repository.saveDisplay(username, DisplayKeys.Account, batch.account, DisplayCodecs.Account)
                cancelled = false
            }
            catch (e: CancellationException) { throw e }
            catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                cancelled = false
                failure = e.readFailureKind()
                mutable.value = mutable.value.copy(busy = false, refreshing = false, balanceLoading = false,
                    account = e.account, accountFromCache = false, errorKind = e.readFailureKind(), error = e.message, problem = e.problem)
                recordBalance(username, e.account, transactionRead)
                repository.saveDisplay(mutable.value.username, DisplayKeys.Account, e.account, DisplayCodecs.Account)
            }
            catch (e: YktException) {
                if (version != requestVersion) return@launch
                cancelled = false
                failure = e.readFailureKind()
                if (!accountObtained) account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                mutable.value = mutable.value.copy(
                    needsLogin = false, busy = false, refreshing = false, balanceLoading = false,
                    errorKind = e.readFailureKind(), error = e.message ?: "服务异常，请稍后刷新",
                    username = username, account = mutable.value.account,
                    accountFromCache = mutable.value.accountFromCache,
                    accountErrorKind = e.readFailureKind().takeUnless { accountObtained },
                    accountError = e.message.takeUnless { accountObtained },
                    problem = PaymentCodeProblem.StatusUnverified,
                )
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                cancelled = false
                failure = ReadFailureKind.Other
                if (!accountObtained) account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                mutable.value = mutable.value.copy(needsLogin = false, busy = false, refreshing = false, balanceLoading = false,
                    errorKind = ReadFailureKind.Other, error = "服务数据异常，请稍后刷新", username = username, account = mutable.value.account,
                    accountFromCache = mutable.value.accountFromCache, accountErrorKind = ReadFailureKind.Other, accountError = "余额暂时无法更新".takeUnless { accountObtained },
                    problem = PaymentCodeProblem.StatusUnverified)
            } finally {
                if (!cancelled) complete(failure)
                else if (version == requestVersion) {
                    resumeNeedsRefresh = true
                    mutable.value = mutable.value.copy(busy = false, refreshing = false, balanceLoading = false)
                }
                balanceRefresh.finished(transactionRead)
            }
        }
    }
    private fun cancelPaymentCardRead() {
        paymentCardRequest?.cancel(); paymentCardRequest = null
        mutable.value = mutable.value.copy(cardStatusLoading = false)
    }

    /** Card warnings can arrive before or after the code without extending its loading state. */
    private fun readPaymentCard(username: String, account: String, version: Long) {
        if (!foreground || !paymentDisplayVisible()) return
        cancelPaymentCardRead()
        fun isCurrent() = version == requestVersion && repository.ownsAccount(username) &&
            !mutable.value.needsLogin && mutable.value.account?.account == account
        mutable.value = mutable.value.copy(accountCard = mutable.value.accountCard?.takeIf { it.account == account },
            cardStatusLoading = true, cardStatusError = null, cardStatusErrorKind = null)
        paymentCardRequest = viewModelScope.launch {
            try {
                val card = withContext(Dispatchers.IO) { repository.paymentCard(username, account, force = true) }
                if (!isCurrent()) return@launch
                mutable.value = mutable.value.copy(accountCard = card, cardStatusLoading = false,
                    cardStatusError = null, cardStatusErrorKind = null)
                if (card.frozen || card.status != 0) {
                    ticker?.cancel(); codeExpiresAt = 0L
                    mutable.value = mutable.value.copy(code = null, secondsLeft = 0,
                        problem = if (card.frozen) PaymentCodeProblem.Frozen else PaymentCodeProblem.Unavailable,
                        error = if (card.frozen) "校园卡已冻结，请在官网或联系校园卡服务中心处理" else "校园卡状态异常，请在官网检查账户",
                        errorKind = ReadFailureKind.Other)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isCurrent()) return@launch
                mutable.value = mutable.value.copy(accountCard = null, cardStatusLoading = false,
                    cardStatusError = (e as? YktException)?.message ?: "校园卡状态暂时无法加载",
                    cardStatusErrorKind = e.readFailureKind())
            }
        }
    }
    /** Card-state changes invalidate both the displayed code and any older in-flight refresh. */
    fun cardStateChanged() {
        cancelPaymentDetection(); mutableSuccess.value = null
        if (preview || mutable.value.needsLogin || mutable.value.restoringLogin) return
        cancelAccountRead()
        requestVersion++
        cancelPaymentCardRead()
        request?.cancel()
        reads.cancel(paymentReadKey())
        ticker?.cancel()
        codeExpiresAt = 0L
        val state = mutable.value
        accountReadAt = 0L
        mutable.value = PaymentUiState(needsLogin = false, username = state.username, account = state.account, accountFromCache = state.accountFromCache,
            problem = PaymentCodeProblem.StatusUnverified.takeIf { accountPageVisible })
        if (accountPageVisible) refreshAccount(manual = true) else refresh()
    }
    private fun show(batch: PaymentBatch, username: String, version: Long, transactionRead: TransactionRefresh.Read,
        codeAttempt: AnalyticsAttempt?) {
        if (version != requestVersion) return
        analytics.paymentCodeFetched(codeAttempt)
        recordBalance(username, batch.account, transactionRead)
        if (!foreground || !paymentDisplayVisible()) {
            mutable.value = PaymentUiState(needsLogin = false, username = username, account = batch.account)
            return
        }
        val interval = batch.displayIntervalSeconds
        codeExpiresAt = SystemClock.elapsedRealtime() + interval * 1000
        val restricted = mutable.value.problem == PaymentCodeProblem.Frozen ||
            mutable.value.problem == PaymentCodeProblem.Unavailable && mutable.value.accountCard?.status?.let { it != 0 } == true
        mutable.value = mutable.value.copy(needsLogin = false, busy = false, refreshing = false,
            username = username, account = batch.account, accountFromCache = false, balanceLoading = false,
            code = batch.codes[0].takeUnless { restricted }, secondsLeft = if (restricted) 0 else interval,
            problem = mutable.value.problem.takeIf { restricted },
            error = mutable.value.error.takeIf { restricted }, errorKind = mutable.value.errorKind.takeIf { restricted })
        if (restricted) codeExpiresAt = 0L else startTicker(version)
    }
    private fun startTicker(version: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (foreground && paymentDisplayVisible() && version == requestVersion) {
                val remaining = codeExpiresAt - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    codeExpiresAt = 0L
                    mutable.value = mutable.value.copy(code = null, secondsLeft = 0,
                        problem = PaymentCodeProblem.StatusUnverified)
                    refresh(trigger = ReadTrigger.Visible); return@launch
                }
                mutable.value = mutable.value.copy(secondsLeft = (remaining + 999) / 1000)
                delay(minOf(1000, remaining))
            }
        }
    }
    fun logout() {
        if (logoutJob?.isActive == true) return
        clearPaymentData()
        // Revoke access and clear payment codes immediately; only an inert shell
        // may remain briefly for the page exit animation. Login waits for storage.
        mutable.value = PaymentUiState(busy = true)
        logoutJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.logout() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notifications.send("清除登录信息失败，请稍后重试") }
            finally { if (mutable.value.busy) mutable.value = mutable.value.copy(busy = false) }
        }
    }
    private fun recordBalance(username: String, account: PaymentAccount, read: TransactionRefresh.Read, notify: Boolean = true) {
        if (!balanceRefresh.accepts(read) || account.balanceCents < 0 || preview || !repository.ownsAccount(username)) return
        val next = Balance(username, account.account, account.payacc, account.paytype, account.balanceCents)
        val previous = lastLiveBalance
        if (notify && previous != null && previous.copy(cents = next.cents) == next && previous.cents != next.cents)
            repository.dataChanges.balance(repository.identity(username), BalanceChange(username, increased = next.cents > previous.cents))
        lastLiveBalance = next
        balanceRefresh.observe(read, balanceKey(account), account.balanceCents)
        accountReadAt = balanceRefresh.cacheReadAt(balanceKey(account), SystemClock.elapsedRealtime())
    }

    private companion object {
        val PAYMENT_PAGES = setOf(ReadPage.Payment, ReadPage.My, ReadPage.Account)
    }
}
