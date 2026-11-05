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
    val activeServices: Int = 0,
    val error: String? = null,
) {
    val name: String get() = if (provider == LifeProvider.Hui798) "慧生活798" else "胖乖生活"
    val services: String get() = if (provider == LifeProvider.Hui798) "生活用水" else "接水 · 洗衣"
    val tag: String get() = if (provider == LifeProvider.Hui798) "hui798" else "panggui"
    val status: String get() = if (restoring) "检查中" else when (connection.status) {
        LifeAccountStatus.NotLoggedIn -> "未登录"
        LifeAccountStatus.Checking -> "检查中"
        LifeAccountStatus.Verified -> "已登录"
        LifeAccountStatus.Unverified -> "待验证"
        LifeAccountStatus.Expired -> "登录失效"
    }
}

@Composable
internal fun LifeAccountsSettingsCard(accounts: List<ManagedLifeAccount>, active: Boolean, onClick: (ManagedLifeAccount) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        accounts.forEach { account ->
            ArrowPreference(title = account.name,
                summary = account.connection.phone.ifBlank { account.services },
                onClick = { if (active) onClick(account) }, enabled = !account.restoring && !account.busy,
                modifier = Modifier.testTag("${account.tag}-account-row"),
                endActions = { Text(account.status, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
        }
    }
}

@Composable
internal fun LifeAccountLogoutDialog(account: ManagedLifeAccount?, active: Boolean, onDismiss: () -> Unit,
    onRemove: (LifeProvider) -> Unit, onProgress: (LifeProvider) -> Unit) {
    val show = active && account != null
    val display = rememberWhileShown(show, account)
    val hasActiveServices = (display?.activeServices ?: 0) > 0
    val canConfirm = show && !account.busy && !account.restoring
    OverlayDialog(show = show,
        title = if (hasActiveServices) "暂时无法退出" else "退出${display?.name.orEmpty()}账户",
        summary = if (hasActiveServices) "服务结束后才能退出账户。" else display?.error ?: "退出后需重新登录。",
        onDismissRequest = onDismiss, renderInRootScaffold = true) {
        Row(Modifier.fillMaxWidth().testTag("life-account-logout-dialog"),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onDismiss, enabled = show && !account.busy,
                modifier = Modifier.weight(1f)) { Text("取消") }
            if (hasActiveServices) {
                Button(onClick = { if (canConfirm) { onDismiss(); onProgress(account.provider) } },
                    enabled = canConfirm, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()) { Text("查看进度") }
            } else {
                Button(onClick = { if (canConfirm) onRemove(account.provider) },
                    enabled = canConfirm, modifier = Modifier.weight(1f).testTag("life-account-exit"),
                    colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(if (display?.busy == true) "正在退出…" else "退出")
                }
            }
        }
    }
}
