package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/** One gesture and fling traverse the controls and body; only the whole page owns overscroll. */
internal class BillPageScroll {
    var controlsOffset by mutableFloatStateOf(0f)
        private set
    var typeOffset by mutableFloatStateOf(0f)
        private set
    var controlsHeight by mutableIntStateOf(0)
        private set
    var typeHeight by mutableIntStateOf(0)
        private set
    var statistics by mutableStateOf(false)
    var inputEnabled: () -> Boolean = { true }
    var body by mutableStateOf<LazyListState?>(null)

    val position: Float get() = controlsOffset + (if (statistics) typeOffset else 0f) +
        (body?.let { it.firstVisibleItemIndex * 500f + it.firstVisibleItemScrollOffset } ?: 0f)

    fun measureControls(height: Int) {
        if (height == controlsHeight) return
        controlsOffset = if (controlsHeight > 0 && controlsOffset >= controlsHeight) height.toFloat()
            else controlsOffset.coerceAtMost(height.toFloat())
        controlsHeight = height
    }

    fun measureType(height: Int) {
        if (height == typeHeight) return
        typeOffset = if (typeHeight > 0 && typeOffset >= typeHeight) height.toFloat()
            else typeOffset.coerceAtMost(height.toFloat())
        typeHeight = height
    }

    private val gestures = ScrollableState(::consume)
    val state: ScrollableState = object : ScrollableState by gestures {
        override val canScrollForward: Boolean get() = inputEnabled() && (controlsOffset < controlsHeight ||
            statistics && typeOffset < typeHeight || body?.canScrollForward == true)
        override val canScrollBackward: Boolean get() = inputEnabled() && (controlsOffset > 0f ||
            statistics && typeOffset > 0f || body?.canScrollBackward == true)
    }

    private fun consume(delta: Float): Float {
        if (!inputEnabled()) return 0f
        var remaining = delta
        if (delta > 0f) {
            val controls = remaining.coerceAtMost((controlsHeight - controlsOffset).coerceAtLeast(0f))
            controlsOffset += controls
            remaining -= controls
            if (statistics) {
                val type = remaining.coerceAtMost((typeHeight - typeOffset).coerceAtLeast(0f))
                typeOffset += type
                remaining -= type
            }
            remaining -= body?.dispatchRawDelta(remaining) ?: 0f
        } else if (delta < 0f) {
            remaining -= body?.dispatchRawDelta(remaining) ?: 0f
            if (statistics) {
                val type = remaining.coerceAtLeast(-typeOffset)
                typeOffset += type
                remaining -= type
            }
            val controls = remaining.coerceAtLeast(-controlsOffset)
            controlsOffset += controls
            remaining -= controls
        }
        return delta - remaining
    }

    companion object {
        val Saver = listSaver<BillPageScroll, Float>(
            save = { listOf(it.controlsOffset, it.typeOffset) },
            restore = { offsets -> BillPageScroll().apply {
                controlsOffset = offsets[0]
                typeOffset = offsets[1]
            } },
        )
    }
}

@Composable
internal fun rememberBillPageScroll(): BillPageScroll = rememberSaveable(saver = BillPageScroll.Saver) {
    BillPageScroll()
}

/** A passive vertical layer: it has no gesture, fling or spring of its own. */
@Composable
internal fun BillScrollLayer(offset: () -> Float, onHeaderHeight: (Int) -> Unit,
    modifier: Modifier = Modifier, header: @Composable () -> Unit, content: @Composable () -> Unit) {
    Layout(modifier = modifier.fillMaxSize().clipToBounds(), content = {
        Column(Modifier.fillMaxWidth()) { header() }
        Box(Modifier.fillMaxSize()) { content() }
    }) { measurables, constraints ->
        val controls = measurables[0].measure(constraints.copy(minHeight = 0))
        val page = measurables[1].measure(constraints)
        onHeaderHeight(controls.height)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val collapsed = offset().coerceIn(0f, controls.height.toFloat()).roundToInt()
            controls.placeRelative(0, -collapsed)
            page.placeRelative(0, controls.height - collapsed)
        }
    }
}
