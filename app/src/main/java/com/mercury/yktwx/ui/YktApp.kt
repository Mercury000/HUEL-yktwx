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
import com.mercury.yktwx.RechargeViewModel
import com.mercury.yktwx.FeeViewModel
import com.mercury.yktwx.CardPasswordViewModel
import com.mercury.yktwx.CardServiceViewModel
import com.mercury.yktwx.MyViewModel
import com.mercury.yktwx.AvatarViewModel
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.HallViewModel
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.Hui798ViewModel
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
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Serializable
private enum class Screen : NavKey { Main, BillDetail, Profile, Account, About, Settings, Theme, References, LifeDevice }
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
        if ((agreed || state.needsLogin) && !state.restoringLogin) {
            val owner = if (state.preview) state.username.takeUnless { state.needsLogin } else session.username
            avatarModel.accountChanged(owner.orEmpty(), state.preview, session.generation)
            if (state.preview || previewWasActive) {
                rechargeModel.accountChanged(owner.orEmpty())
                feeModel.accountChanged(owner.orEmpty())
                cardModel.accountChanged(owner.orEmpty())
                passwordModel.accountChanged(owner.orEmpty())
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
                        val shellOwner = remember { state.username }
                        key(shellOwner) {
                            // Service models remain owned by the app across navigation and login changes.
                            CompositionLocalProvider(LocalViewModelStoreOwner provides shellViewModelOwner) {
                                // One overlay host above both tabs and secondary page layers.
                                Scaffold {
                                    MainContent(
                                        settings, state,
                                        if (interactive) billState else BillUiState(),
                                        viewModel, billViewModel, rechargeModel, feeModel, cardModel, passwordModel, myModel, dormModel, avatarModel,
                                        isActive = interactive,
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
    onLogout: () -> Unit,
    updateModel: AppUpdateViewModel,
    updateState: AppUpdateUiState,
    onInstallUpdate: () -> Unit,
) {
    val context = LocalContext.current
    val paymentSuccess by viewModel.paymentSuccess.collectAsStateWithLifecycle()
    val rechargeState by rechargeModel.state.collectAsStateWithLifecycle()
    val feeState by feeModel.state.collectAsStateWithLifecycle()
    val cardState by cardModel.state.collectAsStateWithLifecycle()
    val passwordState by passwordModel.state.collectAsStateWithLifecycle()
    val collectedMyState by myModel.state.collectAsStateWithLifecycle()
    val avatarState by avatarModel.state.collectAsStateWithLifecycle()
    val collectedDormState by dormModel.state.collectAsStateWithLifecycle()
    val lifeModel: LifeDevicesViewModel = viewModel()
    val lifeState by lifeModel.state.collectAsStateWithLifecycle()
    val hui798Model: Hui798ViewModel = viewModel()
    val hui798State by hui798Model.state.collectAsStateWithLifecycle()
    val pangguiConnection by lifeModel.accountConnection.collectAsStateWithLifecycle()
    val hui798Connection by hui798Model.accountConnection.collectAsStateWithLifecycle()
    val managedAccounts = listOf(
        ManagedLifeAccount(LifeProvider.Hui798, hui798Connection, hui798State.restoring,
            hui798State.accountBusy || hui798State.loggingIn, hui798State.accountError),
        ManagedLifeAccount(LifeProvider.Panggui, pangguiConnection, lifeState.restoring,
            lifeState.accountBusy || lifeState.loggingIn, lifeState.accountError))
    var lifeProvider by remember { mutableStateOf(LifeProvider.Panggui) }
    LifePaymentEffect(lifeState.paymentLaunch, isActive && !state.preview && !state.needsLogin,
        lifeModel::claimLaundryPayment, lifeModel::laundryPaymentReturned)
    val customAvatar = avatarState.bytes != null && avatarState.owner == state.username && avatarState.preview == state.preview && !state.needsLogin
    val avatarPending = !state.needsLogin && (avatarState.owner != state.username || avatarState.preview != state.preview || avatarState.restoring)
    val avatarImage = avatarState.image.takeIf { customAvatar }
    val myState = if (state.needsLogin) MyUiState() else if (customAvatar)
        collectedMyState.copy(avatar = avatarState.bytes, avatarError = null)
        else if (avatarPending) collectedMyState.copy(avatar = null) else collectedMyState
    val dormState = if (state.needsLogin) DormUiState() else collectedDormState
    var feeProject by remember { mutableStateOf<FeeProject?>(null) }
    var feeOpening by remember { mutableStateOf<FeeProject?>(null) }
    var feeTarget by remember { mutableStateOf<FeeRoomBinding?>(null) }
    var dormPaymentVisible by remember { mutableStateOf(false) }
    var feeSaveOnly by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabs = remember(pagerState, scope) { MainTabsPagerState(pagerState, scope) }
    LaunchedEffect(pagerState.currentPage) { tabs.syncPage() }
    val backStack = rememberNavBackStack<Screen>(Screen.Main)
    val layers = backStack.filterIsInstance<Screen>().drop(1)
    val paymentPageSelected = isActive && pagerState.currentPage == 0 && layers.isEmpty()
    val paymentPageCovered = paymentPageSelected &&
        (dormPaymentVisible || rechargeState.visible || cardState.visible || passwordState.visible || lifeState.loginVisible || hui798State.loginVisible)
    val accountPageSelected = isActive && (layers.lastOrNull() == Screen.Account ||
        pagerState.currentPage == 3 && layers.isEmpty())
    val networkState = rememberNetworkState(state.preview)
    val network by networkState
    val foregroundState by rememberForegroundState()
    val foreground = foregroundState.active
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
                analytics.paymentCodeVisible(validPaymentCode, failure)
            }
        }
    }
    LaunchedEffect(foreground, isActive, state.preview) {
        lifeModel.environment(foreground && isActive && !state.preview)
        hui798Model.environment(foreground && isActive && !state.preview)
    }
    DisposableEffect(lifeModel, hui798Model) { onDispose { lifeModel.environment(false); hui798Model.environment(false) } }
    val lifeAccountPageVisible = layers.lastOrNull() == Screen.Settings
    LaunchedEffect(foreground, foregroundState.epoch, isActive, state.preview, network.online, lifeAccountPageVisible,
        paymentPageSelected, lifeState.restoring, hui798State.restoring) {
        if (foreground && isActive && !state.preview && network.online == true && (lifeAccountPageVisible || paymentPageSelected)) {
            lifeModel.checkAccount(); hui798Model.checkAccount()
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
    val feeActive = isActive && dormPaymentVisible
    DisposableEffect(feeActive) {
        onDispose { if (feeActive) feeModel.dismiss() }
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
        if (isActive && screen !in backStack) backStack.add(screen)
    }
    fun close() {
        if (isActive && backStack.size > 1) backStack.removeLastOrNull()
    }
    fun openLifeScanner() {
        if (!isActive ||
            lifeState.waterBusy || lifeState.laundryBusy || hui798State.busy || lifeState.paymentLaunch != null) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeProvider = LifeProvider.Panggui
        lifeModel.openUnifiedScan()
    }
    fun connectLifeAccount() {
        if (!isActive || layers.isNotEmpty()) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        hui798Model.dismissLogin()
        lifeModel.connectAccount()
    }
    fun connectHui798Account() {
        if (!isActive || layers.isNotEmpty()) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeModel.dismissLogin()
        hui798Model.connectAccount()
    }
    fun manageLifeLogin(provider: LifeProvider, change: Boolean) {
        if (!isActive || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        val opened = if (provider == LifeProvider.Hui798) { lifeModel.dismissLogin(); hui798Model.manageLogin(change) }
            else { hui798Model.dismissLogin(); lifeModel.manageLogin(change) }
        if (!opened) Toast.makeText(context, "正在处理请求，请稍后重试", Toast.LENGTH_SHORT).show()
    }
    fun checkLifeAccount(provider: LifeProvider) {
        if (!isActive || !allowOnlineEntry(context, networkState.value.online, state.preview)) return
        if (provider == LifeProvider.Hui798) hui798Model.checkAccount(force = true) else lifeModel.checkAccount(force = true)
    }
    fun openLifeSession(feature: LifeFeature, sessionId: String) {
        if (!isActive) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeProvider = LifeProvider.Panggui
        lifeModel.openSession(feature, sessionId)
    }
    fun openHui798Session(id: String) {
        if (!isActive || hui798State.busy) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        lifeProvider = LifeProvider.Hui798
        hui798Model.openSession(id)
        if (layers.lastOrNull() != Screen.LifeDevice) open(Screen.LifeDevice)
    }
    fun scannedLifeDevice(raw: String) {
        if (!isActive || lifeProvider != LifeProvider.Panggui || layers.lastOrNull() != Screen.LifeDevice ||
            hui798State.busy || lifeState.waterBusy || lifeState.laundryBusy) return
        val route = UnifiedDeviceCode.identify(raw)
        if (route?.provider == LifeProvider.Hui798) {
            lifeProvider = LifeProvider.Hui798
            hui798Model.openDevice(route.code)
        } else lifeModel.scanned(raw)
    }
    LaunchedEffect(lifeState.entryRequest) {
        if (lifeState.entryRequest > 0 && layers.lastOrNull() != Screen.LifeDevice) open(Screen.LifeDevice)
    }
    fun openFee(project: FeeProject, requestedRoom: FeeRoomBinding? = null, saveOnly: Boolean = false) {
        if (!isActive) return
        if (!allowOnlineEntry(context, networkState.value.online, state.preview)) return
        feeOpening = project
        feeProject = project
        feeSaveOnly = saveOnly
        feeTarget = requestedRoom
        dormPaymentVisible = true
        rechargeModel.dismiss()
        cardModel.dismiss()
        passwordModel.dismiss()
        feeModel.open(project, state.username, state.preview, requestedRoom)
    }
    LaunchedEffect(feeOpening, feeState.loading, feeState.config, feeState.error, isActive) {
        if (feeOpening == null) return@LaunchedEffect
        if (!isActive) {
            feeOpening = null
            feeModel.dismiss()
        } else if (!feeState.loading) {
            feeOpening = null
        }
    }
    LaunchedEffect(feeState.config?.project, dormPaymentVisible) {
        val config = feeState.config
        if (dormPaymentVisible && config != null) feeProject = config.project
    }
    fun openBillStats() {
        if (!isActive) return
        billViewModel.openStats()
    }
    fun openBillDetail(record: BillRecord) {
        if (!isActive) return
        billViewModel.openDetail(record)
        open(Screen.BillDetail)
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
            dormPaymentVisible = false
            feeOpening = null
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
                    entry<Screen>(swipeDismiss = swipeDirection) { screen ->
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
                                                    scanEnabled = !lifeState.waterBusy && !lifeState.laundryBusy && !hui798State.busy && lifeState.paymentLaunch == null,
                                                    lifeLoginRequired = !lifeState.restoring && lifeState.connectedPhone.isBlank(),
                                                    onLifeLogin = ::connectLifeAccount,
                                                    hui798LoginRequired = !hui798State.restoring && !hui798State.connected,
                                                    onHui798Login = ::connectHui798Account,
                                                    additionalLifeEntries = hui798State.sessions.filter { it.active }.map { session -> LifeDeviceStatusEntry(LifeFeature.Water,
                                                        when (session.phase) {
                                                            WaterPhase.Ended -> "已完成"
                                                            WaterPhase.Starting -> if (session.uncertain) "启动结果未知" else "正在启动"
                                                            WaterPhase.Stopping -> "正在结束"
                                                            else -> "用水中"
                                                        }, session.device.name, "慧生活798", "hui798-${session.sessionId}", !session.active,
                                                        progress = if (session.active) null else 1f, dismissible = session.canDismissException,
                                                        onOpen = { openHui798Session(session.sessionId) }, onDismiss = { hui798Model.dismissSession(session.sessionId) }) },
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
                                    )
                                    CardPasswordDialog(passwordState, passwordModel::dismiss, passwordModel::selectCard, passwordModel::submit, passwordModel::inputEdited)
                                    // Keep recharge under the root Scaffold so its overlay has a popup host.
                                    RechargeDialog(
                                        rechargeState, onDismiss = rechargeModel::dismiss, onSubmit = rechargeModel::submit,
                                        onCheck = rechargeModel::checkResult, onInputEdited = rechargeModel::inputEdited,
                                        onFinish = {
                                            rechargeModel.finish()
                                        },
                                    )
                                    HallFeeDialog(
                                        state = feeState, visible = isActive && dormPaymentVisible,
                                        project = feeProject, fixedTarget = feeTarget, saveOnly = feeSaveOnly,
                                        onDismiss = { dormPaymentVisible = false; feeOpening = null; feeModel.dismiss() },
                                        onSelect = feeModel::select,
                                        onRetry = { feeProject?.let { feeModel.open(it, state.username, state.preview, feeTarget) } },
                                        onRoomAction = feeModel::requestRoomAction, onConfirmRoomAction = feeModel::confirmRoomAction,
                                        onDismissRoomDialog = feeModel::dismissRoomDialog, onConsumeRoomNotice = feeModel::consumeRoomNotice,
                                        onCreate = feeModel::createWithMethod, onMethod = feeModel::chooseMethod,
                                        onProject = {
                                            if (feeTarget == null) feeModel.selectFamilyProject(it)
                                        }, onMeterKind = feeModel::selectDormMeterKind,
                                        bindingProjects = dormState.bindingProjects,
                                        onPayAccount = feeModel::payAccount, onCancelPayment = feeModel::cancelPayment, onInputEdited = feeModel::inputEdited,
                                        onContinuePayment = feeModel::continuePayment,
                                        onCheck = feeModel::checkResult,
                                        onFinish = {
                                            dormPaymentVisible = false; feeOpening = null; feeModel.dismiss()
                                        },
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
                                Screen.BillDetail -> BillDetailPage(billState.detail, settings) { close() }
                                Screen.LifeDevice -> if (lifeProvider == LifeProvider.Hui798) Hui798DevicePage(hui798State, settings, hui798Model,
                                    active = isActive && layers.lastOrNull() == Screen.LifeDevice, onScan = ::openLifeScanner, onBack = ::close)
                                else LifeDevicePage(lifeState, settings, lifeModel,
                                    // Removed entries retain their content closure for the exit motion.
                                    // Read the live stack so camera ownership ends when the pop commits.
                                    active = isActive && backStack.lastOrNull() == Screen.LifeDevice, onBack = ::close, onScanned = ::scannedLifeDevice,
                                    previewStartAllowed = !LocalNavTransitionScope.current.isRunning)
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
                                            if (provider == LifeProvider.Hui798) hui798Model.removeAccount() else lifeModel.removeAccount()
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
    var showLogout by remember { mutableStateOf(false) }
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
            OverlayDialog(show = showLogout, title = "退出校园卡登录", summary = "退出后将清除本机保存的校园卡登录信息。生活服务账户可在设置中单独管理。",
                onDismissRequest = { showLogout = false }, renderInRootScaffold = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showLogout = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { showLogout = false; onLogout() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
                }
            }
        }
    }
}
