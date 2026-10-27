package com.mercury.yktwx.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.CardServiceUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import com.mercury.yktwx.ui.components.SheetPageContent
import com.mercury.yktwx.ui.components.sheetContentSize
import com.mercury.yktwx.ui.components.rememberOverlaySession
import com.mercury.yktwx.ui.components.rememberWhileShown
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class RecipientSheetPage { Query, Amount }

@Composable
fun CardServiceDialog(
    state: CardServiceUiState,
    onDismiss: () -> Unit,
    onSelectCard: (CampusCard) -> Unit,
    onChangeCard: (String) -> Unit,
    onRefreshCaptcha: () -> Unit,
    onRecipientEdited: () -> Unit,
    onQueryRecipient: (RecipientQueryType, String, String) -> Unit,
    onSelectRecipient: (RechargeRecipient) -> Unit,
    onContinueRecharge: (Long) -> Unit,
    onRetryRecharge: () -> Unit = {},
) {
    // Keep each overlay mounted while its own closing animation runs.
    CardStateDialog(state, onDismiss, onSelectCard, onChangeCard)
    RecipientRechargeSheet(state, onDismiss, onRefreshCaptcha, onRecipientEdited, onQueryRecipient,
        onSelectRecipient, onContinueRecharge, onRetryRecharge)
}

@Composable
private fun RecipientRechargeSheet(
    state: CardServiceUiState, onDismiss: () -> Unit, onRefreshCaptcha: () -> Unit,
    onRecipientEdited: () -> Unit, onQueryRecipient: (RecipientQueryType, String, String) -> Unit,
    onSelectRecipient: (RechargeRecipient) -> Unit, onContinueRecharge: (Long) -> Unit,
    onRetryRecharge: () -> Unit,
) {
    val show = state.visible && state.action == CampusCardAction.OthersRecharge
    val session = rememberOverlaySession(show)
    val targetPage = rememberWhileShown(show,
        if (state.recipients.isNotEmpty()) RecipientSheetPage.Amount else RecipientSheetPage.Query)
    val keyboard = LocalSoftwareKeyboardController.current
    OverlayBottomSheet(show = show, title = "代人充值", onDismissRequest = onDismiss,
        renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        key(session) {
    // Only presentation data survives dismissal; the captcha key stays in live state.
    val display = rememberWhileShown(show, state.copy(captcha = null))
    // Input is local, not SavedState. A new sheet starts with empty query and captcha fields.
    var queryType by remember { mutableStateOf(RecipientQueryType.StudentNumber) }
    var identifier by remember { mutableStateOf("") }
    var captchaCode by remember(show, state.captcha) { mutableStateOf("") }
    var amount by remember(display.recipients) { mutableStateOf("") }
        SheetPageContent(targetPage,
            Modifier.bottomSheetSafeArea()) { page, interactive ->
        val enabled = interactive && show && !display.loading
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (page == RecipientSheetPage.Amount) {
                display.recipients.forEach { recipient ->
                    RecipientSelectionCard(recipient, display.selectedRecipient === recipient,
                        { onSelectRecipient(recipient) }, enabled)
                }
                val config = display.rechargeConfig
                if (config != null) {
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, display.loading,
                        !display.preview && config.recipient === display.selectedRecipient, display.error, onContinueRecharge,
                        enabled = enabled)
                } else {
                    if (display.loading) PaymentAmountPlaceholder()
                    InlineError(display.error, actionLabel = "重试", onAction = { if (enabled) onRetryRecharge() })
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecipientQueryType.entries.forEach { type ->
                        Button(onClick = { if (queryType != type) { queryType = type; identifier = ""; captchaCode = ""; onRecipientEdited() } },
                            enabled = enabled, modifier = Modifier.weight(1f),
                            colors = if (queryType == type) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(type.label) }
                    }
                }
                TextField(value = identifier, onValueChange = {
                    identifier = it.filter { c -> c in '0'..'9' || (queryType == RecipientQueryType.Identity && c in "Xx") }.take(32)
                    onRecipientEdited()
                }, label = queryType.label, singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = if (queryType == RecipientQueryType.StudentNumber) KeyboardType.Number else KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth())
                val bitmap = rememberWhileShown(show, remember(state.captcha) {
                    state.captcha?.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(112.dp).height(56.dp).clickable(enabled = enabled, role = Role.Button,
                        onClickLabel = "更换验证码", onClick = { captchaCode = ""; onRefreshCaptcha() }), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "图形验证码", modifier = Modifier.fillMaxSize())
                        else if (display.loading) CircularProgressIndicator(Modifier.size(24.dp))
                        else Text(if (display.preview) "预览" else "点击重试", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    TextField(value = captchaCode, onValueChange = { captchaCode = it.take(16) }, label = "验证码", singleLine = true,
                        enabled = enabled, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                }
                InlineError(display.error)
                Button(onClick = { keyboard?.hide(); val code = captchaCode; captchaCode = ""; onQueryRecipient(queryType, identifier, code) },
                    enabled = enabled && identifier.isNotBlank() && (state.preview || (state.captcha != null && captchaCode.isNotBlank())),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (display.loading) "查询中…" else "查询") }
            }
        } }
        }
    }
}

@Composable
private fun CardStateDialog(state: CardServiceUiState, onDismiss: () -> Unit, onSelectCard: (CampusCard) -> Unit, onChangeCard: (String) -> Unit) {
    val show = state.visible && state.action != CampusCardAction.OthersRecharge
    val display = rememberWhileShown(show, state.copy(captcha = null))
    var password by remember(show, display.selectedCard) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val card = display.selectedCard
    val initialLoading = display.loading && card == null
    OverlayDialog(show = show,
        title = if (card != null || display.action == CampusCardAction.Unlost) "确认" + display.operation.label else "挂失 / 解挂",
        summary = if (card != null && !display.loading && !display.needsPassword && !card.lost) "挂失后校园卡将暂停使用" else null,
        onDismissRequest = onDismiss, defaultWindowInsetsPadding = true, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().imePadding().sheetContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (initialLoading) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            if (display.cards.size > 1) display.cards.forEach { candidate ->
                Button(onClick = { onSelectCard(candidate) }, enabled = show && !display.loading && !candidate.frozen && candidate.status == 0,
                    modifier = Modifier.fillMaxWidth(), colors = if (card === candidate) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text(candidate.label + if (candidate.lost) " · 已挂失" else " · 未挂失")
                }
            }
            if (card != null && display.needsPassword) {
                TextField(value = password,
                    onValueChange = { password = it.filter { c -> c in '0'..'9' }.take(6) }, label = "校园卡查询密码", singleLine = true,
                    enabled = show && !display.loading, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            } else {
                if (!display.loading && card == null && display.error == null) Text(
                    if (display.cards.any { it.frozen || it.status != 0 }) "校园卡已冻结或状态异常" else "没有可操作的校园卡", fontSize = 14.sp)
            }
            InlineError(display.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, enabled = show, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { keyboard?.hide(); val submitted = password; password = ""; onChangeCard(submitted) },
                    enabled = show && card != null && !display.loading && !display.preview && (!display.needsPassword || password.length == 6),
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (display.loading) "处理中…" else "确定") }
            }
        }
    }
}
