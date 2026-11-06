package com.mercury.yktwx.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

internal fun Modifier.deviceScannerZoomGestures(enabled: Boolean, onScale: (Float) -> Unit,
    onQuickZoom: () -> Unit): Modifier =
    pointerInput(enabled, onQuickZoom) {
        if (enabled) detectTapGestures(onDoubleTap = { onQuickZoom() })
    }.pointerInput(enabled, onScale) {
        if (enabled) detectTransformGestures { _, _, factor, _ -> onScale(factor) }
    }
