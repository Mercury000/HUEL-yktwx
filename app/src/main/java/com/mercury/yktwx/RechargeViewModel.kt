package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class RechargePhase { Amount, Creating, AwaitingPayment, Checking, Paid, Failed, TimedOut }
class RechargeUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val config: RechargeConfiguration? = null,
    val pending: PendingRecharge? = null,
    val phase: RechargePhase = RechargePhase.Amount,
    val error: String? = null,
    val preview: Boolean = false,
    val errorFields: Set<FormField> = emptySet(),
) {
    override fun toString() = "RechargeUiState([redacted])"
}

/** One foreground recharge attempt; closed or cancelled attempts never lock the next one. */
class RechargeViewModel internal constructor(
    app: Application,
    private val repository: AccountRepository,
    private val checkDelayMillis: Long = 5_000,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(RechargeUiState())
    val state = mutable.asStateFlow()
    private val launches = Channel<WechatRechargeLaunch>(Channel.BUFFERED)
    val events = launches.receiveAsFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private val updates = Channel<TransactionUpdate>(Channel.BUFFERED)
    val transactionUpdates = updates.receiveAsFlow()
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L
    private var resultQueriesCompleted = 0
    private var paymentExplicitlyRejected = false
    private val paymentReturn = PaymentReturnObserver(
        viewModelScope, { task }, { mutable.value.loading && mutable.value.phase == RechargePhase.Checking }, ::checkResult,
    )

    fun open(username: String, preferredCard: String?, preview: Boolean) {
        // Opening recharge is always a new amount selection, never an old result.
        endAttempt()
        owner = username
        val current = attempt
        mutable.value = RechargeUiState(loading = true, preview = UiPreview.enabled && preview)
        task = viewModelScope.launch {
            try {
                val config = if (UiPreview.enabled && preview) PreviewData.rechargeConfiguration() else repository.rechargeConfiguration(preferredCard)
                if (attempt == current) mutable.value = RechargeUiState(visible = true, config = config, preview = UiPreview.enabled && preview)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == current) {
                    mutable.value = RechargeUiState(preview = UiPreview.enabled && preview)
                    // Toasts are only for failures before a dialog can be opened.
                    notifications.send(safeError(e, "读取充值信息失败，请稍后重试"))
                }
            }
        }
    }

    fun openOthers(username: String, config: RechargeConfiguration, preview: Boolean) {
        if (config.recipient == null) return
        endAttempt()
        owner = username
        mutable.value = RechargeUiState(visible = true, config = config, preview = UiPreview.enabled && preview)
    }

    fun inputEdited(field: FormField) {
        val s = mutable.value
        if (s.visible && !s.loading && s.pending == null && field in s.errorFields)
            update(error = null)
    }
    fun submit(amountCents: Long) {
        val state = mutable.value
        if (state.preview || state.loading || state.pending != null || task?.isActive == true) return
        val config = state.config ?: return
        config.amountError(amountCents)?.let { update(error = it, errorFields = setOf(FormField.Amount)); return }
        if (!repository.ownsAccount(owner)) { update(error = "账户已切换，请重新打开充值"); return }
        val current = attempt
        val pending = PendingRecharge(owner, config.targetId, config.feeItemId, amountCents, recipient = config.recipient)
        update(loading = true, pending = pending, phase = RechargePhase.Creating, error = null)
        task = viewModelScope.launch {
            try {
                // No automatic retry of either POST. Only retain this live attempt in memory.
                val order = repository.createRecharge(pending.username, config, amountCents)
                if (current != attempt) return@launch
                val created = pending.withOrder(order.orderId, order.payId)
                update(pending = created) // Stay Creating until valid payment parameters are available.
                requireOwner(created)
                val launch = repository.launchRecharge(created)
                if (current != attempt) return@launch
                requireOwner(created)
                if (!paymentReturn.canLaunch) {
                    requestError("未能打开微信")
                    return@launch
                }
                update(loading = false, phase = RechargePhase.AwaitingPayment)
                launches.send(launch)
            } catch (e: CancellationException) { throw e }
            catch (e: RechargePaymentRejected) {
                if (current == attempt) {
                    paymentExplicitlyRejected = true
                    paymentReturn.reset()
                    showResult(RechargePhase.Failed, e.message)
                }
            }
            catch (e: Exception) {
                if (current == attempt) requestError(resultError(e))
            }
        }
    }

    fun canLaunchWechat(): Boolean = paymentReturn.canLaunch && repository.ownsAccount(owner) && mutable.value.phase == RechargePhase.AwaitingPayment
    fun wechatLaunched() = paymentReturn.launched()
    fun launchFailed() {
        paymentReturn.consume()
        showResult(RechargePhase.Failed, "无法打开微信，请确认已安装微信")
    }

    fun checkResult() {
        val pending = mutable.value.pending ?: return
        if (pending.orderId == null || mutable.value.preview ||
            (paymentExplicitlyRejected || mutable.value.phase == RechargePhase.Paid) || task?.isActive == true) return
        if (mutable.value.phase != RechargePhase.Checking || !mutable.value.loading) resultQueriesCompleted = 0
        paymentReturn.consume()
        val current = attempt
        update(visible = true, loading = true, phase = RechargePhase.Checking, error = null)
        task = viewModelScope.launch {
            while (resultQueriesCompleted < 3) {
                try {
                    // Completed attempts keep their budget across pause/resume.
                    // Interrupted read-only requests may be repeated, but no
                    // create/pay POST is retried here.
                    if (resultQueriesCompleted > 0) delay(checkDelayMillis)
                    if (current != attempt) return@launch
                    requireOwner(pending)
                    val result = repository.rechargeResult(pending)
                    if (current != attempt) return@launch
                    requireOwner(pending)
                    resultQueriesCompleted++
                    if (result.paid) {
                        showResult(RechargePhase.Paid)
                        return@launch
                    }
                    if (resultQueriesCompleted == 3) {
                        when (result.serverStatus) {
                            0 -> showResult(RechargePhase.Failed, result.message)
                            2 -> showResult(RechargePhase.TimedOut, result.message)
                            else -> queryError(result.message ?: "订单状态异常")
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (current != attempt) return@launch
                    queryError(resultError(e))
                    return@launch
                }
            }
        }
    }

    private fun requireOwner(pending: PendingRecharge) {
        if (pending.username != owner || !repository.ownsAccount(pending.username)) throw YktException("账户已切换")
    }
    private fun showResult(phase: RechargePhase, error: String? = null) {
        val previous = mutable.value
        update(visible = true, loading = false, phase = phase, error = error)
        if (phase == RechargePhase.Paid && previous.phase != RechargePhase.Paid && !previous.preview && repository.ownsAccount(owner))
            updates.trySend(TransactionUpdate(owner, balance = previous.pending?.recipient == null,
                income = true.takeIf { previous.pending?.recipient == null }))
    }
    private fun requestError(message: String) {
        paymentReturn.reset()
        update(visible = true, loading = false, phase = RechargePhase.Amount, pending = null, error = message)
    }
    private fun queryError(message: String) {
        paymentReturn.reset()
        // A request error is shown on the result page, not classified as a transaction outcome.
        update(visible = true, loading = false, phase = RechargePhase.Checking, error = message)
    }
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
        resultQueriesCompleted = 0
        paymentExplicitlyRejected = false
        paymentReturn.reset()
        owner = ""
        while (launches.tryReceive().isSuccess) { /* no stale payment launch */ }
    }
    fun onForeground() = paymentReturn.foreground()
    fun onResumed(hasWindowFocus: Boolean) = paymentReturn.resumed(hasWindowFocus)
    fun onPaused() = paymentReturn.paused()
    fun onWindowFocusChanged(hasFocus: Boolean) = paymentReturn.focus(hasFocus)
    fun onBackground() = paymentReturn.background()
    private fun resultError(e: Exception) = when (e) {
        is NetworkFailure -> e.message ?: "网络连接失败"
        is AuthenticationRequired, is InvalidCredentials, is CaptchaRequired -> "登录已失效，请重新登录"
        else -> safeError(e, "服务响应异常")
    }
    private fun safeError(e: Exception, fallback: String) = if (e is YktException) e.message ?: fallback else fallback
    private fun update(
        visible: Boolean = mutable.value.visible, loading: Boolean = mutable.value.loading,
        phase: RechargePhase = mutable.value.phase, error: String? = mutable.value.error,
        pending: PendingRecharge? = mutable.value.pending,
        errorFields: Set<FormField> = if (error != null && error == mutable.value.error) mutable.value.errorFields else emptySet(),
    ) {
        val state = mutable.value
        mutable.value = RechargeUiState(visible, loading, state.config, pending, phase, error, preview = state.preview, errorFields = errorFields)
    }
}
