package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.FeeRoomAction
import com.mercury.yktwx.FeeUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One root-hosted sheet for target selection, amount, server payment channels and results. */
@Composable
internal fun HallFeeDialog(state: FeeUiState, visible: Boolean, project: FeeProject?, fixedTarget: FeeRoomBinding?,
    saveOnly: Boolean = false, onDismiss: () -> Unit, onSelect: (FeeLevel, FeeOption) -> Unit,
    onRetry: () -> Unit, onCreate: (Long, String) -> Unit, onMethod: (FeeMethod) -> Unit,
    onPayAccount: (String, String) -> Unit, onCancelPayment: () -> Unit, onFinish: () -> Unit,
    onContinuePayment: (FeeMethod) -> Unit, onRoomAction: () -> Unit,
    onConfirmRoomAction: () -> Unit, onDismissRoomDialog: () -> Unit, onConsumeRoomNotice: () -> Unit,
    onProject: (FeeProject) -> Unit = {}, bindingProjects: Set<FeeProject> = emptySet(),
) {
    var amount by remember(state.config, visible) { mutableStateOf("") }
    var amountStep by remember(visible, project, fixedTarget) { mutableStateOf(fixedTarget != null || project?.network == true) }
    var saveCommon by remember(visible, project, state.selections) { mutableStateOf(false) }
    var bindingPending by remember(visible, project) { mutableStateOf(false) }
    var targetSelected by remember(visible, project, state.selections) { mutableStateOf(true) }
    var methodCode by remember(visible, project) { mutableStateOf("ACCOUNTTSM") }
    var methodId by remember(visible, project) { mutableStateOf<String?>(null) }
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
    val ready = !state.loading && !state.selecting && !state.refreshing && state.query?.canPay == true &&
        state.query.thirdParty != null && state.query.tip == null &&
        config?.project == project &&
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
    val title = when (state.stage) {
        FeeStage.Password -> "一卡通支付"
        FeeStage.Result -> null
        else -> paymentTitle
    }
    OverlayBottomSheet(show = visible && state.stage != FeeStage.Password, title = title, onDismissRequest = {
        if (state.stage == FeeStage.Result) onFinish() else onDismiss()
    }, renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        Column(Modifier.fillMaxWidth().testTag("hall-payment-content").bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state.stage) {
                FeeStage.Query -> {
                    if (config == null) {
                        if (state.loading) {
                            if (amountStep) HallFeeSummary(state, Modifier.testTag("hall-payment-target"),
                                project = project, fixedTarget = fixedTarget)
                            PaymentAmountPlaceholder(methods = if (project?.network == true) listOf("一卡通") else listOf("一卡通", "微信"))
                        }
                        InlineError(state.error, actionLabel = "重试", onAction = onRetry)
                    } else if (!amountStep && project?.network != true) {
                        val targetEnabled = !state.loading && !state.selecting && !state.refreshing && !state.roomBusy && state.roomDialog == null
                        HallFeeTargetOptions(state, onSelect, targetEnabled)
                        if (state.currentRoom != null && project in listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
                            val projects = listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
                                .filter { !saveOnly || it in bindingProjects }
                            if (projects.size > 1) FeeMeterTypeSelector("缴费类型",
                                projects.map { if (it == FeeProject.FamilyElectric) "电费" else "水费" },
                                projects.indexOf(project).coerceAtLeast(0), targetEnabled) { index ->
                                if (projects[index] != project) onProject(projects[index])
                            }
                        }
                        InlineError(state.error, actionLabel = "重试", onAction = onRetry)
                        if (!saveOnly && config.roomBindingEnabled && state.roomBindingKnown && state.roomAction == FeeRoomAction.Save) Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(state = if (saveCommon) ToggleableState.On else ToggleableState.Off,
                                onClick = { saveCommon = !saveCommon }, enabled = ready && !state.roomBusy)
                            Text(if (state.roomBinding == null) "绑定此房间" else "替换已绑定房间", Modifier.padding(start = 8.dp), fontSize = 14.sp)
                        }
                        Button(onClick = {
                            if (ready) {
                                if ((saveOnly || saveCommon) && config.roomBindingEnabled && state.roomAction == FeeRoomAction.Save) {
                                    bindingPending = true; onRoomAction()
                                } else if (saveOnly) onDismiss() else amountStep = true
                            }
                        }, enabled = ready && state.currentRoom != null && !state.roomBusy &&
                            (!saveOnly || config.roomBindingEnabled && state.roomBindingKnown), modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.roomBusy) "正在保存…" else if (saveOnly) "绑定房间" else "下一步") }
                        if (saveOnly && !config.roomBindingEnabled) Text("此项目不支持绑定", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    } else {
                        if (fixedTarget == null && project?.network != true) DashboardLink("返回选房", { amountStep = false }, arrow = false)
                        HallFeeSummary(state, Modifier.testTag("hall-payment-target"), targetSelected,
                            { targetSelected = !targetSelected }, !state.loading && !state.selecting,
                            project, fixedTarget)
                        val codes = if (project?.network == true) listOf("ACCOUNTTSM") else listOf("ACCOUNTTSM", "TSM")
                        if (state.loading && state.query == null) PaymentAmountPlaceholder(config = config.amountRules,
                            methods = codes.map { if (it == "TSM") "微信" else "一卡通" })
                        else PaymentAmountForm(config.amountRules, amount, { amount = it },
                            codes.map { if (it == "TSM") "微信" else "一卡通" }, codes.indexOf(methodCode).coerceAtLeast(0),
                            { methodCode = codes[it] }, state.loading || state.selecting || state.refreshing || state.roomBusy,
                            ready && targetSelected && !state.preview, state.error, { onCreate(it, methodCode) }, onRetry)
                    }
                }
                FeeStage.Methods -> {
                    HallFeeSummary(state)
                    Text("充值金额", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("¥ " + RechargeMoney.yuan(orderAmount), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Text("支付方式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    InlineError(state.error)
                    val methods = state.order?.methods.orEmpty()
                    val selectedIndex = methods.indexOfFirst { it.id == methodId }.takeIf { it >= 0 }
                        ?: methods.indexOfFirst { it.code == methodCode }.takeIf { it >= 0 }
                        ?: if (methods.isEmpty()) -1 else 0
                    HallNeutralTabs(methods.map { it.name }, selectedIndex.coerceAtLeast(0)) {
                        if (!state.loading) { methodCode = methods[it].code; methodId = methods[it].id }
                    }
                    Button(onClick = { methods.getOrNull(selectedIndex)?.let(onMethod) },
                        enabled = !state.loading && selectedIndex >= 0, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.loading) "正在准备支付…" else "确定支付") }
                    DashboardLink("修改金额", onCancelPayment, arrow = false, enabled = !state.loading)
                }
                FeeStage.Password -> Unit
                FeeStage.Result -> PaymentResultContent(state.resultPhase, state.loading, orderAmount, state.error,
                    onFinish, subject = "充值", retryMethods = if (state.canContinuePayment) state.paymentRetry!!.methods else emptyList(),
                    onContinuePayment = onContinuePayment)
            }
        }
    }
    FeePasswordDialog(show = visible && state.stage == FeeStage.Password,
        amountCents = orderAmount, preparation = state.preparation, loading = state.loading, error = state.error,
        onDismiss = onCancelPayment, onPay = onPayAccount)
    val dialog = state.roomDialog
    OverlayDialog(show = visible && dialog != null, title = if (dialog?.previous != null) "替换已绑定房间" else "绑定房间",
        summary = dialog?.let { if (it.previous != null) "将「${it.previous.dialogLabel}」替换为「${it.target.dialogLabel}」" else it.target.dialogLabel },
        onDismissRequest = { bindingPending = false; onDismissRoomDialog() }, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { bindingPending = false; onDismissRoomDialog() }, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onConfirmRoomAction, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (dialog?.previous != null) "替换" else "绑定")
            }
        }
    }
}

@Composable
private fun HallFeeSummary(state: FeeUiState, modifier: Modifier = Modifier, selected: Boolean = true,
    onSelect: () -> Unit = {}, enabled: Boolean = false,
    project: FeeProject? = state.config?.project, fixedTarget: FeeRoomBinding? = null) {
    val pending = state.query == null && (state.loading || state.selecting || state.refreshing)
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

/** Group meter variants only when the live server options contain both siblings. */
@Composable
internal fun HallFeeTargetOptions(state: FeeUiState, onSelect: (FeeLevel, FeeOption) -> Unit, enabled: Boolean) {
    val levels = state.levels.filterNot { it.key in state.hiddenLevels }
    val last = state.levels.lastOrNull()
    val options = last?.let { state.options[it.number] }.orEmpty()
    fun stem(option: FeeOption) = option.name.removeSuffix("照明").removeSuffix("空调").trim()
    val groups = options.groupBy(::stem)
    val paired = state.config?.project in listOf(FeeProject.Zhengdong, FeeProject.Wenbei) && groups.isNotEmpty() &&
        groups.values.all { siblings -> siblings.size == 2 && siblings.map { DormMeterDiscovery.kind(it.name) }.toSet() ==
            setOf(DormMeterKind.Lighting, DormMeterKind.AirConditioning) }
    @Composable fun field(level: FeeLevel) {
        val available = state.options[level.number].orEmpty()
        val selected = available.indexOfFirst { it.id == state.selections[level.key] } + 1
        FeeLocationDropdown(level.name, available.map { it.name }, selected, enabled) { index ->
            available.getOrNull(index)?.let { onSelect(level, it) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ordinary = levels.filterNot { it == last && paired }
        ordinary.forEach { field(it) }
        if (paired && last != null) {
            val selected = options.firstOrNull { it.id == state.selections[last.key] }
            val names = groups.keys.toList()
            FeeLocationDropdown("房间", names, selected?.let { names.indexOf(stem(it)) + 1 } ?: 0, enabled) { index ->
                names.getOrNull(index)?.let { name ->
                    groups[name]?.firstOrNull { it.name.endsWith("照明") }?.let { onSelect(last, it) }
                }
            }
            if (selected != null) {
                val siblings = groups.getValue(stem(selected)).sortedBy { DormMeterDiscovery.kind(it.name).ordinal }
                FeeMeterTypeSelector("电表", siblings.map { DormMeterDiscovery.kind(it.name).label },
                    siblings.indexOf(selected).coerceAtLeast(0), enabled) { onSelect(last, siblings[it]) }
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
