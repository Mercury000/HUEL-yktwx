package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.LocalContentColor

/** Shared by recipient, dorm meter and payment-method selection. */
@Composable
internal fun PaymentSelectionCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateSelection: Boolean = false,
    neutral: Boolean = false,
    content: @Composable ColumnScope.(Color) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val targetContentColor = when {
        neutral && !enabled -> MiuixTheme.colorScheme.disabledOnSurface
        neutral -> MiuixTheme.colorScheme.onSurface
        dark && !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        selected -> MiuixTheme.colorScheme.onPrimary
        dark -> MiuixTheme.colorScheme.onSecondaryVariant
        else -> MiuixTheme.colorScheme.onSurface
    }
    val targetColor = when {
        neutral && selected -> DashboardPalette.iconPanel
        neutral -> DashboardPalette.card
        dark && !enabled -> MiuixTheme.colorScheme.disabledSecondaryVariant
        selected -> MiuixTheme.colorScheme.primary
        dark -> MiuixTheme.colorScheme.secondaryVariant
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val color = if (animateSelection) animateColorAsState(targetColor, tween(ContentMotion.Number), label = "selection-fill").value else targetColor
    val contentColor = if (animateSelection) animateColorAsState(targetContentColor, tween(ContentMotion.Number), label = "selection-content").value else targetContentColor
    if (neutral) {
        Card(modifier = modifier.fillMaxWidth().semantics {
            this.selected = selected
            role = Role.RadioButton
            if (!enabled) disabled()
        }, cornerRadius = 18.dp, colors = CardDefaults.defaultColors(color = color),
            onClick = onClick.takeIf { enabled }) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected, onClick = null, enabled = enabled)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { content(contentColor) }
                }
            }
        }
        return
    }
    ActionSurface(
        onClick = onClick, enabled = enabled,
        color = color,
        contentColor = contentColor,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().semantics {
            this.selected = selected
            role = Role.RadioButton
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content(contentColor)
        }
    }
}
