package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The same full-width location field in fee pages and hall sheets. */
@Composable
internal fun FeeLocationDropdown(
    title: String, items: List<String>, selectedIndex: Int,
    enabled: Boolean, onSelectedIndexChange: (Int) -> Unit,
) {
    val choices = remember(items) { listOf("请选择") + items }
    val currentOnSelectedIndexChange by rememberUpdatedState(onSelectedIndexChange)
    // Miuix keys its option objects on this callback; keep it stable as the fee state changes.
    val select: (Int) -> Unit = remember {
        { index -> if (index > 0) currentOnSelectedIndexChange(index - 1) }
    }
    FeeLocationField {
        OverlayDropdownPreference(
            title = title, items = choices, selectedIndex = selectedIndex,
            enabled = enabled && items.isNotEmpty(), renderInRootScaffold = true,
            maxHeight = 320.dp,
            onSelectedIndexChange = select,
        )
    }
}

/** Match the dropdown's text metrics without creating popup state and option callbacks. */
@Composable
internal fun FeeLocationDropdownPlaceholder() {
    FeeLocationField {
        BasicComponent(title = "", enabled = false, endActions = {
            Text("请选择", Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).weight(1f, fill = false),
                fontSize = MiuixTheme.textStyles.body2.fontSize, textAlign = TextAlign.End)
            DropdownArrowEndAction(actionColor = MiuixTheme.colorScheme.disabledOnSecondaryVariant)
        })
    }
}

@Composable
private fun FeeLocationField(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = DashboardPalette.field)) { content() }
}
