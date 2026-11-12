package com.mercury.yktwx

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
@HiltViewModel
class RechargeViewModel internal constructor(
    private val repository: AccountRepository,
    private val checkDelayMillis: Long = 5_000,
    private val analytics: AppAnalytics,
) : ViewModel() {
    @Inject internal constructor(repository: AccountRepository, analytics: AppAnalytics) : this(repository, checkDelayMillis = 5000, analytics = analytics)
    private val mutable = MutableStateFlow(RechargeUiState())
    val state = mutable.asStateFlow()
    private val launches = Channel<WechatRechargeLaunch>(Channel.BUFFERED)
    val events = launches.receiveAsFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var operationIdentity: AccountIdentity? = null
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L
    private val resultPoller = OrderResultPoller(viewModelScope, checkDelayMillis)
    private var paymentExplicitlyRejected = false
    private var analyticsAttempt: AnalyticsAttempt? = null
    private var analyticsFeature = AnalyticsFeature.RechargeSelf
    private val paymentReturn = PaymentReturnObserver(
        viewModelScope, { task }, { mutable.value.loading && mutable.value.phase == RechargePhase.Checking }, ::checkResult,
    )

    init {
        repository.observeAccount(viewModelScope) { username, renewed ->
            if (!mutable.value.preview && owner.isNotBlank() && (renewed || owner != username)) dismiss()
        }
    }

    fun open(username: String, preferredCard: String?, preview: Boolean) {
        // Opening recharge is always a new amount selection, never an old result.
        endAttempt()
        owner = username
        operationIdentity = repository.identity(username)
        val current = attempt
        mutable.value = RechargeUiState(loading = true, preview = UiPreview.enabled && preview)
        task = viewModelScope.launch {
            try {
                val config = if (UiPreview.enabled && preview) PreviewData.rechargeConfiguration() else repository.rechargeConfiguration(preferredCard)
                if (attempt == current && !preview) operationIdentity = repository.identity(username)
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
        operationIdentity = repository.identity(username)
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
        analyticsFeature = if (config.recipient == null) AnalyticsFeature.RechargeSelf else AnalyticsFeature.RechargeOther
        analyticsAttempt = analytics.submit(analyticsFeature)
        val current = attempt
        val pending = PendingRecharge(owner, config.targetId, config.feeItemId, amountCents, recipient = config.recipient)
        update(loading = true, pending = pending, phase = RechargePhase.Creating, error = null)
        task = viewModelScope.launch {
            try {
                // No automatic retry of either POST. Only retain this live attempt in memory.
                repository.restoreSession()
                if (current != attempt) return@launch
                operationIdentity = repository.identity(pending.username)
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
                    analytics.operationFailed(analyticsAttempt, analyticsFeature, AnalyticsError.from(e))
                    paymentReturn.reset()
                    showResult(RechargePhase.Failed, e.message)
                }
            }
            catch (e: Exception) {
                if (current == attempt) {
                    analytics.operationFailed(analyticsAttempt, analyticsFeature, AnalyticsError.from(e))
                    requestError(resultError(e))
                }
            }
        }
    }

    fun canLaunchWechat(): Boolean = paymentReturn.canLaunch && repository.ownsAccount(owner) && mutable.value.phase == RechargePhase.AwaitingPayment
    fun wechatLaunched() = paymentReturn.launched()
    fun launchFailed() {
        analytics.operationFailed(analyticsAttempt, analyticsFeature, AnalyticsError.External)
        paymentReturn.consume()
        showResult(RechargePhase.Failed, "无法打开微信，请确认已安装微信")
    }

    fun checkResult() {
        val pending = mutable.value.pending ?: return
        if (pending.orderId == null || mutable.value.preview || paymentExplicitlyRejected ||
            mutable.value.phase == RechargePhase.Paid || task?.isActive == true) return
        val newRound = mutable.value.phase != RechargePhase.Checking || !mutable.value.loading
        paymentReturn.consume()
        val current = attempt
        update(visible = true, loading = true, phase = RechargePhase.Checking, error = null)
        resultPoller.start(newRound, isCurrent = { current == attempt }, onJob = { task = it },
            query = {
                requireOwner(pending)
                repository.rechargeResult(pending).also { requireOwner(pending) }
            },
            onResult = { outcome ->
                if (outcome.phase == null) queryError(outcome.message ?: "订单状态异常")
                else showResult(outcome.phase, outcome.message)
            }, onError = { queryError(resultError(it)) })
    }

    private fun requireOwner(pending: PendingRecharge) {
        if (pending.username != owner || !repository.ownsAccount(pending.username)) throw YktException("账户已切换")
    }
    private fun showResult(phase: RechargePhase, error: String? = null) {
        resultPoller.stop()
        val previous = mutable.value
        update(visible = true, loading = false, phase = phase, error = error)
        if (phase == RechargePhase.Paid && previous.phase != RechargePhase.Paid && !previous.preview && repository.ownsAccount(owner))
            repository.dataChanges.transaction(operationIdentity, TransactionUpdate(owner, balance = previous.pending?.recipient == null,
                income = true.takeIf { previous.pending?.recipient == null }))
    }
    private fun requestError(message: String) {
        analytics.operationFailed(analyticsAttempt, analyticsFeature, AnalyticsError.External)
        paymentReturn.reset()
        update(visible = true, loading = false, phase = RechargePhase.Amount, pending = null, error = message)
    }
    private fun queryError(message: String) {
        resultPoller.stop()
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
        analyticsAttempt = null
        attempt++
        task?.cancel()
        task = null
        resultPoller.reset()
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
