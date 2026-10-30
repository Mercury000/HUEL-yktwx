package com.mercury.yktwx.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mercury.yktwx.ui.components.AppIcons
import com.mercury.yktwx.ui.components.LocalBarBlurBackdrop
import com.mercury.yktwx.ui.components.barBlurBackground
import com.mercury.yktwx.ui.components.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val mainNavigationIcons = listOf(MiuixIcons.Scan, MiuixIcons.GridView, AppIcons.Bill, MiuixIcons.Contacts)
private val mainNavigationLabels = listOf("付款", "大厅", "账单", "我的")

@Composable
internal fun shouldShowMainNavigationRail(settings: ThemeSettings): Boolean {
    val windowSize = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) {
        windowSize.width.toDp() to windowSize.height.toDp()
    }
    return !settings.floatingBottomBar.value &&
        (width >= 840.dp || (width >= 600.dp && height / width < 1.2f))
}

/** SonyPods' window rules; floating bars keep their centered, content-sized layout. */
@Composable
internal fun MainNavigationScaffold(
    settings: ThemeSettings,
    selected: Int,
    onSelected: (Int) -> Unit,
    floatingBarVisible: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (shouldShowMainNavigationRail(settings)) {
        Row(Modifier.fillMaxSize()) {
            MainNavigationRail(settings, selected, width >= 1200.dp, onSelected)
            Scaffold(Modifier.weight(1f).fillMaxHeight(), content = content)
        }
    } else {
        Scaffold(bottomBar = {
            if (settings.floatingBottomBar.value) {
                AnimatedVisibility(
                    visible = floatingBarVisible,
                    enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(140)),
                ) {
                    BottomNavigation(settings, selected, onSelected)
                }
            } else {
                BottomNavigation(settings, selected, onSelected)
            }
        }, content = content)
    }
}

@Composable
private fun MainNavigationRail(settings: ThemeSettings, selected: Int, expanded: Boolean, onSelected: (Int) -> Unit) {
    // Keep the user's choice when rotation temporarily replaces the rail with a bottom bar.
    val initiallyExpanded = remember { settings.getNavigationRailExpanded(expanded) }
    val state = rememberNavigationRailState(
        initialValue = if (initiallyExpanded) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
    )
    LaunchedEffect(state.currentValue) { settings.setNavigationRailExpanded(state.isExpanded) }
    NavigationRail(state = state, modifier = Modifier.fillMaxHeight().testTag("main-navigation-rail")) {
        mainNavigationIcons.forEachIndexed { index, icon ->
            NavigationRailItem(selected = selected == index, onClick = { onSelected(index) },
                icon = icon, label = mainNavigationLabels[index])
        }
    }
}

@Composable
private fun BottomNavigation(settings: ThemeSettings, selected: Int, onSelected: (Int) -> Unit) {
    val floating = settings.floatingBottomBar.value
    val blur = settings.blurBottomBar.value
    Box(Modifier.fillMaxWidth().testTag("main-bottom-navigation")) {
        if (floating && settings.floatingBottomBarStyle.value == 1) {
            IosLiquidGlassNavigationBar(
                items = mainNavigationIcons.mapIndexed { index, icon ->
                    NavigationItem(label = mainNavigationLabels[index], icon = icon)
                },
                selectedIndex = selected, onItemClick = onSelected,
                backdrop = LocalBarBlurBackdrop.current, modifier = Modifier.zIndex(2f),
            )
        } else {
            val modifier = Modifier.barBlurBackground(
                if (floating) RoundedCornerShape(FloatingToolbarDefaults.CornerRadius) else RectangleShape,
            ).zIndex(2f)
            if (floating) FloatingNavigationBar(modifier = modifier,
                color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer) {
                mainNavigationIcons.forEachIndexed { index, icon ->
                    FloatingNavigationBarItem(selected = selected == index, onClick = { onSelected(index) },
                        icon = icon, label = mainNavigationLabels[index])
                }
            } else NavigationBar(modifier = modifier,
                color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surface, showDivider = true) {
                mainNavigationIcons.forEachIndexed { index, icon ->
                    NavigationBarItem(selected = selected == index, onClick = { onSelected(index) },
                        icon = icon, label = mainNavigationLabels[index])
                }
            }
        }
    }
}
