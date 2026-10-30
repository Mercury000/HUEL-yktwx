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

/** Presentation only: do not retain query payloads, orders or payment preparations. */
private class HallFeeFormDisplay(state: FeeUiState, project: FeeProject?, fixedTarget: FeeRoomBinding?) {
    val config = state.config
    val project = state.switchingProject ?: config?.project ?: project
    val loading = state.loading
    val selecting = state.selecting
    val roomBusy = state.roomBusy
    val error = state.error
    val levels = state.levels
    val hiddenLevels = state.hiddenLevels
    val options = state.options
    val selections = state.selections
    val currentRoom = state.currentRoom
    val summaryRoom = currentRoom ?: fixedTarget
    val roomBinding = state.roomBinding
    val roomBindingKnown = state.roomBindingKnown
    val roomAction = state.roomAction
    val switchingMeterKind = state.switchingMeterKind
    val queryPending = state.query == null && (loading || selecting)
    val queryDisplay = state.query?.display.orEmpty()
    val hasQueryDisplay = state.query != null && state.query.tip == null
}

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
    onCheck: (() -> Unit)? = null,
) {
    val session = rememberOverlaySession(visible)
    val form = rememberWhileShown(visible && state.stage == FeeStage.Query,
        remember(state, project, fixedTarget) { HallFeeFormDisplay(state, project, fixedTarget) })
    var amount by remember(session, form.config) { mutableStateOf("") }
    var amountStep by remember(session, project, fixedTarget, saveOnly) {
        mutableStateOf(!saveOnly && (fixedTarget != null || project?.network == true))
    }
    var saveCommon by remember(session, form.project, form.selections) { mutableStateOf(false) }
    var bindingPending by remember(session, form.project) { mutableStateOf(false) }
    var targetSelected by remember(session, form.project, form.selections) { mutableStateOf(true) }
    var methodCode by remember(session, form.project) { mutableStateOf("ACCOUNTTSM") }
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
    val config = form.config
    val displayProject = form.project
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
                    ReadContent(config == null && form.loading, Modifier.fillMaxWidth(), pending = { it }) { reading ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (reading) {
                                HallFeeTargetPlaceholder(showRoomBinding = !saveOnly && displayProject in bindingProjects)
                            } else if (config == null) {
                                if (!form.error.isNullOrBlank()) InlineError(form.error)
                            } else {
                                val targetEnabled = active && !state.loading && !state.selecting && !state.roomBusy &&
                                    state.roomDialog == null && state.switchingProject == null && state.switchingMeterKind == null
                                HallFeeTargetOptions(form, onSelect, onMeterKind, targetEnabled)
                                if (form.currentRoom != null && displayProject in listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
                                    val projects = listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
                                        .filter { !saveOnly || it in bindingProjects }
                                    if (projects.size > 1) FeeMeterTypeSelector("缴费类型",
                                        projects.map { if (it == FeeProject.FamilyElectric) "电费" else "水费" },
                                        projects.indexOf(displayProject).coerceAtLeast(0), targetEnabled) { index ->
                                        if (projects[index] != displayProject) onProject(projects[index])
                                    }
                                }
                                if (!form.error.isNullOrBlank()) InlineError(form.error)
                                if (!saveOnly && config.roomBindingEnabled && form.roomBindingKnown && form.roomAction == FeeRoomAction.Save) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(state = if (saveCommon) ToggleableState.On else ToggleableState.Off,
                                        onClick = { saveCommon = !saveCommon }, enabled = targetEnabled && ready)
                                    Text(if (form.roomBinding == null) "绑定此房间" else "替换已绑定房间", Modifier.padding(start = 8.dp), fontSize = 14.sp)
                                }
                                Button(onClick = {
                                    if (targetEnabled && ready) {
                                        if ((saveOnly || saveCommon) && config.roomBindingEnabled && state.roomAction == FeeRoomAction.Save) {
                                            bindingPending = true; onRoomAction()
                                        } else if (saveOnly) onDismiss() else amountStep = true
                                    }
                                }, enabled = targetEnabled && ready && state.currentRoom != null &&
                                    (!saveOnly || config.roomBindingEnabled && state.roomBindingKnown), modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (form.roomBusy) "正在保存…" else if (saveOnly) "绑定房间" else "下一步") }
                                if (saveOnly && !config.roomBindingEnabled) Text("此项目不支持绑定", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
                HallFeePage.Amount -> {
                    HallFeeSummary(form, Modifier.testTag("hall-payment-target"), targetSelected,
                        { targetSelected = !targetSelected }, active && !state.loading && !state.selecting,
                    )
                    val codes = if (form.project?.network == true) listOf("ACCOUNTTSM") else listOf("ACCOUNTTSM", "TSM")
                    // A fixed room is verified across several requests. Configuration can
                    // arrive halfway through the sheet's entrance: keep the geometry template
                    // until that first lookup settles, then retain the mounted form on refresh.
                    var presented by remember(session, form.project, fixedTarget) { mutableStateOf(false) }
                    val showForm = config != null && (!form.queryPending || presented)
                    SideEffect { if (showForm) presented = true }
                    PaymentFormContent(form.loading && !showForm) {
                        if (config != null) PaymentAmountForm(config.amountRules, amount, { amount = it },
                            codes.map { if (it == "TSM") "微信" else "一卡通" }, codes.indexOf(methodCode).coerceAtLeast(0),
                            { methodCode = codes[it] }, form.loading || form.selecting || form.roomBusy,
                            active && ready && targetSelected && !state.preview, form.error, { onCreate(it, methodCode) }, onRetry,
                            enabled = active)
                        else InlineError(form.error)
                    }
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
                            subject = "充值", retryMethods = result.methods, enabled = resultActive, onCheck = onCheck,
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
private fun HallFeeTargetPlaceholder(showRoomBinding: Boolean) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在读取房间选项" },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                PaymentControlPlaceholder { FeeLocationDropdownPlaceholder() }
            }
        }
        if (showRoomBinding) PaymentControlPlaceholder {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = ToggleableState.Off, onClick = {}, enabled = false)
                Text("绑定此房间", Modifier.padding(start = 8.dp), fontSize = 14.sp)
            }
        }
        PaymentControlPlaceholder { PaymentButtonGeometry("选择房间") }
    }
}

@Composable
private fun HallFeeSummary(state: HallFeeFormDisplay, modifier: Modifier = Modifier, selected: Boolean = true,
    onSelect: () -> Unit = {}, enabled: Boolean = false) {
    val room = state.summaryRoom
    // Keep only painted values while rechecking the same target. A settled failure,
    // a different room/project or a meter switch must discard these values.
    var lastDisplay by remember(state.project, room?.selections, state.switchingMeterKind) {
        mutableStateOf<List<Pair<String, String>>?>(null)
    }
    val display = if (state.queryPending) lastDisplay else state.queryDisplay
    SideEffect { if (!state.queryPending) lastDisplay = state.queryDisplay.takeIf { state.hasQueryDisplay } }
    PaymentSelectionCard(selected = selected, onClick = onSelect, modifier = modifier, enabled = enabled) { contentColor ->
        // The room is already known from the clicked card. Querying its readings
        // must never turn the heading into pending paint or crossfade it again.
        if (state.project?.network != true && room != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                UpdatedText(room.dialogLabel, Modifier.weight(1f), numeric = false, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold, color = contentColor)
                if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
            }
        }
        ReadContent(display, Modifier.fillMaxWidth(), pending = { it == null }, contentKey = { it == null }) { shown ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val placeholderColor = contentColor.copy(alpha = .2f)
                if (state.project?.network == true) {
                    if (shown == null) repeat(2) {
                        Row(Modifier.fillMaxWidth()) {
                            DashboardPlaceholder(13.sp, Modifier.weight(1f), fraction = .45f, color = placeholderColor)
                            DashboardPlaceholder(14.sp, Modifier.width(112.dp), fraction = 1f, color = placeholderColor)
                        }
                    }
                    else shown.filter { "套餐" in it.first || "状态" in it.first }.forEach { (name, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            UpdatedText(name, Modifier.widthIn(max = 88.dp), numeric = false, fontSize = 13.sp, color = contentColor.copy(alpha = .75f))
                            UpdatedText(value, Modifier.weight(1f), numeric = false, fontSize = 14.sp, color = contentColor, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                    }
                } else {
                    if (shown == null) {
                        if (room == null) DashboardPlaceholder(17.sp, Modifier.fillMaxWidth(), color = placeholderColor)
                        DashboardPlaceholder(13.sp, Modifier.fillMaxWidth(), fraction = .65f, color = placeholderColor)
                    }
                    else shown.forEach { (label, value) ->
                        UpdatedText(if (value.startsWith(label)) value else "$label  $value", numeric = false, fontSize = 13.sp,
                            color = contentColor.copy(alpha = .8f))
                    }
                }
            }
        }
    }
}

/** One physical location picker, with server-provided meter variants below it. */
@Composable
internal fun HallFeeTargetOptions(state: FeeUiState, onSelect: (FeeLevel, FeeOption) -> Unit,
    onMeterKind: (DormMeterKind) -> Unit, enabled: Boolean) {
    HallFeeTargetOptions(remember(state) { HallFeeFormDisplay(state, null, null) }, onSelect, onMeterKind, enabled)
}

@Composable
private fun HallFeeTargetOptions(state: HallFeeFormDisplay, onSelect: (FeeLevel, FeeOption) -> Unit,
    onMeterKind: (DormMeterKind) -> Unit, enabled: Boolean) {
    val levels = remember(state.levels, state.hiddenLevels) {
        state.levels.filterNot { it.key in state.hiddenLevels }
    }
    val project = state.project
    val meterChoices = remember(project, levels, state.options) {
        if (project == FeeProject.Zhengdong || project == FeeProject.Wenbei)
            dormMeterChoices(levels, state.options) else null
    }

    @Composable fun field(level: FeeLevel) {
        val available = state.options[level.number].orEmpty()
        val selected = available.indexOfFirst { it.id == state.selections[level.key] } + 1
        val labels = remember(project, level, meterChoices, available) {
            if (project == FeeProject.Wenbei && meterChoices != null &&
                level.number > meterChoices.level.number) available.map { physicalLabel(it.name) }
            else available.map { it.name }
        }
        FeeLocationDropdown(level.name, labels, selected, enabled) { index ->
            available.getOrNull(index)?.let { onSelect(level, it) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        levels.forEach { level ->
            if (meterChoices?.level == level) {
                val choices = meterChoices.choices
                val labels = remember(choices) { choices.map { it.label } }
                val selectedChoice = meterChoices.selectedChoice(state.selections[level.key])
                FeeLocationDropdown(level.name, labels,
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
