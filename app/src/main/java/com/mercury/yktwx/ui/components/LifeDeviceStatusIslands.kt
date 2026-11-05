package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.liquid.iosGlassSurface
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The B design: one light island per device, floating above the payment page's navigation. */
@Composable
internal fun LifeDeviceStatusIslands(waterSessions: List<WaterSession>, laundrySessions: List<LaundrySession>,
    onOpenSession: (LifeFeature, String) -> Unit, onDismiss: (LifeFeature, String) -> Unit,
    settings: ThemeSettings, modifier: Modifier = Modifier, enabled: Boolean = true,
    nowMillis: () -> Long = System::currentTimeMillis, additionalEntries: List<LifeDeviceStatusEntry> = emptyList()) {
    var now by remember { mutableLongStateOf(nowMillis()) }
    val ticking = enabled && laundrySessions.any { it.phase == LaundryPhase.Running && it.expectedEndMillis != null }
    LaunchedEffect(ticking, nowMillis) {
        while (ticking) { now = nowMillis(); delay(1000) }
    }
    val entries = buildList {
        waterSessions.filter { it.active }.forEach { session ->
            add(LifeDeviceStatusEntry(LifeFeature.Water, when (session.phase) {
                WaterPhase.Ended -> "已完成"
                WaterPhase.Starting -> "正在启动"
                WaterPhase.Stopping -> "正在结束"
                else -> "接水中"
            }, session.device.name.ifBlank { "接水" }, session.device.shopName,
                session.sessionId, completed = false))
        }
        laundrySessions.forEach { session ->
            val completed = !session.active
            val end = session.expectedEndMillis?.takeIf { session.phase == LaundryPhase.Running }
            val name = session.device.name.let { if (it.isNotBlank() && it.all(Char::isDigit)) "${it}号" else it }
            add(LifeDeviceStatusEntry(LifeFeature.Laundry, when (session.phase) {
                LaundryPhase.Ended -> "已完成"
                LaundryPhase.Closed -> "已关闭"
                LaundryPhase.Checking -> "正在检查"
                LaundryPhase.Creating -> "正在下单"
                LaundryPhase.Paying -> "待支付"
                LaundryPhase.Starting -> "正在启动"
                else -> "洗衣中"
            }, listOf(name, session.program).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "洗衣" }, session.device.shopName, session.sessionId, completed,
                remainingMinutes = end?.takeIf { it > now }?.let { (it - now + 59_999) / 60_000 },
                timeElapsed = end != null && end <= now,
                progress = if (completed) 1f else session.startedAtMillis?.let { start ->
                    end?.takeIf { it > start && it > now }?.let {
                        ((now - start).toFloat() / (it - start)).coerceIn(0f, .99f)
                    }
                }))
        }
        addAll(additionalEntries.filterNot { it.feature == LifeFeature.Water && it.completed })
    }
    StatusEntries(entries, enabled, onOpenSession, onDismiss, settings, modifier)
}

data class LifeDeviceStatusEntry(val feature: LifeFeature, val title: String, val subtitle: String,
    val location: String, val sessionId: String, val completed: Boolean,
    val remainingMinutes: Long? = null, val timeElapsed: Boolean = false, val progress: Float? = null,
    val onOpen: (() -> Unit)? = null, val onDismiss: (() -> Unit)? = null)

@Composable
private fun StatusEntries(entries: List<LifeDeviceStatusEntry>, enabled: Boolean,
    onOpen: (LifeFeature, String) -> Unit, onDismiss: (LifeFeature, String) -> Unit,
    settings: ThemeSettings, modifier: Modifier) {
    val ids = remember { mutableStateListOf<String>() }
    val displayedIds = (ids + entries.map { it.sessionId }).distinct()
    SideEffect { entries.forEach { if (it.sessionId !in ids) ids.add(it.sessionId) } }
    val scrollState = rememberScrollState()
    if (displayedIds.isEmpty()) return
    BoxWithConstraints(modifier.testTag("payment-life-status")) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val preferredWidth = minOf(166.dp * fontScale, (maxWidth - 8.dp).coerceAtLeast(0.dp))
        val pairWidth = (maxWidth / 2 - 8.dp).coerceAtLeast(0.dp)
        val targetWidth = if (displayedIds.size == 2 && pairWidth >= 144.dp * fontScale)
            minOf(preferredWidth, pairWidth) else preferredWidth
        val islandWidth by animateDpAsState(targetWidth,
            tween(ContentMotion.Layout, easing = ContentMotion.Easing), label = "life-island-width")
        Row(Modifier.fillMaxWidth().horizontalScroll(scrollState).testTag("life-island-scroll")
            .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            displayedIds.forEach { id -> key(id) {
                val entry = entries.firstOrNull { it.sessionId == id }
                val currentEntry by rememberUpdatedState(entry)
                val currentEnabled by rememberUpdatedState(enabled)
                val display = rememberWhileShown(entry != null, entry)
                val visibility = remember { MutableTransitionState(false) }
                visibility.targetState = entry != null
                LaunchedEffect(visibility.isIdle, visibility.currentState, entry) {
                    if (entry == null && visibility.isIdle && !visibility.currentState) ids.remove(id)
                }
                AnimatedVisibility(visibility,
                    enter = fadeIn(tween(ContentMotion.Append)) + expandHorizontally(
                        tween(ContentMotion.Insert, easing = ContentMotion.Easing), expandFrom = Alignment.CenterHorizontally),
                    exit = fadeOut(tween(ContentMotion.Fade)) + shrinkHorizontally(
                        tween(ContentMotion.Layout, easing = ContentMotion.Easing), shrinkTowards = Alignment.CenterHorizontally)) {
                    display?.let { displayed ->
                        // Both half-gaps shrink with their own island, avoiding a final spacing jump.
                        Box(Modifier.padding(horizontal = 4.dp)) {
                            StatusIsland(displayed, currentEnabled && currentEntry != null, islandWidth,
                                onOpen, onDismiss, settings)
                        }
                    }
                }
            } }
        }
    }
}

@Composable
private fun StatusIsland(entry: LifeDeviceStatusEntry, enabled: Boolean, width: Dp,
    onOpen: (LifeFeature, String) -> Unit, onDismiss: (LifeFeature, String) -> Unit, settings: ThemeSettings) {
    val dark = isSystemInDarkTheme()
    val liquid = settings.floatingBottomBar.value && settings.floatingBottomBarStyle.value == 1
    val surface = if (settings.floatingBottomBar.value) floatingBarColor() else MiuixTheme.colorScheme.surface
    val material = if (liquid) Modifier.iosGlassSurface(LocalBarBlurBackdrop.current
        .takeIf { settings.blurBottomBar.value }) else Modifier.barBlurBackground(CircleShape, backgroundColor = surface)
    val ink = if (dark) MiuixTheme.colorScheme.onSurface else Color(0xFF252A32)
    val muted = if (dark) DashboardPalette.muted else Color(0xFF939DAA)
    val accent = MiuixTheme.colorScheme.primary
    val panel = if (dark) DashboardPalette.iconPanel else Color(0xFFF1F6FF)
    val border = if (dark) ink.copy(alpha = .08f) else Color(0xFFE7EBF1)
    val detail = when {
        entry.title == "已关闭" -> "订单已关闭，查看详情"
        entry.completed -> "${entry.feature.title}已完成，查看详情"
        entry.remainingMinutes != null -> "预计剩余 ${entry.remainingMinutes} 分钟"
        entry.timeElapsed -> "预计时间已到，仍在读取设备状态"
        entry.title == "待支付" -> "继续支付"
        else -> "查看进度"
    }
    Surface(Modifier.width(width).testTag("life-session-${entry.sessionId}")
        .shadow(4.dp, CircleShape, ambientColor = Color(0xFF2B4161).copy(alpha = .08f),
            spotColor = Color(0xFF2B4161).copy(alpha = .08f)).then(material),
        shape = CircleShape, color = Color.Transparent, border = BorderStroke(.5.dp, border), contentColor = ink) {
        Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionSurface({ if (enabled) { entry.onOpen?.invoke() ?: onOpen(entry.feature, entry.sessionId) } },
                Modifier.weight(1f).testTag("life-status-${entry.feature.name.lowercase()}")
                    .semantics { contentDescription = listOf(entry.feature.title, entry.title,
                        entry.subtitle, entry.location, detail).filter { it.isNotBlank() }.joinToString("，") },
                enabled = enabled, shape = CircleShape, contentColor = ink) {
                Row(Modifier.fillMaxWidth().padding(start = 11.dp,
                    end = if (entry.completed) 0.dp else 8.dp, top = 9.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(26.dp).background(panel, CircleShape), contentAlignment = Alignment.Center) {
                        val animatedProgress by animateFloatAsState(entry.progress ?: 0f,
                            tween(ContentMotion.Number), label = "life-island-progress")
                        // Completion fills the ring and keeps the original device icon inside it.
                        if (entry.feature == LifeFeature.Laundry || entry.completed) {
                            CircularProgressIndicator(Modifier.size(22.dp).testTag("life-progress-${entry.sessionId}")
                                .semantics { contentDescription = if (entry.completed) "${entry.feature.title}已完成"
                                    else if (entry.progress != null) "洗衣预计时间进度" else "${entry.title}，查看进度" },
                                progress = if (entry.progress != null) animatedProgress else null,
                                strokeWidth = 1.5.dp, size = 22.dp,
                                colors = ProgressIndicatorDefaults.progressIndicatorColors(
                                    foregroundColor = accent, backgroundColor = accent.copy(alpha = .14f)))
                        }
                        Icon(if (entry.feature == LifeFeature.Water) AppIcons.Water else AppIcons.Laundry,
                            "${entry.feature.title}设备", Modifier.size(if (entry.feature == LifeFeature.Water && !entry.completed) 17.dp else 13.dp)
                                .testTag("life-device-icon-${entry.sessionId}"), tint = accent)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(entry.title, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.subtitle, fontSize = 10.sp, lineHeight = 14.sp,
                            color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!entry.completed) {
                        val remaining = entry.remainingMinutes
                        if (remaining != null) {
                            Box(Modifier.background(if (dark) panel else Color(0xFFF0F5FF), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                                UpdatedText("${remaining}分", fontSize = 12.sp, lineHeight = 16.sp,
                                    fontWeight = FontWeight.SemiBold, color = accent,
                                    identity = entry.sessionId, maxLines = 1)
                            }
                        } else if (entry.feature == LifeFeature.Water) {
                            Box(Modifier.padding(end = 6.dp).size(5.dp)
                                .background(accent.copy(alpha = .60f), CircleShape))
                        }
                    }
                }
            }
            if (entry.completed) ActionSurface({ if (enabled) { entry.onDismiss?.invoke() ?: onDismiss(entry.feature, entry.sessionId) } },
                Modifier.padding(end = 5.dp).size(30.dp).testTag("dismiss-life-session-${entry.sessionId}"),
                enabled = enabled, shape = CircleShape, contentColor = muted) {
                Box(Modifier.size(20.dp).background(if (dark) panel else Color(0xFFF5F6F8), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Close, "移除已完成的${entry.feature.title}", Modifier.size(12.dp), tint = muted)
                }
            }
        }
    }
}
