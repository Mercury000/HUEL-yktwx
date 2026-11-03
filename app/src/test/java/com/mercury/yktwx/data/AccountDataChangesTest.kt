package com.mercury.yktwx.data

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountDataChangesTest {
    private val changes = AccountDataChanges()
    private val identity = AccountIdentity("user", 1)

    @Test fun lateSubscribersReceiveAllAffectedResourcesAfterCoalescedChanges() = runTest {
        changes.reset(SessionState(restored = true, username = "user", generation = 1))
        changes.transaction(identity, TransactionUpdate("user", income = true))
        changes.transaction(identity, TransactionUpdate("user", balance = false, hall = true,
            project = FeeProject.Network, income = false))
        val received = mutableListOf<Set<AccountResource>>()
        changes.observe(backgroundScope) { received += it }
        runCurrent()
        assertEquals(1, received.size)
        assertEquals(setOf(AccountResource.Data.Balance, AccountResource.Data.IncomeBills,
            AccountResource.Data.ExpenseBills, AccountResource.Data.Profile, AccountResource.Hall(FeeProject.Network)), received.single())
    }

    @Test fun oldGenerationsCannotInvalidateSameAccountAfterRelogin() {
        changes.reset(SessionState(restored = true, username = "user", generation = 1))
        changes.transaction(identity, TransactionUpdate("user"))
        changes.reset(SessionState(restored = true, username = "user", generation = 2))
        changes.transaction(identity, TransactionUpdate("user"))
        assertTrue(changes.state.value.versions.isEmpty())
        changes.balance(AccountIdentity("user", 2), BalanceChange("user", true))
        assertEquals(setOf(AccountResource.Data.IncomeBills), changes.state.value.versions.keys)
        changes.reset(SessionState(restored = true, generation = 3))
        changes.transaction(AccountIdentity("user", 2), TransactionUpdate("user"))
        assertTrue(changes.state.value.versions.isEmpty())
    }

    @Test fun balanceDeltasDoNotClaimPaymentSuccessOrInvalidateCardAndProfileReads() {
        changes.reset(SessionState(restored = true, username = "user", generation = 1))
        changes.balance(identity, BalanceChange("user", false))
        assertEquals(setOf(AccountResource.Data.ExpenseBills), changes.state.value.versions.keys)
        changes.transaction(identity, TransactionUpdate("another-user"))
        assertEquals(setOf(AccountResource.Data.ExpenseBills), changes.state.value.versions.keys)
    }
}
