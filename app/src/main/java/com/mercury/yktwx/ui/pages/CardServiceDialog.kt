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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.CardServiceUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.FieldError
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import com.mercury.yktwx.ui.components.ContentPlaceholder
import com.mercury.yktwx.ui.components.DashboardPlaceholder
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    if (state.action != CampusCardAction.OthersRecharge) {
        CardStateDialog(state, onDismiss, onSelectCard, onChangeCard)
        return
    }
    // Input is local, not SavedState. A new sheet starts with empty query and captcha fields.
    var queryType by remember(state.visible) { mutableStateOf(RecipientQueryType.StudentNumber) }
    var identifier by remember(state.visible) { mutableStateOf("") }
    var captchaCode by remember(state.visible, state.captcha) { mutableStateOf("") }
    var amount by remember(state.visible, state.recipients) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val confirming = state.recipients.isNotEmpty()
    OverlayBottomSheet(show = state.visible, title = "代人充值", onDismissRequest = onDismiss,
        renderInRootScaffold = true, insideMargin = DpSize(20.dp, 12.dp)) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (confirming) {
                state.recipients.forEach { recipient ->
                    RecipientSelectionCard(recipient, state.selectedRecipient === recipient,
                        { onSelectRecipient(recipient) }, !state.loading)
                }
                val config = state.rechargeConfig
                if (config != null) {
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, state.loading,
                        !state.preview && config.recipient === state.selectedRecipient, state.error, onContinueRecharge)
                } else {
                    if (state.loading) PaymentAmountPlaceholder()
                    InlineError(state.error, actionLabel = "重试", onAction = onRetryRecharge)
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecipientQueryType.entries.forEach { type ->
                        Button(onClick = { if (queryType != type) { queryType = type; identifier = ""; captchaCode = ""; onRecipientEdited() } },
                            enabled = !state.loading, modifier = Modifier.weight(1f),
                            colors = if (queryType == type) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) { Text(type.label) }
                    }
                }
                TextField(value = identifier, onValueChange = {
                    identifier = it.filter { c -> c in '0'..'9' || (queryType == RecipientQueryType.Identity && c in "Xx") }.take(32)
                    onRecipientEdited()
                }, label = queryType.label, singleLine = true, enabled = !state.loading,
                    keyboardOptions = KeyboardOptions(keyboardType = if (queryType == RecipientQueryType.StudentNumber) KeyboardType.Number else KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth())
                val bitmap = remember(state.captcha) { state.captcha?.image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(112.dp).height(56.dp).clickable(enabled = !state.loading, role = Role.Button,
                        onClickLabel = "更换验证码", onClick = { captchaCode = ""; onRefreshCaptcha() }), contentAlignment = Alignment.Center) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "图形验证码", modifier = Modifier.fillMaxSize())
                        else if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
                        else Text(if (state.preview) "预览" else "点击重试", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    TextField(value = captchaCode, onValueChange = { captchaCode = it.take(16) }, label = "验证码", singleLine = true,
                        enabled = !state.loading, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                }
                InlineError(state.error)
                Button(onClick = { keyboard?.hide(); val code = captchaCode; captchaCode = ""; onQueryRecipient(queryType, identifier, code) },
                    enabled = !state.loading && identifier.isNotBlank() && (state.preview || (state.captcha != null && captchaCode.isNotBlank())),
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.loading) "查询中…" else "查询") }
            }
        }
    }
}

@Composable
private fun CardStateDialog(state: CardServiceUiState, onDismiss: () -> Unit, onSelectCard: (CampusCard) -> Unit, onChangeCard: (String) -> Unit) {
    var password by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val card = state.selectedCard
    val initialLoading = state.loading && card == null
    OverlayDialog(show = state.visible || initialLoading,
        title = if (initialLoading) "读取校园卡" else if (card != null || state.action == CampusCardAction.Unlost) "确认" + state.operation.label else "挂失 / 解挂",
        summary = if (card != null && !state.loading && !state.needsPassword && !card.lost) "挂失后校园卡将暂停使用" else null,
        onDismissRequest = onDismiss, defaultWindowInsetsPadding = true, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (initialLoading) CardSelectionPlaceholder()
            if (state.cards.size > 1) state.cards.forEach { candidate ->
                Button(onClick = { onSelectCard(candidate) }, enabled = !state.loading && !candidate.frozen && candidate.status == 0,
                    modifier = Modifier.fillMaxWidth(), colors = if (card === candidate) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text(candidate.label + if (candidate.lost) " · 已挂失" else " · 未挂失")
                }
            }
            if (card != null && state.needsPassword) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextField(value = password,
                        onValueChange = { password = it.filter { c -> c in '0'..'9' }.take(6) }, label = "校园卡查询密码", singleLine = true,
                        enabled = !state.loading, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                    FieldError(state.error)
                }
            } else {
                if (!state.loading && card == null && state.error == null) Text(
                    if (state.cards.any { it.frozen || it.status != 0 }) "校园卡已冻结或状态异常" else "没有可操作的校园卡", fontSize = 14.sp)
                InlineError(state.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { keyboard?.hide(); val submitted = password; password = ""; onChangeCard(submitted) },
                    enabled = card != null && !state.loading && !state.preview && (!state.needsPassword || password.length == 6),
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.loading) "处理中…" else "确定") }
            }
        }
    }
}

@Composable
private fun CardSelectionPlaceholder() {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在读取校园卡" },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ContentPlaceholder(Modifier.size(40.dp), cornerRadius = 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .5f, lineHeight = 20.sp)
                    DashboardPlaceholder(11.sp, Modifier.fillMaxWidth(), fraction = .72f, lineHeight = 16.sp)
                }
            }
        }
    }
}
