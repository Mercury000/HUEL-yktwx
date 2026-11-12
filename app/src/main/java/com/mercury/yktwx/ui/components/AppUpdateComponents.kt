package com.mercury.yktwx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.R
import com.mercury.yktwx.data.UpdateDownloadPhase
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UpdateCheckPreference(state: AppUpdateUiState, onCheck: () -> Unit) {
    BasicComponent(title = stringResource(R.string.check_updates),
        enabled = !state.checking, onClick = onCheck, modifier = Modifier.testTag("check-updates"),
        endActions = {
            Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
                if (state.checking) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp)
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                    if (state.release != null) Box(Modifier.size(6.dp).background(MiuixTheme.colorScheme.error, CircleShape).testTag("update-red-dot"))
                    Text(BuildConfig.VERSION_NAME, modifier = Modifier.weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                }
            }
        })
}

@Composable
internal fun UpdateSettingsCard(state: AppUpdateUiState, reminders: Boolean,
    onRemindersChange: (Boolean) -> Unit, onCheck: () -> Unit,
    channel: String = "stable", onChannelChange: (String) -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        SwitchPreference(title = "接收测试版更新",
            summary = "同时接收正式版和测试版更新",
            checked = channel == "beta", onCheckedChange = { onChannelChange(if (it) "beta" else "stable") },
            modifier = Modifier.testTag("update-channel-switch"))
        SwitchPreference(title = stringResource(R.string.automatic_updates),
            summary = stringResource(R.string.automatic_updates_summary),
            checked = reminders, onCheckedChange = onRemindersChange,
            modifier = Modifier.testTag("automatic-updates-switch"))
        UpdateCheckPreference(state, onCheck)
    }
}

@Composable
internal fun AppUpdateDialog(state: AppUpdateUiState, onSkip: () -> Unit = {}, onDownload: () -> Unit = {}, onDismiss: () -> Unit) {
    val release = rememberWhileShown(state.visible, state.release)
    val scroll = rememberScrollState()
    var linkError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.visible, state.release?.versionCode, state.required) { if (state.visible) { scroll.scrollTo(0); linkError = null } }
    val error = state.download.error ?: linkError
    LaunchedEffect(state.visible, error, scroll.maxValue) {
        if (state.visible && error != null) scroll.animateScrollTo(scroll.maxValue)
    }
    OverlayDialog(show = state.visible, title = if (state.required) "需要更新后继续使用" else "更新详情", cornerRadius = 28.dp,
        onDismissRequest = { if (!state.required && !state.download.busy) onDismiss() }, renderInRootScaffold = true) {
        if (release != null) {
            val notes = remember(release.notes, release.versionName) { updateNotesForDisplay(release.notes, release.versionName) }
            Column(Modifier.fillMaxWidth().testTag("app-update-dialog")) {
                Column(Modifier.fillMaxWidth().heightIn(max = (LocalWindowInfo.current.containerDpSize.height * .44f)
                    .coerceIn(100.dp, 360.dp))
                    .verticalScroll(scroll).testTag("app-update-notes"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(release.versionName, modifier = Modifier.weight(1f, fill = false), fontSize = 25.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            lineHeight = 30.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(if (release.channel == "beta") "测试版" else "正式版", fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = .08f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                    updatePublishedTime(release.publishedAt)?.let { Text("发布时间：$it", fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                    if (state.required) Box(Modifier.fillMaxWidth()
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = .07f), RoundedCornerShape(12.dp))
                        .padding(12.dp)) {
                        Text("当前版本需要更新。\n请安装新版本后继续使用财小通。", fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, lineHeight = 20.sp)
                    }
                    if (notes.isNotEmpty()) UpdateMarkdown(notes, onLinkError = { linkError = it })
                    Box(Modifier.fillMaxWidth().heightIn(min = 36.dp)) { AnimatedInlineError(error) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    val compact = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f
                    if (!state.required && !compact) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Button(onClick = onSkip, modifier = Modifier.weight(.96f).heightIn(min = 48.dp),
                                enabled = !state.download.busy, insideMargin = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                                Text("跳过此版本", fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                            }
                            Button(onClick = onDismiss, modifier = Modifier.weight(.64f).heightIn(min = 48.dp),
                                enabled = !state.download.busy, insideMargin = PaddingValues(horizontal = 4.dp, vertical = 12.dp)) {
                                Text("忽略", fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.Medium)
                            }
                            UpdateDownloadButton(state, onDownload, Modifier.weight(1.42f), compact = true)
                        }
                    } else Column {
                        if (!state.required) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = onSkip, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.download.busy) { Text("跳过此版本") }
                            Button(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = !state.download.busy) { Text("忽略") }
                        }
                        UpdateDownloadButton(state, onDownload,
                            Modifier.fillMaxWidth().padding(top = if (state.required) 0.dp else 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadButton(state: AppUpdateUiState, onDownload: () -> Unit,
    modifier: Modifier, compact: Boolean = false) {
    val primaryColors = ButtonDefaults.buttonColorsPrimary()
    Button(onClick = onDownload, modifier = modifier.heightIn(min = 48.dp).testTag("update-download"),
        enabled = state.visible && !state.download.busy, colors = if (state.download.busy)
            primaryColors.copy(disabledColor = primaryColors.color, disabledContentColor = primaryColors.contentColor)
            else primaryColors,
        insideMargin = PaddingValues(horizontal = if (compact) 4.dp else 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.download.busy) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp,
                progress = state.download.percent?.let { it / 100f },
                colors = ProgressIndicatorDefaults.progressIndicatorColors(foregroundColor = primaryColors.contentColor,
                    backgroundColor = primaryColors.contentColor.copy(alpha = .25f)))
            Text(when (state.download.phase) {
                UpdateDownloadPhase.Idle -> "下载安装"
                UpdateDownloadPhase.Downloading -> state.download.percent?.let { "下载中 $it%" } ?: "下载中"
                UpdateDownloadPhase.Verifying -> "正在校验"
                UpdateDownloadPhase.Ready, UpdateDownloadPhase.Installing -> "安装更新"
                UpdateDownloadPhase.NeedsAuthorization -> "允许安装"
                UpdateDownloadPhase.Failed -> if (state.download.retryInstallation) "重试安装" else "重试下载"
            }, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1, fontWeight = FontWeight.Medium)
        }
    }
}
