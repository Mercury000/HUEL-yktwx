package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.RechargeUiState
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
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
    var recipientSelected by remember(state.config, state.visible) { mutableStateOf(true) }
    val isResult = state.pending != null
    val loadingConfig = state.loading && !isResult && state.config == null
    OverlayBottomSheet(
        show = state.visible || loadingConfig,
        title = if (isResult) null else if (state.config?.recipient != null) "代人充值" else "卡片充值",
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
        // The result has its own spacing; avoid adding the sheet's defaults again.
        insideMargin = if (isResult) DpSize(0.dp, 0.dp) else DpSize(20.dp, 12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState())
                .then(if (isResult) Modifier.padding(horizontal = 24.dp) else Modifier.padding(vertical = 8.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isResult) {
                PaymentResultContent(state.phase, state.loading, state.pending.amountCents, state.error, onFinish,
                    subject = if (state.pending.recipient != null) "代充值" else "充值")
            } else {
                val config = state.config
                if (config != null) {
                    if (state.preview) Text("界面预览 · 不发起支付", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                    config.recipient?.let { recipient ->
                        RecipientSelectionCard(recipient, recipientSelected, { recipientSelected = !recipientSelected }, !state.loading)
                    }
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, state.loading,
                        !state.preview && recipientSelected, state.error, onSubmit)
                } else if (loadingConfig) {
                    PaymentAmountPlaceholder(methods = listOf("微信"))
                }
            }
        }
    }
}
