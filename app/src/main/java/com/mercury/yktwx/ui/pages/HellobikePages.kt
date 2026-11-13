package com.mercury.yktwx.ui.pages

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.HellobikeUiState
import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.FeeMethod
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlin.math.roundToInt

private enum class HellobikeLoginPage { Phone, Verification }

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
                    AnimatedInlineError(display.loginError)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton("返回登录", model::cancelSlider, enabled = enabled, modifier = Modifier.testTag("hellobike-slider-back"))
                        TextButton("换一张", model::refreshSlider, enabled = enabled, modifier = Modifier.testTag("hellobike-slider-refresh"))
                    }
                } else {
                    Text("使用哈啰账号扫码骑行", fontSize = 13.sp, color = DashboardPalette.muted)
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
                    InlineErrorGroup(display.loginError, 16.dp) {
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
    var help by remember { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(elapsedMillis()) }
    LaunchedEffect(active, state.precheck, state.closeProof) {
        while (active) { clock = elapsedMillis(); delay(1000) }
    }
    val session = state.session
    val phase = session?.phase
    val interactive = active && !state.loginVisible && !state.paymentVisible
    val busy = state.restoring || state.busy || state.loadingDevice || state.locating || state.paymentInFlight
    val precheckFresh = state.precheck?.fresh(clock) == true
    val primary = when {
        state.restoring -> "正在恢复账号…"
        !state.connected -> "登录并继续"
        busy -> "正在处理…"
        phase == HellobikePhase.Opening || phase == HellobikePhase.Closing -> "查询车辆状态"
        phase == HellobikePhase.Riding -> "还车"
        session?.dismissible == true -> "再次扫码骑行"
        phase == HellobikePhase.Ended && session.paymentAttempt != null -> "查询付款结果"
        phase == HellobikePhase.Ended -> "查看费用与支付"
        precheckFresh -> "确认计费并开锁"
        else -> "重新检查车辆"
    }
    val title = when (phase) {
        HellobikePhase.Riding, HellobikePhase.Closing -> "骑行进度"
        HellobikePhase.Ended -> "骑行结束"
        else -> "扫码骑行"
    }
    val stage = when (phase) { null -> "ready"; HellobikePhase.Ended -> "ended"; else -> "progress" }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    LifeDeviceShell(title, settings, false, scrollBehavior, onBack, { help = true }, true, footer = {
        LifeDeviceActionFooter("hellobike-action-footer") {
            Button({ when {
                !state.connected -> model.connectAccount(false)
                session?.dismissible == true -> onScan()
                phase == HellobikePhase.Riding -> model.checkClose()
                phase == HellobikePhase.Opening || phase == HellobikePhase.Closing -> model.refreshState()
                phase == HellobikePhase.Ended && session.paymentAttempt != null -> model.checkPayment()
                phase == HellobikePhase.Ended -> model.showPayment()
                precheckFresh -> model.startRide()
                else -> state.scannedBikeNo?.let(model::openDevice)
            } }, enabled = interactive && !busy && (session != null || state.scannedBikeNo != null),
                modifier = Modifier.fillMaxWidth().testTag("hellobike-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) { Text(primary) }
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
            LazyColumn(Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).testTag("hellobike-device-page"),
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
                        display.loadingDevice -> "正在检查车辆"
                        display.precheck != null -> "计费规则"
                        !display.connected -> "登录后继续查询车辆"
                        else -> "等待车辆检查"
                    }, display.precheck?.priceRule ?: "开锁和还车时需要获取位置，请在车辆旁操作。",
                        "hellobike-precheck", loading = display.restoring || display.loadingDevice) }
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
                        if (ride.phase == HellobikePhase.Riding) "到达后记得还车" else ride.title, when (ride.phase) {
                        HellobikePhase.Opening -> "正在读取车辆响应。请留意现场锁具，结果待确认时继续查询原订单。"
                        HellobikePhase.Riding -> "请在允许停车的位置还车。离开页面不会结束骑行，可从付款页继续查看。"
                        HellobikePhase.Closing -> "已提交关锁，正在查询本次还车结果。请核对现场锁具状态。"
                        HellobikePhase.Ended -> ""
                    }, "hellobike-usage-notice", loading = display.busy || display.loadingDevice) }
                }
                item { InlineErrorGroup(display.error, 12.dp) {} }
                if (display.officialRequired) item { OfficialHellobikeLink(enabled = interactive) }
            }
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
    OverlayBottomSheet(show = active && help, startAction = { SheetTitle("骑行帮助") }, onDismissRequest = { help = false }, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("扫描哈啰普通单车二维码，核对车辆与计费规则后开锁。骑行中可从付款页状态卡返回；离开页面不会还车。停好车辆后先校验位置，再确认关锁。骑行结束后核对本笔费用，支持平台返回的余额、支付宝和普通银联。需要实名认证、蓝牙、异常停车或其他支付方式时，请在哈啰处理，返回后刷新原订单。",
                fontSize = 13.sp, lineHeight = 22.sp, color = DashboardPalette.muted)
            OfficialHellobikeLink(enabled = currentActive && help)
            Button({ help = false }, Modifier.fillMaxWidth(), enabled = currentActive && help, colors = ButtonDefaults.buttonColorsPrimary()) { Text("知道了") }
        }
    }
    HellobikePaymentSheet(active && state.paymentVisible, state, model)
}

private enum class HellobikePaymentPage { Methods, Result }

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
    val showing by rememberUpdatedState(show)
    val currentState by rememberUpdatedState(state)
    if (!rememberOverlayMounted(show)) return
    val session = rememberOverlaySession(show)
    val isResult = state.paymentFeedback != null
    val page = rememberWhileShown(show, if (isResult) HellobikePaymentPage.Result else HellobikePaymentPage.Methods)
    val currentPage by rememberUpdatedState(page)
    PaymentSheet(show, rememberWhileShown(show, if (isResult) null else "付款"), model::dismissPayment, insideMargin = DpSize(0.dp, 0.dp)) {
        key(session) { SheetPageContent(currentPage, Modifier.bottomSheetSafeArea()) { displayPage, interactive ->
            val enabled = showing && interactive
            val display = rememberWhileShown(showing && interactive, currentState.copy(phone = "", code = "", slider = null, paymentLaunch = null))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = if (displayPage == HellobikePaymentPage.Result) 24.dp else 20.dp)
                .padding(top = 8.dp, bottom = 20.dp).testTag("hellobike-payment-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (displayPage == HellobikePaymentPage.Result) {
                    val result = display.paymentFeedback
                    val amount = display.session?.paymentAttempt?.orders?.sumOf { it.amount.toBigDecimal() }?.toPlainString()
                        ?: display.payDetail?.amount ?: display.session?.amount ?: "0.00"
                    val retry = if (currentState.session?.paymentAttempt == null && currentState.session?.settlement == HellobikeSettlement.Pending)
                        display.payOptions.filter { it.channel.available == true }.map { FeeMethod(it.channel.method, it.channel.method, it.channel.name, false, 0) }
                        else emptyList()
                    val phase = result?.phase ?: RechargePhase.Checking
                    PaymentResultContent(if (phase == RechargePhase.TimedOut) RechargePhase.Checking else phase, result?.loading == true,
                        amount.toBigDecimal().movePointRight(2).longValueExact(), result?.detail, model::dismissPayment,
                        subject = "支付", retryMethods = retry, onContinuePayment = { if (enabled) model.pay(it.id) },
                        enabled = enabled && (!currentState.busy || result?.phase == RechargePhase.Paid),
                        onCheck = { if (enabled) model.checkPayment() }, paymentApp = when (display.session?.paymentAttempt?.channel) {
                            "003" -> "哈啰"; "1201" -> "银联"; else -> "支付宝"
                        }, retryMethodLabel = { it.name })
                } else {
                    val options = display.payOptions
                    var selected by remember(options) { mutableIntStateOf(options.indexOfFirst { it.channel.available == true }.coerceAtLeast(0)) }
                    val option = options.getOrNull(selected)
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        if (display.paymentLoading) CircularProgressIndicator(Modifier.size(24.dp).testTag("hellobike-payment-loading"))
                        else Text(option?.amount?.let { "¥$it" } ?: display.payDetail?.amount?.let { "¥$it" } ?: "—",
                            fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
                    }
                    PaymentMethodActions(options.map { it.channel.name }, selected, { selected = it },
                        if (display.busy) "正在发起…" else option?.channel?.name?.let { "${it}支付" } ?: "确认支付",
                        { if (enabled && option != null) model.pay(option.channel.method) },
                        enabled = enabled && currentPage == HellobikePaymentPage.Methods && !currentState.busy && !currentState.paymentLoading && !currentState.paymentInFlight,
                        canSubmit = option?.channel?.available == true && currentState.session?.paymentAttempt == null,
                        error = display.error ?: option?.channel?.remark ?: "暂无支持的支付方式，请在哈啰处理".takeIf { !display.paymentLoading && options.isEmpty() },
                        modifier = Modifier.testTag("hellobike-pay-submit"))
                    if (!display.paymentLoading && (options.isEmpty() || display.error != null)) Button(model::showPayment,
                        Modifier.fillMaxWidth(), enabled = enabled && !currentState.busy) { Text("刷新费用") }
                }
                if (display.officialRequired || !display.paymentLoading && displayPage == HellobikePaymentPage.Methods &&
                    display.payOptions.none { it.channel.available == true }) OfficialHellobikeLink(enabled = enabled)
            }
        } }
    }
}

@Composable
private fun OfficialHellobikeLink(enabled: Boolean) {
    val context = LocalContext.current
    DashboardLink("打开哈啰", { openHellobike(context) }, enabled = enabled, arrow = false,
        modifier = Modifier.testTag("hellobike-official"), color = DashboardPalette.muted)
}
private fun openHellobike(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("com.jingyao.easybike")
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://m.hellobike.com/AppHelloBikeMainH5/latest/index.html"))
    try { context.startActivity(intent) }
    catch (_: Exception) { Toast.makeText(context, "请打开哈啰核对本次订单", Toast.LENGTH_SHORT).show() }
}
