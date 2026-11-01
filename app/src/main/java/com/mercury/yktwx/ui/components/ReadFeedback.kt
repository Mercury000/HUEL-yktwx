package com.mercury.yktwx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.ReadFailureKind
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Message and category travel together, including retained animation and retry snapshots. */
internal data class ReadError(val message: String, val kind: ReadFailureKind = ReadFailureKind.Other)

internal fun readError(message: String?, kind: ReadFailureKind?) = message?.let { ReadError(it, kind ?: ReadFailureKind.Other) }

internal object FeedbackText {
    const val Offline = "网络连接失败"
    fun read(error: ReadError, cached: Boolean = false): String {
        if (error.kind == ReadFailureKind.Authentication) return "登录已失效，请重新登录"
        val reason = if (error.kind == ReadFailureKind.Network) "网络连接失败" else if (cached) "暂时无法更新" else "暂时无法加载"
        return "$reason，" + if (cached) "显示上次数据" else "请下拉刷新"
    }
    fun inline(message: String): String = message.replace(Regex("[\\r\\n]+"), "，").trim()
    fun firstError(vararg errors: ReadError?): ReadError? =
        errors.firstOrNull { it != null && it.kind != ReadFailureKind.Network } ?: errors.firstOrNull { it != null }
    fun networkError(vararg errors: ReadError?): ReadError? = errors.firstOrNull { it?.kind == ReadFailureKind.Network }
    fun mergedNetworkError(vararg regions: Pair<String, ReadError?>): String? {
        val affected = regions.filter { (_, error) -> error?.kind == ReadFailureKind.Network }.map { it.first }.distinct()
        return affected.takeIf { it.size > 1 }?.joinToString("、")?.plus("更新失败，请下拉刷新")
    }
}

/** Fixed footprint keeps the amount and its adjacent action still throughout a balance read. */
@Composable
internal fun BalanceReadIndicator(loading: Boolean) {
    Box(Modifier.padding(start = 8.dp).size(20.dp).testTag("balance-read-slot"), contentAlignment = Alignment.Center) {
        AnimatedVisibility(loading, enter = fadeIn(tween(ContentMotion.Fade)), exit = fadeOut(tween(ContentMotion.Fade))) {
            CircularProgressIndicator(Modifier.testTag("balance-read-progress").semantics {
                contentDescription = "正在刷新余额"
            }, size = 14.dp, strokeWidth = 1.5.dp, colors = readProgressColors())
        }
    }
}

private val LocalSkeletonActive = staticCompositionLocalOf { true }
internal val LocalSkeletonDelayEnabled = staticCompositionLocalOf { false }
private val LocalPageNetworkError = staticCompositionLocalOf<String?> { null }
private val LocalInitialReadFailure = staticCompositionLocalOf { false }

/** An existing merged failure stays put until the current retry has a settled result. */
@Composable
internal fun rememberPageNetworkError(error: String?, refreshing: Boolean, hasContent: Boolean): String? {
    var previous by remember { mutableStateOf<String?>(null) }
    val displayed = if (!hasContent) null else if (refreshing && previous != null) previous else error
    SideEffect { previous = displayed }
    return displayed
}

@Composable
internal fun rememberCachedReadError(error: ReadError?, refreshing: Boolean, hasContent: Boolean): ReadError? {
    var previous by remember { mutableStateOf<ReadError?>(null) }
    val displayed = if (!hasContent) null else if (refreshing && previous != null) previous else error
    SideEffect { previous = displayed }
    return displayed
}

/** Keep the first content item stable and animate the notice together with its spacing. */
@Composable
internal fun PageReadNotice(error: String?, spacing: Dp, loading: Boolean = false,
    errorTag: String = "page-network-error",
    failed: Boolean = error != null,
    topSpacing: Dp = 0.dp,
    content: @Composable () -> Unit) {
    val userRefresh = LocalUserRefresh.current
    val reading = loading && !userRefresh && !LocalReadOffline.current
    var finishing by remember { mutableStateOf(false) }
    val completion = remember { Animatable(0f) }
    var wasReading by remember { mutableStateOf(reading) }
    LaunchedEffect(reading, error, failed) {
        if (reading) {
            finishing = false
            completion.snapTo(0f)
        } else if (wasReading && !failed && !userRefresh) {
            finishing = true
            completion.animateTo(1f, tween(ContentMotion.Append))
            finishing = false
        } else finishing = false
        wasReading = reading
    }
    // Cached failures are already present on entry; only a new visibility change animates.
    val showing = error != null || reading || finishing
    val displayedError = rememberWhileShown(error != null, error.orEmpty())
    Column(Modifier.fillMaxWidth()) {
        FeedbackTransition(showing, topSpacing = topSpacing, bottomSpacing = spacing) {
            Box(Modifier.fillMaxWidth().readContentSize()) {
                if (error != null || displayedError.isNotEmpty() && !reading && !finishing) {
                    ContentFade(displayedError) { InlineError(it, Modifier.testTag(errorTag)) }
                    if (reading) ReadProgress(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp), completion.value)
                } else ReadProgress(Modifier, completion.value)
            }
        }
        content()
    }
}

/** A moving segment signals activity without suggesting a measured percentage. */
@Composable
private fun ReadProgress(modifier: Modifier, completion: Float) {
    LinearProgressIndicator(modifier.testTag("page-read-progress").semantics {
        contentDescription = "正在刷新数据"
    }, progress = completion.takeIf { it > 0 }?.let { .45f + .55f * it },
        height = 2.dp, colors = readProgressColors())
}

@Composable
private fun readProgressColors() = if (isSystemInDarkTheme()) ProgressIndicatorDefaults.progressIndicatorColors()
    else ProgressIndicatorDefaults.progressIndicatorColors(
    foregroundColor = MiuixTheme.colorScheme.primary.copy(alpha = .5f),
    backgroundColor = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))

/** Keep a failed first read visible during retries; only actual content replaces it. */
@Composable
internal fun rememberInitialReadError(hasContent: Boolean, error: ReadError?, settled: Boolean): ReadError? {
    var failure by remember { mutableStateOf<ReadError?>(null) }
    val offline = LocalReadOffline.current
    SideEffect {
        if (hasContent) failure = null
        else if (!offline && settled && error != null) failure = error
    }
    return if (hasContent) null else (error.takeIf { settled || offline } ?: failure ?: ReadError(FeedbackText.Offline, ReadFailureKind.Network).takeIf { offline })
}

/** Delay only the skeleton's paint, preserving its real measurements and immediate results. */
@Composable
internal fun delayedSkeletonModifier(active: Boolean = true, enabled: Boolean = LocalSkeletonDelayEnabled.current): Modifier {
    if (!enabled) return Modifier
    val opacity = remember { Animatable(0f) }
    val painting = LocalSkeletonActive.current && active
    LaunchedEffect(painting) {
        if (painting) {
            opacity.snapTo(0f)
            delay(250)
            opacity.animateTo(1f, tween(ContentMotion.Fade))
        } else opacity.stop()
    }
    return Modifier.graphicsLayer { alpha = opacity.value }
}

/** Both outgoing skeleton and incoming failure share the viewport during the crossfade. */
@Composable
internal fun InitialReadTransition(error: ReadError?, networkError: String? = null,
    displayRestored: Boolean = false,
    content: @Composable (displayedError: ReadError?) -> Unit) {
    // Cache restoration only changes the content transition, never the page's identity.
    CompositionLocalProvider(LocalReadSnapshotRestored provides displayRestored) {
        val displayedError = rememberWhileShown(error != null, error)
        ContentFade(error != null, Modifier.fillMaxSize()) { showFailure ->
            CompositionLocalProvider(LocalSkeletonActive provides (error == null),
                LocalSkeletonDelayEnabled provides true,
                LocalPageNetworkError provides networkError.takeIf { !showFailure && error == null },
                LocalInitialReadFailure provides showFailure) {
                content(displayedError.takeIf { showFailure })
            }
        }
    }
}

/** First-read and region failures share one icon and one line, without an extra action. */
@Composable
internal fun ReadUnavailable(error: ReadError, modifier: Modifier = Modifier, pullToRefresh: Boolean = true) {
    val dark = isSystemInDarkTheme()
    if (LocalPageNetworkError.current != null && error.kind == ReadFailureKind.Network) {
        // An unread value remains unknown, rather than becoming zero or a confirmed empty result.
        Text("—", modifier.fillMaxWidth().padding(vertical = 16.dp), fontSize = 14.sp,
            textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        return
    }
    if (!LocalInitialReadFailure.current && error.kind == ReadFailureKind.Network) {
        InlineError(FeedbackText.read(error), modifier.testTag("read-unavailable"))
        return
    }
    Column(modifier.fillMaxWidth().testTag("read-unavailable")
        .padding(horizontal = 8.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).background(if (dark) MiuixTheme.colorScheme.errorContainer else MiuixTheme.colorScheme.error.copy(alpha = .06f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(MiuixIcons.Info, null, Modifier.size(26.dp), tint = if (dark) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text(if (pullToRefresh) FeedbackText.read(error) else FeedbackText.inline(error.message),
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Region feedback uses the existing inline style; page-wide network errors are shown once. */
@Composable
internal fun ReadNotice(error: ReadError?, cached: Boolean = false, modifier: Modifier = Modifier,
    topSpacing: Dp = 8.dp, bottomSpacing: Dp = 0.dp) {
    val displayed = error?.takeUnless { it.message.isBlank() || LocalPageNetworkError.current != null && it.kind == ReadFailureKind.Network }
    AnimatedInlineError(displayed?.let { FeedbackText.read(it, cached) }, modifier.testTag("read-notice"), topSpacing = topSpacing, bottomSpacing = bottomSpacing)
}
