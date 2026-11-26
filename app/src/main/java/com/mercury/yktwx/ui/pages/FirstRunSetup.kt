package com.mercury.yktwx.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.Hui798ViewModel
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.PaymentViewModel
import com.mercury.yktwx.R
import com.mercury.yktwx.data.PaymentAccount
import com.mercury.yktwx.data.life.LifeAccountConnection
import com.mercury.yktwx.data.life.LifeAccountStatus
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.ui.MainNavigationPreview
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.UsageConsent
import com.mercury.yktwx.ui.allowOnlineEntry
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.rememberForegroundState
import com.mercury.yktwx.ui.rememberNetworkState
import com.mercury.yktwx.ui.shouldShowMainNavigationRail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FirstRunSetup(settings: ThemeSettings, consent: UsageConsent, payments: PaymentViewModel,
    payment: PaymentUiState, authenticated: Boolean, onExit: () -> Unit, active: Boolean = true,
    updateNotice: @Composable (Boolean) -> Unit) {
    var step by rememberSaveable { mutableStateOf(if (consent.accepted) OobeStep.Accounts else OobeStep.Welcome) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(onFailure: String, action: suspend () -> Unit) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = onFailure }
            finally { saving = false }
        }
    }
    val campusSignedIn = authenticated && !payment.restoringLogin && !payment.needsLogin && !payment.busy
    val currentStep = when {
        step == OobeStep.Welcome -> OobeStep.Welcome
        !consent.accepted || step == OobeStep.Privacy -> OobeStep.Privacy
        !campusSignedIn -> OobeStep.Accounts
        else -> step
    }
    OobePages(currentStep, active) { page, interactive, onStartBounds ->
        when (page) {
            OobeStep.Welcome -> SetupBookend(complete = false, active = interactive, onStartBounds = onStartBounds) { step = OobeStep.Privacy }
            OobeStep.Privacy -> {
                val goBack = { if (interactive && !saving) { error = null; step = OobeStep.Welcome } }
                BackHandler(enabled = interactive, onBack = goBack)
                UsageConsentPage(saving, error, onReject = { if (interactive && !saving) onExit() },
                    onBack = goBack, accepted = consent.accepted) {
                    if (interactive && !saving) {
                        if (consent.accepted) step = OobeStep.Accounts
                        else save("无法保存确认记录，请重试") {
                            consent.acceptForSetup()
                            step = OobeStep.Accounts
                        }
                    }
                }
            }
            OobeStep.Accounts -> SetupAccountLogin(payments, payment, campusSignedIn, onBack = { step = OobeStep.Privacy },
                updateNotice = updateNotice, active = interactive) {
                if (campusSignedIn) step = OobeStep.Appearance
            }
            OobeStep.Appearance -> SetupAppearance(settings, onBack = { step = OobeStep.Accounts }, active = interactive) {
                step = OobeStep.Complete
            }
            OobeStep.Complete -> {
                BackHandler(enabled = interactive) { if (!saving) { error = null; step = OobeStep.Appearance } }
                SetupBookend(complete = true, saving = saving, error = error, active = interactive) {
                    if (campusSignedIn) save("无法保存引导进度，请重试", consent::finishSetup)
                }
            }
        }
    }
    LaunchedEffect(payment.restoringLogin, campusSignedIn) {
        if (!payment.restoringLogin && !campusSignedIn && step > OobeStep.Accounts) step = OobeStep.Accounts
    }
}

@Composable
private fun SetupAccountLogin(payments: PaymentViewModel, payment: PaymentUiState, campusSignedIn: Boolean,
    onBack: () -> Unit, updateNotice: @Composable (Boolean) -> Unit, active: Boolean, onNext: () -> Unit) {
    val lifeModel: LifeDevicesViewModel = viewModel()
    val hui798Model: Hui798ViewModel = viewModel()
    val hellobikeModel: HellobikeViewModel = viewModel(key = "hellobike-false")
    LaunchedEffect(hellobikeModel) { hellobikeModel.initialize(false) }
    val life by lifeModel.state.collectAsStateWithLifecycle()
    val hui798 by hui798Model.state.collectAsStateWithLifecycle()
    val hellobike by hellobikeModel.state.collectAsStateWithLifecycle()
    val pangguiConnection by lifeModel.accountConnection.collectAsStateWithLifecycle()
    val hui798Connection by hui798Model.accountConnection.collectAsStateWithLifecycle()
    val hellobikeConnection by hellobikeModel.accountConnection.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val network by rememberNetworkState(preview = false)
    val foreground by rememberForegroundState()
    val bikeLoginActive = active && foreground.active && hellobike.loginVisible
    DisposableEffect(hellobikeModel, bikeLoginActive) {
        hellobikeModel.environment(bikeLoginActive)
        onDispose { if (bikeLoginActive) hellobikeModel.environment(false) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    var campusLogin by rememberSaveable { mutableStateOf(false) }
    fun dismissCampus() { keyboard?.hide(); campusLogin = false }
    LaunchedEffect(campusSignedIn) { if (campusSignedIn) dismissCampus() }
    LaunchedEffect(payments) {
        payments.messages.collect { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
    }
    val signedIn = buildSet {
        if (pangguiConnection.signedIn()) add(LifeProvider.Panggui)
        if (hui798Connection.signedIn()) add(LifeProvider.Hui798)
        if (hellobikeConnection.signedIn()) add(LifeProvider.Hellobike)
    }
    val restoring = buildSet {
        if (life.restoring) add(LifeProvider.Panggui)
        if (hui798.restoring) add(LifeProvider.Hui798)
        if (hellobike.restoring) add(LifeProvider.Hellobike)
    }
    fun goBack() {
        if (!active) return
        when {
            campusLogin -> dismissCampus()
            life.loginVisible -> { keyboard?.hide(); lifeModel.dismissLogin() }
            hui798.loginVisible -> { keyboard?.hide(); hui798Model.dismissLogin() }
            hellobike.loginVisible -> { keyboard?.hide(); hellobikeModel.dismissLogin() }
            else -> onBack()
        }
    }
    BackHandler(enabled = active) { goBack() }
    SetupAccounts(campusSignedIn, payment.restoringLogin, signedIn, restoring, onBack = ::goBack,
        onCampus = { if (active) campusLogin = true }, onOptionalLogin = { provider ->
            if (active && allowOnlineEntry(context, network.online)) {
                when (provider) {
                    LifeProvider.Panggui -> lifeModel.connectAccount()
                    LifeProvider.Hui798 -> hui798Model.connectAccount()
                    LifeProvider.Hellobike -> hellobikeModel.connectAccount()
                }
            }
        }, onNext = { if (active) onNext() }, updateNotice = { updateNotice(active) }) {
        val showCampus = active && campusLogin && !campusSignedIn && !payment.restoringLogin
        LifeAccountLoginSheet(showCampus, "登录校园一卡通", "oobe-login-drawer", ::dismissCampus) {
            LoginForm(payment, onLogin = { username, password -> if (showCampus) payments.login(username, password) },
                agreed = showCampus, onInputEdited = payments::loginInputEdited, showHeading = false)
        }
        LifeDeviceLoginSheet(life, lifeModel, active)
        Hui798LoginSheet(hui798, hui798Model, active)
        HellobikeLoginSheet(hellobike, hellobikeModel, active)
    }
}

private fun LifeAccountConnection.signedIn() = hasAccount && status != LifeAccountStatus.Expired

@Composable
internal fun SetupAccounts(campusSignedIn: Boolean, campusRestoring: Boolean, signedIn: Set<LifeProvider>,
    restoring: Set<LifeProvider>, onBack: () -> Unit, onCampus: () -> Unit, onOptionalLogin: (LifeProvider) -> Unit,
    onNext: () -> Unit, updateNotice: @Composable () -> Unit = {}, drawers: @Composable () -> Unit) {
    OobePage("登录账号", Modifier.testTag("oobe-accounts"), onBack = onBack, footer = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!campusSignedIn && !campusRestoring) Text("登录校园一卡通后可继续", textAlign = TextAlign.Center,
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            OobePrimaryButton("下一步", onNext, Modifier.fillMaxWidth(), enabled = campusSignedIn)
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp)) {
            updateNotice()
            val headingPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            SmallTitle("校园服务", insideMargin = headingPadding)
            Card(Modifier.fillMaxWidth()) {
                SetupAccountRow("校园一卡通", null, campusSignedIn, campusRestoring, "oobe-campus-row", onCampus)
            }
            Spacer(Modifier.height(12.dp))
            SmallTitle("生活服务", insideMargin = headingPadding)
            Card(Modifier.fillMaxWidth()) {
                LifeProvider.entries.forEach { provider ->
                    SetupAccountRow(provider.displayName, provider.services, provider in signedIn, provider in restoring,
                        "oobe-life-row-${provider.tag}") { onOptionalLogin(provider) }
                }
            }
            if (LifeProvider.entries.any { it !in signedIn && it !in restoring }) {
                Text("稍后可在「我的 → 应用设置 → 生活服务账户」登录",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        drawers()
    }
}

@Composable
private fun SetupAccountRow(title: String, summary: String?, signedIn: Boolean, restoring: Boolean,
    rowTag: String, onLogin: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val canLogin = !signedIn && !restoring
    BasicComponent(title = title, summary = summary, modifier = Modifier.testTag(rowTag),
        onClick = onLogin.takeIf { canLogin }, endActions = {
            Text(when { restoring -> "读取中"; signedIn -> "已登录"; else -> "去登录" }, Modifier.testTag("$rowTag-label"),
                style = MiuixTheme.textStyles.body2,
                color = if (canLogin) colors.primary else colors.onSurfaceVariantSummary)
        })
}

@Composable
internal fun SetupBookend(complete: Boolean, saving: Boolean = false, error: String? = null, active: Boolean = true,
    onStartBounds: (Rect) -> Unit = {}, onNext: () -> Unit) {
    var frameY by remember { mutableFloatStateOf(0f) }
    var frameHeight by remember { mutableIntStateOf(0) }
    var logoY by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { frameY = it.positionInRoot().y; frameHeight = it.size.height }) {
        OobeGlow(admission = !complete, centerY = { if (frameHeight > 0) ((logoY - frameY) / frameHeight).coerceIn(0f, 1f) else .4f },
            modifier = Modifier.matchParentSize(), animate = active)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(if (complete) 2f else 3f))
            Column(Modifier.onGloballyPositioned { logoY = it.positionInRoot().y + it.size.height / 2f }
                .oobeLogoEntrance(complete), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(150.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text("财小通", fontSize = 40.sp, color = Color(0xFF191348))
                if (complete) {
                    Spacer(Modifier.height(28.dp))
                    Text("设置完成", fontSize = 24.sp, color = Color(0xFF302858))
                }
            }
            Spacer(Modifier.weight(4f))
            if (complete) {
                Box(Modifier.widthIn(max = 336.dp).fillMaxWidth().oobeButtonEntrance(complete)) {
                    InlineErrorGroup(error, 10.dp) {
                        OobePrimaryButton("进入财小通", { if (active) onNext() }, Modifier.fillMaxWidth(), enabled = !saving && active)
                    }
                }
                Spacer(Modifier.height(60.dp))
            } else {
                AppIconButton(onClick = { if (active) onNext() }, modifier = Modifier.size(70.dp).onGloballyPositioned {
                    val position = it.positionInRoot()
                    onStartBounds(Rect(position, position + Offset(it.size.width.toFloat(), it.size.height.toFloat())))
                }.oobeButtonEntrance(complete), cornerRadius = 35.dp, backgroundColor = Color.White.copy(alpha = .72f)) {
                    Icon(MiuixIcons.ChevronForward, "开始设置", Modifier.size(28.dp), tint = Color(0xFF191348))
                }
                Spacer(Modifier.weight(2f))
            }
        }
    }
}

@Composable
internal fun SetupAppearance(settings: ThemeSettings, onBack: () -> Unit, active: Boolean = true, onNext: () -> Unit) {
    val goBack = { if (active) onBack() }
    BackHandler(enabled = active, onBack = goBack)
    OobePage("选择外观", onBack = goBack,
        footer = { OobePrimaryButton("完成设置", { if (active) onNext() }, Modifier.fillMaxWidth(), enabled = active) }) {
        ThemeSettingsPage(
            modifier = Modifier.padding(horizontal = 12.dp),
            themeMode = settings.themeMode, accentMode = settings.accentMode,
            onThemeSelectionChange = settings::setThemeSelection,
            floatingBottomBar = settings.floatingBottomBar, onFloatingBottomBarChange = settings::setFloating,
            floatingBottomBarStyle = settings.floatingBottomBarStyle, onFloatingBottomBarStyleChange = settings::setFloatingStyle,
            blurBottomBar = settings.blurBottomBar, onBlurBottomBarChange = settings::setBlurBottom,
            blurTopBar = settings.blurTopBar, onBlurTopBarChange = settings::setBlurTop,
            glassStyle = settings.glassStyle, onGlassStyleChange = settings::setGlassStyle,
            navTransitionStyle = settings.navTransitionStyle, onNavTransitionStyleChange = settings::setNavTransitionStyle,
            swipeBack = settings.swipeBack, onSwipeBackChange = settings::setSwipeBack,
            preview = { SetupAppearancePreview(settings) },
        )
    }
}

private val previewPayment = PaymentUiState(needsLogin = false,
    account = PaymentAccount("preview", "preview", "1", "统一账户", 8650))

@Composable
private fun SetupAppearancePreview(settings: ThemeSettings) {
    val colors = MiuixTheme.colorScheme
    val scanInTopBar = !settings.floatingBottomBar.value || shouldShowMainNavigationRail(settings)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
        BarBlurHost(settings.blurBottomBar.value, settings.blurTopBar.value, Modifier.fillMaxWidth()) {
            BarBackdropContent(Modifier.matchParentSize().background(colors.surface)) {}
            MainNavigationPreview(settings) {
                Column(Modifier.fillMaxWidth()) {
                    if (scanInTopBar) {
                        BlurredBar(topGradient = true) {
                            AppSmallTopAppBar(title = "", color = Color.Transparent, defaultWindowInsetsPadding = false, actions = {
                                AppIconButton(onClick = {}, modifier = Modifier.padding(end = 8.dp)) {
                                    Icon(MiuixIcons.Scan, "扫一扫", Modifier.size(24.dp), tint = colors.onSurface)
                                }
                            })
                        }
                    }
                    PaymentBalanceCard(previewPayment, rechargeLoading = false, onRecharge = {},
                        modifier = Modifier.testTag("oobe-balance-card"))
                }
            }
        }
    }
}
