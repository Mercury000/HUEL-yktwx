package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared by recipient, dorm meter and payment-method selection. */
@Composable
internal fun PaymentSelectionCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateSelection: Boolean = false,
    content: @Composable ColumnScope.(Color) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val targetContentColor = when {
        dark && !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        selected -> MiuixTheme.colorScheme.onPrimary
        dark -> MiuixTheme.colorScheme.onSecondaryVariant
        else -> MiuixTheme.colorScheme.onSurface
    }
    val targetColor = when {
        dark && !enabled -> MiuixTheme.colorScheme.disabledSecondaryVariant
        selected -> MiuixTheme.colorScheme.primary
        dark -> MiuixTheme.colorScheme.secondaryVariant
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
    }
    val color = if (animateSelection) animateColorAsState(targetColor, tween(ContentMotion.Number), label = "selection-fill").value else targetColor
    val contentColor = if (animateSelection) animateColorAsState(targetContentColor, tween(ContentMotion.Number), label = "selection-content").value else targetContentColor
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
