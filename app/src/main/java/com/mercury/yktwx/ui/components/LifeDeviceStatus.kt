package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One ongoing-service entry reused by the home capsule and the hall cards. */
@Composable
internal fun LifeDeviceStatusCards(water: WaterSession?, laundry: LaundrySession?, onOpen: (LifeFeature) -> Unit,
    modifier: Modifier = Modifier, compact: Boolean = false, enabled: Boolean = true,
    nowMillis: () -> Long = System::currentTimeMillis) {
    val activeWater = water?.takeIf { it.active }
    val activeLaundry = laundry?.takeIf { it.active }
    if (activeWater == null && activeLaundry == null) return
    var now by remember(laundry?.orderNo) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(activeLaundry?.phase, activeLaundry?.expectedEndMillis, nowMillis) {
        while (activeLaundry?.phase == LaundryPhase.Running && activeLaundry.expectedEndMillis != null) {
            now = nowMillis()
            delay(1000)
        }
    }
    val entries = buildList {
        activeWater?.let { session -> add(StatusEntry(LifeFeature.Water, when (session.phase) {
            WaterPhase.Starting -> "正在启动接水"
            WaterPhase.Stopping -> "正在结束接水"
            else -> "接水中"
        }, session.device, "查看进度")) }
        activeLaundry?.let { session -> add(StatusEntry(LifeFeature.Laundry, when (session.phase) {
            LaundryPhase.Checking -> "正在检查洗衣设备"
            LaundryPhase.Creating -> "正在创建洗衣订单"
            LaundryPhase.Paying -> "洗衣待支付"
            LaundryPhase.Starting -> "正在启动洗衣"
            else -> "洗衣中"
        }, session.device, if (session.phase == LaundryPhase.Paying) "继续支付" else
            session.expectedEndMillis?.takeIf { session.phase == LaundryPhase.Running }?.let { end ->
                if (end > now) "预计剩余 ${(end - now + 59_999) / 60_000} 分钟" else "预计时间已到"
            } ?: "查看进度")) }
    }
    if (compact) Card(modifier.fillMaxWidth().animateContentSize(tween(ContentMotion.Layout))
        .testTag("life-status-capsule"), cornerRadius = 18.dp) {
        Column {
            entries.forEachIndexed { index, entry ->
                StatusRow(entry, true, enabled, onOpen)
                if (index < entries.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
            }
        }
    } else Column(modifier.fillMaxWidth().testTag("hall-life-status"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEach { entry -> key(entry.feature) {
            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) { StatusRow(entry, false, enabled, onOpen) }
        } }
    }
}

private data class StatusEntry(val feature: LifeFeature, val title: String, val device: LifeDevice, val action: String)

@Composable
private fun StatusRow(entry: StatusEntry, compact: Boolean, enabled: Boolean, onOpen: (LifeFeature) -> Unit) {
    ActionSurface({ onOpen(entry.feature) }, Modifier.fillMaxWidth().testTag("life-status-${entry.feature.name.lowercase()}"),
        enabled = enabled, shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(if (compact) 34.dp else 42.dp).background(DashboardPalette.iconPanel, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center) {
                Icon(if (entry.feature == LifeFeature.Water) AppIcons.Water else AppIcons.Laundry, null,
                    Modifier.size(22.dp), tint = DashboardPalette.iconAccent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (compact) entry.action else listOf(entry.device.name, entry.device.shopName)
                    .filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp, lineHeight = 17.sp,
                    color = DashboardPalette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!compact) Text(entry.action, fontSize = 11.sp, lineHeight = 17.sp, color = MiuixTheme.colorScheme.primary)
            }
            Icon(MiuixIcons.ChevronForward, null, Modifier.size(16.dp), tint = DashboardPalette.muted)
        }
    }
}

/** Measures the native capsule so page content always scrolls clear of both service rows. */
@Composable
internal fun LifeDeviceStatusHost(water: WaterSession?, laundry: LaundrySession?, bottomPadding: Dp,
    visible: Boolean, onOpen: (LifeFeature) -> Unit, modifier: Modifier = Modifier,
    nowMillis: () -> Long = System::currentTimeMillis,
    content: @Composable (extraBottom: Dp) -> Unit) {
    val hasService = water?.active == true || laundry?.active == true
    var height by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val extra = if (visible && hasService) with(density) { height.toDp() } + 12.dp else 0.dp
    // Retain the last valid sessions throughout the exit animation.
    val displayed = rememberWhileShown(visible && hasService, water to laundry)
    Box(modifier.fillMaxSize()) {
        content(extra)
        AnimatedVisibility(visible && hasService, Modifier.align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPadding + 12.dp),
            enter = fadeIn(tween(ContentMotion.Append)) + slideInVertically(tween(ContentMotion.Insert)) { it / 4 },
            exit = fadeOut(tween(ContentMotion.Fade)) + slideOutVertically(tween(ContentMotion.Layout)) { it / 4 }) {
            LifeDeviceStatusCards(displayed.first, displayed.second, onOpen,
                Modifier.onSizeChanged { height = it.height }, compact = true, enabled = visible && hasService, nowMillis = nowMillis)
        }
    }
}
