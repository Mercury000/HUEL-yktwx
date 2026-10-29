package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Clear
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Show
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.ArrowPreference
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.lerp

/** Colors from the approved dashboard mockups; preserve the active accent for Monet. */
internal object DashboardPalette {
    val ink: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE9EDF4) else Color(0xFF202329)
    val muted: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9AA8BA) else Color(0xFF929CA9)
    val card: Color @Composable get() = CardDefaults.defaultColors().color
    val field: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2A2A2A) else Color(0xFFF0F0F0)
    val lightingInk: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFA5BDEC) else Color(0xFF354E78)
    val lightingAccent: Color @Composable get() = lerp(MiuixTheme.colorScheme.primary, lightingInk, .45f)
    val lightingPanel: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF202A3A) else Color(0xFFF4F7FD)
    val lightingButton: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2B3D57) else Color(0xFFE6EDFB)
    val airInk: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF9FC4CB) else Color(0xFF466C78)
    val airAccent: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFA6C8CE) else Color(0xFF799FA8)
    val airPanel: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF222F33) else Color(0xFFF2F7F8)
    val airButton: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2F454A) else Color(0xFFE4EEF0)
}

/** Dashboard composition uses the official bar, buttons and surfaces with explicit dimensions. */
@Composable
internal fun DashboardTopBar(title: String, subtitle: String? = null, scrollBehavior: ScrollBehavior? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    SmallTopAppBar(title = "", color = Color.Transparent, scrollBehavior = scrollBehavior, bottomContent = {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                actions()
            }
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 12.dp), fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    })
}

/** Background reads keep the list usable; only a user pull owns the refresh indicator. */
internal val LocalUserRefresh = staticCompositionLocalOf { false }

@Composable
internal fun DashboardPullToRefresh(loading: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(), scrollBehavior: ScrollBehavior? = null,
    content: @Composable () -> Unit) {
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(loading, requested) { if (!loading) requested = false }
    PullToRefresh(isRefreshing = requested && loading, onRefresh = { requested = true; onRefresh() },
        modifier = modifier, contentPadding = contentPadding, topAppBarScrollBehavior = scrollBehavior,
        color = MiuixTheme.colorScheme.primary, refreshTexts = AppRefreshTexts) {
        CompositionLocalProvider(LocalUserRefresh provides requested) { content() }
    }
}

/** Reserve the same text metrics while data is unknown; placeholders expose no fake values. */
@Composable
internal fun DashboardPlaceholder(fontSize: TextUnit, modifier: Modifier = Modifier,
    fraction: Float = .6f, lineHeight: TextUnit = TextUnit.Unspecified, sample: String = "读取",
    color: Color = MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)) {
    val barHeight = with(LocalDensity.current) { (fontSize * .7f).toDp() }
    Box(modifier.clearAndSetSemantics {}) {
        Text(sample, fontSize = fontSize, lineHeight = lineHeight, maxLines = 1, color = Color.Transparent)
        ContentPlaceholder(Modifier.fillMaxWidth(fraction).height(barHeight).align(Alignment.CenterStart), color = color)
    }
}

/** Shared loading surface for content-shaped placeholders. */
@Composable
internal fun ContentPlaceholder(modifier: Modifier = Modifier, cornerRadius: Dp = 4.dp,
    color: Color = MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)) {
    Box(modifier.then(delayedSkeletonModifier()).clearAndSetSemantics {}.background(color, RoundedCornerShape(cornerRadius)))
}

@Composable
internal fun CompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, color: Color = MiuixTheme.colorScheme.primary.copy(alpha = .08f),
    textColor: Color = MiuixTheme.colorScheme.primary) {
    TextButton(text, onClick = onClick, modifier = modifier, enabled = enabled,
        minHeight = 32.dp, cornerRadius = 12.dp, insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        textStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        colors = ButtonDefaults.textButtonColors(color = color, textColor = textColor))
}

@Composable
internal fun DashboardLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, leading: ImageVector? = null, arrow: Boolean = true,
    color: Color = MiuixTheme.colorScheme.primary, trailingArrowAtEnd: Boolean = false) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, color = Color.Transparent) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (leading != null) Icon(leading, null, Modifier.size(16.dp), tint = color)
            Text(text, modifier = if (trailingArrowAtEnd) Modifier.weight(1f) else Modifier,
                fontSize = 12.sp, color = if (enabled) color else MiuixTheme.colorScheme.disabledOnSurface)
            if (arrow) Icon(MiuixIcons.ChevronForward, null, Modifier.size(13.dp), tint = if (enabled) color else MiuixTheme.colorScheme.disabledOnSurface)
        }
    }
}

/** Compact condition label with one removal action and the standard Surface touch target. */
@Composable
internal fun RemovableCondition(label: String, onRemove: () -> Unit) {
    Surface(onClick = onRemove, color = MiuixTheme.colorScheme.primary.copy(alpha = .08f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.semantics { contentDescription = "移除条件：$label" }) {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, Modifier.widthIn(max = 240.dp), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MiuixTheme.colorScheme.primary)
            Icon(MiuixIcons.Clear, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.primary)
        }
    }
}

/** A small visibility icon with the same expanded touch target as other compact actions. */
@Composable
internal fun VisibilityToggle(visible: Boolean, subject: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.size(24.dp), color = Color.Transparent) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (visible) MiuixIcons.Hide else MiuixIcons.Show,
                contentDescription = if (visible) "隐藏$subject" else "显示$subject",
                modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun DashboardPreference(title: String, onClick: () -> Unit, summary: String? = null,
    enabled: Boolean = true, startAction: (@Composable () -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {}) {
    val styles = MiuixTheme.textStyles
    MiuixTheme(colors = MiuixTheme.colorScheme, textStyles = styles.copy(
        main = styles.main.copy(fontSize = 14.sp),
        body1 = styles.body1.copy(fontSize = 14.sp),
        body2 = styles.body2.copy(fontSize = 11.sp),
        paragraph = styles.paragraph.copy(fontSize = 14.sp),
        headline1 = styles.headline1.copy(fontSize = 14.sp),
    )) {
        ArrowPreference(title = title, summary = summary, onClick = onClick, enabled = enabled,
            startAction = startAction?.let { action -> { Box(Modifier.padding(end = 10.dp)) { action() } } },
            endActions = endActions, insideMargin = PaddingValues(horizontal = 20.dp, vertical = if (summary == null) 8.dp else 14.dp))
    }
}
