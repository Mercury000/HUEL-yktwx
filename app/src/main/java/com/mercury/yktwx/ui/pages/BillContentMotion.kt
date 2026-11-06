package com.mercury.yktwx.ui.pages

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.ui.MainTabsPagerState
import com.mercury.yktwx.ui.components.ContentMotion
import com.mercury.yktwx.ui.components.LocalContentSwitchActive
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun BillUiState.contentSelection() = if (!statsVisible) 0 else if (stats.type == 2) 1 else 2

/** Mode and type transitions share loading coordination, while each owns its moving region. */
internal class BillContentMotion(initialSelection: Int, private val activeState: State<Boolean>) {
    private val transitions = mutableStateMapOf<Any, Boolean>()
    private var requested by mutableStateOf(false)
    val active: Boolean get() = activeState.value
    val running: Boolean get() = active && (requested || transitions.values.any { it })
    var selection = initialSelection

    fun select(next: Int, onSelected: () -> Unit) {
        if (next == selection) return
        selection = next
        requested = true
        onSelected()
    }

    fun update(id: Any, active: Boolean) {
        requested = false
        transitions[id] = active
    }

    fun remove(id: Any) { transitions.remove(id) }
}

@Composable
internal fun rememberBillContentMotion(state: BillUiState, active: Boolean): BillContentMotion {
    // Queries transition only the result paint, preserving the search field and condition slot.
    val identity = listOf(state.dateFilter, state.stats.period, state.needsLogin)
    val activeState = rememberUpdatedState(active)
    return remember(identity) { BillContentMotion(state.contentSelection(), activeState) }
}

@Composable
internal fun <T> BillContentTransition(state: T, selection: Int, motion: BillContentMotion,
    modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    // Identity changes discard outgoing account/query content immediately.
    key(motion) {
        val pager = rememberPagerState(initialPage = selection) { 2 }
        val scope = rememberCoroutineScope()
        val navigation = remember(pager, scope) { MainTabsPagerState(pager, scope) }
        val active = motion.active
        LaunchedEffect(selection, active) { if (active) navigation.animateToPage(selection) }
        val switching = active && (pager.currentPage != selection || navigation.isNavigating ||
            pager.currentPageOffsetFraction != 0f)
        val frames = remember { mutableStateMapOf(selection to state) }
        val id = remember { Any() }
        val moving by rememberUpdatedState(switching)
        SideEffect {
            // Finish hidden transitions without replacing the pager and its content identity.
            if (!active) navigation.snapToPage(selection)
            motion.update(id, switching)
            frames[selection] = state
            if (!switching) frames.keys.toList().filter { it != selection }.forEach { frames.remove(it) }
        }
        DisposableEffect(motion, id) { onDispose { motion.remove(id) } }
        HorizontalPager(pager, modifier.fillMaxSize().clipToBounds().pointerInput(motion) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (moving) event.changes.forEach { it.consume() }
                }
            }
        }, userScrollEnabled = false) { displayedSelection ->
            val current = displayedSelection == selection
            val displayed = if (current) state else frames[displayedSelection]
            if (displayed == null) return@HorizontalPager
            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)
                .then(if (current) Modifier else Modifier.clearAndSetSemantics {})) {
                CompositionLocalProvider(LocalContentSwitchActive provides
                    (LocalContentSwitchActive.current || switching)) {
                    content(displayed)
                }
            }
        }
    }
}

internal fun LazyListScope.billMotionItem(key: Any, motion: BillContentMotion?, fadeAppearance: Boolean = false,
    content: @Composable LazyItemScope.() -> Unit) {
    item(key = key) {
        val appearance = if (fadeAppearance && motion?.running != true) Modifier.animateItem(
            fadeInSpec = tween(ContentMotion.Fade), fadeOutSpec = tween(ContentMotion.Fade), placementSpec = null)
            else Modifier
        if (appearance == Modifier) content() else Box(appearance) { content() }
    }
}
