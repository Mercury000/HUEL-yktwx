// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
//
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    enabled: Boolean = true,
    label: (Int) -> String = { it.toString() },
    visibleItemCount: Int = 5,
    selectedColor: Color = MiuixTheme.colorScheme.primary,
    unselectedColor: Color = MiuixTheme.colorScheme.primary.copy(alpha = 0.55f),
    disabledSelectedColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
    disabledUnselectedColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
    wrapAround: Boolean = false,
    textStyle: TextStyle = MiuixTheme.textStyles.title1,
    itemHeight: Dp = 45.dp,
) {
    require(visibleItemCount % 2 == 1 && visibleItemCount >= 3) {
        "visibleItemCount must be odd and at least 3, but was $visibleItemCount"
    }
    require(selectableRange.first <= selectableRange.last) {
        "selectableRange must not be empty"
    }
    require(selectableRange.first >= displayRange.first && selectableRange.last <= displayRange.last) {
        "selectableRange must be contained in displayRange"
    }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val coercedValue = value.coerceIn(selectableRange)
    val currentIndex = coercedValue - displayRange.first
    val minIndex = selectableRange.first - displayRange.first
    val maxIndex = selectableRange.last - displayRange.first
    val selectableCount = selectableRange.last - selectableRange.first + 1
    val minOffset = (minIndex - currentIndex).toFloat()
    val maxOffset = (maxIndex - currentIndex).toFloat()
    val halfVisibleCount = visibleItemCount / 2
    val totalHeight = itemHeight * visibleItemCount
    val hapticFeedback = LocalHapticFeedback.current

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val flingOffset = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isUserScrolling by remember { mutableStateOf(false) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    val totalOffset by remember { derivedStateOf { dragOffset + flingOffset.value } }

    LaunchedEffect(coercedValue) {
        if (!isDragging && dragOffset == 0f) flingOffset.snapTo(0f)
    }

    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestMinIndex by rememberUpdatedState(minIndex)
    val latestMaxIndex by rememberUpdatedState(maxIndex)
    val latestSelectableCount by rememberUpdatedState(selectableCount)
    val latestWrapAround by rememberUpdatedState(wrapAround)
    val effectiveIndex by remember {
        derivedStateOf {
            val rawIndex = latestCurrentIndex + totalOffset.fastRoundToInt()
            if (latestWrapAround) {
                wrapIndex(rawIndex, latestMinIndex, latestSelectableCount)
            } else {
                rawIndex.coerceIn(latestMinIndex, latestMaxIndex)
            }
        }
    }
    var lastHapticIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { effectiveIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index != lastHapticIndex) {
                    if (isUserScrolling) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastHapticIndex = index
                }
            }
    }

    val draggableState = rememberDraggableState { delta ->
        if (itemHeightPx > 0) {
            val newOffset = dragOffset - delta / itemHeightPx
            dragOffset = if (wrapAround) {
                newOffset
            } else {
                rubberBand(
                    value = newOffset,
                    minimum = minOffset,
                    maximum = maxOffset,
                )
            }
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
                    "${label(coercedValue)}, ${selectableRange.first} - ${selectableRange.last}"
            }
            .onSizeChanged { itemHeightPx = it.height / visibleItemCount }
            .draggable(
                orientation = Orientation.Vertical,
                enabled = enabled,
                state = draggableState,
                onDragStarted = {
                    flingOffset.stop()
                    dragOffset += flingOffset.value
                    flingOffset.snapTo(0f)
                    isDragging = true
                    isUserScrolling = true
                },
                onDragStopped = { velocity ->
                    isDragging = false
                    if (itemHeightPx > 0) {
                        val currentDragOffset = dragOffset
                        dragOffset = 0f
                        flingOffset.snapTo(currentDragOffset)
                        if (wrapAround) {
                            flingOffset.updateBounds(
                                Float.NEGATIVE_INFINITY,
                                Float.POSITIVE_INFINITY,
                            )
                            val velocityInItems = -velocity / itemHeightPx
                            flingOffset.animateDecay(
                                velocityInItems,
                                exponentialDecay(frictionMultiplier = 2f),
                            )
                            val snapped = flingOffset.value
                                .fastRoundToInt()
                                .toFloat()
                            flingOffset.animateTo(
                                snapped,
                                spring(dampingRatio = 1f, stiffness = 400f),
                            )
                            val newIndex = wrapIndex(
                                index = currentIndex + flingOffset.value.fastRoundToInt(),
                                minIndex = minIndex,
                                count = selectableCount,
                            )
                            val newValue = displayRange.first + newIndex
                            if (newValue != coercedValue) currentOnValueChange(newValue)
                        } else if (currentDragOffset < minOffset) {
                            flingOffset.animateTo(
                                targetValue = minOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                            )
                            if (coercedValue != selectableRange.first) {
                                currentOnValueChange(selectableRange.first)
                            }
                        } else if (currentDragOffset > maxOffset) {
                            flingOffset.animateTo(
                                targetValue = maxOffset,
                                animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                            )
                            if (coercedValue != selectableRange.last) {
                                currentOnValueChange(selectableRange.last)
                            }
                        } else {
                            val velocityInItems = -velocity / itemHeightPx
                            flingOffset.updateBounds(minOffset, maxOffset)
                            flingOffset.animateDecay(
                                velocityInItems,
                                exponentialDecay(frictionMultiplier = 2f),
                            )
                            val snapped = flingOffset.value
                                .fastRoundToInt()
                                .toFloat()
                                .coerceIn(minOffset, maxOffset)
                            flingOffset.animateTo(
                                snapped,
                                spring(dampingRatio = 1f, stiffness = 400f),
                            )
                            val newIndex = (currentIndex + flingOffset.value.fastRoundToInt())
                                .coerceIn(minIndex, maxIndex)
                            val newValue = displayRange.first + newIndex
                            if (newValue != coercedValue) currentOnValueChange(newValue)
                            flingOffset.updateBounds(
                                Float.NEGATIVE_INFINITY,
                                Float.POSITIVE_INFINITY,
                            )
                        }
                        flingOffset.snapTo(0f)
                    }
                    isUserScrolling = false
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (itemHeightPx > 0) {
            val offset = totalOffset
            val centerOffset = offset - offset.fastRoundToInt()
            val roundedOffset = offset.fastRoundToInt()
            val resolvedSelectedColor = if (enabled) selectedColor else disabledSelectedColor
            val resolvedUnselectedColor = if (enabled) unselectedColor else disabledUnselectedColor
            for (i in -halfVisibleCount - 1..halfVisibleCount + 1) {
                val rawItemIndex = currentIndex + i + roundedOffset
                val itemIndex = if (wrapAround) {
                    wrapIndex(rawItemIndex, minIndex, selectableCount)
                } else {
                    if (rawItemIndex !in 0 until displayRange.count()) continue
                    rawItemIndex
                }
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
                        red = lerp(resolvedSelectedColor.red, resolvedUnselectedColor.red, normalized),
                        green = lerp(resolvedSelectedColor.green, resolvedUnselectedColor.green, normalized),
                        blue = lerp(resolvedSelectedColor.blue, resolvedUnselectedColor.blue, normalized),
                        alpha = lerp(resolvedSelectedColor.alpha, resolvedUnselectedColor.alpha, normalized),
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun wrapIndex(index: Int, minIndex: Int, count: Int): Int =
    ((index - minIndex) % count + count) % count + minIndex

private fun rubberBand(value: Float, minimum: Float, maximum: Float): Float = when {
    value < minimum -> minimum - edgeResistance(minimum - value)
    value > maximum -> maximum + edgeResistance(value - maximum)
    else -> value
}

private fun edgeResistance(distance: Float): Float =
    MAX_EDGE_OFFSET * (1f - kotlin.math.exp(-distance / EDGE_DISTANCE_SCALE))

private const val MAX_EDGE_OFFSET = 0.42f
private const val EDGE_DISTANCE_SCALE = 0.55f
