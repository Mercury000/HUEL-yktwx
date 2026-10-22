package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
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

/** Shared dialog/page error banner. Keep one instance directly above the primary action. */
@Composable
internal fun InlineError(
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(MiuixTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                .padding(start = 12.dp, end = if (actionLabel == null) 12.dp else 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                MiuixIcons.Info, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MiuixTheme.colorScheme.error,
            )
            Text(
                message.orEmpty(), modifier = Modifier.weight(1f),
                fontSize = 13.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onErrorContainer,
            )
            if (actionLabel != null) TextButton(actionLabel, onClick = onAction)
        }
    }
}

/** Shared validation text directly below the field it belongs to. */
@Composable
internal fun FieldError(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = !message.isNullOrBlank(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            message.orEmpty(), modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            fontSize = 12.sp, lineHeight = 16.sp, color = MiuixTheme.colorScheme.error,
        )
    }
}
