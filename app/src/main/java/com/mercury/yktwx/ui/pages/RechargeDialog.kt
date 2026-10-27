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
import com.mercury.yktwx.ui.components.SheetTitle
import com.mercury.yktwx.ui.components.SheetPageContent
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class RechargeSheetPage { Amount, Result }

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
        startAction = { SheetTitle(if (isResult) null else if (state.config?.recipient != null) "代人充值" else "卡片充值") },
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
        // The result has its own spacing; avoid adding the sheet's defaults again.
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        SheetPageContent(if (isResult) RechargeSheetPage.Result else RechargeSheetPage.Amount,
            Modifier.bottomSheetSafeArea()) { page, interactive ->
        val resultPage = page == RechargeSheetPage.Result
        val enabled = interactive && state.visible
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .then(if (resultPage) Modifier.padding(horizontal = 24.dp)
                    else Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (resultPage) {
                PaymentResultContent(state.phase, state.loading, state.pending?.amountCents ?: 0, state.error,
                    { if (enabled) onFinish() }, subject = if (state.pending?.recipient != null) "代充值" else "充值")
            } else {
                val config = state.config
                if (config != null) {
                    if (state.preview) Text("界面预览 · 不发起支付", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                    config.recipient?.let { recipient ->
                        RecipientSelectionCard(recipient, recipientSelected, { recipientSelected = !recipientSelected }, enabled && !state.loading)
                    }
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, state.loading || !enabled,
                        enabled && !state.preview && recipientSelected, state.error, onSubmit)
                } else if (loadingConfig) {
                    PaymentAmountPlaceholder(methods = listOf("微信"))
                }
            }
        }
        }
    }
}
