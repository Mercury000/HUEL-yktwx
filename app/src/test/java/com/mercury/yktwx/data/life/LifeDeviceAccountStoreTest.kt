package com.mercury.yktwx.data.life

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LifeDeviceAccountStoreTest {
    private class Records : LifeDeviceRecords {
        val values = mutableMapOf<String, String>()
        var failWrite = false
        override fun read(key: String) = values[key]?.let(::JSONObject)
        override fun write(values: Map<String, String>) { if (failWrite) throw IllegalStateException("storage unavailable"); this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
    }
    private val device = LifeDevice("77", "serial", "77", "00", "22", "22", "shop", "imei",
        "学校", "", "before", false, false, null, listOf(LifeSku("clean", "桶自洁", true, "0.00", minutes = 5)), emptyList())
    private val account = LifeAccount("13800000000", "test-token")
    @Test fun failedAccountReplacementPreservesThePreviousAccountAndProgress() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val session = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended)
        store.saveLaundrySessions(listOf(session)); records.failWrite = true
        try { store.saveAccount(LifeAccount("13900000000", "other-token")); fail("Write should fail") }
        catch (_: IllegalStateException) {}
        assertEquals(account, store.account())
        assertEquals(listOf(session), store.laundrySessions())
    }

    @Test fun removingExpiredLocalAccountAlsoErasesItsSavedProgress() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        store.saveLaundrySessions(listOf(LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended)))
        store.clearAccount(); store.removeAccount()
        assertTrue(records.values.isEmpty())
    }
    @Test fun multipleSessionsRoundTripStableIdsAndServerTimes() {
        val records = Records(); val store = LifeDeviceAccountStore(records)
        store.saveAccount(account)
        val first = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running,
            orderNo = "order-1", orderId = "id-1", startedAtMillis = 100_000, expectedEndMillis = 400_000, payTypeName = "余额")
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
    @Test fun paymentDeadlineAndClosureRoundTripAndLegacyRecordsHaveNoMadeUpStart() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val paying = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash", createdAtMillis = 100_000L)
        store.saveLaundrySession(paying)
        assertEquals(paying, store.laundrySession())
        store.saveLaundrySessions(listOf(paying.copy(phase = LaundryPhase.Closed)))
        assertEquals(LaundryPhase.Closed, LifeDeviceAccountStore(records).laundrySessions().single().phase)
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.apply {
            getJSONArray("sessions").getJSONObject(0).remove("createdAt")
        }.toString()
        assertNull(LifeDeviceAccountStore(records).laundrySessions().single().createdAtMillis)
        assertEquals(300L, paying.paymentRemainingSeconds(100_000L))
        assertEquals(1L, paying.paymentRemainingSeconds(399_999L))
        assertEquals(0L, paying.paymentRemainingSeconds(400_000L))
        assertNull(paying.copy(paid = true).paymentRemainingSeconds(200_000L))
    }

    @Test fun completionObservationSurvivesBothStorageFormatsAndOldRecordsRemainReadable() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val completed = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended,
            completionObservedAtMillis = 1_799_999_000_000L)
        store.saveLaundrySession(completed)
        assertEquals(completed, LifeDeviceAccountStore(records).laundrySession())
        store.saveLaundrySessions(listOf(completed))
        assertEquals(listOf(completed), LifeDeviceAccountStore(records).laundrySessions())
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.apply {
            getJSONArray("sessions").getJSONObject(0).remove("completionObservedAt")
        }.toString()
        assertEquals(listOf(completed.copy(completionObservedAtMillis = null)), LifeDeviceAccountStore(records).laundrySessions())
    }
    @Test fun hiddenUnknownCreationRoundTripsWithoutLosingDuplicateProtectionAndLegacyDefaultsStayVisible() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val hidden = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Creating, createSent = true,
            creationUncertain = true, islandDismissed = true)
        store.saveLaundrySession(hidden)
        assertEquals(hidden, LifeDeviceAccountStore(records).laundrySession())
        store.saveLaundrySessions(listOf(hidden))
        assertEquals(listOf(hidden), LifeDeviceAccountStore(records).laundrySessions())
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.apply {
            getJSONArray("sessions").getJSONObject(0).apply { remove("creationUncertain"); remove("islandDismissed") }
        }.toString()
        val legacy = LifeDeviceAccountStore(records).laundrySessions().single()
        assertFalse(legacy.islandDismissed); assertFalse(legacy.creationUncertain); assertTrue(legacy.createSent)
    }
}
