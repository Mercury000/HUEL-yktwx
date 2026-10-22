package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Miuix sheets avoid the IME but do not reserve navigation-bar space for their content.
 * Union and inset consumption keep the larger bottom inset, rather than stacking IME + nav.
 */
@Composable
internal fun Modifier.bottomSheetSafeArea(
    insets: WindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime),
): Modifier = windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
