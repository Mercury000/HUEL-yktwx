package com.mercury.yktwx.data.life

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Hui798AccountStoreTest {
    private class Records : LifeDeviceRecords {
        val data = mutableMapOf<String, String>()
        var failWrite = false
        override fun read(key: String) = data[key]?.let(::JSONObject)
        override fun write(values: Map<String, String>) { if (failWrite) throw IllegalStateException("storage unavailable"); data.putAll(values) }
        override fun remove(key: String) { data.remove(key) }
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
        records.data["account"] = records.read("account")!!.apply { remove("userId") }.toString()
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
