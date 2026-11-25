package com.mercury.yktwx.ui.nav

import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.Hui798ViewModel
import com.mercury.yktwx.LifeDevicesViewModel
import com.mercury.yktwx.data.life.LifeFeature

internal enum class LifeRouteRestore {
    WaitingForAccount,
    WaitingForNetwork,

    /** A command owns the provider for a moment; retry when it becomes idle instead of dropping the page. */
    WaitingForIdle,
    AccountChanged,
    Missing,
    Restored,
}

private fun AppRoute.LifeDevice.waiting(restoring: Boolean, owner: String?, online: Boolean?): LifeRouteRestore? = when {
    !valid -> LifeRouteRestore.Missing
    restoring -> LifeRouteRestore.WaitingForAccount
    account != null && account != owner -> LifeRouteRestore.AccountChanged
    deviceId != null && online != true -> LifeRouteRestore.WaitingForNetwork
    else -> null
}

internal fun restoreLifeRoute(route: AppRoute.LifeDevice, online: Boolean?, model: LifeDevicesViewModel): LifeRouteRestore {
    val state = model.state.value
    route.waiting(state.restoring, model.restorationOwner, online)?.let { return it }
    if (state.waterBusy || state.laundryBusy || state.accountBusy || state.paymentLaunch != null)
        return LifeRouteRestore.WaitingForIdle
    val restored = when {
        route.scanning -> { model.openUnifiedScan(); true }
        route.sessionId != null -> {
            val exists = if (route.feature == LifeFeature.Water) state.waterSessions.any { it.sessionId == route.sessionId }
                else state.laundrySessions.any { it.sessionId == route.sessionId }
            exists && model.openSession(route.feature, route.sessionId)
        }
        else -> model.restoreDevice(route.feature, route.deviceId!!, route.skuId)
    }
    return if (restored) LifeRouteRestore.Restored else LifeRouteRestore.Missing
}

internal fun restoreLifeRoute(route: AppRoute.LifeDevice, online: Boolean?, model: Hui798ViewModel): LifeRouteRestore {
    val state = model.state.value
    route.waiting(state.restoring, model.restorationOwner, online)?.let { return it }
    if (state.busy || state.accountBusy) return LifeRouteRestore.WaitingForIdle
    val restored = if (route.sessionId != null) {
        val exists = state.sessions.any { it.sessionId == route.sessionId }
        exists && model.openSession(route.sessionId)
    } else route.deviceId?.takeIf { it.matches(Regex("[0-9]{8,32}")) }?.let(model::openDevice) ?: false
    return if (restored) LifeRouteRestore.Restored else LifeRouteRestore.Missing
}

internal fun restoreLifeRoute(route: AppRoute.LifeDevice, online: Boolean?, model: HellobikeViewModel): LifeRouteRestore {
    val state = model.state.value
    route.waiting(state.restoring, model.restorationOwner, online)?.let { return it }
    if (state.busy || state.accountBusy || state.paymentInFlight) return LifeRouteRestore.WaitingForIdle
    val restored = if (route.sessionId != null) {
        val exists = state.sessions.any { it.sessionId == route.sessionId }
        exists && model.openSession(route.sessionId)
    } else route.deviceId?.let(model::openDevice) ?: false
    return if (restored) LifeRouteRestore.Restored else LifeRouteRestore.Missing
}
