package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.mercury.yktwx.ui.PaymentCodePresentationEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.mercury.yktwx.preview.PreviewNotes
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.data.PaymentCodeProblem
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.WaterSession
import com.mercury.yktwx.data.life.LaundrySession
import com.mercury.yktwx.ui.LocalMainNavigationMotion
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.ThemeSettings
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.Locale

@Composable
fun PaymentPage(state: PaymentUiState, settings: ThemeSettings, bottomPadding: Dp, onRefresh: () -> Unit, onRecharge: () -> Unit = {}, rechargeLoading: Boolean = false, onUnlost: () -> Unit = {}, unlostLoading: Boolean = false, unlostEnabled: Boolean = true, isActive: Boolean = true, updateNotice: @Composable () -> Unit = {},
    onScan: () -> Unit = {}, scanEnabled: Boolean = true,
    lifeLoginRequired: Boolean = false, onLifeLogin: () -> Unit = {},
    waterSessions: List<WaterSession> = emptyList(), laundrySessions: List<LaundrySession> = emptyList(),
    onOpenLifeSession: (LifeFeature, String) -> Unit = { _, _ -> },
    onDismissLifeSession: (LifeFeature, String) -> Unit = { _, _ -> },
    lifeNowMillis: () -> Long = System::currentTimeMillis,
    hui798LoginRequired: Boolean = false, onHui798Login: () -> Unit = {},
    additionalLifeEntries: List<LifeDeviceStatusEntry> = emptyList()) {
    // Restoring an account precedes the shell's read effect. An unverified account
    // without a failed read is still waiting; keep this presentation local to the UI.
    val awaitingVerification = !state.needsLogin && state.code == null && state.error == null &&
        (state.problem == null || state.problem == PaymentCodeProblem.StatusUnverified)
    val displayState = if (awaitingVerification && !LocalReadOffline.current) state.copy(busy = true) else state
    val displayCode = displayState.code.takeIf { displayState.problem == null && !displayState.busy && displayState.error == null }
    PaymentCodePresentationEffect(isActive && displayCode != null)
    var enlarged by remember { mutableStateOf<BarcodeFormat?>(null) }
    LaunchedEffect(isActive, displayCode == null) { if (!isActive || displayCode == null) enlarged = null }
    val readError = readError(displayState.error, displayState.errorKind)
        ?: ReadError(FeedbackText.Offline, com.mercury.yktwx.data.ReadFailureKind.Network)
            .takeIf { LocalReadOffline.current && displayCode == null && displayState.problem !in listOf(PaymentCodeProblem.Lost, PaymentCodeProblem.Frozen) }
        ?: ReadError("暂时无法确认卡状态").takeIf { displayState.problem == PaymentCodeProblem.StatusUnverified && !displayState.busy }
    val initialError = rememberInitialReadError(displayState.account != null || displayCode != null,
        readError, !displayState.busy)
    val networkError = rememberPageNetworkError(displayState.busy || displayState.refreshing || displayState.accountLoading || displayState.balanceLoading || displayState.cardStatusLoading,
        displayState.account != null || displayCode != null, null,
        ReadRegion("余额", readError(displayState.accountError, displayState.accountErrorKind), displayState.account != null),
        ReadRegion("卡状态", readError(displayState.cardStatusError, displayState.cardStatusErrorKind), displayState.accountCard != null))
        .takeIf { initialError == null && readError == null && !displayState.busy }
    val showEnlarged = isActive && displayCode != null && enlarged != null
    // Only the format survives exit. The popup may still compose its previous content
    // after the close action clears enlarged, so it must not unwrap that live state.
    val enlargedFormat = rememberWhileShown(showEnlarged, enlarged)
    val currentCode by rememberUpdatedState(displayCode.takeIf { isActive })
    OverlayDialog(show = showEnlarged, title = if (enlargedFormat == BarcodeFormat.CODE_128) "付款条形码" else "付款二维码",
        onDismissRequest = { enlarged = null }, renderInRootScaffold = true) {
        // Always re-read validity, including in a retained popup callback during exit.
        val code = currentCode
        val format = enlargedFormat
        if (code != null && format != null) {
            val barcode = format == BarcodeFormat.CODE_128
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CodeArtwork(code, format, if (barcode) 1440 else 1200, if (barcode) 480 else 1200,
                    Modifier.fillMaxWidth().aspectRatio(if (barcode) 3f else 1f),
                    if (barcode) "放大的付款条形码" else "放大的付款二维码")
                TextButton("收起", onClick = { enlarged = null }, enabled = enlarged != null, modifier = Modifier.fillMaxWidth())
            }
        }
    }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val lifeStatus: @Composable () -> Unit = {
        InlineLifeLoginNotice(lifeLoginRequired, onLifeLogin, enabled = isActive)
        InlineLifeLoginNotice(hui798LoginRequired, onHui798Login, enabled = isActive,
            text = "登录慧生活798，扫码用水", tag = "hui798-login-notice")
    }
    val hasLifeSessions = waterSessions.any { it.active } || laundrySessions.isNotEmpty() ||
        additionalLifeEntries.any { it.feature != LifeFeature.Water || !it.completed }
    val navigationMotion = LocalMainNavigationMotion.current
    BarBlurHost(bottomBarBlurEnabled = settings.blurBottomBar.value, topBarBlurEnabled = settings.blurTopBar.value) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                DashboardTopBar("付款", scrollBehavior = scrollBehavior) {
                    val enabled = isActive && scanEnabled
                    IconButton(onClick = { if (isActive && scanEnabled) { enlarged = null; onScan() } },
                        modifier = Modifier.padding(end = 16.dp).testTag("payment-scan-button")
                            .semantics { if (!enabled) disabled() }, enabled = enabled) {
                        Icon(AppIcons.Scan, "扫一扫", Modifier.size(24.dp),
                            tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface)
                    }
                }
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                PullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    topAppBarScrollBehavior = scrollBehavior,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = AppRefreshTexts,
                ) {
                InitialReadTransition(initialError, networkError) { displayedError ->
                    val showFailure = displayedError != null
                    // Only an empty pending shell is retained for the outgoing skeleton.
                    val state = if (!showFailure && initialError != null) displayState.copy(error = null, busy = true) else displayState
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().overScrollVertical().imePadding().testTag("payment-page-list"),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 10.dp,
                            bottom = bottomPadding + 24.dp + if (hasLifeSessions) 64.dp else 0.dp,
                            start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (showFailure) {
                            item {
                                Column {
                                    updateNotice()
                                    lifeStatus()
                                    ReadUnavailable(checkNotNull(displayedError), Modifier.fillParentMaxHeight())
                                }
                            }
                        } else {
                        item {
                            PageReadNotice(networkError, 16.dp, errorMaxLines = Int.MAX_VALUE) {
                            updateNotice()
                            lifeStatus()
                            Card(Modifier.fillMaxWidth().testTag("payment-code-card"), cornerRadius = 24.dp) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Row(Modifier.fillMaxWidth().height(paymentHeaderHeight()), verticalAlignment = Alignment.CenterVertically) {
                                        Text("付款码", fontSize = 16.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        val status = when {
                                            state.busy -> "核验中"
                                            state.problem == PaymentCodeProblem.Lost -> "已挂失"
                                            state.problem == PaymentCodeProblem.Frozen -> "已冻结"
                                            state.problem == PaymentCodeProblem.StatusUnverified -> ""
                                            displayCode != null -> "${state.secondsLeft}s 后刷新"
                                            state.error != null -> ""
                                            else -> "等待刷新"
                                        }
                                        val restricted = state.problem == PaymentCodeProblem.Lost || state.problem == PaymentCodeProblem.Frozen
                                        val dark = isSystemInDarkTheme()
                                        Text(status, fontSize = 12.sp, lineHeight = 16.sp,
                                            color = if (restricted && !state.busy) { if (dark) Color(0xFFE7B775) else Color(0xFF996018) }
                                                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = if (restricted && !state.busy) Modifier
                                                .background(if (dark) Color(0xFF3B3022) else Color(0xFFFFF1DE), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp, vertical = 5.dp) else Modifier)

                                    }
                                    // One shared footprint, independent of codes, status or bitmap loading.
                                    // Measure static text only; no payment code is retained in measurement/cache.
                                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                                        val bodyHeight = paymentContentHeight(maxWidth)
                                        Box(Modifier.fillMaxWidth().height(bodyHeight).testTag("payment-code-content"),
                                            contentAlignment = Alignment.Center) {
                                            if (state.busy || state.problem != null || readError != null) {
                                                PaymentStatusContent(state, readError, onUnlost, unlostLoading, unlostEnabled)
                                            } else {
                                                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                                    Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                                                        displayCode?.let { code ->
                                                            key(code) {
                                                                CodeArtwork(code, BarcodeFormat.CODE_128, 1440, 330, Modifier.fillMaxSize().paymentCodeClickable(enabled = isActive, onClickLabel = "放大条形码") { enlarged = BarcodeFormat.CODE_128 }, "付款条形码")
                                                            }
                                                        }
                                                    }
                                                    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                                                        val code = displayCode
                                                        if (code != null) {
                                                            key(code) {
                                                                CodeArtwork(code, BarcodeFormat.QR_CODE, 900, 900, Modifier.fillMaxSize().paymentCodeClickable(enabled = isActive, onClickLabel = "放大二维码") { enlarged = BarcodeFormat.QR_CODE }, "付款二维码")
                                                            }
                                                        } else {
                                                            Text(state.error ?: "付款码暂不可用，请下拉刷新",
                                                                modifier = Modifier.fillMaxWidth(),
                                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                                fontSize = 14.sp, textAlign = TextAlign.Center)
                                                        }
                                                    }
                                                    PaymentCodeNumber(displayCode, isActive)
                                                    Text("向商户出示，切勿分享或截图", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                                                        fontSize = 13.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(AppIcons.CardState, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        UpdatedText(state.account?.name ?: "统一账户", Modifier.padding(start = 6.dp).weight(1f), numeric = false, fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            // A fixed text baseline reserves the balance row's
                                            // height even while only a small spinner is shown.
                                            Text("¥ 0.00", fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                                color = Color.Transparent, modifier = Modifier.clearAndSetSemantics {})
                                            ReadContent((state.busy && state.account == null) to state.account?.balanceCents,
                                                pending = { it.first }, contentKey = { it.first }) { (reading, balance) ->
                                            if (reading) {
                                                DashboardPlaceholder(30.sp, Modifier.fillMaxWidth(), fraction = .48f,
                                                    lineHeight = 38.sp, sample = "¥ 0.00")
                                            } else {
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                UpdatedText(
                                                    balance?.let(::money) ?: "—", prefix = "¥ ", identity = state.account?.account,
                                                    modifier = Modifier.weight(1f, fill = false),
                                                    fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                                )
                                                BalanceReadIndicator(state.balanceLoading || state.accountLoading)
                                                }
                                            }
                                            }
                                        }
                                        CompactButton("充值", onClick = onRecharge,
                                            enabled = !rechargeLoading)
                                    }
                                }
                            }
                        }
                        if (UiPreview.enabled && state.preview) item { PreviewNotes.Payment(state.problem != null) }
                        }
                    }
                }
                }
            }

        }
        LifeDeviceStatusIslands(waterSessions, laundrySessions, onOpenLifeSession, onDismissLifeSession, settings,
            modifier = Modifier.align(Alignment.BottomCenter)
                .then(navigationMotion?.modifier() ?: Modifier)
                // The page receives the navigation inset plus the app's 28dp scroll margin.
                .padding(bottom = (bottomPadding - 20.dp).coerceAtLeast(8.dp), start = 16.dp, end = 16.dp)
                .imePadding().fillMaxWidth(),
            enabled = isActive && !showEnlarged && navigationMotion?.visible != false,
            nowMillis = lifeNowMillis, additionalEntries = additionalLifeEntries)
        }
    }
}

/** Reserve the padded status pill even when a plain refresh label is displayed. */
@Composable
private fun paymentHeaderHeight(): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val title = measurer.measure(AnnotatedString("付款码"),
        style = TextStyle(fontSize = 16.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold))
    val status = measurer.measure(AnnotatedString("核验中"),
        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
    return maxOf(32.dp, with(density) { title.size.height.toDp() }, with(density) { status.size.height.toDp() } + 10.dp)
}

/** Stable body height measured without user/account/code data, so every state uses the same slots. */
@Composable
private fun paymentContentHeight(width: Dp): Dp {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val constraints = with(density) { Constraints(maxWidth = width.roundToPx()) }
    fun textHeight(text: String, size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal): Dp {
        val result = measurer.measure(AnnotatedString(text),
            style = TextStyle(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight), constraints = constraints)
        return with(density) { result.size.height.toDp() }
    }
    val codeHeight = 88.dp + 232.dp + 48.dp + maxOf(24.dp, textHeight("0000 •••• •••• ••••", 14, 20)) +
        textHeight("向商户出示，切勿分享或截图", 13, 18)
    val titleHeight = listOf("正在确认卡状态", "付款已暂停", "暂时无法确认卡状态", "付款码暂不可用")
        .maxOf { textHeight(it, 23, 30, FontWeight.SemiBold) }
    val summaryHeight = listOf(
        "确认完成前，暂不展示付款码",
        "校园卡已挂失，当前无法使用付款码\n找回卡片后，可通过解挂恢复使用",
        "校园卡已冻结，当前无法使用付款码\n请在官网或联系校园卡服务中心处理",
        "网络连接失败，请下拉刷新",
    ).maxOf { textHeight(it, 13, 21) }
    val actionHeight = maxOf(50.dp, with(density) { 24.sp.toDp() } + 24.dp)
    // Minimum status spacing for large fonts; normal-sized status uses the code footprint.
    val statusHeight = 32.dp + 92.dp + 32.dp + titleHeight + 12.dp + summaryHeight + actionHeight
    return maxOf(codeHeight, statusHeight)
}

/** Verification, paused payment and read failures share the existing centered status layout. */
@Composable
private fun PaymentStatusContent(
    state: PaymentUiState,
    readError: ReadError?,
    onUnlost: () -> Unit,
    unlostLoading: Boolean,
    unlostEnabled: Boolean,
) {
    val dark = isSystemInDarkTheme()
    val lost = state.problem == PaymentCodeProblem.Lost
    val restricted = lost || state.problem == PaymentCodeProblem.Frozen
    val tint = if (restricted && !state.busy) {
        if (dark) Color(0xFFE7B775) else Color(0xFF996018)
    } else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val iconBackground = if (restricted && !state.busy) {
        if (dark) Color(0xFF3B3022) else Color(0xFFFFF1DE)
    } else MiuixTheme.colorScheme.surface
    @Composable
    fun statusIcon() {
        Box(Modifier.size(92.dp).background(iconBackground, CircleShape), contentAlignment = Alignment.Center) {
            if (state.busy) CircularProgressIndicator(Modifier.size(30.dp))
            else Icon(AppIcons.CardState, contentDescription = null, modifier = Modifier.size(44.dp), tint = tint)
        }
    }
    @Composable
    fun statusMessage() {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when {
                state.busy -> "正在确认卡状态"
                restricted -> "付款已暂停"
                state.problem == PaymentCodeProblem.StatusUnverified -> "暂时无法确认卡状态"
                else -> "付款码暂不可用"
            }, modifier = Modifier.fillMaxWidth(), fontSize = 23.sp, lineHeight = 30.sp,
                maxLines = if (!state.busy && !restricted) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(when {
                state.busy -> "确认完成前，暂不展示付款码"
                lost -> "校园卡已挂失，当前无法使用付款码\n找回卡片后，可通过解挂恢复使用"
                state.problem == PaymentCodeProblem.Frozen -> "校园卡已冻结，当前无法使用付款码\n请在官网或联系校园卡服务中心处理"
                else -> FeedbackText.read(readError ?: ReadError(""))
            }, modifier = Modifier.fillMaxWidth(), fontSize = 13.sp, lineHeight = 21.sp,
                maxLines = if (!state.busy && !restricted) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
    if (!state.busy && lost) {
        Column(Modifier.fillMaxSize().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween) {
            statusIcon()
            statusMessage()
            Button(onClick = onUnlost, enabled = unlostEnabled && !unlostLoading,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (unlostLoading) "读取中…" else "解挂")
            }
        }
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().testTag("payment-status-group"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                statusIcon()
                statusMessage()
            }
        }
    }
}
