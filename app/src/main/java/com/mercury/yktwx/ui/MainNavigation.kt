package com.mercury.yktwx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.zIndex
import com.mercury.yktwx.ui.components.AppIcons
import com.mercury.yktwx.ui.components.LocalBarBlurBackdrop
import com.mercury.yktwx.ui.components.barBlurBackground
import com.mercury.yktwx.ui.components.floatingBarColor
import com.mercury.yktwx.ui.components.liquid.IosLiquidGlassNavigationBar
import com.mercury.yktwx.ui.components.ActionSurface
import com.mercury.yktwx.ui.components.liquid.iosNavigationSurface
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassMotion
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.icon.glass.Scan as GlassScan
import top.yukonga.miuix.kmp.icon.glass.GridView as GlassGridView
import top.yukonga.miuix.kmp.icon.glass.Contacts as GlassContacts
import top.yukonga.miuix.kmp.icon.glass.Wallet as GlassWallet
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val mainNavigationIcons = listOf(MiuixIcons.Scan, MiuixIcons.GridView, AppIcons.Bill, MiuixIcons.Contacts)
private val glassNavigationIcons = listOf(MiuixIcons.Glass.GlassScan, MiuixIcons.Glass.GlassGridView,
    MiuixIcons.Glass.GlassWallet, MiuixIcons.Glass.GlassContacts)
private val mainNavigationLabels = listOf("付款", "大厅", "账单", "我的")

@Composable
internal fun shouldShowMainNavigationRail(): Boolean {
    val windowSize = LocalWindowInfo.current.containerSize
    val (width, height) = with(LocalDensity.current) {
        windowSize.width.toDp() to windowSize.height.toDp()
    }
    return width >= 840.dp || (width >= 600.dp && height / width < 1.2f)
}

/** Keep page content at one composition location as the demo's adaptive chrome changes. */
@Composable
internal fun MainNavigationScaffold(
    settings: ThemeSettings,
    selected: Int,
    onSelected: (Int) -> Unit,
    railOutside: Boolean = false,
    onScan: (() -> Unit)? = null,
    scanEnabled: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val width = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val useRail = shouldShowMainNavigationRail()
    Row(Modifier.fillMaxSize()) {
        if (useRail && !railOutside) {
            MainNavigationRail(selected, width >= 1200.dp, onSelected)
        }
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            bottomBar = { if (!useRail) BottomNavigation(settings, selected, onSelected, onScan, scanEnabled) },
            content = content,
        )
    }
}

@Composable
internal fun MainNavigationRail(selected: Int, expanded: Boolean, onSelected: (Int) -> Unit) {
    val state = rememberNavigationRailState(
        initialValue = if (expanded) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
    )
    LaunchedEffect(expanded) { if (expanded) state.expand() else state.collapse() }
    NavigationRail(state = state, modifier = Modifier.fillMaxHeight().testTag("main-navigation-rail")) {
        mainNavigationIcons.forEachIndexed { index, icon ->
            NavigationRailItem(selected = selected == index, onClick = { onSelected(index) },
                icon = icon, label = mainNavigationLabels[index])
        }
    }
}

@Composable
private fun BottomNavigation(settings: ThemeSettings, selected: Int, onSelected: (Int) -> Unit,
    onScan: (() -> Unit)?, scanEnabled: Boolean) {
    val floating = settings.floatingBottomBar.value
    val blur = settings.blurBottomBar.value
    val style = settings.floatingBottomBarStyle.value
    val backdrop = if (blur) LocalBarBlurBackdrop.current else null
    val navigationBottom = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
        .asPaddingValues().calculateBottomPadding()
    val bottomMargin = if (navigationBottom == 0.dp) 32.dp else 16.dp + navigationBottom
    val scanAction: (@Composable () -> Unit)? = onScan?.takeIf { selected == 0 }?.let { action -> {
        FloatingScanAction(style, scanEnabled, action, backdrop)
    } }
    Box(Modifier.fillMaxWidth().testTag("main-bottom-navigation")) {
        if (floating && settings.floatingBottomBarStyle.value == 2) {
            GlassNavigationBar(items = glassNavigationIcons.mapIndexed { index, icon ->
                GlassNavigationItem(icon, label = mainNavigationLabels[index])
            }, selectedIndex = selected, onSelect = onSelected, backdrop = backdrop,
                trailingAction = scanAction,
                modifier = Modifier.padding(horizontal = 24.dp).zIndex(2f).testTag("os4-navigation"))
        } else if (floating && settings.floatingBottomBarStyle.value == 1) {
            FloatingNavigationActionLayout(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = bottomMargin),
                trailingAction = scanAction,
            ) {
                IosLiquidGlassNavigationBar(
                    items = mainNavigationIcons.mapIndexed { index, icon ->
                        NavigationItem(label = mainNavigationLabels[index], icon = icon)
                    },
                    selectedIndex = selected, onItemClick = onSelected,
                    backdrop = backdrop, modifier = Modifier.zIndex(2f), includeOuterPadding = false,
                )
            }
        } else {
            val modifier = Modifier.barBlurBackground(
                if (floating) RoundedCornerShape(FloatingToolbarDefaults.CornerRadius) else RectangleShape,
                backgroundColor = if (floating) floatingBarColor() else MiuixTheme.colorScheme.surface,
            ).zIndex(2f)
            if (floating) {
                FloatingNavigationActionLayout(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    trailingAction = scanAction?.let { action -> { Box(Modifier.padding(bottom = bottomMargin)) { action() } } }) {
                    // Four native icon items occupy 252dp; preserve that compact width.
                    val naturalWidth = FloatingNavigationBarDefaults.HorizontalPadding * 2 +
                        (FloatingNavigationBarDefaults.IconSize + FloatingNavigationBarDefaults.IconPadding * 2) * mainNavigationIcons.size +
                        FloatingNavigationBarDefaults.ItemSpacing * (mainNavigationIcons.size - 1)
                    Box(Modifier.widthIn(max = naturalWidth)) {
                        FloatingNavigationBar(modifier = modifier.fillMaxWidth().testTag("default-floating-capsule"),
                            color = if (blur) Color.Transparent else floatingBarColor()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FloatingNavigationBarDefaults.ItemSpacing)) {
                                mainNavigationIcons.forEachIndexed { index, icon ->
                                    FloatingNavigationBarItem(selected = selected == index, onClick = { onSelected(index) },
                                        icon = icon, label = mainNavigationLabels[index], modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
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

@Composable
private fun FloatingScanAction(style: Int, enabled: Boolean, onScan: () -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?) {
    val tag = Modifier.testTag("main-floating-scan")
    when (style) {
        2 -> GlassIconButton(onClick = { if (enabled) onScan() }, backdrop = backdrop,
            size = GlassNavigationBarDefaults.Height,
            shadow = GlassShadows.Float,
            modifier = tag.semantics { role = Role.Button; if (!enabled) disabled() }
                .graphicsLayer { alpha = if (enabled) 1f else .38f }) {
            Icon(MiuixIcons.Glass.GlassScan, "扫一扫", Modifier.size(28.dp), tint = MiuixTheme.colorScheme.onSurface)
        }
        1 -> ActionSurface(onClick = onScan, enabled = enabled, shape = CircleShape,
            modifier = tag.size(64.dp).iosNavigationSurface(backdrop), color = Color.Transparent) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(MiuixIcons.Scan, "扫一扫", Modifier.size(22.dp),
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            }
        }
        else -> IconButton(onClick = onScan, enabled = enabled,
            modifier = tag.size(52.dp).dropShadow(CircleShape,
                Shadow(radius = 10.dp, color = Color.Black, alpha = .2f))
                .barBlurBackground(CircleShape, backgroundColor = floatingBarColor()),
            backgroundColor = Color.Transparent) {
            Icon(MiuixIcons.Scan, "扫一扫", Modifier.size(FloatingNavigationBarDefaults.IconSize))
        }
    }
}

/** Adapted from the pinned Miuix GlassNavigationBarLayout (Apache-2.0).
 * Keeps the navigation mounted as action width, gap, scale and opacity change together.
 */
@Composable
private fun FloatingNavigationActionLayout(modifier: Modifier = Modifier,
    trailingAction: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    val retainedAction = remember { mutableStateOf(trailingAction) }
    val progress = animateFloatAsState(if (trailingAction != null) 1f else 0f,
        GlassMotion.navShowHide(), label = "floatingNavigationAction")
    SideEffect { if (trailingAction != null || progress.value == 0f) retainedAction.value = trailingAction }
    val presentedAction = trailingAction ?: retainedAction.value.takeIf { progress.value > 0f }
    Layout(modifier = modifier, content = {
        Box(propagateMinConstraints = true) { content() }
        if (presentedAction != null) Box(Modifier.focusProperties { canFocus = trailingAction != null }
            .graphicsLayer {
                outsets = LayerOutsets(12.dp, 12.dp, 12.dp, 12.dp)
                val fraction = progress.value.coerceIn(0f, 1f)
                scaleX = fraction; scaleY = fraction; alpha = fraction
            }.then(if (trailingAction == null) Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            } else Modifier)) { presentedAction() }
    }) { measurables, constraints ->
        val action = measurables.getOrNull(1)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val fraction = progress.value.coerceIn(0f, 1f)
        val actionWidth = ((action?.width ?: 0) * fraction).toInt()
        val gap = if (action != null) (GlassNavigationBarDefaults.ActionSpacing.toPx() * fraction).toInt()
            .coerceIn(0, (constraints.maxWidth - actionWidth).coerceAtLeast(0)) else 0
        val bar = measurables[0].measure(constraints.copy(
            // Measure the actual capsule, not a full-width wrapper around a capped capsule.
            minWidth = 0,
            maxWidth = if (constraints.hasBoundedWidth) (constraints.maxWidth - actionWidth - gap).coerceAtLeast(0)
                else constraints.maxWidth))
        val groupWidth = bar.width + gap + actionWidth
        val width = constraints.constrainWidth(groupWidth)
        val height = constraints.constrainHeight(maxOf(bar.height, action?.height ?: 0))
        layout(width, height) {
            val start = (width - groupWidth) / 2
            bar.placeRelative(start, (height - bar.height) / 2)
            action?.placeRelative(start + bar.width + gap + (actionWidth - action.width) / 2, (height - action.height) / 2)
        }
    }
}
