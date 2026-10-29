package com.mercury.yktwx.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

private class InputBounds {
    var bounds = Rect.Zero
    var enabled = true
}

private class InputFocusRegistry {
    val inputs = mutableSetOf<InputBounds>()
    var focused: InputBounds? = null

    fun containsInput(position: Offset) = inputs.any { it.enabled && it.bounds.contains(position) }
}

private val LocalInputFocusRegistry = staticCompositionLocalOf<InputFocusRegistry?> { null }

/** Observe down events without consuming clicks, drags, links or field selection. */
@Composable
internal fun InputFocusDismissalHost(content: @Composable () -> Unit) {
    val registry = remember { InputFocusRegistry() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalInputFocusRegistry provides registry) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(registry, focus, keyboard) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val input = registry.focused
                    // A press on another field transfers focus directly; do not close the IME
                    // between its numeric/password sessions or make inset-driven layouts jump.
                    if (input != null && !registry.containsInput(down.position + origin)) {
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                }
            }) { content() }
    }
}

/** Attach before the Miuix TextField's focus target, including its trailing icons. */
internal fun Modifier.trackInputFocus(enabled: Boolean = true): Modifier = composed {
    val registry = LocalInputFocusRegistry.current ?: return@composed this
    val input = remember { InputBounds() }
    SideEffect { input.enabled = enabled }
    DisposableEffect(registry, input) {
        registry.inputs.add(input)
        onDispose {
            registry.inputs.remove(input)
            if (registry.focused === input) registry.focused = null
        }
    }
    onGloballyPositioned { input.bounds = it.boundsInWindow() }
        .onFocusChanged { state ->
            if (state.hasFocus) registry.focused = input
            else if (registry.focused === input) registry.focused = null
        }
}
