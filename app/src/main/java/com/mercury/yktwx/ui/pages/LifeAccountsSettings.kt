package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ManagedLifeAccount(
    val provider: LifeProvider,
    val connection: LifeAccountConnection,
    val restoring: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val name: String get() = provider.displayName
    val services: String get() = provider.services
    val tag: String get() = provider.tag
    val status: String get() = if (restoring) "检查中" else when (connection.status) {
        LifeAccountStatus.NotLoggedIn -> "未登录"
        LifeAccountStatus.Checking -> "检查中"
        LifeAccountStatus.Verified -> "已登录"
        LifeAccountStatus.Unverified -> if (connection.error != null) "检查失败" else if (connection.hasAccount) "已登录" else "未登录"
        LifeAccountStatus.Expired -> "登录失效"
    }
}

@Composable
internal fun LifeAccountsSettingsCard(accounts: List<ManagedLifeAccount>, active: Boolean, onClick: (ManagedLifeAccount) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        accounts.forEach { account ->
            ArrowPreference(title = account.name,
                summary = account.connection.error?.takeIf { account.connection.status == LifeAccountStatus.Unverified }
                    ?: account.connection.phone.ifBlank { account.services },
                onClick = { if (active) onClick(account) }, enabled = !account.busy && (!account.restoring || account.connection.hasAccount),
                modifier = Modifier.testTag("${account.tag}-account-row"),
                endActions = { Text(account.status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
        }
    }
}

@Composable
internal fun LifeAccountLogoutDialog(account: ManagedLifeAccount?, active: Boolean, onDismiss: () -> Unit,
    onRemove: (LifeProvider) -> Unit) {
    val show = active && account != null
    val display = rememberWhileShown(show, account)
    val canConfirm = show && !account.busy
    OverlayDialog(show = show,
        title = "退出${display?.name.orEmpty()}账户",
        summary = display?.error ?: "退出后需重新登录。",
        onDismissRequest = onDismiss, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().testTag("life-account-logout-dialog"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss, enabled = show && !account.busy,
                modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { if (canConfirm) onRemove(account.provider) },
                enabled = canConfirm, modifier = Modifier.weight(1f).testTag("life-account-exit"),
                colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(if (display?.busy == true) "正在退出…" else "退出")
            }
        }
    }
}
