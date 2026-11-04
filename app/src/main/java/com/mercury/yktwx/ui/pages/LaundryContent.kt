package com.mercury.yktwx.ui.pages

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.LifeDevicesUiState
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.FeeMethod
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun LazyListScope.laundryContent(state: LifeDevicesUiState, model: LifeDevicesViewModel,
    active: Boolean, nowMillis: () -> Long) {
    val session = state.laundry
    if (session == null) {
        item { LifeDeviceHeading("选择洗衣程序") }
        state.device?.let { device ->
            item { LaundryDeviceIdentity(device) }
            item {
                Column(Modifier.fillMaxWidth().testTag("laundry-programs"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    device.skus.forEach { sku ->
                        PaymentSelectionCard(sku.id == state.selectedSkuId, { model.selectSku(sku.id) },
                            Modifier.fillMaxWidth().testTag("laundry-program-${sku.id}"), enabled = active && sku.available && !state.laundryBusy,
                            animateSelection = true, neutral = true) { contentColor ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val minutes = state.laundryQuote?.takeIf { it.skuId == sku.id }?.minutes ?: sku.minutes
                                    Text(sku.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
                                    val subtitle = listOfNotNull(sku.feature.takeIf { it.isNotBlank() }, minutes?.let { "约 $it 分钟" }).joinToString(" · ")
                                    Text(subtitle, Modifier.testTag("laundry-program-minutes-${sku.id}"), fontSize = 11.sp,
                                        lineHeight = 17.sp, color = if (sku.available) DashboardPalette.muted else contentColor)
                                }
                                sku.price.toBigDecimalOrNull()?.let {
                                    Text("¥${it.setScale(2)}", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = contentColor)
                                }
                            }
                            if (!sku.available) Text("暂不可用", fontSize = 11.sp, color = contentColor.copy(alpha = .8f))
                        }
                    }
                }
            }
            val selected = device.skus.firstOrNull { it.id == state.selectedSkuId }
            if (selected != null) item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                        AnimatedContent(selected.name == "桶自洁", transitionSpec = {
                            fadeIn(tween(140, delayMillis = 80)) togetherWith fadeOut(tween(80))
                        }, label = "laundry-program-instructions") { cleaning ->
                            Text(if (cleaning) "桶自洁请按设备说明使用，关好门后启动。"
                                else "放好衣物并关好门后再支付。", Modifier.heightIn(min = 40.dp),
                                fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                        }
                }
            }
        }
    } else {
        item { LaundryProgress(session, state.laundryBusy, nowMillis) }
        item { LaundryFacts(session) }
        item {
            Card(Modifier.fillMaxWidth().testTag("laundry-usage-notice"), cornerRadius = 24.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session.phase == LaundryPhase.Closed) "订单已关闭，可重新扫码选择设备。"
                        else if (session.phase == LaundryPhase.Ended && session.program == "桶自洁") "桶自洁已完成，使用前请检查设备。"
                        else if (session.phase == LaundryPhase.Ended) "请检查设备，及时取回物品。"
                        else "预计时间仅供参考，以设备实际完成为准。",
                        fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                }
            }
        }
    }
    item { AnimatedInlineError(state.error.takeUnless { state.paymentVisible }) }
}

@Composable
private fun LaundryDeviceIdentity(device: LifeDevice) {
    Card(Modifier.fillMaxWidth().testTag("life-device-hero"), cornerRadius = 24.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LifeDeviceGlyph(LifeFeature.Laundry)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(device.name.ifBlank { "洗衣设备" }, Modifier.testTag("life-device-name"), fontSize = 19.sp,
                    lineHeight = 27.sp, fontWeight = FontWeight.SemiBold)
                if (device.shopName.isNotBlank()) Text(device.shopName, fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
            }
        }
    }
}

@Composable
private fun LaundryProgress(session: LaundrySession, busy: Boolean, nowMillis: () -> Long) {
    var now by remember(session.orderNo) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(session.phase, nowMillis) {
        while (session.phase == LaundryPhase.Running) { now = nowMillis(); delay(1000) }
    }
    val ended = session.phase == LaundryPhase.Ended
    Column(Modifier.fillMaxWidth().padding(top = 34.dp, bottom = 20.dp).testTag("laundry-progress"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (session.phase == LaundryPhase.Closed) {
            LifeDeviceGlyph(LifeFeature.Laundry)
            Text("订单已关闭", Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
        } else if (ended) {
            DeviceCompletedIcon("laundry-ended-icon")
            Text("洗衣已完成", Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            if (session.program.isNotBlank()) Text("${session.program}已结束", Modifier.padding(top = 7.dp), fontSize = 12.sp, color = DashboardPalette.muted)
        } else if (session.phase == LaundryPhase.Running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(lifeSuccessInk(), CircleShape))
                Text("${session.program}进行中", fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            session.expectedEndMillis?.let { end ->
                val seconds = ((end - now).coerceAtLeast(0) + 999) / 1000
                Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                    Modifier.padding(top = 22.dp, bottom = 9.dp).testTag("laundry-remaining"),
                    fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold)
                Text(if (end > now) "预计剩余时间" else "预计时间已到，正在读取完成状态", fontSize = 12.sp, color = DashboardPalette.muted)
            } ?: Text("设备运行中", Modifier.padding(top = 22.dp), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        } else {
            if (session.phase == LaundryPhase.Paying && !busy) LifeDeviceGlyph(LifeFeature.Laundry)
            else CircularProgressIndicator(Modifier.size(40.dp))
            val label = when (session.phase) {
                LaundryPhase.Checking -> "正在检查设备"
                LaundryPhase.Creating -> "正在创建订单"
                LaundryPhase.Paying -> "完成支付后启动设备"
                else -> "正在读取设备启动结果"
            }
            Text(label, Modifier.padding(top = 22.dp), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            val seconds = rememberLaundryPaymentSeconds(session, nowMillis = nowMillis)
            if (seconds != null) LaundryPaymentCountdown(seconds, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
internal fun DeviceCompletedIcon(tag: String) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (entered) 1f else .82f,
        tween(ContentMotion.Insert, easing = ContentMotion.Easing), label = "device-completed-scale")
    Box(Modifier.size(64.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = .1f), CircleShape).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, null, Modifier.size(30.dp * scale), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun LaundryFacts(session: LaundrySession) {
    val rows = buildList {
        if (session.device.name.isNotBlank()) add("设备" to session.device.name)
        if (session.device.shopName.isNotBlank()) add("位置" to session.device.shopName)
        if (session.program.isNotBlank()) add("程序" to session.program)
        add((if (session.paid) "支付金额" else "应付金额") to "¥${session.price.toBigDecimal().setScale(2)}")
        add("支付方式" to if (session.payMethod == "2") "余额支付" else "支付宝")
        if (session.phase == LaundryPhase.Ended) session.finishedAtMillis?.let { add("完成时间" to laundryTime(it)) }
        else session.expectedEndMillis?.let { add("预计完成" to laundryTime(it)) }
    }
    Card(Modifier.fillMaxWidth().testTag("laundry-info"), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                LifeDeviceField(label) { Text(value, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End) }
                if (index < rows.lastIndex) HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
            }
        }
    }
}

private fun laundryTime(millis: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Shanghai")))

@Composable
internal fun LaundryActions(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean, onBack: () -> Unit,
    nowMillis: () -> Long = System::currentTimeMillis) {
    val session = state.laundry
    val expired = rememberLaundryPaymentSeconds(session, active, nowMillis) == 0L
    val ended = session != null && !session.active
    val canCancel = session?.canCancel == true && state.paymentLaunch == null
    var cancelRequested by remember(session?.sessionId, active, state.paymentVisible, canCancel) { mutableStateOf(false) }
    val confirmingCancel = cancelRequested && active && canCancel && !state.paymentVisible && !state.laundryBusy
    val retry = state.error != null
    val label = when {
        state.laundryBusy -> "正在处理…"
        state.quoteLoading -> "正在读取价格…"
        ended -> "再次扫码洗衣"
        expired -> "查询支付结果"
        session?.phase == LaundryPhase.Paying -> "继续支付"
        retry -> "重试"
        session?.phase == LaundryPhase.Running -> "刷新进度"
        session != null -> "正在处理…"
        else -> "选择支付方式"
    }
    LifeDeviceActionFooter("laundry-action-footer") {
        if (session != null) Text(when {
            session.phase == LaundryPhase.Closed -> "可重新扫码下单"
            ended -> "本次服务已结束"
            session.phase == LaundryPhase.Paying -> "离开页面不会取消订单"
            else -> "离开页面不会停止设备"
        },
            fontSize = 12.sp, color = DashboardPalette.muted)
        Button(onClick = {
            when {
                ended -> model.scan()
                session?.phase == LaundryPhase.Paying -> model.showLaundryPayment()
                session?.phase == LaundryPhase.Checking && !session.createSent -> model.showLaundryPayment()
                retry || session != null -> model.retryLaundry()
                else -> model.showLaundryPayment()
            }
        }, enabled = active && !state.laundryBusy && !state.quoteLoading && when {
            session != null -> ended || retry || session.phase in setOf(LaundryPhase.Running, LaundryPhase.Paying)
            retry -> true
            else -> state.laundryQuote?.channels?.any { it.laundryUnavailableReason() == null } == true
        }, modifier = Modifier.fillMaxWidth().testTag("laundry-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
            AnimatedContent(label, transitionSpec = { fadeIn(tween(120, delayMillis = 100)) togetherWith fadeOut(tween(100)) },
                label = "laundry-button-label") { Text(it) }
        }
        if (canCancel) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { cancelRequested = true }, modifier = Modifier.weight(1f).testTag("laundry-cancel-order"),
                enabled = active && !state.laundryBusy && !state.quoteLoading && !state.paymentVisible) { Text("取消订单") }
            Button(onClick = onBack, modifier = Modifier.weight(1f), enabled = active) { Text("返回") }
        } else if (session != null) Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = active) { Text("返回") }
    }
    OverlayDialog(show = confirmingCancel, title = "取消洗衣订单", summary = "取消后需重新扫码下单。",
        onDismissRequest = { cancelRequested = false }, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { cancelRequested = false }, modifier = Modifier.weight(1f), enabled = confirmingCancel) { Text("暂不取消") }
            Button(onClick = {
                if (confirmingCancel) { cancelRequested = false; model.cancelLaundry() }
            }, modifier = Modifier.weight(1f).testTag("laundry-confirm-cancel"), enabled = confirmingCancel,
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认取消") }
        }
    }
}

@Composable
internal fun LaundryPaymentSheet(show: Boolean, state: LifeDevicesUiState, model: LifeDevicesViewModel,
    nowMillis: () -> Long = System::currentTimeMillis) {
    val overlaySession = rememberOverlaySession(show)
    val seconds = rememberLaundryPaymentSeconds(state.laundry, show, nowMillis)
    val expired = seconds == 0L
    val closed = state.laundry?.phase == LaundryPhase.Closed
    val feedback = if (expired && !state.laundryBusy && state.paymentLaunch == null &&
        state.paymentFeedback?.phase != RechargePhase.Paid)
        state.paymentFeedback?.copy(phase = RechargePhase.Checking, loading = false,
            detail = state.paymentFeedback.detail.takeIf { state.paymentFeedback.phase == RechargePhase.Checking }
                ?: LaundryPaymentExpiredMessage)
            ?: com.mercury.yktwx.LaundryPaymentFeedback(checkNotNull(state.laundry).sessionId, RechargePhase.Checking,
                detail = LaundryPaymentExpiredMessage)
    else state.paymentFeedback
    val isResult = feedback != null && feedback.phase != RechargePhase.Creating
    val targetPage = rememberWhileShown(show, if (isResult) LaundrySheetPage.Result else LaundrySheetPage.Method)
    val title = rememberWhileShown(show, if (isResult) null else "付款")
    PaymentSheet(show = show, title = title, onDismissRequest = model::dismissLaundryPayment,
        insideMargin = DpSize(0.dp, 0.dp)) {
        key(overlaySession) { SheetPageContent(targetPage, Modifier.bottomSheetSafeArea()) { page, interactive ->
            val resultPage = page == LaundrySheetPage.Result
            val enabled = show && interactive
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .then(if (resultPage) Modifier.padding(horizontal = 24.dp)
                    else Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp))
                .testTag("laundry-payment-sheet"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (resultPage) {
                    val result = rememberWhileShown(show && isResult, LaundryResultDisplay(
                        feedback?.phase ?: RechargePhase.Checking, feedback?.loading == true, feedback?.detail,
                        state.laundry?.price ?: state.laundryQuote?.price ?: "0.00",
                        state.laundryQuote?.channels.orEmpty()))
                    val methods = remember(result.channels) {
                        result.channels.filter { it.laundryUnavailableReason() == null }.map {
                            FeeMethod(it.method, it.method, it.name.removeSuffix("支付"), false, 0)
                        }
                    }
                    if (seconds != null && !expired) LaundryPaymentCountdown(seconds)
                    PaymentResultContent(result.phase, result.loading, result.price.toBigDecimal().movePointRight(2).longValueExact(),
                        result.detail, { if (enabled && isResult) model.dismissLaundryPayment() }, subject = "支付",
                        retryMethods = methods.takeUnless { expired || closed }.orEmpty(),
                        onContinuePayment = { if (enabled && isResult && !expired) model.payLaundry(it.id) },
                        enabled = enabled && isResult && (!state.laundryBusy || result.phase == RechargePhase.Paid),
                        onCheck = { if (enabled && isResult) model.checkLaundryPayment() }, paymentApp = "支付宝")
                } else {
                    // Keep display-only form values during the transition; never retain SDK payloads.
                    val quote = rememberWhileShown(show && !isResult, state.laundryQuote)
                    val busy = rememberWhileShown(show && !isResult, state.laundryBusy)
                    val error = rememberWhileShown(show && !isResult, state.error)
                    val channels = quote?.channels.orEmpty().filter { it.laundrySupported }
                    var selected by remember(channels) { mutableIntStateOf(channels.indexOfFirst { it.laundryUnavailableReason() == null }.coerceAtLeast(0)) }
                    Text(quote?.price?.let { "¥${it.toBigDecimal().setScale(2)}" } ?: "—", Modifier.fillMaxWidth(), fontSize = 32.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    if (seconds != null) LaundryPaymentCountdown(seconds)
                    val method = channels.getOrNull(selected)
                    val reason = if (channels.isEmpty()) "暂无支持的支付方式" else method?.laundryUnavailableReason()
                    val canPay = state.laundry?.let { !it.active || it.phase == LaundryPhase.Paying ||
                        it.phase == LaundryPhase.Checking && !it.createSent } ?: true
                    PaymentMethodActions(channels.map { it.name }, selected, { selected = it },
                        label = if (busy) "正在发起…" else if (method?.method == "13") "支付宝支付" else "确认支付",
                        onSubmit = { method?.let { model.payLaundry(it.method) } },
                        enabled = enabled && !isResult && !busy && state.paymentLaunch == null,
                        canSubmit = !expired && !closed && canPay && method != null && reason == null, error = error ?: reason,
                        modifier = Modifier.testTag("laundry-pay-submit"))
                }
            }
        } }
    }
}

@Composable
private fun rememberLaundryPaymentSeconds(session: LaundrySession?, active: Boolean = true,
    nowMillis: () -> Long = System::currentTimeMillis): Long? {
    val remaining by produceState(session?.paymentRemainingSeconds(nowMillis()), session, active, nowMillis) {
        do {
            value = session?.paymentRemainingSeconds(nowMillis())
            if (!active || value == null || value == 0L) break
            delay(1000)
        } while (true)
    }
    return remaining
}

@Composable
private fun LaundryPaymentCountdown(seconds: Long, modifier: Modifier = Modifier) {
    Text(if (seconds == 0L) "付款时间已到" else "剩余支付时间 ${String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)}",
        modifier.fillMaxWidth().testTag("laundry-payment-remaining"), fontSize = 12.sp,
        textAlign = TextAlign.Center, color = DashboardPalette.muted)
}

private enum class LaundrySheetPage { Method, Result }
private data class LaundryResultDisplay(val phase: RechargePhase, val loading: Boolean, val detail: String?,
    val price: String, val channels: List<LifePayChannel>)
