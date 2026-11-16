package com.mercury.yktwx.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.glassPopupAnchorValue
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

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
    val glass = LocalGlassStyle.current
    val backdrop = LocalAppearanceBackdrop.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val mounted = remember { mutableStateOf(expanded) }
    val anchor = rememberGlassPopupAnchor()
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    val actualEnabled = enabled && items.isNotEmpty()
    LaunchedEffect(actualEnabled) { if (!actualEnabled) expanded = false }
    val haptic = LocalHapticFeedback.current
    val currentSelection by rememberUpdatedState(onSelectedIndexChange)
    val entry = remember(items, selectedIndex) {
        DropdownEntry(items.mapIndexed { index, text ->
            DropdownItem(text = text, selected = index == selectedIndex, onClick = { currentSelection(index) })
        })
    }
    val actionColor = if (actualEnabled) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    BasicComponent(
        modifier = if (glass) Modifier.onGloballyPositioned { rowBounds = it.boundsInWindow() } else Modifier,
        title = title,
        summary = summary,
        insideMargin = insideMargin,
        endActions = {
            Row(modifier = if (glass) Modifier.glassPopupAnchorValue(anchor) else Modifier,
                verticalAlignment = Alignment.CenterVertically) {
                items.getOrNull(selectedIndex)?.let { text ->
                    Text(text, Modifier.padding(end = 8.dp).weight(1f, fill = false),
                        fontSize = MiuixTheme.textStyles.body2.fontSize, color = actionColor, textAlign = TextAlign.End)
                }
                DropdownArrowEndAction(actionColor = actionColor)
            }
            if (!glass) OverlayDropdownPopup(entry = entry, show = expanded && actualEnabled,
                onDismiss = { expanded = false }, onDismissFinished = { mounted.value = false },
                maxHeight = maxHeight, dropdownColors = DropdownDefaults.dropdownColors(),
                renderInRootScaffold = true, collapseOnSelection = true)
        },
        role = Role.DropdownList,
        holdDownState = mounted.value,
        enabled = actualEnabled,
        onClick = {
            expanded = !expanded
            if (expanded) {
                mounted.value = true
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
    )
    if (glass && mounted.value) {
        // PopupLayout clears its own controller when disposed. Keep that transient
        // host state separate from the saved intent when switching appearance.
        val hostVisible = remember { mutableStateOf(true) }
        PopupLayout(visible = hostVisible, enableWindowDim = false, enableBackHandler = false,
            enterTransition = EnterTransition.None, exitTransition = ExitTransition.None,
            renderInRootScaffold = true) {
            Box(Modifier.fillMaxSize()) {
                GlassDropdownPopup(show = expanded && actualEnabled, onDismissRequest = { expanded = false },
                    anchorBounds = rowBounds, backdrop = backdrop, anchor = anchor,
                    sizing = GlassPopupSizing(maxHeight = maxHeight ?: 500.dp)) {
                    // The public popup removes its content when the exit finishes. Keep the
                    // Scaffold host alive until then, so its input shield cannot truncate the arc.
                    val currentExpanded by rememberUpdatedState(expanded)
                    DisposableEffect(Unit) {
                        onDispose { if (!currentExpanded) mounted.value = false }
                    }
                    items.forEachIndexed { index, text ->
                        GlassPopupItem(text = text, selected = selectedIndex == index, onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            expanded = false
                            currentSelection(index)
                        })
                    }
                }
            }
        }
    }
}
