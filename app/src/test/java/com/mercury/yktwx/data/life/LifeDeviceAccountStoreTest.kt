package com.mercury.yktwx.data.life

import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import org.junit.Assert.*
import org.junit.Test

class LifeDeviceAccountStoreTest {
    private class Records : LifeDeviceRecords {
        val values = mutableMapOf<String, String>()
        var failWrite = false
        override fun read(key: String) = values[key]?.let(::parseObject)
        override fun write(values: Map<String, String>) { if (failWrite) throw IllegalStateException("storage unavailable"); this.values.putAll(values) }
        override fun remove(key: String) { values.remove(key) }
    }
    private val device = LifeDevice("77", "serial", "77", "00", "22", "22", "shop", "imei",
        "学校", "", "before", false, false, null, listOf(LifeSku("clean", "桶自洁", true, "0.00", minutes = 5)), emptyList())
    private val account = LifeAccount("13800000000", "test-token")
    @Test fun unversionedUnknownOrderSurvivesUpgradeAndSavesOnlyCurrentFormat() {
        val records = Records()
        records.values["account"] = """{"phone":"13800000000","token":"test-token"}"""
        // Historical persisted shape: absent nullable values, no session ID, no schema marker.
        records.values["laundry"] = """{"owner":"13800000000","device":{"id":"77","serial":"serial","name":"77","category":"00","routeShopId":"22","orgId":"22","shopId":"shop","imei":"imei","shopName":"学校","address":"","payment":"before","skus":[{"id":"clean","name":"桶自洁","price":"0.00","minutes":5}],"channels":[]},"skuId":"clean","price":"0.00","payMethod":"13","phase":"Creating","createSent":true,"creationUncertain":true}"""
        val store = LifeDeviceAccountStore(records)
        val restored = store.laundrySessions().single()
        assertEquals("legacy-laundry--clean", restored.sessionId)
        assertTrue(restored.createSent)
        assertTrue(restored.creationUncertain)
        assertTrue(restored.canDismissUnknownCreation)
        assertNull(restored.device.bluetooth)
        assertNull(restored.device.skus.single().available)
        store.saveLaundrySessions(listOf(restored))
        assertFalse(records.values.containsKey("laundry"))
        assertEquals(2L, records.read("laundry_sessions")!!.strictLongValue("schemaVersion"))
        assertEquals(listOf(restored), LifeDeviceAccountStore(records).laundrySessions())
        store.removeLaundrySession(restored.sessionId)
        assertTrue(LifeDeviceAccountStore(records).laundrySessions().isEmpty())
    }
    @Test fun unknownWaterDeletionErasesOwnedRecordAfterExpiryAndPreservesOtherProgressAndUnknownFields() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val unknownDevice = device.copy(category = "04", bluetooth = null, supportsFinish = null,
            skus = listOf(LifeSku("water", "接水", null)), channels = listOf(LifePayChannel("15", "支付宝", null, null)))
        val unknown = WaterSession(unknownDevice, "water", WaterPhase.Starting, startSent = true, creationUncertain = true)
        val running = unknown.copy(sessionId = "running", phase = WaterPhase.Running, orderNo = "order-2", creationUncertain = false)
        store.saveWaterSessions(listOf(unknown, running))
        assertEquals(listOf(unknown, running), LifeDeviceAccountStore(records).waterSessions())
        store.clearAccount(); store.removeWaterSession(unknown.sessionId)
        assertFalse(records.read("water_sessions")!!.toString().contains(unknown.sessionId))
        store.saveAccount(account); assertEquals(listOf(running), store.waterSessions())
    }
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
    @Test fun logoutErasesAllProgressAndReloginCannotRestoreIt() {
        for (expired in listOf(false, true)) {
            val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
            val waters = WaterPhase.entries.map { phase ->
                WaterSession(device.copy(category = "04"), "water", phase, startSent = true,
                    creationUncertain = true, sessionId = "water-$phase")
            }
            val laundries = LaundryPhase.entries.map { phase ->
                LaundrySession(device, "clean", "0.00", "13", phase, createSent = true,
                    creationUncertain = true, sessionId = "laundry-$phase")
            }
            store.saveWaterSessions(waters); store.saveLaundrySessions(laundries)
            store.saveSession(waters.first()); store.saveLaundrySession(laundries.first())
            if (expired) store.clearAccount()
            store.removeAccount()
            assertTrue(records.values.isEmpty())
            val restored = LifeDeviceAccountStore(records); restored.saveAccount(account)
            assertTrue(restored.waterSessions().isEmpty()); assertTrue(restored.laundrySessions().isEmpty())
            assertNull(restored.session()); assertNull(restored.laundrySession())
        }
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
            val record = records.read(key)!!.withField("sessionId", null).withField("schemaVersion", null)
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
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.let { it.withField("sessions",
            jsonArrayOf(listOf(it.arrayValue("sessions").objectValue(0).withField("createdAt", null)))) }.toString()
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
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.let { it.withField("sessions",
            jsonArrayOf(listOf(it.arrayValue("sessions").objectValue(0).withField("completionObservedAt", null)))) }.toString()
        assertEquals(listOf(completed.copy(completionObservedAtMillis = null)), LifeDeviceAccountStore(records).laundrySessions())
    }
    @Test fun legacyHiddenUnknownCreationRemainsReadableForCleanup() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val hidden = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Creating, createSent = true,
            creationUncertain = true, islandDismissed = true)
        store.saveLaundrySession(hidden)
        assertEquals(hidden, LifeDeviceAccountStore(records).laundrySession())
        store.saveLaundrySessions(listOf(hidden))
        assertEquals(listOf(hidden), LifeDeviceAccountStore(records).laundrySessions())
        records.values["laundry_sessions"] = records.read("laundry_sessions")!!.let { it.withField("sessions",
            jsonArrayOf(listOf(it.arrayValue("sessions").objectValue(0).withField("creationUncertain", null)
                .withField("islandDismissed", null)))) }.toString()
        val legacy = LifeDeviceAccountStore(records).laundrySessions().single()
        assertFalse(legacy.islandDismissed); assertFalse(legacy.creationUncertain); assertTrue(legacy.createSent)
    }
    @Test fun dismissedUnknownCreationIsDeletedAfterLoginExpiryWithoutDeletingOtherOrders() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val unknown = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Creating, createSent = true,
            creationUncertain = true)
        val running = unknown.copy(phase = LaundryPhase.Running, orderNo = "other-order", orderId = "other-id", sessionId = "other")
        store.saveLaundrySessions(listOf(unknown, running)); store.clearAccount()
        store.removeLaundrySession(unknown.sessionId)
        val remaining = records.read("laundry_sessions")!!.arrayValue("sessions")
        assertEquals(1, remaining.size); assertEquals("other", remaining.objectValue(0).stringValue("sessionId"))
        store.saveAccount(account)
        assertEquals(listOf(running), LifeDeviceAccountStore(records).laundrySessions())
    }
    @Test fun dismissingLegacySingleProgressErasesItWithoutCredentials() {
        val records = Records(); val store = LifeDeviceAccountStore(records); store.saveAccount(account)
        val unknown = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Creating, createSent = true,
            creationUncertain = true)
        store.saveLaundrySession(unknown); store.clearAccount()
        store.removeLaundrySession(unknown.sessionId)
        assertFalse(records.values.containsKey("laundry")); assertNull(store.account())
    }
}
