package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val SheetPageDuration = 320

/** Retain display values during exit; never pass passwords, captcha keys or payment preparations. */
@Composable
internal fun <T> rememberWhileShown(shown: Boolean, value: T): T {
    var displayed by remember { mutableStateOf(value) }
    if (shown) SideEffect { displayed = value }
    return if (shown) value else displayed
}

/** A new opening starts at its requested page, including when the previous exit is unfinished. */
@Composable
internal fun rememberOverlaySession(shown: Boolean): Any {
    var wasShown by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf(Any()) }
    if (shown && !wasShown) session = Any()
    wasShown = shown
    return session
}

internal fun Modifier.sheetContentSize(): Modifier = animateContentSize(
    animationSpec = tween(SheetPageDuration, easing = FastOutSlowInEasing),
)

/** Invoke inside the sheet's title slot so the popup owns the transition. */
@Composable
internal fun SheetTitle(title: String?) {
    AnimatedContent(targetState = title, modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
        transitionSpec = {
            (fadeIn(tween(240)) togetherWith fadeOut(tween(180)))
                .using(SizeTransform { _, _ -> tween(SheetPageDuration, easing = FastOutSlowInEasing) })
        }, label = "SheetTitle") { text ->
        if (text != null) Text(text, modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.title4.fontSize,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** Animate route enums only; never keep credentials or payment models as transition targets.
 * Loading and ready content must share a route so refreshes replace their placeholders in place.
 * This must be composed inside the popup, alongside its measurement and animation clock.
 * The outgoing page remains visible briefly, but must no longer accept actions.
 */
@Composable
internal fun <T : Enum<T>> SheetPageContent(targetPage: T, modifier: Modifier = Modifier,
    content: @Composable (page: T, interactive: Boolean) -> Unit) {
    val distance = with(LocalDensity.current) { 24.dp.roundToPx() }
    AnimatedContent(targetState = targetPage, modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            ((fadeIn(tween(220)) +
                slideInHorizontally(tween(SheetPageDuration, easing = FastOutSlowInEasing)) { direction * distance }) togetherWith
                (fadeOut(tween(180)) +
                    slideOutHorizontally(tween(SheetPageDuration, easing = FastOutSlowInEasing)) { -direction * distance }))
                .using(SizeTransform { _, _ -> tween(SheetPageDuration, easing = FastOutSlowInEasing) })
        }, label = "SheetPage") { page -> content(page, page == targetPage) }
}
