package com.mercury.yktwx.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The Miuix demo's default effects; pane transitions have no physical screen corner. */
@Composable
internal fun rememberMiuixNavigationEffects(wideScreen: Boolean = false): NavDisplayEffects {
    val cornerRadius = if (wideScreen) 0.dp else rememberNavSystemCornerRadius()
    val backdropColor = MiuixTheme.colorScheme.surface
    return remember(cornerRadius, backdropColor) {
        NavDisplayEffects(cornerClipRadius = cornerRadius, backdropColor = backdropColor)
    }
}
