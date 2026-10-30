package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    ReadLayout(Modifier.fillMaxWidth()) {
        TabRowWithContour(tabs = tabs, selectedTabIndex = selected, onTabSelected = onSelect,
            colors = TabRowDefaults.tabRowColors(backgroundColor = DashboardPalette.field,
                contentColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                selectedBackgroundColor = DashboardPalette.card, selectedContentColor = MiuixTheme.colorScheme.onSurface),
            indication = rememberPressFeedback(shape = RoundedCornerShape(TabRowDefaults.TabRowWithContourCornerRadius)))
    }
}

/** Match the native contour height and outer radius without creating hidden tabs or scroll state. */
@Composable
internal fun HallNeutralTabsPlaceholder() {
    ContentPlaceholder(Modifier.fillMaxWidth().height(TabRowDefaults.TabRowWithContourHeight),
        cornerRadius = TabRowDefaults.TabRowWithContourCornerRadius + 5.dp)
}

private enum class RoomSheetPage { Rooms, Temporary, Add }

private class RoomSheetSession(val initialPage: RoomSheetPage) {
    var page by mutableStateOf(initialPage)
}

@Composable
internal fun HallRoomSheet(state: DormUiState, visible: Boolean,
    onDismiss: () -> Unit, onSelect: (Int) -> Unit,
    onRemove: (DormRoom) -> Unit, onProject: (FeeProject) -> Unit, onAdd: (FeeProject) -> Unit,
    initialTab: Int = 0, initialAdding: Boolean = false) {
    val initialPage = when {
        initialAdding -> RoomSheetPage.Add
        initialTab == 1 -> RoomSheetPage.Temporary
        else -> RoomSheetPage.Rooms
    }
    var wasVisible by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(RoomSheetSession(initialPage)) }
    // Initialize before the first visible frame; retain the page throughout dismissal.
    if (visible && (!wasVisible || initialPage != session.initialPage)) session = RoomSheetSession(initialPage)
    wasVisible = visible
    var removal by remember(visible) { mutableStateOf<DormRoom?>(null) }
    val busy = state.managing
    OverlayBottomSheet(show = visible, onDismissRequest = onDismiss,
        startAction = {
            key(session) { SheetTitle(if (session.page == RoomSheetPage.Add) "新增绑定" else "切换房间 / 临时缴费") }
        },
        renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        key(session) { SheetPageContent(
            targetPage = session.page,
            modifier = Modifier.fillMaxWidth().testTag("hall-room-content").bottomSheetSafeArea(),
        ) { page, interactive ->
            val adding = page == RoomSheetPage.Add
            val enabled = visible && !busy && interactive
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!adding) HallNeutralTabs(listOf("房间管理", "临时缴费"),
                    if (page == RoomSheetPage.Temporary) 1 else 0) {
                    if (enabled) session.page = if (it == 1) RoomSheetPage.Temporary else RoomSheetPage.Rooms
                }
                if (page == RoomSheetPage.Rooms) {
                    InlineErrorGroup(state.error, 14.dp) {
                        ReadContent(state.rooms.isEmpty() && !state.roomsLoaded, Modifier.fillMaxWidth(), pending = { it }) { reading ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (reading) DashboardPlaceholder(14.sp, Modifier.fillMaxWidth().padding(vertical = 16.dp))
                                else if (state.rooms.isEmpty()) {
                                    if (state.roomsLoaded) Text("暂无绑定房间", Modifier.padding(vertical = 16.dp),
                                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                if (!reading) state.rooms.forEachIndexed { index, room ->
                                    PaymentSelectionCard(selected = index == state.selectedIndex, onClick = { onSelect(index) },
                                        enabled = enabled, modifier = Modifier.testTag("hall-room-$index")) { contentColor ->
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            UpdatedText(room.hallLabel, Modifier.weight(1f), numeric = false, fontSize = 16.sp,
                                                fontWeight = FontWeight.SemiBold, color = contentColor)
                                            DashboardLink("解绑", { removal = room }, enabled = enabled && !state.loading, arrow = false,
                                                color = contentColor.copy(alpha = .82f))
                                        }
                                    }
                                }
                            }
                        }
                        Button(onClick = { session.page = RoomSheetPage.Add }, enabled = enabled && !state.loading && state.bindingProjects.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("新增绑定") }
                    }
                } else {
                    val projects = if (adding) state.bindingProjects else FeeProject.entries.filterNot { it.network }.toSet()
                    val rows = listOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.Commercial)
                        .filter { it in projects || it == FeeProject.FamilyElectric && FeeProject.FamilyWater in projects }
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color = DashboardPalette.field)) {
                        rows.forEachIndexed { index, project ->
                            DashboardPreference(project.hallTitle, onClick = {
                                val target = if (project == FeeProject.FamilyElectric && project !in projects) FeeProject.FamilyWater else project
                                if (adding) onAdd(target) else onProject(target)
                            }, enabled = enabled && (!adding || !state.loading))
                            if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        } }
    }
    val removing = visible && removal != null
    val removalLabel = rememberWhileShown(removing, removal?.hallLabel)
    OverlayDialog(show = removing, title = "解绑房间", summary = removalLabel,
        onDismissRequest = { removal = null }, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { removal = null }, enabled = removing, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { removal?.let(onRemove); removal = null }, enabled = removing && !state.loading && !state.managing, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("解绑") }
        }
    }
}
