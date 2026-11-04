package com.mercury.yktwx.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
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
import com.mercury.yktwx.RechargeViewModel
import com.mercury.yktwx.FeeViewModel
import com.mercury.yktwx.CardPasswordViewModel
import com.mercury.yktwx.CardServiceViewModel
import com.mercury.yktwx.MyViewModel
import com.mercury.yktwx.AvatarViewModel
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.HallViewModel
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.AppUpdateViewModel
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.data.FeeRoomBinding
import com.mercury.yktwx.data.PaymentCodeProblem
import com.mercury.yktwx.data.CampusCardAction
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.ReadEnvironment
import com.mercury.yktwx.data.ReadPage
import com.mercury.yktwx.data.WechatRechargeLaunch
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class Screen { BillDetail, Profile, Account, About, Settings, Theme, References, LifeDevice }
private enum class AuthPage { Restoring, Login, Main }

@Composable
fun YktApp(settings: ThemeSettings, viewModel: PaymentViewModel, billViewModel: BillViewModel, consent: UsageConsent? = null) {
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
        // Release metadata needs consent, but never a campus account or successful login.
        AppUpdateEffect(updateModel, settings.automaticUpdates.value,
            allowed = consent?.accepted == true && !state.preview && !LocalInspectionMode.current)
        val keepLoginWhileInitializing = remember(consent) { consent?.accepted == false }
        LaunchedEffect(agreed, authenticated) {
            // An Activity can retain its models while a changed notice is restored.
            // Revoke that old login before consent can expose the shell again.
            if (!agreed && authenticated) viewModel.logout()
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
            !agreed -> AuthPage.Login
            state.restoringLogin -> if (keepLoginWhileInitializing) AuthPage.Login else AuthPage.Restoring
            !authenticated -> AuthPage.Login
            else -> AuthPage.Main
        }
        LaunchedEffect(authPage) { updateModel.dismiss() }
        AnimatedContent(
            // Route-only target: never retain a payment batch, code or password
            // as an animation snapshot. Normal refreshes do not change screens.
            targetState = authPage,
            modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            transitionSpec = {
                if (initialState == AuthPage.Restoring || targetState == AuthPage.Restoring) {
                    (EnterTransition.None togetherWith ExitTransition.None).using(null)
                } else if (targetState == AuthPage.Main) {
                    (islandPageEnter() togetherWith islandPageExit(-BACKGROUND_PARALLAX))
                        .apply { targetContentZIndex = 1f }.using(null)
                } else {
                    // Main slides away like a popped detail layer; login is the
                    // uncovered background, using the same timings and easing.
                    (islandPageEnter(-BACKGROUND_PARALLAX) togetherWith islandPageExit())
                        .apply { targetContentZIndex = 0f }.using(null)
                }
            },
            label = "AuthenticationPageTransition",
        ) { page ->
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
                            AppUpdateDialog(updateState.copy(visible = interactive && agreed && updateState.visible), updateModel::dismiss)
                        })
                    AuthPage.Main -> {
                        // Keep only shell identity for the outgoing animation.
                        // Latest logged-out state has already removed payment codes.
                        val shellOwner = remember { state.username }
                        key(shellOwner) {
                            // One overlay host above both tabs and secondary page layers.
                            Scaffold {
                                MainContent(
                                    settings, state,
                                    if (interactive) billState else BillUiState(),
                                    viewModel, billViewModel, rechargeModel, feeModel, cardModel, passwordModel, myModel, dormModel, avatarModel,
                                    isActive = interactive,
                                    onLogout = { consent?.revoke(); viewModel.logout() },
                                    updateModel = updateModel, updateState = updateState,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
    LifeAlipayEffect(lifeState.paymentLaunch, isActive && !state.preview && !state.needsLogin,
        lifeModel::launchLaundryPayment)
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
    var layers by remember { mutableStateOf<List<Screen>>(emptyList()) }
    val paymentPageSelected = isActive && pagerState.currentPage == 0 && layers.isEmpty()
    val paymentPageCovered = paymentPageSelected &&
        (dormPaymentVisible || rechargeState.visible || cardState.visible || passwordState.visible)
    val accountPageSelected = isActive && (layers.lastOrNull() == Screen.Account ||
        pagerState.currentPage == 3 && layers.isEmpty())
    val network by rememberNetworkState(state.preview)
    val foregroundState by rememberForegroundState()
    val foreground = foregroundState.active
    LaunchedEffect(foreground, isActive, state.preview) { lifeModel.environment(foreground && isActive && !state.preview) }
    DisposableEffect(lifeModel) { onDispose { lifeModel.environment(false) } }
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

    // One shell-owned snapshot drives every ViewModel read decision.
    LaunchedEffect(readEnvironment, state.username, billState.readIdentity) {
        viewModel.onReadEnvironment(readEnvironment)
        myModel.onReadEnvironment(readEnvironment)
        dormModel.onReadEnvironment(readEnvironment)
        billViewModel.onReadEnvironment(readEnvironment)
    }
    DisposableEffect(viewModel, billViewModel, myModel, dormModel) {
        onDispose {
            val inactive = ReadEnvironment.Inactive
            viewModel.onReadEnvironment(inactive)
            billViewModel.onReadEnvironment(inactive)
            myModel.onReadEnvironment(inactive)
            dormModel.onReadEnvironment(inactive)
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
    val motion = remember { List(MAX_LAYERS) { IslandLayerMotion() } }
    val lastScreens = remember { mutableStateListOf<Screen?>(null, null, null) }
    SideEffect {
        layers.forEachIndexed { index, screen ->
            if (lastScreens[index] != screen) lastScreens[index] = screen
        }
    }
    val renderScreens = List(MAX_LAYERS) { layers.getOrNull(it) ?: lastScreens[it] }
    // Automatic results stay inline; user-opened details are confined to pages with an update entry.
    val updatePromptAllowed = isActive &&
        (layers.lastOrNull() == Screen.Settings || layers.isEmpty())
    LaunchedEffect(updatePromptAllowed, updateState.detailsPending) {
        updateModel.presentIfAllowed(updatePromptAllowed)
    }
    val updateNotice: @Composable () -> Unit = {
        InlineUpdateNotice(updateState, updateModel::openDetails, updateModel::dismissNotice)
    }
    val distance: () -> Long = { settings.predictiveBackDistance.value.coerceIn(25, 100).toLong() }
    fun open(screen: Screen) {
        if (isActive && layers.size < MAX_LAYERS && motion.none { it.predictiveActive }) layers = layers + screen
    }
    fun close() {
        if (isActive && layers.isNotEmpty() && motion.none { it.predictiveActive }) {
            layers = layers.dropLast(1)
        }
    }
    fun openLifeDevice(feature: LifeFeature) {
        if (!isActive || layers.size >= MAX_LAYERS || motion.any { it.predictiveActive }) return
        if (!allowOnlineEntry(context, state.preview)) return
        lifeModel.open(feature)
    }
    LaunchedEffect(lifeState.entryRequest) {
        if (lifeState.entryRequest > 0 && layers.lastOrNull() != Screen.LifeDevice) open(Screen.LifeDevice)
    }
    fun openFee(project: FeeProject, requestedRoom: FeeRoomBinding? = null, saveOnly: Boolean = false) {
        if (!isActive || layers.size >= MAX_LAYERS || motion.any { it.predictiveActive }) return
        if (!allowOnlineEntry(context, state.preview)) return
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
            if (!allowOnlineEntry(context, state.preview)) return
            cardModel.dismiss(); passwordModel.dismiss()
            rechargeModel.open(state.username, state.account?.account, state.preview)
        }
    }
    fun cardAction(action: CampusCardAction) {
        if (isActive) {
            if (!allowOnlineEntry(context, state.preview)) return
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

    repeat(MAX_LAYERS) { k ->
        val shown = layers.size > k
        LaunchedEffect(shown, motion[k].predictiveActive) {
            if (!motion[k].predictiveActive) {
                val target = if (shown) 1f else 0f
                val duration = if (shown) LAYER_ENTER_DURATION else LAYER_EXIT_DURATION
                coroutineScope {
                    launch { motion[k].backdropIntensity.animateTo(target, tween(duration, easing = FastOutSlowInEasing)) }
                    launch { motion[k].coveredDepth.animateTo(target, tween(duration, easing = FastOutSlowInEasing)) }
                }
            }
        }
        IslandLayerBackHandlers(
            enabled = isActive && settings.predictiveBack.value && layers.size == k + 1,
            motion = motion[k], maxTranslationPercent = distance,
            onDismissed = {
                lastScreens[k] = null; layers = layers.take(k)
            },
        )
    }
    if (!settings.predictiveBack.value) BackHandler(enabled = isActive && layers.isNotEmpty()) { close() }

    val useRail = shouldShowMainNavigationRail(settings)
    val blurRootForBack = !useRail && (settings.predictiveBack.value || settings.floatingBottomBar.value)
    CompositionLocalProvider(LocalReadOffline provides (network.online == false)) {
    BarBlurHost(
        bottomBarBlurEnabled = settings.blurBottomBar.value,
        topBarBlurEnabled = false,
        captureForEffects = blurRootForBack && motion.isBoundaryEngaged(0, layers.size),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val depth = motion[0].coveredDepth.value.coerceIn(0f, 1f)
                if (useRail) {
                    // The rail and pager form one main page, as in KernelSU's MainScreen entry.
                    translationX = -size.width * depth * 0.25f
                    alpha = 1f - 0.1f * depth
                    scaleX = 1f
                    scaleY = 1f
                } else {
                    translationX = -size.width * depth * BACKGROUND_PARALLAX
                    scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                    scaleY = scaleX
                    alpha = 1f
                }
            }) {
                MainNavigationScaffold(
                    settings, tabs.selectedPage, tabs::animateToPage,
                    // Classic navigation stays with the page when predictive back is disabled.
                    bottomBarVisible = (!settings.predictiveBack.value && !settings.floatingBottomBar.value) ||
                        (layers.isEmpty() && !motion[0].predictiveActive &&
                            motion[0].coveredDepth.value < EFFECT_VISIBILITY_THRESHOLD),
                ) { padding ->
                    // Match SonyPods: full-bleed content and a constant 28dp scroll margin.
                    BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        LifeDeviceStatusHost(lifeState.water, lifeState.laundry, padding.calculateBottomPadding(),
                            visible = isActive && !state.preview && !state.needsLogin && layers.isEmpty() &&
                                pagerState.currentPage != 1 && paymentSuccess == null && !dormPaymentVisible &&
                                !rechargeState.visible && !cardState.visible && !passwordState.visible && !lifeState.loginVisible,
                            onOpen = ::openLifeDevice) { extraBottom ->
                        val bottom = padding.calculateBottomPadding() + 28.dp + extraBottom
                        HorizontalPager(
                            state = pagerState, modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1, key = { it },
                        ) { page ->
                            when (page) {
                                0 -> PaymentPage(
                                    state, settings, bottom,
                                    updateNotice = updateNotice,
                                    onRefresh = { if (isActive) viewModel.refresh(manual = true) },
                                    onRecharge = ::recharge,
                                    rechargeLoading = rechargeState.loading && !rechargeState.visible,
                                    isActive = paymentPageSelected && !paymentPageCovered,
                                    onUnlost = {
                                        val account = state.account?.account
                                        if (isActive && account != null && state.problem == PaymentCodeProblem.Lost &&
                                            feeOpening == null && !rechargeState.loading && !cardState.loading && !passwordState.loading) {
                                            if (!allowOnlineEntry(context, state.preview)) return@PaymentPage
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
                                    onOpenLifeDevice = ::openLifeDevice,
                                    waterSession = lifeState.water,
                                    laundrySession = lifeState.laundry,
                                )
                                2 -> BillPage(
                                    updateNotice = updateNotice,
                                    state = billState,
                                    settings = settings,
                                    onBack = { tabs.animateToPage(0) },
                                    onRefresh = billViewModel::refresh,
                                    onLoadMore = billViewModel::loadMore,
                                    onDateSelected = billViewModel::selectDate,
                                    onFiltersApplied = billViewModel::applyFilters,
                                    onSearch = billViewModel::search,
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
        }
        PredictiveBackBackdrop(
            intensity = motion[0].backdropIntensity.value,
            visible = motion[0].backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD,
            modifier = Modifier.fillMaxSize(),
            // The pager's snapshot excludes navigation. Dim the live page when needed.
            blurEnabled = blurRootForBack,
        )
        BarBlurHost(false, false, captureForEffects = motion.isBoundaryEngaged(1, layers.size)) {
            IslandLevelHost(0, layers.size, renderScreens, motion, distance) { screen ->
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
                    Screen.LifeDevice -> LifeDevicePage(lifeState, settings, lifeModel,
                        active = isActive && layers.lastOrNull() == Screen.LifeDevice, onBack = ::close)
                    else -> SecondaryPage(
                        screen = screen,
                        settings = settings,
                        onBack = { close() },
                        onTheme = { open(Screen.Theme) },
                        onLogout = { if (isActive) onLogout() },
                        updateState = updateState,
                        onCheckUpdates = updateModel::checkManually,
                    )
                }
            }
        }
    }
    }
    LifeDeviceLoginSheet(lifeState, lifeModel, active = isActive && !state.needsLogin)
    AppUpdateDialog(updateState.copy(visible = isActive && !state.needsLogin && updateState.visible), updateModel::dismiss)
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
                    Screen.Settings -> LazyColumn(modifier = modifier.testTag("settings-page-list"), contentPadding = contentPadding) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                ArrowPreference(title = stringResource(R.string.theme_title), onClick = onTheme)
                                SwitchPreference(
                                    title = stringResource(R.string.payment_motion_detection),
                                    summary = stringResource(R.string.payment_motion_detection_summary),
                                    checked = settings.paymentMotionDetection.value,
                                    onCheckedChange = settings::setPaymentMotionDetection,
                                    modifier = Modifier.testTag("payment-motion-detection-switch"),
                                )
                                SwitchPreference(
                                    title = stringResource(R.string.prevent_screenshots),
                                    summary = stringResource(R.string.prevent_screenshots_summary),
                                    checked = settings.preventScreenshots.value,
                                    onCheckedChange = settings::setPreventScreenshots,
                                )
                            }
                        }
                        item {
                            Box(Modifier.padding(top = 16.dp)) {
                                UpdateSettingsCard(updateState, settings.automaticUpdates.value,
                                    settings::setAutomaticUpdates, onCheckUpdates)
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                                ArrowPreference(title = "退出登录", onClick = { showLogout = true })
                            }
                        }
                    }
                    Screen.Theme -> ThemeSettingsPage(
                        modifier = modifier, contentPadding = contentPadding,
                        themeMode = settings.themeMode, onThemeModeChange = settings::setTheme,
                        accentMode = settings.accentMode, onAccentModeChange = settings::setAccent,
                        floatingBottomBar = settings.floatingBottomBar, onFloatingBottomBarChange = settings::setFloating,
                        floatingBottomBarStyle = settings.floatingBottomBarStyle, onFloatingBottomBarStyleChange = settings::setFloatingStyle,
                        blurBottomBar = settings.blurBottomBar, onBlurBottomBarChange = settings::setBlurBottom,
                        blurTopBar = settings.blurTopBar, onBlurTopBarChange = settings::setBlurTop,
                        predictiveBack = settings.predictiveBack, onPredictiveBackChange = settings::setPredictive,
                        predictiveBackDistance = settings.predictiveBackDistance, onPredictiveBackDistanceChange = settings::setDistance,
                    )
                    Screen.References -> ReferencesPage(modifier, contentPadding)
                    else -> Unit
                }
            }
            OverlayDialog(show = showLogout, title = "退出登录", summary = "退出后将清除本机保存的登录信息。",
                onDismissRequest = { showLogout = false }, renderInRootScaffold = true) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { showLogout = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { showLogout = false; onLogout() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定") }
                }
            }
        }
    }
}
