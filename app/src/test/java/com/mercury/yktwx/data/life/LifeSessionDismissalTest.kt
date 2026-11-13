package com.mercury.yktwx.data.life

import org.junit.Assert.*
import org.junit.Test

class LifeSessionDismissalTest {
    private val device = LifeDevice("water", "serial", "用水", "04", "org", "org", "shop", "imei",
        "学校", "", "after", false, true, null, emptyList(), emptyList())

    @Test fun confirmedRunningAndPendingRecordsKeepTheirProgressWithoutADismissButton() {
        assertFalse(WaterSession(device, "sku", WaterPhase.Running, orderNo = "order").canDismissRecord())
        assertFalse(Hui798Session(Hui798Device("device", "用水"), WaterPhase.Running, 1, serverTime = 1).canDismissRecord())
        assertFalse(LaundrySession(device, "sku", "1", "13", LaundryPhase.Paying, orderNo = "order", orderId = "id", serverStatus = 0).canDismissRecord())
        assertFalse(fixtureBikeSession.canDismissRecord())
        assertFalse(fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Pending).canDismissRecord())
    }

    @Test fun failedQueriesAndMissingOrUncertainStateAreDismissibleAcrossEveryService() {
        val water = WaterSession(device, "sku", WaterPhase.Stopping, orderNo = "order")
        val hui = Hui798Session(Hui798Device("device", "用水"), WaterPhase.Stopping, 1, serverTime = 1)
        val laundry = LaundrySession(device, "sku", "1", "13", LaundryPhase.Starting, orderNo = "order", orderId = "id")
        assertTrue(water.canDismissRecord("状态查询失败")); assertTrue(water.copy(creationUncertain = true).canDismissRecord())
        assertTrue(water.copy(orderNo = "").canDismissRecord()); assertTrue(water.copy(fault = "设备故障").canDismissRecord())
        assertTrue(hui.canDismissRecord("状态查询失败")); assertTrue(hui.copy(uncertain = true).canDismissRecord())
        assertTrue(hui.copy(serverTime = null).canDismissRecord())
        assertTrue(laundry.canDismissRecord("状态查询失败")); assertTrue(laundry.copy(creationUncertain = true).canDismissRecord())
        assertTrue(laundry.copy(serverStatus = 7).canDismissRecord()); assertTrue(laundry.copy(orderNo = "").canDismissRecord())
        assertTrue(laundry.copy(orderId = "").canDismissRecord())
        assertTrue(fixtureBikeSession.canDismissRecord("状态查询失败"))
        assertTrue(fixtureBikeSession.copy(phase = HellobikePhase.Closing, uncertain = true).canDismissRecord())
        for (settlement in listOf(HellobikeSettlement.Unknown, HellobikeSettlement.Paying))
            assertTrue(fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = settlement).canDismissRecord())
    }
}
