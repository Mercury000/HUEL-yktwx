package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class FeeStage { Query, Methods, Password, Result }
enum class FeeRoomAction { Save, Unbind }
/** A new-payment draft, deliberately without the failed order's ID or pay ID. */
class FeePaymentRetry(val amountCents: Long, val methods: List<FeeMethod>) {
    override fun toString() = "FeePaymentRetry([redacted])"
}
class FeeRoomDialog(val action: FeeRoomAction, val target: FeeRoomBinding, val previous: FeeRoomBinding?) {
    override fun toString() = "FeeRoomDialog([redacted])"
}
class FeeUiState(
    val config: FeeConfiguration? = null, val loading: Boolean = false, val error: String? = null,
    val levels: List<FeeLevel> = emptyList(), val options: Map<Int, List<FeeOption>> = emptyMap(),
    val selections: Map<String, String> = emptyMap(), val names: Map<String, String> = emptyMap(),
    val query: FeeQuery? = null, val order: FeeOrder? = null, val method: FeeMethod? = null,
    val preparation: FeeAccountPreparation? = null, val stage: FeeStage = FeeStage.Query,
    val resultPhase: RechargePhase = RechargePhase.Creating, val preview: Boolean = false,
    val hiddenLevels: Set<String> = emptySet(), val refreshing: Boolean = false,
    val selecting: Boolean = false,
    val roomBinding: FeeRoomBinding? = config?.roomBinding,
    val roomBindingKnown: Boolean = config?.invalidRoomBinding != true,
    val roomBusy: Boolean = false, val roomDialog: FeeRoomDialog? = null,
    val roomNotice: String? = null,
    val paymentRetry: FeePaymentRetry? = null,
) {
    val canContinuePayment get() = !preview && !loading && stage == FeeStage.Result &&
        resultPhase == RechargePhase.Failed && query?.thirdParty != null &&
        paymentRetry?.methods?.isNotEmpty() == true
    val currentRoom get() = FeeRoomBinding.from(levels, selections, names)
    val roomAction get() = if (currentRoom?.sameTarget(roomBinding) == true) FeeRoomAction.Unbind else FeeRoomAction.Save
    val roomActionEnabled get() = config?.roomBindingEnabled == true && roomBindingKnown &&
        stage == FeeStage.Query && !loading && !selecting && !refreshing && !roomBusy && currentRoom != null &&
        (roomAction == FeeRoomAction.Unbind || (query?.thirdParty != null && query.tip == null))
    override fun toString() = "FeeUiState([redacted])"
}

class FeeViewModel internal constructor(
    app: Application,
    private val repository: AccountRepository,
    private val checkDelayMillis: Long = 5000,
    private val checkTimeoutMillis: Long = 15_000,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(FeeUiState())
    val state = mutable.asStateFlow()
    private val launches = Channel<WechatRechargeLaunch>(Channel.BUFFERED)
    val events = launches.receiveAsFlow()
    private var task: Job? = null
    private var checkDeadline: Job? = null
    private var owner = ""
    private var attempt = 0L
    private var completedChecks = 0
    private var accountPaymentSubmitted = false
    private val paymentReturn = PaymentReturnObserver(viewModelScope, { task },
        { !accountPaymentSubmitted && mutable.value.stage == FeeStage.Result &&
            mutable.value.resultPhase == RechargePhase.Checking }, ::checkResult)

    fun open(project: FeeProject, username: String, preview: Boolean) {
        dismiss()
        owner = username
        mutable.value = FeeUiState(loading = true, preview = preview)
        val generation = attempt
        launchTask {
            try {
                val config = if (preview) FeeConfiguration(project, project.label,
                    RechargeConfiguration(project.id, "", "", 0, if (project.network) listOf(1500,3000,4500) else listOf(1000,5000,10000), 1, 200000, true, false),
                    roomBindingEnabled = !project.network && project != FeeProject.Commercial)
                    else repository.feeConfiguration(username, project)
                if (attempt != generation) return@launchTask
                val initial = if (preview) previewQuery(project, false, 0)
                    else repository.feeQuery(username, project, 0, emptyMap(), false)
                if (attempt != generation) return@launchTask
                if (initial.tip != null) throw YktException(initial.tip)
                if (project.network || initial.levels.isEmpty()) {
                    val detail = if (preview) previewQuery(project, true, 0)
                        else repository.feeQuery(username, project, 0, emptyMap(), true)
                    if (attempt == generation) {
                        if (detail.tip != null) throw YktException(detail.tip)
                        mutable.value = FeeUiState(config, query = detail, preview = preview)
                    }
                } else {
                    val first = initial.levels.first()
                    val entrance = FeeUiState(config, levels = initial.levels,
                        options = mapOf(first.number to initial.options), preview = preview)
                    if (initial.options.isEmpty()) throw YktException("没有可用的校区")
                    if (config.roomBinding != null) {
                        restoreRoom(entrance, config.roomBinding, generation)
                    } else if (initial.options.size == 1 && first.key in setOf("campus", "xiaoqu_id")) {
                        mutable.value = copy(entrance, loading = true)
                        loadSelection(entrance, first, initial.options.single(), generation)
                    } else mutable.value = entrance
                    if (config.invalidRoomBinding) mutable.value = copy(mutable.value,
                        roomBindingKnown = false, error = "已保存的房间信息无法识别，请先在官网解绑或更正")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == generation) mutable.value = FeeUiState(error = message(e), preview = preview)
            }
        }
    }

    fun select(level: FeeLevel, option: FeeOption) {
        val state = mutable.value
        if (state.config == null || state.loading || state.selecting || state.roomBusy || state.roomDialog != null || state.stage != FeeStage.Query ||
            state.options[level.number]?.none { it.id == option.id } != false) return
        val generation = attempt
        mutable.value = copy(state, selecting = true, error = null, refreshing = false)
        launchTask {
            try { loadSelection(state, level, option, generation) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, selecting = false, error = message(e))
            }
        }
    }

    private suspend fun loadSelection(state: FeeUiState, level: FeeLevel, option: FeeOption, generation: Long,
        allowRoomPrompt: Boolean = true, continueLoading: Boolean = false) {
        val config = state.config ?: return
        val keep = state.levels.filter { it.number < level.number }.map { it.key }.toSet()
        val selections = state.selections.filterKeys { it in keep } + (level.key to option.id)
        val names = state.names.filterKeys { it in keep } + (level.key to option.name)
        val next = state.levels.firstOrNull { it.number > level.number }
        val hidden = if (level == state.levels.firstOrNull() && state.options[level.number]?.size == 1 &&
            level.key in setOf("campus", "xiaoqu_id")) state.hiddenLevels + level.key else state.hiddenLevels
        mutable.value = copy(mutable.value, selections = selections, names = names,
            options = state.options.filterKeys { it <= level.number }, query = null, hiddenLevels = hidden)
        val query = if (state.preview) previewQuery(config.project, next == null, level.number)
            else repository.feeQuery(owner, config.project, level.number, selections, next == null)
        if (generation != attempt) return
        mutable.value = copy(mutable.value, loading = continueLoading, selecting = false, error = query.tip, stage = state.stage,
            options = if (next == null) mutable.value.options else mutable.value.options + (next.number to query.options),
            query = if (next == null) query else null)
        if (next == null && allowRoomPrompt) offerRoomSave()
    }

    private suspend fun restoreRoom(entrance: FeeUiState, binding: FeeRoomBinding, generation: Long) {
        mutable.value = copy(entrance, loading = true)
        if (binding.selections.keys != entrance.levels.map { it.key }.toSet()) {
            mutable.value = copy(entrance, error = "已保存的房间选项已失效，请重新选择后保存")
            return
        }
        try {
            for ((index, level) in entrance.levels.withIndex()) {
                val current = mutable.value
                val option = current.options[level.number]?.firstOrNull { it.id == binding.selections[level.key] }
                if (option == null) {
                    mutable.value = copy(current, loading = false, error = "已保存的房间选项已失效，请重新选择后保存")
                    return
                }
                loadSelection(current, level, option, generation, allowRoomPrompt = false,
                    continueLoading = index != entrance.levels.lastIndex)
                if (attempt != generation) return
                if (mutable.value.error != null) {
                    mutable.value = copy(mutable.value, loading = false)
                    return
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (attempt == generation) mutable.value = copy(mutable.value, loading = false, error = message(e))
        }
    }

    private fun offerRoomSave() {
        val state = mutable.value
        if (state.preview || state.roomBinding != null || !state.roomActionEnabled || state.roomDialog != null) return
        mutable.value = copy(state, roomDialog = FeeRoomDialog(FeeRoomAction.Save, state.currentRoom!!, null))
    }

    fun requestRoomAction() {
        val state = mutable.value
        if (!state.roomActionEnabled || state.roomDialog != null || task?.isActive == true) return
        mutable.value = copy(state, roomDialog = FeeRoomDialog(state.roomAction, state.currentRoom!!, state.roomBinding))
    }

    fun dismissRoomDialog() {
        if (!mutable.value.roomBusy) mutable.value = copy(mutable.value, roomDialog = null)
    }

    fun consumeRoomNotice() { mutable.value = copy(mutable.value, roomNotice = null) }

    fun confirmRoomAction() {
        val state = mutable.value
        val config = state.config ?: return
        val dialog = state.roomDialog ?: return
        if (!state.roomActionEnabled || state.roomBusy || task?.isActive == true ||
            !dialog.target.sameTarget(state.currentRoom) || dialog.action != state.roomAction) return
        val generation = attempt
        val target = if (dialog.action == FeeRoomAction.Save) dialog.target else null
        mutable.value = copy(state, roomBusy = true, roomDialog = null, error = null, roomNotice = null)
        launchTask {
            var failure: Exception? = null
            try {
                if (!state.preview) repository.setFeeRoom(owner, config.project, target)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e }
            if (generation != attempt) return@launchTask
            // Even a lost write response must be reconciled by a read, never by replaying the write.
            try {
                val fresh = if (state.preview) target else repository.feeConfiguration(owner, config.project).let {
                    if (!it.roomBindingEnabled || it.invalidRoomBinding) throw YktException("房间绑定状态无法确认")
                    it.roomBinding
                }
                if (generation != attempt) return@launchTask
                val confirmed = if (target == null) fresh == null else target.sameTarget(fresh)
                mutable.value = copy(mutable.value, roomBusy = false, roomBinding = fresh, roomBindingKnown = true,
                    error = if (confirmed) null else failure?.let(::message) ?: "房间绑定未更新，请稍后重试",
                    roomNotice = if (confirmed) (if (target == null) "已解绑" else "已保存为我的房间") else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, roomBusy = false, roomBindingKnown = false,
                    error = "无法确认保存或解绑结果，请重新打开页面核实，勿重复提交")
            }
        }
    }

    fun refreshQuery() {
        val state = mutable.value
        val config = state.config ?: return
        if (state.loading || state.roomBusy || state.roomDialog != null || state.stage != FeeStage.Query || task?.isActive == true) return
        val generation = attempt
        val last = state.levels.lastOrNull { state.selections.containsKey(it.key) }
        val detail = config.project.network || state.levels.isEmpty() || last == state.levels.lastOrNull()
        val requestLevel = last?.number ?: 0
        val next = state.levels.firstOrNull { it.number > requestLevel }
        mutable.value = copy(state, loading = true, refreshing = true, error = null)
        launchTask {
            try {
                val query = if (state.preview) previewQuery(config.project, detail, requestLevel)
                    else repository.feeQuery(owner, config.project, requestLevel, state.selections, detail)
                if (generation == attempt) {
                    mutable.value = copy(mutable.value, loading = false, refreshing = false,
                        error = query.tip, query = if (detail) query else null,
                        options = if (!detail && next != null) mutable.value.options + (next.number to query.options) else mutable.value.options)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false, refreshing = false, error = message(e))
            }
        }
    }

    /** Dismissing an unpaid UI ends only this attempt; it does not pop the fee page. */
    fun cancelPayment() {
        if (mutable.value.stage !in setOf(FeeStage.Methods, FeeStage.Password, FeeStage.Result)) return
        endPaymentAttempt()
        mutable.value = copy(mutable.value, loading = false, refreshing = false, error = null,
            order = null, method = null, preparation = null, paymentRetry = null, stage = FeeStage.Query,
            resultPhase = RechargePhase.Creating)
    }
    private fun endPaymentAttempt() {
        attempt++
        task?.cancel()
        task = null
        checkDeadline?.cancel()
        checkDeadline = null
        paymentReturn.reset()
        completedChecks = 0
        accountPaymentSubmitted = false
        while (launches.tryReceive().isSuccess) { }
    }
    /** Only an explicit rejection permits a user-confirmed new order, never a replay. */
    fun continuePayment(method: FeeMethod) {
        val s = mutable.value
        val retry = s.paymentRetry ?: return
        val config = s.config ?: return
        val query = s.query ?: return
        if (!s.canContinuePayment || task?.isActive == true || s.roomBusy || s.roomDialog != null ||
            repository.username() != owner || retry.methods.none { it.id == method.id && it.code == method.code }) return
        if (config.amountRules.amountError(retry.amountCents) != null) return
        endPaymentAttempt()
        val generation = attempt
        val marker = PendingRecharge(owner, query.thirdParty ?: return, config.project.id,
            retry.amountCents, System.currentTimeMillis())
        mutable.value = copy(s, loading = true, error = null, order = FeeOrder(marker, emptyList()),
            method = null, preparation = null, paymentRetry = null, resultPhase = RechargePhase.Creating)
        launchTask {
            val order = try {
                repository.createFee(marker.username, config, query, retry.amountCents)
            } catch (e: CancellationException) { throw e }
            catch (e: FeePaymentRejected) {
                if (generation == attempt) result(RechargePhase.Failed, e.message)
                return@launchTask
            }
            catch (e: Exception) {
                if (generation == attempt) result(RechargePhase.Unknown,
                    "订单结果未确认，请在官网核实，确认前请勿重复缴费")
                return@launchTask
            }
            if (generation != attempt) return@launchTask
            // Keep the failed channel excluded even if the new order's method list has changed.
            val alternatives = order.methods.filter { current -> retry.methods.any { it.code == current.code } }
            mutable.value = copy(mutable.value, loading = false,
                order = FeeOrder(order.pending, alternatives), stage = FeeStage.Methods)
            // The previous list is presentation-only. Only the new order authorizes a method.
            val currentMethod = alternatives.firstOrNull { it.id == method.id && it.code == method.code }
            if (currentMethod == null) {
                if (alternatives.isEmpty()) result(RechargePhase.Failed, "其他支付方式已不可用，请重新发起缴费")
                else mutable.value = copy(mutable.value, error = "支付方式已变化，请重新选择")
                return@launchTask
            }
            prepareMethod(copy(mutable.value, order = order), currentMethod, generation)
        }
    }
    fun create(amount: Long) {
        val s = mutable.value; val config = s.config ?: return; val q = s.query ?: return
        if (s.preview || s.loading || s.roomBusy || s.roomDialog != null || s.selecting || s.stage != FeeStage.Query || task?.isActive == true) return
        config.amountRules.amountError(amount)?.let { mutable.value=copy(s,error=it); return }
        val generation=attempt
        accountPaymentSubmitted = false
        val marker = PendingRecharge(owner, q.thirdParty ?: return, config.project.id, amount, System.currentTimeMillis())
        mutable.value=copy(s,loading=true,error=null,order=FeeOrder(marker, emptyList()),paymentRetry=null)
        launchTask {
            try {
                val order=repository.createFee(marker.username,config,q,amount)
                if (generation==attempt) mutable.value=copy(mutable.value,loading=false,order=order,stage=FeeStage.Methods)
            } catch (e: CancellationException) { throw e }
            catch (e: FeePaymentRejected) {
                if (generation == attempt) result(RechargePhase.Failed, e.message)
            }
            catch (e: Exception) { if(generation==attempt) result(RechargePhase.Unknown, "订单结果未确认，请在官网核实，确认前请勿重复缴费") }
        }
    }
    fun chooseMethod(method: FeeMethod) {
        val s = mutable.value
        val order = s.order ?: return
        if (s.loading || task?.isActive == true || s.stage != FeeStage.Methods ||
            order.methods.none { it.id == method.id && it.code == method.code }) return
        val generation = attempt
        launchTask { prepareMethod(s, method, generation) }
    }
    private suspend fun prepareMethod(s: FeeUiState, method: FeeMethod, generation: Long) {
        val order = s.order ?: return
        val pending = order.pending.withOrder(order.pending.orderId ?: return, method.id)
        mutable.value = copy(s, loading = true, error = null, order = FeeOrder(pending, order.methods),
            method = method, preparation = null, paymentRetry = null)
        try {
            if (method.wechat) {
                mutable.value = copy(mutable.value, stage = FeeStage.Result, resultPhase = RechargePhase.Creating)
                val launch = repository.launchFeeWechat(pending, method)
                if (generation != attempt) return
                mutable.value = copy(mutable.value, loading = false, resultPhase = RechargePhase.AwaitingPayment)
                if (paymentReturn.canLaunch) launches.send(launch) else result(RechargePhase.Unknown, "支付尚未完成")
            } else {
                val preparation = repository.prepareFeeAccount(pending, method)
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false,
                    stage = FeeStage.Password, preparation = preparation)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: FeeOrderExpired) { if (generation == attempt) result(RechargePhase.Failed, e.message) }
        catch (e: FeeOrderAlreadyPaid) { if (generation == attempt) { accountPaymentSubmitted = true; startCheck() } }
        catch (e: FeePaymentRejected) {
            if (generation == attempt) result(RechargePhase.Failed, e.message, retryable = true)
        }
        catch (e: Exception) {
            if (generation == attempt) {
                if (method.wechat) result(RechargePhase.Unknown, unknownDetail(message(e)))
                else result(RechargePhase.Failed, message(e), retryable = true)
            }
        }
    }
    fun payAccount(type: String, password: String) {
        val s = mutable.value
        val pending = s.order?.pending ?: return
        val method = s.method ?: return
        val preparation = s.preparation ?: return
        if (s.preview || s.loading || task?.isActive == true || s.stage != FeeStage.Password) return
        if (!password.matches(Regex("[0-9]{6}")) || preparation.accounts.none { it.type == type }) return
        val generation = attempt
        // Keep the password dialog in place while submitting. It has already cleared the input.
        mutable.value = copy(s, loading = true, error = null)
        launchTask {
            try {
                repository.payFeeAccount(pending, method, preparation, type, password)
                if (generation == attempt) { accountPaymentSubmitted = true; startCheck() }
            } catch (e: CancellationException) { throw e }
            catch (e: FeeOrderExpired) { if (generation == attempt) result(RechargePhase.Failed, e.message) }
            catch (e: FeeOrderAlreadyPaid) {
                if (generation == attempt) { accountPaymentSubmitted = true; startCheck() }
            }
            catch (e: FeePaymentNotStarted) {
                if (generation == attempt) result(RechargePhase.Failed, e.message, retryable = true)
            }
            catch (e: FeePaymentRejected) {
                if (generation == attempt) result(RechargePhase.Failed, e.message, retryable = true)
            }
            catch (e: AuthenticationRequired) { if (generation == attempt) result(RechargePhase.Unknown, unknownDetail(message(e))) }
            catch (e: InvalidCredentials) { if (generation == attempt) result(RechargePhase.Unknown, unknownDetail(message(e))) }
            catch (e: CaptchaRequired) { if (generation == attempt) result(RechargePhase.Unknown, unknownDetail(message(e))) }
            catch (_: Exception) {
                // A lost response may still mean a successful debit. Only query, never replay.
                if (generation == attempt) { accountPaymentSubmitted = true; startCheck() }
            }
        }
    }
    private fun startCheck() {
        task=null
        // Account queries need no external app return; dialog focus changes must not pause them.
        if (accountPaymentSubmitted || paymentReturn.canLaunch) checkResult()
        else {
            mutable.value=copy(mutable.value,loading=true,error=null,stage=FeeStage.Result,resultPhase=RechargePhase.Checking,preparation=null)
            paymentReturn.checkOnNextInteraction()
        }
    }
    fun checkResult() {
        val pending=mutable.value.order?.pending ?: return
        if(task?.isActive==true)return
        if(mutable.value.resultPhase!=RechargePhase.Checking)completedChecks=0
        paymentReturn.consume()
        val generation=attempt
        mutable.value=copy(mutable.value,loading=true,error=null,stage=FeeStage.Result,resultPhase=RechargePhase.Checking,preparation=null)
        startCheckDeadline(generation)
        launchTask {
            while(completedChecks<3) {
                try {
                    if(completedChecks>0)delay(checkDelayMillis)
                    if(generation!=attempt)return@launchTask
                    val response=repository.feeResult(pending)
                    if(generation!=attempt)return@launchTask
                    completedChecks++
                    if(response.paid){result(RechargePhase.Paid);return@launchTask}
                    if (completedChecks == 3) when {
                        response.serverStatus == 2 -> result(RechargePhase.Failed, "订单已过期，请重新发起缴费")
                        response.serverStatus == 0 && !accountPaymentSubmitted -> result(RechargePhase.Failed)
                        else -> result(RechargePhase.Unknown, unknownDetail())
                    }
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    if(generation!=attempt)return@launchTask
                    if (repository.username() != pending.username || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired) {
                        result(RechargePhase.Unknown,unknownDetail(message(e)));return@launchTask
                    }
                    completedChecks++
                    if(completedChecks==3)result(RechargePhase.Unknown,unknownDetail(message(e)))
                }
            }
        }
    }
    private fun launchTask(block: suspend CoroutineScope.() -> Unit) {
        // Main.immediate may complete inline and launch a successor query before launch returns.
        // Install this job first so the caller cannot overwrite the successor's task reference.
        val job = viewModelScope.launch(start = CoroutineStart.LAZY, block = block)
        task = job
        job.start()
    }
    private fun startCheckDeadline(generation: Long) {
        if (checkDeadline?.isActive == true) return
        checkDeadline = viewModelScope.launch {
            delay(checkTimeoutMillis)
            if (generation == attempt && mutable.value.resultPhase == RechargePhase.Checking) {
                // This independent UI deadline also covers a suspended query, shared lock or lost focus.
                val query = task
                result(RechargePhase.Unknown, unknownDetail("查询超时"))
                query?.cancel()
            }
        }
    }
    fun canLaunchWechat()=paymentReturn.canLaunch && repository.username()==owner && mutable.value.resultPhase==RechargePhase.AwaitingPayment
    fun wechatLaunched()=paymentReturn.launched()
    fun launchFailed(){paymentReturn.consume();result(RechargePhase.Failed,"无法打开微信，请确认已安装微信",retryable=true)}
    fun accountChanged(username:String){if(owner.isNotEmpty() && username!=owner)dismiss()}
    fun dismiss(){attempt++;task?.cancel();task=null;checkDeadline?.cancel();checkDeadline=null;paymentReturn.reset();owner="";completedChecks=0;accountPaymentSubmitted=false;while(launches.tryReceive().isSuccess){};mutable.value=FeeUiState()}
    fun onForeground()=paymentReturn.foreground()
    fun onResumed(focus:Boolean)=paymentReturn.resumed(focus)
    fun onPaused()=paymentReturn.paused()
    fun onWindowFocusChanged(focus:Boolean)=paymentReturn.focus(focus)
    fun onBackground()=paymentReturn.background()
    private fun result(phase: RechargePhase, error: String? = null, retryable: Boolean = false) {
        checkDeadline?.cancel()
        checkDeadline = null
        paymentReturn.reset()
        val s = mutable.value
        val retry = if (phase == RechargePhase.Failed && retryable && s.method != null) s.order?.let {
            val alternatives = it.methods.filter { method -> method.id != s.method.id && method.code != s.method.code }
                .distinctBy { method -> method.id to method.code }
            FeePaymentRetry(it.pending.amountCents, alternatives)
        } else null
        mutable.value = copy(s, loading = false, error = error,
            stage = FeeStage.Result, resultPhase = phase, preparation = null,
            order = if (retry != null) null else s.order,
            method = if (retry != null) null else s.method, paymentRetry = retry)
    }
    private fun unknownDetail(reason: String? = null) = listOfNotNull(reason,
        "请稍后在账单中核实，确认前请勿重复缴费").joinToString("。")
    private fun message(e:Exception)=if(e is YktException)e.message ?: "服务暂不可用" else "服务数据异常，请稍后重试"
    private fun previewQuery(project:FeeProject,detail:Boolean,level:Int):FeeQuery=if(detail)FeeQuery(thirdParty="{}",display=listOf((if(project.network)"用户套餐" else "剩余电量") to (if(project.network)"预览套餐" else "100 度")))
        else {val levels=if(project.network)emptyList() else listOf(FeeLevel("campus",1,"校区"),FeeLevel("building",2,"楼栋"),FeeLevel("floor",3,"楼层"),FeeLevel("room",4,"房间"));FeeQuery(levels=levels,options=listOf(FeeOption("demo","预览"+(levels.firstOrNull{it.number>level}?.name ?: "校区"))))}
    private fun copy(
        s: FeeUiState,
        loading: Boolean = s.loading,
        error: String? = s.error,
        levels: List<FeeLevel> = s.levels,
        options: Map<Int, List<FeeOption>> = s.options,
        selections: Map<String, String> = s.selections,
        names: Map<String, String> = s.names,
        query: FeeQuery? = s.query,
        order: FeeOrder? = s.order,
        method: FeeMethod? = s.method,
        preparation: FeeAccountPreparation? = s.preparation,
        stage: FeeStage = s.stage,
        resultPhase: RechargePhase = s.resultPhase,
        hiddenLevels: Set<String> = s.hiddenLevels,
        refreshing: Boolean = s.refreshing,
        selecting: Boolean = s.selecting,
        roomBinding: FeeRoomBinding? = s.roomBinding,
        roomBindingKnown: Boolean = s.roomBindingKnown,
        roomBusy: Boolean = s.roomBusy,
        roomDialog: FeeRoomDialog? = s.roomDialog,
        roomNotice: String? = s.roomNotice,
        paymentRetry: FeePaymentRetry? = s.paymentRetry,
    ) = FeeUiState(s.config, loading, error, levels, options, selections, names, query, order,
        method, preparation, stage, resultPhase, s.preview, hiddenLevels, refreshing, selecting,
        roomBinding, roomBindingKnown, roomBusy, roomDialog, roomNotice, paymentRetry)
}
