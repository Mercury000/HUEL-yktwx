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
import androidx.compose.ui.text.style.TextOverflow
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
    var targetTab by remember { mutableIntStateOf(0) }
    var targetAdding by remember { mutableStateOf(false) }
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
                    item {
                        UtilityCard(dorm, feeOpening, onRefreshDorm,
                            onSwitch = { targetTab = 0; targetAdding = false; targets = true },
                            onAdd = { targetTab = 0; targetAdding = true; targets = true },
                            onTemporaryPay = { targetTab = 1; targetAdding = false; targets = true },
                            onPay = onOpenDormFee)
                    }
                    item { NetworkCard(dorm, feeOpening, { onOpenFee(FeeProject.Network) }) }
                    item {
                        Card(Modifier.fillMaxWidth().testTag("hall-others-card"), cornerRadius = 24.dp) {
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
    }, onRemove = onRemoveRoom, onAdd = { targets = false; onAddRoom(it) },
        initialTab = targetTab, initialAdding = targetAdding)
}

@Composable
private fun UtilityCard(state: DormUiState, opening: FeeProject?, onRefresh: () -> Unit,
    onSwitch: () -> Unit, onAdd: () -> Unit, onTemporaryPay: () -> Unit, onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("hall-utility-card"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Electricity, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("水电费", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                if (state.rooms.isNotEmpty() || !state.loaded) {
                    DashboardLink("切换", onClick = onSwitch, enabled = opening == null && !state.managing)
                }
            }
            if (state.error != null) InlineError(state.error, actionLabel = "重试", onAction = onRefresh)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.rooms.isEmpty() && !state.loaded) {
                    UtilityPlaceholder()
                } else if (state.rooms.isEmpty()) {
                    UnboundRoomContent(enabled = opening == null && !state.managing && !state.loading,
                        onAdd = onAdd, onTemporaryPay = onTemporaryPay)
                } else state.selectedRoom?.let { room ->
                    key(room.project, room.binding.selections) { DormRoomContent(room, opening, { onSwitch() }, onPay) }
                }
            }
        }
    }
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
            Icon(if (temporary) AppIcons.Bill else AppIcons.Hall, null, Modifier.size(17.dp), tint = accent)
            Text(if (temporary) "临时缴费" else "房间管理", fontSize = 13.sp, lineHeight = 18.sp, color = ink)
        }
        Text(if (temporary) "无需绑定" else "常用房间", fontSize = 10.sp, lineHeight = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        UtilityValueRow {
            Text(if (temporary) "按次缴费" else "尚未添加", fontSize = 22.sp, lineHeight = 38.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, color = ink)
        }
        Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp), color = button, contentColor = accent) {
            Text(if (temporary) "去缴费" else "添加房间",
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), fontSize = 13.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun UtilityValueRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(with(LocalDensity.current) { 38.sp.toDp() }),
        verticalAlignment = Alignment.Bottom, content = content)
}

@Composable
private fun UtilityPlaceholder() {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在读取房间" }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DashboardPlaceholder(15.sp, Modifier.fillMaxWidth(), lineHeight = 22.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(DashboardPalette.field).padding(14.dp),
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
private fun DormRoomContent(room: DormRoom, opening: FeeProject?, onChoose: (FeeProject) -> Unit,
    onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(room.hallLabel, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
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
                    Text(if (family) if (water) "水费" else "电费" else meter.kind.label, fontSize = 13.sp, lineHeight = 18.sp, color = ink)
                }
                val readings = meter.readings
                val primary = readings.firstOrNull()
                Text(primary?.label ?: if (water) "剩余水量" else "剩余电量",
                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                UtilityValueRow {
                    Text(primary?.value ?: "—", fontSize = 29.sp, lineHeight = 38.sp, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false), overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold, color = ink)
                    if (primary?.unit?.isNotEmpty() == true) Text(primary.unit,
                        Modifier.padding(start = 3.dp, bottom = 4.dp), fontSize = 11.sp, lineHeight = 16.sp,
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
                        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
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
    Card(Modifier.fillMaxWidth().testTag("hall-network-card"), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Network, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.primary)
                Text("校园网", Modifier.weight(1f).padding(start = 8.dp), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                state.network?.display?.firstOrNull { "状态" in it.first }?.second?.let { HallBadge(it, air = true) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!state.loaded && state.network == null) DashboardPlaceholder(15.sp, Modifier.weight(1f), lineHeight = 22.sp)
                else Text(state.network?.display?.firstOrNull { "套餐" in it.first }?.second ?: "—", Modifier.weight(1f),
                    fontSize = 15.sp, lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        .padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
        color = if (air) DashboardPalette.airAccent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
