package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.CardPasswordUiState
import com.mercury.yktwx.data.CampusCard
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun CardPasswordDialog(state: CardPasswordUiState, onDismiss: () -> Unit, onSelectCard: (CampusCard) -> Unit,
    onSubmit: (String, String, String) -> Unit) {
    // Never rememberSaveable: no password in saved-state Bundles or after reopening.
    var oldPassword by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    var newPassword by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    var confirmation by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = state.selectedCard != null && listOf(oldPassword, newPassword, confirmation).all { it.length == 6 } &&
        newPassword == confirmation && !state.loading && !state.preview
    fun submit() {
        if (!valid) return
        val old = oldPassword; val new = newPassword; val confirmed = confirmation
        oldPassword = ""; newPassword = ""; confirmation = ""
        keyboard?.hide()
        onSubmit(old, new, confirmed)
    }
    fun dismiss() {
        if (state.submitting) return
        oldPassword = ""; newPassword = ""; confirmation = ""
        keyboard?.hide(); onDismiss()
    }
    OverlayDialog(show = state.visible, title = "修改查询密码",
        onDismissRequest = ::dismiss, defaultWindowInsetsPadding = true, renderInRootScaffold = true) {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.cards.size > 1) state.cards.forEach { card ->
                Button(onClick = { onSelectCard(card) }, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth(), colors = if (state.selectedCard === card) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors()) { Text(card.label) }
            }
            TextField(value = oldPassword, onValueChange = { oldPassword = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "原查询密码", singleLine = true, enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            TextField(value = newPassword, onValueChange = { newPassword = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "新查询密码（6 位数字）", singleLine = true, enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth())
            TextField(value = confirmation, onValueChange = { confirmation = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "确认新查询密码", singleLine = true, enabled = !state.loading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth())
            if (confirmation.length == 6 && newPassword.length == 6 && confirmation != newPassword)
                Text("两次输入的新密码不一致", fontSize = 13.sp, color = MiuixTheme.colorScheme.error)
            state.error?.let { Text("修改失败：$it", fontSize = 13.sp, color = MiuixTheme.colorScheme.error) }
            if (state.preview) Text("预览模式不会修改密码", fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::dismiss, enabled = !state.submitting, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = ::submit, enabled = valid, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (state.submitting) "修改中…" else "确认修改") }
            }
        }
    }
}
