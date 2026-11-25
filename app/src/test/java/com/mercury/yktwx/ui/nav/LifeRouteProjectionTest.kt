package com.mercury.yktwx.ui.nav

import com.mercury.yktwx.HellobikeUiState
import com.mercury.yktwx.Hui798UiState
import com.mercury.yktwx.LifeDevicesUiState
import com.mercury.yktwx.data.life.HellobikePhase
import com.mercury.yktwx.data.life.HellobikePosition
import com.mercury.yktwx.data.life.HellobikePrecheck
import com.mercury.yktwx.data.life.HellobikeRegion
import com.mercury.yktwx.data.life.HellobikeSession
import com.mercury.yktwx.data.life.Hui798Device
import com.mercury.yktwx.data.life.Hui798Session
import com.mercury.yktwx.data.life.LaundryPhase
import com.mercury.yktwx.data.life.LaundrySession
import com.mercury.yktwx.data.life.LifeDevice
import com.mercury.yktwx.data.life.LifeFeature
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.data.life.LifeSku
import com.mercury.yktwx.data.life.WaterPhase
import com.mercury.yktwx.data.life.WaterSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The route follows the provider state; a route/state mismatch is never a rendering decision. */
class LifeRouteProjectionTest {
    private val owner = "fixture-owner"
    private val device = LifeDevice(
        "water", "867369079842630", "测试设备", "04", "org", "org", "shop", "imei",
        "学校", "", "after", false, true, null,
        listOf(LifeSku("water-sku", "冷水", true)), emptyList(),
    )
    private val water = WaterSession(device, "water-sku", WaterPhase.Running,
        orderNo = "water-order", sessionId = "water-session")
    private val laundry = LaundrySession(
        device.copy(id = "laundry", category = "00", payment = "before"), "laundry-sku",
        "0.00", "2", LaundryPhase.Running, sessionId = "laundry-session",
    )
    private val huiDevice = Hui798Device("123456789012345", "测试用水")
    private val huiSession = Hui798Session(huiDevice, WaterPhase.Running, 1_000, true, 1_000, 1_000,
        sessionId = "hui-session")
    private val bikeNo = "1234567890"
    private val bike = HellobikeSession("ride-1", bikeNo, HellobikeRegion("0371", "410100"),
        HellobikePhase.Riding, 1_000, sessionId = "bike-session", startedAtMillis = 1_000)
    private val precheck = HellobikePrecheck(bikeNo, "order-1", "2", 50_000,
        HellobikeRegion("0371", "410100"), HellobikePosition(34.7, 113.6, 12f, 50_000))

    @Test fun pangguiDeviceBecomesTheSessionThatStartsOnIt() {
        val route = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water,
            deviceId = device.serial, account = owner, skuId = "water-sku")
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Water,
            water = water, selectedSkuId = "water-sku")
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water,
                sessionId = water.sessionId, account = owner, skuId = "water-sku"),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun pangguiScanningDropsTheOldOrderBeforeTheCameraTakesOver() {
        val route = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water,
            sessionId = water.sessionId, account = owner, skuId = "water-sku")
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Water,
            scanning = true, water = water, selectedSkuId = "water-sku")
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water, account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun pangguiRemovedOrderFallsBackToTheDeviceThatIsStillLoaded() {
        val route = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Laundry,
            sessionId = laundry.sessionId, account = owner, skuId = "laundry-sku")
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = laundry.device, selectedSkuId = "laundry-sku")
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Laundry,
                deviceId = laundry.device.serial, account = owner, skuId = "laundry-sku"),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun pangguiStateWithoutADeviceOrOrderKeepsTheCurrentRoute() {
        val route = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water,
            sessionId = water.sessionId, account = owner)
        assertNull(projectLifeRoute(route, LifeDevicesUiState(restoring = false), owner))
    }

    @Test fun pangguiScannedSerialWinsOverACachedDevice() {
        val route = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water, account = owner)
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Water,
            scannedSerial = "867369079842631", device = device)
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Water,
                deviceId = "867369079842631", account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun hui798DeviceBecomesItsSession() {
        val route = AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water,
            deviceId = huiDevice.id, account = owner)
        val state = Hui798UiState(restoring = false, device = huiDevice, sessions = listOf(huiSession),
            selectedSessionId = huiSession.sessionId)
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water,
                sessionId = huiSession.sessionId, account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun hui798RemovedOrderFallsBackToTheDeviceItWasUsing() {
        val route = AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water,
            sessionId = huiSession.sessionId, account = owner)
        val state = Hui798UiState(restoring = false, device = huiDevice)
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water,
                deviceId = huiDevice.id, account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun hui798StateWithoutADeviceOrOrderKeepsTheCurrentRoute() {
        val route = AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water,
            sessionId = huiSession.sessionId, account = owner)
        assertNull(projectLifeRoute(route, Hui798UiState(restoring = false), owner))
    }

    @Test fun hellobikeDeviceBecomesItsRide() {
        val route = AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
            deviceId = bikeNo, account = owner)
        val state = HellobikeUiState(restoring = false, scannedBikeNo = bikeNo, precheck = precheck,
            sessions = listOf(bike), selectedSessionId = bike.sessionId)
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
                sessionId = bike.sessionId, account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun hellobikePrecheckKeepsTheScannedBikeAddressable() {
        val route = AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride, account = owner)
        val state = HellobikeUiState(restoring = false, scannedBikeNo = bikeNo, precheck = precheck)
        assertEquals(
            AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
                deviceId = bikeNo, account = owner),
            projectLifeRoute(route, state, owner),
        )
    }

    @Test fun hellobikeStateWithoutABikeOrRideKeepsTheCurrentRoute() {
        val route = AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
            sessionId = bike.sessionId, account = owner)
        assertNull(projectLifeRoute(route, HellobikeUiState(restoring = false), owner))
    }

    @Test fun aRouteForAnotherProviderOrFeatureIsNeverReprojected() {
        val huiRoute = AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water, account = owner)
        assertNull(projectLifeRoute(huiRoute,
            LifeDevicesUiState(restoring = false, water = water), owner))
        val pangguiRide = AppRoute.LifeDevice(LifeProvider.Panggui, LifeFeature.Ride, account = owner)
        assertNull(projectLifeRoute(pangguiRide,
            LifeDevicesUiState(restoring = false, feature = LifeFeature.Ride), owner))
        val huiOnRide = AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Ride, account = owner)
        assertNull(projectLifeRoute(huiOnRide, Hui798UiState(restoring = false), owner))
        val bikeOnWater = AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Water, account = owner)
        assertNull(projectLifeRoute(bikeOnWater, HellobikeUiState(restoring = false), owner))
    }
}
