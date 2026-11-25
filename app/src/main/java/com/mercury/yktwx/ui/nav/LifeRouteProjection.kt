package com.mercury.yktwx.ui.nav

import com.mercury.yktwx.HellobikeUiState
import com.mercury.yktwx.Hui798UiState
import com.mercury.yktwx.LifeDevicesUiState
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.LifeProvider

/**
 * Projects provider state onto the single navigation destination it currently describes.
 *
 * A route is navigation identity and a persisted bookmark, never a render permission: page
 * content is always rendered from state. The projection therefore has to follow every legitimate
 * transition (device -> order, order -> rescan, order -> device after a rejected or removed
 * record) instead of rejecting the new state.
 *
 * A `null` result means the state has no addressable page right now. The caller keeps the current
 * route in that case; the page still renders the empty state and can recover on the next scan.
 */
internal fun projectLifeRoute(route: AppRoute.LifeDevice, state: LifeDevicesUiState, owner: String?): AppRoute.LifeDevice? {
    if (route.provider != LifeProvider.Panggui) return null
    val feature = state.feature
    if (feature == LifeFeature.Ride) return null
    val sessionId = when (feature) {
        LifeFeature.Water -> state.water?.sessionId
        LifeFeature.Laundry -> state.laundry?.sessionId
        LifeFeature.Ride -> null
    }
    return when {
        // An exception rescan keeps the page route alive while the camera takes over.
        state.scanning -> route.copy(feature = feature, deviceId = null, sessionId = null, account = owner, skuId = null)
        sessionId != null -> route.copy(feature = feature, deviceId = null, sessionId = sessionId, account = owner,
            skuId = state.selectedSkuId.takeIf(String::isNotBlank))
        else -> {
            // A removed or rejected order falls back to the device that is still loaded.
            val deviceId = state.scannedSerial ?: state.device?.serial ?: return null
            route.copy(feature = feature, deviceId = deviceId, sessionId = null, account = owner,
                skuId = state.selectedSkuId.takeIf(String::isNotBlank))
        }
    }
}

internal fun projectLifeRoute(route: AppRoute.LifeDevice, state: Hui798UiState, owner: String?): AppRoute.LifeDevice? {
    if (route.provider != LifeProvider.Hui798 || route.feature != LifeFeature.Water) return null
    val sessionId = state.selectedSessionId
    val deviceId = state.scannedDeviceId ?: state.device?.id
    return when {
        sessionId != null -> route.copy(deviceId = null, sessionId = sessionId, account = owner, skuId = null)
        // Polling can remove a rejected order; keep the device identity so the page stays usable.
        deviceId != null -> route.copy(deviceId = deviceId, sessionId = null, account = owner, skuId = null)
        else -> null
    }
}

internal fun projectLifeRoute(route: AppRoute.LifeDevice, state: HellobikeUiState, owner: String?): AppRoute.LifeDevice? {
    if (route.provider != LifeProvider.Hellobike || route.feature != LifeFeature.Ride) return null
    val sessionId = state.selectedSessionId
    val bikeNo = state.scannedBikeNo ?: state.precheck?.bikeNo
    return when {
        sessionId != null -> route.copy(deviceId = null, sessionId = sessionId, account = owner, skuId = null)
        bikeNo != null -> route.copy(deviceId = bikeNo, sessionId = null, account = owner, skuId = null)
        else -> null
    }
}
