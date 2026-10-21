package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.PaymentUiState
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Authentication is a separate screen, not one page of the main tab pager. */
@Composable
fun LoginPage(state: PaymentUiState, onLogin: (String, String) -> Unit) {
    Scaffold { padding ->
        Box(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                .padding(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                LoginForm(state, onLogin)
                Text(
                    "登录信息仅保存在本机，并使用 Android Keystore 加密。",
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun LoginForm(state: PaymentUiState, onLogin: (String, String) -> Unit) {
    var username by remember { mutableStateOf(state.username) }
    // Never rememberSaveable the password: no plaintext in SavedState/Bundle.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (!state.busy && username.isNotBlank() && password.isNotBlank()) {
            keyboard?.hide()
            val submittedPassword = password
            password = ""
            visible = false
            onLogin(username, submittedPassword)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("登录校园一卡通", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("河南财经政法大学", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        TextField(value = username, onValueChange = { username = it.filter { c -> c in '0'..'9' }.take(24) }, label = "学号 / 工号", singleLine = true, enabled = !state.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
        TextField(value = password, onValueChange = { password = it }, label = "密码", singleLine = true, enabled = !state.busy,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, modifier = Modifier.size(48.dp)) {
                    Icon(if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { submit() }, enabled = !state.busy && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(if (state.busy) "正在登录…" else "登录")
        }
    }
}
