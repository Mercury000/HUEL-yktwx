package com.mercury.yktwx.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import android.widget.Toast
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mercury.yktwx.AppNavigationViewModel
import com.mercury.yktwx.ui.nav.AppRoute as Screen
import com.mercury.yktwx.RechargeViewModel
import com.mercury.yktwx.FeeViewModel
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.CardPasswordViewModel
import com.mercury.yktwx.CardServiceViewModel
import com.mercury.yktwx.MyViewModel
import com.mercury.yktwx.AvatarViewModel
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.HallViewModel
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.Hui798ViewModel
import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.AppUpdateViewModel
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.data.FeeRoomBinding
import com.mercury.yktwx.data.CampusCardAction
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.ReadEnvironment
import com.mercury.yktwx.data.ReadPage
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.data.PreferencesLoadState
import com.mercury.yktwx.data.WechatRechargeLaunch
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mercury.yktwx.BillViewModel
import com.mercury.yktwx.PaymentViewModel
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.R
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.nav.*
import com.mercury.yktwx.ui.pages.*
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class AuthPage : NavKey { Restoring, Login, Main }

@Composable
fun YktApp(settings: ThemeSettings, viewModel: PaymentViewModel, billViewModel: BillViewModel, consent: UsageConsent? = null,
    onExit: (() -> Unit)? = null) {
    val loaded by settings.repository.state.collectAsStateWithLifecycle()
    if (loaded !is PreferencesLoadState.Ready) {
        AppTheme(ColorSchemeMode.System, 0) {
            PreferencesEntry(loaded is PreferencesLoadState.Failed, settings.repository::retry)
        }
        return
    }
    val mode = when (settings.themeMode.value) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    AppTheme(mode, settings.accentMode.value) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val session by viewModel.sessionState.collectAsStateWithLifecycle()
        val authenticated = if (state.preview) !state.needsLogin else session.username != null
        val agreed = consent?.accepted != false
        val updateModel: AppUpdateViewModel = viewModel()
        val updateState by updateModel.state.collectAsStateWithLifecycle()
        val updateInstall = rememberUpdateInstallAction(updateModel)
        // Release metadata needs consent, but never a campus account or successful login.
        AppUpdateEffect(updateModel, settings.automaticUpdates.value,
            allowed = consent?.accepted == true && !state.preview && !LocalInspectionMode.current,
            channel = settings.updateChannel.value)
        val activity = LocalActivity.current
        Scaffold(modifier = Modifier.semantics { testTagsAsResourceId = true }) {
            if (!updateState.ready) {
                PreferencesEntry(updateState.loadFailed, updateModel::retryLoading)
            } else if (!agreed) {
                // Life model constructors recover orders over the network. Defer the
                // business composition, navigation and payment launches until agreement.
                ConsentEntryPage(settings, state, authenticated)
            } else if (updateState.required) {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                AppUpdateDialog(updateState, onDownload = updateInstall, onDismiss = {})
            } else {
                ApprovedApp(settings, viewModel, billViewModel, consent, state, authenticated, updateModel, updateState, updateInstall)
            }
            if (consent != null && updateState.ready) RequiredUsageConsent(consent,
                onExit = { if (onExit != null) onExit() else activity?.finishAndRemoveTask() })
        }
    }
}

@Composable
private fun ApprovedApp(settings: ThemeSettings, viewModel: PaymentViewModel, billViewModel: BillViewModel,
    consent: UsageConsent?, state: PaymentUiState, authenticated: Boolean,
    updateModel: AppUpdateViewModel, updateState: AppUpdateUiState, onInstallUpdate: () -> Unit) {
    val agreed = consent?.accepted != false
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val navigation: AppNavigationViewModel = viewModel()
    LaunchedEffect(session, state.restoringLogin, state.preview, state.username, state.needsLogin) {
        if (!state.restoringLogin && (state.preview || session.restored)) {
            val owner = if (state.preview) state.username.takeUnless { state.needsLogin } else session.username
            navigation.bind(owner, state.preview, session.generation)
        }
    }
    var hadLogin by remember { mutableStateOf(false) }
    LaunchedEffect(authenticated, state.restoringLogin) {
        if (!state.restoringLogin) {
            if (hadLogin && !authenticated) consent?.revoke()
            hadLogin = authenticated
        }
    }
    val billState by billViewModel.state.collectAsStateWithLifecycle()
    val rechargeModel: RechargeViewModel = viewModel()
    val feeModel: FeeViewModel = viewModel()
    val cardModel: CardServiceViewModel = viewModel()
    val passwordModel: CardPasswordViewModel = viewModel()
    val myModel: MyViewModel = viewModel()
    val avatarModel: AvatarViewModel = viewModel()
    val dormModel: HallViewModel = viewModel()
    val context = LocalContext.current
    var previewWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(agreed, session, state.restoringLogin, state.needsLogin, state.username, state.preview) {
        if ((agreed || state.needsLogin) && !state.restoringLogin && (state.preview || session.restored)) {
            val owner = if (state.preview) state.username.takeUnless { state.needsLogin } else session.username
            viewModel.bindPresentation(owner, state.preview, session.generation)
            avatarModel.accountChanged(owner.orEmpty(), state.preview, session.generation)
            feeModel.accountChanged(owner.orEmpty(), state.preview)
            rechargeModel.accountChanged(owner.orEmpty(), state.preview)
            cardModel.accountChanged(owner.orEmpty(), state.preview)
            passwordModel.accountChanged(owner.orEmpty(), state.preview)
            if (state.preview || previewWasActive) {
                billViewModel.accountChanged(owner)
                myModel.accountChanged(owner.orEmpty(), state.preview)
                dormModel.accountChanged(owner.orEmpty(), state.preview)
            }
            previewWasActive = state.preview
        }
    }
    WechatLaunchEffect(rechargeModel.events, rechargeModel::canLaunchWechat, rechargeModel::wechatLaunched, rechargeModel::launchFailed)
    WechatLaunchEffect(feeModel.events, feeModel::canLaunchWechat, feeModel::wechatLaunched, feeModel::launchFailed)
    LaunchedEffect(rechargeModel) {
        rechargeModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(cardModel) {
        cardModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(passwordModel) {
        passwordModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(cardModel, rechargeModel) {
        cardModel.rechargeRequests.collect { request ->
            if (cardModel.accepts(request)) {
                cardModel.dismiss()
                rechargeModel.openOthers(request.username, request.config, request.preview)
                rechargeModel.submit(request.amountCents)
            }
        }
    }
    val authPage = when {
        state.restoringLogin -> AuthPage.Restoring
        !authenticated -> AuthPage.Login
        else -> AuthPage.Main
    }
    LaunchedEffect(authPage) { updateModel.dismiss() }
    val authBackStack = remember { navBackStackOf(authPage) }
    val shellViewModelOwner = checkNotNull(LocalViewModelStoreOwner.current)
    SideEffect { authBackStack[0] = authPage }
    NavDisplay(
        backStack = authBackStack,
        modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        onBack = {},
        transition = miuixNavigationTransition(settings.navTransitionStyle.value),
        effects = rememberMiuixNavigationEffects(transitionStyle = settings.navTransitionStyle.value),
    ) {
        entry<AuthPage>(swipeDismiss = NavSwipeDirection.None) { page ->
            val interactive = page == authPage
            Box(
                Modifier.fillMaxSize()
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {})
                    .pointerInput(interactive) {
                        if (!interactive) awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
            ) {
                when (page) {
                    AuthPage.Restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    AuthPage.Login -> LoginPage(if (agreed) state else PaymentUiState(username = state.username, busy = state.busy, restoringLogin = state.restoringLogin),
                        onLogin = { username, password -> if (interactive && agreed) viewModel.login(username, password) },
                        consent = consent, onInputEdited = viewModel::loginInputEdited,
                        updateNotice = {
                            InlineUpdateNotice(updateState.copy(noticeVisible = interactive && agreed && updateState.noticeVisible),
                                updateModel::openDetails, updateModel::dismissNotice, bottomSpacing = 18.dp)
                        },
                        updateDialog = {
                            AppUpdateDialog(updateState.copy(visible = interactive && agreed && updateState.visible),
                                onSkip = updateModel::skip, onDownload = onInstallUpdate, onDismiss = updateModel::ignore)
                        })
                    AuthPage.Main -> {
                        // Keep only shell identity for the outgoing animation.
                        // Latest logged-out state has already removed payment codes.
                        // Scope survives process recreation, but changes on every real account replacement.
                        key(navigation.scope) {
                            // Service models remain owned by the app across navigation and login changes.
                            CompositionLocalProvider(LocalViewModelStoreOwner provides shellViewModelOwner) {
                                // One overlay host above both tabs and secondary page layers.
                                Scaffold {
                                    if (!navigation.readyFor(state.username, state.preview)) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                    } else MainContent(
                                        settings, state,
                                        if (interactive) billState else BillUiState(),
                                        viewModel, billViewModel, rechargeModel, feeModel, cardModel, passwordModel, myModel, dormModel, avatarModel,
                                        isActive = interactive, navigation = navigation,
                                        onLogout = { consent?.revoke(); viewModel.logout() },
                                        updateModel = updateModel, updateState = updateState,
                                        onInstallUpdate = onInstallUpdate,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsentEntryPage(settings: ThemeSettings, state: PaymentUiState, authenticated: Boolean) {
    Box(Modifier.fillMaxSize().testTag(if (authenticated) "consent-main-shell" else "consent-login-shell")) {
        // This local page never exposes retained codes or interactive accessibility nodes.
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }) {
            when {
                state.restoringLogin -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                authenticated -> BarBlurHost(settings.blurBottomBar.value, false) {
                    MainNavigationScaffold(settings, 0, {}) { padding ->
                        BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                            PaymentPage(PaymentUiState(needsLogin = false, username = state.username, busy = true),
                                settings, padding.calculateBottomPadding() + 28.dp, onRefresh = {},
                                isActive = false, scanEnabled = false)
                        }
                    }
                }
                else -> LoginPage(PaymentUiState(username = state.username, restoringLogin = true)) { _, _ -> }
            }
        }
    }
}

@Composable
private fun RequiredUsageConsent(consent: UsageConsent, onExit: () -> Unit) {
    val context = LocalContext.current
    var hintShown by rememberSaveable(UsageNotice.hash) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(consent, consent.accepted) {
        if (!hintShown) consent.confirmationHint?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            hintShown = true
        }
    }
    fun exit() { if (!saving && !consent.accepted) onExit() }
    UsageNoticeSheet(show = !consent.accepted, onDismiss = ::exit, saving = saving, error = error, onAgree = {
        if (!saving && !consent.accepted) {
            saving = true; error = null
            scope.launch {
                try { consent.accept() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "无法保存确认记录，请重试" }
                finally { saving = false }
            }
        }
    })
    BackHandler(enabled = !consent.accepted) { exit() }
}

/** Shared allowlisted launch for recharge and living fees; payment results are server-queried. */
@Composable
private fun WechatLaunchEffect(events: Flow<WechatRechargeLaunch>, canLaunch: () -> Boolean, onLaunched: () -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val allowed by rememberUpdatedState(canLaunch)
    val launched by rememberUpdatedState(onLaunched)
    val failed by rememberUpdatedState(onFailed)
    LaunchedEffect(events) {
        events.collect { request ->
            if (allowed()) {
                try {
                    launched()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.uri)).setPackage("com.tencent.mm"))
                } catch (_: ActivityNotFoundException) { failed() }
                catch (_: SecurityException) { failed() }
            }
        }
    }
}

/** Only composed after authentication, so tabs and their navigation cannot leak into login. */
@Composable
private fun MainContent(
    settings: ThemeSettings,
    state: PaymentUiState,
    billState: BillUiState,
    viewModel: PaymentViewModel,
    billViewModel: BillViewModel,
    rechargeModel: RechargeViewModel,
    feeModel: FeeViewModel,
    cardModel: CardServiceViewModel,
    passwordModel: CardPasswordViewModel,
    myModel: MyViewModel,
    dormModel: HallViewModel,
    avatarModel: AvatarViewModel,
    isActive: Boolean,
    navigation: AppNavigationViewModel,
    onLogout: () -> Unit,
    updateModel: AppUpdateViewModel,
    updateState: AppUpdateUiState,
    onInstallUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val paymentSuccess by viewModel.paymentSuccess.collectAsStateWithLifecycle()
    val rechargeState by rechargeModel.state.collectAsStateWithLifecycle()
    val feeState by feeModel.state.collectAsStateWithLifecycle()
    val feeEntry by feeModel.entry.collectAsStateWithLifecycle()
    val feeDraft by feeModel.form.collectAsStateWithLifecycle()
    val cardState by cardModel.state.collectAsStateWithLifecycle()
    val passwordState by passwordModel.state.collectAsStateWithLifecycle()
    val collectedMyState by myModel.state.collectAsStateWithLifecycle()
    val avatarState by avatarModel.state.collectAsStateWithLifecycle()
    val collectedDormState by dormModel.state.collectAsStateWithLifecycle()
    val lifeModel: LifeDevicesViewModel = viewModel()
    val lifeState by lifeModel.state.collectAsStateWithLifecycle()
    val hui798Model: Hui798ViewModel = viewModel()
    val hui798State by hui798Model.state.collectAsStateWithLifecycle()
    val hellobikeModel: HellobikeViewModel = viewModel(key = "hellobike-${state.preview}")
    LaunchedEffect(hellobikeModel, state.preview) { hellobikeModel.initialize(state.preview) }
    val hellobikeState by hellobikeModel.state.collectAsStateWithLifecycle()
    val pangguiConnection by lifeModel.accountConnection.collectAsStateWithLifecycle()
    val hui798Connection by hui798Model.accountConnection.collectAsStateWithLifecycle()
    val hellobikeConnection by hellobikeModel.accountConnection.collectAsStateWithLifecycle()
    val managedAccounts = listOf(
        ManagedLifeAccount(LifeProvider.Hui798, hui798Connection, hui798State.restoring,
            hui798State.accountBusy || hui798State.loggingIn, hui798State.accountError, hui798Model.restorationOwner),
        ManagedLifeAccount(LifeProvider.Panggui, pangguiConnection, lifeState.restoring,
            lifeState.accountBusy || lifeState.loggingIn, lifeState.accountError, lifeModel.restorationOwner),
        ManagedLifeAccount(LifeProvider.Hellobike, hellobikeConnection, hellobikeState.restoring,
            hellobikeState.accountBusy || hellobikeState.loggingIn || hellobikeState.paymentInFlight, hellobikeState.accountError,
            hellobikeModel.restorationOwner))
    LifePaymentEffect(lifeState.paymentLaunch, isActive && !state.preview && !state.needsLogin,
        lifeModel::claimLaundryPayment, lifeModel::laundryPaymentReturned)
    val customAvatar = avatarState.bytes != null && avatarState.owner == state.username && avatarState.preview == state.preview && !state.needsLogin
    val avatarPending = !state.needsLogin && (avatarState.owner != state.username || avatarState.preview != state.preview || avatarState.restoring)
    val avatarImage = avatarState.image.takeIf { customAvatar }
    val myState = if (state.needsLogin) MyUiState() else if (customAvatar)
        collectedMyState.copy(avatar = avatarState.bytes, avatarError = null)
        else if (avatarPending) collectedMyState.copy(avatar = null) else collectedMyState
    val dormState = if (state.needsLogin) DormUiState() else collectedDormState
    val feeProject = feeState.config?.project ?: feeEntry?.project
    val dormPaymentVisible = feeEntry != null
    val feeOpening = feeProject.takeIf { dormPaymentVisible && feeState.loading && feeState.config == null && feeState.stage == FeeStage.Query }
    val feeTarget = feeState.currentRoom?.takeIf { it.selections == feeEntry?.fixed }
    val feeSaveOnly = feeEntry?.saveOnly == true
    val pagerState = rememberPagerState(initialPage = navigation.tab, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabs = remember(pagerState, scope) { MainTabsPagerState(pagerState, scope) }
    LaunchedEffect(pagerState.currentPage) { tabs.syncPage(); navigation.tab = pagerState.currentPage }
    val backStack = navigation.backStack
    val layers = backStack.filterIsInstance<Screen>().drop(1)
    val lifeRoute = layers.lastOrNull() as? Screen.LifeDevice
    val lifeProvider = lifeRoute?.provider ?: LifeProvider.Panggui
    val paymentPageSelected = isActive && pagerState.currentPage == 0 && layers.isEmpty()
    val paymentPageCovered = paymentPageSelected &&
        (dormPaymentVisible || rechargeState.visible || cardState.visible || passwordState.visible ||
            lifeState.loginVisible || hui798State.loginVisible || hellobikeState.loginVisible)
    val accountPageSelected = isActive && (layers.lastOrNull() == Screen.Account ||
        pagerState.currentPage == 3 && layers.isEmpty())
    val networkState = rememberNetworkState(state.preview)
    val network by networkState
    val foregroundState by rememberForegroundState()
    val foreground = foregroundState.active
    LifePaymentEffect(hellobikeState.paymentLaunch, foreground && isActive && !state.preview && !state.needsLogin,
        hellobikeModel::claimPayment, hellobikeModel::paymentReturned)
    HellobikeLocationPermissionEffect(hellobikeState.locationPermissionRequest,
        foreground && isActive && !state.preview && !state.needsLogin, hellobikeModel)
    val analytics = viewModel<AnalyticsViewModel>().analytics
    val analyticsActive by analytics.active.collectAsStateWithLifecycle()
    val analyticsPage = when {
        !isActive || state.preview || state.needsLogin || layers.isNotEmpty() -> null
        pagerState.settledPage == 0 -> AnalyticsPage.Payment
        pagerState.settledPage == 2 -> if (billState.statsVisible) AnalyticsPage.BillStats else AnalyticsPage.BillList
        else -> null
    }
    val validPaymentCode = state.code != null && state.problem == null && !state.busy && state.error == null
    LaunchedEffect(analyticsActive, foreground, foregroundState.epoch, analyticsPage, validPaymentCode, state.busy,
        state.errorKind, state.error, paymentPageCovered) {
        if (analyticsActive && foreground && !state.preview) {
            analytics.pageVisible(analyticsPage)
            if (analyticsPage == AnalyticsPage.Payment && !paymentPageCovered) {
                val failure = if (!validPaymentCode && !state.busy && !state.restoringLogin && state.error != null)
                    when (state.errorKind) {
                        com.mercury.yktwx.data.ReadFailureKind.Network -> if (network.online == false) AnalyticsError.Offline else AnalyticsError.Network
                        com.mercury.yktwx.data.ReadFailureKind.Authentication -> AnalyticsError.Auth
                        else -> AnalyticsError.Business
                    } else null
                analytics.paymentCodeStatus(validPaymentCode, failure)
            }
        }
    }
    LaunchedEffect(foreground, isActive, state.preview) {
        lifeModel.environment(foreground && isActive && !state.preview)
        hui798Model.environment(foreground && isActive && !state.preview)
        hellobikeModel.environment(foreground && isActive && !state.preview)
    }
    val campusSession by viewModel.sessionState.collectAsStateWithLifecycle()
    LaunchedEffect(state.username, state.preview, campusSession.generation, lifeState.restoring, hui798State.restoring, hellobikeState.restoring,
        lifeModel.restorationOwner, hui798Model.restorationOwner, hellobikeModel.restorationOwner) {
        val generation = campusSession.generation
        lifeModel.bindPresentation(state.username, state.preview, generation)
        hui798Model.bindPresentation(state.username, state.preview, generation)
        hellobikeModel.bindPresentation(state.username, state.preview, generation)
    }
    LaunchedEffect(isActive, foreground, network.online, lifeState.loginVisible, hui798State.loginVisible,
        hellobikeState.loginVisible, lifeState.laundry?.sessionId, lifeState.device?.id, lifeState.selectedSkuId,
        hellobikeState.selectedSessionId, lifeState.paymentRestorePending, hellobikeState.paymentRestorePending, layers.lastOrNull()) {
        val active = isActive && foreground && !state.preview
        val paymentPage = layers.lastOrNull() is Screen.LifeDevice
        lifeModel.resumePresentation(active && paymentPage, network.online == true)
        hui798Model.resumePresentation(active, network.online == true)
        hellobikeModel.resumePresentation(active, network.online == true, paymentPage)
    }
    DisposableEffect(lifeModel, hui798Model, hellobikeModel) { onDispose {
        lifeModel.environment(false); hui798Model.environment(false); hellobikeModel.environment(false)
    } }
    val lifeAccountPageVisible = layers.lastOrNull() == Screen.Settings
    LaunchedEffect(foreground, foregroundState.epoch, isActive, state.preview, network.online, lifeAccountPageVisible,
        paymentPageSelected, lifeState.restoring, hui798State.restoring, hellobikeState.restoring) {
        if (foreground && isActive && !state.preview && network.online == true && (lifeAccountPageVisible || paymentPageSelected)) {
            lifeModel.checkAccount(); hui798Model.checkAccount(); hellobikeModel.checkAccount()
        }
    }
    val readPage = when {
        !isActive || paymentSuccess != null || dormPaymentVisible || rechargeState.visible || cardState.visible || passwordState.visible ||
            rechargeState.loading || cardState.loading || passwordState.loading || feeOpening != null -> null
        layers.lastOrNull() == Screen.Profile -> ReadPage.Profile
        layers.lastOrNull() == Screen.Account -> ReadPage.Account
        layers.isNotEmpty() -> null
        else -> ReadPage.entries[pagerState.currentPage]
    }
    val activeReadPage = readPage.takeIf { foreground }
    val readEnvironment = ReadEnvironment(
        page = activeReadPage,
        foreground = foreground,
        online = network.online,
        epoch = network.epoch,
        paymentVisible = paymentPageSelected && !paymentPageCovered,
        paymentCovered = paymentPageCovered,
        accountVisible = accountPageSelected,
        resumeEpoch = foregroundState.epoch,
    )

    val readLifecycle = LocalLifecycleOwner.current.lifecycle
    val latestReadEnvironment by rememberUpdatedState(readEnvironment)
    val publishReadEnvironment = remember(viewModel, myModel, dormModel, billViewModel) {
        { environment: ReadEnvironment ->
            viewModel.onReadEnvironment(environment)
            myModel.onReadEnvironment(environment)
            dormModel.onReadEnvironment(environment)
            billViewModel.onReadEnvironment(environment)
        }
    }
    // Publish normal page/query changes from composition, but revoke reads synchronously at
    // ON_STOP: a stopped Activity may not render another frame to run this LaunchedEffect.
    LaunchedEffect(readEnvironment, state.username, billState.readIdentity, readLifecycle) {
        publishReadEnvironment(if (readLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) readEnvironment
            else readEnvironment.copy(page = null, foreground = false))
    }
    DisposableEffect(publishReadEnvironment, readLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                publishReadEnvironment(latestReadEnvironment.copy(page = null, foreground = false))
            }
        }
        readLifecycle.addObserver(observer)
        onDispose {
            readLifecycle.removeObserver(observer)
            publishReadEnvironment(ReadEnvironment.Inactive)
        }
    }
    LaunchedEffect(isActive) {
        if (!isActive) feeModel.dismiss()
    }
    LaunchedEffect(isActive, foregroundState, networkState.value.online, feeEntry) {
        feeModel.restoreIfNeeded(isActive && foreground, state.preview || networkState.value.online == true)
    }
    LaunchedEffect(isActive, foregroundState, networkState.value.online, rechargeState.visible, rechargeState.loading) {
        rechargeModel.restoreIfNeeded(isActive && foreground, state.preview || networkState.value.online == true)
    }
    LaunchedEffect(isActive, foregroundState, networkState.value.online, cardState.visible, cardState.loading,
        passwordState.visible, passwordState.loading) {
        val allowed = state.preview || networkState.value.online == true
        cardModel.restoreIfNeeded(isActive && foreground, allowed)
        passwordModel.restoreIfNeeded(isActive && foreground, allowed)
    }
    PaymentMotionEffect(
        enabled = settings.paymentMotionDetection.value && isActive && pagerState.currentPage == 0 &&
            !pagerState.isScrollInProgress && layers.isEmpty() &&
            feeOpening == null && !dormPaymentVisible && !rechargeState.visible && !rechargeState.loading && !cardState.visible &&
            !cardState.loading && !passwordState.visible && !passwordState.loading &&
            !state.preview && !state.needsLogin && state.account != null && state.problem == null && paymentSuccess == null,
        username = state.username, model = viewModel,
    )
    // Automatic results stay inline; user-opened details are confined to pages with an update entry.
    val updatePromptAllowed = isActive &&
        (layers.lastOrNull() == Screen.Settings || layers.isEmpty())
    LaunchedEffect(updatePromptAllowed, updateState.detailsPending) {
        updateModel.presentIfAllowed(updatePromptAllowed)
    }
    val updateNotice: @Composable () -> Unit = {
        InlineUpdateNotice(updateState, updateModel::openDetails, updateModel::dismissNotice)
    }
    fun open(screen: Screen) {
        if (isActive) navigation.open(screen)
    }
    fun close() {
        if (isActive) navigation.close()
    }
    fun openLifeScanner() {
        if (!isActive ||
            lifeState.waterBusy || lifeState.laundryBusy || hui798State.busy || hellobikeState.busy ||
            lifeState.paymentLaunch != null || hellobikeState.paymentInFlight) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeModel.openUnifiedScan()
        navigation.showLife(Screen.LifeDevice())
    }
    fun connectLifeAccount() {
        if (!isActive || layers.isNotEmpty()) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        hui798Model.dismissLogin()
        hellobikeModel.dismissLogin()
        lifeModel.connectAccount()
    }
    fun connectHui798Account() {
        if (!isActive || layers.isNotEmpty()) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeModel.dismissLogin()
        hellobikeModel.dismissLogin()
        hui798Model.connectAccount()
    }
    fun connectHellobikeAccount() {
        if (!isActive || layers.isNotEmpty() || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeModel.dismissLogin(); hui798Model.dismissLogin()
        hellobikeModel.connectAccount()
    }
    fun manageLifeLogin(provider: LifeProvider, change: Boolean) {
        if (!isActive || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        val opened = when (provider) {
            LifeProvider.Hui798 -> { lifeModel.dismissLogin(); hellobikeModel.dismissLogin(); hui798Model.manageLogin(change) }
            LifeProvider.Panggui -> { hui798Model.dismissLogin(); hellobikeModel.dismissLogin(); lifeModel.manageLogin(change) }
            LifeProvider.Hellobike -> { lifeModel.dismissLogin(); hui798Model.dismissLogin(); hellobikeModel.manageLogin(change) }
        }
        if (!opened) Toast.makeText(context, "正在处理请求，请稍后重试", Toast.LENGTH_SHORT).show()
    }
    fun checkLifeAccount(provider: LifeProvider) {
        if (!isActive || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        when (provider) {
            LifeProvider.Hui798 -> hui798Model.checkAccount(force = true)
            LifeProvider.Panggui -> lifeModel.checkAccount(force = true)
            LifeProvider.Hellobike -> hellobikeModel.checkAccount(force = true)
        }
    }
    fun openLifeSession(feature: LifeFeature, sessionId: String) {
        if (!isActive) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeModel.openSession(feature, sessionId)
        navigation.showLife(Screen.LifeDevice(LifeProvider.Panggui, feature, sessionId = sessionId, account = lifeModel.restorationOwner))
    }
    fun openHui798Session(id: String) {
        if (!isActive || hui798State.busy) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        hui798Model.openSession(id)
        navigation.showLife(Screen.LifeDevice(LifeProvider.Hui798, LifeFeature.Water, sessionId = id, account = hui798Model.restorationOwner))
    }
    fun openHellobikeSession(id: String) {
        if (!isActive || hellobikeState.busy || hellobikeState.paymentInFlight || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        hellobikeModel.openSession(id)
        navigation.showLife(Screen.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride, sessionId = id, account = hellobikeModel.restorationOwner))
    }
    fun scannedLifeDevice(raw: String, manual: Boolean = false) {
        if (!isActive || lifeProvider != LifeProvider.Panggui || layers.lastOrNull() !is Screen.LifeDevice ||
            hui798State.busy || lifeState.waterBusy || lifeState.laundryBusy || hellobikeState.busy || hellobikeState.paymentInFlight) return
        val route = if (manual) UnifiedDeviceCode.identifyManual(raw) else UnifiedDeviceCode.identify(raw)
        when (route?.provider) {
            LifeProvider.Hui798 -> hui798Model.openDevice(route.code)
            LifeProvider.Hellobike -> hellobikeModel.openDevice(route.code)
            else -> lifeModel.scanned(raw)
        }
        if (route != null) navigation.showLife(Screen.LifeDevice(route.provider, route.feature, deviceId = route.code,
            account = when (route.provider) {
                LifeProvider.Panggui -> lifeModel.restorationOwner
                LifeProvider.Hui798 -> hui798Model.restorationOwner
                LifeProvider.Hellobike -> hellobikeModel.restorationOwner
            }))
    }
    // Apply a restored route only after its encrypted platform store is ready.
    LaunchedEffect(lifeRoute, lifeState.paymentRestorePending, hellobikeState.paymentRestorePending, lifeState.cancelOrderConfirmation) {
        if (lifeRoute?.provider != LifeProvider.Panggui) lifeModel.discardRestoredPayment()
        if (lifeRoute?.provider != LifeProvider.Hellobike) hellobikeModel.discardRestoredPayment()
        if (lifeRoute?.provider != LifeProvider.Panggui || lifeRoute.sessionId != lifeState.cancelOrderConfirmation)
            lifeModel.dismissLaundryCancellation()
    }
    val lifeStoreReady = when (lifeProvider) {
        LifeProvider.Panggui -> !lifeState.restoring
        LifeProvider.Hui798 -> !hui798State.restoring
        LifeProvider.Hellobike -> !hellobikeState.restoring
    }
    val lifeOwner = when (lifeProvider) {
        LifeProvider.Panggui -> lifeModel.restorationOwner
        LifeProvider.Hui798 -> hui798Model.restorationOwner
        LifeProvider.Hellobike -> hellobikeModel.restorationOwner
    }
    LaunchedEffect(lifeRoute, lifeStoreReady, lifeOwner, foreground, isActive, network.online) {
        val route = lifeRoute ?: return@LaunchedEffect
        if (!isActive || !foreground || !lifeStoreReady) return@LaunchedEffect
        if (!navigation.needsLifeRestore(route)) {
            if (route.account != null && route.account != lifeOwner) {
                lifeModel.discardRestoredPayment(); lifeModel.dismissLaundryCancellation(); hellobikeModel.discardRestoredPayment()
                navigation.close()
                Toast.makeText(context, "生活服务账户已变化，请重新选择设备", Toast.LENGTH_SHORT).show()
            }
            return@LaunchedEffect
        }
        val result = when (route.provider) {
            LifeProvider.Panggui -> restoreLifeRoute(route, network.online, lifeModel)
            LifeProvider.Hui798 -> restoreLifeRoute(route, network.online, hui798Model)
            LifeProvider.Hellobike -> restoreLifeRoute(route, network.online, hellobikeModel)
        }
        when (result) {
            LifeRouteRestore.Restored -> navigation.lifeRestored()
            LifeRouteRestore.WaitingForAccount, LifeRouteRestore.WaitingForNetwork -> Unit
            LifeRouteRestore.AccountChanged, LifeRouteRestore.Missing -> {
                when (route.provider) {
                    LifeProvider.Panggui -> lifeModel.discardRestoredPayment()
                    LifeProvider.Hellobike -> hellobikeModel.discardRestoredPayment()
                    LifeProvider.Hui798 -> Unit
                }
                navigation.close()
                Toast.makeText(context, if (result == LifeRouteRestore.AccountChanged) "生活服务账户已变化，请重新选择设备"
                    else "设备或订单记录已失效，请重新选择", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Keep stable route parameters in sync as a device becomes an order. No business calls here.
    LaunchedEffect(lifeRoute, lifeState, hui798State, hellobikeState) {
        val route = lifeRoute ?: return@LaunchedEffect
        if (!isActive || !lifeStoreReady || navigation.needsLifeRestore(route) || route.account != null && route.account != lifeOwner) return@LaunchedEffect
        val current = when (route.provider) {
            LifeProvider.Panggui -> {
                val session = if (lifeState.feature == LifeFeature.Water) lifeState.water?.sessionId else lifeState.laundry?.sessionId
                Screen.LifeDevice(feature = lifeState.feature, sessionId = session.takeUnless { lifeState.scanning },
                    deviceId = lifeState.scannedSerial.takeIf { !lifeState.scanning && session == null },
                    account = lifeModel.restorationOwner, skuId = lifeState.selectedSkuId.takeIf(String::isNotBlank))
            }
            LifeProvider.Hui798 -> route.copy(sessionId = hui798State.selectedSessionId,
                deviceId = hui798State.scannedDeviceId.takeIf { hui798State.selectedSessionId == null }, account = hui798Model.restorationOwner)
            LifeProvider.Hellobike -> route.copy(sessionId = hellobikeState.selectedSessionId,
                deviceId = hellobikeState.scannedBikeNo.takeIf { hellobikeState.selectedSessionId == null }, account = hellobikeModel.restorationOwner)
        }
        if (current != route) navigation.showLife(current)
    }
    fun openFee(project: FeeProject, requestedRoom: FeeRoomBinding? = null, saveOnly: Boolean = false) {
        if (!isActive) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        rechargeModel.dismiss()
        cardModel.dismiss()
        passwordModel.dismiss()
        feeModel.open(project, state.username, state.preview, requestedRoom, saveOnly)
    }
    fun openBillStats() {
        if (!isActive) return
        billViewModel.openStats()
    }
    fun openBillDetail(record: BillRecord) {
        if (!isActive) return
        billViewModel.openDetail(record)
        open(Screen.BillDetail(billViewModel.reference(record)))
    }
    fun recharge() {
        if (isActive && feeOpening == null && !passwordState.loading && !cardState.loading && !rechargeState.loading) {
            if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
            cardModel.dismiss(); passwordModel.dismiss()
            rechargeModel.open(state.username, state.account?.account, state.preview)
        }
    }
    fun cardAction(action: CampusCardAction) {
        if (isActive) {
            if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
            feeModel.dismiss()
            rechargeModel.dismiss()
            if (action == CampusCardAction.ChangePassword) {
                cardModel.dismiss(); passwordModel.open(state.username, state.preview)
            } else {
                passwordModel.dismiss(); cardModel.dismiss(); cardModel.open(action, state.username, state.preview)
            }
        }
    }
    // Root tabs return to payment; secondary pages and popups keep their own back handling.
    BackHandler(
        enabled = isActive && layers.isEmpty() && !dormPaymentVisible &&
            (tabs.selectedPage != 0 || pagerState.currentPage != 0),
    ) {
        tabs.animateToPage(0)
    }

    val useRail = shouldShowMainNavigationRail(settings)
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val swipeDirection = when {
        !settings.swipeBack.value -> NavSwipeDirection.None
        LocalLayoutDirection.current == LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
        else -> NavSwipeDirection.LeftToRight
    }
    val navigationTransition = miuixNavigationTransition(settings.navTransitionStyle.value)
    val navigationEffects = rememberMiuixNavigationEffects(wideScreen = useRail, transitionStyle = settings.navTransitionStyle.value)
    CompositionLocalProvider(LocalReadOffline provides (network.online == false)) {
        // Match the demo: the rail stays beside a clipped navigation pane.
        Row(Modifier.fillMaxSize()) {
            if (useRail) MainNavigationRail(settings, tabs.selectedPage, width >= 1200.dp, tabs::animateToPage)
            Box(Modifier.weight(1f).fillMaxHeight().then(if (useRail) Modifier.clipToBounds() else Modifier)) {
                NavDisplay(
                    backStack = backStack,
                    onBack = ::close,
                    transition = navigationTransition,
                    effects = navigationEffects,
                ) {
                    appEntries(swipeDirection) { screen ->
                        if (screen == Screen.Main) {
                            BarBlurHost(settings.blurBottomBar.value, false) {
                                MainNavigationScaffold(settings, tabs.selectedPage, tabs::animateToPage, railOutside = true) { padding ->
                                    // Match SonyPods: full-bleed content and a constant 28dp scroll margin.
                                    BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                                        val bottom = padding.calculateBottomPadding() + 28.dp
                                        HorizontalPager(
                                            state = pagerState, modifier = Modifier.fillMaxSize().then(
                                                if (!pagerState.isScrollInProgress)
                                                    Modifier.testTag("main-pager-ready-${pagerState.settledPage}")
                                                else Modifier),
                                            verticalAlignment = Alignment.Top,
                                        ) { page ->
                                            when (page) {
                                                0 -> PaymentPage(
                                                    state, settings, bottom,
                                                    updateNotice = updateNotice,
                                                    onRefresh = { if (isActive) viewModel.refresh(manual = true) },
                                                    onRecharge = ::recharge,
                                                    rechargeLoading = rechargeState.loading && !rechargeState.visible,
                                                    isActive = paymentPageSelected && !paymentPageCovered,
                                                    onScan = ::openLifeScanner,
                                                    scanEnabled = !lifeState.waterBusy && !lifeState.laundryBusy && !hui798State.busy && !hellobikeState.busy &&
                                                        lifeState.paymentLaunch == null && !hellobikeState.paymentInFlight,
                                                    lifeLoginRequired = !lifeState.restoring && lifeState.connectedPhone.isBlank(),
                                                    onLifeLogin = ::connectLifeAccount,
                                                    hui798LoginRequired = !hui798State.restoring && !hui798State.connected,
                                                    onHui798Login = ::connectHui798Account,
                                                    hellobikeLoginRequired = !hellobikeState.restoring && !hellobikeState.connected,
                                                    hellobikeLoginExpired = hellobikeConnection.status == LifeAccountStatus.Expired,
                                                    onHellobikeLogin = ::connectHellobikeAccount,
                                                    additionalLifeEntries = hui798State.sessions.filter { it.active }.map { session -> LifeDeviceStatusEntry(LifeFeature.Water,
                                                        when (session.phase) {
                                                            WaterPhase.Ended -> "已完成"
                                                            WaterPhase.Starting -> if (session.uncertain) "启动结果未知" else "正在启动"
                                                            WaterPhase.Stopping -> "正在结束"
                                                            else -> "用水中"
                                                        }, session.device.name, "慧生活798", "hui798-${session.sessionId}", !session.active,
                                                        progress = if (session.active) null else 1f, dismissible = session.canDismissException,
                                                        onOpen = { openHui798Session(session.sessionId) }, onDismiss = { hui798Model.dismissSession(session.sessionId) }) } +
                                                        hellobikeState.sessions.map { session -> LifeDeviceStatusEntry(LifeFeature.Ride,
                                                            hellobikeState.sessionErrors[session.sessionId]?.let { "状态异常" } ?: session.title,
                                                            "哈啰单车", session.region?.label.orEmpty().ifBlank { "哈啰" }, "hellobike-${session.sessionId}",
                                                            completed = session.dismissible, progress = if (session.dismissible) 1f else null,
                                                            dismissible = session.canDismissRecord(hellobikeState.sessionErrors[session.sessionId]),
                                                            onOpen = { openHellobikeSession(session.sessionId) }, onDismiss = { hellobikeModel.dismissSession(session.sessionId) }) },
                                                    waterSessions = lifeState.waterSessions, laundrySessions = lifeState.laundrySessions,
                                                    onOpenLifeSession = ::openLifeSession,
                                                    onDismissLifeSession = lifeModel::dismissSession,
                                                    onUnlost = {
                                                        val account = state.account?.account
                                                        if (isActive && account != null && state.accountCard?.let { it.account == account && it.lost && !it.frozen } == true &&
                                                            feeOpening == null && !rechargeState.loading && !cardState.loading && !passwordState.loading) {
                                                            if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return@PaymentPage
                                                            rechargeModel.dismiss()
                                                            passwordModel.dismiss()
                                                            cardModel.openUnlost(state.username, account, state.preview)
                                                        }
                                                    },
                                                    unlostLoading = cardState.action == CampusCardAction.Unlost && cardState.loading,
                                                    unlostEnabled = feeOpening == null && !rechargeState.loading && !cardState.loading && !passwordState.loading,
                                                )
                                                1 -> HallPage(
                                                    updateNotice = updateNotice,
                                                    settings = settings,
                                                    bottomPadding = bottom,
                                                    onOpenFee = { openFee(it) },
                                                    dorm = dormState,
                                                    isActive = isActive && pagerState.currentPage == 1 && layers.isEmpty(),
                                                    onRefreshDorm = dormModel::refresh,
                                                    onOpenDormFee = { project, target -> openFee(project, target) },
                                                    onSelectRoom = dormModel::selectRoom, onRemoveRoom = dormModel::remove,
                                                    onAddRoom = { openFee(it, saveOnly = true) },
                                                    feeOpening = feeOpening, feeOpeningTarget = feeTarget,
                                                    onOpenCardService = ::cardAction,
                                                    othersRechargeLoading = cardState.loading && cardState.action == CampusCardAction.OthersRecharge,
                                                )
                                                2 -> BillPage(
                                                    updateNotice = updateNotice,
                                                    state = billState,
                                                    settings = settings,
                                                    onBack = { tabs.animateToPage(0) },
                                                    onRefresh = billViewModel::refresh,
                                                    onLoadMore = billViewModel::loadMore,
                                                    onDateSelected = { filter ->
                                                        if (filter != billState.dateFilter) analytics.filterApplied(AnalyticsFilter.Date)
                                                        billViewModel.selectDate(filter)
                                                    },
                                                    onFiltersApplied = billViewModel::applyFilters,
                                                    onSearch = billViewModel::search,
                                                    onSearchConfirmed = { cleared -> analytics.filterApplied(if (cleared) AnalyticsFilter.Clear else AnalyticsFilter.Search) },
                                                    onOpenStats = ::openBillStats,
                                                    onShowDetails = billViewModel::showDetails,
                                                    onStatsTypeChange = billViewModel::selectStatsType,
                                                    onRefreshStats = billViewModel::refreshStats,
                                                    onOpenDetail = ::openBillDetail,
                                                    isActive = isActive && pagerState.currentPage == 2 && layers.isEmpty(),
                                                    showBack = false,
                                                    bottomPadding = bottom,
                                                )
                                                3 -> MyPage(
                                                    updateNotice = updateNotice,
                                                    state = myState, payment = state, settings = settings, bottomPadding = bottom,
                                                    customAvatar = customAvatar,
                                                    avatarImage = avatarImage, avatarPending = avatarPending,
                                                    isActive = isActive && pagerState.currentPage == 3 && layers.isEmpty(),
                                                    onRefresh = { myModel.refresh(); viewModel.refreshAccount(manual = true) },
                                                    onProfile = { open(Screen.Profile) }, onAccount = { open(Screen.Account) },
                                                    onBills = { billViewModel.showCurrentMonth(); tabs.animateToPage(2) },
                                                    onRecharge = ::recharge, rechargeLoading = rechargeState.loading && !rechargeState.visible,
                                                    onCardAction = ::cardAction,
                                                    manageLoading = cardState.loading && cardState.action == CampusCardAction.ManageState,
                                                    passwordLoading = passwordState.loading,
                                                    onSettings = { open(Screen.Settings) }, onAbout = { open(Screen.About) },
                                                )
                                            }
                                        }
                                    }
                                    PaymentSuccessDialog(paymentSuccess, viewModel::dismissPaymentSuccess)
                                    CardServiceDialog(
                                        cardState, cardModel::dismiss, cardModel::selectCard, cardModel::changeCard,
                                        cardModel::refreshCaptcha, cardModel::clearRecipient, cardModel::queryRecipient,
                                        cardModel::selectRecipient, cardModel::continueRecharge, cardModel::retryRecipientConfig, cardModel::inputEdited,
                                        savedDraft = cardModel.form.collectAsStateWithLifecycle().value,
                                        onDraftChanged = cardModel::updateForm, onRetryRestore = cardModel::retryRestore,
                                    )
                                    CardPasswordDialog(passwordState, passwordModel::dismiss, passwordModel::selectCard,
                                        passwordModel::submit, passwordModel::inputEdited, passwordModel::retryRestore)
                                    // Keep recharge under the root Scaffold so its overlay has a popup host.
                                    RechargeDialog(
                                        rechargeState, onDismiss = rechargeModel::dismiss, onSubmit = rechargeModel::submit,
                                        onCheck = rechargeModel::checkResult, onInputEdited = rechargeModel::inputEdited,
                                        savedDraft = rechargeModel.form.collectAsStateWithLifecycle().value,
                                        onDraftChanged = rechargeModel::updateForm, onRetry = rechargeModel::retryRestore,
                                        onFinish = {
                                            rechargeModel.finish()
                                        },
                                    )
                                    HallFeeDialog(
                                        state = feeState, visible = isActive && dormPaymentVisible,
                                        project = feeProject, fixedTarget = feeTarget, saveOnly = feeSaveOnly,
                                        onDismiss = feeModel::dismiss,
                                        onSelect = feeModel::select,
                                        onRetry = feeModel::retry,
                                        onRoomAction = feeModel::requestRoomAction, onConfirmRoomAction = feeModel::confirmRoomAction,
                                        onDismissRoomDialog = feeModel::dismissRoomDialog, onConsumeRoomNotice = feeModel::consumeRoomNotice,
                                        onCreate = feeModel::createWithMethod, onMethod = feeModel::chooseMethod,
                                        onProject = {
                                            if (feeEntry?.fixed == null) feeModel.selectFamilyProject(it)
                                        }, onMeterKind = feeModel::selectDormMeterKind,
                                        bindingProjects = dormState.bindingProjects,
                                        onPayAccount = feeModel::payAccount, onCancelPayment = feeModel::cancelPayment, onInputEdited = feeModel::inputEdited,
                                        onContinuePayment = feeModel::continuePayment,
                                        onCheck = feeModel::checkResult,
                                        onFinish = feeModel::dismiss,
                                        savedDraft = feeDraft, onDraftChanged = feeModel::updateForm,
                                        fixedSelection = feeEntry?.fixed,
                                    )
                                }
                            }
                        } else {
                            when (screen) {
                                Screen.Profile -> PersonalProfilePage(myState, settings, ::close, myModel::refresh,
                                    onAvatar = if (isActive && layers.lastOrNull() == Screen.Profile) avatarModel::open else null,
                                    customAvatar = customAvatar, avatarImage = avatarImage, avatarPending = avatarPending)
                                Screen.Account -> AccountDetailPage(state, settings, ::close, ::recharge,
                                    rechargeLoading = rechargeState.loading && !rechargeState.visible,
                                    onRefresh = { viewModel.refreshAccount(manual = true) })
                                Screen.About -> AboutPage(PaddingValues(bottom = 28.dp), isActive && layers.lastOrNull() == Screen.About,
                                    onOpenReferences = { open(Screen.References) }, onBack = ::close)
                                is Screen.BillDetail -> {
                                    val detailActive = isActive && foreground && backStack.lastOrNull() == screen
                                    LaunchedEffect(screen, detailActive, network.online, billState.needsLogin, billState.restoringSelection) {
                                        billViewModel.restoreDetail(screen.bill, detailActive, network.online)
                                    }
                                    DisposableEffect(screen) { onDispose { billViewModel.restoreDetail(screen.bill, false, null) } }
                                    BillDetailPage(billState.detail?.takeIf { it.stableId == screen.bill.id }, settings,
                                        loading = billState.detailLoading, error = billState.detailError,
                                        onRetry = { billViewModel.restoreDetail(screen.bill, detailActive, network.online, retry = true) }) { close() }
                                }
                                is Screen.LifeDevice -> if (navigation.needsLifeRestore(screen)) {
                                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (network.online == false) Text("联网后继续恢复设备") else CircularProgressIndicator()
                                        Spacer(Modifier.height(24.dp))
                                        Button(::close) { Text("返回") }
                                    }
                                } else when (screen.provider) {
                                    LifeProvider.Hui798 -> Hui798DevicePage(hui798State, settings, hui798Model,
                                        active = isActive && backStack.lastOrNull() == screen, onScan = ::openLifeScanner, onBack = ::close)
                                    LifeProvider.Hellobike -> HellobikeDevicePage(hellobikeState, settings, hellobikeModel,
                                        active = isActive && backStack.lastOrNull() == screen, onScan = ::openLifeScanner, onBack = ::close)
                                    LifeProvider.Panggui -> LifeDevicePage(lifeState, settings, lifeModel,
                                        // Read the live stack so camera ownership ends when the pop commits.
                                        active = isActive && backStack.lastOrNull() == screen, onBack = ::close,
                                        onScanned = { scannedLifeDevice(it) }, onManualScanned = { scannedLifeDevice(it, manual = true) },
                                        previewStartAllowed = !LocalNavTransitionScope.current.isRunning)
                                }
                                else -> SecondaryPage(
                                    screen = screen,
                                    settings = settings,
                                    onBack = { close() },
                                    onTheme = { open(Screen.Theme) },
                                    onLogout = { if (isActive) onLogout() },
                                    updateState = updateState,
                                    onCheckUpdates = updateModel::checkManually,
                                    lifeAccounts = managedAccounts,
                                    active = isActive && layers.lastOrNull() == screen,
                                    onCheckLifeAccount = ::checkLifeAccount,
                                    onLoginLifeAccount = { manageLifeLogin(it, false) },
                                    onRemoveLifeAccount = { provider ->
                                        if (isActive) {
                                            when (provider) {
                                                LifeProvider.Hui798 -> hui798Model.removeAccount()
                                                LifeProvider.Panggui -> lifeModel.removeAccount()
                                                LifeProvider.Hellobike -> hellobikeModel.removeAccount()
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LifeDeviceLoginSheet(lifeState, lifeModel, active = isActive && !state.needsLogin)
    Hui798LoginSheet(hui798State, hui798Model, active = isActive && !state.needsLogin)
    HellobikeLoginSheet(hellobikeState, hellobikeModel, active = isActive && !state.needsLogin && !state.preview)
    AppUpdateDialog(updateState.copy(visible = isActive && !state.needsLogin && updateState.visible),
        onSkip = updateModel::skip, onDownload = onInstallUpdate, onDismiss = updateModel::ignore)
    AvatarEditor(avatarModel, avatarState, collectedMyState.avatar,
        active = isActive && !state.needsLogin && layers.lastOrNull() == Screen.Profile &&
            avatarState.owner == state.username && avatarState.preview == state.preview)
}

@Composable
private fun SecondaryPage(
    screen: Screen,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onTheme: () -> Unit,
    onLogout: () -> Unit,
    updateState: AppUpdateUiState,
    onCheckUpdates: () -> Unit,
    lifeAccounts: List<ManagedLifeAccount>,
    active: Boolean,
    onCheckLifeAccount: (LifeProvider) -> Unit,
    onLoginLifeAccount: (LifeProvider) -> Unit,
    onRemoveLifeAccount: (LifeProvider) -> Unit,
) {
    var showLogout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) showLogout = false }
    val title = when (screen) {
        Screen.Settings -> stringResource(R.string.settings_title)
        Screen.Theme -> stringResource(R.string.theme_title)
        Screen.References -> stringResource(R.string.about_references)
        else -> ""
    }
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(title = title, largeTitle = title, color = Color.Transparent, scrollBehavior = scroll,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") } })
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                val modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scroll.nestedScrollConnection)
                val contentPadding = PaddingValues(top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 28.dp, start = 16.dp, end = 16.dp)
                when (screen) {
                    Screen.Settings -> AppSettingsContent(modifier, contentPadding, settings, updateState,
                        onCheckUpdates, onTheme, lifeAccounts, active, onLogout = { showLogout = true },
                        onCheckAccount = onCheckLifeAccount, onLoginAccount = onLoginLifeAccount,
                        onRemoveAccount = onRemoveLifeAccount, scrollBehavior = scroll)
                    Screen.Theme -> ThemeSettingsPage(
                        modifier = modifier, contentPadding = contentPadding,
                        themeMode = settings.themeMode, onThemeModeChange = settings::setTheme,
                        accentMode = settings.accentMode, onAccentModeChange = settings::setAccent,
                        floatingBottomBar = settings.floatingBottomBar, onFloatingBottomBarChange = settings::setFloating,
                        floatingBottomBarStyle = settings.floatingBottomBarStyle, onFloatingBottomBarStyleChange = settings::setFloatingStyle,
                        blurBottomBar = settings.blurBottomBar, onBlurBottomBarChange = settings::setBlurBottom,
                        blurTopBar = settings.blurTopBar, onBlurTopBarChange = settings::setBlurTop,
                        swipeBack = settings.swipeBack, onSwipeBackChange = settings::setSwipeBack,
                        navTransitionStyle = settings.navTransitionStyle, onNavTransitionStyleChange = settings::setNavTransitionStyle,
                    )
                    Screen.References -> ReferencesPage(modifier, contentPadding)
                    else -> Unit
                }
            }
            OverlayDialog(show = active && showLogout, title = "退出校园卡登录", summary = "退出后将清除本机保存的校园卡登录信息。生活服务账户可在设置中单独管理。",
                onDismissRequest = { showLogout = false }, renderInRootScaffold = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showLogout = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { showLogout = false; onLogout() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
                }
            }
        }
    }
}
