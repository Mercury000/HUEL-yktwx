package com.mercury.yktwx.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun miuixNavigationTransition(style: Int): NavTransition =
    if (style == 1) CrossActivityTransition else NavTransitions.MiuixDefault

/** Match the demo's effects for its Miuix and AOSP styles. */
@Composable
internal fun rememberMiuixNavigationEffects(wideScreen: Boolean = false, transitionStyle: Int = 0): NavDisplayEffects {
    val cornerRadius = if (wideScreen) 0.dp else rememberNavSystemCornerRadius()
    val backdropColor = MiuixTheme.colorScheme.surface
    val isAosp = transitionStyle == 1
    val dark = isSystemInDarkTheme()
    return remember(cornerRadius, backdropColor, isAosp, dark) {
        NavDisplayEffects(
            cornerClipRadius = if (isAosp && cornerRadius <= 0.dp) 32.dp else cornerRadius,
            cornerClipMode = if (isAosp) NavCornerClipMode.All else NavCornerClipMode.Leading,
            dimAmount = if (isAosp) { if (dark) 0.8f else 0.2f } else 0.5f,
            backdropColor = backdropColor,
        )
    }
}
