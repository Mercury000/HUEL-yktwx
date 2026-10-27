package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.FeeMethod
import com.mercury.yktwx.data.RechargeMoney
import com.mercury.yktwx.data.RechargeConfiguration
import com.mercury.yktwx.data.RechargeRecipient
import com.mercury.yktwx.ui.components.FieldError
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.PaymentSelectionCard
import com.mercury.yktwx.ui.components.ContentPlaceholder
import com.mercury.yktwx.ui.components.DashboardPlaceholder
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The amount and method form shared by hall fees and campus-card recharge. */
@Composable
internal fun PaymentAmountForm(
    config: RechargeConfiguration, amount: String, onAmountChange: (String) -> Unit,
    methods: List<String>, selectedMethod: Int, onMethod: (Int) -> Unit,
    loading: Boolean, canSubmit: Boolean, error: String?, onSubmit: (Long) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    val cents = RechargeMoney.cents(amount)
    val valid = config.amountError(cents) == null
    val keyboard = LocalSoftwareKeyboardController.current
    Text("充值金额", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    PaymentAmountInput(config, amount, onAmountChange, enabled = !loading)
    Text("支付方式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    HallNeutralTabs(methods, selectedMethod) { if (!loading) onMethod(it) }
    InlineError(error, actionLabel = if (onRetry == null) null else "重试", onAction = { onRetry?.invoke() })
    Button(onClick = { if (canSubmit && !loading && valid) cents?.let { keyboard?.hide(); onSubmit(it) } },
        enabled = canSubmit && !loading && valid,
        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
        Text(if (loading) "正在发起…" else if (cents != null && valid) "充值 ¥ " + RechargeMoney.yuan(cents) else "充值")
    }
}

@Composable
internal fun PaymentAmountPlaceholder(modifier: Modifier = Modifier, config: RechargeConfiguration? = null,
    methods: List<String> = listOf("微信")) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "正在读取充值信息" },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .28f)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val amounts = config?.amountsCents ?: listOf(1000L, 5000L, 10000L)
            amounts.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { amount ->
                        PaymentControlPlaceholder(Modifier.weight(1f)) {
                            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                                Text("¥ " + RechargeMoney.label(amount))
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (config?.allowCustom != false) PaymentControlPlaceholder {
                TextField(value = "", onValueChange = {}, label = "自定义金额（元）",
                    useLabelAsPlaceholder = true, singleLine = true, enabled = false,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .28f)
        PaymentControlPlaceholder { HallNeutralTabs(methods, 0) {} }
        InlineError(null)
        PaymentControlPlaceholder {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()) { Text("充值") }
        }
    }
}

/** Measure with the same controls as the form so font scale and library defaults also match. */
@Composable
private fun PaymentControlPlaceholder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().drawWithContent {}.clearAndSetSemantics {}) { content() }
        ContentPlaceholder(Modifier.matchParentSize(), cornerRadius = 16.dp)
    }
}

@Composable
internal fun RecipientSelectionCard(recipient: RechargeRecipient, selected: Boolean,
    onClick: () -> Unit, enabled: Boolean = true) {
    PaymentSelectionCard(selected, onClick, Modifier.testTag("recharge-recipient-${recipient.targetId}"), enabled) { contentColor ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(recipient.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = contentColor, modifier = Modifier.weight(1f))
            if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
        }
        Text(recipient.sno.ifBlank { recipient.cardAccount }, fontSize = 14.sp, color = contentColor)
        Text(recipient.accountName + " · " + recipient.targetId, fontSize = 13.sp,
            color = contentColor.copy(alpha = .8f))
    }
}

@Composable
internal fun PaymentAmountInput(
    config: com.mercury.yktwx.data.RechargeConfiguration,
    amount: String,
    onAmountChange: (String) -> Unit,
    error: String? = null,
    enabled: Boolean = true,
) {
    var validationMessage by remember(config) { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cents = RechargeMoney.cents(amount)
    val amountError = config.amountError(cents)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        config.amountsCents.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    Button(
                        onClick = {
                            validationMessage = null
                            onAmountChange(RechargeMoney.label(preset))
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }, modifier = Modifier.weight(1f), enabled = enabled,
                        colors = if (cents == preset) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { Text("¥ " + RechargeMoney.label(preset)) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (config.allowCustom) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextField(
                value = amount,
                onValueChange = { value ->
                    if (value.length <= 11 && value.all { it in '0'..'9' || it == '.' } && value.count { it == '.' } <= 1) {
                        onAmountChange(value)
                        validationMessage = null
                    }
                },
                label = "自定义金额（元）", useLabelAsPlaceholder = true, singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (focus.isFocused) validationMessage = null
                },
                keyboardOptions = KeyboardOptions(keyboardType = if (config.integerOnly) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    validationMessage = if (amount.isBlank()) null else amountError
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
            )
            // Only an explicit keyboard Done can show a format hint. Neither
            // preset selection nor focus loss renders a transient validation row.
            FieldError(validationMessage)
        }
        InlineError(error)
    }
}

@Composable
internal fun PaymentResultContent(
    phase: RechargePhase,
    loading: Boolean,
    amountCents: Long,
    detail: String?,
    onFinish: () -> Unit,
    subject: String = "充值",
    retryMethods: List<FeeMethod> = emptyList(),
    onContinuePayment: (FeeMethod) -> Unit = {},
) {
    val busy = loading || phase == RechargePhase.AwaitingPayment
    val failed = phase == RechargePhase.Failed && !busy
    val methods = retryMethods.distinctBy { it.id to it.code }
    val canContinue = failed && methods.isNotEmpty()
    var selectedMethod by remember(phase, detail, retryMethods) { mutableStateOf<FeeMethod?>(null) }
    val title = when (phase) {
        RechargePhase.Paid -> subject + "成功"
        RechargePhase.Failed -> subject + "失败"
        RechargePhase.Unknown -> "结果待确认"
        RechargePhase.Checking -> "正在确认结果"
        RechargePhase.Creating -> "正在发起" + subject
        else -> "请在微信中完成支付"
    }
    val color = when (phase) {
        RechargePhase.Paid -> MiuixTheme.colorScheme.primary
        RechargePhase.Failed -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 0.dp, bottom = if (busy) 36.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            ResultMark(phase, color)
        }
        Spacer(Modifier.height(if (busy) 16.dp else 20.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (busy) 8.dp else 12.dp))
        Text(
            if (failed) detail?.takeIf { it.isNotBlank() } ?: "未能完成支付，请稍后重试"
            else "¥ " + RechargeMoney.yuan(amountCents),
            modifier = Modifier.fillMaxWidth(),
            fontSize = if (failed) 17.sp else 36.sp,
            fontWeight = if (failed) FontWeight.Normal else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        val resultDetail = detail ?: if (phase == RechargePhase.Unknown) "稍后可在账单中核实" else null
        if (!busy && !failed && phase != RechargePhase.Paid && resultDetail != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                resultDetail,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
        if (canContinue) {
            Spacer(Modifier.height(20.dp))
            Text("可选择以下方式重新支付", fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                methods.forEach { method ->
                    val selected = selectedMethod?.let { it.id == method.id && it.code == method.code } == true
                    PaymentSelectionCard(selected = selected,
                        onClick = { selectedMethod = if (selected) null else method }) { contentColor ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(method.name + "支付", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                color = contentColor, modifier = Modifier.weight(1f))
                            if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("取消支付") }
                Button(onClick = { selectedMethod?.let(onContinuePayment) }, enabled = selectedMethod != null,
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("继续支付") }
            }
        } else if (!busy) {
            Spacer(Modifier.height(28.dp))
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("完成") }
        }
    }
}

@Composable
private fun ResultMark(phase: RechargePhase, color: Color) {
    Canvas(Modifier.size(76.dp)) {
        drawCircle(color.copy(alpha = 0.10f))
        val stroke = 4.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        when (phase) {
            RechargePhase.Paid -> { line(.28f, .50f, .44f, .65f); line(.44f, .65f, .73f, .35f) }
            RechargePhase.Failed -> { line(.34f, .34f, .66f, .66f); line(.66f, .34f, .34f, .66f) }
            else -> {
                drawCircle(color, radius = size.width * .25f, style = Stroke(stroke))
                line(.50f, .34f, .50f, .50f); line(.50f, .50f, .62f, .57f)
            }
        }
    }
}
