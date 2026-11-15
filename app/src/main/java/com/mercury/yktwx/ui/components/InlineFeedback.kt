package com.mercury.yktwx.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** The slot owns its spacing and remains mounted long enough to finish disappearance. */
@Composable
internal fun FeedbackTransition(showing: Boolean, modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp, bottomSpacing: Dp = 0.dp, content: @Composable () -> Unit) {
    // Restored failures and first composition are static; scrolling is never an arrival event.
    val visible = remember(LocalReadSnapshotRestored.current) { MutableTransitionState(showing) }
    visible.targetState = showing
    val layout = LocalReadLayoutMotion.current
    val moving = !visible.isIdle || visible.currentState != visible.targetState
    DisposableEffect(layout, moving) {
        if (moving && layout != null) layout.feedbackTransitions++
        onDispose { if (moving && layout != null) layout.feedbackTransitions-- }
    }
    AnimatedVisibility(visibleState = visible, modifier = modifier.fillMaxWidth(),
        enter = fadeIn(tween(ContentMotion.Fade)) +
            expandVertically(tween(ContentMotion.Layout, easing = ContentMotion.Easing), expandFrom = Alignment.Top),
        exit = fadeOut(tween(ContentMotion.Fade)) +
            shrinkVertically(tween(ContentMotion.Layout, easing = ContentMotion.Easing), shrinkTowards = Alignment.Top)) {
        Box(Modifier.fillMaxWidth().padding(top = topSpacing, bottom = bottomSpacing)) { content() }
    }
}

@Composable
internal fun AnimatedInlineError(message: String?, modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp, bottomSpacing: Dp = 0.dp, preserveMessage: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null) {
    AnimatedInlineFeedback(message, Unit, modifier, topSpacing, bottomSpacing, preserveMessage) { _, _ ->
        trailingContent?.invoke()
    }
}

/** Text and action share one transition, including wrapping caused by a wider progress action. */
@Composable
private fun <T> AnimatedInlineFeedback(message: String?, action: T, modifier: Modifier = Modifier,
    topSpacing: Dp = 0.dp, bottomSpacing: Dp = 0.dp, preserveMessage: Boolean = false,
    trailingContent: @Composable (T, Boolean) -> Unit) {
    val showing = !message.isNullOrBlank()
    val target = rememberWhileShown(showing, message.orEmpty() to action)
    FeedbackTransition(showing, topSpacing = topSpacing, bottomSpacing = bottomSpacing) {
        // Only display values transition; outgoing actions cannot dispatch another retry.
        ContentFade(target, animateSize = true, ownsSizeAnimation = true) { displayed ->
            val current = displayed == target
            InlineError(displayed.first, modifier.then(if (current) Modifier else Modifier.clearAndSetSemantics {}),
                preserveMessage = preserveMessage, trailingContent = { trailingContent(displayed.second, current) })
        }
    }
}

private data class RetryFeedbackAction(val checking: Boolean, val label: String)

/** A page shares the click acknowledgement across every entry to the same read. */
@Stable
internal class RetryFeedbackState(private val scope: CoroutineScope) {
    var dispatching by mutableStateOf(false)
        private set
    fun dispatch(action: () -> Unit) {
        if (dispatching) return
        dispatching = true
        scope.launch { delay(ContentMotion.Append.toLong()); dispatching = false }
        action()
    }
}

@Composable
internal fun rememberRetryFeedbackState(identity: Any? = Unit): RetryFeedbackState {
    val scope = rememberCoroutineScope()
    return remember(identity) { RetryFeedbackState(scope) }
}

/** Retain the last failure during a retry so progress stays beside the action. */
@Composable
internal fun InlineRetryFeedback(message: String?, busy: Boolean, label: String, onRetry: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, busyLabel: String = "正在检查",
    actionModifier: Modifier = Modifier, identity: Any? = Unit,
    retryFeedback: RetryFeedbackState? = null, showProgress: Boolean = true) {
    key(identity) {
        var lastFailure by rememberSaveable { mutableStateOf(message.orEmpty()) }
        val feedback = retryFeedback ?: rememberRetryFeedbackState(identity)
        val checking = busy || feedback.dispatching
        SideEffect {
            if (!message.isNullOrBlank()) lastFailure = message
            else if (!checking) lastFailure = ""
        }
        AnimatedInlineFeedback(if (checking) message?.takeIf(String::isNotBlank) ?: lastFailure else message,
            RetryFeedbackAction(checking, if (checking) busyLabel else label), modifier, preserveMessage = true) { action, current ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showProgress && action.checking) CircularProgressIndicator(size = 14.dp, strokeWidth = 2.dp)
                DashboardLink(action.label, { feedback.dispatch(onRetry) }, actionModifier,
                    enabled = current && enabled && !checking && !message.isNullOrBlank(), arrow = false)
            }
        }
    }
}

/** Use as one child of a spaced Column: hidden feedback adds no extra parent gap. */
@Composable
internal fun InlineErrorGroup(message: String?, spacing: Dp, modifier: Modifier = Modifier,
    preserveMessage: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        AnimatedInlineError(message, bottomSpacing = spacing, preserveMessage = preserveMessage)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}
