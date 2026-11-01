package com.mercury.yktwx.ui

import com.mercury.yktwx.data.BalanceChange
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.TransactionUpdate
import org.junit.Assert.*
import org.junit.Test

class TransactionReadCoordinatorTest {
    private val events = mutableListOf<String>()
    private val coordinator = TransactionReadCoordinator(
        invalidateBalance = { events += "balance" },
        invalidateBills = { events += "bills:$it" },
        invalidateMy = { events += "my" },
        invalidateHall = { events += "hall:${it?.id}" },
        activate = { events += "activate:$it" },
    )

    @Test fun queuedEventsFromAnotherOrLoggedOutAccountDoNotInvalidateCurrentReads() {
        for (owner in listOf("new-user", null, "")) {
            coordinator.onTransaction(TransactionUpdate("old-user", hall = true), owner, ReadPage.My)
            coordinator.onBalanceChange(BalanceChange("old-user", true), owner, ReadPage.My)
        }
        assertTrue(events.isEmpty())
        coordinator.onTransaction(TransactionUpdate("new-user", income = true), "new-user", ReadPage.My)
        assertEquals(listOf("balance", "bills:true", "my", "activate:My"), events)
    }

    @Test fun coveredOrUnavailablePagesRetainInvalidationWithoutStartingReads() {
        coordinator.onTransaction(TransactionUpdate("user", hall = true, project = FeeProject.Network), "user", null)
        assertEquals(listOf("balance", "bills:null", "my", "hall:${FeeProject.Network.id}"), events)
        events.clear()
        coordinator.onBalanceChange(BalanceChange("user", false), "user", null)
        assertEquals(listOf("bills:false", "my"), events)
    }

    @Test fun onlyAffectedHallProjectsActivateAndConfirmedBalancesAreNotReadAgain() {
        coordinator.onTransaction(TransactionUpdate("user", balance = false, income = false), "user", ReadPage.Hall)
        assertEquals(listOf("bills:false", "my"), events)
        events.clear()
        coordinator.onTransaction(TransactionUpdate("user", balance = false, hall = true,
            project = FeeProject.Zhengdong, income = false), "user", ReadPage.Hall)
        assertEquals(listOf("bills:false", "my", "hall:${FeeProject.Zhengdong.id}", "activate:Hall"), events)
    }

    @Test fun balanceDeltasRefreshLedgerPagesWithoutActivatingProfileOrPaymentReads() {
        for (page in listOf(ReadPage.Profile, ReadPage.Payment, ReadPage.Account, ReadPage.Hall)) {
            coordinator.onBalanceChange(BalanceChange("user", true), "user", page)
            assertEquals(listOf("bills:true", "my"), events)
            events.clear()
        }
        coordinator.onBalanceChange(BalanceChange("user", false), "user", ReadPage.Bill)
        assertEquals(listOf("bills:false", "my", "activate:Bill"), events)
        events.clear()
        coordinator.onTransaction(TransactionUpdate("user", balance = false), "user", ReadPage.Profile)
        assertEquals(listOf("bills:null", "my", "activate:Profile"), events)
    }
}
