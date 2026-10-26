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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
) {
    LaunchedEffect(isActive) { if (isActive) onActivate() }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                DashboardTopBar("大厅", "校园生活服务，常用缴费更快找到", scrollBehavior)
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
                    item { DormCard(dorm, feeOpening, onRefreshDorm, onOpenFee, onOpenDormFee) }
                    item { LifeFeesCard(feeOpening, onOpenFee) }
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                            DashboardPreference(title = "代人充值", summary = "通过学工号或证件号，为他人充值",
                                startAction = { Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                                    .background(MiuixTheme.colorScheme.primary.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                                    Icon(AppIcons.OthersRecharge, null, Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
                                } },
                                enabled = !cardServiceOpening, onClick = { onOpenCardService(CampusCardAction.OthersRecharge) })
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
internal fun DormCard(state: DormUiState, opening: FeeProject?, onRefresh: () -> Unit,
    onChoose: (FeeProject) -> Unit, onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("我的宿舍", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (state.rooms.isNotEmpty()) DashboardLink("管理", onClick = { onChoose(state.rooms.first().project) }, enabled = opening == null)
            }
            if (state.error != null) InlineError(state.error, actionLabel = "重试", onAction = onRefresh)
            if (state.rooms.isEmpty()) {
                Text(if (state.loading) "正在读取已绑定的房间…" else "选择宿舍后，可查看照明与空调剩余电量", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(FeeProject.Zhengdong, FeeProject.Wenbei).forEach { project ->
                        CompactButton(project.label, onClick = { onChoose(project) }, enabled = opening == null,
                            modifier = Modifier.weight(1f))
                    }
                }
            } else state.rooms.forEachIndexed { index, room ->
                if (index > 0) HorizontalDivider()
                DormRoomContent(room, opening, onChoose, onPay)
            }
        }
    }
}

@Composable
private fun DormRoomContent(room: DormRoom, opening: FeeProject?, onChoose: (FeeProject) -> Unit,
    onPay: (FeeProject, FeeRoomBinding) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(room.label.replace("-", " · "), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(room.project.label + " · 宿舍电量", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        room.meters.forEach { meter ->
            val air = meter.kind == DormMeterKind.AirConditioning
            val ink = if (air) DashboardPalette.airInk else DashboardPalette.lightingInk
            val accent = if (air) DashboardPalette.airAccent else DashboardPalette.lightingAccent
            val panel = if (air) DashboardPalette.airPanel else DashboardPalette.lightingPanel
            val button = if (air) DashboardPalette.airButton else DashboardPalette.lightingButton
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(panel).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (meter.kind == DormMeterKind.AirConditioning) AppIcons.AirConditioning else AppIcons.Electricity,
                        null, Modifier.size(17.dp), tint = accent)
                    Text(meter.kind.label, fontSize = 13.sp, color = ink)
                }
                Text("剩余电量", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(meter.remainingKwh ?: "—", fontSize = 29.sp, fontWeight = FontWeight.SemiBold, color = ink)
                    if (meter.remainingKwh != null) Text("度", Modifier.padding(start = 3.dp, bottom = 4.dp), fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (meter.remainingKwh == null && meter.query != null && meter.error == null) {
                    Text(meter.query.display.joinToString("\n") { it.second }, fontSize = 12.sp)
                }
                if (meter.error != null) Text(meter.error, fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
                CompactButton("${meter.kind.label}缴费", onClick = { onPay(room.project, meter.room) }, enabled = opening == null,
                    modifier = Modifier.fillMaxWidth(), color = button, textColor = accent)
            }
        }
    }
    if (room.notice != null) {
        Text(room.notice, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        DashboardLink("核实房间", onClick = { onChoose(room.project) }, enabled = opening == null)
    }
}

@Composable
private fun LifeFeesCard(opening: FeeProject?, onOpen: (FeeProject) -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("生活缴费", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            FeeProject.entries.chunked(3).forEach { projects ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { project ->
                        ServiceEntry(
                            label = project.label,
                            icon = when (project) {
                                FeeProject.Network -> AppIcons.Network
                                FeeProject.FamilyWater -> AppIcons.Water
                                else -> AppIcons.Electricity
                            },
                            loading = opening == project,
                            enabled = opening == null,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(project) },
                        )
                    }
                    repeat(3 - projects.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
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
