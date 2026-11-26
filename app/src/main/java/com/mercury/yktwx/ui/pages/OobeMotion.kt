package com.mercury.yktwx.ui.pages

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class OobeStep { Welcome, Privacy, Accounts, Appearance, Complete }

private val PageEase = Easing { ((1.0 - cos(PI * it)) * .5).toFloat() }
internal val OobeSineInOut = CubicBezierEasing(.37f, 0f, .63f, 1f)
private val SineOut = Easing { sin(PI * it / 2).toFloat() }
private val CubicOut = Easing { 1f - (1f - it) * (1f - it) * (1f - it) }
private val QuartOut = Easing { val rest = 1f - it; 1f - rest * rest * rest * rest }

@Composable
internal fun OobePages(step: OobeStep, active: Boolean = true,
    content: @Composable (OobeStep, Boolean, (Rect) -> Unit) -> Unit) {
    val transition = updateTransition(step, label = "oobeStep")
    var root by remember { mutableStateOf(Offset.Zero) }
    var startBounds by remember { mutableStateOf<Rect?>(null) }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val expands = transition.currentState == OobeStep.Welcome && step == OobeStep.Privacy && startBounds != null
    val expansion by transition.animateFloat(transitionSpec = { tween(350, easing = PageEase) }, label = "oobeExpand") {
        if (it == OobeStep.Welcome) 0f else 1f
    }
    transition.AnimatedContent(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { root = it.positionInRoot() },
        transitionSpec = {
            if (initialState == OobeStep.Welcome && targetState == OobeStep.Privacy && startBounds != null) {
                (fadeIn(tween(230, easing = OobeSineInOut)) togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
            } else {
                val side = if (targetState > initialState) direction else -direction
                (slideInHorizontally(tween(350, easing = PageEase)) { side * it } togetherWith
                    slideOutHorizontally(tween(350, easing = PageEase)) { -side * it }).using(null)
            }
        }) { page ->
        val interactive = active && page == step
        val reveal = Modifier.graphicsLayer {
            val bounds = startBounds
            clip = page == OobeStep.Privacy && expands && bounds != null
            if (clip && bounds != null) {
                val from = bounds.translate(-root)
                val fraction = expansion
                val radius = lerp(from.height / 2, 0f, fraction)
                shape = GenericShape { finalSize, _ -> addRoundRect(RoundRect(
                    Rect(lerp(from.left, 0f, fraction), lerp(from.top, 0f, fraction),
                        lerp(from.right, finalSize.width, fraction), lerp(from.bottom, finalSize.height, fraction)),
                    CornerRadius(radius))) }
            }
        }
        Box(Modifier.fillMaxSize().oobeInteraction(interactive).then(reveal)) {
            content(page, interactive) { startBounds = it }
        }
    }
}

@Composable
internal fun OobeExit(showSetup: Boolean, home: @Composable () -> Unit, setup: @Composable (Boolean) -> Unit) {
    val transition = updateTransition(showSetup, label = "oobeExit")
    val alpha by transition.animateFloat(transitionSpec = { tween(230, 60, OobeSineInOut) }, label = "oobeHomeAlpha") {
        if (it) 0f else 1f
    }
    val scale by transition.animateFloat(transitionSpec = { tween(619, easing = HomeSpring) }, label = "oobeHomeScale") {
        if (it) 1.3f else 1f
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (!showSetup) Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }) { home() }
        transition.AnimatedVisibility(visible = { it }, enter = EnterTransition.None,
            exit = fadeOut(tween(360, easing = OobeSineInOut))) {
            Box(Modifier.fillMaxSize().oobeInteraction(showSetup)) { setup(showSetup) }
        }
    }
}

private val HomeSpring = Easing { fraction ->
    val damping = .65
    val angular = 2 * PI / .55
    val decay = angular * damping
    val frequency = angular * sqrt(1 - damping * damping)
    val time = .619 * fraction
    if (fraction == 1f) 1f else (1 - exp(-decay * time) * (cos(frequency * time) + decay / frequency * sin(frequency * time))).toFloat()
}

internal fun Modifier.oobeInteraction(active: Boolean): Modifier = if (active) this else
    clearAndSetSemantics {}.focusProperties { canFocus = false }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
internal fun Modifier.oobeLogoEntrance(complete: Boolean): Modifier {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val opacity by animateFloatAsState(if (revealed) 1f else 0f,
        tween(if (complete) 1500 else 440, if (complete) 0 else 60, if (complete) QuartOut else SineOut), label = "oobeLogoAlpha")
    val progress by animateFloatAsState(if (revealed) 1f else 0f,
        tween(1500, easing = QuartOut), label = "oobeLogoRise")
    val scale by animateFloatAsState(if (revealed) 1f else .5f, keyframes {
        durationMillis = 1140
        .5f at 0 using SineOut
        .95f at 440 using CubicOut
    }, label = "oobeLogoScale")
    return graphicsLayer {
        alpha = opacity
        if (complete) translationY = 100f * (1f - progress)
        else { scaleX = scale; scaleY = scale }
    }
}

@Composable
internal fun Modifier.oobeButtonEntrance(complete: Boolean): Modifier {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val opacity by animateFloatAsState(if (revealed) 1f else 0f,
        tween(450, if (complete) 1000 else 1340, if (complete) SineOut else CubicOut), label = "oobeButtonAlpha")
    return graphicsLayer {
        alpha = opacity
        if (!complete) { scaleX = .9f + .1f * opacity; scaleY = scaleX }
    }
}
