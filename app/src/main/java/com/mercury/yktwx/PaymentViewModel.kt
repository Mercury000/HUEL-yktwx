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
) {
    override fun toString() = "PaymentUiState([redacted])"
}

class PaymentViewModel internal constructor(app: Application, private val repository: AccountRepository) : AndroidViewModel(app) {
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
    private var logoutJob: Job? = null
    private var preview = false
    private var previewLost = false
    private var requestVersion = 0L
    private val mutableSuccess = MutableStateFlow<Long?>(null)
    val paymentSuccess = mutableSuccess.asStateFlow()
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
            current.code == null || current.problem != null || mutableSuccess.value != null || balanceCheck?.isActive == true) return
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
                        mutable.value = mutable.value.copy(account = updated)
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

    fun initialize(uiPreview: Boolean = false, previewLost: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = UiPreview.enabled && uiPreview
        this.previewLost = previewLost
        if (UiPreview.enabled && preview) {
            mutable.value = PreviewData.paymentState(previewLost)
            return
        }
        viewModelScope.launch {
            try {
                val username = withContext(Dispatchers.IO) { repository.username() }
                val exists = username.isNotBlank()
                mutable.value = PaymentUiState(needsLogin = !exists, username = username)
                if (exists && foreground && pageVisible) refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = PaymentUiState(error = "无法读取本机登录信息，请重新登录")
            }
        }
    }
    fun onForeground() {
        foreground = true
        if (UiPreview.enabled && preview && pageVisible) mutable.value = PreviewData.paymentState(previewLost)
        else if (pageVisible && initialized && !preview && !mutable.value.restoringLogin && !mutable.value.needsLogin && !mutable.value.busy) refresh()
    }
    /** A modal keeps a valid backdrop stable; offscreen pages discard it. Neither auto-refreshes. */
    fun setPageVisible(visible: Boolean, preserveDisplay: Boolean = false) {
        val covered = !visible && preserveDisplay
        if (pageVisible == visible && pageCovered == covered) return
        pageVisible = visible
        pageCovered = covered
        if (!visible) pausePaymentDisplay(preserveDisplay = covered)
        else if (UiPreview.enabled && preview) mutable.value = PreviewData.paymentState(previewLost)
        else if (foreground && initialized) resumePaymentDisplay()
    }
    fun onBackground() {
        foreground = false
        pausePaymentDisplay()
    }
    private fun pausePaymentDisplay(preserveDisplay: Boolean = false) {
        setPaymentMotionActive(false)
        mutableSuccess.value = null
        requestVersion++
        ticker?.cancel(); request?.cancel()
        val old = mutable.value
        if (preserveDisplay) {
            // One expiry wakeup replaces per-second updates; never leave an expired code visible.
            if (!preview && old.code != null) {
                val version = requestVersion
                ticker = viewModelScope.launch {
                    delay((codeExpiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    if (version == requestVersion && foreground && pageCovered) {
                        codeExpiresAt = 0L
                        mutable.value = mutable.value.copy(code = null, secondsLeft = 0, busy = false,
                            refreshing = false, error = "付款码已过期，关闭弹窗后刷新")
                    }
                }
            }
            return
        }
        codeExpiresAt = 0L
        mutable.value = PaymentUiState(
            old.needsLogin, logoutJob?.isActive == true, old.error, old.username,
            preview = preview, restoringLogin = old.restoringLogin,
            account = old.account.takeUnless { old.needsLogin },
            problem = old.problem.takeUnless { old.needsLogin },
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
    fun login(username: String, password: String) {
        if (mutable.value.busy || mutable.value.restoringLogin || logoutJob?.isActive == true) return
        cancelPaymentDetection(); mutableSuccess.value = null
        val old = mutable.value
        val version = ++requestVersion
        mutable.value = PaymentUiState(true, true, username = username)
        request = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { repository.login(username, password) }
                if (version != requestVersion) return@launch
                notifications.send("登录成功")
                show(batch, username.trim(), version)
            } catch (e: CancellationException) { throw e
            } catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                // Credentials were accepted; an unavailable barcode must not hide the account.
                notifications.send("登录成功")
                mutable.value = PaymentUiState(needsLogin = false, username = username.trim(), account = e.account, error = e.message, problem = e.problem)
            } catch (e: YktException) {
                if (version != requestVersion) return@launch
                // Old credentials are not proof that this submitted login succeeded.
                mutable.value = PaymentUiState(needsLogin = true, username = username,
                    error = e.message ?: "登录失败，请重试")
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = true, username = old.username,
                    error = "登录失败，请稍后重试")
            }
        }
    }
    fun refresh(manual: Boolean = false) {
        if (!manual && !pageVisible) {
            if (pageCovered) resumeNeedsRefresh = true
            return
        }
        if (preview || mutable.value.restoringLogin || mutable.value.busy || mutable.value.needsLogin) return
        resumeNeedsRefresh = false
        codeExpiresAt = 0L
        ticker?.cancel()
        val version = ++requestVersion
        val username = mutable.value.username
        val account = mutable.value.account
        mutable.value = PaymentUiState(needsLogin = false, busy = true, username = username, refreshing = manual, account = account)
        request = viewModelScope.launch {
            try { show(withContext(Dispatchers.IO) { repository.refresh() }, username, version) }
            catch (e: CancellationException) { throw e }
            catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = false, username = username, account = e.account, error = e.message, problem = e.problem)
            }
            catch (e: YktException) {
                if (version != requestVersion) return@launch
                val requireLogin = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                mutable.value = PaymentUiState(
                    needsLogin = requireLogin,
                    error = e.message ?: if (requireLogin) "登录已失效，请重新登录" else "服务异常，请稍后刷新",
                    username = username, account = account.takeUnless { requireLogin },
                )
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = false, error = "服务数据异常，请稍后刷新", username = username, account = account)
            }
        }
    }
    /** Card-state changes invalidate both the displayed code and any older in-flight refresh. */
    fun cardStateChanged() {
        cancelPaymentDetection(); mutableSuccess.value = null
        if (preview || mutable.value.needsLogin || mutable.value.restoringLogin) return
        requestVersion++
        request?.cancel()
        ticker?.cancel()
        codeExpiresAt = 0L
        val state = mutable.value
        mutable.value = PaymentUiState(needsLogin = false, username = state.username, account = state.account)
        refresh()
    }
    private fun show(batch: PaymentBatch, username: String, version: Long) {
        if (version != requestVersion) return
        if (!foreground || !pageVisible) {
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
            while (foreground && pageVisible && version == requestVersion) {
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
}
