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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.InlineError
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun FeePasswordDialog(show: Boolean, amountCents: Long, preparation: FeeAccountPreparation?, loading: Boolean, error: String?,
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
