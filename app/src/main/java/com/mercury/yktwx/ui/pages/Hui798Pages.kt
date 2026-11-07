package com.mercury.yktwx.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.Hui798UiState
import com.mercury.yktwx.Hui798ViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*

@Composable
internal fun Hui798LoginSheet(state: Hui798UiState, model: Hui798ViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val keyboard = LocalSoftwareKeyboardController.current
    LifeAccountLoginSheet(show, if (state.changingAccount) "更换慧生活798账户" else "登录慧生活798", "hui798-login-sheet", { keyboard?.hide(); model.dismissLogin() }) {
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
            Box(Modifier.width(124.dp).height(56.dp)
                .testTag("hui798-refresh-captcha")
                .clickable(enabled = enabled && !display.captchaLoading, onClick = model::refreshCaptcha),
                contentAlignment = Alignment.Center) {
                when {
                    display.captchaLoading -> CircularProgressIndicator(Modifier.size(20.dp))
                    bitmap != null -> Image(bitmap.asImageBitmap(), "图形验证码，点击刷新", Modifier.fillMaxSize())
                    else -> Text("加载失败，点击重试", fontSize = 12.sp)
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
                Text(if (display.loggingIn) "正在登录…" else if (display.changingAccount) "登录并更换账户" else "登录并继续")
            }
        }
    }
}

@Composable
internal fun Hui798DevicePage(state: Hui798UiState, settings: ThemeSettings, model: Hui798ViewModel,
    active: Boolean, onScan: () -> Unit, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis) {
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    val phase = state.session?.phase ?: WaterPhase.Ready
    val querying = state.restoring || state.loadingDevice
    val ready = state.connected && state.deviceReady && !querying && state.error == null
    val title = when {
        state.restoring -> "正在恢复账号"
        state.loadingDevice -> "正在查询设备"
        !state.connected -> "登录后即可用水"
        state.error != null -> "暂未确认设备状态"
        ready -> "设备可用"
        else -> "等待查询设备状态"
    }
    val subtitle = when {
        state.restoring -> "请稍候"
        state.loadingDevice -> "正在确认设备是否可以用水"
        !state.connected -> "使用慧生活798账号继续"
        ready -> "核对设备编号后，点击下方开始用水；用完后点击结束用水"
        state.error != null -> null
        else -> "查询成功后即可开始用水"
    }
    val failed = state.error != null && phase in setOf(WaterPhase.Starting, WaterPhase.Stopping)
    val retryDevice = phase == WaterPhase.Ready && state.connected && !state.deviceReady && (state.device != null || state.scannedDeviceId != null)
    val loading = state.restoring || state.busy || state.loadingDevice || !failed && phase in setOf(WaterPhase.Starting, WaterPhase.Stopping)
    val label = when {
        state.restoring -> "正在加载…"
        state.loadingDevice -> "正在查询…"
        phase != WaterPhase.Ended && !state.connected -> "登录并继续"
        retryDevice && state.deviceInUse -> "刷新状态"
        retryDevice -> "重新查询"
        failed -> "刷新状态"
        phase == WaterPhase.Starting -> "正在启动…"
        phase == WaterPhase.Stopping -> "正在结束…"
        phase == WaterPhase.Running -> "结束用水"
        phase == WaterPhase.Ended -> "再次扫码用水"
        else -> "开始用水"
    }
    val primary = WaterDeviceAction(label, !loading && when {
        phase == WaterPhase.Ended -> true
        !state.connected -> true
        retryDevice -> true
        phase == WaterPhase.Ready -> state.deviceReady && state.device != null
        phase == WaterPhase.Running -> state.session?.sessionId in state.confirmedSessions
        else -> failed
    }) {
        when {
            phase == WaterPhase.Ended -> onScan()
            !state.connected -> model.connectAccount(false)
            retryDevice || failed -> model.refreshState()
            phase == WaterPhase.Running -> model.stopWater()
            else -> model.startWater()
        }
    }
    val session = state.session
    val device = session?.device ?: state.device
    val occupied = phase == WaterPhase.Ready && state.deviceInUse && !querying && state.connected && state.error == null
    val display = WaterDeviceUiState(LifeProvider.Hui798, device?.name.orEmpty(), deviceId = device?.id ?: state.scannedDeviceId,
        phase = phase, startedAtMillis = session?.startedAtMillis, endedAtMillis = session?.endedAtMillis,
        status = if (phase == WaterPhase.Ready && !occupied) WaterDeviceStatus(title, subtitle, querying) else null,
        error = state.error ?: "设备正在使用，请结束后刷新状态".takeIf { occupied })
    val refresh = WaterDeviceAction("刷新状态", state.connected && !state.busy && !state.loadingDevice && phase != WaterPhase.Ended, model::refreshState)
    WaterDevicePage(display, settings, active, primary,
        scanAction = WaterDeviceAction("重新扫码", !state.busy && !state.restoring, onScan), refreshAction = refresh,
        onBack = onBack, footerNote = display.feeNotice, nowMillis = nowMillis,
        loginAction = if (!state.restoring && !state.connected) WaterDeviceAction("登录并继续", true) { model.connectAccount(false) } else null,
        retryAction = refresh.takeIf { state.error != null && state.connected && phase == WaterPhase.Running }, showRescan = true)
}
