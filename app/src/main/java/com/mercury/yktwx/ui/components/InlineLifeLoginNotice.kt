package com.mercury.yktwx.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun InlineLifeLoginNotice(visible: Boolean, onLogin: () -> Unit, enabled: Boolean = true,
    text: String = "登录胖乖生活，扫码接水或洗衣", tag: String = "life-login-notice") {
    FeedbackTransition(visible, bottomSpacing = 12.dp) {
        val foreground = MiuixTheme.colorScheme.onSurfaceVariantSummary
        val accent = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.onSecondaryVariant else MiuixTheme.colorScheme.primary
        val background = if (isSystemInDarkTheme()) MiuixTheme.colorScheme.secondaryVariant
            else MiuixTheme.colorScheme.primary.copy(alpha = .06f)
        ActionSurface(onClick = onLogin, enabled = visible && enabled, color = background,
            shape = RoundedCornerShape(12.dp), feedbackColor = accent,
            modifier = Modifier.fillMaxWidth().testTag(tag)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(AppIcons.Scan, null, Modifier.size(18.dp), tint = accent)
                Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = foreground)
                Icon(MiuixIcons.ChevronForward, null, Modifier.size(14.dp), tint = foreground)
            }
        }
    }
}
