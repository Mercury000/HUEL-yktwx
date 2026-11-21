package com.mercury.yktwx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mercury.yktwx.state.RestorableState
import com.mercury.yktwx.ui.nav.AppRoute
import com.mercury.yktwx.ui.nav.LifeEntryOrigin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

@HiltViewModel
internal class AppNavigationViewModel @Inject constructor(handle: SavedStateHandle) : ViewModel() {
    val backStack = navBackStackOf(AppRoute.Main)
    var tab by mutableIntStateOf(0)
    private var binding by mutableStateOf<Pair<String?, Boolean>?>(null)
    private var generation: Long? = null
    private var restoredLife by mutableStateOf<AppRoute.LifeDevice?>(null)

    /** Selects the LifeDevice push motion without coupling it to the destination route type. */
    var lifeEntryOrigin by mutableStateOf<LifeEntryOrigin?>(null)
        private set
    var scope by mutableStateOf(java.util.UUID.randomUUID().toString())
        private set
    private val saved = RestorableState(handle, "navigation", NavigationSnapshot.serializer(), { it.valid }) {
        NavigationSnapshot(tab, backStack.filterIsInstance<AppRoute>(), scope)
    }

    fun readyFor(owner: String, preview: Boolean) = binding == (owner to preview)

    fun bind(owner: String?, preview: Boolean, generation: Long) {
        if (binding == (owner to preview) && this.generation == generation) return
        val renewed = this.generation != null && this.generation != generation
        val snapshot = saved.bind(owner, preview, renewed)
        val routes = snapshot?.routes ?: listOf(AppRoute.Main)
        val restoredLifeRoute = routes.lastOrNull() as? AppRoute.LifeDevice
        binding = owner to preview; this.generation = generation
        // Set the origin before the stack changes so the restored entry is built with the
        // static arrival motion instead of animating a page that was already visible.
        lifeEntryOrigin = restoredLifeRoute?.let { LifeEntryOrigin.Restored }
        backStack.clear(); backStack.addAll(routes)
        tab = snapshot?.tab ?: 0
        scope = snapshot?.scope ?: java.util.UUID.randomUUID().toString()
        restoredLife = restoredLifeRoute
    }

    fun needsLifeRestore(route: AppRoute.LifeDevice) = restoredLife == route
    fun lifeRestored() { restoredLife = null }
    /** Push a non-LifeDevice page. LifeDevice entries use [showLife] so their motion is explicit. */
    fun open(route: AppRoute) { if (route !in backStack) backStack.add(route) }

    /** Push or replace a LifeDevice page; [origin] must describe why the page appears. */
    fun showLife(route: AppRoute.LifeDevice, origin: LifeEntryOrigin) {
        // Publish the origin first so the new entry is composed with the matching motion.
        lifeEntryOrigin = origin
        restoredLife = null
        if (backStack.lastOrNull() is AppRoute.LifeDevice) backStack[backStack.lastIndex] = route else open(route)
    }

    /** Replace the visible LifeDevice route after a state sync; never changes how it was entered. */
    fun updateLife(route: AppRoute.LifeDevice) {
        if (backStack.lastOrNull() is AppRoute.LifeDevice) backStack[backStack.lastIndex] = route
    }

    fun close() {
        restoredLife = null
        val leavingLife = backStack.lastOrNull() is AppRoute.LifeDevice
        if (backStack.size > 1) backStack.removeLastOrNull()
        if (leavingLife) lifeEntryOrigin = null
    }

    fun returnToMain() {
        restoredLife = null
        while (backStack.size > 1) backStack.removeLastOrNull()
        lifeEntryOrigin = null
    }

    @Serializable private data class NavigationSnapshot(val tab: Int, val routes: List<AppRoute>, val scope: String) {
        val valid get() = scope.length in 1..64 && tab in 0..3 && routes.size in 1..12 && routes.first() == AppRoute.Main &&
            routes.count { it is AppRoute.LifeDevice } <= 1 &&
            routes.drop(1).none { it == AppRoute.Main } && routes.distinct().size == routes.size && routes.all {
                when (it) {
                    is AppRoute.BillDetail -> it.bill.valid
                    is AppRoute.LifeDevice -> it.valid
                    else -> true
                }
            }
    }
}
