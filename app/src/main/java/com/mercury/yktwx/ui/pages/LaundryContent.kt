package com.mercury.yktwx.ui.pages

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.LifeDevicesUiState
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
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
                                DeviceOptionCard(sku.id == state.selectedSkuId, { model.selectSku(sku.id) },
                                    Modifier.fillMaxWidth().testTag("laundry-program-${sku.id}"), enabled = active && sku.available && !state.laundryBusy) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(sku.id == state.selectedSkuId, onClick = null, enabled = sku.available)
                                        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(sku.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                            if (sku.feature.isNotBlank()) Text(sku.feature, fontSize = 11.sp, lineHeight = 17.sp, color = DashboardPalette.muted)
                                            if (sku.id == state.selectedSkuId) state.laundryQuote?.minutes?.let {
                                                Text("预计 $it 分钟", fontSize = 11.sp, color = DashboardPalette.muted)
                                            }
                                        }
                                    sku.price.toBigDecimalOrNull()?.let {
                                        Text("¥${it.setScale(2)}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    }
                                    }
                                    if (!sku.available) Text("暂不可用", fontSize = 11.sp, color = DashboardPalette.muted)
                                }
                    }
                }
            }
            val selected = device.skus.firstOrNull { it.id == state.selectedSkuId }
            if (selected != null) item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("开始前检查设备", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (selected.name == "桶自洁") "桶自洁请按设备说明使用，关好门后再启动。"
                            else "请按设备说明操作，放好衣物并关好门后再支付。", fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                    }
                }
            }
        }
    } else {
        item { LaundryProgress(session, state.laundryBusy, nowMillis) }
        item { LaundryFacts(session) }
        item {
            Card(Modifier.fillMaxWidth().testTag("laundry-usage-notice"), cornerRadius = 22.dp,
                colors = CardDefaults.defaultColors(color = if (session.phase == LaundryPhase.Ended) DashboardPalette.lightingPanel else DashboardPalette.card)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (session.phase == LaundryPhase.Ended) "本次程序已完成" else "运行期间请按设备说明操作",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (session.phase == LaundryPhase.Ended && session.program == "桶自洁") "桶自洁已完成，使用前请检查设备。"
                        else if (session.phase == LaundryPhase.Ended) "请检查设备，及时取回物品。"
                        else "离开此页不会停止洗衣，可从大厅返回进度。预计时间归零后仍会读取订单状态。",
                        fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                }
            }
        }
    }
    item { AnimatedInlineError(state.error) }
}

@Composable
private fun LaundryDeviceIdentity(device: LifeDevice) {
    Card(Modifier.fillMaxWidth().testTag("life-device-hero"), cornerRadius = 24.dp) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
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

/** Shared native miuix Surface with animated selection for programs and payment channels. */
@Composable
internal fun DeviceOptionCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val fill by animateColorAsState(if (selected) DashboardPalette.airPanel else DashboardPalette.card,
        tween(ContentMotion.Number), label = "device-option-fill")
    ActionSurface(onClick, modifier, enabled, RoundedCornerShape(18.dp), fill) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
        if (ended) {
            DeviceCompletedIcon("laundry-ended-icon")
            Text("洗衣已完成", Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            if (session.program.isNotBlank()) Text("${session.program}已结束", Modifier.padding(top = 7.dp), fontSize = 12.sp, color = DashboardPalette.muted)
        } else if (session.phase == LaundryPhase.Running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(lifeSuccessInk(), CircleShape))
                Text("${session.program}进行中", fontSize = 12.sp, color = DashboardPalette.airAccent, fontWeight = FontWeight.Medium)
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
        }
    }
}

@Composable
internal fun DeviceCompletedIcon(tag: String) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (entered) 1f else .82f,
        tween(ContentMotion.Insert, easing = ContentMotion.Easing), label = "device-completed-scale")
    Box(Modifier.size(64.dp).background(lifeSuccessFill(), CircleShape).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, null, Modifier.size(30.dp * scale), tint = lifeSuccessInk())
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
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
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
internal fun LaundryActions(state: LifeDevicesUiState, model: LifeDevicesViewModel, active: Boolean, onBack: () -> Unit) {
    val session = state.laundry
    val ended = session?.phase == LaundryPhase.Ended
    val retry = state.error != null
    val label = when {
        state.laundryBusy -> "正在处理…"
        state.quoteLoading -> "正在读取价格…"
        ended -> "再次扫码洗衣"
        session?.phase == LaundryPhase.Paying -> "继续支付"
        retry -> "重试"
        session?.phase == LaundryPhase.Running -> "刷新进度"
        session != null -> "正在处理…"
        else -> "选择支付方式"
    }
    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(horizontal = 16.dp)
        .padding(top = 14.dp).bottomSheetSafeArea().padding(bottom = 20.dp).testTag("laundry-action-footer"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (session == null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("本次应付", fontSize = 12.sp, color = DashboardPalette.muted)
            AnimatedContent(state.laundryQuote?.price?.let { "¥${it.toBigDecimal().setScale(2)}" } ?: "—",
                transitionSpec = { fadeIn(tween(ContentMotion.Fade)) togetherWith fadeOut(tween(ContentMotion.Fade)) },
                label = "laundry-price-update") { amount -> Text(amount,
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("laundry-quote-price"))
            }
        } else Text(if (ended) "本次服务已结束" else "离开页面不会停止设备", fontSize = 10.sp, color = DashboardPalette.muted)
        Button(onClick = {
            when {
                ended -> model.scan()
                session?.phase == LaundryPhase.Paying -> model.showLaundryPayment()
                retry || session != null -> model.retryLaundry()
                else -> model.showLaundryPayment()
            }
        }, enabled = active && !state.laundryBusy && !state.quoteLoading && when {
            session != null -> ended || retry || session.phase in setOf(LaundryPhase.Running, LaundryPhase.Paying)
            retry -> true
            else -> state.laundryQuote?.channels?.any { it.laundryUnavailableReason() == null } == true
        }, modifier = Modifier.fillMaxWidth().testTag("laundry-primary-action"), colors = ButtonDefaults.buttonColorsPrimary()) {
            AnimatedContent(label, transitionSpec = { fadeIn(tween(ContentMotion.Fade)) togetherWith fadeOut(tween(ContentMotion.Fade)) },
                label = "laundry-button-label") { Text(it) }
        }
        if (session != null) TextButton("返回大厅", onBack, Modifier.fillMaxWidth(), enabled = active,
            colors = ButtonDefaults.textButtonColors(color = DashboardPalette.card, textColor = MiuixTheme.colorScheme.primary))
    }
}

@Composable
internal fun LaundryPaymentSheet(show: Boolean, state: LifeDevicesUiState, model: LifeDevicesViewModel) {
    val overlaySession = rememberOverlaySession(show)
    OverlayBottomSheet(show = show, startAction = { SheetTitle("支付并启动") }, onDismissRequest = model::dismissLaundryPayment,
        renderInRootScaffold = true) {
        key(overlaySession) {
            val display = rememberWhileShown(show, state)
            val quote = display.laundryQuote
            var selected by remember { mutableStateOf(quote?.channels?.firstOrNull { it.laundryUnavailableReason() == null }?.method.orEmpty()) }
            Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).testTag("laundry-payment-sheet"),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(quote?.price?.let { "¥${it.toBigDecimal().setScale(2)}" } ?: "—", Modifier.fillMaxWidth(), fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                val program = display.device?.skus?.firstOrNull { it.id == quote?.skuId }?.name.orEmpty()
                Text(listOfNotNull(display.device?.name?.takeIf { it.isNotBlank() }, program.takeIf { it.isNotBlank() }).joinToString(" · "),
                    Modifier.fillMaxWidth(), fontSize = 13.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
                quote?.channels?.forEach { channel ->
                    val reason = channel.laundryUnavailableReason()
                    DeviceOptionCard(selected == channel.method, { selected = channel.method }, enabled = show && reason == null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(channel.name, fontSize = 14.sp)
                                if (reason != null) Text(reason, fontSize = 11.sp, lineHeight = 18.sp, color = DashboardPalette.muted)
                            }
                            RadioButton(selected == channel.method, onClick = null, enabled = reason == null)
                        }
                    }
                }
                Text("支付后自动读取设备启动结果，请先按设备说明做好准备。", fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
                Button(onClick = { model.payLaundry(selected) }, enabled = show && !display.laundryBusy &&
                    quote?.channels?.any { it.method == selected && it.laundryUnavailableReason() == null } == true,
                    modifier = Modifier.fillMaxWidth().testTag("laundry-pay-submit"), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (selected == "13") "支付宝支付并启动" else "余额支付并启动")
                }
            }
        }
    }
}
