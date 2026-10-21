package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun RechargeDialog(
    state: RechargeUiState,
    onDismiss: () -> Unit,
    onSubmit: (Long) -> Unit,
    onFinish: () -> Unit,
) {
    var amount by remember(state.config) { mutableStateOf("") }
    val cents = RechargeMoney.cents(amount)
    val amountError = state.config?.amountError(cents)
    val isResult = state.pending != null
    OverlayBottomSheet(
        show = state.visible,
        title = if (isResult) null else "卡片充值",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
        // The result has its own spacing; avoid adding the sheet's defaults again.
        insideMargin = if (isResult) DpSize(0.dp, 0.dp) else DpSize(20.dp, 12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .then(if (isResult) Modifier.padding(horizontal = 24.dp) else Modifier.padding(vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isResult) {
                PaymentResultContent(state.phase, state.loading, state.pending?.amountCents ?: 0, state.error, onFinish)
            } else state.config?.let { config ->
                if (state.preview) Text("界面预览 · 不发起支付", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                PaymentAmountInput(config, amount, { amount = it }, state.error, !state.loading)
                Button(
                    onClick = { cents?.let(onSubmit) }, enabled = !state.loading && !state.preview && amountError == null,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (cents != null && amountError == null) "微信充值 ¥ " + RechargeMoney.yuan(cents) else "微信充值")
                }
            }
        }
    }
}
