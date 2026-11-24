package com.mercury.yktwx.ui.pages

import androidx.compose.animation.AnimatedVisibility
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

@Composable
fun ThemeSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    themeMode: State<Int> = mutableStateOf(0),
    accentMode: State<Int> = mutableStateOf(0),
    onThemeSelectionChange: (Int) -> Unit = {},
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
        stringResource(R.string.theme_monet_system),
        stringResource(R.string.theme_monet_light),
        stringResource(R.string.theme_monet_dark),
    )

    val floatingStyleOptions = listOf(
        stringResource(R.string.floating_bottom_bar_style_default),
        stringResource(R.string.floating_bottom_bar_style_ios),
        stringResource(R.string.floating_bottom_bar_style_glass),
    )

    val navTransitionStyleOptions = listOf(
        stringResource(R.string.nav_transition_miuix),
        stringResource(R.string.nav_transition_aosp),
        stringResource(R.string.nav_transition_expansion),
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
                        title = stringResource(R.string.theme_mode_title),
                        items = themeOptions,
                        selectedIndex = themeMode.value.coerceIn(0, 2) + accentMode.value.coerceIn(0, 1) * 3,
                        onSelectedIndexChange = onThemeSelectionChange,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.floating_bottom_bar),
                        checked = floatingBottomBar.value,
                        onCheckedChange = { onFloatingBottomBarChange(it) },
                    )
                    AnimatedVisibility(visible = floatingBottomBar.value) {
                        RestorableDropdownPreference(
                            title = stringResource(R.string.floating_bottom_bar_style),
                            items = floatingStyleOptions,
                            selectedIndex = floatingBottomBarStyle.value.coerceIn(floatingStyleOptions.indices),
                            onSelectedIndexChange = { onFloatingBottomBarStyleChange(it) },
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.glass_style),
                        checked = glassStyle.value,
                        onCheckedChange = onGlassStyleChange,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.blur_bottom_bar),
                        checked = blurBottomBar.value,
                        onCheckedChange = { onBlurBottomBarChange(it) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.blur_top_bar),
                        checked = blurTopBar.value,
                        onCheckedChange = { onBlurTopBarChange(it) },
                    )
                    RestorableDropdownPreference(
                        title = stringResource(R.string.nav_transition_style),
                        items = navTransitionStyleOptions,
                        selectedIndex = navTransitionStyle.value.coerceIn(navTransitionStyleOptions.indices),
                        onSelectedIndexChange = onNavTransitionStyleChange,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.swipe_back),
                        checked = swipeBack.value,
                        onCheckedChange = { onSwipeBackChange(it) },
                    )
                }
            }
        }
    }
}
