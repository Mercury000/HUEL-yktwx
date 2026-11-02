package com.mercury.yktwx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.AppUpdateUiState
import com.mercury.yktwx.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A passive, scrollable update hint. The small close glyph has its own full touch target. */
@Composable
internal fun InlineUpdateNotice(state: AppUpdateUiState, onOpen: () -> Unit, onDismiss: () -> Unit,
    topSpacing: Dp = 0.dp, bottomSpacing: Dp = 12.dp) {
    val release = rememberWhileShown(state.noticeVisible, state.release)
    FeedbackTransition(state.noticeVisible && release != null, topSpacing = topSpacing, bottomSpacing = bottomSpacing) {
        val foreground = MiuixTheme.colorScheme.onSurfaceVariantSummary
        val accent = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.onSecondaryVariant else MiuixTheme.colorScheme.primary
        val background = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.secondaryVariant
            else MiuixTheme.colorScheme.primary.copy(alpha = .06f)
        Row(Modifier.fillMaxWidth().background(background, RoundedCornerShape(12.dp)),
            verticalAlignment = Alignment.CenterVertically) {
            ActionSurface(onClick = onOpen, enabled = state.noticeVisible,
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                modifier = Modifier.weight(1f).testTag("app-update-notice"), feedbackColor = accent) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(MiuixIcons.Refresh, null, Modifier.size(18.dp), tint = accent)
                    Text(stringResource(R.string.update_found, release?.versionName.orEmpty()),
                        Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = foreground)
                }
            }
            ActionSurface(onClick = onDismiss, enabled = state.noticeVisible,
                modifier = Modifier.size(44.dp).testTag("dismiss-update-notice"),
                shape = RoundedCornerShape(12.dp), feedbackColor = foreground) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Close, stringResource(R.string.update_notice_dismiss), Modifier.size(14.dp),
                        tint = foreground.copy(alpha = .65f))
                }
            }
        }
    }
}
