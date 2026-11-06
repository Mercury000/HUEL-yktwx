package com.mercury.yktwx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** Keep every page slot, but create offscreen content only after the visible page has drawn. */
@Composable
internal fun RetainedMainTabsPager(
    state: PagerState,
    selectedPage: Int,
    prewarmEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit,
) {
    val prepared = remember(state) { List(state.pageCount) { mutableStateOf(it == state.currentPage) } }
    val firstDraw = remember(state) { CompletableDeferred<Unit>() }
    val canPrewarm by rememberUpdatedState(prewarmEnabled && !state.isScrollInProgress)

    LaunchedEffect(state) {
        firstDraw.await()
        // Allow the first buffer and its immediate state updates to finish before warming pages.
        withFrameNanos { }
        withFrameNanos { }
        delay(160)
        while (prepared.any { !it.value }) {
            snapshotFlow { canPrewarm }.first { it }
            withFrameNanos { }
            if (!canPrewarm) continue
            val next = prepared.indices.filter { !prepared[it].value }
                .minByOrNull { abs(it - state.currentPage) } ?: break
            prepared[next].value = true
            // Each new subtree gets a separate layout frame; navigation interrupts prewarming.
            withFrameNanos { }
            delay(48)
        }
    }

    HorizontalPager(
        state = state,
        modifier = modifier.drawWithContent {
            drawContent()
            firstDraw.complete(Unit)
        },
        beyondViewportPageCount = state.pageCount - 1,
        key = { it },
    ) { page ->
        val needed = prepared[page].value || page == state.currentPage || page == selectedPage ||
            state.isScrollInProgress && abs(page - state.currentPage) <= 1
        Box(Modifier.fillMaxSize()) {
            if (needed) {
                SideEffect { prepared[page].value = true }
                content(page)
            }
        }
    }
}
