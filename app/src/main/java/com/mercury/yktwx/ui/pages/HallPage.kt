package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
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
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun HallPage(
    settings: ThemeSettings,
    bottomPadding: Dp,
    onOpenFee: (FeeProject) -> Unit = {},
    feeOpening: FeeProject? = null,
    onOpenCardService: (CampusCardAction) -> Unit = {},
    cardServiceOpening: Boolean = false,
    cardServiceLoadingAction: CampusCardAction? = null,
    dorm: DormUiState = DormUiState(),
    isActive: Boolean = false,
    onActivate: () -> Unit = {},
    onRefreshDorm: () -> Unit = {},
    onOpenDormFee: (FeeProject, FeeRoomBinding) -> Unit = { project, _ -> onOpenFee(project) },
    onSelectRoom: (Int) -> Unit = {},
    onRemoveRoom: (DormRoom) -> Unit = {},
    onAddRoom: (FeeProject) -> Unit = onOpenFee,
) {
    LaunchedEffect(isActive) { if (isActive) onActivate() }
    var targets by remember { mutableStateOf(false) }
    LaunchedEffect(isActive) { if (!isActive) targets = false }
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize().overScrollVertical().imePadding(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 10.dp,
                        bottom = bottomPadding + 24.dp,
                        start = 16.dp, end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { UtilityCard(dorm, feeOpening, onRefreshDorm, { targets = true }, onOpenDormFee) }
                    item { NetworkCard(dorm, feeOpening, { onOpenFee(FeeProject.Network) }) }
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                            DashboardPreference(title = "代人充值",
                                startAction = { Icon(AppIcons.OthersRecharge, null, Modifier.size(22.dp), tint = MiuixTheme.colorScheme.primary) },
                                enabled = !cardServiceOpening, onClick = { onOpenCardService(CampusCardAction.OthersRecharge) })
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
    }, onRemove = onRemoveRoom, onAdd = { targets = false; onAddRoom(it) })
}

@Composable
private fun UtilityCard(state: DormUiState, opening: FeeProject?, onRefresh: () -> Unit,
    onSwitch: () -> Unit, onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Electricity, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("水电费", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                DashboardLink("切换", onClick = onSwitch, enabled = opening == null && !state.managing)
            }
            if (state.error != null) InlineError(state.error, actionLabel = "重试", onAction = onRefresh)
            if (state.rooms.isEmpty()) {
                Text(if (state.loading) "正在读取房间…" else "尚未添加房间", fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                CompactButton("选择房间", onClick = onSwitch, enabled = opening == null, modifier = Modifier.fillMaxWidth())
            } else state.selectedRoom?.let { room ->
                key(room.project, room.binding.selections) { DormRoomContent(room, opening, { onSwitch() }, onPay) }
            }
        }
    }
}

@Composable
private fun DormRoomContent(room: DormRoom, opening: FeeProject?, onChoose: (FeeProject) -> Unit,
    onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(room.hallLabel, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        HallBadge("已绑定")
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        room.meters.forEach { meter ->
            val project = meter.project ?: room.project
            val water = project == FeeProject.FamilyWater
            val family = project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
            val air = meter.kind == DormMeterKind.AirConditioning
            val secondary = air || water
            val ink = if (secondary) DashboardPalette.airInk else DashboardPalette.lightingInk
            val accent = if (secondary) DashboardPalette.airAccent else DashboardPalette.lightingAccent
            val panel = if (secondary) DashboardPalette.airPanel else DashboardPalette.lightingPanel
            val button = if (secondary) DashboardPalette.airButton else DashboardPalette.lightingButton
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (water) AppIcons.Water else if (air) AppIcons.AirConditioning else AppIcons.Electricity,
                        null, Modifier.size(17.dp), tint = accent)
                    Text(if (family) if (water) "水费" else "电费" else meter.kind.label, fontSize = 13.sp, color = ink)
                }
                val readings = meter.readings
                val primary = readings.firstOrNull()
                Text(primary?.label ?: if (water) "剩余水量" else "剩余电量",
                    fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(primary?.value ?: "—", fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    if (primary?.unit?.isNotEmpty() == true) Text(primary.unit,
                        Modifier.padding(start = 3.dp, bottom = 4.dp), fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                readings.drop(1).takeIf { it.isNotEmpty() }?.let { extra ->
                    Text(extra.joinToString(" · ") { "${it.label} ${it.value}${it.unit}" }, fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (meter.error != null) Text(meter.error, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                Surface(
                    onClick = { onPay(project, meter.room) },
                    enabled = opening == null,
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
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    if (room.notice != null) {
        Text(room.notice, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DashboardLink("核实房间", onClick = { onChoose(room.project) }, enabled = opening == null)
    }
}

@Composable
private fun NetworkCard(state: DormUiState, opening: FeeProject?, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Network, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("校园网", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                state.network?.display?.firstOrNull { "状态" in it.first }?.second?.let { HallBadge(it, air = true) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.network?.display?.firstOrNull { "套餐" in it.first }?.second
                    ?: if (state.loading) "正在读取…" else "—", Modifier.weight(1f), fontSize = 15.sp)
                CompactButton("充值", onClick = onOpen, enabled = opening == null, modifier = Modifier.testTag("network-recharge"))
            }
            InlineError(state.networkError)
        }
    }
}

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
    Text(text, Modifier.clip(RoundedCornerShape(4.dp)).background(if (air) DashboardPalette.airPanel else DashboardPalette.field)
        .padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp,
        color = if (air) DashboardPalette.airAccent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/** Campus card services share the three-column grid used by living fees. */
@Composable
internal fun CampusCardServicesCard(opening: Boolean, onOpen: (CampusCardAction) -> Unit, loadingAction: CampusCardAction? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("校园卡服务", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CampusCardAction.ManageState, CampusCardAction.OthersRecharge, CampusCardAction.ChangePassword).forEach { action ->
                    ServiceEntry(
                        label = action.label,
                        icon = when (action) {
                            CampusCardAction.ManageState -> AppIcons.CardState
                            CampusCardAction.ChangePassword -> AppIcons.Password
                            else -> AppIcons.OthersRecharge
                        },
                        loading = loadingAction == action,
                        enabled = !opening,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(action) },
                    )
                }
            }
        }
    }
}

/** The whole entry is tappable, but its press indication stays inside the circular icon. */
@Composable
private fun ServiceEntry(label: String, icon: ImageVector, loading: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .semantics { contentDescription = label }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer)
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            else Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Text(label, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
