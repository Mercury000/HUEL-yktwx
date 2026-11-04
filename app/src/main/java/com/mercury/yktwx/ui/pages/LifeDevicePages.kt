package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.LifeDevicesUiState
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.Locale

/** Both feature entries use this account drawer; closing it leaves the account disconnected. */
@Composable
internal fun LifeDeviceLoginSheet(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val session = rememberOverlaySession(show)
    val keyboard = LocalSoftwareKeyboardController.current
    OverlayBottomSheet(show = show, startAction = { SheetTitle("登录胖乖生活") },
        onDismissRequest = { keyboard?.hide(); model.dismissLogin() }, renderInRootScaffold = true) {
        key(session) {
            val display = rememberWhileShown(show, state)
            val enabled = show && !display.loggingIn && !display.sendingCode
            Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState())
                .testTag("life-login-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("接水与洗衣共用此账号", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                DigitTextField(display.phone, model::phoneChanged, label = "手机号", maxDigits = 11,
                    enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("life-login-phone"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DigitTextField(display.code, model::codeChanged, label = "验证码", maxDigits = 8,
                        enabled = enabled, modifier = Modifier.weight(1f).testTag("life-login-code"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (enabled) { keyboard?.hide(); model.login() } }))
                    TextButton(if (display.sendingCode) "发送中…" else if (display.codeSeconds > 0) "${display.codeSeconds} 秒" else "发送验证码",
                        model::sendCode, enabled = enabled && display.phone.length == 11 && display.codeSeconds == 0,
                        modifier = Modifier.testTag("life-send-code"))
                }
                InlineErrorGroup(display.loginError, 16.dp) {
                    Button(onClick = { keyboard?.hide(); model.login() }, enabled = enabled && display.phone.length == 11 && display.code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("life-login-submit"), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(if (display.loggingIn) "正在登录…" else "登录并继续")
                    }
                }
            }
        }
    }
}

private data class LifeDevicePresentation(val stage: String, val state: LifeDevicesUiState)

/** Shared page container, spacing and device presentation for both features. */
@Composable
internal fun LifeDevicePage(state: LifeDevicesUiState, settings: ThemeSettings, model: LifeDevicesViewModel,
    active: Boolean, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis) {
    var serial by rememberSaveable(state.entryRequest) { mutableStateOf("") }
    var manual by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    var help by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    LaunchedEffect(state.scanning, active) { if (!state.scanning || !active) { manual = false; help = false } }
    val scanning = state.scanning || state.loadingDevice && state.device == null
    val view = LocalView.current
    if (scanning && active) DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            oldStatus?.let { controller.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    val title = when {
        scanning -> "扫一扫"
        state.feature == LifeFeature.Laundry && state.laundry?.phase == LaundryPhase.Ended -> "洗衣完成"
        state.feature == LifeFeature.Laundry && state.laundry?.phase == LaundryPhase.Closed -> "洗衣订单"
        state.feature == LifeFeature.Laundry && state.laundry != null -> "洗衣进度"
        state.feature == LifeFeature.Laundry -> "扫码洗衣"
        state.water?.phase == WaterPhase.Ended -> "接水结束"
        state.water?.phase in setOf(WaterPhase.Running, WaterPhase.Stopping) -> "接水进度"
        else -> "扫码接水"
    }
    LifeDeviceShell(title, settings, scanning, onBack, onHelp = { help = true }, showHelp = true,
        footer = {
            if (state.feature == LifeFeature.Water && !scanning) WaterActions(state, model, active, onBack)
            if (state.feature == LifeFeature.Laundry && !scanning) LaundryActions(state, model, active, onBack, nowMillis)
        }) { padding ->
        val stage = when {
            scanning -> "scan"
            state.feature == LifeFeature.Laundry -> "laundry-${when (state.laundry?.phase) {
                null -> "ready"
                LaundryPhase.Running -> "running"
                LaundryPhase.Ended -> "ended"
                LaundryPhase.Closed -> "closed"
                else -> "processing"
            }}"
            state.water?.phase == WaterPhase.Ended -> "water-ended"
            state.water?.phase in setOf(WaterPhase.Running, WaterPhase.Stopping) -> "water-running"
            else -> "water-ready"
        }
        AnimatedContent(LifeDevicePresentation(stage, state.copy(code = "", paymentLaunch = null)),
            contentKey = { it.stage }, modifier = Modifier.fillMaxSize(), transitionSpec = {
            ((fadeIn(tween(ContentMotion.Append, delayMillis = 120)) +
                slideInVertically(tween(ContentMotion.Append, delayMillis = 120, easing = ContentMotion.Easing)) { it / 45 })
                togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
        }, label = "life-device-stage") { presentation ->
        val displayedStage = presentation.stage
        val state = presentation.state
        val stageActive = active && displayedStage == stage
        if (displayedStage == "scan") Box(Modifier.fillMaxSize().testTag("life-device-page")) {
            DeviceScanner(stageActive, { code -> if (!manual && !help && !state.loadingDevice) model.scanned(code) }, immersive = true,
                onManualInput = { manual = true })
            if (state.loadingDevice) Text("正在识别设备…", Modifier.align(Alignment.Center), fontSize = 14.sp, color = Color.White)
            if (!state.error.isNullOrBlank() && !manual) Text(state.error,
                Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding() + 16.dp, start = 24.dp, end = 24.dp),
                fontSize = 12.sp, lineHeight = 20.sp, color = Color.White, textAlign = TextAlign.Center)
        } else LazyColumn(Modifier.fillMaxSize().overScrollVertical().testTag("life-device-page"),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 20.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.feature == LifeFeature.Laundry) {
                laundryContent(state, model, stageActive, nowMillis)
            } else {
                val water = state.water
                val ended = water?.phase == WaterPhase.Ended
                val progress = water?.phase in setOf(WaterPhase.Running, WaterPhase.Stopping)
                if (ended) {
                    val session = checkNotNull(water)
                    item { WaterProgress(session, nowMillis) }
                    item { WaterFeeNotice() }
                    item { LifeDeviceFacts(session.device, session) }
                } else if (progress) {
                    val session = checkNotNull(water)
                    item { WaterProgress(session, nowMillis) }
                    item { LifeDeviceFacts(session.device) }
                    item { LifePaymentCard(session.device) }
                    item { WaterUsageNotice(session.device, running = true) }
                } else {
                    item { LifeDeviceHeading("确认接水") }
                    state.device?.let { device ->
                        item { LifeDeviceHero(device, state.feature) }
                        item { LifePaymentCard(device, showOptions = true, state = state,
                            onSelect = model::selectSku, enabled = stageActive && !state.waterBusy && !state.loadingDevice) }
                        item { WaterUsageNotice(device) }
                    }
                }
                item { AnimatedInlineError(state.error) }
            }
        }
        }
    }
    LifeManualDeviceSheet(active && manual && state.scanning, state, serial,
        onValue = { serial = it },
        onDismiss = { manual = false; keyboard?.hide() }, onIdentify = { keyboard?.hide(); model.scanned(serial) })
    LifeDeviceHelpSheet(active && help, state, model, onDismiss = { help = false })
    LaundryPaymentSheet(active && state.paymentVisible, state, model, nowMillis)
}

@Composable
private fun LifeDeviceShell(title: String, settings: ThemeSettings, scanning: Boolean, onBack: () -> Unit,
    onHelp: () -> Unit, showHelp: Boolean, footer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val surface = if (scanning) Color(0xFF1B2025) else MiuixTheme.colorScheme.surface
    val ink = if (scanning) Color.White else MiuixTheme.colorScheme.onSurface
    BarBlurHost(false, settings.blurTopBar.value && !scanning) {
        Scaffold(topBar = {
            val bar: @Composable () -> Unit = {
                SmallTopAppBar(title = title, color = if (scanning) Color.Transparent else surface, titleColor = ink,
                    modifier = Modifier.testTag("life-device-top-bar"),
                    navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回", tint = ink) } },
                    actions = {
                        if (showHelp) IconButton(onClick = onHelp) {
                            Icon(MiuixIcons.Info, "帮助", tint = ink)
                        }
                    })
            }
            if (scanning) Box(Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent)))) {
                bar()
            } else BlurredBar(topGradient = true) { bar() }
        }, bottomBar = { footer() }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(surface)) { content(padding) }
        }
    }
}

@Composable
internal fun LifeDeviceHeading(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
        fontSize = 29.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
}

/** One centered identity card and icon treatment, ready for other device categories. */
@Composable
internal fun LifeDeviceHero(device: LifeDevice, feature: LifeFeature) {
    Card(Modifier.fillMaxWidth().testTag("life-device-hero"), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LifeDeviceGlyph(feature)
            Spacer(Modifier.height(15.dp))
            Text(device.name.ifBlank { if (feature == LifeFeature.Water) "饮水设备" else "洗衣设备" }, Modifier.testTag("life-device-name"), fontSize = 19.sp,
                lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (device.shopName.isNotBlank()) Text(device.shopName, Modifier.padding(top = 6.dp).testTag("life-device-location"),
                fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun LifeDeviceGlyph(feature: LifeFeature) {
    Box(Modifier.size(70.dp).clip(RoundedCornerShape(24.dp)).background(DashboardPalette.iconPanel)
        .testTag("life-device-glyph"), contentAlignment = Alignment.Center) {
        Icon(if (feature == LifeFeature.Water) AppIcons.Water else AppIcons.Laundry, null, Modifier.size(35.dp),
            tint = DashboardPalette.iconAccent)
    }
}

@Composable
private fun LifeDeviceFacts(device: LifeDevice, session: WaterSession? = null) {
    val duration = session?.startedAtMillis?.let { started -> session.endedAtMillis?.let { end -> waterDuration(end - started) } }
    val rows = buildList {
        if (device.name.isNotBlank()) add("设备" to device.name)
        if (device.shopName.isNotBlank()) add("位置" to device.shopName)
        if (duration != null) add("本地参考时长" to duration)
        if (session != null && device.alipay != null) add("支付方式" to device.alipay!!.name)
        else add("服务提供方" to "胖乖生活")
    }
    Card(Modifier.fillMaxWidth().testTag("life-device-info"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                LifeDeviceField(label) {
                    if (label == "支付方式") LifePaymentValue(device)
                    else Text(value, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
                }
                if (index < rows.lastIndex) HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
            }
        }
    }
}

@Composable
internal fun LifeDeviceField(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(label, fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { content() }
    }
}

@Composable
private fun LifePaymentCard(device: LifeDevice, showOptions: Boolean = false, state: LifeDevicesUiState? = null,
    onSelect: (String) -> Unit = {}, enabled: Boolean = false) {
    val channel = device.alipay ?: return
    Card(Modifier.fillMaxWidth().testTag("life-payment-card"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            LifeDeviceField("支付方式") {
                LifePaymentValue(device)
            }
            if (showOptions) {
                HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                LifeDeviceField("免密状态") {
                    val usable = channel.available && !channel.needsOpen
                    val green = lifeSuccessInk()
                    Text(if (channel.needsOpen) "需开通" else if (usable) "可使用" else "不可用",
                        Modifier.clip(RoundedCornerShape(6.dp)).background(if (usable) lifeSuccessFill() else DashboardPalette.field)
                            .padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = if (usable) green else DashboardPalette.muted)
                }
                val skus = device.skus.filter { it.name.isNotBlank() }
                if (skus.size == 1) {
                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                    LifeDeviceField("接水选项") { Text(skus.single().name, fontSize = 12.sp) }
                } else if (skus.isNotEmpty()) {
                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("接水选项", fontSize = 12.sp, color = DashboardPalette.muted)
                        skus.forEach { sku ->
                            PaymentSelectionCard(selected = sku.id == state?.selectedSkuId, enabled = enabled && sku.available,
                                onClick = { onSelect(sku.id) }) { Text(sku.name, fontSize = 13.sp, color = it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LifePaymentValue(device: LifeDevice) {
    val channel = device.alipay ?: return
    Text(if (device.payment == "after") "支付宝免密" else channel.name, fontSize = 12.sp)
}

@Composable
private fun WaterUsageNotice(device: LifeDevice, running: Boolean = false) {
    Card(Modifier.fillMaxWidth().testTag("water-usage-notice"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (!device.supportsFinish) "请按设备说明操作" else if (running) "接好后及时结束" else "接好后，点结束接水",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(if (running) "离开此页不会自动结束接水，可从付款页状态卡返回接水进度。"
                else "请先放稳容器。费用可在胖乖生活或支付宝中查询。",
                fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
        }
    }
}

@Composable
private fun WaterFeeNotice() {
    Card(Modifier.fillMaxWidth().testTag("water-fee-notice"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(AppIcons.Bill, null, Modifier.size(17.dp), tint = MiuixTheme.colorScheme.primary)
                Text("费用可在胖乖生活或支付宝查询", Modifier.weight(1f), fontSize = 12.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold)
            }
            Text("支付宝免密后付，实际费用与扣款情况请查看对应记录。", fontSize = 11.sp, lineHeight = 19.sp,
                color = DashboardPalette.muted)
        }
    }
}

@Composable
internal fun lifeSuccessFill(): Color = if (isSystemInDarkTheme()) Color(0xFF283E32) else Color(0xFFEAF5EE)
@Composable
internal fun lifeSuccessInk(): Color = if (isSystemInDarkTheme()) Color(0xFF96C7AF) else Color(0xFF549276)

private fun waterDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

@Composable
private fun WaterProgress(session: WaterSession, nowMillis: () -> Long) {
    var now by remember(session.startedAtMillis) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(session.phase, session.startedAtMillis, nowMillis) {
        while (session.phase != WaterPhase.Ended) { now = nowMillis(); delay(1000) }
    }
    val ended = session.phase == WaterPhase.Ended
    Column(Modifier.fillMaxWidth().padding(top = if (ended) 30.dp else 36.dp, bottom = 24.dp).testTag("water-progress"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (ended) {
            DeviceCompletedIcon("water-ended-icon")
            Text("接水已结束", Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            Text("设备已结束本次接水", Modifier.padding(top = 7.dp), fontSize = 12.sp, color = DashboardPalette.muted)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(lifeSuccessInk(), CircleShape))
                Text("接水中", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            session.startedAtMillis?.let { started ->
                Text(waterDuration(now - started), Modifier.padding(top = 22.dp, bottom = 9.dp).testTag("water-elapsed"),
                    fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold)
                Text("本地参考计时", fontSize = 12.sp, color = DashboardPalette.muted)
            }
            Text("费用以实际扣款记录为准", Modifier.padding(top = 3.dp), fontSize = 10.sp, color = DashboardPalette.muted)
        }
    }
}

@Composable
private fun WaterActions(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean, onBack: () -> Unit) {
    val water = state.water
    val phase = water?.phase ?: WaterPhase.Ready
    val failedCommand = state.error != null && water != null && phase != WaterPhase.Running
    val loading = state.loadingDevice || state.waterBusy || phase in setOf(WaterPhase.Starting, WaterPhase.Stopping) && !failedCommand ||
        phase == WaterPhase.Ended && water?.finalized != true && !failedCommand
    val label = when {
        state.loadingDevice -> "正在识别…"
        failedCommand && !state.waterBusy -> "重试"
        phase == WaterPhase.Starting -> "正在启动…"
        phase == WaterPhase.Stopping -> "正在结束…"
        phase == WaterPhase.Running -> "结束接水"
        phase == WaterPhase.Ended && water?.finalized != true -> "正在处理…"
        phase == WaterPhase.Ended -> "再次扫码接水"
        else -> "开始接水"
    }
    LifeDeviceActionFooter("water-action-footer") {
    Text(if (phase == WaterPhase.Ready) "支付宝免密后付" else "费用可在胖乖生活或支付宝查询", fontSize = 12.sp,
        lineHeight = 17.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
    if (phase == WaterPhase.Running && water?.device?.supportsFinish != true) {
        Text("请按设备说明结束接水", Modifier.fillMaxWidth(), fontSize = 13.sp, textAlign = TextAlign.Center,
            color = DashboardPalette.muted)
    } else Button(onClick = {
        when {
            failedCommand -> model.retryWater()
            phase == WaterPhase.Running -> model.stopWater()
            phase == WaterPhase.Ended -> model.scan()
            else -> model.startWater()
        }
    }, enabled = active && !loading && when {
        phase == WaterPhase.Ready -> state.device?.startError == null && state.device != null
        else -> true
    }, modifier = Modifier.fillMaxWidth().testTag("water-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
        AnimatedContent(label, transitionSpec = { fadeIn(tween(120, delayMillis = 100)) togetherWith fadeOut(tween(100)) },
            label = "water-button-label") { Text(it) }
    }
    if (phase == WaterPhase.Ended) Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = active) { Text("返回") }
    }
}

/** The device pages share the app's native action spacing and neutral secondary button style. */
@Composable
internal fun LifeDeviceActionFooter(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(horizontal = 16.dp)
        .padding(top = 14.dp).bottomSheetSafeArea().padding(bottom = 20.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun LifeManualDeviceSheet(show: Boolean, state: LifeDevicesUiState, serial: String,
    onValue: (String) -> Unit, onDismiss: () -> Unit, onIdentify: () -> Unit) {
    OverlayBottomSheet(show = show, startAction = { SheetTitle("输入设备编号") }, onDismissRequest = onDismiss, renderInRootScaffold = true) {
        val value = rememberWhileShown(show, serial)
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("填写设备显示屏或贴纸上的编号，识别后核对设备。", fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
            val identify = { if (show && LifeDeviceCode.identify(serial) != null) onIdentify() }
            DeviceCodeTextField(value, onValue, label = "设备码 / 二维码内容", enabled = show && !state.loadingDevice,
                modifier = Modifier.fillMaxWidth().testTag("life-device-serial"), keyboardActions = KeyboardActions(onDone = { identify() }))
            InlineErrorGroup(state.error, 16.dp) {
                Button(onClick = onIdentify, enabled = show && !state.loadingDevice && LifeDeviceCode.identify(serial) != null,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.loadingDevice) "正在识别…" else "识别设备")
                }
            }
        }
    }
}

@Composable
private fun LifeDeviceHelpSheet(show: Boolean, state: LifeDevicesUiState, model: LifeDevicesViewModel, onDismiss: () -> Unit) {
    OverlayBottomSheet(show = show, startAction = { SheetTitle(if (state.scanning) "扫码帮助" else "${state.feature.title}帮助") }, onDismissRequest = onDismiss, renderInRootScaffold = true) {
        val display = rememberWhileShown(show, state)
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (state.scanning) "扫描接水或洗衣设备上的二维码，识别后自动进入对应界面。已有服务进度可从付款页的状态卡查看。"
                else if (state.feature == LifeFeature.Water) "扫码后核对设备，放稳容器再开始。接好后点击结束接水；离开页面不会自动停止设备。费用请在胖乖生活或支付宝查询。"
                else "扫码后核对机器、程序和价格，按设备说明放入衣物并关好门。支付后会读取设备启动结果；离开页面不会停止洗衣。桶自洁请按设备说明使用，完成后可从付款页返回进度。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            display.device?.let { device ->
                LifeDeviceField("设备编号") { Text(device.serial, fontSize = 12.sp) }
                if (device.address.isNotBlank()) LifeDeviceField("地址") {
                    Text(device.address, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
                }
                if ((if (display.feature == LifeFeature.Water) display.water?.active != true && display.water?.phase != WaterPhase.Ended else display.laundry == null)) Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onDismiss(); model.scan() }, modifier = Modifier.weight(1f), enabled = show && !display.waterBusy && !display.laundryBusy) { Text("重新扫码") }
                    Button(onClick = { onDismiss(); model.refreshDevice() }, modifier = Modifier.weight(1f), enabled = show && !display.waterBusy && !display.laundryBusy && !display.loadingDevice) { Text("刷新设备") }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = show,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}
