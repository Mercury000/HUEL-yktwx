package com.mercury.yktwx.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.*
import top.yukonga.miuix.kmp.layout.*
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog

internal val LocalGlassStyle = staticCompositionLocalOf { false }
internal val LocalAppearanceBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
internal val LocalGlassOverlay = staticCompositionLocalOf { false }
private val LocalGlassBarButtons = staticCompositionLocalOf { false }

/** The outer Scaffold records its content slot; its popup host and scrim are siblings. */
@Composable
internal fun AppAppearanceHost(glassStyle: Boolean, content: @Composable () -> Unit) {
    val background = MiuixTheme.colorScheme.surface
    val backdrop = if (glassStyle && isRuntimeShaderSupported()) rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    } else null
    CompositionLocalProvider(LocalGlassStyle provides glassStyle, LocalAppearanceBackdrop provides backdrop) {
        content()
    }
}

@Composable
internal fun AppearanceBackdropContent(content: @Composable () -> Unit) {
    val backdrop = LocalAppearanceBackdrop.current
    Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
        content()
    }
}

/** Forward layout and dismissal to Miuix; only the surface changes with the appearance. */
@Composable
internal fun AppOverlayDialog(
    show: Boolean, modifier: Modifier = Modifier, title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(), summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(), backgroundColor: Color = DialogDefaults.backgroundColor(),
    enableWindowDim: Boolean = true, onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null, outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin, defaultWindowInsetsPadding: Boolean = true,
    renderInRootScaffold: Boolean = true, maxWidth: Dp = DialogDefaults.MaxWidth,
    largeScreen: Boolean? = null, cornerRadius: Dp? = null,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassStyle.current
    OverlayDialog(show = show, modifier = modifier, title = title, titleColor = titleColor,
        summary = summary, summaryColor = summaryColor, backgroundColor = backgroundColor,
        enableWindowDim = enableWindowDim, onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
        outsideMargin = outsideMargin, insideMargin = insideMargin,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding, renderInRootScaffold = renderInRootScaffold,
        maxWidth = maxWidth, largeScreen = largeScreen, cornerRadius = cornerRadius,
        dimColor = if (glass) GlassDialogDefaults.ScrimColor else MiuixTheme.colorScheme.windowDimming,
        surface = if (glass) glassDialogSurface(LocalAppearanceBackdrop.current, containerColor = backgroundColor) else null,
        content = { CompositionLocalProvider(LocalGlassOverlay provides glass) { content() } })
}

@Composable
internal fun AppOverlayBottomSheet(
    show: Boolean, modifier: Modifier = Modifier, title: String? = null,
    startAction: @Composable (() -> Unit)? = null, endAction: @Composable (() -> Unit)? = null,
    backgroundColor: Color = BottomSheetDefaults.backgroundColor(), enableWindowDim: Boolean = true,
    cornerRadius: Dp = BottomSheetDefaults.cornerRadius, sheetMaxWidth: Dp = BottomSheetDefaults.maxWidth,
    onDismissRequest: (() -> Unit)? = null, onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = BottomSheetDefaults.outsideMargin, insideMargin: DpSize = BottomSheetDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true, dragHandleColor: Color = BottomSheetDefaults.dragHandleColor(),
    allowDismiss: Boolean = true, enableNestedScroll: Boolean = true, renderInRootScaffold: Boolean = true,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassStyle.current
    OverlayBottomSheet(show = show, modifier = modifier, title = title, startAction = startAction, endAction = endAction,
        backgroundColor = backgroundColor, enableWindowDim = enableWindowDim, cornerRadius = cornerRadius,
        sheetMaxWidth = sheetMaxWidth, onDismissRequest = onDismissRequest, onDismissFinished = onDismissFinished,
        outsideMargin = outsideMargin, insideMargin = insideMargin, defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        dragHandleColor = dragHandleColor, allowDismiss = allowDismiss, enableNestedScroll = enableNestedScroll,
        renderInRootScaffold = renderInRootScaffold,
        dimColor = if (glass) GlassBottomSheetDefaults.ScrimColor else MiuixTheme.colorScheme.windowDimming,
        surface = if (glass) glassBottomSheetSurface(LocalAppearanceBackdrop.current, containerColor = backgroundColor) else null,
        content = { CompositionLocalProvider(LocalGlassOverlay provides glass) { content() } })
}

@Composable
internal fun AppTopAppBar(
    title: String, modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface, largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface, subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null, defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding, navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding, bottomContent: @Composable () -> Unit = {},
    isContentScrolled: Boolean? = null, visualContentOffset: (() -> Float)? = null,
) {
    if (LocalGlassStyle.current) {
        AppGlassTopAppBar(title = title, largeTitle = largeTitle, subtitle = subtitle,
            modifier = modifier, titleAlpha = { titleColor.alpha },
            scrollBehavior = scrollBehavior, isContentScrolled = isContentScrolled,
            visualContentOffset = visualContentOffset, navigationIcon = navigationIcon,
            actions = actions, bottomContent = bottomContent,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding)
    } else {
        TopAppBar(title, modifier, color, titleColor, largeTitle, largeTitleColor, subtitle, subtitleColor,
            navigationIcon, actions, scrollBehavior, defaultWindowInsetsPadding, titlePadding,
            navigationIconPadding, actionIconPadding, bottomContent)
    }
}

@Composable
internal fun AppSmallTopAppBar(
    title: String, modifier: Modifier = Modifier, color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface, subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null, defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding, navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding, bottomContent: @Composable () -> Unit = {},
    backgroundAlpha: () -> Float = { 1f },
    isContentScrolled: Boolean? = null,
    visualContentOffset: (() -> Float)? = null,
    glassStyle: Boolean = LocalGlassStyle.current,
) {
    val glass = glassStyle
    val scrolled = isContentScrolled ?: (scrollBehavior?.state?.contentOffset?.let { it < 0f } ?: true)
    CompositionLocalProvider(LocalGlassBarButtons provides glass) {
        if (glass) {
            AppGlassTopAppBar(title = title, modifier = modifier,
                isContentScrolled = scrolled, scrollBehavior = scrollBehavior,
                visualContentOffset = visualContentOffset,
                titleAlpha = { titleColor.alpha }, backgroundAlpha = backgroundAlpha,
                subtitle = subtitle, navigationIcon = navigationIcon, actions = actions,
                bottomContent = bottomContent, defaultWindowInsetsPadding = defaultWindowInsetsPadding)
        } else SmallTopAppBar(title = title, modifier = modifier,
            color = color, titleColor = titleColor,
            subtitle = subtitle, subtitleColor = subtitleColor, navigationIcon = navigationIcon, actions = actions,
            scrollBehavior = scrollBehavior, defaultWindowInsetsPadding = defaultWindowInsetsPadding,
            titlePadding = titlePadding,
            navigationIconPadding = navigationIconPadding, actionIconPadding = actionIconPadding,
            bottomContent = bottomContent)
    }
}

/** The demo's GlassPage keeps material sampling on while progressive blur is toggled. */
@Composable
private fun AppGlassTopAppBar(
    title: String, modifier: Modifier,
    largeTitle: String = title, subtitle: String = "", scrollBehavior: ScrollBehavior?,
    isContentScrolled: Boolean?, visualContentOffset: (() -> Float)?,
    titleAlpha: () -> Float, backgroundAlpha: () -> Float = { 1f },
    navigationIcon: @Composable () -> Unit, actions: @Composable RowScope.() -> Unit,
    bottomContent: @Composable () -> Unit, defaultWindowInsetsPadding: Boolean,
) {
    val backdrop = LocalBarBlurBackdrop.current
    val scrolled = isContentScrolled ?: (scrollBehavior?.state?.contentOffset?.let { it < 0f } ?: true)
    CompositionLocalProvider(LocalGlassBarButtons provides true) {
        GlassTopAppBar(title = title, largeTitle = largeTitle, subtitle = subtitle,
            modifier = modifier.testTag("glass-top-bar"), backdrop = backdrop,
            isContentScrolled = scrolled, visualContentOffset = visualContentOffset,
            // This is Glass Top Bar Progressive Blur, not the demo's global Enable Blur Effect.
            // Native defaults retain the gradient band and button material when it is off.
            progressiveBlur = LocalTopBarBlurEnabled.current, scrollBehavior = scrollBehavior,
            titleAlpha = titleAlpha, backgroundAlpha = backgroundAlpha,
            navigationIcon = navigationIcon, actions = actions, bottomContent = bottomContent,
            defaultWindowInsetsPadding = defaultWindowInsetsPadding)
    }
}

@Composable
internal fun AppIconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified, cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    minHeight: Dp = IconButtonDefaults.MinHeight, minWidth: Dp = IconButtonDefaults.MinWidth,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlassBarButtons.current
    val size = maxOf(GlassTopAppBarDefaults.ButtonSize, minHeight, minWidth)
    if (glass) {
        // Keep the official top-bar material context for disabled actions as well.
        // GlassIconButton has no enabled parameter, so block touch, focus and activation here.
        val disabledModifier = if (enabled) Modifier else Modifier
            .semantics { disabled() }.focusProperties { canFocus = false }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        GlassIconButton(onClick = { if (enabled) onClick() }, modifier = modifier.then(disabledModifier),
            backdrop = LocalBarBlurBackdrop.current,
            size = size, content = content)
    } else {
        IconButton(onClick, modifier, enabled, holdDownState, backgroundColor, cornerRadius, minHeight, minWidth, content)
    }
}

@Composable
internal fun AppBackIcon(contentDescription: String? = "返回", modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val glass = LocalGlassBarButtons.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(if (glass) MiuixIcons.Glass.ChevronBackward else MiuixIcons.Back, contentDescription,
        modifier.then(if (glass) Modifier.size(28.dp) else Modifier)
            .graphicsLayer { if (rtl) scaleX = -1f }, tint)
}

/** Inline controls record only their underlay, never the glass control itself. */
@Composable
private fun InlineGlassTabs(
    tabs: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier,
    minWidth: Dp, maxWidth: Dp, height: Dp, spacing: Dp, segmented: Boolean, contentAlignment: Alignment,
) {
    val surface = MiuixTheme.colorScheme.surface
    val inlineBackdrop = rememberLayerBackdrop { drawRect(surface) }
    val overlayBackdrop = LocalAppearanceBackdrop.current.takeIf { LocalGlassOverlay.current }
    val backdrop = overlayBackdrop ?: inlineBackdrop
    Box(Modifier.fillMaxWidth().then(modifier).height(height), contentAlignment = contentAlignment) {
        val tabModifier = Modifier.widthIn(
            min = minWidth * tabs.size + spacing * (tabs.size - 1),
            max = maxWidth * tabs.size + spacing * (tabs.size - 1)).fillMaxWidth()
        Box(tabModifier) {
            val underlayShape = if (segmented) GlassSegmentedTabRowDefaults.shape(height)
                else androidx.compose.ui.graphics.RectangleShape
            if (overlayBackdrop == null) Box(Modifier.matchParentSize().clip(underlayShape).layerBackdrop(inlineBackdrop))
            if (segmented) {
                GlassSegmentedTabRow(tabs = tabs, selectedIndex = selected, onSelect = onSelected,
                    backdrop = backdrop, modifier = Modifier.fillMaxWidth(), height = height, shadow = null)
            } else {
                GlassTabRow(tabs = tabs, selectedIndex = selected, onSelect = onSelected, backdrop = backdrop,
                    modifier = Modifier.fillMaxWidth(), height = height, tabGap = spacing, shadow = null)
            }
        }
    }
}

@Composable
internal fun AppTabRow(
    tabs: List<String>, selectedTabIndex: Int, onTabSelected: (Int) -> Unit, modifier: Modifier = Modifier,
    colors: TabRowColors = TabRowDefaults.tabRowColors(), minWidth: Dp = TabRowDefaults.TabRowMinWidth,
    maxWidth: Dp = TabRowDefaults.TabRowMaxWidth, height: Dp = TabRowDefaults.TabRowHeight,
    cornerRadius: Dp = TabRowDefaults.TabRowCornerRadius, itemSpacing: Dp = 9.dp,
    contentAlignment: Alignment = Alignment.Center, listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = null,
) {
    if (LocalGlassStyle.current && tabs.size in 2..4 && selectedTabIndex in tabs.indices) {
        InlineGlassTabs(tabs, selectedTabIndex, onTabSelected, modifier, minWidth, maxWidth, height, itemSpacing, false, contentAlignment)
    } else TabRow(tabs, selectedTabIndex, onTabSelected, modifier, colors, minWidth, maxWidth, height,
        cornerRadius, itemSpacing, contentAlignment, listState, interactionSource, indication)
}

@Composable
internal fun AppTabRowWithContour(
    tabs: List<String>, selectedTabIndex: Int, onTabSelected: (Int) -> Unit, modifier: Modifier = Modifier,
    colors: TabRowColors = TabRowDefaults.tabRowColors(), minWidth: Dp = TabRowDefaults.TabRowWithContourMinWidth,
    maxWidth: Dp = TabRowDefaults.TabRowWithContourMaxWidth, height: Dp = TabRowDefaults.TabRowWithContourHeight,
    cornerRadius: Dp = TabRowDefaults.TabRowWithContourCornerRadius, itemSpacing: Dp = 5.dp,
    contentAlignment: Alignment = Alignment.Center, listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null, indication: Indication? = null,
) {
    if (LocalGlassStyle.current && tabs.size in 2..4 && selectedTabIndex in tabs.indices) {
        InlineGlassTabs(tabs, selectedTabIndex, onTabSelected, modifier, minWidth, maxWidth, height, itemSpacing, true, contentAlignment)
    } else TabRowWithContour(tabs, selectedTabIndex, onTabSelected, modifier, colors, minWidth, maxWidth, height,
        cornerRadius, itemSpacing, contentAlignment, listState, interactionSource, indication)
}
