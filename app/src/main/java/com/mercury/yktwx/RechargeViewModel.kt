package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class RechargePhase { Amount, Creating, AwaitingPayment, Checking, Paid, Unpaid, Failed, Unknown }
class RechargeUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val config: RechargeConfiguration? = null,
    val pending: PendingRecharge? = null,
    val phase: RechargePhase = RechargePhase.Amount,
    val error: String? = null,
    val balanceCents: Long? = null,
    val preview: Boolean = false,
) {
    override fun toString() = "RechargeUiState([redacted])"
}

/** One foreground recharge attempt; closed or cancelled attempts never lock the next one. */
class RechargeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AccountRepository.shared(app)
    private val mutable = MutableStateFlow(RechargeUiState())
    val state = mutable.asStateFlow()
    private val launches = Channel<WechatRechargeLaunch>(Channel.BUFFERED)
    val events = launches.receiveAsFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var task: Job? = null
    private var returnCheck: Job? = null
    private var foreground = false
    private var resumed = false
    private var windowFocused = false
    private var owner = ""
    private var attempt = 0L
    private var awaitingWechatReturn = false
    private var leftForWechat = false

    fun open(username: String, preferredCard: String?, preview: Boolean) {
        // Opening recharge is always a new amount selection, never an old result.
        endAttempt()
        owner = username
        val current = attempt
        mutable.value = RechargeUiState(loading = true, preview = preview)
        task = viewModelScope.launch {
            try {
                val config = if (preview) RechargeConfiguration(
                    "demo", "demo", "统一账户", 0, listOf(1000, 5000, 10000), 1, 50000, true, false,
                ) else repository.rechargeConfiguration(preferredCard)
                if (attempt == current) mutable.value = RechargeUiState(visible = true, config = config, preview = preview)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == current) {
                    mutable.value = RechargeUiState(preview = preview)
                    // Toasts are only for failures before a dialog can be opened.
                    notifications.send(safeError(e, "读取充值信息失败，请稍后重试"))
                }
            }
        }
    }

    fun submit(amountCents: Long) {
        val state = mutable.value
        if (state.preview || state.loading || state.pending != null || task?.isActive == true) return
        val config = state.config ?: return
        config.amountError(amountCents)?.let { update(error = it); return }
        if (repository.username() != owner) { update(error = "账户已切换，请重新打开充值"); return }
        val current = attempt
        val pending = PendingRecharge(owner, config.targetId, config.feeItemId, amountCents, System.currentTimeMillis())
        update(loading = true, pending = pending, phase = RechargePhase.Creating, error = null)
        task = viewModelScope.launch {
            try {
                // No automatic retry of either POST. Only retain this live attempt in memory.
                val order = repository.createRecharge(pending.username, config, amountCents)
                if (current != attempt) return@launch
                val created = pending.withOrder(order.orderId, order.payId)
                update(pending = created, phase = RechargePhase.AwaitingPayment)
                requireOwner(created)
                val launch = repository.launchRecharge(created)
                if (current != attempt) return@launch
                if (!foreground) {
                    showResult(RechargePhase.Unknown, "支付未完成，请重新发起充值")
                    return@launch
                }
                update(loading = false, phase = RechargePhase.AwaitingPayment)
                launches.send(launch)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == attempt) showResult(RechargePhase.Unknown, resultError(e))
            }
        }
    }

    fun canLaunchWechat(): Boolean = foreground && owner.isNotEmpty() && repository.username() == owner && mutable.value.phase == RechargePhase.AwaitingPayment
    fun wechatLaunched() {
        returnCheck?.cancel()
        awaitingWechatReturn = true
        leftForWechat = false
    }
    fun launchFailed() {
        returnCheck?.cancel()
        awaitingWechatReturn = false
        leftForWechat = false
        showResult(RechargePhase.Failed, "无法打开微信，请确认已安装微信")
    }

    fun checkResult() {
        val pending = mutable.value.pending ?: return
        if (pending.orderId == null || mutable.value.preview || task?.isActive == true) return
        awaitingWechatReturn = false
        leftForWechat = false
        val current = attempt
        update(visible = true, loading = true, phase = RechargePhase.Checking, error = null)
        task = viewModelScope.launch {
            try {
                requireOwner(pending)
                // One query for this return only: no polling, archived orders, or restart recovery.
                val result = repository.rechargeResult(pending)
                if (current != attempt) return@launch
                requireOwner(pending)
                when {
                    result.paid -> showResult(RechargePhase.Paid)
                    // The matched order currently has no completed payment. This
                    // is not an SDK cancellation callback or a closed-order claim.
                    result.serverStatus == 0 -> showResult(RechargePhase.Unpaid)
                    result.serverStatus == 2 -> showResult(RechargePhase.Failed, "支付已超时")
                    else -> showResult(RechargePhase.Unknown, "暂时无法确认订单状态")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == attempt) showResult(RechargePhase.Unknown, resultError(e))
            }
        }
    }

    private fun requireOwner(pending: PendingRecharge) {
        if (pending.username != owner || repository.username() != pending.username) throw YktException("账户已切换")
    }
    private fun showResult(phase: RechargePhase, error: String? = null) = update(visible = true, loading = false, phase = phase, error = error)
    fun accountChanged(username: String) {
        if (owner.isNotEmpty() && username != owner) dismiss()
    }
    fun finish() = dismiss()
    fun dismiss() {
        endAttempt()
        mutable.value = RechargeUiState()
    }
    private fun endAttempt() {
        attempt++
        task?.cancel()
        task = null
        returnCheck?.cancel()
        returnCheck = null
        awaitingWechatReturn = false
        leftForWechat = false
        owner = ""
        while (launches.tryReceive().isSuccess) { /* no stale payment launch */ }
    }
    fun onForeground() {
        foreground = true
    }
    fun onResumed(hasWindowFocus: Boolean) {
        resumed = true
        windowFocused = hasWindowFocus
        checkWechatReturn()
    }
    fun onPaused() {
        resumed = false
        returnCheck?.cancel()
        // A translucent WeChat payment activity may pause us without stopping us.
        if (awaitingWechatReturn) leftForWechat = true
    }
    fun onWindowFocusChanged(hasFocus: Boolean) {
        windowFocused = hasFocus
        if (!hasFocus) {
            returnCheck?.cancel()
            if (awaitingWechatReturn) leftForWechat = true
        } else {
            // Also handle payment windows that only take and restore window focus.
            checkWechatReturn()
        }
    }
    private fun checkWechatReturn() {
        if (!foreground || !resumed || !windowFocused || !awaitingWechatReturn || !leftForWechat) return
        if (returnCheck?.isActive == true) return
        val current = attempt
        val launchTask = task
        returnCheck = viewModelScope.launch {
            // A very quick cancel can resume us before the launch coroutine exits.
            // Wait for it rather than silently dropping that return notification.
            launchTask?.join()
            if (current == attempt && foreground && resumed && windowFocused && awaitingWechatReturn && leftForWechat) {
                // checkResult consumes the return flags: resume and focus cannot
                // issue duplicate queries for the same payment window.
                checkResult()
            }
        }
    }
    fun onBackground() {
        foreground = false
        returnCheck?.cancel()
        if (awaitingWechatReturn) leftForWechat = true
        if (mutable.value.phase == RechargePhase.Checking) {
            task?.cancel()
            task = null
            showResult(RechargePhase.Unknown, "结果待确认")
        }
    }
    private fun resultError(e: Exception) = when (e) {
        is NetworkFailure -> "网络异常，请稍后查看账单"
        is AuthenticationRequired, is InvalidCredentials, is CaptchaRequired -> "登录已失效，请重新登录"
        else -> "暂未确认本次付款，请稍后查看账单"
    }
    private fun safeError(e: Exception, fallback: String) = if (e is YktException) e.message ?: fallback else fallback
    private fun update(
        visible: Boolean = mutable.value.visible, loading: Boolean = mutable.value.loading,
        phase: RechargePhase = mutable.value.phase, error: String? = mutable.value.error,
        pending: PendingRecharge? = mutable.value.pending,
    ) {
        val state = mutable.value
        mutable.value = RechargeUiState(visible, loading, state.config, pending, phase, error, preview = state.preview)
    }
}
