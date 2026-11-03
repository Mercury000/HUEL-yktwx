package com.mercury.yktwx.ui.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.ui.components.ContentMotion
import com.mercury.yktwx.ui.components.LocalReadMotionEnabled
import kotlin.coroutines.coroutineContext

private enum class BillResultKind { Pending, Error, Empty, Records }

private fun BillUiState.resultKind() = when {
    items.isNotEmpty() -> BillResultKind.Records
    !listLoaded && error == null -> BillResultKind.Pending
    error != null -> BillResultKind.Error
    else -> BillResultKind.Empty
}

/** Only result paint is retained during a query exit; controls always use the current query. */
internal class BillQueryMotion(initial: BillUiState) {
    var displayed by mutableStateOf(initial)
        private set
    val opacity = Animatable(1f)
    var running by mutableStateOf(false)
        private set
    private var queried = false
    var pendingHeight by mutableIntStateOf(0)
        private set

    fun accepts(current: BillUiState) = displayed.readIdentity == current.readIdentity && !running

    fun synchronize(current: BillUiState, list: LazyListState) {
        if (!running && displayed.readIdentity == current.readIdentity && displayed.resultKind() == current.resultKind()) {
            displayed = current
            if (current.resultKind() != BillResultKind.Pending) {
                val layout = list.layoutInfo
                val results = layout.visibleItemsInfo.filter { it.key !in listOf("summary", "search", "preview") }
                if (results.isNotEmpty()) pendingHeight =
                    (minOf(results.maxOf { it.offset + it.size }, layout.viewportEndOffset) -
                        maxOf(results.minOf { it.offset }, layout.viewportStartOffset)).coerceAtLeast(0)
            }
        }
    }

    suspend fun update(latest: () -> BillUiState, animate: Boolean) {
        val current = latest()
        if (!animate || coroutineContext[MotionDurationScale]?.scaleFactor == 0f) {
            displayed = current
            opacity.snapTo(1f)
            running = false
            return
        }
        val changedQuery = displayed.readIdentity != current.readIdentity
        if (!changedQuery && !(queried && displayed.resultKind() != current.resultKind())) {
            displayed = current
            return
        }
        running = true
        queried = true
        try {
            // Interrupted searches continue from the currently painted opacity.
            opacity.animateTo(0f, tween(ContentMotion.Fade, easing = ContentMotion.Easing))
            displayed = latest()
            // Pending paint already owns its 250ms delay and 150ms entrance.
            if (displayed.resultKind() == BillResultKind.Pending) opacity.snapTo(1f)
            else opacity.animateTo(1f, tween(ContentMotion.Fade, easing = ContentMotion.Easing))
        } finally {
            running = false
        }
    }
}

@Composable
internal fun rememberBillQueryMotion(state: BillUiState, list: LazyListState,
    active: Boolean, switching: Boolean): BillQueryMotion {
    val motion = remember(state.dateFilter, state.needsLogin, active, state.statsVisible) { BillQueryMotion(state) }
    val current by rememberUpdatedState(state)
    val animate = LocalReadMotionEnabled.current && active && !state.needsLogin && !state.statsVisible && !switching
    SideEffect { motion.synchronize(state, list) }
    LaunchedEffect(motion, state.readIdentity, state.resultKind(), animate) {
        motion.update({ current }, animate)
    }
    return motion
}

internal fun Modifier.billResultPaint(motion: BillQueryMotion, current: BillUiState): Modifier =
    graphicsLayer { alpha = motion.opacity.value }
        .then(if (motion.accepts(current)) Modifier else Modifier.clearAndSetSemantics {})
