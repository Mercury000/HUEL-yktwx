package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.data.DormRoom
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val FeeProject.hallTitle: String get() = when (this) {
    FeeProject.Zhengdong -> "郑东宿舍电费"
    FeeProject.Wenbei -> "文北宿舍电费"
    FeeProject.FamilyElectric, FeeProject.FamilyWater -> "家属区水电费"
    else -> label
}

@Composable
internal fun HallNeutralTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRowWithContour(tabs = tabs, selectedTabIndex = selected, onTabSelected = onSelect,
        colors = TabRowDefaults.tabRowColors(backgroundColor = DashboardPalette.field,
            contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            selectedBackgroundColor = DashboardPalette.card, selectedContentColor = MiuixTheme.colorScheme.onSurface))
}

@Composable
internal fun HallRoomSheet(state: DormUiState, visible: Boolean,
    onDismiss: () -> Unit, onSelect: (Int) -> Unit,
    onRemove: (DormRoom) -> Unit, onProject: (FeeProject) -> Unit, onAdd: (FeeProject) -> Unit) {
    var tab by remember(visible) { mutableIntStateOf(0) }
    var adding by remember(visible) { mutableStateOf(false) }
    var removal by remember(visible) { mutableStateOf<DormRoom?>(null) }
    val busy = state.managing || state.loading
    OverlayBottomSheet(show = visible, title = "切换房间 / 临时缴费", onDismissRequest = onDismiss,
        renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HallNeutralTabs(listOf("房间管理", "临时缴费"), tab) { tab = it; adding = false }
            if (tab == 0 && !adding) {
                InlineError(state.error)
                if (state.rooms.isEmpty()) Text("暂无绑定房间", Modifier.padding(vertical = 16.dp),
                    fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                state.rooms.forEachIndexed { index, room ->
                    val selected = index == state.selectedIndex
                    PaymentSelectionCard(selected = selected, onClick = { onSelect(index) },
                        enabled = !busy) { contentColor ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(room.hallLabel, Modifier.weight(1f), fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, color = contentColor)
                            DashboardLink("解绑", { removal = room }, enabled = !busy, arrow = false,
                                color = contentColor.copy(alpha = .82f))
                        }
                    }
                }
                Button(onClick = { adding = true }, enabled = !busy && state.bindingProjects.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("新增绑定") }
            } else {
                if (adding) DashboardLink("返回房间管理", { adding = false }, arrow = false)
                val projects = if (adding) state.bindingProjects else FeeProject.entries.filterNot { it.network }.toSet()
                val rows = listOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.Commercial)
                    .filter { it in projects || it == FeeProject.FamilyElectric && FeeProject.FamilyWater in projects }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = DashboardPalette.field)) {
                    rows.forEachIndexed { index, project ->
                        DashboardPreference(project.hallTitle, onClick = {
                            val target = if (project == FeeProject.FamilyElectric && project !in projects) FeeProject.FamilyWater else project
                            if (adding) onAdd(target) else onProject(target)
                        }, enabled = !busy)
                        if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                    }
                }
            }
        }
    }
    OverlayDialog(show = visible && removal != null, title = "解绑房间", summary = removal?.hallLabel,
        onDismissRequest = { removal = null }, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { removal = null }, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { removal?.let(onRemove); removal = null }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("解绑") }
        }
    }
}
