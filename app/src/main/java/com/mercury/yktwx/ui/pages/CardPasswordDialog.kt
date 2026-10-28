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
import com.mercury.yktwx.preview.PreviewNotes
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.CardPasswordUiState
import com.mercury.yktwx.data.CampusCard
import com.mercury.yktwx.ui.components.trackInputFocus
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.rememberWhileShown
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun CardPasswordDialog(state: CardPasswordUiState, onDismiss: () -> Unit, onSelectCard: (CampusCard) -> Unit,
    onSubmit: (String, String, String) -> Unit) {
    // Never rememberSaveable: no password in saved-state Bundles or after reopening.
    var oldPassword by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    var newPassword by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    var confirmation by remember(state.visible, state.selectedCard) { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val valid = state.visible && state.selectedCard != null && listOf(oldPassword, newPassword, confirmation).all { it.length == 6 } &&
        newPassword == confirmation && !state.loading && !state.preview
    fun submit() {
        if (!valid) return
        val old = oldPassword; val new = newPassword; val confirmed = confirmation
        oldPassword = ""; newPassword = ""; confirmation = ""
        keyboard?.hide()
        onSubmit(old, new, confirmed)
    }
    fun dismiss() {
        if (!state.visible || state.submitting) return
        oldPassword = ""; newPassword = ""; confirmation = ""
        keyboard?.hide(); onDismiss()
    }
    OverlayDialog(show = state.visible, title = "修改查询密码",
        onDismissRequest = ::dismiss, defaultWindowInsetsPadding = true, renderInRootScaffold = true) {
        val display = rememberWhileShown(state.visible, state)
        val enabled = state.visible && !display.loading
        val error = rememberWhileShown(state.visible,
            if (confirmation.length == 6 && newPassword.length == 6 && confirmation != newPassword)
                "两次输入的新密码不一致" else display.error)
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (display.cards.size > 1) display.cards.forEach { card ->
                Button(onClick = { onSelectCard(card) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), colors = if (display.selectedCard === card) ButtonDefaults.buttonColorsPrimary()
                        else ButtonDefaults.buttonColors()) { Text(card.label) }
            }
            TextField(value = oldPassword, onValueChange = { oldPassword = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "原查询密码", singleLine = true, enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                modifier = Modifier.trackInputFocus().fillMaxWidth())
            TextField(value = newPassword, onValueChange = { newPassword = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "新查询密码（6 位数字）", singleLine = true, enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                modifier = Modifier.trackInputFocus().fillMaxWidth())
            TextField(value = confirmation, onValueChange = { confirmation = it.filter { c -> c in '0'..'9' }.take(6) },
                label = "确认新查询密码", singleLine = true, enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.trackInputFocus().fillMaxWidth())
            InlineError(error)
            if (UiPreview.enabled && display.preview) PreviewNotes.CardPassword()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::dismiss, enabled = state.visible && !display.submitting, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = ::submit, enabled = valid, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text(if (display.submitting) "修改中…" else "确认修改") }
            }
        }
    }
}
