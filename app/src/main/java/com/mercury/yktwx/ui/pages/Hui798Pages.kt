package com.mercury.yktwx.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.Hui798UiState
import com.mercury.yktwx.Hui798ViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun Hui798LoginSheet(state: Hui798UiState, model: Hui798ViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val keyboard = LocalSoftwareKeyboardController.current
    LifeAccountLoginSheet(show, "登录慧生活798", "hui798-login-sheet", { keyboard?.hide(); model.dismissLogin() }) {
        val display = rememberWhileShown(show, state)
        val enabled = show && !display.loggingIn && !display.sendingCode
        Text("使用慧生活798账号扫码用水", fontSize = 13.sp, color = DashboardPalette.muted)
        DigitTextField(display.phone, model::phoneChanged, label = "手机号", maxDigits = 11, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("hui798-login-phone"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CaptchaTextField(display.imageCode, model::imageCodeChanged, label = "图形验证码", enabled = enabled && !display.captchaLoading,
                modifier = Modifier.weight(1f).testTag("hui798-image-code"))
            val bitmap = remember(display.captcha) { display.captcha?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            Button(onClick = model::refreshCaptcha, enabled = enabled, modifier = Modifier.width(124.dp).height(56.dp)
                .testTag("hui798-refresh-captcha")) {
                when {
                    display.captchaLoading -> CircularProgressIndicator(Modifier.size(20.dp))
                    bitmap != null -> Image(bitmap.asImageBitmap(), "图形验证码，点击刷新", Modifier.fillMaxSize())
                    else -> Text("刷新验证码")
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            DigitTextField(display.code, model::codeChanged, label = "短信验证码", maxDigits = 8, enabled = enabled,
                modifier = Modifier.weight(1f).testTag("hui798-login-code"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled) { keyboard?.hide(); model.login() } }))
            TextButton(if (display.sendingCode) "发送中…" else if (display.codeSeconds > 0) "${display.codeSeconds} 秒" else "发送验证码",
                model::sendCode, enabled = enabled && !display.captchaLoading && display.captcha != null && display.phone.length == 11 &&
                    display.imageCode.isNotBlank() && display.codeSeconds == 0, modifier = Modifier.testTag("hui798-send-code"))
        }
        InlineErrorGroup(display.loginError, 16.dp) {
            Button(onClick = { keyboard?.hide(); model.login() }, enabled = enabled && display.phone.length == 11 && display.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("hui798-login-submit"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (display.loggingIn) "正在登录…" else "登录并继续")
            }
        }
    }
}

@Composable
internal fun Hui798DevicePage(state: Hui798UiState, settings: ThemeSettings, model: Hui798ViewModel,
    active: Boolean, onScan: () -> Unit, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis) {
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    var help by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val phase = state.session?.phase ?: WaterPhase.Ready
    val title = when (phase) { WaterPhase.Ended -> "用水结束"; WaterPhase.Ready -> "扫码用水"; else -> "用水进度" }
    LifeDeviceShell(title, settings, false, scrollBehavior, onBack, { help = true }, true,
        footer = { Hui798Actions(state, model, active, onScan, onBack) }) { padding ->
        AnimatedContent(state, contentKey = { it.session?.phase ?: WaterPhase.Ready }, transitionSpec = {
            fadeIn(tween(200, delayMillis = 120)) togetherWith fadeOut(tween(120))
        }, label = "hui798-device-stage") { display ->
            LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)
                .testTag("hui798-device-page"), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                    bottom = padding.calculateBottomPadding() + 20.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!display.restoring && !display.connected) item {
                    InlineLifeLoginNotice(true, { model.connectAccount(false) }, enabled = active,
                        text = "登录慧生活798，继续扫码用水", tag = "hui798-device-login-notice")
                }
                val session = display.session
                if (session != null && session.phase != WaterPhase.Starting) {
                    item { LifeWaterProgress(session.phase, session.startedAtMillis, session.endedAtMillis, "用水", "hui798", nowMillis) }
                }
                item { Hui798DeviceIdentity(display) }
                if (session == null || session.phase == WaterPhase.Starting) item { Hui798DeviceStatus(display) }
                else {
                    if (session.phase != WaterPhase.Ended) item {
                        Text("用完请点击结束用水，离开页面不会停止设备。", Modifier.padding(horizontal = 4.dp),
                            fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                    }
                    if (display.error != null) item {
                        InlineErrorGroup(display.error, 12.dp) {
                            if (display.connected && session.phase == WaterPhase.Running) TextButton("刷新状态", model::refreshState,
                                enabled = active && !display.busy, modifier = Modifier.testTag("hui798-retry"))
                        }
                    }
                }
            }
        }
    }
    OverlayBottomSheet(show = active && help, startAction = { SheetTitle("用水帮助") }, onDismissRequest = { help = false }, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("扫码后核对设备，再点击开始用水。用完后点击结束用水，设备确认结束后可从付款页移除进度。离开页面不会自动停止设备；签约、绑定和费用请在慧生活798查看。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ help = false; onScan() }, Modifier.weight(1f), enabled = active && !state.busy) { Text("重新扫码") }
                Button({ help = false; model.refreshState() }, Modifier.weight(1f), enabled = active && state.connected && !state.busy &&
                    !state.loadingDevice && state.session?.phase != WaterPhase.Ended) { Text("刷新状态") }
            }
            Button({ help = false }, Modifier.fillMaxWidth(), enabled = active && help, colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}

@Composable
private fun Hui798DeviceIdentity(state: Hui798UiState) {
    Card(Modifier.fillMaxWidth().testTag("hui798-device-info"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LifeDeviceGlyph(LifeFeature.Water)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("慧生活798", fontSize = 12.sp, color = DashboardPalette.muted)
                    Text(state.device?.name.orEmpty().ifBlank { "饮水设备" }, Modifier.testTag("life-device-name"),
                        fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            val id = state.device?.id ?: state.scannedDeviceId
            if (id != null) {
                HorizontalDivider(Modifier.padding(top = 18.dp), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("设备编号", fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                    Text(id, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun Hui798DeviceStatus(state: Hui798UiState) {
    val starting = state.session?.phase == WaterPhase.Starting
    val querying = state.restoring || state.loadingDevice || starting && state.error == null
    val ready = state.connected && state.deviceReady && !querying && state.error == null
    val title = when {
        state.restoring -> "正在恢复账号"
        state.loadingDevice -> "正在查询设备"
        starting && state.error == null -> "正在启动设备"
        !state.connected -> "登录后即可用水"
        state.error != null -> "暂未确认设备状态"
        ready -> "设备可用"
        else -> "等待查询设备状态"
    }
    val subtitle = when {
        state.restoring -> "请稍候"
        state.loadingDevice -> "正在确认设备是否可以用水"
        starting -> "请等待设备响应，留意现场出水情况"
        !state.connected -> "使用慧生活798账号继续"
        ready -> "核对设备编号后，点击下方开始用水"
        state.error != null -> null
        else -> "查询成功后即可开始用水"
    }
    Card(Modifier.fillMaxWidth().testTag("hui798-device-status"), cornerRadius = 24.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (querying) CircularProgressIndicator(Modifier.size(18.dp).testTag("hui798-device-loading"))
                else Box(Modifier.size(7.dp).background(if (ready) lifeSuccessInk() else DashboardPalette.muted, CircleShape))
                Text(title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                    color = if (ready) lifeSuccessInk() else MiuixTheme.colorScheme.onSurface)
            }
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
            AnimatedInlineError(state.error, topSpacing = 12.dp)
        }
    }
}

@Composable
private fun Hui798Actions(state: Hui798UiState, model: Hui798ViewModel, active: Boolean, onScan: () -> Unit, onBack: () -> Unit) {
    val phase = state.session?.phase ?: WaterPhase.Ready
    val failed = state.error != null && phase in setOf(WaterPhase.Starting, WaterPhase.Stopping)
    val retryDevice = phase == WaterPhase.Ready && state.connected && !state.deviceReady && (state.device != null || state.scannedDeviceId != null)
    val loading = state.restoring || state.busy || state.loadingDevice || !failed && phase in setOf(WaterPhase.Starting, WaterPhase.Stopping)
    val label = when {
        state.restoring -> "正在加载…"
        state.loadingDevice -> "正在查询…"
        phase != WaterPhase.Ended && !state.connected -> "登录并继续"
        retryDevice -> "重新查询"
        failed -> "刷新状态"
        phase == WaterPhase.Starting -> "正在启动…"
        phase == WaterPhase.Stopping -> "正在结束…"
        phase == WaterPhase.Running -> "结束用水"
        phase == WaterPhase.Ended -> "再次扫码用水"
        else -> "开始用水"
    }
    LifeDeviceActionFooter("hui798-action-footer") {
        if (phase == WaterPhase.Ready || phase == WaterPhase.Ended)
            Text("费用可在慧生活798查询", fontSize = 12.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
        Button(onClick = {
            when {
                phase == WaterPhase.Ended -> onScan()
                !state.connected -> model.connectAccount(false)
                retryDevice || failed -> model.refreshState()
                phase == WaterPhase.Running -> model.stopWater()
                else -> model.startWater()
            }
        }, enabled = active && !loading && when {
            phase == WaterPhase.Ended -> true
            !state.connected -> true
            retryDevice -> true
            phase == WaterPhase.Ready -> state.deviceReady && state.device != null
            phase == WaterPhase.Running -> state.session?.sessionId in state.confirmedSessions
            else -> failed
        }, modifier = Modifier.fillMaxWidth().testTag("hui798-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) { Text(label) }
        if (phase == WaterPhase.Ready) DashboardLink("重新扫码", onScan, enabled = active && !state.busy && !state.restoring,
            modifier = Modifier.testTag("hui798-rescan"), arrow = false, color = DashboardPalette.muted)
        if (phase == WaterPhase.Ended) Button(onBack, Modifier.fillMaxWidth(), enabled = active) { Text("返回") }
    }
}
