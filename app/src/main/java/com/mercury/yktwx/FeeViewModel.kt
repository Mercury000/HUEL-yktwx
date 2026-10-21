package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class FeeStage { Query, Methods, Password, Result }
class FeeUiState(
    val config: FeeConfiguration? = null, val loading: Boolean = false, val error: String? = null,
    val levels: List<FeeLevel> = emptyList(), val options: Map<Int, List<FeeOption>> = emptyMap(),
    val selections: Map<String, String> = emptyMap(), val names: Map<String, String> = emptyMap(),
    val query: FeeQuery? = null, val order: FeeOrder? = null, val method: FeeMethod? = null,
    val preparation: FeeAccountPreparation? = null, val stage: FeeStage = FeeStage.Query,
    val resultPhase: RechargePhase = RechargePhase.Creating, val preview: Boolean = false,
    val hiddenLevels: Set<String> = emptySet(), val refreshing: Boolean = false,
    val selecting: Boolean = false,
) { override fun toString() = "FeeUiState([redacted])" }

class FeeViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AccountRepository.shared(app)
    private val mutable = MutableStateFlow(FeeUiState())
    val state = mutable.asStateFlow()
    private val launches = Channel<WechatRechargeLaunch>(Channel.BUFFERED)
    val events = launches.receiveAsFlow()
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L
    private var completedChecks = 0
    private val paymentReturn = PaymentReturnObserver(viewModelScope, { task },
        { mutable.value.stage == FeeStage.Result && mutable.value.resultPhase == RechargePhase.Checking }, ::checkResult)

    fun open(project: FeeProject, username: String, preview: Boolean) {
        dismiss()
        owner = username
        mutable.value = FeeUiState(loading = true, preview = preview)
        val generation = attempt
        task = viewModelScope.launch {
            try {
                val config = if (preview) FeeConfiguration(project, project.label,
                    RechargeConfiguration(project.id, "", "", 0, if (project.network) listOf(1500,3000,4500) else listOf(1000,5000,10000), 1, 200000, true, false))
                    else repository.feeConfiguration(username, project)
                if (attempt != generation) return@launch
                val initial = if (preview) previewQuery(project, false, 0)
                    else repository.feeQuery(username, project, 0, emptyMap(), false)
                if (attempt != generation) return@launch
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
                    if (initial.options.size == 1 && first.key in setOf("campus", "xiaoqu_id")) {
                        mutable.value = copy(entrance, loading = true)
                        loadSelection(entrance, first, initial.options.single(), generation)
                    } else mutable.value = entrance
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == generation) mutable.value = FeeUiState(error = message(e), preview = preview)
            }
        }
    }

    fun select(level: FeeLevel, option: FeeOption) {
        val state = mutable.value
        if (state.config == null || state.loading || state.selecting || state.stage != FeeStage.Query ||
            state.options[level.number]?.none { it.id == option.id } != false) return
        val generation = attempt
        mutable.value = copy(state, selecting = true, error = null, refreshing = false)
        task = viewModelScope.launch {
            try { loadSelection(state, level, option, generation) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, selecting = false, error = message(e))
            }
        }
    }

    private suspend fun loadSelection(state: FeeUiState, level: FeeLevel, option: FeeOption, generation: Long) {
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
        mutable.value = copy(mutable.value, loading = false, selecting = false, error = query.tip, stage = state.stage,
            options = if (next == null) mutable.value.options else mutable.value.options + (next.number to query.options),
            query = if (next == null) query else null)
    }

    fun refreshQuery() {
        val state = mutable.value
        val config = state.config ?: return
        if (state.loading || state.stage != FeeStage.Query || task?.isActive == true) return
        val generation = attempt
        val last = state.levels.lastOrNull { state.selections.containsKey(it.key) }
        val detail = config.project.network || state.levels.isEmpty() || last == state.levels.lastOrNull()
        val requestLevel = last?.number ?: 0
        val next = state.levels.firstOrNull { it.number > requestLevel }
        mutable.value = copy(state, loading = true, refreshing = true, error = null)
        task = viewModelScope.launch {
            try {
                val query = if (state.preview) previewQuery(config.project, detail, requestLevel)
                    else repository.feeQuery(owner, config.project, requestLevel, state.selections, detail)
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false, refreshing = false,
                    error = query.tip, query = if (detail) query else null,
                    options = if (!detail && next != null) mutable.value.options + (next.number to query.options) else mutable.value.options)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false, refreshing = false, error = message(e))
            }
        }
    }

    /** Dismissing an unpaid UI ends only this attempt; it does not pop the fee page. */
    fun cancelPayment() {
        if (mutable.value.stage !in setOf(FeeStage.Methods, FeeStage.Password, FeeStage.Result)) return
        attempt++
        task?.cancel()
        task = null
        paymentReturn.reset()
        completedChecks = 0
        while (launches.tryReceive().isSuccess) { }
        mutable.value = copy(mutable.value, loading = false, refreshing = false, error = null,
            order = null, method = null, preparation = null, stage = FeeStage.Query,
            resultPhase = RechargePhase.Creating)
    }
    fun create(amount: Long) {
        val s = mutable.value; val config = s.config ?: return; val q = s.query ?: return
        if (s.preview || s.loading || s.stage != FeeStage.Query || task?.isActive == true) return
        config.amountRules.amountError(amount)?.let { mutable.value=copy(s,error=it); return }
        val generation=attempt
        val marker = PendingRecharge(owner, q.thirdParty ?: return, config.project.id, amount, System.currentTimeMillis())
        mutable.value=copy(s,loading=true,error=null,order=FeeOrder(marker, emptyList()))
        task=viewModelScope.launch {
            try {
                val order=repository.createFee(marker.username,config,q,amount)
                if (generation==attempt) mutable.value=copy(mutable.value,loading=false,order=order,stage=FeeStage.Methods)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(generation==attempt) mutable.value=copy(mutable.value,loading=false,stage=FeeStage.Result,error="订单结果未确认，请在官网核实",resultPhase=RechargePhase.Unknown) }
        }
    }
    fun chooseMethod(method: FeeMethod) {
        val s=mutable.value; val order=s.order ?: return
        if(s.loading || task?.isActive == true || s.stage!=FeeStage.Methods || order.methods.none { it.id==method.id && it.code==method.code }) return
        val generation=attempt
        val pending=order.pending.withOrder(order.pending.orderId!!,method.id)
        mutable.value=copy(s,loading=true,error=null,order=FeeOrder(pending,order.methods),method=method,preparation=null)
        task=viewModelScope.launch {
            try {
                if(method.wechat) {
                    mutable.value=copy(mutable.value,stage=FeeStage.Result,resultPhase=RechargePhase.Creating)
                    val launch=repository.launchFeeWechat(pending,method)
                    if(generation!=attempt)return@launch
                    mutable.value=copy(mutable.value,loading=false,resultPhase=RechargePhase.AwaitingPayment)
                    if(paymentReturn.canLaunch) launches.send(launch) else result(RechargePhase.Unknown,"支付尚未完成")
                } else {
                    val preparation=repository.prepareFeeAccount(pending,method)
                    if(generation==attempt) mutable.value=copy(mutable.value,loading=false,stage=FeeStage.Password,preparation=preparation)
                }
            } catch(e: CancellationException){throw e}
            catch(e: Exception){if(generation==attempt) { if(method.wechat) result(RechargePhase.Unknown,message(e)) else mutable.value=copy(mutable.value,loading=false,error=message(e),stage=FeeStage.Methods) }}
        }
    }
    fun payAccount(type: String,password: String) {
        val s=mutable.value; val pending=s.order?.pending ?: return; val method=s.method ?: return;val prep=s.preparation ?: return
        if(s.preview || s.loading || task?.isActive == true || s.stage!=FeeStage.Password)return
        if (!password.matches(Regex("[0-9]{6}")) || prep.accounts.none { it.type == type }) return
        val generation=attempt
        mutable.value=copy(s,loading=true,error=null,stage=FeeStage.Result,resultPhase=RechargePhase.Creating,preparation=null)
        task=viewModelScope.launch {
            try {
                repository.payFeeAccount(pending,method,prep,type,password)
                if(generation==attempt) startCheck()
            } catch(e: CancellationException){throw e}
            catch(e: Exception){
                // Never replay the password POST. The order may have been paid
                // even when its acknowledgement was lost; use read-only checks.
                if(generation==attempt) startCheck()
            }
        }
    }
    private fun startCheck() {
        task=null
        if (paymentReturn.canLaunch) checkResult()
        else {
            mutable.value=copy(mutable.value,loading=true,error=null,stage=FeeStage.Result,resultPhase=RechargePhase.Checking)
            paymentReturn.checkOnNextInteraction()
        }
    }
    fun checkResult() {
        val pending=mutable.value.order?.pending ?: return
        if(task?.isActive==true)return
        if(mutable.value.resultPhase!=RechargePhase.Checking)completedChecks=0
        paymentReturn.consume()
        val generation=attempt
        mutable.value=copy(mutable.value,loading=true,error=null,stage=FeeStage.Result,resultPhase=RechargePhase.Checking)
        task=viewModelScope.launch {
            while(completedChecks<3) {
                try {
                    if(completedChecks>0)delay(5000)
                    if(generation!=attempt)return@launch
                    val response=repository.feeResult(pending)
                    if(generation!=attempt)return@launch
                    completedChecks++
                    if(response.paid){result(RechargePhase.Paid);return@launch}
                    if(completedChecks==3)result(if(response.serverStatus in listOf(0,2))RechargePhase.Failed else RechargePhase.Unknown,
                        if(response.serverStatus==2)"支付已超时" else if(response.serverStatus==0)null else "结果待确认")
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    if(generation!=attempt)return@launch
                    if (repository.username() != pending.username || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired) {
                        result(RechargePhase.Unknown,message(e));return@launch
                    }
                    completedChecks++
                    if(completedChecks==3)result(RechargePhase.Unknown,message(e))
                }
            }
        }
    }
    fun canLaunchWechat()=paymentReturn.canLaunch && repository.username()==owner && mutable.value.resultPhase==RechargePhase.AwaitingPayment
    fun wechatLaunched()=paymentReturn.launched()
    fun launchFailed(){paymentReturn.consume();result(RechargePhase.Failed,"无法打开微信，请确认已安装微信")}
    fun accountChanged(username:String){if(owner.isNotEmpty() && username!=owner)dismiss()}
    fun dismiss(){attempt++;task?.cancel();task=null;paymentReturn.reset();owner="";completedChecks=0;while(launches.tryReceive().isSuccess){};mutable.value=FeeUiState()}
    fun onForeground()=paymentReturn.foreground()
    fun onResumed(focus:Boolean)=paymentReturn.resumed(focus)
    fun onPaused()=paymentReturn.paused()
    fun onWindowFocusChanged(focus:Boolean)=paymentReturn.focus(focus)
    fun onBackground()=paymentReturn.background()
    private fun result(phase:RechargePhase,error:String?=null){mutable.value=copy(mutable.value,loading=false,error=error,stage=FeeStage.Result,resultPhase=phase,preparation=null)}
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
    ) = FeeUiState(s.config, loading, error, levels, options, selections, names, query, order,
        method, preparation, stage, resultPhase, s.preview, hiddenLevels, refreshing, selecting)
}
