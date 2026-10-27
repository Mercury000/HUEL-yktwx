package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.FeeRoomAction
import com.mercury.yktwx.FeeUiState
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class HallFeePage { Target, Amount, Result }

private data class HallFeeResultDisplay(
    val phase: RechargePhase, val loading: Boolean, val amountCents: Long,
    val detail: String?, val methods: List<FeeMethod>,
)

/** One root-hosted sheet for target selection, amount, server payment channels and results. */
@Composable
internal fun HallFeeDialog(state: FeeUiState, visible: Boolean, project: FeeProject?, fixedTarget: FeeRoomBinding?,
    saveOnly: Boolean = false, onDismiss: () -> Unit, onSelect: (FeeLevel, FeeOption) -> Unit,
    onRetry: () -> Unit, onCreate: (Long, String) -> Unit, onMethod: (FeeMethod) -> Unit,
    onPayAccount: (String, String) -> Unit, onCancelPayment: () -> Unit, onFinish: () -> Unit,
    onContinuePayment: (FeeMethod) -> Unit, onRoomAction: () -> Unit,
    onConfirmRoomAction: () -> Unit, onDismissRoomDialog: () -> Unit, onConsumeRoomNotice: () -> Unit,
    onProject: (FeeProject) -> Unit = {}, onMeterKind: (DormMeterKind) -> Unit = {},
    bindingProjects: Set<FeeProject> = emptySet(),
) {
    val session = rememberOverlaySession(visible)
    var amount by remember(state.config, visible) { mutableStateOf("") }
    var amountStep by remember(visible, project, fixedTarget, saveOnly) {
        mutableStateOf(!saveOnly && (fixedTarget != null || project?.network == true))
    }
    var saveCommon by remember(visible, project, state.selections) { mutableStateOf(false) }
    var bindingPending by remember(visible, project) { mutableStateOf(false) }
    var targetSelected by remember(visible, project, state.selections) { mutableStateOf(true) }
    var methodCode by remember(visible, project) { mutableStateOf("ACCOUNTTSM") }
    LaunchedEffect(state.roomNotice) {
        if (bindingPending && state.roomNotice != null) {
            bindingPending = false; saveCommon = false
            onConsumeRoomNotice()
            if (saveOnly) onDismiss() else amountStep = true
        }
    }
    LaunchedEffect(state.roomBusy, state.error) {
        if (bindingPending && !state.roomBusy && state.error != null) bindingPending = false
    }
    val cents = RechargeMoney.cents(amount)
    val config = state.config
    val displayProject = state.switchingProject ?: config?.project ?: project
    val ready = !state.loading && !state.selecting && state.query?.canPay == true &&
        state.query.thirdParty != null && state.query.tip == null &&
        state.switchingProject == null && state.switchingMeterKind == null &&
        config?.project == displayProject &&
        (fixedTarget == null || fixedTarget.sameTarget(state.currentRoom))
    val orderAmount = state.order?.pending?.amountCents ?: state.paymentRetry?.amountCents ?: cents ?: 0
    val meterName = fixedTarget?.let { target ->
        when (project) {
            FeeProject.FamilyElectric -> "电费"
            FeeProject.FamilyWater -> "水费"
            else -> target.names.values.lastOrNull()?.let {
                DormMeterDiscovery.kind(it).takeUnless { kind -> kind == DormMeterKind.Other }?.label
            }
        }
    }
    val paymentTitle = if (project?.network == true) "校园网充值" else meterName?.plus("充值") ?: project?.hallTitle ?: "水电缴费"
    val requestedPage = when {
        saveOnly -> HallFeePage.Target
        state.stage == FeeStage.Password -> null
        state.stage == FeeStage.Result || state.stage == FeeStage.Methods -> HallFeePage.Result
        amountStep || project?.network == true -> HallFeePage.Amount
        else -> HallFeePage.Target
    }
    var page by remember(session) { mutableStateOf(requestedPage ?: HallFeePage.Amount) }
    if (visible && requestedPage != null) page = requestedPage
    val title = rememberWhileShown(visible, when {
        saveOnly -> "新增绑定"
        page == HallFeePage.Result -> null
        else -> paymentTitle
    })
    OverlayBottomSheet(show = visible && state.stage != FeeStage.Password,
        startAction = { key(session) { SheetTitle(title) } }, onDismissRequest = {
        if (state.stage == FeeStage.Result) onFinish() else onDismiss()
    }, renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        key(session) { SheetPageContent(page, Modifier.testTag("hall-payment-content").bottomSheetSafeArea()) { shownPage, interactive ->
        val active = interactive && visible && state.stage != FeeStage.Password
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (shownPage) {
                HallFeePage.Target -> {
                    if (config == null) {
                        if (state.loading) HallFeeTargetPlaceholder()
                        InlineError(state.error, actionLabel = "重试", onAction = { if (active) onRetry() })
                    } else {
                        val targetEnabled = active && !state.loading && !state.selecting && !state.roomBusy &&
                            state.roomDialog == null && state.switchingProject == null && state.switchingMeterKind == null
                        HallFeeTargetOptions(state, onSelect, onMeterKind, targetEnabled)
                        if (state.currentRoom != null && displayProject in listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
                            val projects = listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
                                .filter { !saveOnly || it in bindingProjects }
                            if (projects.size > 1) FeeMeterTypeSelector("缴费类型",
                                projects.map { if (it == FeeProject.FamilyElectric) "电费" else "水费" },
                                projects.indexOf(displayProject).coerceAtLeast(0), targetEnabled) { index ->
                                if (projects[index] != displayProject) onProject(projects[index])
                            }
                        }
                        InlineError(state.error, actionLabel = "重试", onAction = onRetry)
                        if (!saveOnly && config.roomBindingEnabled && state.roomBindingKnown && state.roomAction == FeeRoomAction.Save) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state = if (saveCommon) ToggleableState.On else ToggleableState.Off,
                                onClick = { saveCommon = !saveCommon }, enabled = active && ready && !state.roomBusy)
                            Text(if (state.roomBinding == null) "绑定此房间" else "替换已绑定房间", Modifier.padding(start = 8.dp), fontSize = 14.sp)
                        }
                        Button(onClick = {
                            if (active && ready) {
                                if ((saveOnly || saveCommon) && config.roomBindingEnabled && state.roomAction == FeeRoomAction.Save) {
                                    bindingPending = true; onRoomAction()
                                } else if (saveOnly) onDismiss() else amountStep = true
                            }
                        }, enabled = active && ready && state.currentRoom != null && !state.roomBusy &&
                            (!saveOnly || config.roomBindingEnabled && state.roomBindingKnown), modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.roomBusy) "正在保存…" else if (saveOnly) "绑定房间" else "下一步") }
                        if (saveOnly && !config.roomBindingEnabled) Text("此项目不支持绑定", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                HallFeePage.Amount -> {
                        HallFeeSummary(state, Modifier.testTag("hall-payment-target"), targetSelected,
                            { targetSelected = !targetSelected }, active && !state.loading && !state.selecting,
                            project, fixedTarget)
                        val codes = if (project?.network == true) listOf("ACCOUNTTSM") else listOf("ACCOUNTTSM", "TSM")
                        if (state.loading && (config == null || state.query == null)) PaymentAmountPlaceholder(config = config?.amountRules,
                            methods = codes.map { if (it == "TSM") "微信" else "一卡通" })
                        else if (config != null) PaymentAmountForm(config.amountRules, amount, { amount = it },
                            codes.map { if (it == "TSM") "微信" else "一卡通" }, codes.indexOf(methodCode).coerceAtLeast(0),
                            { methodCode = codes[it] }, state.loading || state.selecting || state.roomBusy,
                            active && ready && targetSelected && !state.preview, state.error, { onCreate(it, methodCode) }, onRetry,
                            enabled = active)
                        else InlineError(state.error, actionLabel = "重试", onAction = { if (active) onRetry() })
                }
                HallFeePage.Result -> {
                    // Unavailable methods reuse the same choices without creating another order.
                    val choosingMethod = state.stage == FeeStage.Methods
                    val resultActive = active && (choosingMethod || state.stage == FeeStage.Result)
                    // Dismissal clears the live state before the exit animation ends.
                    // Retain only display values inside this page, never the order or password data.
                    var display by remember { mutableStateOf<HallFeeResultDisplay?>(null) }
                    if (resultActive) display = HallFeeResultDisplay(
                        phase = if (choosingMethod) {
                            if (state.loading) RechargePhase.Creating else RechargePhase.Failed
                        } else state.resultPhase,
                        loading = state.loading, amountCents = orderAmount, detail = state.error,
                        methods = if (choosingMethod) state.order?.methods.orEmpty()
                            else if (state.canContinuePayment) state.paymentRetry!!.methods else emptyList(),
                    )
                    display?.let { result ->
                        PaymentResultContent(result.phase, result.loading, result.amountCents, result.detail,
                            { if (resultActive) { if (choosingMethod) onCancelPayment() else onFinish() } },
                            subject = "充值", retryMethods = result.methods,
                            onContinuePayment = { if (resultActive) { if (choosingMethod) onMethod(it) else onContinuePayment(it) } })
                    }
                }
            }
        }
        } }
    }
    FeePasswordDialog(show = visible && state.stage == FeeStage.Password,
        amountCents = orderAmount, preparation = state.preparation, loading = state.loading, error = state.error,
        onDismiss = onCancelPayment, onPay = onPayAccount)
    val dialog = state.roomDialog
    val confirmingRoom = visible && dialog != null
    val replacing = rememberWhileShown(confirmingRoom, dialog?.previous != null)
    val roomSummary = rememberWhileShown(confirmingRoom,
        dialog?.let { if (it.previous != null) "将「${it.previous.dialogLabel}」替换为「${it.target.dialogLabel}」" else it.target.dialogLabel })
    OverlayDialog(show = confirmingRoom, title = if (replacing) "替换已绑定房间" else "绑定房间",
        summary = roomSummary,
        onDismissRequest = { bindingPending = false; onDismissRoomDialog() }, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { bindingPending = false; onDismissRoomDialog() }, enabled = confirmingRoom, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onConfirmRoomAction, enabled = confirmingRoom && !state.roomBusy,
                modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (replacing) "替换" else "绑定")
            }
        }
    }
}

@Composable
private fun HallFeeTargetPlaceholder() {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在读取房间选项" },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(3) {
            PaymentControlPlaceholder { FeeLocationDropdown("", emptyList(), 0, false) {} }
        }
        PaymentControlPlaceholder {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("选择房间") }
        }
    }
}

@Composable
private fun HallFeeSummary(state: FeeUiState, modifier: Modifier = Modifier, selected: Boolean = true,
    onSelect: () -> Unit = {}, enabled: Boolean = false,
    project: FeeProject? = state.config?.project, fixedTarget: FeeRoomBinding? = null) {
    val pending = state.query == null && (state.loading || state.selecting)
    val room = state.currentRoom ?: fixedTarget
    PaymentSelectionCard(selected = selected, onClick = onSelect, modifier = modifier, enabled = enabled) { contentColor ->
            val placeholderColor = contentColor.copy(alpha = .2f)
            if (project?.network == true) {
                if (pending) repeat(2) {
                    Row(Modifier.fillMaxWidth()) {
                        DashboardPlaceholder(13.sp, Modifier.weight(1f), fraction = .45f, color = placeholderColor)
                        DashboardPlaceholder(14.sp, Modifier.width(112.dp), fraction = 1f, color = placeholderColor)
                    }
                }
                state.query?.display?.filter { "套餐" in it.first || "状态" in it.first }?.forEach { (name, value) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(name, Modifier.weight(1f), fontSize = 13.sp, color = contentColor.copy(alpha = .75f))
                        Text(value, fontSize = 14.sp, color = contentColor)
                    }
                }
            } else {
                room?.let {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(it.dialogLabel, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
                        if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
                    }
                }
                if (pending) {
                    if (room == null) DashboardPlaceholder(17.sp, Modifier.fillMaxWidth(), color = placeholderColor)
                    DashboardPlaceholder(13.sp, Modifier.fillMaxWidth(), fraction = .65f, color = placeholderColor)
                }
                state.query?.display.orEmpty().forEach { (label, value) ->
                    Text(if (value.startsWith(label)) value else "$label  $value", fontSize = 13.sp,
                        color = contentColor.copy(alpha = .8f))
                }
            }
        }
    }

/** One physical location picker, with server-provided meter variants below it. */
@Composable
internal fun HallFeeTargetOptions(state: FeeUiState, onSelect: (FeeLevel, FeeOption) -> Unit,
    onMeterKind: (DormMeterKind) -> Unit, enabled: Boolean) {
    val levels = state.levels.filterNot { it.key in state.hiddenLevels }
    val project = state.switchingProject ?: state.config?.project
    val meterChoices = if (project in setOf(FeeProject.Zhengdong, FeeProject.Wenbei))
        dormMeterChoices(levels, state.options) else null

    @Composable fun field(level: FeeLevel) {
        val available = state.options[level.number].orEmpty()
        val selected = available.indexOfFirst { it.id == state.selections[level.key] } + 1
        val labels = if (project == FeeProject.Wenbei && meterChoices != null &&
            level.number > meterChoices.level.number) available.map { physicalLabel(it.name) }
            else available.map { it.name }
        FeeLocationDropdown(level.name, labels, selected, enabled) { index ->
            available.getOrNull(index)?.let { onSelect(level, it) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        levels.forEach { level ->
            if (meterChoices?.level == level) {
                val choices = meterChoices.choices
                val selectedChoice = meterChoices.selectedChoice(state.selections[level.key])
                FeeLocationDropdown(level.name, choices.map { it.label },
                    selectedChoice?.let { choices.indexOf(it) + 1 } ?: 0, enabled) { index ->
                    choices.getOrNull(index)?.let { choice ->
                        val preferred = choice.option(DormMeterKind.Lighting) ?: choice.options.firstOrNull()
                        preferred?.let { onSelect(level, it) }
                    }
                }
            } else {
                field(level)
            }
        }
        if (state.currentRoom != null) {
            val selectedChoice = meterChoices?.let { choices ->
                choices.selectedChoice(state.selections[choices.level.key])
            }
            val siblings = selectedChoice?.options.orEmpty()
            if (siblings.size > 1) {
                val types = siblings.map { DormMeterDiscovery.kind(it.name) }
                val selectedId = meterChoices?.let { it.level.key }?.let(state.selections::get)
                val selectedKind = state.switchingMeterKind ?: DormMeterDiscovery.kind(
                    selectedChoice?.options?.firstOrNull { it.id == selectedId }?.name.orEmpty())
                FeeMeterTypeSelector("电表", types.map { it.label },
                    types.indexOf(selectedKind).coerceAtLeast(0), enabled) { index ->
                        siblings.getOrNull(index)?.let { target ->
                            val meterLevel = meterChoices?.level
                            if (meterLevel != null && meterLevel == state.levels.lastOrNull()) {
                                onSelect(meterLevel, target)
                            } else {
                                onMeterKind(DormMeterDiscovery.kind(target.name))
                            }
                        }
                    }
            }
        }
    }
}

/** Meter choices appear below the room, only while choosing a payment target. */
@Composable
private fun FeeMeterTypeSelector(label: String, types: List<String>, selected: Int,
    enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("fee-meter-type"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        HallNeutralTabs(types, selected) { if (enabled) onSelect(it) }
    }
}
