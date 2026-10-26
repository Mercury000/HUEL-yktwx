package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.FeeUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FeePaymentDialogs(
    state: FeeUiState, interactive: Boolean, orderAmount: Long,
    onMethod: (FeeMethod) -> Unit, onPayAccount: (String, String) -> Unit,
    onCancelPayment: () -> Unit, onFinish: () -> Unit,
    onContinuePayment: (FeeMethod) -> Unit,
) {
    OverlayBottomSheet(show = interactive && state.stage == FeeStage.Methods, title = "支付方式",
        onDismissRequest = onCancelPayment, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("¥ " + RechargeMoney.yuan(orderAmount), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            InlineError(state.error)
            state.order?.methods.orEmpty().forEach { method ->
                val busy = state.loading && state.method?.id == method.id
                Button(onClick = { onMethod(method) }, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (busy) "正在准备支付" else method.name + "支付")
                }
            }
        }
    }
    FeePasswordDialog(
        show = interactive && state.stage == FeeStage.Password,
        amountCents = orderAmount, preparation = state.preparation, loading = state.loading, error = state.error,
        onDismiss = onCancelPayment, onPay = onPayAccount,
    )
    OverlayBottomSheet(show = interactive && state.stage == FeeStage.Result, title = null,
        onDismissRequest = onFinish, renderInRootScaffold = true, insideMargin = DpSize(0.dp, 0.dp)) {
        Box(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            PaymentResultContent(state.resultPhase, state.loading, orderAmount, state.error,
                onFinish, subject = "缴费",
                retryMethods = if (state.canContinuePayment) state.paymentRetry!!.methods else emptyList(),
                onContinuePayment = onContinuePayment)
        }
    }
}

@Composable
private fun FeePasswordDialog(show: Boolean, amountCents: Long, preparation: FeeAccountPreparation?, loading: Boolean, error: String?,
    onDismiss: () -> Unit, onPay: (String, String) -> Unit) {
    var password by remember(preparation, show) { mutableStateOf("") }
    var type by remember(preparation) { mutableStateOf(preparation?.accounts?.firstOrNull()?.type.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun pay() {
        if (!show || type.isEmpty() || password.length != 6 || loading) return
        val value = password
        password = ""
        keyboard?.hide()
        onPay(type, value)
    }
    OverlayDialog(show = show, title = "一卡通支付",
        onDismissRequest = { password = ""; keyboard?.hide(); onDismiss() }, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("¥ " + RechargeMoney.yuan(amountCents), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if ((preparation?.accounts?.size ?: 0) > 1) preparation?.accounts?.forEach { account ->
                Button(onClick = { type = account.type }, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                    colors = if (type == account.type) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(account.name) }
            }
            preparation?.accounts?.firstOrNull { it.type == type }?.let { Text("可用余额 ¥ " + RechargeMoney.yuan(it.balanceCents), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center) }
            TextField(value = password, onValueChange = { password = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "六位支付密码", singleLine = true, useLabelAsPlaceholder = true, enabled = !loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { pay() }), modifier = Modifier.fillMaxWidth())
            InlineError(error)
            Button(onClick = { pay() }, enabled = password.length == 6 && !loading && type.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (loading) "正在支付…" else "确定支付") }
        }
    }
}
