package com.mercury.yktwx.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UpdateCheckPreference(state: AppUpdateUiState, onCheck: () -> Unit) {
    BasicComponent(title = stringResource(R.string.check_updates),
        enabled = !state.checking, onClick = onCheck, modifier = Modifier.testTag("check-updates"),
        endActions = {
            if (state.checking) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
            else Text(BuildConfig.VERSION_NAME, fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        })
}

@Composable
internal fun UpdateSettingsCard(state: AppUpdateUiState, automatic: Boolean,
    onAutomaticChange: (Boolean) -> Unit, onCheck: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        SwitchPreference(title = stringResource(R.string.automatic_updates),
            summary = stringResource(R.string.automatic_updates_summary),
            checked = automatic, onCheckedChange = onAutomaticChange,
            modifier = Modifier.testTag("automatic-updates-switch"))
        UpdateCheckPreference(state, onCheck)
    }
}

@Composable
internal fun AppUpdateDialog(state: AppUpdateUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val release = rememberWhileShown(state.visible, state.release)
    val notes = release?.notes.orEmpty()
    val scroll = rememberScrollState()
    var openError by remember { mutableStateOf(false) }
    LaunchedEffect(state.visible, state.release?.versionCode) {
        if (state.visible) { scroll.scrollTo(0); openError = false }
    }
    OverlayDialog(show = state.visible, title = stringResource(R.string.update_found, release?.versionName.orEmpty()),
        onDismissRequest = onDismiss, renderInRootScaffold = true) {
        if (release != null) {
            Column(Modifier.fillMaxWidth().testTag("app-update-dialog")) {
                if (notes.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(scroll).padding(top = 12.dp)
                        .testTag("app-update-notes")) {
                        Text(notes, fontSize = 14.sp, lineHeight = 22.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (openError) Text(stringResource(R.string.link_open_failed), Modifier.padding(top = 12.dp),
                    color = MiuixTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = state.visible) {
                        Text(stringResource(R.string.update_later))
                    }
                    Button(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadPageUrl)))
                            onDismiss()
                        } catch (_: ActivityNotFoundException) { openError = true }
                    }, modifier = Modifier.weight(1f), enabled = state.visible, colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(stringResource(R.string.update_download))
                    }
                }
            }
        }
    }
}
