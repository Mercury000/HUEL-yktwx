package com.mercury.yktwx.data.life

import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import org.junit.Assert.*
import org.junit.Test

class Hui798AccountStoreTest {
    @Test fun unversionedFlatRecordsReadThroughCurrentDtoAndUnknownStartCanBeRemoved() {
        val records = Records()
        records.data["account"] = """{"phone":"13800000000","token":"fixture-token"}"""
        records.data["sessions"] = """{"owner":"13800000000","items":[{"deviceId":"fixture-device","name":"用水","phase":"Starting","attemptedAt":100000,"acknowledged":true,"sessionId":"old-id"}]}"""
        val store = Hui798AccountStore(records)
        val restored = store.sessions().single()
        assertEquals("old-id", restored.sessionId)
        assertTrue(restored.uncertain)
        assertTrue(restored.startAcknowledged)
        assertTrue(restored.canDismissException)
        store.clearAccount()
        store.removeSession("old-id")
        assertEquals(2L, records.read("sessions")!!.strictLongValue("schemaVersion"))
        store.saveAccount(Hui798Account("13800000000", "renewed-token"))
        assertTrue(store.sessions().isEmpty())
    }
    private class Records : LifeDeviceRecords {
        val data = mutableMapOf<String, String>()
        var failWrite = false
        override fun read(key: String) = data[key]?.let(::parseObject)
        override fun write(values: Map<String, String>) { if (failWrite) throw IllegalStateException("storage unavailable"); data.putAll(values) }
        override fun remove(key: String) { data.remove(key) }
    }
    @Test fun unknownStartDeletionWorksAfterExpiryAndCannotReappearWhenCredentialsAreRenewed() {
        val records = Records(); val store = Hui798AccountStore(records)
        val account = Hui798Account("13800000000", "fixture-token")
        val unknown = Hui798Session(Hui798Device("device-1", "用水"), attemptedAtMillis = 100_000, uncertain = true)
        val running = unknown.copy(device = Hui798Device("device-2", "用水"), sessionId = "running", phase = WaterPhase.Running,
            serverTime = 100_000, uncertain = false)
        store.saveAccount(account); store.saveSessions(listOf(unknown, running)); store.clearAccount()
        store.removeSession(unknown.sessionId)
        assertFalse(records.read("sessions")!!.toString().contains(unknown.sessionId))
        store.saveAccount(account); assertEquals(listOf(running), store.sessions())
    }
    @Test fun failedReplacementPreservesPreviousCredentialsAndRecoveryRecords() {
        val records = Records(); val store = Hui798AccountStore(records)
        val account = Hui798Account("13800000000", "fixture-token")
        val session = Hui798Session(Hui798Device("fixture-device", "用水设备"), WaterPhase.Ended, 100_000)
        store.saveAccount(account); store.saveSessions(listOf(session)); records.failWrite = true
        try { store.saveAccount(Hui798Account("13900000000", "other-token")); fail("Write should fail") }
        catch (_: IllegalStateException) {}
        assertEquals(account, store.account()); assertEquals(listOf(session), store.sessions())
    }
    @Test fun removalErasesExpiredAccountRecoveryRecords() {
        val records = Records(); val store = Hui798AccountStore(records)
        store.saveAccount(Hui798Account("13800000000", "fixture-token"))
        store.saveSessions(listOf(Hui798Session(Hui798Device("fixture-device", "用水设备"), WaterPhase.Ended, 100_000)))
        store.clearAccount(); store.removeAccount()
        assertTrue(records.data.isEmpty())
    }
    @Test fun accountIdentityRoundTripsAndLegacyAccountsRequireServerLookup() {
        val records = Records(); val store = Hui798AccountStore(records)
        val account = Hui798Account("13800000000", "fixture-token", "fixture-user")
        store.saveAccount(account); assertEquals(account, Hui798AccountStore(records).account())
        records.data["account"] = records.read("account")!!.withField("userId", null).toString()
        assertEquals(account.copy(userId = null), store.account())
        store.saveAccount(account); assertEquals(account, store.account())
    }
    @Test fun authenticationLossPreservesOwnedProgressButSwitchingAccountsDoesNotAdoptIt() {
        val store = Hui798AccountStore(Records())
        val first = Hui798Account("13800000000", "first-token")
        val session = Hui798Session(Hui798Device("fixture-device", "用水设备"), WaterPhase.Running, 1_700_000_000_000,
            true, 1_700_000_000_000, 1_700_000_000_000, sessionId = "fixture-session")
        store.saveAccount(first); store.saveSessions(listOf(session)); store.clearAccount()
        assertTrue(store.sessions().isEmpty())
        store.saveAccount(first.copy(token = "renewed-token")); assertEquals(listOf(session), store.sessions())
        store.saveAccount(Hui798Account("13900000000", "other-token")); assertTrue(store.sessions().isEmpty())
    }
    @Test fun completedProgressAndRemovalRoundTripWithoutCrossingIntoPangguiRecords() {
        val records = Records()
        val store = Hui798AccountStore(records)
        store.saveAccount(Hui798Account("13800000000", "fixture-token"))
        val session = Hui798Session(Hui798Device("fixture-device", "用水设备"), WaterPhase.Ended, 1_700_000_000_000,
            true, 1_700_000_000_000, 1_700_000_000_000, 1_700_000_001_000, "fixture-session")
        store.saveSessions(listOf(session)); assertEquals(listOf(session), store.sessions())
        assertFalse(records.data.keys.any { it in setOf("water", "water_sessions", "laundry", "laundry_sessions") })
        store.saveSessions(emptyList()); assertTrue(store.sessions().isEmpty())
    }
    @Test fun expiredAccountCanDismissCompletedProgressWithoutRemovingItsOtherSession() {
        val store = Hui798AccountStore(Records())
        val account = Hui798Account("13800000000", "fixture-token")
        val running = Hui798Session(Hui798Device("fixture-device", "用水设备"), WaterPhase.Running, 1_700_000_000_000,
            true, 1_700_000_000_000, 1_700_000_000_000, sessionId = "running")
        val completed = running.copy(phase = WaterPhase.Ended, endedAtMillis = 1_700_000_001_000, sessionId = "completed")
        store.saveAccount(account); store.saveSessions(listOf(completed, running)); store.clearAccount()
        store.saveSessions(listOf(running))
        store.saveAccount(account.copy(token = "renewed-token"))
        assertEquals(listOf(running), store.sessions())
    }
}
