package com.mercury.yktwx.ui.pages

import com.mercury.yktwx.ui.components.AppTextButton as TextButton
import com.mercury.yktwx.ui.components.AppButton as Button
import com.mercury.yktwx.ui.components.AppOverlayDialog as OverlayDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.HellobikeUiState
import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import kotlin.math.roundToInt

private enum class HellobikeLoginPage { Phone, Verification }
private enum class HellobikePrimaryAction { Login, Permission, Return, Refresh, Payment, Unlock, Check, Resume }

@Composable
internal fun HellobikeLoginSheet(state: HellobikeUiState, model: HellobikeViewModel, active: Boolean) {
    val show = active && state.loginVisible
    val showing by rememberUpdatedState(show)
    val currentState by rememberUpdatedState(state)
    val keyboard = LocalSoftwareKeyboardController.current
    val page = rememberWhileShown(show, if (state.sliderVerification) HellobikeLoginPage.Verification else HellobikeLoginPage.Phone)
    val currentPage by rememberUpdatedState(page)
    LifeAccountLoginSheet(show, if (page == HellobikeLoginPage.Verification) "安全验证" else if (state.changingAccount) "更换哈啰账户" else "登录哈啰", "hellobike-login-sheet", {
        keyboard?.hide(); model.dismissLogin()
    }) {
        SheetPageContent(currentPage) { displayPage, interactive ->
            // Only presentation values survive exit; verification credentials remain in the live model.
            val display = rememberWhileShown(showing && interactive, currentState.copy(slider = null, code = "", paymentLaunch = null))
            val enabled = showing && interactive && !currentState.loggingIn && !currentState.sendingCode && !currentState.sliderLoading && !currentState.accountBusy
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (displayPage == HellobikeLoginPage.Verification) {
                    if (currentState.sliderLoading) Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp).testTag("hellobike-slider-loading"))
                    }
                    if (showing && interactive) currentState.slider?.let { slider ->
                        HellobikeSliderContent(slider, currentState.sliderOffset, model::sliderOffsetChanged, enabled,
                            onFinished = { if (enabled) model.sendCode(submitSlider = true) })
                    }
                    if (currentState.sendingCode) Text("正在验证…", fontSize = 13.sp, color = DashboardPalette.muted)
                    AnimatedInlineError(display.loginError, preserveMessage = true)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton("返回登录", model::cancelSlider, enabled = enabled, modifier = Modifier.testTag("hellobike-slider-back"))
                        TextButton("换一张", model::refreshSlider, enabled = enabled, modifier = Modifier.testTag("hellobike-slider-refresh"))
                    }
                } else {
                    DigitTextField(display.phone, model::phoneChanged, label = "手机号", maxDigits = 11, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("hellobike-login-phone"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DigitTextField(if (showing && interactive) currentState.code else "", model::codeChanged, label = "短信验证码", maxDigits = 8, enabled = enabled,
                            modifier = Modifier.weight(1f).testTag("hellobike-login-code"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (enabled) { keyboard?.hide(); model.login() } }))
                        TextButton(if (display.sendingCode) "发送中…" else if (display.codeSeconds > 0) "${display.codeSeconds} 秒" else "发送验证码",
                            { keyboard?.hide(); model.sendCode() }, enabled = enabled && display.phone.length == 11 && display.codeSeconds == 0,
                            modifier = Modifier.testTag("hellobike-send-code"))
                    }
                    InlineErrorGroup(display.loginError, 16.dp, preserveMessage = true) {
                        Button({ keyboard?.hide(); model.login() }, enabled = enabled && display.phone.length == 11 && currentState.code.length in 4..8,
                            modifier = Modifier.fillMaxWidth().testTag("hellobike-login-submit"), colors = ButtonDefaults.buttonColorsPrimary()) {
                            Text(if (display.loggingIn) "正在登录…" else if (display.changingAccount) "登录并更换账户" else "登录并继续")
                        }
                    }
                }
                if (display.loginOfficialRequired && display.loginError != null) OfficialHellobikeLink(enabled = enabled)
            }
        }
    }
}

@Composable
internal fun HellobikeSliderContent(slider: HellobikeSlider, offset: Int, onOffset: (Int) -> Unit, enabled: Boolean, onFinished: () -> Unit) {
    val background = remember(slider.background) { BitmapFactory.decodeByteArray(slider.background, 0, slider.background.size)?.asImageBitmap() }
    val piece = remember(slider.piece) { BitmapFactory.decodeByteArray(slider.piece, 0, slider.piece.size)?.asImageBitmap() }
    if (background == null || piece == null) { AnimatedInlineError("验证图片加载失败，请刷新"); return }
    Column(Modifier.fillMaxWidth().testTag("hellobike-slider"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("拖动滑块，将拼图放入缺口", fontSize = 12.sp, color = DashboardPalette.muted)
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(slider.width.toFloat() / slider.height)) {
            Image(background, "滑块验证背景", Modifier.fillMaxSize())
            Image(piece, "滑块拼图", Modifier.offset(x = maxWidth * offset / slider.width,
                y = maxWidth * slider.y / slider.width).width(maxWidth * slider.pieceWidth / slider.width)
                .height(maxWidth * slider.pieceHeight / slider.width))
        }
        Slider(value = offset.toFloat(), onValueChange = { onOffset(it.roundToInt()) }, valueRange = 0f..slider.maximum.toFloat(),
            onValueChangeFinished = onFinished,
            enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("hellobike-slider-drag").semantics {
                setProgress { target ->
                    if (enabled && target.isFinite()) {
                        onOffset(target.roundToInt().coerceIn(0, slider.maximum)); onFinished(); true
                    } else false
                }
            })
    }
}

@Composable
internal fun HellobikeDevicePage(state: HellobikeUiState, settings: ThemeSettings, model: HellobikeViewModel,
    active: Boolean, onScan: () -> Unit, onBack: () -> Unit, nowMillis: () -> Long = System::currentTimeMillis,
    elapsedMillis: () -> Long = SystemClock::elapsedRealtime) {
    val currentActive by rememberUpdatedState(active)
    DisposableEffect(model, active) { model.pageVisible(active); onDispose { model.pageVisible(false) } }
    var help by rememberSaveable { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(elapsedMillis()) }
    LaunchedEffect(active, state.precheck, state.closeProof) {
        while (active) { clock = elapsedMillis(); delay(1000) }
    }
    val session = state.session
    val phase = session?.phase
    val showPayment = active && state.paymentVisible && (state.canShowPayment ||
        session?.paymentAttempt != null && session.settlement != HellobikeSettlement.Closed)
    val interactive = active && !state.loginVisible && !showPayment
    val feedbackIdentity = model.restorationOwner to (state.selectedSessionId ?: state.scannedBikeNo)
    val retryFeedback = rememberRetryFeedbackState(feedbackIdentity)
    val busy = state.restoring || state.busy || state.loadingDevice || state.locating || state.paymentInFlight || state.precheck?.skipConfirmation == true ||
        state.locationPermissionRequest != null || retryFeedback.dispatching
    val checkingBill = phase == HellobikePhase.Ended && !session.dismissible && (state.loadingDevice || state.busy)
    val precheckFresh = state.precheck?.fresh(clock) == true
    val primary = when {
        state.restoring -> "正在恢复账号…"
        !state.connected -> "登录并继续"
        state.locationPermissionRequest != null -> "等待定位授权…"
        state.checkingTemporaryLock -> "还车"
        checkingBill -> if (session.paymentAttempt == null) "正在查询费用…" else "正在查询付款结果…"
        state.loadingDevice || state.locating || retryFeedback.dispatching -> when (phase) {
            HellobikePhase.Ended -> "骑行已结束"
            HellobikePhase.Riding -> "正在查询车辆…"
            HellobikePhase.Closing -> "正在查询还车结果…"
            HellobikePhase.Opening, null -> "正在扫码开锁…"
        }
        busy -> if (phase == HellobikePhase.Opening || session?.temporaryAction != null) "${session.title}…"
            else if (state.precheck?.skipConfirmation == true) "正在开锁…" else "正在处理…"
        state.locationPermissionRequired -> if (state.locationPermissionBlocked) "去设置开启定位" else "开启定位"
        phase == HellobikePhase.Opening || phase == HellobikePhase.Closing || session?.temporaryAction != null -> "查询车辆状态"
        phase == HellobikePhase.Riding && session.temporarilyLocked -> "继续骑行"
        phase == HellobikePhase.Riding -> "还车"
        phase == HellobikePhase.Ended -> "支付"
        precheckFresh && !state.precheck.skipConfirmation -> if (state.precheck.priceRule.isBlank()) "确认开锁" else "确认计费并开锁"
        else -> "重新检查车辆"
    }
    val primaryAction = when {
        session?.dismissible == true -> null
        !state.connected -> HellobikePrimaryAction.Login
        state.locationPermissionRequired -> HellobikePrimaryAction.Permission
        session?.temporaryAction != null -> HellobikePrimaryAction.Refresh
        phase == HellobikePhase.Riding && session.temporarilyLocked -> HellobikePrimaryAction.Resume
        phase == HellobikePhase.Riding -> HellobikePrimaryAction.Return
        phase == HellobikePhase.Opening || phase == HellobikePhase.Closing -> HellobikePrimaryAction.Refresh
        phase == HellobikePhase.Ended -> HellobikePrimaryAction.Payment.takeIf { checkingBill || state.canShowPayment }
        precheckFresh && !state.precheck.skipConfirmation -> HellobikePrimaryAction.Unlock
        else -> HellobikePrimaryAction.Check
    }
    val title = when (phase) {
        HellobikePhase.Riding, HellobikePhase.Closing -> "骑行进度"
        HellobikePhase.Ended -> "骑行结束"
        else -> "扫码骑行"
    }
    val stage = when (phase) { null -> "ready"; HellobikePhase.Ended -> "ended"; else -> "progress" }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    var topBarListState by remember { mutableStateOf<LazyListState?>(null) }
    LifeDeviceShell(title, settings, scrollBehavior, onBack, { help = true }, true,
        listState = topBarListState, footer = {
        LifeDeviceActionFooter("hellobike-action-footer") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phase == HellobikePhase.Riding && session.temporaryAction == null) {
                    key(feedbackIdentity, session.temporarilyLocked) {
                    if (session.temporarilyLocked) Button({ retryFeedback.dispatch(model::checkClose) }, Modifier.weight(1f)
                        .testTag("hellobike-paused-return"), enabled = interactive && !busy) { Text("还车") }
                    else if (session.temporaryLockSupported) Button({ retryFeedback.dispatch(model::checkTemporaryLock) }, Modifier.weight(1f)
                        .testTag("hellobike-temporary-lock"), enabled = interactive && !busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.checkingTemporaryLock) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp,
                                modifier = Modifier.testTag("hellobike-action-progress"))
                            ContentFade(if (state.checkingTemporaryLock) "正在检查…" else "临时锁车") { Text(it) }
                        }
                    }
                    }
                }
            // Replacing the operation cancels an in-flight press instead of rebinding its release.
            if (primaryAction != null) key(feedbackIdentity, primaryAction, state.precheck.takeIf { primaryAction == HellobikePrimaryAction.Unlock }) {
            Button({ when (primaryAction) {
                HellobikePrimaryAction.Login -> model.connectAccount(false)
                HellobikePrimaryAction.Permission, HellobikePrimaryAction.Check -> retryFeedback.dispatch(model::retryDeviceCheck)
                HellobikePrimaryAction.Return -> retryFeedback.dispatch(model::checkClose)
                HellobikePrimaryAction.Refresh -> retryFeedback.dispatch(model::refreshState)
                HellobikePrimaryAction.Payment -> model.showPayment()
                HellobikePrimaryAction.Unlock -> model.startRide(state.precheck)
                HellobikePrimaryAction.Resume -> model.resumeRide()
            } }, enabled = interactive && !busy && (session != null || state.scannedBikeNo != null),
                modifier = Modifier.weight(1f).testTag("hellobike-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.checkingTemporaryLock && (state.restoring || state.busy || state.loadingDevice || state.locating ||
                        state.locationPermissionRequest != null || retryFeedback.dispatching)) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp,
                        modifier = Modifier.testTag("hellobike-action-progress"))
                    ContentFade(primary) { Text(it) }
                }
            }
            }
            }
            if (session == null) DashboardLink("重新扫码", onScan, enabled = interactive && !busy, arrow = false,
                modifier = Modifier.testTag("hellobike-rescan"), color = DashboardPalette.muted)
            else Button(onBack, Modifier.fillMaxWidth(), enabled = interactive) { Text("返回") }
        }
    }) { padding ->
        AnimatedContent(stage, Modifier.fillMaxSize(), transitionSpec = {
            ((fadeIn(tween(ContentMotion.Append, delayMillis = 120)) + slideInVertically(tween(ContentMotion.Append, delayMillis = 120)) { it / 45 })
                togetherWith fadeOut(tween(120))).using(SizeTransform(clip = false))
        }, label = "hellobike-device-stage") { displayedStage ->
            val display = rememberWhileShown(displayedStage == stage, state.copy(phone = "", code = "", slider = null, paymentLaunch = null))
            val ride = display.session
            val listState = rememberLazyListState()
            SideEffect { if (displayedStage == stage) topBarListState = listState }
            LazyColumn(Modifier.fillMaxSize().topBarOverscroll(scrollBehavior).nestedScroll(scrollBehavior.nestedScrollConnection).testTag("hellobike-device-page"),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding() + 20.dp,
                    start = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!display.restoring && !display.connected) item {
                    InlineLifeLoginNotice(true, { model.connectAccount(false) }, enabled = interactive && displayedStage == stage,
                        text = "登录哈啰，继续扫码骑行", tag = "hellobike-device-login-notice")
                }
                if (display.recoveryRequired) item { HellobikeRecoveryNotice(model, interactive && !busy) }
                if (ride == null) {
                    item { LifeDeviceHero("哈啰单车", display.region?.label.orEmpty(), LifeFeature.Ride) }
                    item { LifeDeviceInfoCard(buildList {
                        display.scannedBikeNo?.let { add("车辆编号" to it) }
                        add("服务提供方" to "哈啰")
                    }, "hellobike-device-info") }
                    item { LifeDeviceNoticeCard(when {
                        display.restoring -> "正在恢复账号"
                        display.loadingDevice -> "正在扫码开锁"
                        display.precheck != null -> "计费规则"
                        !display.connected -> "登录后继续查询车辆"
                        else -> "等待车辆检查"
                    }, display.precheck?.priceRule ?: "扫一扫会按哈啰规则直接开锁，请在车辆旁操作。开锁和还车时需要获取位置。",
                        "hellobike-precheck") }
                    display.precheck?.notice?.let { notice -> item { LifeDeviceNoticeCard("哈啰提示", notice, "hellobike-official-notice") } }
                    if (display.precheck != null && !precheckFresh) item { Text("车辆检查已过期，请重新检查", fontSize = 12.sp, color = DashboardPalette.muted) }
                } else {
                    if (ride.phase != HellobikePhase.Opening) item {
                        LifeDeviceProgress(ride.phase == HellobikePhase.Ended, ride.title, "骑行已结束",
                            if (ride.settled) "本笔费用已结清" else "车辆已结束本次骑行", ride.startedAtMillis, ride.endedAtMillis,
                            "hellobike", "骑行参考计时", "实际时长与费用以哈啰记录为准", nowMillis)
                    }
                    item { LifeDeviceInfoCard(buildList {
                        add("车辆编号" to ride.bikeNo)
                        ride.region?.label?.takeIf(String::isNotBlank)?.let { add("地区" to it) }
                        ride.amount?.let { add("骑行费用" to "¥$it") }
                        if (ride.phase == HellobikePhase.Ended) add("费用状态" to ride.title)
                        ride.distance?.let { add("骑行距离" to "$it 公里") }
                        add("服务提供方" to "哈啰")
                    }, "hellobike-device-info") }
                    if (ride.phase != HellobikePhase.Ended) item { LifeDeviceNoticeCard(
                        if (ride.phase == HellobikePhase.Riding && !ride.temporarilyLocked && ride.temporaryAction == null) "到达后记得还车" else ride.title, when (ride.phase) {
                        HellobikePhase.Opening -> "正在读取车辆响应。请留意现场锁具，结果待确认时继续查询原订单。"
                        HellobikePhase.Riding -> when {
                            ride.temporaryAction != null -> "已提交${if (ride.temporaryAction == HellobikeTemporaryAction.Lock) "临时锁车" else "继续骑行"}，正在确认车辆状态。请留意现场锁具。"
                            ride.temporarilyLocked -> "临时锁车期间继续计费，时限结束后车辆会自动开锁。需要继续使用时点击继续骑行，结束用车请还车。"
                            else -> "请在允许停车的位置还车。离开页面不会结束骑行，可从付款页继续查看。"
                        }
                        HellobikePhase.Closing -> "已提交关锁，正在查询本次还车结果。请核对现场锁具状态。"
                        HellobikePhase.Ended -> ""
                    }, "hellobike-usage-notice") }
                }
                item(key = "hellobike-device-error-slot") {
                    if (ride?.phase == HellobikePhase.Ended || display.locationPermissionRequired || display.locationPermissionRequest != null)
                        AnimatedInlineError(display.error ?: display.paymentFeedback?.detail.takeIf { ride?.dismissible == false && !display.canShowPayment },
                            Modifier.testTag("hellobike-device-error"), preserveMessage = true)
                    else InlineRetryFeedback(display.error, display.loadingDevice || display.locating,
                        "重新检查", model::retryDeviceCheck, Modifier.testTag("hellobike-device-error"),
                        enabled = interactive && displayedStage == stage && !display.busy && !display.paymentInFlight,
                        actionModifier = Modifier.testTag("hellobike-device-retry"),
                        identity = model.restorationOwner to (display.selectedSessionId ?: display.scannedBikeNo),
                        retryFeedback = retryFeedback, showProgress = false)
                }
                if (display.officialRequired) item { OfficialHellobikeLink(enabled = interactive) }
            }
        }
    }
    val temporaryProof = state.temporaryLockProof
    val temporaryConfirm = interactive && temporaryProof != null && temporaryProof.sessionId == session?.sessionId &&
        session.phase == HellobikePhase.Riding && !session.temporarilyLocked && session.temporaryAction == null
    OverlayDialog(show = temporaryConfirm, title = "确认临时锁车", summary =
        "临时锁车期间按正常骑行时间收费。" + (temporaryProof?.canPauseSeconds?.let { "本次最多可临时锁车${(it.toLong() + 59) / 60}分钟。" } ?: "") +
            "临时锁车时间结束后，车辆会自动开锁进入骑行中。", onDismissRequest = model::dismissTemporaryLock,
        renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(model::dismissTemporaryLock, Modifier.weight(1f), enabled = temporaryConfirm && !busy) { Text("取消") }
            Button(model::commitTemporaryLock, Modifier.weight(1f).testTag("hellobike-confirm-temporary-lock"),
                enabled = temporaryConfirm && !busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text("确定锁车") }
        }
    }
    val proof = state.closeProof
    val confirm = interactive && proof != null && proof.sessionId == session?.sessionId
    val confirming by rememberUpdatedState(confirm)
    OverlayDialog(show = confirm, title = "确认还车", summary = if (proof?.fresh(clock) == true)
        proof.detail ?: "此处可以还车。请停稳车辆，确认后留意车锁是否关闭。" else "位置已过期，请返回后重新还车。",
        onDismissRequest = model::dismissClose, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(model::dismissClose, Modifier.weight(1f), enabled = confirming) { Text("暂不还车") }
            Button(model::commitClose, Modifier.weight(1f).testTag("hellobike-confirm-close"), enabled = confirming && proof?.fresh(clock) == true && !busy,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认还车") }
        }
    }
    AppScrollableBottomSheet(show = active && help, startAction = { SheetTitle("骑行帮助") },
        onDismissRequest = { help = false }) { headerPadding ->
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(headerPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("扫描哈啰普通单车二维码后，会按官方规则直接开锁；官方要求确认时才显示确认开锁。请在车辆旁扫码并留意锁具。骑行中支持的车辆可临时锁车，锁车期间继续计费，时限结束后自动开锁。骑行中可从付款页状态卡返回；离开页面不会还车。停好车辆后点击还车，按哈啰返回的要求决定是否需要再次确认，并留意锁具是否关闭。骑行结束后自动核对费用，支持平台返回的余额、支付宝和普通银联。需要实名认证、蓝牙、异常停车或其他支付方式时，请在哈啰处理。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            OfficialHellobikeLink(enabled = currentActive && help)
            Button({ help = false }, Modifier.fillMaxWidth(), enabled = currentActive && help, colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
    HellobikePaymentSheet(showPayment, state, model)
}

private enum class HellobikePaymentPage { Methods, Result }

private data class HellobikePaymentResultDisplay(val phase: RechargePhase, val loading: Boolean, val amount: String?,
    val detail: String?, val paymentApp: String, val cancelled: Boolean, val canResume: Boolean)

@Composable
private fun HellobikeRecoveryNotice(model: HellobikeViewModel, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LifeDeviceNoticeCard("请核对原订单", "本机的部分骑行记录无法恢复。请在哈啰核对未结束的骑行和待付费用，再继续使用。", "hellobike-recovery-notice")
        OfficialHellobikeLink(enabled)
        TextButton("已在哈啰核对", model::acknowledgeRecovery, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("hellobike-recovery-acknowledge"))
    }
}

@Composable
internal fun HellobikePaymentSheet(show: Boolean, state: HellobikeUiState, model: HellobikeViewModel) {
    val currentState by rememberUpdatedState(state)
    val result = state.paymentFeedback?.takeUnless { it.phase == RechargePhase.Creating }
    val isResult = result != null
    val attempt = state.session?.paymentAttempt
    val resultDisplay = rememberWhileShown(show && isResult, HellobikePaymentResultDisplay(
        result?.phase?.let { if (it == RechargePhase.TimedOut) RechargePhase.Checking else it } ?: RechargePhase.Checking,
        result?.loading == true,
        attempt?.displayAmount ?: state.payDetail?.amount ?: state.session?.amount ?: attempt?.orders?.singleOrNull()?.amount,
        result?.detail, when (attempt?.channel) { "003" -> "哈啰"; "1201" -> "银联"; else -> "支付宝" },
        cancelled = result?.cancelled == true, canResume = result?.cancelled == true && state.canShowPayment))
    val page = if (isResult) HellobikePaymentPage.Result else HellobikePaymentPage.Methods
    val currentPage by rememberUpdatedState(page)
    val currentResult by rememberUpdatedState(resultDisplay)
    PaymentSheetPages(show, currentPage, { it == HellobikePaymentPage.Result }, if (isResult) null else "付款",
        model::dismissPayment, Modifier.testTag("hellobike-payment-sheet")) { shownPage, enabled ->
        if (shownPage == HellobikePaymentPage.Result) {
            val display = currentResult
            PaymentResultContent(display.phase, display.loading, 0, display.detail, model::dismissPayment, subject = "支付",
                enabled = enabled && currentPage == HellobikePaymentPage.Result && (!currentState.busy || display.phase == RechargePhase.Paid),
                paymentApp = display.paymentApp, preserveError = true, cancelled = display.cancelled,
                onResumePayment = if (display.canResume) model::continueCancelledPayment else null,
                amountYuan = display.amount?.toBigDecimalOrNull()?.setScale(2, java.math.RoundingMode.HALF_UP)?.toPlainString() ?: "—")
        } else {
            val display = rememberWhileShown(enabled && currentPage == HellobikePaymentPage.Methods,
                currentState.copy(phone = "", code = "", slider = null, paymentLaunch = null))
            val options = display.payOptions
            val selected = options.indexOfFirst { it.selectionId == currentState.paymentMethod }.takeIf { it >= 0 }
                ?: options.indexOfFirst { it.channel.available == true && it.channel.needsOpen != true }.coerceAtLeast(0)
            val option = options.getOrNull(selected)
            PaymentFixedAmount(option?.amount ?: display.payDetail?.amount, display.paymentLoading,
                Modifier.testTag("hellobike-payment-loading"))
            PaymentMethodActions(options.map { it.channel.name }, selected, { model.selectPaymentMethod(options[it].selectionId) },
                if (display.busy) "正在发起…" else option?.channel?.name?.let { "${it}支付" } ?: "确认支付",
                { if (enabled && option != null) model.pay(option.selectionId) },
                enabled = enabled && currentPage == HellobikePaymentPage.Methods && !currentState.busy &&
                    !currentState.paymentLoading && !currentState.paymentInFlight,
                canSubmit = option?.channel?.available == true && option.channel.needsOpen != true && currentState.canShowPayment,
                error = display.error ?: display.paymentNotice ?: option?.channel?.remark ?: "暂无可用的支付方式".takeIf {
                    display.canShowPayment && !display.paymentLoading && options.isEmpty()
                },
                modifier = Modifier.testTag("hellobike-pay-submit"), preserveError = true, onCancel = model::dismissPayment)
        }
        if (currentState.officialRequired || shownPage == HellobikePaymentPage.Result && currentState.paymentFeedback?.phase == RechargePhase.TimedOut ||
            !currentState.paymentLoading && shownPage == HellobikePaymentPage.Methods &&
            currentState.payOptions.none { it.channel.available == true }) OfficialHellobikeLink(enabled = enabled)
    }
}

@Composable
internal fun OfficialHellobikeLink(enabled: Boolean, color: androidx.compose.ui.graphics.Color = DashboardPalette.muted, label: String = "打开哈啰") {
    val context = LocalContext.current
    DashboardLink(label, { openHellobike(context) }, enabled = enabled, arrow = false,
        modifier = Modifier.testTag("hellobike-official"), color = color)
}
private fun openHellobike(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.jingyao.easybike")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://m.hellobike.com/AppHelloBikeMainH5/latest/index.html"))
    try { context.startActivity(intent) }
    catch (_: Exception) { Toast.makeText(context, "请打开哈啰核对本次订单", Toast.LENGTH_SHORT).show() }
}
