package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.CampusCardAction
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.FeeRoomBinding
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.data.DormRoom
import com.mercury.yktwx.data.DormMeterKind
import com.mercury.yktwx.data.DormMeterSlot
import com.mercury.yktwx.data.DormMeter
import com.mercury.yktwx.data.FeeQuery
import com.mercury.yktwx.data.ReadFailureKind
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun HallPage(
    settings: ThemeSettings,
    bottomPadding: Dp,
    onOpenFee: (FeeProject) -> Unit = {},
    feeOpening: FeeProject? = null,
    feeOpeningTarget: FeeRoomBinding? = null,
    onOpenCardService: (CampusCardAction) -> Unit = {},
    othersRechargeLoading: Boolean = false,
    dorm: DormUiState = DormUiState(),
    isActive: Boolean = false,
    onRefreshDorm: () -> Unit = {},
    onOpenDormFee: (FeeProject, FeeRoomBinding) -> Unit = { project, _ -> onOpenFee(project) },
    onSelectRoom: (Int) -> Unit = {},
    onRemoveRoom: (DormRoom) -> Unit = {},
    onAddRoom: (FeeProject) -> Unit = onOpenFee,
    updateNotice: @Composable () -> Unit = {},
) {
    val dorm = if (LocalReadOffline.current) dorm.offlineDisplay() else dorm
    var targets by remember { mutableStateOf(false) }
    var targetTab by remember { mutableIntStateOf(0) }
    var targetAdding by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) targets = false }
    val hasContent = dorm.rooms.isNotEmpty() || dorm.roomsAvailable || dorm.network != null
    val initialError = rememberInitialReadError(hasContent,
        FeedbackText.firstError(readError(dorm.error, dorm.errorKind), readError(dorm.networkError, dorm.networkErrorKind)), !dorm.loading && dorm.loaded)
    val networkError = rememberPageNetworkError(dorm.loading, hasContent, initialError,
        ReadRegion("水电费", FeedbackText.networkError(readError(dorm.error, dorm.errorKind),
            ReadError(FeedbackText.Offline, ReadFailureKind.Network).takeIf { ReadFailureKind.Network in dorm.projectErrorKinds.values },
            *dorm.rooms.flatMap { room -> room.meters.map { readError(it.error, it.errorKind) } }.toTypedArray()),
            dorm.roomsAvailable || dorm.rooms.isNotEmpty()),
        ReadRegion("校园网信息", readError(dorm.networkError, dorm.networkErrorKind), dorm.network != null),
        restoringDisplay = dorm.restoringDisplay)
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                DashboardTopBar("大厅", scrollBehavior = scrollBehavior)
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                DashboardPullToRefresh(
                    loading = dorm.loading,
                    onRefresh = onRefreshDorm,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 10.dp),
                    scrollBehavior = scrollBehavior,
                ) {
                InitialReadTransition(initialError, networkError, dorm.displayRestored) { displayedError ->
                    val showFailure = displayedError != null
                    // Only an empty pending shell is retained for the outgoing skeleton.
                    val dorm = if (!showFailure && (initialError != null ||
                        dorm.loading && !dorm.roomsAvailable && dorm.rooms.isEmpty() && dorm.network == null))
                        DormUiState(loading = true) else dorm
                LazyColumn(
                    modifier = Modifier.fillMaxSize().overScrollVertical().imePadding().testTag("hall-page-list"),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 10.dp,
                        bottom = bottomPadding + 24.dp,
                        start = 16.dp, end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (showFailure) {
                        item {
                            Column {
                                updateNotice()
                                ReadUnavailable(checkNotNull(displayedError), Modifier.fillParentMaxHeight())
                            }
                        }
                    } else {
                    item {
                        Column {
                            PageReadNotice(networkError.takeIf { initialError == null }, 14.dp,
                                errorMaxLines = Int.MAX_VALUE,
                                loading = (dorm.loading || isActive && !dorm.loaded) && (dorm.loaded || dorm.displayRestored),
                                failed = dorm.error != null || dorm.networkError != null || dorm.rooms.any { room -> room.meters.any { it.error != null } }) {
                                updateNotice()
                                UtilityCard(dorm, feeOpening, feeOpeningTarget, onRefreshDorm,
                                    onSwitch = { targetTab = 0; targetAdding = false; targets = true },
                                    onAdd = { targetTab = 0; targetAdding = true; targets = true },
                                    onTemporaryPay = { targetTab = 1; targetAdding = false; targets = true },
                                    onPay = onOpenDormFee)
                            }
                        }
                    }
                    item { NetworkCard(dorm, feeOpening, { onOpenFee(FeeProject.Network) }) }
                    item {
                        Card(Modifier.fillMaxWidth().testTag("hall-others-card"), cornerRadius = 24.dp) {
                            DashboardPreference(title = "代人充值",
                                startAction = { Icon(AppIcons.OthersRecharge, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary) },
                                enabled = !othersRechargeLoading, onClick = { onOpenCardService(CampusCardAction.OthersRecharge) })
                        }
                    }
                    }
                }
                }
                }
            }
        }
    }
    HallRoomSheet(dorm, targets, onDismiss = { targets = false }, onSelect = {
        onSelectRoom(it); targets = false
    }, onProject = {
        targets = false; onOpenFee(it)
    }, onRemove = onRemoveRoom, onAdd = { targets = false; onAddRoom(it) },
        initialTab = targetTab, initialAdding = targetAdding)
}

@Composable
private fun UtilityCard(state: DormUiState, opening: FeeProject?, openingTarget: FeeRoomBinding?, onRefresh: () -> Unit,
    onSwitch: () -> Unit, onAdd: () -> Unit, onTemporaryPay: () -> Unit, onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("hall-utility-card"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Electricity, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("水电费", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (state.rooms.isNotEmpty() || !state.roomsLoaded) {
                    DashboardLink("切换", onClick = onSwitch, enabled = !state.managing)
                }
            }
            val display = remember(state) { utilityDisplay(state) }
            ReadLayout(Modifier.fillMaxWidth()) {
                ReadContent(display, Modifier.fillMaxWidth(),
                    pending = { it.pending },
                    contentKey = ::utilityContentKey) { displayed ->
                    val interactive = utilityContentKey(displayed) == utilityContentKey(display)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (displayed.pending) {
                            UtilityPlaceholder()
                        } else if (displayed.room == null && displayed.error != null) {
                            ReadUnavailable(displayed.error)
                        } else if (displayed.room == null) {
                            UnboundRoomContent(enabled = interactive && !state.managing && !state.loading,
                                onAdd = onAdd, onTemporaryPay = onTemporaryPay)
                        } else displayed.room.let { room ->
                            key(room.project, room.binding.selections) {
                                DormRoomContent(room, opening, openingTarget, { onSwitch() }, onPay, displayed.error, interactive)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Amount-only refreshes retain the existing panels and their numeric animation. */
private data class UtilityDisplay(val room: DormRoom?, val pending: Boolean, val error: ReadError?)

/** Animated snapshots contain display readings, never the server payment query payload. */
private fun utilityDisplay(state: DormUiState) = UtilityDisplay(state.selectedRoom?.let { room ->
    DormRoom(room.project, room.binding, room.meters.map { meter ->
        DormMeter(meter.kind, meter.room, meter.query?.let { FeeQuery(display = it.display) },
            meter.error, meter.project, meter.updating, meter.errorKind)
    }, room.updatedAtMillis, room.notice, pendingMeters = room.pendingMeters)
}, state.rooms.isEmpty() && !state.roomsLoaded && state.error == null, readError(state.error, state.errorKind))

/** Only a different room or visible panel structure replaces the body. Background refresh
 * flags and normalized project/order metadata must never fade already painted cards. */
private fun utilityContentKey(state: UtilityDisplay): Any = state.room?.let { room ->
    val readySlots = room.meters.map { DormMeterSlot(it.kind, it.project ?: room.project) }.toSet()
    val slots = (readySlots + room.pendingMeters).sortedWith(
        compareBy<DormMeterSlot> { it.kind.ordinal }.thenBy { it.project.ordinal })
    listOf(room.project, room.binding.selections, slots.map { listOf(it, it in readySlots) })
} ?: when {
    state.pending -> "pending"
    state.error != null -> "error"
    else -> "unbound"
}

@Composable
private fun UnboundRoomContent(enabled: Boolean, onAdd: () -> Unit, onTemporaryPay: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("我的房间", Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)
        HallBadge("未绑定")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UnboundUtilityPanel(temporary = false, enabled = enabled, onClick = onAdd, modifier = Modifier.weight(1f))
        UnboundUtilityPanel(temporary = true, enabled = enabled, onClick = onTemporaryPay, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UnboundUtilityPanel(temporary: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ink = if (temporary) DashboardPalette.airInk else DashboardPalette.lightingInk
    val accent = if (temporary) DashboardPalette.airAccent else DashboardPalette.lightingAccent
    val panel = if (temporary) DashboardPalette.airPanel else DashboardPalette.lightingPanel
    val button = if (temporary) DashboardPalette.airButton else DashboardPalette.lightingButton
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (temporary) AppIcons.Bill else MiuixIcons.GridView, null, Modifier.size(17.dp), tint = accent)
            Text(if (temporary) "临时缴费" else "房间管理", fontSize = 13.sp, lineHeight = 18.sp, color = ink)
        }
        Text(if (temporary) "无需绑定" else "常用房间", fontSize = 10.sp, lineHeight = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        UtilityValueRow {
            Text(if (temporary) "按次缴费" else "尚未添加", fontSize = 22.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold, color = ink)
        }
        ActionSurface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), color = button, contentColor = accent) {
            Text(if (temporary) "去缴费" else "添加房间",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun UtilityValueRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(with(LocalDensity.current) { 38.sp.toDp() }),
        verticalAlignment = Alignment.Bottom, content = content)
}

@Composable
private fun UtilityPlaceholder() {
    Column(Modifier.fillMaxWidth().then(delayedSkeletonModifier()).semantics { contentDescription = "正在读取房间" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardPlaceholder(15.sp, Modifier.fillMaxWidth(), lineHeight = 22.sp)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(DashboardPalette.field).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(17.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .08f), CircleShape))
                        DashboardPlaceholder(13.sp, Modifier.weight(1f), lineHeight = 18.sp)
                    }
                    DashboardPlaceholder(10.sp, Modifier.fillMaxWidth(), lineHeight = 14.sp)
                    UtilityValueRow {
                        DashboardPlaceholder(29.sp, Modifier.fillMaxWidth(), lineHeight = 38.sp, sample = "0")
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        DashboardPlaceholder(13.sp, Modifier.fillMaxWidth(), lineHeight = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DormRoomContent(room: DormRoom, opening: FeeProject?, openingTarget: FeeRoomBinding?, onChoose: (FeeProject) -> Unit,
    onPay: (FeeProject, FeeRoomBinding) -> Unit, readError: ReadError? = null, interactive: Boolean = true) {
    Column {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        UpdatedText(room.hallLabel, Modifier.weight(1f), numeric = false, fontSize = 15.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)
        HallBadge("已绑定")
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val slots = (room.meters.map { DormMeterSlot(it.kind, it.project ?: room.project) } + room.pendingMeters)
            .distinct().sortedWith(compareBy<DormMeterSlot> { it.kind.ordinal }.thenBy { it.project.ordinal })
        slots.forEach { slot ->
            val meter = room.meters.firstOrNull { it.kind == slot.kind && (it.project ?: room.project) == slot.project }
            if (meter == null) PendingMeterPanel(slot, Modifier.weight(1f).fillMaxHeight()) else {
                val project = slot.project
                val water = project == FeeProject.FamilyWater
                val family = project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
                val air = meter.kind == DormMeterKind.AirConditioning
                val secondary = air || water
                val ink = if (secondary) DashboardPalette.airInk else DashboardPalette.lightingInk
                val accent = if (secondary) DashboardPalette.airAccent else DashboardPalette.lightingAccent
                val panel = if (secondary) DashboardPalette.airPanel else DashboardPalette.lightingPanel
                val button = if (secondary) DashboardPalette.airButton else DashboardPalette.lightingButton
                Column(Modifier.weight(1f).fillMaxHeight().testTag("utility-panel-${project.name}-${slot.kind.name}")
                    .clip(RoundedCornerShape(18.dp)).background(panel).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(if (water) AppIcons.Water else if (air) AppIcons.AirConditioning else AppIcons.Electricity,
                            null, Modifier.size(17.dp), tint = accent)
                        Text(if (family) if (water) "水费" else "电费" else meter.kind.label, fontSize = 13.sp, lineHeight = 18.sp, color = ink)
                    }
                    val readings = meter.readings
                    val primary = readings.firstOrNull()
                    Column(Modifier.weight(1f).fillMaxWidth().testTag("utility-readings-${project.name}-${slot.kind.name}"),
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                        UpdatedText(primary?.label ?: if (water) "剩余水量" else "剩余电量",
                            numeric = false, fontSize = 10.sp, lineHeight = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (primary != null && !primary.value.matches(Regex("-?\\d+(?:\\.\\d+)?"))) {
                            UpdatedText(primary.value, numeric = false, fontSize = 15.sp, lineHeight = 22.sp, color = ink)
                        } else UtilityValueRow {
                            UpdatedText(primary?.value ?: "—", identity = listOf(project, meter.room.selections), fontSize = 29.sp, lineHeight = 38.sp,
                                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                                fontWeight = FontWeight.SemiBold, color = ink, fitNumber = true)
                            if (primary?.unit?.isNotEmpty() == true) Text(primary.unit,
                                Modifier.padding(start = 3.dp).alignByBaseline(), fontSize = 11.sp, lineHeight = 16.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        readings.drop(1).map { "${it.label.trim()} ${it.value.trim()}${it.unit.trim()}".trim() }
                            .filter(String::isNotBlank).takeIf { it.isNotEmpty() }?.let { extra ->
                            UpdatedText(extra.joinToString(" · "), numeric = false, fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    ActionSurface(
                        onClick = { onPay(project, meter.room) },
                        enabled = interactive && !feeEntryOpening(opening, openingTarget, project, meter.room),
                        modifier = Modifier.fillMaxWidth().then(
                            if (room.meters.size == 1) Modifier.testTag("utility-single-recharge") else Modifier),
                        shape = RoundedCornerShape(12.dp),
                        color = button,
                        contentColor = accent,
                    ) {
                        Text(
                            if (family) if (water) "水费充值" else "电费充值"
                            else if (meter.kind == DormMeterKind.Other) "充值" else "${meter.kind.label}充值",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
    }
    ReadNotice(FeedbackText.firstError(readError, *room.meters.map { readError(it.error, it.errorKind) }.toTypedArray()),
        cached = room.meters.any { it.query != null }, topSpacing = 20.dp)
    if (room.notice != null && readError == null && room.meters.none { it.error != null }) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdatedText(room.notice, numeric = false, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DashboardLink("核实房间", onClick = { onChoose(room.project) }, enabled = interactive)
        }
    }
    }
}

@Composable
private fun PendingMeterPanel(slot: DormMeterSlot, modifier: Modifier) {
    val offline = LocalReadOffline.current
    val water = slot.project == FeeProject.FamilyWater
    val secondary = water || slot.kind == DormMeterKind.AirConditioning
    val family = slot.project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
    val ink = if (secondary) DashboardPalette.airInk else DashboardPalette.lightingInk
    val accent = if (secondary) DashboardPalette.airAccent else DashboardPalette.lightingAccent
    Column(modifier.clip(RoundedCornerShape(18.dp))
        .background(if (secondary) DashboardPalette.airPanel else DashboardPalette.lightingPanel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (water) AppIcons.Water else if (secondary) AppIcons.AirConditioning else AppIcons.Electricity,
                null, Modifier.size(17.dp), tint = accent)
            Text(if (family) if (water) "水费" else "电费" else slot.kind.label,
                fontSize = 13.sp, lineHeight = 18.sp, color = ink)
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            if (offline) {
                Text(if (water) "剩余水量" else "剩余电量", fontSize = 10.sp, lineHeight = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                UtilityValueRow {
                    Text("—", fontSize = 29.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, color = ink)
                }
            } else {
                DashboardPlaceholder(10.sp, Modifier.fillMaxWidth(), lineHeight = 14.sp)
                UtilityValueRow { DashboardPlaceholder(29.sp, Modifier.fillMaxWidth(), lineHeight = 38.sp, sample = "0") }
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            if (offline) {
                if (LocalPageNetworkError.current == null) Text(FeedbackText.Offline, fontSize = 13.sp, lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } else DashboardPlaceholder(13.sp, Modifier.fillMaxWidth(), lineHeight = 18.sp)
        }
    }
}

@Composable
private fun NetworkCard(state: DormUiState, opening: FeeProject?, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("hall-network-card"), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Network, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("校园网", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                state.network?.display?.firstOrNull { "状态" in it.first }?.second?.let { HallBadge(it, air = true) }
            }
            Column {
            if (state.network == null && state.networkError != null) {
                ReadUnavailable(checkNotNull(readError(state.networkError, state.networkErrorKind)))
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                val packageName = if (!state.networkLoaded && state.network == null) null
                    else state.network?.display?.firstOrNull { "套餐" in it.first }?.second ?: "—"
                ReadContent(packageName, Modifier.weight(1f), pending = { it == null }) { name ->
                    if (name == null) DashboardPlaceholder(15.sp,
                        Modifier.fillMaxWidth().testTag("network-package-placeholder"), lineHeight = 22.sp)
                    else Text(name, Modifier.fillMaxWidth().testTag("network-package-name"),
                        fontSize = 15.sp, lineHeight = 22.sp)
                }
                CompactButton("充值", onClick = onOpen, enabled = opening != FeeProject.Network, modifier = Modifier.testTag("network-recharge"))
            }
            ReadNotice(readError(state.networkError.takeIf { state.network != null }, state.networkErrorKind), cached = true, topSpacing = 24.dp)
            }
        }
    }
}

private fun feeEntryOpening(opening: FeeProject?, openingTarget: FeeRoomBinding?, project: FeeProject,
    target: FeeRoomBinding): Boolean = opening == project && openingTarget?.sameTarget(target) != false

internal val DormRoom.hallLabel: String get() {
    val region = when (project) {
        FeeProject.Zhengdong -> "郑东"
        FeeProject.Wenbei -> "文北"
        FeeProject.FamilyElectric, FeeProject.FamilyWater -> "家属区"
        FeeProject.Commercial -> "商业街"
        else -> project.label
    }
    val location = label.replace("-", " · ")
    return if (location.startsWith(region)) location else "$region · $location"
}

@Composable
internal fun HallBadge(text: String, air: Boolean = false) {
    UpdatedText(text, Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(4.dp)).background(if (air) DashboardPalette.airPanel else DashboardPalette.field)
        .padding(horizontal = 5.dp, vertical = 2.dp), numeric = false, fontSize = 10.sp, lineHeight = 14.sp,
        color = if (air) DashboardPalette.airAccent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
