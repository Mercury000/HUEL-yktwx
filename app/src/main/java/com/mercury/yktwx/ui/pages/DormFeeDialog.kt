package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.FeeUiState
import com.mercury.yktwx.data.FeeMethod
import com.mercury.yktwx.data.DormMeterDiscovery
import com.mercury.yktwx.data.RechargeMoney
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import com.mercury.yktwx.ui.components.PaymentSelectionCard
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The selected dorm meter already supplies the target; only ask for the amount. */
@Composable
internal fun DormFeeDialog(
    state: FeeUiState, visible: Boolean, title: String,
    onDismiss: () -> Unit, onCreate: (Long) -> Unit, onMethod: (FeeMethod) -> Unit,
    onPayAccount: (String, String) -> Unit, onCancelPayment: () -> Unit,
    onFinish: () -> Unit, onContinuePayment: (FeeMethod) -> Unit,
) {
    var amount by remember(state.config, visible) { mutableStateOf("") }
    var selected by remember(state.selections, visible) { mutableStateOf(true) }
    val cents = RechargeMoney.cents(amount)
    val config = state.config
    val amountError = config?.amountRules?.amountError(cents)
    OverlayBottomSheet(
        show = visible && state.stage == FeeStage.Query,
        title = title, onDismissRequest = onDismiss, renderInRootScaffold = true,
        insideMargin = DpSize(20.dp, 12.dp),
    ) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.currentRoom?.let { room ->
                val kind = DormMeterDiscovery.kind(room.names.values.lastOrNull().orEmpty())
                PaymentSelectionCard(selected = selected, onClick = { selected = !selected }, enabled = !state.loading,
                    modifier = Modifier.testTag("dorm-payment-target")) { contentColor ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(room.dialogLabel.removeSuffix(kind.label).trim(), Modifier.weight(1f),
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
                        if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
                    }
                    Text(listOfNotNull(config?.name, kind.label).joinToString(" · "), fontSize = 14.sp,
                        color = if (selected) contentColor else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (state.preview) Text("界面预览 · 不发起缴费", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
            if (config != null) {
                PaymentAmountInput(config.amountRules, amount, { amount = it }, state.error, !state.loading)
                Button(
                    onClick = { if (selected) cents?.let(onCreate) },
                    enabled = selected && !state.preview && !state.loading && !state.selecting && !state.refreshing &&
                        state.query?.canPay == true && state.query.thirdParty != null && amountError == null,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (state.loading) "正在发起缴费…" else if (cents != null && amountError == null) "缴费 ¥ " + RechargeMoney.yuan(cents) else "缴费")
                }
            }
        }
    }
    FeePaymentDialogs(state, visible, state.order?.pending?.amountCents ?: state.paymentRetry?.amountCents ?: cents ?: 0,
        onMethod, onPayAccount, onCancelPayment, onFinish, onContinuePayment)
}
