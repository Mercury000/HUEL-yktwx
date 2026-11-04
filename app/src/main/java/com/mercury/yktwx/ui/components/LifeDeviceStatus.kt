package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Session entries reused by the home capsule and the hall history cards. */
@Composable
internal fun LifeDeviceStatusCards(water: WaterSession?, laundry: LaundrySession?, onOpen: (LifeFeature) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false, enabled: Boolean = true,
    nowMillis: () -> Long = System::currentTimeMillis,
    waterSessions: List<WaterSession> = listOfNotNull(water), laundrySessions: List<LaundrySession> = listOfNotNull(laundry),
    onOpenSession: (LifeFeature, String) -> Unit = { feature, _ -> onOpen(feature) },
    onDismiss: (LifeFeature, String) -> Unit = { _, _ -> }) {
    val waters = waterSessions.filter { it.active || !compact && it.phase == WaterPhase.Ended }
    val laundries = laundrySessions.filter { it.active || !compact && it.phase == LaundryPhase.Ended }
    var now by remember { mutableLongStateOf(nowMillis()) }
    val ticking = laundries.any { it.phase == LaundryPhase.Running && it.expectedEndMillis != null }
    LaunchedEffect(ticking, nowMillis) {
        while (ticking) {
            now = nowMillis()
            delay(1000)
        }
    }
    val entries = buildList {
        waters.forEach { session -> add(StatusEntry(LifeFeature.Water, when (session.phase) {
            WaterPhase.Ended -> "接水已结束"
            WaterPhase.Starting -> "正在启动接水"
            WaterPhase.Stopping -> "正在结束接水"
            else -> "接水中"
        }, session.device, if (session.phase == WaterPhase.Ended) "查看详情" else "查看进度",
            sessionId = session.sessionId, completed = session.phase == WaterPhase.Ended, dismissible = session.finalized)) }
        laundries.forEach { session -> add(StatusEntry(LifeFeature.Laundry, when (session.phase) {
            LaundryPhase.Ended -> "洗衣已完成"
            LaundryPhase.Checking -> "正在检查洗衣设备"
            LaundryPhase.Creating -> "正在创建洗衣订单"
            LaundryPhase.Paying -> "洗衣待支付"
            LaundryPhase.Starting -> "正在启动洗衣"
            else -> "洗衣中"
        }, session.device, if (session.phase == LaundryPhase.Ended) "查看详情" else if (session.phase == LaundryPhase.Paying) "继续支付" else
            session.expectedEndMillis?.takeIf { session.phase == LaundryPhase.Running }?.let { end ->
                if (end > now) "预计剩余 ${(end - now + 59_999) / 60_000} 分钟" else "预计时间已到"
            } ?: "查看进度", remainingMinutes = session.expectedEndMillis
                ?.takeIf { session.phase == LaundryPhase.Running && it > now }?.let { (it - now + 59_999) / 60_000 },
            progress = session.startedAtMillis?.let { start -> session.expectedEndMillis
                ?.takeIf { session.phase == LaundryPhase.Running && it > start && it > now }?.let { end ->
                    ((now - start).toFloat() / (end - start)).coerceIn(0f, .99f)
                } }, sessionId = session.sessionId, completed = session.phase == LaundryPhase.Ended,
            dismissible = session.phase == LaundryPhase.Ended)) }
    }
    if (compact) Card(modifier.fillMaxWidth().animateContentSize(tween(ContentMotion.Layout))
        .testTag("life-status-capsule"), cornerRadius = 18.dp) {
        StatusEntries(entries, true, enabled, onOpenSession, onDismiss)
    } else StatusEntries(entries, false, enabled, onOpenSession, onDismiss, modifier.fillMaxWidth())
}

private data class StatusEntry(val feature: LifeFeature, val title: String, val device: LifeDevice, val action: String,
    val remainingMinutes: Long? = null, val progress: Float? = null, val sessionId: String,
    val completed: Boolean = false, val dismissible: Boolean = false)

@Composable
private fun StatusEntries(entries: List<StatusEntry>, compact: Boolean, enabled: Boolean,
    onOpen: (LifeFeature, String) -> Unit, onDismiss: (LifeFeature, String) -> Unit, modifier: Modifier = Modifier) {
    val ids = remember { mutableStateListOf<String>().apply { addAll(entries.map { it.sessionId }) } }
    val displayedIds = (ids + entries.map { it.sessionId }).distinct()
    SideEffect { entries.forEach { if (it.sessionId !in ids) ids.add(it.sessionId) } }
    Column(modifier.then(if (compact) Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())
        else if (displayedIds.isNotEmpty()) Modifier.testTag("hall-life-status") else Modifier)) {
        displayedIds.forEach { id -> key(id) {
            val entry = entries.firstOrNull { it.sessionId == id }
            val display = rememberWhileShown(entry != null, entry)
            val visibility = remember { MutableTransitionState(compact) }
            visibility.targetState = entry != null
            LaunchedEffect(visibility.isIdle, visibility.currentState, entry) {
                if (entry == null && visibility.isIdle && !visibility.currentState) ids.remove(id)
            }
            val gap by animateDpAsState(if (!compact && entry != null) if (entries.lastOrNull()?.sessionId == id) 14.dp else 10.dp else 0.dp,
                tween(ContentMotion.Layout), label = "life-status-gap")
            AnimatedVisibility(visibility,
                enter = fadeIn(tween(ContentMotion.Append)) + expandVertically(tween(ContentMotion.Insert, easing = ContentMotion.Easing)),
                exit = fadeOut(tween(ContentMotion.Fade)) + shrinkVertically(tween(ContentMotion.Layout, easing = ContentMotion.Easing))) {
                display?.let { displayed ->
                    if (compact) Column {
                        StatusRow(displayed, true, enabled && entry != null, onOpen, onDismiss)
                        if (entry != null && entries.lastOrNull()?.sessionId != id) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                    } else Card(Modifier.fillMaxWidth().padding(bottom = gap).animateContentSize(tween(ContentMotion.Layout)), cornerRadius = 24.dp) {
                        StatusRow(displayed, false, enabled && entry != null, onOpen, onDismiss)
                    }
                }
            }
        } }
    }
}

@Composable
private fun StatusRow(entry: StatusEntry, compact: Boolean, enabled: Boolean,
    onOpen: (LifeFeature, String) -> Unit, onDismiss: (LifeFeature, String) -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("life-session-${entry.sessionId}"), verticalAlignment = Alignment.CenterVertically) {
    ActionSurface({ onOpen(entry.feature, entry.sessionId) }, Modifier.weight(1f).testTag("life-status-${entry.feature.name.lowercase()}"),
        enabled = enabled, shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 20.dp, vertical = if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(if (compact) 34.dp else 42.dp).background(DashboardPalette.iconPanel, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(if (entry.feature == LifeFeature.Water) AppIcons.Water else AppIcons.Laundry, null,
                    Modifier.size(22.dp), tint = DashboardPalette.iconAccent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (compact) listOf(entry.device.name, entry.action).filter { it.isNotBlank() }.joinToString(" · ") else listOf(entry.device.name, entry.device.shopName)
                    .filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp, lineHeight = 17.sp,
                    color = DashboardPalette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (compact) Icon(MiuixIcons.ChevronForward, null, Modifier.size(16.dp), tint = DashboardPalette.muted)
            else entry.remainingMinutes?.let { remaining ->
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        UpdatedText(remaining.toString(), fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.primary, identity = entry.device.id)
                        Text("分钟", Modifier.padding(bottom = 3.dp), fontSize = 11.sp, color = DashboardPalette.muted)
                    }
                    Text("预计剩余", fontSize = 10.sp, color = DashboardPalette.muted)
                }
            } ?: Text(entry.action, Modifier.widthIn(max = 112.dp), fontSize = 12.sp, lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.primary)
        }
        if (!compact && !entry.completed) {
            val progress by animateFloatAsState(entry.progress ?: 0f, tween(ContentMotion.Number), label = "life-status-progress")
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("life-status-progress-${entry.feature.name.lowercase()}")
                .semantics { contentDescription = if (entry.progress == null) "${entry.title}，查看进度" else "洗衣预计时间进度" },
                progress = if (entry.progress != null) progress else null, height = 3.dp)
        }
        }
    }
    if (!compact && entry.completed && entry.dismissible) IconButton(onClick = { if (enabled) onDismiss(entry.feature, entry.sessionId) },
        modifier = Modifier.padding(end = 8.dp).testTag("dismiss-life-session-${entry.sessionId}")) {
        Icon(AppIcons.Close, "移除已完成的${entry.feature.title}", Modifier.size(16.dp), tint = DashboardPalette.muted)
    }
    }
}

/** Measures the capsule and bounds its height while keeping every running order reachable. */
@Composable
internal fun LifeDeviceStatusHost(water: WaterSession?, laundry: LaundrySession?, bottomPadding: Dp,
    visible: Boolean, onOpen: (LifeFeature) -> Unit, modifier: Modifier = Modifier,
    nowMillis: () -> Long = System::currentTimeMillis,
    waterSessions: List<WaterSession> = listOfNotNull(water), laundrySessions: List<LaundrySession> = listOfNotNull(laundry),
    onOpenSession: (LifeFeature, String) -> Unit = { feature, _ -> onOpen(feature) },
    content: @Composable (extraBottom: Dp) -> Unit) {
    val hasService = waterSessions.any { it.active } || laundrySessions.any { it.active }
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val extra = if (visible && hasService) with(density) { height.toDp() } + 12.dp else 0.dp
    // Retain the last valid sessions throughout the exit animation.
    val displayed = rememberWhileShown(visible && hasService, waterSessions to laundrySessions)
    Box(modifier.fillMaxSize()) {
        content(extra)
        AnimatedVisibility(visible && hasService, Modifier.align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
            enter = fadeIn(tween(ContentMotion.Append)) + slideInVertically(tween(ContentMotion.Insert)) { it / 4 },
            exit = fadeOut(tween(ContentMotion.Fade)) + slideOutVertically(tween(ContentMotion.Layout)) { it / 4 }) {
            LifeDeviceStatusCards(null, null, onOpen,
                Modifier.onSizeChanged { height = it.height }, compact = true, enabled = visible && hasService, nowMillis = nowMillis,
                waterSessions = displayed.first, laundrySessions = displayed.second, onOpenSession = onOpenSession)
        }
    }
}
