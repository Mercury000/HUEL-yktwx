package com.mercury.yktwx.ui.pages

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.mercury.yktwx.data.FormField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.ui.components.ActionSurface
import com.mercury.yktwx.ui.components.InlineErrorGroup
import com.mercury.yktwx.ui.components.trackInputFocus
import com.mercury.yktwx.ui.components.UsageNoticeSheet
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.UsageConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Authentication is a separate screen, not one page of the main tab pager. */
@Composable
fun LoginPage(state: PaymentUiState, onLogin: (String, String) -> Unit) {
    LoginPage(state, null, onLogin = onLogin)
}

@Composable
fun LoginPage(state: PaymentUiState, consent: UsageConsent?, onInputEdited: (FormField) -> Unit = {},
    updateNotice: @Composable () -> Unit = {}, updateDialog: @Composable () -> Unit = {},
    onLogin: (String, String) -> Unit) {
    var noticeVisible by rememberSaveable(UsageNotice.hash) { mutableStateOf(false) }
    var hintShown by rememberSaveable(UsageNotice.hash) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var consentError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    LaunchedEffect(consent) {
        if (!hintShown) consent?.confirmationHint?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            hintShown = true
        }
    }
    fun readNotice() { focus.clearFocus(); keyboard?.hide(); consentError = null; noticeVisible = true }
    fun acceptNotice() {
        val current = consent ?: return
        if (saving) return
        saving = true; consentError = null
        scope.launch {
            try {
                current.accept()
                noticeVisible = false
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { consentError = "无法保存确认记录，请重试" }
            finally { saving = false }
        }
    }
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
                LoginForm(state, onLogin, consent?.accepted != false, onInputEdited,
                    updateNotice = updateNotice,
                    consentRow = {
                        if (consent != null) ConsentRow(consent.accepted, enabled = !state.busy && !saving,
                            onToggle = { if (consent.accepted) consent.revoke() else readNotice() }, onRead = ::readNotice)
                    })
                if (consent == null) Text(
                    "登录信息仅保存在本机，并使用 Android Keystore 加密。",
                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (consent != null) UsageNoticeSheet(noticeVisible, onDismiss = { if (!saving) noticeVisible = false },
            onAgree = if (consent.accepted) null else ::acceptNotice, saving = saving, error = consentError)
        updateDialog()
    }
}

@Composable
private fun ConsentRow(accepted: Boolean, enabled: Boolean, onToggle: () -> Unit, onRead: () -> Unit) {
    val linkColor = MiuixTheme.colorScheme.primary
    val text = remember(linkColor, enabled, onRead) { buildAnnotatedString {
        append("我已阅读并同意")
        withLink(LinkAnnotation.Clickable("usage-notice", TextLinkStyles(SpanStyle(color = linkColor)),
            linkInteractionListener = { if (enabled) onRead() })) { append("《${UsageNotice.Title}》") }
    } }
    ActionSurface(onClick = onToggle, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("usage-consent-row")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(if (accepted) ToggleableState.On else ToggleableState.Off, onClick = onToggle,
                enabled = enabled, modifier = Modifier.testTag("usage-consent-checkbox"))
            Text(text, Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp), fontSize = 13.sp,
                lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun LoginForm(state: PaymentUiState, onLogin: (String, String) -> Unit, agreed: Boolean, onInputEdited: (FormField) -> Unit,
    updateNotice: @Composable () -> Unit,
    consentRow: (@Composable () -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf(state.username) }
    // Never rememberSaveable the password: no plaintext in SavedState/Bundle.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val autofill = LocalAutofillManager.current
    LaunchedEffect(state.needsLogin) {
        if (!state.needsLogin) { autofill?.commit(); password = ""; visible = false }
    }
    fun submit() {
        if (agreed && !state.busy && !state.restoringLogin && username.isNotBlank() && password.isNotBlank()) {
            focus.clearFocus()
            keyboard?.hide()
            visible = false
            onLogin(username, password)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("登录校园一卡通", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("河南财经政法大学", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Column(Modifier.fillMaxWidth()) {
            updateNotice()
            TextField(value = username, onValueChange = {
                val next = it.filter { c -> c in '0'..'9' }.take(24)
                if (next != username) { username = next; onInputEdited(FormField.Username) }
            }, label = "学号 / 工号", singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth().trackInputFocus(!state.busy).semantics { contentType = ContentType.Username })
        }
        TextField(value = password, onValueChange = { if (it != password) { password = it; onInputEdited(FormField.Password) } }, label = "密码", singleLine = true, enabled = !state.busy,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                    Icon(if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                        contentDescription = if (visible) "隐藏密码" else "显示密码",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }, modifier = Modifier.fillMaxWidth().trackInputFocus(!state.busy).semantics { contentType = ContentType.Password })
        InlineErrorGroup(state.error, 18.dp) {
            consentRow?.invoke()
            Button(onClick = { submit() }, enabled = agreed && !state.busy && !state.restoringLogin && username.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (state.busy) "正在登录…" else "登录")
            }
        }
    }
}
