package com.mercury.yktwx.ui.pages

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.CardServiceUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
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
    onContinueRecharge: () -> Unit,
) {
    if (state.action != CampusCardAction.OthersRecharge) {
        CardStateDialog(state, onDismiss, onSelectCard, onChangeCard)
        return
    }
    // Input is local, not SavedState. Preserve recipient ID on back, never preserve captcha codes.
    var queryType by remember(state.visible) { mutableStateOf(RecipientQueryType.StudentNumber) }
    var identifier by remember(state.visible) { mutableStateOf("") }
    var captchaCode by remember(state.visible, state.captcha) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val confirming = state.recipients.isNotEmpty()
    fun previous() { captchaCode = ""; onRefreshCaptcha() }
    OverlayBottomSheet(show = state.visible, title = "代人充值", onDismissRequest = onDismiss, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (confirming) {
                state.recipients.forEach { recipient ->
                    val selected = state.selectedRecipient === recipient
                    val contentColor = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                    Surface(onClick = { if (!state.loading) onSelectRecipient(recipient) },
                        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
                        contentColor = contentColor,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(recipient.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = contentColor, modifier = Modifier.weight(1f))
                                if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
                            }
                            Text(recipient.sno.ifBlank { recipient.cardAccount }, fontSize = 14.sp, color = contentColor)
                            Text(recipient.accountName + " · " + recipient.targetId, fontSize = 13.sp, color = if (selected) contentColor else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                state.error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = ::previous, enabled = !state.loading, modifier = Modifier.weight(1f)) { Text("上一步") }
                    Button(onClick = { keyboard?.hide(); onContinueRecharge() }, enabled = !state.loading && state.selectedRecipient != null,
                        modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.loading) "读取中…" else "下一步") }
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
                state.error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
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
    OverlayDialog(show = state.visible,
        title = if (card != null) "确认" + state.operation.label else "挂失 / 解挂",
        summary = if (card != null && !state.loading && !state.needsPassword && !card.lost) "挂失后校园卡将暂停使用" else null,
        onDismissRequest = onDismiss, defaultWindowInsetsPadding = true, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.loading && card == null) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            if (state.cards.size > 1) state.cards.forEach { candidate ->
                Button(onClick = { onSelectCard(candidate) }, enabled = !state.loading && !candidate.frozen && candidate.status == 0,
                    modifier = Modifier.fillMaxWidth(), colors = if (card === candidate) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
                    Text(candidate.label + if (candidate.lost) " · 已挂失" else " · 未挂失")
                }
            }
            if (card != null && state.needsPassword) TextField(value = password,
                onValueChange = { password = it.filter { c -> c in '0'..'9' }.take(6) }, label = "校园卡查询密码", singleLine = true,
                enabled = !state.loading, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            if (!state.loading && card == null && state.error == null) Text(
                if (state.cards.any { it.frozen || it.status != 0 }) "校园卡已冻结或状态异常" else "没有可操作的校园卡", fontSize = 14.sp)
            state.error?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { keyboard?.hide(); val submitted = password; password = ""; onChangeCard(submitted) },
                    enabled = card != null && !state.loading && !state.preview && (!state.needsPassword || password.length == 6),
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.loading) "处理中…" else "确定") }
            }
        }
    }
}
