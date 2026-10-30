package com.mercury.yktwx.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class PressFeedbackStyle { Background, Content, Group }

/** Shared indication for library controls; their own clipping still bounds the feedback. */
@Composable
internal fun AppPressFeedbackHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIndication provides rememberPressFeedback()) { content() }
}

/** Keep Surface's semantics and expanded touch target while matching feedback to its shape. */
@Composable
internal fun ActionSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = Color.Transparent,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    feedbackStyle: PressFeedbackStyle = PressFeedbackStyle.Background,
    feedbackColor: Color = contentColor,
    content: @Composable () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        color = color, contentColor = contentColor,
        indication = rememberPressFeedback(shape, feedbackColor, feedbackStyle), content = content)
}

/** Opening the enlarged code is the feedback; preserve the original artwork and dimensions. */
internal fun Modifier.paymentCodeClickable(enabled: Boolean, onClickLabel: String, onClick: () -> Unit): Modifier =
    clickable(interactionSource = null, indication = null,
        enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)

@Composable
internal fun rememberPressFeedback(
    // Library controls already clip to their own circle/squircle/card outline.
    // A second rounded outline here would leave gaps inside their real boundary.
    shape: Shape = RectangleShape,
    color: Color = MiuixTheme.colorScheme.onSurface,
    style: PressFeedbackStyle = PressFeedbackStyle.Background,
): IndicationNodeFactory {
    val dark = isSystemInDarkTheme()
    val opacity = if (dark) .06f else .05f
    return remember(shape, color, style, opacity) { PressFeedback(shape, color, style, opacity) }
}

private data class PressFeedback(
    val shape: Shape,
    val color: Color,
    val style: PressFeedbackStyle,
    val opacity: Float,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressFeedbackNode(interactionSource, this)
}

private class PressFeedbackNode(
    private val source: InteractionSource,
    private val feedback: PressFeedback,
) : Modifier.Node(), DrawModifierNode {
    private var progress = Animatable(0f)
    private var ambient = Animatable(0f)
    private val contentPaint = Paint()
    private var animation: Job? = null
    private var ambientAnimation: Job? = null

    override fun onAttach() {
        progress = Animatable(0f)
        ambient = Animatable(0f)
        coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            val hovers = mutableSetOf<HoverInteraction.Enter>()
            val focuses = mutableSetOf<FocusInteraction.Focus>()
            var pressed = false
            var ambientTarget = 0f
            source.interactions.collect { interaction ->
                var released = false
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> released = presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                    is HoverInteraction.Enter -> hovers.add(interaction)
                    is HoverInteraction.Exit -> hovers.remove(interaction.enter)
                    is FocusInteraction.Focus -> focuses.add(interaction)
                    is FocusInteraction.Unfocus -> focuses.remove(interaction.focus)
                }
                // Hover remains subtle; keyboard focus stays stronger and visible after release.
                val nextAmbient = when {
                    focuses.isNotEmpty() -> 1.6f
                    hovers.isNotEmpty() -> .5f
                    else -> 0f
                }
                if (nextAmbient != ambientTarget) {
                    ambientTarget = nextAmbient
                    ambientAnimation?.cancel()
                    ambientAnimation = launch { ambient.animateTo(nextAmbient, tween(100)) }
                }
                val nextPressed = presses.isNotEmpty()
                if (nextPressed != pressed) {
                    pressed = nextPressed
                    animation?.cancel()
                    animation = launch(start = CoroutineStart.UNDISPATCHED) {
                        // Scroll containers can deliver Press and Release in one frame.
                        // Finish a short pulse for a real tap; cancelled gestures only fade out.
                        if (nextPressed) {
                            progress.animateTo(1f, tween(70))
                        } else {
                            if (released && progress.value < .6f) progress.animateTo(1f, tween(40))
                            progress.animateTo(0f, tween(140))
                        }
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // Press takes precedence, then returns to the still-active hover/focus state.
        // Using max() would hide a press entirely when the stronger focus state is active.
        val amount = ambient.value + (1f - ambient.value) * progress.value
        if (feedback.style == PressFeedbackStyle.Background) {
            // The control's background has already been drawn. Keep ink and artwork above
            // this state layer; never tint the rendered contents of the control.
            drawStateLayer(amount)
            drawContent()
        } else {
            // Only keyboard focus adds a persistent background to transparent actions.
            // Ordinary presses/hover change the complete content group without a new tile.
            drawStateLayer(((ambient.value - .5f) / 1.1f).coerceIn(0f, 1f) * 1.6f)
            val fade = if (feedback.style == PressFeedbackStyle.Group) .08f else .12f
            val contentAmount = maxOf(progress.value, ambient.value.coerceAtMost(.5f))
            if (contentAmount == 0f) {
                drawContent()
                return
            }
            contentPaint.alpha = 1f - fade * contentAmount
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), contentPaint)
                drawContent()
                canvas.restore()
            }
        }
    }

    private fun ContentDrawScope.drawStateLayer(amount: Float) {
        if (amount <= 0f) return
        val color = feedback.color.copy(alpha = feedback.color.alpha * feedback.opacity * amount)
        if (feedback.shape == RectangleShape) drawRect(color)
        else drawOutline(feedback.shape.createOutline(size, layoutDirection, this), color)
    }
}
