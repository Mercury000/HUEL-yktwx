package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.AccountRepository
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
private class WenbeiMeterResolution(
    val binding: FeeRoomBinding,
    val options: Map<Int, List<FeeOption>>,
)
class FeeUiState(
    val config: FeeConfiguration? = null, val loading: Boolean = false, val error: String? = null,
    val levels: List<FeeLevel> = emptyList(), val options: Map<Int, List<FeeOption>> = emptyMap(),
    val selections: Map<String, String> = emptyMap(), val names: Map<String, String> = emptyMap(),
    val query: FeeQuery? = null, val order: FeeOrder? = null, val method: FeeMethod? = null,
    val preparation: FeeAccountPreparation? = null, val stage: FeeStage = FeeStage.Query,
    val resultPhase: RechargePhase = RechargePhase.Amount, val preview: Boolean = false,
    val hiddenLevels: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val roomBinding: FeeRoomBinding? = config?.roomBinding,
    val roomBindingKnown: Boolean = config?.invalidRoomBinding != true,
    val roomBusy: Boolean = false, val roomDialog: FeeRoomDialog? = null,
    val roomNotice: String? = null,
    val paymentRetry: FeePaymentRetry? = null,
    val switchingProject: FeeProject? = null,
    val switchingMeterKind: DormMeterKind? = null,
    val errorFields: Set<FormField> = emptySet(),
) {
    val canContinuePayment get() = !preview && !loading && switchingProject == null && switchingMeterKind == null &&
        stage == FeeStage.Result &&
        resultPhase == RechargePhase.Failed && query?.thirdParty != null &&
        paymentRetry?.methods?.isNotEmpty() == true
    val currentRoom get() = FeeRoomBinding.from(levels, selections, names)
    val roomAction get() = if (currentRoom?.sameTarget(roomBinding) == true) FeeRoomAction.Unbind else FeeRoomAction.Save
    val roomActionEnabled get() = config?.roomBindingEnabled == true && roomBindingKnown &&
        stage == FeeStage.Query && !loading && !selecting && !roomBusy &&
        switchingProject == null && switchingMeterKind == null && currentRoom != null &&
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
    private val roomUpdates = Channel<Unit>(Channel.CONFLATED)
    val roomChanges = roomUpdates.receiveAsFlow()
    private val updates = Channel<TransactionUpdate>(Channel.BUFFERED)
    val transactionUpdates = updates.receiveAsFlow()
    private var task: Job? = null
    private var checkDeadline: Job? = null
    private var owner = ""
    private var attempt = 0L
    private var completedChecks = 0
    private var accountPaymentSubmitted = false
    private var fixedHallTarget = false
    private val paymentReturn = PaymentReturnObserver(viewModelScope, { task },
        { !accountPaymentSubmitted && mutable.value.loading && mutable.value.stage == FeeStage.Result &&
            mutable.value.resultPhase == RechargePhase.Checking }, ::checkResult)

    fun open(project: FeeProject, username: String, preview: Boolean, requestedRoom: FeeRoomBinding? = null) {
        dismiss()
        fixedHallTarget = requestedRoom != null
        owner = username
        mutable.value = FeeUiState(loading = true, preview = UiPreview.enabled && preview)
        val generation = attempt
        launchTask {
            try {
                val config = if (UiPreview.enabled && preview) PreviewData.feeConfiguration(project)
                    else repository.feeConfiguration(username, project)
                if (attempt != generation) return@launchTask
                val initial = if (UiPreview.enabled && preview) PreviewData.feeQuery(project, false, 0, emptyMap())
                    else repository.feeQuery(username, project, 0, emptyMap(), false)
                if (attempt != generation) return@launchTask
                if (initial.tip != null) throw YktException(initial.tip)
                if (project.network || initial.levels.isEmpty()) {
                    val detail = if (UiPreview.enabled && preview) PreviewData.feeQuery(project, true, 0, emptyMap())
                        else repository.feeQuery(username, project, 0, emptyMap(), true)
                    if (attempt == generation) {
                        if (detail.tip != null) throw YktException(detail.tip)
                        mutable.value = FeeUiState(config, query = detail, preview = UiPreview.enabled && preview)
                    }
                } else {
                    val first = initial.levels.first()
                    val entrance = FeeUiState(config, levels = initial.levels,
                        options = mapOf(first.number to initial.options), preview = UiPreview.enabled && preview)
                    if (initial.options.isEmpty()) throw YktException("没有可用的校区")
                    val target = requestedRoom
                    if (UiPreview.enabled && preview && requestedRoom != null) {
                        mutable.value = FeeUiState(config, levels = initial.levels,
                            selections = requestedRoom.selections, names = requestedRoom.names,
                            options = initial.levels.associate { it.number to listOf(FeeOption(requestedRoom.selections.getValue(it.key), requestedRoom.names.getValue(it.key))) },
                            query = PreviewData.feeQuery(project, true, initial.levels.last().number, requestedRoom.selections), preview = true)
                    } else if (target != null) {
                        restoreRoom(entrance, target, generation)
                    } else if (initial.options.size == 1 && first.key in setOf("campus", "xiaoqu_id")) {
                        mutable.value = copy(entrance, loading = true)
                        loadSelection(entrance, first, initial.options.single(), generation)
                    } else mutable.value = entrance
                    if (config.invalidRoomBinding) mutable.value = copy(mutable.value,
                        roomBindingKnown = false, error = "已保存的房间信息无法识别，请先在官网解绑或更正")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == generation) mutable.value = FeeUiState(error = message(e), preview = UiPreview.enabled && preview)
            }
        }
    }

    /** Keep the household while resolving the other service's own live option IDs. */
    fun selectFamilyProject(project: FeeProject): Boolean {
        val state = mutable.value
        val family = setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
        if (fixedHallTarget || state.config?.project !in family || project !in family ||
            state.config?.project == project || state.stage != FeeStage.Query || state.loading || state.selecting ||
            state.roomBusy || state.roomDialog != null || state.switchingProject != null ||
            state.switchingMeterKind != null || task?.isActive == true) return false
        val household = state.currentRoom ?: return false
        val generation = attempt
        mutable.value = copy(state, loading = true, error = null, query = null, switchingProject = project)
        launchTask {
            try {
                val config = if (UiPreview.enabled && state.preview) PreviewData.feeConfiguration(project)
                    else repository.feeConfiguration(owner, project)
                if (generation != attempt) return@launchTask
                val initial = feeQueryFor(project, 0, emptyMap(), false, state.preview)
                if (initial.tip != null) throw YktException(initial.tip)
                val levels = initial.levels.sortedBy { it.number }
                if (levels.isEmpty() || levels.map { it.key }.toSet() != household.selections.keys) {
                    throw YktException("未找到此房间对应的水表或电表，请重新选择房间")
                }
                var options = initial.options
                val optionsByNumber = linkedMapOf<Int, List<FeeOption>>()
                val selections = linkedMapOf<String, String>()
                val names = linkedMapOf<String, String>()
                var detail: FeeQuery? = null
                for ((index, level) in levels.withIndex()) {
                    optionsByNumber[level.number] = options
                    val sourceName = household.names[level.key]
                        ?: throw YktException("未找到此房间对应的水表或电表，请重新选择房间")
                    val option = options.singleOrNull {
                        physicalLabel(it.name) == physicalLabel(sourceName)
                    } ?: throw YktException("未找到此房间对应的水表或电表，请重新选择房间")
                    selections[level.key] = option.id
                    names[level.key] = option.name
                    if (index == levels.lastIndex) {
                        detail = feeQueryFor(project, level.number, selections.toMap(), true, state.preview)
                        break
                    }
                    val next = feeQueryFor(project, level.number, selections.toMap(), false, state.preview)
                    if (next.tip != null) throw YktException(next.tip)
                    options = next.options
                }
                val resolved = detail ?: throw YktException("未找到此房间对应的水表或电表，请重新选择房间")
                if (resolved.tip != null) throw YktException(resolved.tip)
                if (generation != attempt) return@launchTask
                mutable.value = copy(mutable.value, config = config, levels = levels, options = optionsByNumber,
                    selections = selections, names = names, query = resolved, loading = false, selecting = false,
                    error = null, hiddenLevels = state.hiddenLevels.filterTo(mutableSetOf()) {
                        it in levels.map { level -> level.key }.toSet()
                    },
                    roomBinding = config.roomBinding, roomBindingKnown = !config.invalidRoomBinding,
                    switchingProject = null, switchingMeterKind = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false, selecting = false,
                    query = state.query, error = message(e), switchingProject = null, switchingMeterKind = null)
            }
        }
        return true
    }

    /** Resolve the sibling meter without making the user walk the other branch's pickers again. */
    fun selectDormMeterKind(kind: DormMeterKind) {
        val state = mutable.value
        val project = state.config?.project ?: return
        if (fixedHallTarget || project !in setOf(FeeProject.Zhengdong, FeeProject.Wenbei) ||
            state.stage != FeeStage.Query || state.loading || state.selecting || state.roomBusy ||
            state.roomDialog != null || state.switchingProject != null || state.switchingMeterKind != null ||
            task?.isActive == true || state.currentRoom == null) return
        val choices = dormMeterChoices(state.levels.filterNot { it.key in state.hiddenLevels }, state.options) ?: return
        val selected = choices.selectedChoice(state.selections[choices.level.key]) ?: return
        val selectedOption = selected.options.firstOrNull { it.id == state.selections[choices.level.key] } ?: return
        if (DormMeterDiscovery.kind(selectedOption.name) == kind) return
        val target = selected.option(kind) ?: return
        if (project == FeeProject.Zhengdong) {
            select(choices.level, target)
            return
        }
        val generation = attempt
        mutable.value = copy(state, selecting = true, error = null, query = null, switchingMeterKind = kind)
        launchTask {
            try {
                val resolved = resolveWenbeiMeter(state, choices, target, generation)
                if (generation != attempt) return@launchTask
                val detail = feeQueryFor(project, state.levels.last().number,
                    resolved.binding.selections, true, state.preview)
                if (detail.tip != null) throw YktException(detail.tip)
                mutable.value = copy(mutable.value, options = resolved.options,
                    selections = resolved.binding.selections, names = resolved.binding.names,
                    query = detail, selecting = false, error = null, switchingProject = null,
                    switchingMeterKind = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, query = state.query,
                    selecting = false, switchingProject = null, switchingMeterKind = null, error = message(e))
            }
        }
    }

    fun select(level: FeeLevel, option: FeeOption) {
        val state = mutable.value
        if (state.config == null || state.loading || state.selecting || state.roomBusy || state.roomDialog != null || state.stage != FeeStage.Query ||
            state.switchingProject != null || state.switchingMeterKind != null ||
            state.options[level.number]?.none { it.id == option.id } != false) return
        val generation = attempt
        mutable.value = copy(state, selecting = true, error = null)
        launchTask {
            try { loadSelection(state, level, option, generation) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == attempt) mutable.value = copy(mutable.value, selecting = false, error = message(e))
            }
        }
    }

    private fun selectedState(state: FeeUiState, level: FeeLevel, option: FeeOption): FeeUiState {
        val keep = state.levels.filter { it.number < level.number }.map { it.key }.toSet()
        val selections = state.selections.filterKeys { it in keep } + (level.key to option.id)
        val names = state.names.filterKeys { it in keep } + (level.key to option.name)
        val hidden = if (level == state.levels.firstOrNull() && state.options[level.number]?.size == 1 &&
            level.key in setOf("campus", "xiaoqu_id")) state.hiddenLevels + level.key else state.hiddenLevels
        return copy(state, selections = selections, names = names,
            options = state.options.filterKeys { it <= level.number }, query = null, hiddenLevels = hidden)
    }

    private suspend fun resolveSelection(state: FeeUiState, level: FeeLevel, generation: Long,
        continueLoading: Boolean = false): FeeUiState? {
        val config = state.config ?: return null
        val next = state.levels.firstOrNull { it.number > level.number }
        val query = if (UiPreview.enabled && state.preview) PreviewData.feeQuery(config.project, next == null, level.number, mutable.value.selections)
            else repository.feeQuery(owner, config.project, level.number, state.selections, next == null)
        if (generation != attempt) return null
        return copy(state, loading = continueLoading, selecting = false, error = query.tip,
            options = if (next == null) state.options else state.options + (next.number to query.options),
            query = if (next == null) query else null)
    }

    private suspend fun loadSelection(state: FeeUiState, level: FeeLevel, option: FeeOption, generation: Long) {
        if (state.config == null) return
        val selected = selectedState(state, level, option)
        // Explicit selection still clears downstream fields as soon as the user changes a field.
        mutable.value = copy(mutable.value, selections = selected.selections, names = selected.names,
            options = selected.options, query = null, hiddenLevels = selected.hiddenLevels)
        resolveSelection(mutable.value, level, generation)?.let { mutable.value = it }
    }

    private suspend fun restoreRoom(entrance: FeeUiState, binding: FeeRoomBinding, generation: Long) {
        val unavailable = "已保存的房间选项已失效，请重新选择后保存"
        var current = copy(entrance, loading = true)
        mutable.value = current
        if (binding.selections.keys != entrance.levels.map { it.key }.toSet()) {
            mutable.value = copy(entrance, error = unavailable)
            return
        }
        try {
            // A fixed hall target only needs the final verified room. Keep intermediate traversal
            // local so every level does not rebuild the amount sheet during its entrance animation.
            for ((index, level) in entrance.levels.withIndex()) {
                val options = current.options[level.number].orEmpty()
                val option = options.firstOrNull { it.id == binding.selections[level.key] }
                if (option == null) {
                    mutable.value = copy(current, loading = false, error = unavailable)
                    return
                }
                current = selectedState(current, level, option)
                current = resolveSelection(current, level, generation,
                    continueLoading = index != entrance.levels.lastIndex) ?: return
                if (current.error != null) {
                    mutable.value = copy(current, loading = false)
                    return
                }
            }
            mutable.value = current
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (attempt == generation) mutable.value = copy(current, loading = false, error = message(e))
        }
    }

    private suspend fun resolveWenbeiMeter(
        state: FeeUiState,
        choices: DormMeterChoices,
        target: FeeOption,
        generation: Long,
    ): WenbeiMeterResolution {
        val project = state.config?.project ?: throw YktException("缴费项目已失效")
        val source = state.currentRoom ?: throw YktException("请先选择完整房间")
        val levels = state.levels.sortedBy { it.number }
        val buildingIndex = levels.indexOf(choices.level)
        val roomIndex = levels.lastIndex
        val floorIndex = roomIndex - 1
        if (buildingIndex < 0 || floorIndex <= buildingIndex) throw YktException("未找到对应的空调或照明房间")
        val floor = levels[floorIndex]
        val room = levels[roomIndex]
        val targetKind = DormMeterDiscovery.kind(target.name)
        val optionsByNumber = linkedMapOf<Int, List<FeeOption>>()
        val selections = linkedMapOf<String, String>()
        val names = linkedMapOf<String, String>()
        for (index in 0 until buildingIndex) {
            val level = levels[index]
            val id = source.selections[level.key] ?: throw YktException("未找到对应的空调或照明房间")
            selections[level.key] = id
            names[level.key] = source.names[level.key] ?: throw YktException("未找到对应的空调或照明房间")
            optionsByNumber[level.number] = state.options[level.number].orEmpty()
        }
        selections[choices.level.key] = target.id
        names[choices.level.key] = target.name
        optionsByNumber[choices.level.number] = state.options[choices.level.number].orEmpty()

        val floorQuery = feeQueryFor(project, choices.level.number, selections.toMap(), false, state.preview)
        if (floorQuery.tip != null) throw YktException(floorQuery.tip)
        optionsByNumber[floor.number] = floorQuery.options
        val wantedRoom = wenbeiRoomKey(source.names[room.key]
            ?: throw YktException("未找到对应的空调或照明房间"))
        val candidates = mutableListOf<Triple<FeeOption, FeeOption, List<FeeOption>>>()
        for (floorOption in floorQuery.options) {
            if (generation != attempt) throw CancellationException()
            val querySelections = selections.toMutableMap().apply { this[floor.key] = floorOption.id }
            val roomQuery = feeQueryFor(project, floor.number, querySelections, false, state.preview)
            if (roomQuery.tip != null) throw YktException(roomQuery.tip)
            val matches = roomQuery.options.filter {
                DormMeterDiscovery.kind(it.name) == targetKind && wenbeiRoomKey(it.name) == wantedRoom
            }
            if (matches.size > 1) throw YktException("该房间对应的空调或照明不唯一，请重新选择房间")
            matches.singleOrNull()?.let { candidates += Triple(floorOption, it, roomQuery.options) }
        }
        val resolved = candidates.singleOrNull()
            ?: throw YktException("未找到此房间对应的空调或照明，请重新选择房间")
        selections[floor.key] = resolved.first.id
        names[floor.key] = resolved.first.name
        selections[room.key] = resolved.second.id
        names[room.key] = resolved.second.name
        optionsByNumber[room.number] = resolved.third
        val binding = FeeRoomBinding.from(levels, selections, names)
            ?: throw YktException("房间信息异常，请重新选择")
        return WenbeiMeterResolution(binding, optionsByNumber)
    }

    private fun wenbeiRoomKey(name: String): String {
        val groups = Regex("\\d+").findAll(physicalLabel(name)).map { match ->
            match.value.toIntOrNull()?.toString() ?: match.value
        }.toList()
        return groups.lastOrNull() ?: physicalLabel(name)
    }

    private suspend fun feeQueryFor(project: FeeProject, level: Int, selections: Map<String, String>,
        detail: Boolean, preview: Boolean): FeeQuery = if (UiPreview.enabled && preview) {
        PreviewData.feeQuery(project, detail, level, selections)
    } else {
        repository.feeQuery(owner, project, level, selections, detail)
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
            state.switchingProject != null || state.switchingMeterKind != null ||
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
                val fresh = if (UiPreview.enabled && state.preview) target else repository.feeConfiguration(owner, config.project).let {
                    if (!it.roomBindingEnabled || it.invalidRoomBinding) throw YktException("房间绑定状态无法确认")
                    it.roomBinding
                }
                if (generation != attempt) return@launchTask
                val confirmed = if (target == null) fresh == null else target.sameTarget(fresh)
                roomUpdates.trySend(Unit)
                mutable.value = copy(mutable.value, roomBusy = false, roomBinding = fresh, roomBindingKnown = true,
                    error = if (confirmed) null else failure?.let(::message) ?: "房间绑定未更新，请稍后重试",
                    roomNotice = if (confirmed) (if (target == null) "已解绑" else "已保存为我的房间") else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                roomUpdates.trySend(Unit)
                if (generation == attempt) mutable.value = copy(mutable.value, roomBusy = false, roomBindingKnown = false,
                    error = "无法确认保存或解绑结果，请重新打开页面核实，勿重复提交")
            }
        }
    }

    /** Dismissing an unpaid UI ends only this attempt; it does not pop the fee page. */
    fun cancelPayment() {
        if (mutable.value.stage !in setOf(FeeStage.Methods, FeeStage.Password, FeeStage.Result)) return
        endPaymentAttempt()
        mutable.value = copy(mutable.value, loading = false, error = null,
            order = null, method = null, preparation = null, paymentRetry = null, stage = FeeStage.Query,
            resultPhase = RechargePhase.Amount)
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
            !repository.ownsAccount(owner) || retry.methods.none { it.id == method.id && it.code == method.code }) return
        if (config.amountRules.amountError(retry.amountCents) != null) return
        endPaymentAttempt()
        val generation = attempt
        val marker = PendingRecharge(owner, query.thirdParty ?: return, config.project.id,
            retry.amountCents)
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
                if (generation == attempt) requestError(message(e))
                return@launchTask
            }
            if (generation != attempt) return@launchTask
            // Keep the failed channel excluded even if the new order's method list has changed.
            val alternatives = order.methods.filter { current -> retry.methods.any { it.code == current.code } }
            // The previous list is presentation-only. Only the new order authorizes a method.
            val currentMethod = alternatives.firstOrNull { it.id == method.id && it.code == method.code }
            if (currentMethod == null) {
                mutable.value = copy(mutable.value, loading = false,
                    order = FeeOrder(order.pending, alternatives), stage = FeeStage.Methods)
                if (alternatives.isEmpty()) result(RechargePhase.Failed, "其他支付方式已不可用，请重新发起缴费")
                else mutable.value = copy(mutable.value, error = "支付方式已变化，请重新选择")
                return@launchTask
            }
            prepareMethod(copy(mutable.value, order = order), currentMethod, generation)
        }
    }

    fun inputEdited(field: FormField) {
        val s = mutable.value
        if (!s.loading && !s.selecting && !s.roomBusy &&
            s.stage in setOf(FeeStage.Query, FeeStage.Password) && field in s.errorFields)
            mutable.value = copy(s, error = null)
    }
    fun createWithMethod(amount: Long, methodCode: String) {
        if (methodCode !in setOf("ACCOUNTTSM", "TSM")) return
        val s = mutable.value; val config = s.config ?: return; val q = s.query ?: return
        if (s.preview || s.loading || s.roomBusy || s.roomDialog != null || s.selecting ||
            s.switchingProject != null || s.switchingMeterKind != null ||
            !q.canPay || s.stage != FeeStage.Query || task?.isActive == true) return
        config.amountRules.amountError(amount)?.let { mutable.value=copy(s,error=it,errorFields=setOf(FormField.Amount)); return }
        val generation=attempt
        accountPaymentSubmitted = false
        val marker = PendingRecharge(owner, q.thirdParty ?: return, config.project.id, amount)
        mutable.value=copy(s,loading=true,error=null,order=FeeOrder(marker, emptyList()),paymentRetry=null,
            resultPhase=RechargePhase.Creating)
        launchTask {
            try {
                val order=repository.createFee(marker.username,config,q,amount)
                if (generation==attempt) {
                    val selected = order.methods.singleOrNull { it.code == methodCode }
                    // Keep the amount form loading until the chosen method is ready.
                    // The method picker is only needed when the server requires a new choice.
                    if (selected != null) prepareMethod(copy(mutable.value, order = order), selected, generation)
                    else mutable.value = copy(mutable.value, loading = false, order = order,
                        stage = FeeStage.Methods, error = "所选支付方式不可用，请重新选择")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: FeePaymentRejected) {
                if (generation == attempt) result(RechargePhase.Failed, e.message)
            }
            catch (e: Exception) { if(generation==attempt) requestError(message(e)) }
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
                val launch = repository.launchFeeWechat(pending, method)
                if (generation != attempt) return
                mutable.value = copy(mutable.value, loading = false, stage = FeeStage.Result,
                    resultPhase = RechargePhase.AwaitingPayment)
                if (paymentReturn.canLaunch) launches.send(launch) else requestError("未能打开微信")
            } else {
                val preparation = repository.prepareFeeAccount(pending, method)
                if (generation == attempt) mutable.value = copy(mutable.value, loading = false,
                    stage = FeeStage.Password, preparation = preparation)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: FeeOrderExpired) { if (generation == attempt) result(RechargePhase.TimedOut, e.message) }
        catch (e: FeeOrderAlreadyPaid) { if (generation == attempt) { accountPaymentSubmitted = true; startCheck() } }
        catch (e: FeePaymentRejected) {
            if (generation == attempt) result(RechargePhase.Failed, e.message, retryable = true)
        }
        catch (e: Exception) {
            if (generation == attempt) {
                requestError(message(e))
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
            catch (e: FeeOrderExpired) { if (generation == attempt) result(RechargePhase.TimedOut, e.message) }
            catch (e: FeeOrderAlreadyPaid) {
                if (generation == attempt) { accountPaymentSubmitted = true; startCheck() }
            }
            catch (e: FeePaymentNotStarted) {
                if (generation == attempt) result(RechargePhase.Failed, e.message, retryable = true)
            }
            catch (e: FeePaymentRejected) {
                if (generation == attempt) {
                    if (e.reason == FeeRejectionReason.Password) {
                        // Correct the password within this attempt. The dialog already cleared it;
                        // the next explicit submit still checks the order and server expiry.
                        mutable.value = copy(s, loading = false, error = e.message, errorFields = setOf(FormField.Password, FormField.PaymentAccount))
                    } else result(RechargePhase.Failed, e.message, retryable = true)
                }
            }
            catch (e: Exception) {
                if (generation == attempt) requestError(message(e))
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
        if(pending.orderId == null || task?.isActive==true)return
        if(mutable.value.resultPhase!=RechargePhase.Checking || !mutable.value.loading)completedChecks=0
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
                        response.serverStatus == 2 -> result(RechargePhase.TimedOut, response.message)
                        response.serverStatus == 0 -> result(RechargePhase.Failed, response.message)
                        else -> queryError(response.message ?: "订单状态异常")
                    }
                }catch(e:CancellationException){throw e}
                catch(e:Exception){
                    if(generation!=attempt)return@launchTask
                    queryError(message(e))
                    return@launchTask
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
            if (generation == attempt && mutable.value.loading && mutable.value.resultPhase == RechargePhase.Checking) {
                // This independent UI deadline also covers a suspended query, shared lock or lost focus.
                val query = task
                queryError("查询超时")
                query?.cancel()
            }
        }
    }
    fun canLaunchWechat()=paymentReturn.canLaunch && repository.ownsAccount(owner) && mutable.value.resultPhase==RechargePhase.AwaitingPayment
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
        if (phase == RechargePhase.Paid && s.resultPhase != RechargePhase.Paid && !s.preview && repository.ownsAccount(owner))
            updates.trySend(TransactionUpdate(owner, balance = s.method?.code == "ACCOUNTTSM", hall = true, project = s.config?.project, income = false))
    }
    private fun requestError(message: String) {
        checkDeadline?.cancel()
        checkDeadline = null
        paymentReturn.reset()
        mutable.value = copy(mutable.value, loading = false, error = message,
            stage = FeeStage.Query, resultPhase = RechargePhase.Amount,
            order = null, method = null, preparation = null, paymentRetry = null)
    }
    private fun queryError(message: String) {
        checkDeadline?.cancel()
        checkDeadline = null
        paymentReturn.reset()
        mutable.value = copy(mutable.value, loading = false, error = message,
            stage = FeeStage.Result, resultPhase = RechargePhase.Checking, preparation = null)
    }
    private fun message(e:Exception)=if(e is YktException)e.message ?: "服务暂不可用" else "服务数据异常，请稍后重试"
    private fun copy(
        s: FeeUiState,
        config: FeeConfiguration? = s.config,
        loading: Boolean = s.loading,
        error: String? = s.error,
        errorFields: Set<FormField> = if (error != null && error == s.error) s.errorFields else emptySet(),
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
        selecting: Boolean = s.selecting,
        roomBinding: FeeRoomBinding? = s.roomBinding,
        roomBindingKnown: Boolean = s.roomBindingKnown,
        roomBusy: Boolean = s.roomBusy,
        roomDialog: FeeRoomDialog? = s.roomDialog,
        roomNotice: String? = s.roomNotice,
        paymentRetry: FeePaymentRetry? = s.paymentRetry,
        switchingProject: FeeProject? = s.switchingProject,
        switchingMeterKind: DormMeterKind? = s.switchingMeterKind,
    ) = FeeUiState(config, loading, error, levels, options, selections, names, query, order,
        method, preparation, stage, resultPhase, s.preview, hiddenLevels, selecting,
        roomBinding, roomBindingKnown, roomBusy, roomDialog, roomNotice, paymentRetry,
        switchingProject, switchingMeterKind, errorFields)
}
