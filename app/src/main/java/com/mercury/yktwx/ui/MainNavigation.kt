package com.mercury.yktwx.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mercury.yktwx.ui.components.AppIcons
import com.mercury.yktwx.ui.components.LocalBarBlurBackdrop
import com.mercury.yktwx.ui.components.barBlurBackground
import com.mercury.yktwx.ui.components.floatingBarColor
import com.mercury.yktwx.ui.components.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val mainNavigationIcons = listOf(MiuixIcons.Scan, MiuixIcons.GridView, AppIcons.Bill, MiuixIcons.Contacts)
private val mainNavigationLabels = listOf("付款", "大厅", "账单", "我的")

/** Navigation and payment islands move as one footer, retaining their full layout inset. */
internal class MainNavigationMotion(
    val visible: Boolean, private val hidden: Boolean,
    private val displacement: State<Float>, private val opacity: State<Float>, private val height: Int,
) {
    fun modifier(): Modifier = Modifier.graphicsLayer {
        translationY = displacement.value * height
        alpha = opacity.value
    }.then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
}

internal val LocalMainNavigationMotion = staticCompositionLocalOf<MainNavigationMotion?> { null }

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
    bottomBarVisible: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (shouldShowMainNavigationRail(settings)) {
        Row(Modifier.fillMaxSize()) {
            MainNavigationRail(settings, selected, width >= 1200.dp, onSelected)
            Scaffold(Modifier.weight(1f).fillMaxHeight(), content = content)
        }
    } else {
        val transition = updateTransition(bottomBarVisible, label = "main-footer")
        val displacement = transition.animateFloat(
            transitionSpec = { tween(if (targetState) 260 else 200) }, label = "main-footer-slide",
        ) { if (it) 0f else 1f }
        val opacity = transition.animateFloat(
            transitionSpec = { tween(if (targetState) 180 else 140) }, label = "main-footer-fade",
        ) { if (it) 1f else 0f }
        var height by remember { mutableIntStateOf(0) }
        val motion = MainNavigationMotion(bottomBarVisible,
            !transition.currentState && !transition.targetState, displacement, opacity, height)
        CompositionLocalProvider(LocalMainNavigationMotion provides motion) {
            Scaffold(bottomBar = {
                Box(Modifier.onSizeChanged { height = it.height }.then(motion.modifier())) {
                    BottomNavigation(settings, selected) { if (bottomBarVisible) onSelected(it) }
                }
            }, content = content)
        }
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
                // Return effects may capture the background even when bar blur is disabled.
                backdrop = if (blur) LocalBarBlurBackdrop.current else null, modifier = Modifier.zIndex(2f),
            )
        } else {
            val modifier = Modifier.barBlurBackground(
                if (floating) RoundedCornerShape(FloatingToolbarDefaults.CornerRadius) else RectangleShape,
                backgroundColor = if (floating && isSystemInDarkTheme()) floatingBarColor() else MiuixTheme.colorScheme.surface,
            ).zIndex(2f)
            if (floating) FloatingNavigationBar(modifier = modifier,
                color = if (blur) Color.Transparent else floatingBarColor()) {
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
