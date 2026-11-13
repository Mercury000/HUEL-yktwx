package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Uses the existing preference layout with a saveable display intent. */
@Composable
internal fun RestorableDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    maxHeight: Dp? = null,
    insideMargin: PaddingValues = BasicComponentDefaults.InsideMargin,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf(expanded) }
    val haptic = LocalHapticFeedback.current
    val currentSelection by rememberUpdatedState(onSelectedIndexChange)
    val entry = remember(items, selectedIndex) {
        DropdownEntry(items.mapIndexed { index, text ->
            DropdownItem(text = text, selected = index == selectedIndex, onClick = { currentSelection(index) })
        })
    }
    val actionColor = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    BasicComponent(
        title = title,
        summary = summary,
        insideMargin = insideMargin,
        endActions = {
            items.getOrNull(selectedIndex)?.let { text ->
                Text(text, Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).weight(1f, fill = false),
                    fontSize = MiuixTheme.textStyles.body2.fontSize, color = actionColor, textAlign = TextAlign.End)
            }
            DropdownArrowEndAction(actionColor = actionColor)
            OverlayDropdownPopup(entry = entry, show = expanded && enabled && items.isNotEmpty(),
                onDismiss = { expanded = false }, onDismissFinished = { held = false },
                maxHeight = maxHeight, dropdownColors = DropdownDefaults.dropdownColors(),
                renderInRootScaffold = true, collapseOnSelection = true)
        },
        role = Role.DropdownList,
        holdDownState = held,
        enabled = enabled && items.isNotEmpty(),
        onClick = {
            expanded = !expanded
            if (expanded) {
                held = true
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
    )
}
