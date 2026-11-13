package com.mercury.yktwx

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.mercury.yktwx.state.RestorableState
import com.mercury.yktwx.ui.nav.AppRoute
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
        binding = owner to preview; this.generation = generation
        backStack.clear(); backStack.addAll(snapshot?.routes ?: listOf(AppRoute.Main))
        tab = snapshot?.tab ?: 0
        scope = snapshot?.scope ?: java.util.UUID.randomUUID().toString()
        restoredLife = backStack.lastOrNull() as? AppRoute.LifeDevice
    }

    fun needsLifeRestore(route: AppRoute.LifeDevice) = restoredLife == route
    fun lifeRestored() { restoredLife = null }
    fun open(route: AppRoute) { if (route !in backStack) backStack.add(route) }
    fun showLife(route: AppRoute.LifeDevice) {
        restoredLife = null
        if (backStack.lastOrNull() is AppRoute.LifeDevice) backStack[backStack.lastIndex] = route else open(route)
    }
    fun close() {
        restoredLife = null
        if (backStack.size > 1) backStack.removeLastOrNull()
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
