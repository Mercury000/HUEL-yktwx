package com.mercury.yktwx.ui.pages

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.data.life.WaterPhase
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import java.util.Locale

/** Presentation only: each provider keeps its own account, commands and confirmation rules. */
internal data class WaterDeviceUiState(
    val provider: LifeProvider, val name: String, val location: String = "", val deviceId: String? = null,
    val phase: WaterPhase = WaterPhase.Ready, val startedAtMillis: Long? = null, val endedAtMillis: Long? = null,
    val paymentMethod: String? = null, val supportsFinish: Boolean = true,
    val status: WaterDeviceStatus? = null, val error: String? = null,
) {
    val action: String get() = if (provider == LifeProvider.Panggui) "接水" else "用水"
    val providerName: String get() = if (provider == LifeProvider.Panggui) "胖乖生活" else "慧生活798"
    val tag: String get() = if (provider == LifeProvider.Panggui) "water" else "hui798"
    val feeNotice: String get() = "费用可在" + if (provider == LifeProvider.Panggui) "胖乖生活或支付宝查询" else "慧生活798查询"
    val feeDetail: String get() = if (provider == LifeProvider.Panggui) "支付宝免密后付，实际费用与扣款情况请查看对应记录。"
        else "实际费用与扣款情况请在慧生活798查看。"
    val help: String get() = if (provider == LifeProvider.Panggui)
        "扫码后核对饮水设备，放稳容器再开始。接好后点击结束接水；离开页面不会自动停止设备。费用请在胖乖生活或支付宝查询。"
        else "扫码后核对生活用水设备，再点击开始用水。用完后点击结束用水；离开页面不会自动停止设备。签约、绑定和费用请在慧生活798查看。"
}

internal data class WaterDeviceStatus(val title: String, val detail: String?, val loading: Boolean = false)
internal data class WaterDeviceAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

private val WaterPhase.pageStage: String get() = when (this) {
    WaterPhase.Ended -> "ended"
    WaterPhase.Running, WaterPhase.Stopping -> "running"
    else -> "ready"
}

private data class WaterDevicePresentation(val state: WaterDeviceUiState, val paymentContent: (@Composable (Boolean) -> Unit)?)

/** Both drinking water and domestic water use this complete page and its stage transitions. */
@Composable
internal fun WaterDevicePage(state: WaterDeviceUiState, settings: ThemeSettings, active: Boolean,
    primaryAction: WaterDeviceAction, scanAction: WaterDeviceAction, refreshAction: WaterDeviceAction,
    onBack: () -> Unit, footerNote: String, nowMillis: () -> Long = System::currentTimeMillis,
    loginAction: WaterDeviceAction? = null, retryAction: WaterDeviceAction? = null, showRescan: Boolean = false,
    paymentContent: (@Composable (Boolean) -> Unit)? = null) {
    var help by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val title = when (state.phase) {
        WaterPhase.Ended -> "${state.action}结束"
        WaterPhase.Running, WaterPhase.Stopping -> "${state.action}进度"
        else -> "扫码${state.action}"
    }
    LifeDeviceShell(title, settings, false, scrollBehavior, onBack, { help = true }, true,
        footer = { WaterDeviceActions(state, active, primaryAction, scanAction, onBack, footerNote, showRescan) }) { padding ->
        AnimatedContent(WaterDevicePresentation(state, paymentContent), contentKey = { it.state.phase.pageStage },
            modifier = Modifier.fillMaxSize(), transitionSpec = {
            ((fadeIn(tween(ContentMotion.Append, delayMillis = 120)) +
                slideInVertically(tween(ContentMotion.Append, delayMillis = 120, easing = ContentMotion.Easing)) { it / 45 })
                togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
        }, label = "water-device-stage") { presentation ->
            val display = presentation.state
            val payment = presentation.paymentContent
            val stageActive = active && display.phase.pageStage == state.phase.pageStage
            LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)
                .testTag(if (display.provider == LifeProvider.Panggui) "life-device-page" else "hui798-device-page"),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 20.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (loginAction != null) item {
                    InlineLifeLoginNotice(true, loginAction.onClick, enabled = stageActive && loginAction.enabled,
                        text = "登录${display.providerName}，继续扫码${display.action}", tag = "${display.tag}-device-login-notice")
                }
                when (display.phase) {
                    WaterPhase.Ended -> {
                        item { LifeWaterProgress(display.phase, display.startedAtMillis, display.endedAtMillis, display.action, display.tag, nowMillis) }
                        item { WaterFeeNotice(display) }
                        item { WaterDeviceFacts(display) }
                    }
                    WaterPhase.Running, WaterPhase.Stopping -> {
                        item { LifeWaterProgress(display.phase, display.startedAtMillis, display.endedAtMillis, display.action, display.tag, nowMillis) }
                        item { WaterDeviceFacts(display) }
                        if (payment != null) item { payment(false) }
                        item { WaterUsageNotice(display) }
                    }
                    else -> {
                        item { LifeDeviceHero(display.name.ifBlank {
                            if (display.provider == LifeProvider.Panggui) "饮水设备" else "生活用水设备"
                        }, display.location, LifeFeature.Water) }
                        if (payment != null) item { payment(stageActive) }
                        else item { WaterDeviceFacts(display) }
                        item { WaterUsageNotice(display) }
                    }
                }
                item { InlineErrorGroup(display.error, 12.dp) {
                    if (retryAction != null) TextButton(retryAction.label, retryAction.onClick,
                        enabled = stageActive && retryAction.enabled, modifier = Modifier.testTag("${display.tag}-retry"))
                } }
            }
        }
    }
    OverlayBottomSheet(show = active && help, startAction = { SheetTitle("${state.action}帮助") },
        onDismissRequest = { help = false }, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(state.help, fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ help = false; scanAction.onClick() }, Modifier.weight(1f), enabled = active && scanAction.enabled) { Text(scanAction.label) }
                Button({ help = false; refreshAction.onClick() }, Modifier.weight(1f), enabled = active && refreshAction.enabled) { Text(refreshAction.label) }
            }
            Button({ help = false }, Modifier.fillMaxWidth(), enabled = active && help, colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}

@Composable
private fun WaterDeviceFacts(state: WaterDeviceUiState) {
    val rows = buildList {
        if (state.phase in setOf(WaterPhase.Running, WaterPhase.Stopping, WaterPhase.Ended) && state.name.isNotBlank()) add("设备" to state.name)
        if (state.location.isNotBlank()) add("位置" to state.location)
        state.deviceId?.let { add("设备编号" to it) }
        if (state.phase == WaterPhase.Ended) {
            state.startedAtMillis?.let { start -> state.endedAtMillis?.let { end -> add("本地参考时长" to waterDuration(end - start)) } }
        }
        if (state.phase == WaterPhase.Ended && state.paymentMethod != null) add("支付方式" to state.paymentMethod)
        else add("服务提供方" to state.providerName)
    }
    Card(Modifier.fillMaxWidth().testTag(if (state.provider == LifeProvider.Panggui) "life-device-info" else "hui798-device-info"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                LifeDeviceField(label) { Text(value, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End) }
                if (index < rows.lastIndex) HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
            }
        }
    }
}

@Composable
private fun WaterUsageNotice(state: WaterDeviceUiState) {
    val running = state.phase in setOf(WaterPhase.Running, WaterPhase.Stopping)
    val status = state.status ?: if (state.phase == WaterPhase.Starting) WaterDeviceStatus(
        if (state.error == null) "正在启动设备" else "暂未确认设备状态",
        "请等待设备响应，留意现场出水情况", state.error == null) else null
    Card(Modifier.fillMaxWidth().testTag("${state.tag}-usage-notice"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (status?.loading == true) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp,
                    modifier = Modifier.testTag("${state.tag}-device-loading"))
                Text(status?.title ?: when {
                    !state.supportsFinish -> "请按设备说明操作"
                    running -> if (state.provider == LifeProvider.Panggui) "接好后及时结束" else "用完后及时结束"
                    else -> if (state.provider == LifeProvider.Panggui) "接好后，点结束接水" else "用完后，点结束用水"
                }, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            val detail = if (status != null) status.detail else if (running)
                "离开此页不会自动结束${state.action}，可从付款页状态卡返回${state.action}进度。"
                else (if (state.provider == LifeProvider.Panggui) "请先放稳容器。" else "开始前请核对生活用水设备。") + state.feeNotice + "。"
            if (detail != null) Text(detail, fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
        }
    }
}

@Composable
private fun WaterFeeNotice(state: WaterDeviceUiState) {
    Card(Modifier.fillMaxWidth().testTag("${state.tag}-fee-notice"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(AppIcons.Bill, null, Modifier.size(17.dp), tint = MiuixTheme.colorScheme.primary)
                Text(state.feeNotice, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(state.feeDetail, fontSize = 11.sp, lineHeight = 19.sp, color = DashboardPalette.muted)
        }
    }
}

@Composable
private fun WaterDeviceActions(state: WaterDeviceUiState, active: Boolean, primary: WaterDeviceAction,
    scan: WaterDeviceAction, onBack: () -> Unit, footerNote: String, showRescan: Boolean) {
    LifeDeviceActionFooter("${state.tag}-action-footer") {
        Text(footerNote, fontSize = 12.sp, lineHeight = 17.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
        if (state.phase == WaterPhase.Running && !state.supportsFinish) {
            Text("请按设备说明结束${state.action}", Modifier.fillMaxWidth(), fontSize = 13.sp, textAlign = TextAlign.Center, color = DashboardPalette.muted)
        } else Button(primary.onClick, enabled = active && primary.enabled,
            modifier = Modifier.fillMaxWidth().testTag("${state.tag}-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
            AnimatedContent(primary.label, transitionSpec = { fadeIn(tween(120, delayMillis = 100)) togetherWith fadeOut(tween(100)) },
                label = "water-button-label") { Text(it) }
        }
        if (showRescan && state.phase == WaterPhase.Ready) DashboardLink(scan.label, scan.onClick,
            enabled = active && scan.enabled, modifier = Modifier.testTag("${state.tag}-rescan"), arrow = false, color = DashboardPalette.muted)
        if (state.phase == WaterPhase.Ended) Button(onBack, Modifier.fillMaxWidth(), enabled = active) { Text("返回") }
    }
}

private fun waterDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
internal fun LifeWaterProgress(phase: WaterPhase, startedAt: Long?, endedAt: Long?, action: String, tag: String,
    nowMillis: () -> Long = System::currentTimeMillis) {
    var now by remember(startedAt) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(phase, startedAt, nowMillis) {
        while (phase != WaterPhase.Ended) { now = nowMillis(); delay(1000) }
    }
    val ended = phase == WaterPhase.Ended
    Column(Modifier.fillMaxWidth().padding(top = if (ended) 30.dp else 36.dp, bottom = 24.dp).testTag("$tag-progress"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (ended) {
            DeviceCompletedIcon("$tag-ended-icon")
            Text("${action}已结束", Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            Text("设备已结束本次${action}", Modifier.padding(top = 7.dp), fontSize = 12.sp, color = DashboardPalette.muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(lifeSuccessInk(), CircleShape))
                Text(if (phase == WaterPhase.Stopping) "正在结束" else "${action}中", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            startedAt?.let { started ->
                Text(waterDuration((endedAt ?: now) - started), Modifier.padding(top = 22.dp, bottom = 9.dp).testTag("$tag-elapsed"),
                    fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold)
                Text("本地参考计时", fontSize = 12.sp, color = DashboardPalette.muted)
            }
            Text("费用以实际扣款记录为准", Modifier.padding(top = 3.dp), fontSize = 10.sp, color = DashboardPalette.muted)
        }
    }
}
