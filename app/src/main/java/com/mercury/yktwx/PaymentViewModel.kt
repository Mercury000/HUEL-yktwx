package com.mercury.yktwx

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
) {
    override fun toString() = "PaymentUiState([redacted])"
}

class PaymentViewModel internal constructor(app: Application, private val repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(PaymentUiState(restoringLogin = true))
    val state = mutable.asStateFlow()
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
    private var accountRequest: Job? = null
    private var accountReadVersion = 0L
    private var accountReadAt = 0L
    private var cardReadAt = 0L
    private var accountPageVisible = false
    private var logoutJob: Job? = null
    private var preview = false
    private var previewLost = false
    private var requestVersion = 0L
    private val mutableSuccess = MutableStateFlow<Long?>(null)
    val paymentSuccess = mutableSuccess.asStateFlow()
    private val updates = Channel<TransactionUpdate>(Channel.BUFFERED)
    val transactionUpdates = updates.receiveAsFlow()
    private val balanceNotifications = Channel<BalanceChange>(Channel.BUFFERED)
    val balanceChanges = balanceNotifications.receiveAsFlow()
    private data class Balance(val username: String, val account: String, val payacc: String,
        val paytype: String, val cents: Long)
    private var lastLiveBalance: Balance? = null
    private var readPageVisible = true
    private fun balanceKey(account: PaymentAccount) = "${account.account}:${account.payacc}:${account.paytype}"
    private fun balancePending(): Boolean = mutable.value.account?.let { balanceRefresh.needsTransactionRead(balanceKey(it)) } ?: false
    private val balanceRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { balancePending() },
        isReading = { mutable.value.busy || accountRequest?.isActive == true },
        retry = { refreshAccount(silent = true, balanceOnly = true) })
    private var motionActive = false
    private var balanceCheck: Job? = null
    private var detectionVersion = 0L
    private data class PaymentTurn(val username: String, val account: PaymentAccount, val version: Long)
    private var paymentTurn: PaymentTurn? = null

    internal fun setPaymentMotionActive(active: Boolean) {
        motionActive = active
        if (!active) cancelPaymentDetection()
    }
    internal fun cancelPaymentDetection() {
        detectionVersion++
        balanceCheck?.cancel(); balanceCheck = null; paymentTurn = null
    }
    internal fun paymentTurnStarted() {
        val current = mutable.value
        if (!foreground || !motionActive || preview || current.needsLogin || current.busy ||
            current.code == null || current.accountFromCache || current.problem != null || mutableSuccess.value != null || balanceCheck?.isActive == true) return
        val account = current.account ?: return
        paymentTurn = PaymentTurn(current.username, account, ++detectionVersion)
    }
    internal fun paymentTurnReturned() {
        val turn = paymentTurn ?: return
        if (balanceCheck?.isActive == true || !isCurrent(turn)) return
        balanceCheck = viewModelScope.launch {
            try {
                // Bounded silent retries cover a balance endpoint that lags behind the scanner.
                for (wait in longArrayOf(0, 350, 700)) {
                    delay(wait)
                    if (!isCurrent(turn)) return@launch
                    val updated = try { withContext(Dispatchers.IO) { repository.paymentAccount(turn.username) } }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { continue }
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
                        updates.trySend(TransactionUpdate(turn.username, balance = false, income = false))
                        requestVersion++; request?.cancel(); ticker?.cancel()
                        mutable.value = mutable.value.copy(busy = false)
                        refresh()
                        return@launch
                    }
                }
            } finally {
                if (turn.version == detectionVersion) paymentTurn = null
            }
        }
    }
    private fun isCurrent(turn: PaymentTurn): Boolean {
        val current = mutable.value
        val account = current.account ?: return false
        return turn.version == detectionVersion && foreground && motionActive && !current.needsLogin &&
            current.username == turn.username && current.problem == null && mutableSuccess.value == null &&
            account.account == turn.account.account && account.payacc == turn.account.payacc && account.paytype == turn.account.paytype
    }
    fun dismissPaymentSuccess() { mutableSuccess.value = null }

    fun initialize(uiPreview: Boolean = false, previewLost: Boolean = false, restoreLogin: Boolean = true) {
        if (initialized) return
        initialized = true
        preview = UiPreview.enabled && uiPreview
        this.previewLost = previewLost
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.paymentState(previewLost)
            return
        }
        val initializationVersion = requestVersion
        viewModelScope.launch {
            try {
                // A newly accepted notice starts at explicit login, even if older credentials remain.
                if (!restoreLogin) {
                    withContext(Dispatchers.IO) { repository.username(); repository.logout() }
                    if (initializationVersion == requestVersion) mutable.value = PaymentUiState()
                    return@launch
                }
                val username = withContext(Dispatchers.IO) { repository.username() }
                val exists = username.isNotBlank()
                val cached = if (exists) repository.cachedDisplay(username, DisplayKeys.Account, DisplayCodecs.Account) else null
                if (initializationVersion != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = !exists, username = username, account = cached,
                    accountFromCache = cached != null, problem = PaymentCodeProblem.StatusUnverified.takeIf { cached != null })
                if (exists && foreground && pageVisible) refresh()
                else if (exists && foreground && accountPageVisible) refreshAccount()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = PaymentUiState(error = "无法读取本机登录信息，请重新登录")
            }
        }
    }
    fun onForeground() {
        foreground = true
        updateBalanceVisibility()
        if (UiPreview.enabled && preview && pageVisible) mutable.value = PreviewData.paymentState(previewLost)
        else if (pageVisible && initialized && !preview && !mutable.value.restoringLogin && !mutable.value.needsLogin && !mutable.value.busy) refresh()
        else if (accountPageVisible) refreshAccount()
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
        updateBalanceVisibility()
        cancelAccountRead()
        pausePaymentDisplay()
    }
    /** Balance and physical card state belong to account screens; never fetch a barcode here. */
    fun setAccountPageVisible(visible: Boolean) {
        if (accountPageVisible == visible) return
        accountPageVisible = visible
        updateBalanceVisibility()
        if (visible) refreshAccount() else cancelAccountRead()
    }
    /** The shell excludes transaction sheets, offscreen pages and background/offline reads. */
    fun setReadPageVisible(visible: Boolean) {
        if (readPageVisible == visible) return
        readPageVisible = visible
        updateBalanceVisibility()
        if (!visible) cancelAccountRead()
        else if (balancePending()) {
            if (pageVisible) refresh() else if (accountPageVisible) refreshAccount()
        }
    }
    private fun updateBalanceVisibility() = balanceRefresh.setVisible(
        foreground && readPageVisible && !pageCovered && (pageVisible || accountPageVisible))
    fun invalidateBalance() {
        balanceRefresh.invalidate()
        cancelAccountRead()
        accountReadAt = 0L
        resumeNeedsRefresh = true
        if (mutable.value.busy) {
            requestVersion++; request?.cancel(); ticker?.cancel()
            mutable.value = mutable.value.copy(busy = false, refreshing = false, balanceLoading = false)
        }
        if (foreground && readPageVisible && !pageCovered) {
            if (pageVisible) refresh() else if (accountPageVisible) refreshAccount()
        }
    }

    fun refreshAccount(manual: Boolean = false, silent: Boolean = false, balanceOnly: Boolean = false) {
        val current = mutable.value
        if (!(accountPageVisible || balanceOnly && pageVisible) || !readPageVisible && !manual || !foreground || preview || current.needsLogin || current.restoringLogin ||
            current.busy || accountRequest?.isActive == true) return
        val readBalance = manual || current.account == null || balanceRefresh.needsRead(balanceKey(current.account)) ||
            !ReadCachePolicy.isFresh(accountReadAt)
        val readCard = !balanceOnly && (manual || current.accountCard == null || current.accountCard.account != current.account?.account ||
            current.cardStatusError != null || !ReadCachePolicy.isFresh(cardReadAt))
        if (!readBalance && !readCard) return
        val username = current.username
        val version = ++accountReadVersion
        val transactionRead = balanceRefresh.started()
        mutable.value = current.copy(accountLoading = readBalance && !silent, accountError = null,
            cardStatusLoading = readCard && !silent, cardStatusError = current.cardStatusError.takeUnless { readCard })
        accountRequest = viewModelScope.launch {
            fun isCurrent() = version == accountReadVersion && !mutable.value.needsLogin && mutable.value.username == username
            var balanceObtained = !readBalance
            try {
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
                    val card = withContext(Dispatchers.IO) { repository.paymentCard(username, account.account) }
                    if (!isCurrent() || !repository.ownsAccount(username)) return@launch
                    if (card.account != account.account || card.status < 0) throw YktException("校园卡状态数据异常")
                    cardReadAt = SystemClock.elapsedRealtime()
                    mutable.value = mutable.value.copy(accountCard = card, cardStatusLoading = false, cardStatusError = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isCurrent()) return@launch
                val requiresLogin = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                if (requiresLogin) {
                    requestVersion++; request?.cancel(); ticker?.cancel()
                    cancelPaymentDetection(); mutableSuccess.value = null
                    codeExpiresAt = 0L
                    mutable.value = PaymentUiState(needsLogin = true, username = username,
                        error = "登录已失效，请重新登录")
                } else if (balanceObtained) mutable.value = mutable.value.copy(accountCard = null,
                    cardStatusError = (e as? YktException)?.message ?: "校园卡状态暂时无法加载")
                else {
                    current.account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                    mutable.value = mutable.value.copy(accountLoading = false,
                        accountError = (e as? YktException)?.message ?: "余额暂时无法加载")
                }
            } finally {
                if (version == accountReadVersion) mutable.value = mutable.value.copy(accountLoading = false, cardStatusLoading = false)
                balanceRefresh.finished(transactionRead)
            }
        }
    }
    private fun cancelAccountRead() {
        balanceRefresh.pause()
        accountReadVersion++
        accountRequest?.cancel(); accountRequest = null
        mutable.value = mutable.value.copy(accountLoading = false, cardStatusLoading = false)
    }
    private fun paymentDisplayVisible() = pageVisible || pageCovered
    private fun pausePaymentDisplay() {
        setPaymentMotionActive(false)
        mutableSuccess.value = null
        requestVersion++
        ticker?.cancel(); request?.cancel()
        val old = mutable.value
        codeExpiresAt = 0L
        mutable.value = PaymentUiState(
            old.needsLogin, logoutJob?.isActive == true, old.error, old.username,
            preview = preview, restoringLogin = old.restoringLogin,
            account = old.account.takeUnless { old.needsLogin },
            accountFromCache = old.accountFromCache,
            problem = old.problem.takeUnless { old.needsLogin },
            accountLoading = old.accountLoading, accountError = old.accountError.takeUnless { old.needsLogin },
            accountCard = old.accountCard.takeUnless { old.needsLogin },
            cardStatusLoading = old.cardStatusLoading, cardStatusError = old.cardStatusError.takeUnless { old.needsLogin },
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
                refresh()
            }
        }
    }
    fun loginInputEdited(field: FormField) {
        val s = mutable.value
        if (s.needsLogin && !s.busy && field in s.errorFields)
            mutable.value = s.copy(error = null, errorFields = emptySet())
    }
    fun login(username: String, password: String) {
        if (mutable.value.busy || mutable.value.restoringLogin || logoutJob?.isActive == true) return
        cancelAccountRead()
        cancelPaymentDetection(); mutableSuccess.value = null
        balanceRefresh.clear(); lastLiveBalance = null
        val old = mutable.value
        val version = ++requestVersion
        val transactionRead = balanceRefresh.started()
        mutable.value = PaymentUiState(true, true, username = username)
        request = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { repository.login(username, password) }
                if (version != requestVersion) return@launch
                notifications.send("登录成功")
                show(batch, username.trim(), version, transactionRead)
                repository.saveDisplay(username.trim(), DisplayKeys.Account, batch.account, DisplayCodecs.Account)
            } catch (e: CancellationException) { throw e
            } catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                // Credentials were accepted; an unavailable barcode must not hide the account.
                notifications.send("登录成功")
                mutable.value = PaymentUiState(needsLogin = false, username = username.trim(), account = e.account, error = e.message, problem = e.problem)
                recordBalance(username.trim(), e.account, transactionRead)
                repository.saveDisplay(mutable.value.username, DisplayKeys.Account, e.account, DisplayCodecs.Account)
            } catch (e: YktException) {
                if (version != requestVersion) return@launch
                // Old credentials are not proof that this submitted login succeeded.
                mutable.value = PaymentUiState(needsLogin = true, username = username,
                    error = e.message ?: "登录失败，请重试", errorFields = inputErrorFields(e))
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = true, username = old.username,
                    error = "登录失败，请稍后重试")
            }
        }
    }
    fun refresh(manual: Boolean = false) {
        if (!manual && !paymentDisplayVisible()) {
            resumeNeedsRefresh = true
            return
        }
        if (preview || mutable.value.restoringLogin || mutable.value.busy || mutable.value.needsLogin) return
        // A newer payment refresh owns the account result, superseding any older balance read.
        cancelAccountRead()
        resumeNeedsRefresh = false
        codeExpiresAt = 0L
        ticker?.cancel()
        val version = ++requestVersion
        val transactionRead = balanceRefresh.started()
        val username = mutable.value.username
        val account = mutable.value.account
        mutable.value = PaymentUiState(needsLogin = false, busy = true, username = username, refreshing = manual, account = account,
            accountFromCache = mutable.value.accountFromCache, balanceLoading = true)
        request = viewModelScope.launch {
            var accountObtained = false
            try {
                val batch = withContext(Dispatchers.IO) { repository.refresh { fresh ->
                    withContext(Dispatchers.Main.immediate) {
                        if (version == requestVersion && repository.ownsAccount(username) && !mutable.value.needsLogin) {
                            accountObtained = true
                            recordBalance(username, fresh, transactionRead)
                            mutable.value = mutable.value.copy(account = fresh, accountFromCache = false, balanceLoading = false)
                            viewModelScope.launch {
                                if (version == requestVersion) repository.saveDisplay(username, DisplayKeys.Account, fresh, DisplayCodecs.Account)
                            }
                        }
                    }
                } }
                show(batch, username, version, transactionRead)
                repository.saveDisplay(username, DisplayKeys.Account, batch.account, DisplayCodecs.Account)
            }
            catch (e: CancellationException) { throw e }
            catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = false, username = username, account = e.account, error = e.message, problem = e.problem)
                recordBalance(username, e.account, transactionRead)
                repository.saveDisplay(mutable.value.username, DisplayKeys.Account, e.account, DisplayCodecs.Account)
            }
            catch (e: YktException) {
                if (version != requestVersion) return@launch
                val requireLogin = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                if (!requireLogin && !accountObtained) account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                mutable.value = PaymentUiState(
                    needsLogin = requireLogin,
                    error = e.message ?: if (requireLogin) "登录已失效，请重新登录" else "服务异常，请稍后刷新",
                    username = username, account = mutable.value.account.takeUnless { requireLogin },
                    accountFromCache = !requireLogin && mutable.value.accountFromCache,
                    accountError = if (requireLogin || accountObtained) null else e.message,
                    problem = PaymentCodeProblem.StatusUnverified.takeUnless { requireLogin },
                )
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                if (!accountObtained) account?.let { balanceRefresh.failed(transactionRead, balanceKey(it)) }
                mutable.value = PaymentUiState(needsLogin = false, error = "服务数据异常，请稍后刷新", username = username, account = mutable.value.account,
                    accountFromCache = mutable.value.accountFromCache, accountError = "余额暂时无法更新".takeUnless { accountObtained },
                    problem = PaymentCodeProblem.StatusUnverified)
            } finally {
                balanceRefresh.finished(transactionRead)
            }
        }
    }
    /** Card-state changes invalidate both the displayed code and any older in-flight refresh. */
    fun cardStateChanged() {
        cancelPaymentDetection(); mutableSuccess.value = null
        if (preview || mutable.value.needsLogin || mutable.value.restoringLogin) return
        cancelAccountRead()
        requestVersion++
        request?.cancel()
        ticker?.cancel()
        codeExpiresAt = 0L
        val state = mutable.value
        accountReadAt = 0L
        mutable.value = PaymentUiState(needsLogin = false, username = state.username, account = state.account, accountFromCache = state.accountFromCache,
            problem = PaymentCodeProblem.StatusUnverified.takeIf { accountPageVisible })
        if (accountPageVisible) refreshAccount(manual = true) else refresh()
    }
    private fun show(batch: PaymentBatch, username: String, version: Long, transactionRead: TransactionRefresh.Read) {
        if (version != requestVersion) return
        recordBalance(username, batch.account, transactionRead)
        if (!foreground || !paymentDisplayVisible()) {
            mutable.value = PaymentUiState(needsLogin = false, username = username, account = batch.account)
            return
        }
        val interval = batch.displayIntervalSeconds
        codeExpiresAt = SystemClock.elapsedRealtime() + interval * 1000
        mutable.value = PaymentUiState(false, username = username, account = batch.account, code = batch.codes[0], secondsLeft = interval)
        startTicker(version)
    }
    private fun startTicker(version: Long) {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (foreground && paymentDisplayVisible() && version == requestVersion) {
                val remaining = codeExpiresAt - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    refresh(); return@launch
                }
                mutable.value = mutable.value.copy(secondsLeft = (remaining + 999) / 1000)
                delay(minOf(1000, remaining))
            }
        }
    }
    fun cardPasswordChanged(username: String) {
        if (preview || mutable.value.username != username) return
        notifications.trySend("查询密码修改成功，请重新登录")
        logout()
    }
    fun logout() {
        if (logoutJob?.isActive == true) return
        accountReadAt = 0L
        balanceRefresh.clear(); lastLiveBalance = null
        cancelAccountRead()
        setPaymentMotionActive(false); mutableSuccess.value = null
        requestVersion++
        ticker?.cancel(); request?.cancel()
        codeExpiresAt = 0L
        // Revoke access and clear payment codes immediately; only an inert shell
        // may remain briefly for the page exit animation. Login waits for storage.
        mutable.value = PaymentUiState(busy = true)
        logoutJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.logout() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notifications.send("清除登录信息失败，请稍后重试") }
            finally { mutable.value = PaymentUiState() }
        }
    }
    private fun recordBalance(username: String, account: PaymentAccount, read: TransactionRefresh.Read, notify: Boolean = true) {
        if (!balanceRefresh.accepts(read) || account.balanceCents < 0 || preview || !repository.ownsAccount(username)) return
        val next = Balance(username, account.account, account.payacc, account.paytype, account.balanceCents)
        val previous = lastLiveBalance
        if (notify && previous != null && previous.copy(cents = next.cents) == next && previous.cents != next.cents)
            balanceNotifications.trySend(BalanceChange(username, increased = next.cents > previous.cents))
        lastLiveBalance = next
        balanceRefresh.observe(read, balanceKey(account), account.balanceCents)
        accountReadAt = balanceRefresh.cacheReadAt(balanceKey(account), SystemClock.elapsedRealtime())
    }
}
