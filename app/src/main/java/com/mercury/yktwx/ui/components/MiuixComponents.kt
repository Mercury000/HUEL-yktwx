package com.mercury.yktwx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Section header with custom SmallTitle margins (4dp above, 18h/8v inside),
// used by the About page.
@Composable
internal fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** One single-line error in the current form. Empty errors occupy no space. */
@Composable
internal fun InlineError(
    message: String?, modifier: Modifier = Modifier, maxLines: Int = 1,
) {
    if (message.isNullOrBlank()) return
    Row(modifier.fillMaxWidth()
        .background(MiuixTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(MiuixIcons.Info, null, Modifier.size(18.dp), tint = if (isSystemInDarkTheme())
            MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.error)
        Text(FeedbackText.inline(message), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = maxLines, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onErrorContainer)
    }
}
