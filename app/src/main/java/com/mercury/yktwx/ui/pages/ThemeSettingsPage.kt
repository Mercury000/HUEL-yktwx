package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.R
import top.yukonga.miuix.kmp.basic.Card
import com.mercury.yktwx.ui.components.RestorableDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

private val NavTransitionStyleOptions = listOf("Miuix", "AOSP")

@Composable
fun ThemeSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: State<Int> = mutableStateOf(0),
    onThemeModeChange: (Int) -> Unit = {},
    accentMode: State<Int> = mutableStateOf(0),
    onAccentModeChange: (Int) -> Unit = {},
    floatingBottomBar: State<Boolean> = mutableStateOf(false),
    onFloatingBottomBarChange: (Boolean) -> Unit = {},
    blurBottomBar: State<Boolean> = mutableStateOf(false),
    onBlurBottomBarChange: (Boolean) -> Unit = {},
    floatingBottomBarStyle: State<Int> = mutableStateOf(0),
    onFloatingBottomBarStyleChange: (Int) -> Unit = {},
    blurTopBar: State<Boolean> = mutableStateOf(false),
    onBlurTopBarChange: (Boolean) -> Unit = {},
    glassStyle: State<Boolean> = mutableStateOf(false),
    onGlassStyleChange: (Boolean) -> Unit = {},
    swipeBack: State<Boolean> = mutableStateOf(false),
    onSwipeBackChange: (Boolean) -> Unit = {},
    navTransitionStyle: State<Int> = mutableStateOf(0),
    onNavTransitionStyleChange: (Int) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val themeOptions = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark),
    )
    val accentOptions = listOf(
        stringResource(R.string.color_default),
        stringResource(R.string.color_monet),
    )

    val floatingStyleOptions = listOf(
        stringResource(R.string.floating_bottom_bar_style_default),
        stringResource(R.string.floating_bottom_bar_style_ios),
        stringResource(R.string.floating_bottom_bar_style_glass),
    )

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("theme-page-list"),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
    ) {
        item {
            // Keep the existing empty space outside the card, while allowing a short
            // settings page to scroll beneath the header (the demo's About body does this too).
            Box {
                // fillParentMaxHeight excludes LazyColumn content padding; add it back
                // outside the sizing modifier so the body occupies the whole viewport.
                Spacer(Modifier.padding(bottom = contentPadding.calculateTopPadding() +
                    contentPadding.calculateBottomPadding() + 24.dp).fillParentMaxHeight())
                Card {
                    RestorableDropdownPreference(
                        title = stringResource(R.string.theme_title),
                        items = themeOptions,
                        selectedIndex = themeMode.value.coerceIn(themeOptions.indices),
                        onSelectedIndexChange = { onThemeModeChange(it) },
                    )
                    RestorableDropdownPreference(
                        title = stringResource(R.string.theme_color),
                        summary = stringResource(R.string.theme_color_summary),
                        items = accentOptions,
                        selectedIndex = accentMode.value.coerceIn(accentOptions.indices),
                        onSelectedIndexChange = { onAccentModeChange(it) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.floating_bottom_bar),
                        summary = stringResource(R.string.floating_bottom_bar_summary),
                        checked = floatingBottomBar.value,
                        onCheckedChange = { onFloatingBottomBarChange(it) },
                    )
                    if (floatingBottomBar.value) {
                        RestorableDropdownPreference(
                            title = stringResource(R.string.floating_bottom_bar_style),
                            items = floatingStyleOptions,
                            selectedIndex = floatingBottomBarStyle.value.coerceIn(floatingStyleOptions.indices),
                            onSelectedIndexChange = { onFloatingBottomBarStyleChange(it) },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.glass_style),
                        summary = stringResource(R.string.glass_style_summary),
                        checked = glassStyle.value,
                        onCheckedChange = onGlassStyleChange,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.blur_bottom_bar),
                        summary = stringResource(R.string.blur_bottom_bar_summary),
                        checked = blurBottomBar.value,
                        onCheckedChange = { onBlurBottomBarChange(it) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.blur_top_bar),
                        summary = stringResource(if (glassStyle.value) R.string.blur_glass_top_bar_summary
                            else R.string.blur_top_bar_summary),
                        checked = blurTopBar.value,
                        onCheckedChange = { onBlurTopBarChange(it) },
                    )
                    RestorableDropdownPreference(
                        title = stringResource(R.string.nav_transition_style),
                        items = NavTransitionStyleOptions,
                        selectedIndex = navTransitionStyle.value.coerceIn(NavTransitionStyleOptions.indices),
                        onSelectedIndexChange = onNavTransitionStyleChange,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.swipe_back),
                        summary = stringResource(R.string.swipe_back_summary),
                        checked = swipeBack.value,
                        onCheckedChange = { onSwipeBackChange(it) },
                    )
                }
            }
        }
    }
}
