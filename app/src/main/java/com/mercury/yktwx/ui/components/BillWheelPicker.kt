// Adapted from compose-miuix-ui NumberPicker, Apache-2.0.
package com.mercury.yktwx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

@Composable
internal fun BillWheelPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    selectableRange: IntRange,
    displayRange: IntRange,
    modifier: Modifier = Modifier,
    label: (Int) -> String,
    visibleItemCount: Int = 5,
    selectedColor: Color = MiuixTheme.colorScheme.primary,
    unselectedColor: Color = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    textStyle: TextStyle = MiuixTheme.textStyles.title1,
    itemHeight: Dp = 45.dp,
) {
    require(visibleItemCount % 2 == 1 && visibleItemCount >= 3)
    require(selectableRange.first >= displayRange.first && selectableRange.last <= displayRange.last)
    require(value in selectableRange)

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentIndex = value - displayRange.first
    val minIndex = selectableRange.first - displayRange.first
    val maxIndex = selectableRange.last - displayRange.first
    val minOffset = (minIndex - currentIndex).toFloat()
    val maxOffset = (maxIndex - currentIndex).toFloat()
    val halfVisibleCount = visibleItemCount / 2
    val totalHeight = itemHeight * visibleItemCount

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val flingOffset = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    val totalOffset by remember { derivedStateOf { dragOffset + flingOffset.value } }

    LaunchedEffect(value) {
        if (!isDragging && dragOffset == 0f) flingOffset.snapTo(0f)
    }

    val draggableState = rememberDraggableState { delta ->
        if (itemHeightPx > 0) {
            dragOffset = rubberBand(
                value = dragOffset - delta / itemHeightPx,
                minimum = minOffset,
                maximum = maxOffset,
            )
        }
    }
    val resolvedTextStyle = if (textStyle.fontWeight == null) {
        textStyle.copy(fontWeight = FontWeight.SemiBold)
    } else {
        textStyle
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .clipToBounds()
            .semantics {
                contentDescription =
                    "${label(value)}, ${selectableRange.first} - ${selectableRange.last}"
            }
            .onSizeChanged { itemHeightPx = it.height / visibleItemCount }
            .draggable(
                orientation = Orientation.Vertical,
                state = draggableState,
                onDragStarted = {
                    flingOffset.stop()
                    dragOffset += flingOffset.value
                    flingOffset.snapTo(0f)
                    isDragging = true
                },
                onDragStopped = { velocity ->
                    isDragging = false
                    if (itemHeightPx > 0) {
                        val currentDragOffset = dragOffset
                        dragOffset = 0f
                        flingOffset.snapTo(currentDragOffset)
                        if (currentDragOffset < minOffset) {
                            flingOffset.animateTo(
                                targetValue = minOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                            )
                            if (value != selectableRange.first) {
                                currentOnValueChange(selectableRange.first)
                            }
                        } else if (currentDragOffset > maxOffset) {
                            flingOffset.animateTo(
                                targetValue = maxOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                            )
                            if (value != selectableRange.last) {
                                currentOnValueChange(selectableRange.last)
                            }
                        } else {
                            val velocityInItems = -velocity / itemHeightPx
                            flingOffset.updateBounds(minOffset, maxOffset)
                            flingOffset.animateDecay(velocityInItems, exponentialDecay(frictionMultiplier = 2f))
                            val snapped = flingOffset.value
                                .fastRoundToInt()
                                .toFloat()
                                .coerceIn(minOffset, maxOffset)
                            flingOffset.animateTo(snapped, spring(dampingRatio = 1f, stiffness = 400f))
                            val newIndex = (currentIndex + flingOffset.value.fastRoundToInt()).coerceIn(minIndex, maxIndex)
                            val newValue = displayRange.first + newIndex
                            if (newValue != value) currentOnValueChange(newValue)
                            flingOffset.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                        }
                        flingOffset.snapTo(0f)
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (itemHeightPx > 0) {
            val offset = totalOffset
            val centerOffset = offset - offset.fastRoundToInt()
            val roundedOffset = offset.fastRoundToInt()
            for (i in -halfVisibleCount - 1..halfVisibleCount + 1) {
                val itemIndex = currentIndex + i + roundedOffset
                if (itemIndex !in 0 until displayRange.count()) continue
                val displayValue = displayRange.first + itemIndex
                val distance = i.toFloat() - centerOffset
                val normalized = (abs(distance) / (halfVisibleCount + 0.5f)).coerceIn(0f, 1f)
                val alpha = (1f - normalized) * (1f - normalized * 0.5f)
                val scale = 1f - 0.2f * normalized
                Text(
                    text = label(displayValue),
                    modifier = Modifier.graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        translationY = distance * itemHeightPx
                    },
                    style = resolvedTextStyle,
                    color = Color(
                        red = lerp(selectedColor.red, unselectedColor.red, normalized),
                        green = lerp(selectedColor.green, unselectedColor.green, normalized),
                        blue = lerp(selectedColor.blue, unselectedColor.blue, normalized),
                        alpha = lerp(selectedColor.alpha, unselectedColor.alpha, normalized),
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun rubberBand(value: Float, minimum: Float, maximum: Float): Float = when {
    value < minimum -> minimum - edgeResistance(minimum - value)
    value > maximum -> maximum + edgeResistance(value - maximum)
    else -> value
}

private fun edgeResistance(distance: Float): Float =
    MAX_EDGE_OFFSET * (1f - kotlin.math.exp(-distance / EDGE_DISTANCE_SCALE))

private const val MAX_EDGE_OFFSET = 0.42f
private const val EDGE_DISTANCE_SCALE = 0.55f
