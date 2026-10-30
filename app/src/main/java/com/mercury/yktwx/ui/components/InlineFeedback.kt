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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    topSpacing: Dp = 0.dp, bottomSpacing: Dp = 0.dp) {
    val showing = !message.isNullOrBlank()
    val displayed = rememberWhileShown(showing, message.orEmpty())
    FeedbackTransition(showing, topSpacing = topSpacing, bottomSpacing = bottomSpacing) {
        // Only text transitions: credentials and live request objects never enter animation state.
        ContentFade(displayed) { InlineError(it, modifier) }
    }
}

/** Use as one child of a spaced Column: hidden feedback adds no extra parent gap. */
@Composable
internal fun InlineErrorGroup(message: String?, spacing: Dp, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        AnimatedInlineError(message, bottomSpacing = spacing)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}
