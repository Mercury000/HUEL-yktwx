package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Both feature entries use this account drawer; closing it leaves the account disconnected. */
@Composable
internal fun LifeDeviceLoginSheet(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val keyboard = LocalSoftwareKeyboardController.current
    LifeAccountLoginSheet(show, "登录胖乖生活", "life-login-sheet", { keyboard?.hide(); model.dismissLogin() }) {
        val display = rememberWhileShown(show, state)
        val enabled = show && !display.loggingIn && !display.sendingCode
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

@Composable
internal fun LifeAccountLoginSheet(show: Boolean, title: String, tag: String, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val session = rememberOverlaySession(show)
    OverlayBottomSheet(show = show, startAction = { SheetTitle(title) }, onDismissRequest = onDismiss, renderInRootScaffold = true) {
        key(session) {
            Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).testTag(tag),
                verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

private data class LifeDevicePresentation(val stage: String, val state: LifeDevicesUiState)

/** Shared page container, spacing and device presentation for both features. */
@Composable
internal fun LifeDevicePage(state: LifeDevicesUiState, settings: ThemeSettings, model: LifeDevicesViewModel,
    active: Boolean, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis,
    onScanned: (String) -> Unit = model::scanned) {
    var serial by rememberSaveable(state.entryRequest) { mutableStateOf("") }
    var manual by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    var help by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    LaunchedEffect(state.scanning, active) { if (!state.scanning || !active) { manual = false; help = false } }
    val scanning = state.scanning || state.loadingDevice && state.device == null
    if (state.feature == LifeFeature.Water && !scanning) {
        PangguiWaterDevicePage(state, settings, model, active, onBack, nowMillis)
        return
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
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
        else -> "扫码洗衣"
    }
    LifeDeviceShell(title, settings, scanning, scrollBehavior, onBack, onHelp = { help = true }, showHelp = true,
        footer = {
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
            else -> "laundry-ready"
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
            DeviceScanner(stageActive, { code -> if (!manual && !help && !state.loadingDevice) onScanned(code) }, immersive = true,
                onManualInput = { manual = true })
            if (state.loadingDevice) Text("正在识别设备…", Modifier.align(Alignment.Center), fontSize = 14.sp, color = Color.White)
            if (!state.error.isNullOrBlank() && !manual) Text(state.error,
                Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding() + 16.dp, start = 24.dp, end = 24.dp),
                fontSize = 12.sp, lineHeight = 20.sp, color = Color.White, textAlign = TextAlign.Center)
        } else LazyColumn(Modifier.fillMaxSize().overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection).testTag("life-device-page"),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 20.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            laundryContent(state, model, stageActive, nowMillis)
        }
        }
    }
    LifeManualDeviceSheet(active && manual && state.scanning, state, serial,
        onValue = { serial = it },
        onDismiss = { manual = false; keyboard?.hide() }, onIdentify = { keyboard?.hide(); onScanned(serial) })
    LifeDeviceHelpSheet(active && help, state, model, onDismiss = { help = false })
    LaundryPaymentSheet(active && state.paymentVisible, state, model, nowMillis)
}

@Composable
internal fun LifeDeviceShell(title: String, settings: ThemeSettings, scanning: Boolean, scrollBehavior: ScrollBehavior, onBack: () -> Unit,
    onHelp: () -> Unit, showHelp: Boolean, footer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val surface = if (scanning) Color(0xFF1B2025) else MiuixTheme.colorScheme.surface
    val ink = if (scanning) Color.White else MiuixTheme.colorScheme.onSurface
    BarBlurHost(false, settings.blurTopBar.value && !scanning) {
        Scaffold(topBar = {
            val navigationIcon: @Composable () -> Unit = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回", tint = ink) }
            }
            val actions: @Composable RowScope.() -> Unit = {
                if (showHelp) IconButton(onClick = onHelp) {
                    Icon(MiuixIcons.Info, "帮助", tint = ink)
                }
            }
            if (scanning) Box(Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent)))) {
                SmallTopAppBar(title = title, color = Color.Transparent, titleColor = ink,
                    modifier = Modifier.testTag("life-device-top-bar"), navigationIcon = navigationIcon, actions = actions)
            } else BlurredBar(topGradient = true) {
                TopAppBar(title = title, largeTitle = title, color = Color.Transparent, scrollBehavior = scrollBehavior,
                    modifier = Modifier.testTag("life-device-top-bar"), navigationIcon = navigationIcon, actions = actions)
            }
        }, bottomBar = { footer() }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(surface)) { content(padding) }
        }
    }
}

/** One centered identity card and icon treatment, ready for other device categories. */
@Composable
internal fun LifeDeviceHero(device: LifeDevice, feature: LifeFeature) {
    LifeDeviceHero(device.name, device.shopName, feature)
}

@Composable
internal fun LifeDeviceHero(name: String, location: String, feature: LifeFeature) {
    Card(Modifier.fillMaxWidth().testTag("life-device-hero"), cornerRadius = 24.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LifeDeviceGlyph(feature)
            Spacer(Modifier.height(15.dp))
            Text(name.ifBlank { if (feature == LifeFeature.Water) "饮水设备" else "洗衣设备" }, Modifier.testTag("life-device-name"), fontSize = 19.sp,
                lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (location.isNotBlank()) Text(location, Modifier.padding(top = 6.dp).testTag("life-device-location"),
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
internal fun lifeSuccessFill(): Color = if (isSystemInDarkTheme()) Color(0xFF283E32) else Color(0xFFEAF5EE)
@Composable
internal fun lifeSuccessInk(): Color = if (isSystemInDarkTheme()) Color(0xFF96C7AF) else Color(0xFF549276)

@Composable
private fun LifePaymentValue(device: LifeDevice) {
    val channel = device.alipay ?: return
    Text(if (device.payment == "after") "支付宝免密" else channel.name, fontSize = 12.sp)
}

@Composable
private fun PangguiWaterDevicePage(state: LifeDevicesUiState, settings: ThemeSettings, model: LifeDevicesViewModel,
    active: Boolean, onBack: () -> Unit, nowMillis: () -> Long) {
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
    val device = water?.device ?: state.device
    val display = WaterDeviceUiState(LifeProvider.Panggui, device?.name.orEmpty(), device?.shopName.orEmpty(),
        phase = phase, startedAtMillis = water?.startedAtMillis, endedAtMillis = water?.endedAtMillis,
        paymentMethod = device?.alipay?.let { if (device.payment == "after") "支付宝免密" else it.name },
        supportsFinish = device?.supportsFinish == true, error = state.error)
    val primary = WaterDeviceAction(label, !loading && when {
        phase == WaterPhase.Ready -> state.device?.startError == null && state.device != null
        else -> true
    }) {
        when {
            failedCommand -> model.retryWater()
            phase == WaterPhase.Running -> model.stopWater()
            phase == WaterPhase.Ended -> model.scan()
            else -> model.startWater()
        }
    }
    WaterDevicePage(display, settings, active, primary,
        scanAction = WaterDeviceAction("重新扫码", !state.waterBusy && !state.loadingDevice, model::scan),
        refreshAction = WaterDeviceAction("刷新设备", !state.waterBusy && !state.loadingDevice && phase == WaterPhase.Ready, model::refreshDevice),
        onBack = onBack, footerNote = if (phase == WaterPhase.Ready) "支付宝免密后付" else display.feeNotice, nowMillis = nowMillis,
        paymentContent = if (device == null) null else { enabled -> LifePaymentCard(device,
            showOptions = phase in setOf(WaterPhase.Ready, WaterPhase.Starting), state = state, onSelect = model::selectSku,
            enabled = enabled && !state.waterBusy && !state.loadingDevice) })
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
            val identify = { if (show && UnifiedDeviceCode.identify(serial) != null) onIdentify() }
            DeviceCodeTextField(value, onValue, label = "设备码 / 二维码内容", enabled = show && !state.loadingDevice,
                modifier = Modifier.fillMaxWidth().testTag("life-device-serial"), keyboardActions = KeyboardActions(onDone = { identify() }))
            InlineErrorGroup(state.error, 16.dp) {
                Button(onClick = onIdentify, enabled = show && !state.loadingDevice && UnifiedDeviceCode.identify(serial) != null,
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
            Text(if (state.scanning) "扫描胖乖接水、洗衣或慧生活798用水设备上的二维码，识别后自动进入对应界面。798 手动输入请填写完整二维码链接；已有服务进度可从付款页的状态卡查看。"
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
