package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** The same full-width location field in fee pages and hall sheets. */
@Composable
internal fun FeeLocationDropdown(
    title: String, items: List<String>, selectedIndex: Int,
    enabled: Boolean, onSelectedIndexChange: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 14.dp,
        colors = CardDefaults.defaultColors(color = DashboardPalette.field)) {
        OverlayDropdownPreference(
            title = title, items = listOf("请选择") + items, selectedIndex = selectedIndex,
            enabled = enabled && items.isNotEmpty(), renderInRootScaffold = true,
            maxHeight = 320.dp,
            onSelectedIndexChange = { if (it > 0) onSelectedIndexChange(it - 1) },
        )
    }
}
