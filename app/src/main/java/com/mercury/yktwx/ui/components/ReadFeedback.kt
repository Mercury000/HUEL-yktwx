package com.mercury.yktwx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object FeedbackText {
    const val Offline = "网络连接失败"
    fun read(error: String, cached: Boolean = false): String {
        if ("登录" in error && ("失效" in error || "重新登录" in error)) return "登录已失效，请重新登录"
        val reason = if (isNetwork(error)) "网络连接失败" else "暂时无法加载"
        return "$reason，" + if (cached) "下拉刷新更新" else "请下拉刷新"
    }
    fun inline(message: String): String = message.replace(Regex("[\\r\\n]+"), "，").trim()
    fun isNetwork(message: String) = listOf("网络连接失败", "网络异常", "网络不可用").any { it in message }
    fun networkError(vararg messages: String?): String? = messages.firstOrNull { it != null && isNetwork(it) }
    fun mergedNetworkError(vararg regions: Pair<String, String?>): String? {
        val affected = regions.filter { (_, error) -> error != null && isNetwork(error) }.map { it.first }.distinct()
        return affected.takeIf { it.size > 1 }?.joinToString("、")?.plus("更新失败，请下拉刷新")
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

/** Keep the first content item stable and animate the notice together with its spacing. */
@Composable
internal fun PageNetworkNotice(error: String?, spacing: Dp, content: @Composable () -> Unit) {
    // Cached failures are already present on entry; only a new visibility change animates.
    val visible = remember { MutableTransitionState(error != null) }
    visible.targetState = error != null
    val displayedError = rememberWhileShown(error != null, error.orEmpty())
    Column(Modifier.fillMaxWidth()) {
        AnimatedVisibility(visibleState = visible,
            enter = fadeIn(tween(150)) + expandVertically(tween(200), expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top)) {
            InlineError(displayedError, Modifier.testTag("page-network-error").padding(bottom = spacing))
        }
        content()
    }
}

/** Keep a failed first read visible during retries; only actual content replaces it. */
@Composable
internal fun rememberInitialReadError(hasContent: Boolean, error: String?, settled: Boolean): String? {
    var failure by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if (hasContent) failure = null
        else if (settled && error != null) failure = error
    }
    return if (hasContent) null else (error.takeIf { settled } ?: failure)
}

/** Delay only the skeleton's paint, preserving its real measurements and immediate results. */
@Composable
internal fun delayedSkeletonModifier(): Modifier {
    if (!LocalSkeletonDelayEnabled.current) return Modifier
    val opacity = remember { Animatable(0f) }
    val active = LocalSkeletonActive.current
    LaunchedEffect(active) {
        if (active) {
            delay(250)
            opacity.animateTo(1f, tween(150))
        } else opacity.stop()
    }
    return Modifier.graphicsLayer { alpha = opacity.value }
}

/** Both outgoing skeleton and incoming failure share the viewport during the crossfade. */
@Composable
internal fun InitialReadTransition(error: String?, networkError: String? = null,
    content: @Composable (displayedError: String?) -> Unit) {
    val displayedError = rememberWhileShown(error != null, error.orEmpty())
    AnimatedContent(targetState = error != null, modifier = Modifier.fillMaxSize(),
        transitionSpec = { (fadeIn(tween(150)) togetherWith fadeOut(tween(150))).using(null) },
        label = "InitialReadFailureTransition") { showFailure ->
        CompositionLocalProvider(LocalSkeletonActive provides (error == null),
            LocalSkeletonDelayEnabled provides true,
            LocalPageNetworkError provides networkError.takeIf { !showFailure && error == null },
            LocalInitialReadFailure provides showFailure) {
            content(displayedError.takeIf { showFailure })
        }
    }
}

/** First-read and region failures share one icon and one line, without an extra action. */
@Composable
internal fun ReadUnavailable(error: String, modifier: Modifier = Modifier, pullToRefresh: Boolean = true) {
    if (LocalPageNetworkError.current != null && FeedbackText.isNetwork(error)) {
        // An unread value remains unknown, rather than becoming zero or a confirmed empty result.
        Text("—", modifier.fillMaxWidth().padding(vertical = 16.dp), fontSize = 14.sp,
            textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        return
    }
    if (!LocalInitialReadFailure.current && FeedbackText.isNetwork(error)) {
        InlineError(FeedbackText.read(error), modifier.testTag("read-unavailable"))
        return
    }
    Column(modifier.fillMaxWidth().testTag("read-unavailable")
        .padding(horizontal = 8.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp).background(MiuixTheme.colorScheme.error.copy(alpha = .06f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(MiuixIcons.Info, null, Modifier.size(26.dp), tint = MiuixTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text(if (pullToRefresh) FeedbackText.read(error) else FeedbackText.inline(error),
            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Region feedback uses the existing inline style; page-wide network errors are shown once. */
@Composable
internal fun ReadNotice(error: String?, cached: Boolean = false, modifier: Modifier = Modifier) {
    if (error.isNullOrBlank()) return
    if (LocalPageNetworkError.current != null && FeedbackText.isNetwork(error)) return
    InlineError(FeedbackText.read(error, cached), modifier.testTag("read-notice").padding(top = 8.dp))
}
