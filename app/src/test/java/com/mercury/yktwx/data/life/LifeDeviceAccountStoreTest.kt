package com.mercury.yktwx.data.life

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LifeDeviceAccountStoreTest {
    private class Records : LifeDeviceRecords {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]?.let(::JSONObject)
        override fun write(values: Map<String, String>) { this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
    }
    private val device = LifeDevice("77", "serial", "77", "00", "22", "22", "shop", "imei",
        "学校", "", "before", false, false, null, listOf(LifeSku("clean", "桶自洁", true, "0.00", minutes = 5)), emptyList())
    private val account = LifeAccount("13800000000", "test-token")
    @Test fun multipleSessionsRoundTripStableIdsAndServerTimes() {
        val records = Records(); val store = LifeDeviceAccountStore(records)
        store.saveAccount(account)
        val first = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running,
            orderNo = "order-1", orderId = "id-1", startedAtMillis = 100_000, expectedEndMillis = 400_000)
        val second = first.copy(device = device.copy(id = "78"), phase = LaundryPhase.Ended,
            orderNo = "order-2", orderId = "id-2", finishedAtMillis = 521_000, sessionId = "second")
        val water = WaterSession(device.copy(category = "04"), "water", WaterPhase.Running, orderNo = "water-1")
        store.saveLaundrySessions(listOf(first, second)); store.saveWaterSessions(listOf(water))
        val restored = LifeDeviceAccountStore(records)
        assertEquals(listOf(first, second), restored.laundrySessions())
        assertEquals(listOf(water), restored.waterSessions())
        restored.saveLaundrySessions(listOf(first))
        assertEquals(listOf(first), LifeDeviceAccountStore(records).laundrySessions())
        assertEquals(listOf(water), restored.waterSessions())
        restored.saveLaundrySessions(emptyList())
        assertTrue(LifeDeviceAccountStore(records).laundrySessions().isEmpty())
    }
    @Test fun legacySingleRecordsMigrateWithoutChangingSessionIdentity() {
        val records = Records(); val store = LifeDeviceAccountStore(records)
        store.saveAccount(account)
        store.saveLaundrySession(LaundrySession(device, "clean", "0.00", "2", orderNo = "old-laundry"))
        store.saveSession(WaterSession(device.copy(category = "04"), "water", WaterPhase.Running, orderNo = "old-water"))
        // Old app versions did not store sessionId.
        listOf("water", "laundry").forEach { key ->
            val record = records.read(key)!!.apply { remove("sessionId") }
            records.values[key] = record.toString()
        }
        val waters = store.waterSessions(); val laundries = store.laundrySessions()
        assertEquals(waters, LifeDeviceAccountStore(records).waterSessions())
        assertEquals(laundries, LifeDeviceAccountStore(records).laundrySessions())
        store.saveWaterSessions(waters); store.saveLaundrySessions(laundries)
        assertFalse(records.values.containsKey("water")); assertFalse(records.values.containsKey("laundry"))
        assertEquals(waters, LifeDeviceAccountStore(records).waterSessions())
        assertEquals(laundries, LifeDeviceAccountStore(records).laundrySessions())
    }
    @Test fun accountExpiryPreservesOwnersSessionsButAnotherAccountCannotReadThem() {
        val records = Records(); val store = LifeDeviceAccountStore(records)
        store.saveAccount(account)
        val laundry = LaundrySession(device, "clean", "0.00", "2")
        store.saveLaundrySessions(listOf(laundry)); store.clearAccount()
        assertTrue(store.laundrySessions().isEmpty())
        store.saveAccount(account.copy(token = "renewed-token"))
        assertEquals(listOf(laundry), store.laundrySessions())
        store.clearAccount(); store.saveAccount(LifeAccount("13900000000", "another-token"))
        assertTrue(store.laundrySessions().isEmpty()); assertTrue(store.waterSessions().isEmpty())
        store.saveLaundrySessions(emptyList())
        assertTrue(LifeDeviceAccountStore(records).laundrySessions().isEmpty())
    }
}
