package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.RechargeUiState
import com.mercury.yktwx.data.RechargeMoney
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        if (config.allowCustom) TextField(
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
        val inputError = validationMessage ?: error
        inputError?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
    }
}

@Composable
internal fun PaymentResultContent(phase: RechargePhase, loading: Boolean, amountCents: Long, detail: String?, onFinish: () -> Unit, subject: String = "充值") {
    val busy = loading || phase == RechargePhase.AwaitingPayment
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
            "¥ " + RechargeMoney.yuan(amountCents),
            fontSize = 36.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        val resultDetail = detail ?: if (phase == RechargePhase.Unknown) "稍后可在账单中核实" else null
        if (!busy && phase != RechargePhase.Paid && resultDetail != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                resultDetail,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
        if (!busy) {
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
