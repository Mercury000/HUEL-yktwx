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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mercury.yktwx.RechargeViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mercury.yktwx.BillViewModel
import com.mercury.yktwx.PaymentViewModel
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.R
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.components.liquid.IosLiquidGlassNavigationBar
import com.mercury.yktwx.ui.nav.*
import com.mercury.yktwx.ui.pages.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

private enum class Screen { BillStats, BillDetail, Settings, Theme, References }
private enum class AuthPage { Restoring, Login, Main }

@Composable
fun YktApp(settings: ThemeSettings, viewModel: PaymentViewModel, billViewModel: BillViewModel) {
    val mode = when (settings.themeMode.value) {
        1 -> ColorSchemeMode.Light
        2 -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
    AppTheme(mode, settings.accentMode.value) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val billState by billViewModel.state.collectAsStateWithLifecycle()
        val rechargeModel: RechargeViewModel = viewModel()
        val context = LocalContext.current
        LaunchedEffect(state.restoringLogin, state.needsLogin, state.username) {
            if (!state.restoringLogin) {
                val owner = state.username.takeUnless { state.needsLogin }
                rechargeModel.accountChanged(owner.orEmpty())
                billViewModel.accountChanged(owner)
            }
        }
        LaunchedEffect(rechargeModel, state.username) {
            rechargeModel.events.collect { launch ->
                if (!rechargeModel.canLaunchWechat()) return@collect
                try {
                    rechargeModel.wechatLaunched()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launch.uri)).setPackage("com.tencent.mm"))
                } catch (_: ActivityNotFoundException) { rechargeModel.launchFailed() }
                catch (_: SecurityException) { rechargeModel.launchFailed() }
            }
        }
        LaunchedEffect(rechargeModel) {
            rechargeModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
        LaunchedEffect(viewModel) {
            viewModel.messages.collect { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
        }
        val authPage = when {
            state.restoringLogin -> AuthPage.Restoring
            state.needsLogin -> AuthPage.Login
            else -> AuthPage.Main
        }
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
                    AuthPage.Login -> LoginPage(state) { username, password ->
                        if (interactive) viewModel.login(username, password)
                    }
                    AuthPage.Main -> {
                        // Keep only shell identity for the outgoing animation.
                        // Latest logged-out state has already removed payment codes.
                        val shellOwner = remember { state.username }
                        key(shellOwner) {
                            MainContent(
                                settings, state,
                                if (interactive) billState else BillUiState(),
                                viewModel, billViewModel, rechargeModel,
                                isActive = interactive,
                            )
                        }
                    }
                }
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
    isActive: Boolean,
) {
    val rechargeState by rechargeModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val tabs = remember(pagerState, scope) { MainTabsPagerState(pagerState, scope) }
    LaunchedEffect(pagerState.currentPage) { tabs.syncPage() }
    LaunchedEffect(isActive, billState.needsLogin) {
        if (isActive && billState.needsLogin) viewModel.refresh()
    }
    var layers by remember { mutableStateOf<List<Screen>>(emptyList()) }
    val motion = remember { List(MAX_LAYERS) { IslandLayerMotion() } }
    val lastScreens = remember { mutableStateListOf<Screen?>(null, null, null) }
    SideEffect {
        layers.forEachIndexed { index, screen ->
            if (lastScreens[index] != screen) lastScreens[index] = screen
        }
    }
    val renderScreens = List(MAX_LAYERS) { layers.getOrNull(it) ?: lastScreens[it] }
    val distance: () -> Long = { settings.predictiveBackDistance.value.coerceIn(25, 100).toLong() }
    fun open(screen: Screen) {
        if (isActive && layers.size < MAX_LAYERS && motion.none { it.predictiveActive }) layers = layers + screen
    }
    fun close() {
        if (isActive && layers.isNotEmpty() && motion.none { it.predictiveActive }) layers = layers.dropLast(1)
    }
    fun openBillStats() {
        if (!isActive) return
        billViewModel.openStats()
        open(Screen.BillStats)
    }
    fun openBillDetail(record: BillRecord) {
        if (!isActive) return
        billViewModel.openDetail(record)
        open(Screen.BillDetail)
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
            onDismissed = { lastScreens[k] = null; layers = layers.take(k) },
        )
    }
    if (!settings.predictiveBack.value) BackHandler(enabled = isActive && layers.isNotEmpty()) { close() }

    BarBlurHost(
        bottomBarBlurEnabled = settings.blurBottomBar.value,
        topBarBlurEnabled = false,
        captureForEffects = motion.isBoundaryEngaged(0, layers.size) ||
            (settings.floatingBottomBar.value && settings.floatingBottomBarStyle.value == 1),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val depth = motion[0].coveredDepth.value.coerceIn(0f, 1f)
                translationX = -size.width * depth * BACKGROUND_PARALLAX
                scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                scaleY = scaleX
            }) {
                Scaffold(
                    bottomBar = { BottomNavigation(settings, tabs.selectedPage, tabs::animateToPage) },
                ) { padding ->
                    // Match SonyPods: full-bleed content and a constant 28dp scroll margin.
                    val bottom = padding.calculateBottomPadding() + 28.dp
                    BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                        HorizontalPager(
                            state = pagerState, modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 1, key = { it },
                        ) { page ->
                            when (page) {
                                0 -> PaymentPage(
                                    state, settings, bottom,
                                    onRefresh = { if (isActive) viewModel.refresh(manual = true) },
                                    onLogout = { if (isActive) viewModel.logout() },
                                    onRecharge = { if (isActive) rechargeModel.open(state.username, state.account?.account, state.preview) },
                                    rechargeLoading = rechargeState.loading && !rechargeState.visible,
                                )
                                1 -> BillPage(
                                    state = billState,
                                    settings = settings,
                                    onBack = { tabs.animateToPage(0) },
                                    onActivate = billViewModel::onActivate,
                                    onRefresh = billViewModel::refresh,
                                    onLoadMore = billViewModel::loadMore,
                                    onMonthSelected = billViewModel::selectMonth,
                                    onFiltersApplied = billViewModel::applyFilters,
                                    onSearch = billViewModel::search,
                                    onOpenStats = ::openBillStats,
                                    onOpenDetail = ::openBillDetail,
                                    isActive = isActive && pagerState.currentPage == 1 && layers.isEmpty(),
                                    showBack = false,
                                    bottomPadding = bottom,
                                )
                                2 -> AboutTabPage(
                                    isActive = isActive && pagerState.currentPage == 2 && layers.isEmpty(),
                                    pageBottomContentPadding = bottom,
                                    onOpenReferences = { open(Screen.References) },
                                    onOpenSettings = { open(Screen.Settings) },
                                )
                            }
                        }
                    }
                    // Keep recharge under the root Scaffold so its overlay has a popup host.
                    RechargeDialog(
                        rechargeState, onDismiss = rechargeModel::dismiss, onSubmit = rechargeModel::submit,
                        onFinish = {
                            val paid = rechargeState.phase == com.mercury.yktwx.RechargePhase.Paid
                            rechargeModel.finish()
                            if (paid) viewModel.refresh()
                        },
                    )
                }
            }
        }
        PredictiveBackBackdrop(
            intensity = motion[0].backdropIntensity.value,
            visible = motion[0].backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD,
            modifier = Modifier.fillMaxSize(),
        )
        BarBlurHost(false, false, captureForEffects = motion.isBoundaryEngaged(1, layers.size)) {
            IslandLevelHost(0, layers.size, renderScreens, motion, distance) { screen ->
                when (screen) {
                    Screen.BillStats -> BillStatsPage(
                        state = billState,
                        settings = settings,
                        onBack = { close() },
                        onMonthChange = billViewModel::selectStatsMonth,
                        onTypeChange = billViewModel::selectStatsType,
                        onRetry = billViewModel::refreshStats,
                        onOpenDetail = ::openBillDetail,
                    )
                    Screen.BillDetail -> BillDetailPage(billState.detail, settings) { close() }
                    else -> SecondaryPage(
                        screen = screen,
                        settings = settings,
                        onBack = { close() },
                        onTheme = { open(Screen.Theme) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavigation(settings: ThemeSettings, selected: Int, onSelected: (Int) -> Unit) {
    val icons = listOf(MiuixIcons.Scan, AppIcons.Bill, MiuixIcons.Info)
    val labels = listOf("付款", "账单", "关于")
    val floating = settings.floatingBottomBar.value
    val blur = settings.blurBottomBar.value
    if (floating && settings.floatingBottomBarStyle.value == 1) {
        IosLiquidGlassNavigationBar(
            items = icons.mapIndexed { index, icon -> NavigationItem(label = labels[index], icon = icon) },
            selectedIndex = selected, onItemClick = onSelected,
            backdrop = LocalBarBlurBackdrop.current, modifier = Modifier.zIndex(2f),
        )
    } else {
        val modifier = Modifier.barBlurBackground(
            if (floating) RoundedCornerShape(FloatingToolbarDefaults.CornerRadius) else RectangleShape,
        ).zIndex(2f)
        if (floating) FloatingNavigationBar(modifier = modifier, color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer) {
            icons.forEachIndexed { index, icon ->
                FloatingNavigationBarItem(selected = selected == index, onClick = { onSelected(index) }, icon = icon, label = labels[index])
            }
        } else NavigationBar(modifier = modifier, color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surface, showDivider = true) {
            icons.forEachIndexed { index, icon ->
                NavigationBarItem(selected = selected == index, onClick = { onSelected(index) }, icon = icon, label = labels[index])
            }
        }
    }
}

@Composable
private fun SecondaryPage(
    screen: Screen,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onTheme: () -> Unit,
) {
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
                    Screen.Settings -> LazyColumn(modifier = modifier, contentPadding = contentPadding) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                ArrowPreference(title = stringResource(R.string.theme_title), onClick = onTheme)
                                SwitchPreference(
                                    title = stringResource(R.string.prevent_screenshots),
                                    summary = stringResource(R.string.prevent_screenshots_summary),
                                    checked = settings.preventScreenshots.value,
                                    onCheckedChange = settings::setPreventScreenshots,
                                )
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
        }
    }
}
