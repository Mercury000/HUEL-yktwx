package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One motion language for read feedback, numbers, new records and chart updates. */
internal object ContentMotion {
    const val Fade = 150
    const val Number = 220
    const val Insert = 260
    const val Append = 180
    const val Chart = 280
    const val Highlight = 650
    const val Layout = 200
    val Easing = FastOutSlowInEasing
}

private val ReadNumberPattern = Regex("-?\\d+(?:\\.\\d+)?")

/** First composition is static. Only a changed value under the same identity transitions. */
@Composable
internal fun UpdatedText(
    text: String, modifier: Modifier = Modifier, fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
    color: Color = MiuixTheme.colorScheme.onSurface, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, prefix: String = "", identity: Any? = Unit,
    numeric: Boolean = true, textAlign: TextAlign = TextAlign.Start,
    fontFamily: FontFamily? = null, fitNumber: Boolean = true,
) {
    val switchActive = LocalContentSwitchActive.current
    if (!LocalReadMotionEnabled.current) {
        Text(prefix + text, modifier, fontSize = fontSize, lineHeight = lineHeight,
            fontWeight = fontWeight, color = color, maxLines = maxLines, overflow = overflow,
            textAlign = textAlign, fontFamily = fontFamily)
        return
    }
    key(identity) {
        if (!numeric) {
            ContentFade(text, modifier,
                animateSize = maxLines > 1) { displayed ->
                Text(prefix + displayed, modifier = if (displayed != text) Modifier.clearAndSetSemantics {} else Modifier,
                    fontSize = fontSize, lineHeight = lineHeight,
                    fontWeight = fontWeight, color = color, maxLines = maxLines,
                    overflow = overflow, textAlign = textAlign, fontFamily = fontFamily)
            }
        } else {
            val resize = maxLines > 1 && !LocalReadLayoutManaged.current
            val preferredSize = fontSize.takeUnless { it == TextUnit.Unspecified } ?: MiuixTheme.textStyles.main.fontSize
            val numberSize = remember(preferredSize) { WholeNumberAutoSize(preferredSize) }
            Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(prefix + text) },
                horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start) {
                if (prefix.isNotEmpty()) Text(prefix, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, color = color)
                val distance = with(LocalDensity.current) { 6.dp.roundToPx() }
                AnimatedContent(targetState = text, modifier = Modifier.weight(1f, fill = false), transitionSpec = {
                    val before = ReadNumberPattern.findAll(initialState).lastOrNull()?.value?.toBigDecimalOrNull()
                    val after = ReadNumberPattern.findAll(targetState).lastOrNull()?.value?.toBigDecimalOrNull()
                    val direction = if (before != null && after != null && after < before) -1 else 1
                    val duration = ContentMotion.Number
                    val enter = fadeIn(tween(duration, easing = ContentMotion.Easing)) +
                        slideInVertically(tween(duration, easing = ContentMotion.Easing)) { if (before != null && after != null) distance * direction else 0 }
                    val exit = fadeOut(tween(duration, easing = ContentMotion.Easing)) +
                        slideOutVertically(tween(duration, easing = ContentMotion.Easing)) { if (before != null && after != null) -distance * direction else 0 }
                    (if (switchActive) EnterTransition.None togetherWith ExitTransition.None
                    else enter togetherWith exit).using(if (resize && !switchActive) SizeTransform { _, _ ->
                        tween(ContentMotion.Layout, easing = ContentMotion.Easing)
                    } else null)
                }, label = "UpdatedText") { displayed ->
                    val fitting = fitNumber && displayed.matches(ReadNumberPattern)
                    Text(displayed, modifier = if (displayed != text) Modifier.clearAndSetSemantics {} else Modifier,
                        fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
                        autoSize = if (fitting) numberSize else null,
                        color = color, maxLines = if (fitting) 1 else maxLines, softWrap = !fitting,
                        overflow = overflow, fontFamily = fontFamily,
                        textAlign = textAlign)
                }
            }
        }
    }
}

/** Fit whole single-line labels using the same measured sizing as read-only numbers. */
@Composable
internal fun rememberSingleLineTextAutoSize(preferredSize: TextUnit): TextAutoSize =
    remember(preferredSize) { WholeNumberAutoSize(preferredSize) }

/** Keep signs and decimal digits together; fitting happens during text measurement, before paint. */
private data class WholeNumberAutoSize(val preferredSize: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        if (!constraints.hasBoundedWidth || constraints.maxWidth == 0) return preferredSize
        val natural = performLayout(Constraints(), text, preferredSize)
        val availableWidth = (constraints.maxWidth - 1).coerceAtLeast(1).toFloat()
        val ratio = (availableWidth / natural.size.width.coerceAtLeast(1)).coerceAtMost(1f)
        val candidate = preferredSize * ratio
        fun TextLayoutResult.fits() = lineCount == 1 && getLineEnd(0) == text.length &&
            getLineRight(0) <= availableWidth && !didOverflowWidth
        // Font hinting and font scale need not be proportional. Verify the final
        // constraints, including the final character, rather than trusting the ratio.
        if (performLayout(constraints, text, candidate).fits()) return candidate
        var low = 0f
        var high = ratio
        repeat(10) {
            val middle = (low + high) / 2
            if (performLayout(constraints, text, preferredSize * middle).fits()) low = middle
            else high = middle
        }
        val fitted = preferredSize * low
        // Leave the layout cache at the chosen size and the actual constraints.
        performLayout(constraints, text, fitted)
        return fitted
    }
}

/** Carries cache restoration through nested transitions without rebuilding the page. */
internal val LocalReadSnapshotRestored = staticCompositionLocalOf { false }
/** Hidden geometry templates need text metrics, never transitions or number fitting. */
internal val LocalReadMotionEnabled = staticCompositionLocalOf { true }
/** A tab switch owns paint; response-driven children must not add another entrance animation. */
internal val LocalContentSwitchActive = staticCompositionLocalOf { false }
private val LocalReadLayoutManaged = staticCompositionLocalOf { false }
private val LocalReadSkeletonManaged = staticCompositionLocalOf { false }
internal class ReadLayoutMotion { var feedbackTransitions by mutableIntStateOf(0) }
internal val LocalReadLayoutMotion = staticCompositionLocalOf<ReadLayoutMotion?> { null }

private data class ContentFadeTarget<T>(val value: T, val restored: Boolean)

/** In-place layout changes use the same duration as skeleton/content replacement. */
@Composable
internal fun Modifier.readContentSize(animate: Boolean = true): Modifier {
    val restored = LocalReadSnapshotRestored.current
    var wasRestored by remember { mutableStateOf(restored) }
    val restoring = restored && !wasRestored
    SideEffect { wasRestored = restored }
    return animateContentSize(if (restoring || !animate) snap() else tween(ContentMotion.Layout, easing = ContentMotion.Easing))
}

/** One ancestor owns geometry; children still fade without adding a second size animation. */
@Composable
internal fun ReadLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val owner = LocalReadLayoutMotion.current ?: remember { ReadLayoutMotion() }
    // While a notice expands, use its measured frames directly instead of chasing them
    // with another height animation. Other response-driven layout changes still animate.
    val sized = if (LocalReadLayoutManaged.current || !LocalReadMotionEnabled.current) modifier else modifier.readContentSize(animate = owner.feedbackTransitions == 0)
    Box(sized) {
        CompositionLocalProvider(LocalReadLayoutManaged provides true, LocalReadLayoutMotion provides owner) { content() }
    }
}

/** Shared skeleton/empty/content transition; cached content does not replay entry on navigation. */
@Composable
internal fun <T> ContentFade(state: T, modifier: Modifier = Modifier, contentKey: (T) -> Any? = { it },
    animate: Boolean = true, animateSize: Boolean = false, content: @Composable (T) -> Unit) {
    val switchActive = LocalContentSwitchActive.current
    val resize = animateSize && !LocalReadLayoutManaged.current
    AnimatedContent(targetState = ContentFadeTarget(state, LocalReadSnapshotRestored.current),
        modifier = modifier, transitionSpec = {
        // A newly restored snapshot replaces pending paint immediately. Keep the
        // composition alive so controls and their selection/scroll state survive.
        val restoring = targetState.restored && !initialState.restored
        (if (animate && !restoring && !switchActive) fadeIn(tween(ContentMotion.Fade)) togetherWith fadeOut(tween(ContentMotion.Fade))
        else EnterTransition.None togetherWith ExitTransition.None).using(
            if (animate && !restoring && !switchActive && resize) SizeTransform { _, _ ->
                tween(ContentMotion.Layout, easing = ContentMotion.Easing)
            } else null,
        )
    }, label = "ContentFade", contentKey = { contentKey(it.value) }) {
        CompositionLocalProvider(LocalReadLayoutManaged provides (LocalReadLayoutManaged.current || resize)) { content(it.value) }
    }
}

/** Keep pending paint alive outside the transition so outgoing skeletons never restart their delay. */
@Composable
internal fun <T> ReadContent(state: T, modifier: Modifier = Modifier,
    pending: (T) -> Boolean, contentKey: (T) -> Any? = { it }, animateSize: Boolean = true,
    content: @Composable (T) -> Unit) {
    val skeletonPaint = delayedSkeletonModifier(active = pending(state),
        enabled = !LocalReadSkeletonManaged.current && !LocalReadSkeletonImmediate.current)
    ContentFade(state, modifier, contentKey, animateSize = animateSize) { displayed ->
        CompositionLocalProvider(LocalSkeletonDelayEnabled provides false,
            LocalReadSkeletonManaged provides (LocalReadSkeletonManaged.current || pending(displayed))) {
            val pendingContent = pending(displayed)
            val paint = if (pendingContent) skeletonPaint else Modifier
            // Outgoing paint participates in the fade but no longer announces loading.
            val semantics = if (pendingContent && !pending(state)) Modifier.clearAndSetSemantics {} else Modifier
            Box(paint.then(semantics)) { content(displayed) }
        }
    }
}

/** Unknown values keep one line; real text wraps at its final width using the shared motion. */
@Composable
internal fun ReadText(text: String?, modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified, lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null, color: Color = MiuixTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start, fraction: Float = .6f, sample: String = "读取") {
    ReadContent(text, modifier, pending = { it == null }) { displayed ->
        if (displayed == null) DashboardPlaceholder(fontSize, Modifier.fillMaxWidth(),
            fraction = fraction, lineHeight = lineHeight, sample = sample)
        else Text(displayed, Modifier.fillMaxWidth(), fontSize = fontSize, lineHeight = lineHeight,
            fontWeight = fontWeight, color = color, textAlign = textAlign)
    }
}

/** A short-lived insertion event, never an animation triggered by merely scrolling into view. */
@Composable
internal fun recordArrivalModifier(event: Long, animate: Boolean, appended: Boolean, index: Int): Modifier {
    val opacity = remember(event) { Animatable(if (animate) 0f else 1f) }
    val lift = remember(event) { Animatable(if (animate && !appended) 8f else 0f) }
    val tint = remember(event) { Animatable(if (animate && !appended) .075f else 0f) }
    LaunchedEffect(event, animate) {
        if (!animate || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
            opacity.snapTo(1f); lift.snapTo(0f); tint.snapTo(0f); return@LaunchedEffect
        }
        if (!appended) delay(index.coerceAtMost(2) * 35L)
        kotlinx.coroutines.coroutineScope {
            launch { opacity.animateTo(1f, tween(if (appended) ContentMotion.Append else ContentMotion.Insert, easing = ContentMotion.Easing)) }
            launch { lift.animateTo(0f, tween(ContentMotion.Insert, easing = ContentMotion.Easing)) }
            launch { tint.animateTo(0f, tween(ContentMotion.Highlight)) }
        }
    }
    val density = LocalDensity.current.density
    return Modifier.graphicsLayer { alpha = opacity.value; translationY = lift.value * density }
        .background(MiuixTheme.colorScheme.primary.copy(alpha = tint.value))
}

/** Interrupted refreshes begin from the currently displayed geometry, not an obsolete target. */
@Composable
internal fun <K> rememberChartValues(values: Map<K, Double>): Map<K, Double> {
    val switchActive = LocalContentSwitchActive.current
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(values) }
    var target by remember { mutableStateOf(values) }
    fun sampled(): Map<K, Double> = (from.keys + target.keys).associateWith {
        val start = from[it] ?: 0.0
        start + ((target[it] ?: 0.0) - start) * progress.value
    }
    LaunchedEffect(values, switchActive) {
        if (values != target) {
            from = sampled()
            target = values
            progress.snapTo(0f)
            if (switchActive) progress.snapTo(1f)
            else progress.animateTo(1f, tween(ContentMotion.Chart, easing = ContentMotion.Easing))
            from = target
        } else if (switchActive) {
            progress.snapTo(1f)
            from = target
        }
    }
    return sampled()
}
