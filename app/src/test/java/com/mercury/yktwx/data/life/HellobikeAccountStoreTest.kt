package com.mercury.yktwx.data.life

import org.junit.Assert.*
import org.junit.Test

class HellobikeAccountStoreTest {
    @Test fun cardPurchaseSurvivesExpiryAndAccountSwitchAndLogoutErasesIt() {
        val store = BikeRecords().store()
        val purchase = HellobikeCardPurchase("校园月卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", fixtureBikeRegion)
        store.saveCardPurchase(fixtureBikeAccount.userGuid, purchase)
        store.clearAccount()
        assertEquals(purchase, store.load().cardPurchases[fixtureBikeAccount.userGuid])
        val other = fixtureBikeAccount.copy(userGuid = "other-owner")
        store.saveAccount(other)
        try { store.saveCardPurchase(fixtureBikeAccount.userGuid, purchase); fail("Old owner cannot overwrite card purchase") }
        catch (e: HellobikeException) { assertTrue(e.storageFailure) }
        store.saveAccount(fixtureBikeAccount)
        assertEquals(purchase, store.load().cardPurchases[fixtureBikeAccount.userGuid])
        store.removeAccount(); assertTrue(store.load().cardPurchases.isEmpty())
    }
    @Test fun pendingRecordsSurviveExpiryAndOwnerRoundTripsWithoutExposingOtherOwners() {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
        store.clearAccount()
        assertNull(store.load().account); assertEquals(listOf(fixtureBikeSession), store.load().sessions)
        val other = fixtureBikeAccount.copy(phone = "13100000000", userGuid = "fixture-other-user-01")
        store.saveAccount(other)
        assertTrue(store.load().sessions.isEmpty()); assertNull(store.load().account?.takeIf { it.userGuid == fixtureBikeAccount.userGuid })
        store.saveSessions(other.userGuid, listOf(fixtureBikeSession.copy(rideGuid = "other-ride", sessionId = "other-session")))
        store.saveAccount(fixtureBikeAccount)
        assertEquals(listOf(fixtureBikeSession), store.load().sessions)
        assertEquals("other-session", store.load().retainedSessions[other.userGuid]?.single()?.sessionId)
    }
    @Test fun logoutErasesAllProgressAndReloginCannotRestoreIt() {
        for (expired in listOf(false, true)) {
            val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
            val other = fixtureBikeAccount.copy(phone = "13100000000", userGuid = "other-owner")
            store.saveAccount(other)
            val sessions = HellobikePhase.entries.flatMap { phase ->
                HellobikeSettlement.entries.map { settlement ->
                    fixtureBikeSession.copy(phase = phase, settlement = settlement, uncertain = true,
                        rideGuid = "$phase-$settlement", sessionId = "$phase-$settlement")
                }
            }
            store.saveSessions(other.userGuid, sessions)
            assertTrue(store.load().retainedSessions.isNotEmpty())
            if (expired) store.clearAccount()
            store.removeAccount()
            assertEquals(HellobikeStoredState(), HellobikeAccountStore(records).load())
            for (owner in listOf(other, fixtureBikeAccount)) {
                store.saveAccount(owner)
                assertTrue(store.load().sessions.isEmpty())
                assertTrue(store.load().retainedSessions.values.all { it.isEmpty() })
            }
        }
    }
    @Test fun logoutCanEraseUnreadableRecordsWithoutRecoveringThem() {
        val records = BikeRecords(); val store = records.store()
        records.values["state"] = "unreadable"
        store.removeAccount()
        assertEquals(HellobikeStoredState(), store.load())
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
