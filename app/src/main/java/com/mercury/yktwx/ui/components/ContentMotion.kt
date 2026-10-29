package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
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

/** First composition is static. Only a changed value under the same identity transitions. */
@Composable
internal fun UpdatedText(
    text: String, modifier: Modifier = Modifier, fontSize: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
    color: Color = MiuixTheme.colorScheme.onSurface, maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, prefix: String = "", identity: Any? = Unit,
    numeric: Boolean = true, textAlign: TextAlign = TextAlign.Start,
) {
    key(identity) {
        Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(prefix + text) },
            horizontalArrangement = if (textAlign == TextAlign.End) Arrangement.End else Arrangement.Start) {
            if (prefix.isNotEmpty()) Text(prefix, fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight, color = color)
            val distance = with(LocalDensity.current) { 6.dp.roundToPx() }
            AnimatedContent(targetState = text, transitionSpec = {
                val before = Regex("-?\\d+(?:\\.\\d+)?").findAll(initialState).lastOrNull()?.value?.toBigDecimalOrNull()
                val after = Regex("-?\\d+(?:\\.\\d+)?").findAll(targetState).lastOrNull()?.value?.toBigDecimalOrNull()
                val direction = if (before != null && after != null && after < before) -1 else 1
                val duration = if (numeric) ContentMotion.Number else ContentMotion.Fade
                val enter = fadeIn(tween(duration, easing = ContentMotion.Easing)) +
                    slideInVertically(tween(duration, easing = ContentMotion.Easing)) { if (numeric && before != null && after != null) distance * direction else 0 }
                val exit = fadeOut(tween(duration, easing = ContentMotion.Easing)) +
                    slideOutVertically(tween(duration, easing = ContentMotion.Easing)) { if (numeric && before != null && after != null) -distance * direction else 0 }
                (enter togetherWith exit).using(null)
            }, label = "UpdatedText") { displayed ->
                Text(displayed, modifier = if (displayed != text) Modifier.clearAndSetSemantics {} else Modifier,
                    fontSize = fontSize, lineHeight = lineHeight, fontWeight = fontWeight,
                    color = color, maxLines = maxLines, overflow = overflow)
            }
        }
    }
}

/** Shared skeleton/empty/content transition; cached content does not replay entry on navigation. */
@Composable
internal fun <T> ContentFade(state: T, modifier: Modifier = Modifier, contentKey: (T) -> Any? = { it },
    animate: Boolean = true, content: @Composable (T) -> Unit) {
    AnimatedContent(targetState = state, modifier = modifier, transitionSpec = {
        (if (animate) fadeIn(tween(ContentMotion.Fade)) togetherWith fadeOut(tween(ContentMotion.Fade))
        else EnterTransition.None togetherWith ExitTransition.None).using(null)
    }, label = "ContentFade", contentKey = contentKey, content = { content(it) })
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
    val progress = remember { Animatable(1f) }
    var from by remember { mutableStateOf(values) }
    var target by remember { mutableStateOf(values) }
    fun sampled(): Map<K, Double> = (from.keys + target.keys).associateWith {
        val start = from[it] ?: 0.0
        start + ((target[it] ?: 0.0) - start) * progress.value
    }
    LaunchedEffect(values) {
        if (values != target) {
            from = sampled()
            target = values
            progress.snapTo(0f)
            progress.animateTo(1f, tween(ContentMotion.Chart, easing = ContentMotion.Easing))
            from = target
        }
    }
    return sampled()
}
