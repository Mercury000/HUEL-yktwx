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
import com.mercury.yktwx.ui.components.DigitTextField
import com.mercury.yktwx.ui.components.InlineErrorGroup
import com.mercury.yktwx.ui.components.rememberWhileShown
import com.mercury.yktwx.ui.components.ReadLayout
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class FeePasswordDisplay(
    val amountCents: Long, val accounts: List<FeeAccount>, val type: String, val loading: Boolean, val error: String?,
)

@Composable
internal fun FeePasswordDialog(show: Boolean, amountCents: Long, preparation: FeeAccountPreparation?, loading: Boolean, error: String?,
    onDismiss: () -> Unit, onPay: (String, String) -> Unit, onInputEdited: (FormField) -> Unit = {},
    selectedType: String? = null, onTypeChanged: ((String) -> Unit)? = null) {
    var password by remember(preparation, show) { mutableStateOf("") }
    var localType by remember(preparation) { mutableStateOf(preparation?.accounts?.firstOrNull()?.type.orEmpty()) }
    val type = selectedType ?: localType
    val keyboard = LocalSoftwareKeyboardController.current
    fun pay() {
        if (!show || preparation?.accounts?.none { it.type == type } != false || password.length != 6 || loading) return
        val value = password
        password = ""
        keyboard?.hide()
        onPay(type, value)
    }
    OverlayDialog(show = show, title = "一卡通支付",
        onDismissRequest = { if (show) { password = ""; keyboard?.hide(); onDismiss() } }, renderInRootScaffold = true) {
        val display = rememberWhileShown(show, FeePasswordDisplay(amountCents, preparation?.accounts.orEmpty(), type, loading, error))
        val enabled = show && !display.loading
        ReadLayout(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("¥ " + RechargeMoney.yuan(display.amountCents), fontSize = 32.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (display.accounts.size > 1 || display.accounts.none { it.type == type }) display.accounts.forEach { account ->
                Button(onClick = { if (type != account.type) { if (onTypeChanged != null) onTypeChanged(account.type) else localType = account.type; onInputEdited(FormField.PaymentAccount) } }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                    colors = if (display.type == account.type) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(account.name) }
            }
            display.accounts.firstOrNull { it.type == display.type }?.let { Text("可用余额 ¥ " + RechargeMoney.yuan(it.balanceCents), fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center) }
            DigitTextField(value = password, onValueChange = { next ->
                if (next != password) { password = next; onInputEdited(FormField.Password) }
            },
                label = "六位支付密码", maxDigits = 6, enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { pay() }), modifier = Modifier.fillMaxWidth())
            InlineErrorGroup(display.error, 16.dp) {
                Button(onClick = { pay() }, enabled = enabled && password.length == 6 && display.accounts.any { it.type == type },
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (display.loading) "正在支付…" else "确定支付") }
            }
        }
        }
    }
}
