package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.R
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun AppSettingsContent(
    modifier: Modifier, contentPadding: PaddingValues, settings: ThemeSettings,
    updateState: AppUpdateUiState, onCheckUpdates: () -> Unit,
    onTheme: () -> Unit, accounts: List<ManagedLifeAccount>, active: Boolean, onLogout: () -> Unit,
    onCheckAccount: (LifeProvider) -> Unit, onLoginAccount: (LifeProvider) -> Unit,
    onRemoveAccount: (LifeProvider) -> Unit, scrollBehavior: ScrollBehavior? = null,
) {
    var selected by remember { mutableStateOf<LifeProvider?>(null) }
    val selectedAccount = accounts.firstOrNull { it.provider == selected }
    LaunchedEffect(active, selectedAccount?.connection?.hasAccount) {
        if (!active || selectedAccount?.connection?.hasAccount == false) selected = null
    }
    DashboardPullToRefresh(loading = accounts.any { it.connection.status == LifeAccountStatus.Checking },
        onRefresh = {
            if (active) accounts.filter { it.connection.hasAccount && !it.restoring && !it.busy &&
                it.connection.status != LifeAccountStatus.Expired }.forEach { onCheckAccount(it.provider) }
        }, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        scrollBehavior = scrollBehavior) {
    LazyColumn(modifier.testTag("settings-page-list"), contentPadding = PaddingValues(
        top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())) {
        item { SmallTitle("生活服务账户") }
        item {
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                LifeAccountsSettingsCard(accounts, active) { account ->
                    if (!account.connection.hasAccount || account.connection.status == LifeAccountStatus.Expired)
                        onLoginAccount(account.provider)
                    else selected = account.provider
                }
            }
        }
        item { SmallTitle("通用") }
        item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                ArrowPreference(title = stringResource(R.string.theme_title), onClick = onTheme)
                SwitchPreference(title = stringResource(R.string.payment_brightness),
                    summary = stringResource(R.string.payment_brightness_summary),
                    checked = settings.paymentBrightness.value, onCheckedChange = settings::setPaymentBrightness,
                    modifier = Modifier.testTag("payment-brightness-switch"))
                SwitchPreference(title = stringResource(R.string.payment_motion_detection),
                    summary = stringResource(R.string.payment_motion_detection_summary),
                    checked = settings.paymentMotionDetection.value, onCheckedChange = settings::setPaymentMotionDetection,
                    modifier = Modifier.testTag("payment-motion-detection-switch"))
                SwitchPreference(title = stringResource(R.string.prevent_screenshots),
                    summary = stringResource(R.string.prevent_screenshots_summary),
                    checked = settings.preventScreenshots.value, onCheckedChange = settings::setPreventScreenshots)
            }
        }
        item { SmallTitle("应用更新") }
        item { Box(Modifier.padding(horizontal = 16.dp)) {
            UpdateSettingsCard(updateState, settings.automaticUpdates.value, settings::setAutomaticUpdates, onCheckUpdates,
                settings.updateChannel.value, settings::setUpdateChannel)
        } }
        item {
            Box(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp)) {
                Card(Modifier.fillMaxWidth()) { ArrowPreference(title = "退出校园卡登录", onClick = onLogout) }
            }
        }
    }
    }
    LifeAccountLogoutDialog(selectedAccount, active, { selected = null }, onRemoveAccount)
}
