package com.mercury.yktwx.ui.pages

import com.mercury.yktwx.ui.components.AppBackIcon
import com.mercury.yktwx.ui.components.AppCard as Card
import com.mercury.yktwx.ui.components.AppTextButton as TextButton
import com.mercury.yktwx.ui.components.AppButton as Button
import com.mercury.yktwx.ui.components.AppIconButton as IconButton
import com.mercury.yktwx.ui.components.AppSmallTopAppBar as SmallTopAppBar
import com.mercury.yktwx.ui.components.AppTopAppBar as TopAppBar
import com.mercury.yktwx.ui.components.AppOverlayBottomSheet as OverlayBottomSheet
import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
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
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both feature entries use this account drawer; closing it leaves the account disconnected. */
@Composable
internal fun LifeDeviceLoginSheet(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val keyboard = LocalSoftwareKeyboardController.current
    LifeAccountLoginSheet(show, if (state.changingAccount) "更换胖乖生活账户" else "登录胖乖生活", "life-login-sheet", { keyboard?.hide(); model.dismissLogin() }) {
        val display = rememberWhileShown(show, state)
        val enabled = show && !display.loggingIn && !display.sendingCode
        Text("接水、淋浴与洗衣共用此账号", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
        InlineErrorGroup(display.loginError, 16.dp, preserveMessage = true) {
            Button(onClick = { keyboard?.hide(); model.login() }, enabled = enabled && display.phone.length == 11 && display.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth().testTag("life-login-submit"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (display.loggingIn) "正在登录…" else if (display.changingAccount) "登录并更换账户" else "登录并继续")
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

/** Shared page container, spacing and device presentation for both features. */
@Composable
internal fun LifeDevicePage(state: LifeDevicesUiState, settings: ThemeSettings, model: LifeDevicesViewModel,
    active: Boolean, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis,
    onRequestScan: () -> Unit = model::scan,
    onScanned: (String) -> Unit = model::scanned, recognitionStartAllowed: Boolean = true,
    onManualScanned: (DeviceScanRoute) -> Unit = { onScanned(it.code) }) {
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    val scanning = state.scanning
    if (scanning) {
        LifeScannerPage(state, active, onBack, onScanned, recognitionStartAllowed, onManualScanned)
        return
    }
    var help by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) help = false }
    val retryFeedback = rememberRetryFeedbackState(model.restorationOwner to
        (state.laundry?.sessionId ?: state.scannedSerial ?: state.device?.id))
    if (state.feature == LifeFeature.Water && !scanning) {
        PangguiWaterDevicePage(state, settings, model, active, onBack, nowMillis, onRequestScan)
        return
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var topBarListState by remember { mutableStateOf<LazyListState?>(null) }
    val title = when {
        state.feature == LifeFeature.Laundry && state.laundry?.phase == LaundryPhase.Ended -> "洗衣完成"
        state.feature == LifeFeature.Laundry && state.laundry?.phase in setOf(LaundryPhase.Closed, LaundryPhase.Refunding) -> "洗衣订单"
        state.feature == LifeFeature.Laundry && state.laundry != null -> "洗衣进度"
        else -> "扫码洗衣"
    }
    val awaitingAccount = state.scannedSerial != null && (state.restoring || state.connectedPhone.isBlank())
    LifeDeviceShell(title, settings, scrollBehavior, onBack, onHelp = { help = true }, showHelp = true,
        listState = topBarListState,
        footer = {
            if (state.feature == LifeFeature.Laundry) {
                if (awaitingAccount) LifeDeviceActionFooter("laundry-action-footer") {
                    Button(onClick = { model.connectAccount(clearPending = false) }, enabled = active && !state.restoring,
                        modifier = Modifier.fillMaxWidth().testTag("laundry-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(if (state.restoring) "正在恢复账号…" else "登录并继续")
                    }
                    Button(onClick = onRequestScan, enabled = active, modifier = Modifier.fillMaxWidth()) { Text("重新扫码") }
                } else LaundryActions(state, model, active, onBack, nowMillis, retryFeedback, onRequestScan)
            }
        }) { padding ->
        val stage = when {
            state.feature == LifeFeature.Laundry -> "laundry-${when (state.laundry?.phase) {
                null -> "ready"
                LaundryPhase.Running -> "running"
                LaundryPhase.Ended -> "ended"
                LaundryPhase.Closed -> "closed"
                LaundryPhase.Refunding -> "refunding"
                else -> "processing"
            }}"
            else -> "laundry-ready"
        }
        AnimatedContent(stage, modifier = Modifier.fillMaxSize(), transitionSpec = {
            ((fadeIn(tween(ContentMotion.Append, delayMillis = 120)) +
                slideInVertically(tween(ContentMotion.Append, delayMillis = 120, easing = ContentMotion.Easing)) { it / 45 })
                togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
        }, label = "life-device-stage") { displayedStage ->
        val stageActive = active && displayedStage == stage
        // Animate routes only; retain display data while an outgoing stage finishes.
        val state = rememberWhileShown(displayedStage == stage, state.copy(code = "", paymentLaunch = null))
        val listState = rememberLazyListState()
        SideEffect { if (displayedStage == stage) topBarListState = listState }
        LazyColumn(Modifier.fillMaxSize().topBarOverscroll(scrollBehavior)
            .nestedScroll(scrollBehavior.nestedScrollConnection).testTag("life-device-page"),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 20.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (awaitingAccount) {
                if (!state.restoring) item {
                    InlineLifeLoginNotice(true, { model.connectAccount(clearPending = false) }, enabled = stageActive,
                        text = "登录胖乖生活，继续扫码洗衣", tag = "laundry-device-login-notice")
                }
                item { LifeDeviceHero("洗衣设备", "", LifeFeature.Laundry) }
                item { Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        LifeDeviceField("设备编号") { Text(state.scannedSerial.orEmpty(), fontSize = 12.sp) }
                        Text(if (state.restoring) "正在恢复胖乖生活账号，完成后继续查询设备" else "登录后继续查询设备",
                            fontSize = 13.sp, color = DashboardPalette.muted)
                    }
                } }
            } else laundryContent(state, model, stageActive, nowMillis, retryFeedback)
        }
        }
    }
    LifeDeviceHelpSheet(active && help, state, model, onScan = onRequestScan,
        onDismiss = { help = false }, retryFeedback = retryFeedback)
    LaundryPaymentSheet(active && state.paymentVisible, state, model, nowMillis)
}

@Composable
internal fun LifeScannerPage(state: LifeDevicesUiState, active: Boolean,
    onBack: () -> Unit, onScanned: (String) -> Unit, recognitionStartAllowed: Boolean,
    onManualScanned: (DeviceScanRoute) -> Unit,
    cameraBackendFactory: (android.content.Context) -> DeviceCameraBackend = ::CameraXDeviceCameraBackend) {
    var serial by rememberSaveable(state.entryRequest) { mutableStateOf("") }
    var manual by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    var help by rememberSaveable(state.entryRequest) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) { if (!active) { manual = false; help = false } }
    // Scanning has its own full-screen composition; it never instantiates the generic page shell.
    LifeScannerShell(
        topBar = {
            Box(Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent)))) {
                SmallTopAppBar(title = "扫一扫", color = Color.Transparent, titleColor = Color.White, glassStyle = false,
                    modifier = Modifier.testTag("life-device-top-bar"), navigationIcon = {
                        IconButton(onClick = onBack) { AppBackIcon("返回", tint = Color.White) }
                    }, actions = {
                        IconButton(onClick = { help = true }) { Icon(MiuixIcons.Info, "帮助", tint = Color.White) }
                    })
            }
        },
        belowTopBar = {
            if (!state.error.isNullOrBlank() && !manual) Box(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                contentAlignment = Alignment.TopCenter) {
                Text(state.error, Modifier.padding(horizontal = 24.dp),
                    fontSize = 12.sp, lineHeight = 20.sp, color = Color.White, textAlign = TextAlign.Center)
            }
        },
    ) {
        Box(Modifier.fillMaxSize().testTag("life-device-page")) {
            DeviceScanner(active, { code -> if (!manual && !help && !state.loadingDevice) onScanned(code) }, immersive = true,
                onManualInput = { manual = true }, recognitionStartAllowed = recognitionStartAllowed,
                cameraBackendFactory = cameraBackendFactory)
            if (state.loadingDevice) Text("正在识别设备…", Modifier.align(Alignment.Center), fontSize = 14.sp, color = Color.White)
        }
    }
    LifeManualDeviceSheet(active && manual, state, serial,
        onValue = { serial = it },
        onDismiss = { manual = false; keyboard?.hide() }, onIdentify = { keyboard?.hide(); onManualScanned(it) })
    ScannerHelpSheet(active && help) { help = false }
}

@Composable
private fun ScannerHelpSheet(show: Boolean, onDismiss: () -> Unit) {
    if (!rememberOverlayMounted(show)) return
    OverlayBottomSheet(show = show, startAction = { SheetTitle("扫码帮助") }, onDismissRequest = onDismiss,
        renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("扫描胖乖接水、淋浴、洗衣、慧生活798用水设备或哈啰单车上的二维码，识别后自动进入对应界面。也可手动输入设备编号；出现平台选项时，选择设备上的平台。已有服务进度可从付款页的状态卡查看。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = show,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}

@Composable
internal fun LifeDeviceShell(title: String, settings: ThemeSettings, scrollBehavior: ScrollBehavior, onBack: () -> Unit,
    onHelp: () -> Unit, showHelp: Boolean, listState: LazyListState?,
    footer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val topBarOffset = rememberLazyListTopBarOffset(listState, scrollBehavior)
    val currentContent by rememberUpdatedState(content)
    val body = remember { movableContentOf<PaddingValues> { padding -> currentContent(padding) } }
    val surface = MiuixTheme.colorScheme.surface
    val ink = MiuixTheme.colorScheme.onSurface
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            val navigationIcon: @Composable () -> Unit = {
                IconButton(onClick = onBack) { AppBackIcon("返回", tint = ink) }
            }
            val actions: @Composable RowScope.() -> Unit = {
                if (showHelp) IconButton(onClick = onHelp) {
                    Icon(MiuixIcons.Info, "帮助", tint = ink)
                }
            }
            BlurredBar(topGradient = true) {
                TopAppBar(title = title, largeTitle = title, color = Color.Transparent, scrollBehavior = scrollBehavior,
                    isContentScrolled = listState?.canScrollBackward == true, visualContentOffset = topBarOffset,
                    modifier = Modifier.testTag("life-device-top-bar"), navigationIcon = navigationIcon, actions = actions)
            }
        }, bottomBar = { footer() }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(surface)) { body(padding) }
        }
    }
}

/** The scanner needs only a full-page body and an overlaid bar, not Scaffold's seven slots. */
@Composable
internal fun LifeScannerShell(topBar: @Composable () -> Unit, belowTopBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit) {
    // The app's outer Scaffold owns the root overlays. Keep preview pixels out of its blur capture.
    BarBlurHost(false, false) {
        Layout(content = {
            Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            Box { topBar() }
            Box { belowTopBar() }
        }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val bar = measurables[1].measure(loose)
            val below = measurables[2].measure(loose)
            val body = measurables[0].measure(loose)
            layout(constraints.maxWidth, constraints.maxHeight) {
                body.place(0, 0)
                bar.place(0, 0)
                below.place(0, bar.height)
            }
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
            Text(name.ifBlank { when (feature) {
                LifeFeature.Water -> "饮水设备"
                LifeFeature.Laundry -> "洗衣设备"
                LifeFeature.Ride -> "哈啰单车"
            } }, Modifier.testTag("life-device-name"), fontSize = 19.sp,
                lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (location.isNotBlank()) Text(location, Modifier.padding(top = 6.dp).testTag("life-device-location"),
                fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun LifeDeviceGlyph(feature: LifeFeature) {
    ServiceIcon(lifeFeatureIcon(feature), Modifier.testTag("life-device-glyph"), size = 70.dp)
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
private fun WaterPaymentDetails(device: LifeDevice, showOptions: Boolean = false, state: LifeDevicesUiState? = null,
    onSelect: (String) -> Unit = {}, enabled: Boolean = false) {
    val channel = device.alipay ?: return
    Column(Modifier.fillMaxWidth().testTag("life-payment-card")) {
        LifeDeviceField("支付方式") {
            LifePaymentValue(device)
        }
        if (showOptions) {
            HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
            LifeDeviceField("免密状态") {
                val usable = channel.available == true && channel.needsOpen == false
                val green = lifeSuccessInk()
                Text(if (channel.needsOpen == true) "需开通" else if (usable) "可使用" else if (channel.available == false) "不可用" else "未知",
                    Modifier.clip(RoundedCornerShape(6.dp)).background(if (usable) lifeSuccessFill() else DashboardPalette.field)
                        .padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 10.sp, color = if (usable) green else DashboardPalette.muted)
            }
            val skus = device.skus.filter { it.name.isNotBlank() }
            if (skus.size == 1) {
                HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                LifeDeviceField(if (device.isShower) "出水口" else "接水选项") { Text(skus.single().name, fontSize = 12.sp) }
            } else if (skus.size in 2..3 && skus.all { it.name.length <= 4 && it.available != false } &&
                skus.any { it.id == state?.selectedSkuId }) {
                HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                LifeDeviceField(if (device.isShower) "出水口" else "接水选项") {
                    WaterOptionTabs(skus, skus.indexOfFirst { it.id == state?.selectedSkuId }, enabled, onSelect)
                }
            } else if (skus.isNotEmpty()) {
                HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (device.isShower) "出水口" else "接水选项", fontSize = 12.sp, color = DashboardPalette.muted)
                    skus.forEach { sku ->
                        PaymentSelectionCard(selected = sku.id == state?.selectedSkuId, enabled = enabled && sku.available != false,
                            onClick = { onSelect(sku.id) }) { Text(sku.name, fontSize = 13.sp, color = it) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun WaterOptionTabs(skus: List<LifeSku>, selected: Int, enabled: Boolean, onSelect: (String) -> Unit) {
    val disabledModifier = if (enabled) Modifier else Modifier
        .semantics { disabled() }.focusProperties { canFocus = false }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    Box(Modifier.widthIn(max = (skus.size * 64 + 10).dp).fillMaxWidth()
        .testTag("water-option-tabs").then(disabledModifier)) {
        val styles = MiuixTheme.textStyles
        MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = styles.copy(
            body2 = styles.body2.copy(fontSize = 12.sp), subtitle = styles.subtitle.copy(fontSize = 12.sp))) {
            AppTabRowWithContour(tabs = skus.map { it.name }, selectedTabIndex = selected,
                onTabSelected = { if (enabled) onSelect(skus[it].id) },
                minWidth = 64.dp, maxWidth = 64.dp, height = 34.dp, cornerRadius = 9.dp, itemSpacing = 0.dp,
                contentAlignment = if (LocalGlassStyle.current) Alignment.CenterEnd else Alignment.Center,
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = if (isSystemInDarkTheme()) DashboardPalette.card else DashboardPalette.field,
                    contentColor = DashboardPalette.muted,
                    selectedBackgroundColor = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.secondaryVariant else DashboardPalette.card,
                    selectedContentColor = DashboardPalette.ink))
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
    Text(channel.name, fontSize = 12.sp)
}

@Composable
private fun PangguiWaterDevicePage(state: LifeDevicesUiState, settings: ThemeSettings, model: LifeDevicesViewModel,
    active: Boolean, onBack: () -> Unit, nowMillis: () -> Long, onRequestScan: () -> Unit) {
    val water = state.water
    val device = water?.device ?: state.device
    val action = device?.waterAction ?: "接水"
    val phase = water?.phase ?: WaterPhase.Ready
    val exceptional = water?.canDismissException == true
    val restoringAccount = state.restoring && state.scannedSerial != null
    val loginRequired = !state.restoring && state.connectedPhone.isBlank() && state.scannedSerial != null
    val failedCommand = exceptional || state.error != null && water != null && phase != WaterPhase.Running
    val loading = restoringAccount || state.loadingDevice || state.waterBusy || phase in setOf(WaterPhase.Starting, WaterPhase.Stopping) && !failedCommand ||
        phase == WaterPhase.Ended && water?.finalized != true && !failedCommand
    val label = when {
        restoringAccount -> "正在恢复账号…"
        loginRequired -> "登录并继续"
        state.loadingDevice -> "正在识别…"
        phase == WaterPhase.Ready && state.error != null -> "重新检查设备"
        exceptional && !state.waterBusy -> "重新扫码"
        failedCommand && !state.waterBusy -> "重试"
        phase == WaterPhase.Starting -> "正在启动…"
        phase == WaterPhase.Stopping -> "正在结束…"
        phase == WaterPhase.Ended && water?.finalized != true -> "正在处理…"
        state.waterBusy -> "正在检查…"
        phase == WaterPhase.Running -> "结束$action"
        phase == WaterPhase.Ended -> "再次扫码$action"
        else -> "开始$action"
    }
    val display = WaterDeviceUiState(LifeProvider.Panggui, device?.name.orEmpty(), device?.shopName.orEmpty(),
        deviceId = device?.serial ?: state.scannedSerial,
        phase = phase, startedAtMillis = water?.startedAtMillis, endedAtMillis = water?.endedAtMillis,
        paymentMethod = device?.alipay?.name,
        supportsFinish = if (exceptional) null else device?.supportsFinish, error = state.error ?: water?.fault, shower = device?.isShower == true,
        checking = state.loadingDevice || state.waterBusy,
        status = when {
            restoringAccount -> WaterDeviceStatus("正在恢复账号", "恢复完成后继续查询设备")
            exceptional -> WaterDeviceStatus(water.fault ?: "启动结果未知", "可移除本条提醒或重新扫码")
            else -> null
        })
    val primary = WaterDeviceAction(label, !loading && when {
        loginRequired -> true
        phase == WaterPhase.Ready -> state.error != null || state.device != null && state.selectedSkuId.isNotBlank()
        else -> true
    }) {
        when {
            loginRequired -> model.connectAccount(clearPending = false)
            phase == WaterPhase.Ready && state.error != null -> model.refreshDevice()
            exceptional -> model.rescanWaterException()
            failedCommand -> model.retryWater()
            phase == WaterPhase.Running -> model.stopWater()
            phase == WaterPhase.Ended -> onRequestScan()
            else -> model.startWater()
        }
    }
    WaterDevicePage(display, settings, active, primary,
        scanAction = WaterDeviceAction("重新扫码", !state.waterBusy && !state.loadingDevice, onRequestScan),
        refreshAction = WaterDeviceAction("刷新设备", !state.restoring && !loginRequired && !state.waterBusy && !state.loadingDevice, model::refreshDevice),
        onBack = onBack, footerNote = if (phase == WaterPhase.Ready) "支付宝免密后付" else display.feeNotice, nowMillis = nowMillis,
        loginAction = if (loginRequired) WaterDeviceAction("登录并继续", true) { model.connectAccount(clearPending = false) } else null,
        retryAction = when {
            loginRequired || exceptional -> null
            water == null -> WaterDeviceAction("重新检查", !state.loadingDevice, model::refreshDevice)
            phase == WaterPhase.Running -> WaterDeviceAction("刷新状态", !state.waterBusy, model::retryWater)
            else -> null
        },
        feedbackIdentity = model.restorationOwner to (water?.sessionId ?: display.deviceId),
        primaryReadsState = !loginRequired && !exceptional && (phase == WaterPhase.Ready && state.error != null || failedCommand),
        primaryLoading = loading,
        paymentContent = if (device?.alipay == null) null else { enabled -> WaterPaymentDetails(device,
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
    onValue: (String) -> Unit, onDismiss: () -> Unit, onIdentify: (DeviceScanRoute) -> Unit) {
    if (!rememberOverlayMounted(show)) return
    OverlayBottomSheet(show = show, startAction = { SheetTitle("输入设备编号") }, onDismissRequest = onDismiss, renderInRootScaffold = true) {
        val value = rememberWhileShown(show, serial)
        val candidates = remember(value) { UnifiedDeviceCode.manualCandidates(value) }
        var selectedProvider by rememberSaveable(show, serial) { mutableStateOf<LifeProvider?>(null) }
        val route = UnifiedDeviceCode.identifyManual(serial, selectedProvider)
        val identify = { if (show && !state.loadingDevice) route?.let(onIdentify); Unit }
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("填写设备显示屏或贴纸上的编号，识别后核对设备。", fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
            DeviceCodeTextField(value, onValue, label = "设备编号", enabled = show && !state.loadingDevice,
                modifier = Modifier.fillMaxWidth().testTag("life-device-serial"), keyboardActions = KeyboardActions(onDone = { identify() }))
            if (candidates.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("选择设备上的平台", fontSize = 12.sp, color = DashboardPalette.muted)
                    candidates.forEach { candidate ->
                        PaymentSelectionCard(selected = selectedProvider == candidate.provider,
                            onClick = { selectedProvider = candidate.provider }, enabled = show && !state.loadingDevice,
                            modifier = Modifier.testTag("life-manual-provider-${candidate.provider.tag}")) {
                            Text(candidate.provider.displayName, fontSize = 13.sp, color = it)
                        }
                    }
                }
            }
            InlineErrorGroup(state.error, 16.dp, preserveMessage = true) {
                Button(onClick = identify, enabled = show && !state.loadingDevice && route != null,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (state.loadingDevice) "正在识别…" else "识别设备")
                }
            }
        }
    }
}

@Composable
private fun LifeDeviceHelpSheet(show: Boolean, state: LifeDevicesUiState, model: LifeDevicesViewModel,
    onScan: () -> Unit, onDismiss: () -> Unit,
    retryFeedback: RetryFeedbackState) {
    if (!rememberOverlayMounted(show)) return
    OverlayBottomSheet(show = show, startAction = { SheetTitle(if (state.scanning) "扫码帮助" else "${state.feature.title}帮助") }, onDismissRequest = onDismiss, renderInRootScaffold = true) {
        val display = rememberWhileShown(show, state)
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (state.scanning) "扫描胖乖接水、淋浴、洗衣、慧生活798用水设备或哈啰单车上的二维码，识别后自动进入对应界面。也可手动输入设备编号；出现平台选项时，选择设备上的平台。已有服务进度可从付款页的状态卡查看。"
                else if (state.feature == LifeFeature.Water) "扫码后核对设备，放稳容器再开始。接好后点击结束接水；离开页面不会自动停止设备。费用请在胖乖生活或支付宝查询。"
                else "扫码后核对机器、程序和价格，按设备说明放入衣物并关好门。支付后会读取设备启动结果；离开页面不会停止洗衣，完成后可从付款页返回进度。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            display.device?.let { device ->
                LifeDeviceField("设备编号") { Text(device.serial, fontSize = 12.sp) }
                if (device.address.isNotBlank()) LifeDeviceField("地址") {
                    Text(device.address, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End)
                }
                if ((if (display.feature == LifeFeature.Water) display.water?.active != true && display.water?.phase != WaterPhase.Ended else display.laundry == null)) Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onDismiss(); onScan() }, modifier = Modifier.weight(1f), enabled = show && !display.waterBusy && !display.laundryBusy) { Text("重新扫码") }
                    Button(onClick = { onDismiss(); retryFeedback.dispatch(model::refreshDevice) }, modifier = Modifier.weight(1f),
                        enabled = show && !display.waterBusy && !display.laundryBusy && !display.loadingDevice && !retryFeedback.dispatching) { Text("刷新设备") }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = show,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
}
