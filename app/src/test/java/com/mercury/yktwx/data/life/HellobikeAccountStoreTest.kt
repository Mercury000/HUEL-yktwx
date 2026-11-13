package com.mercury.yktwx.data.life

import org.junit.Assert.*
import org.junit.Test

class HellobikeAccountStoreTest {
    @Test fun pendingRecordsSurviveExpiryLogoutAndOwnerRoundTripsWithoutExposingOtherOwners() {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
        store.clearAccount()
        assertNull(store.load().account); assertEquals(listOf(fixtureBikeSession), store.load().sessions)
        val other = fixtureBikeAccount.copy(phone = "13100000000", userGuid = "fixture-other-user-01")
        store.saveAccount(other)
        assertTrue(store.load().sessions.isEmpty()); assertNull(store.load().account?.takeIf { it.userGuid == fixtureBikeAccount.userGuid })
        store.saveSessions(other.userGuid, listOf(fixtureBikeSession.copy(rideGuid = "other-ride", sessionId = "other-session")))
        store.removeAccount(); assertNull(store.load().account); assertTrue(store.load().sessions.isEmpty())
        store.saveAccount(fixtureBikeAccount)
        assertEquals(listOf(fixtureBikeSession), store.load().sessions)
        assertEquals("other-session", store.load().retainedSessions[other.userGuid]?.single()?.sessionId)
    }
    @Test fun failedDiskCommitsPreserveBothCredentialsAndPendingOperations() {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
        val before = store.load(); records.failWrite = true
        for (operation in listOf<() -> Unit>({ store.saveSessions(fixtureBikeAccount.userGuid, emptyList()) },
            { store.saveAccount(fixtureBikeAccount.copy(userGuid = "other-owner")) }, { store.removeAccount() })) {
            try { operation(); fail("A failed commit must be reported") } catch (_: IllegalStateException) {}
            assertEquals(before, store.load())
        }
    }
    @Test fun aLateWriteFromAnotherAccountCannotReplaceCurrentProgress() {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
        try { store.saveSessions("another-owner", emptyList()); fail("Owner must match") }
        catch (e: HellobikeException) { assertTrue(e.storageFailure) }
        assertEquals(listOf(fixtureBikeSession), store.load().sessions)
    }
    @Test fun corruptRecordsRequireRecoveryAndAcknowledgementDoesNotChangeAnyOrderState() {
        val records = BikeRecords(); val store = records.store()
        records.values["state"] = """{"schemaVersion":2,"ownerGuid":"","sessions":[{"rideGuid":"broken"}]}"""
        try { store.load(); fail("Corruption cannot look like no pending order") }
        catch (e: HellobikeException) { assertTrue(e.storageFailure) }
        store.recoverUnreadable("new-fixture-client")
        assertTrue(store.load().recoveryRequired)
        store.saveAccount(fixtureBikeAccount)
        store.saveSessions(fixtureBikeAccount.userGuid, listOf(fixtureBikeSession))
        store.acknowledgeRecovery()
        assertFalse(store.load().recoveryRequired); assertEquals(listOf(fixtureBikeSession), store.load().sessions)
    }
}
