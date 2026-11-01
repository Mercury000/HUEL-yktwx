package com.mercury.yktwx.ui

import com.mercury.yktwx.data.BalanceChange
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.TransactionUpdate

internal enum class ReadPage { Payment, Hall, Bill, My, Profile, Account }

/** Routes read invalidation; the shell supplies the current owner and eligible visible page. */
internal class TransactionReadCoordinator(
    private val invalidateBalance: () -> Unit,
    private val invalidateBills: (Boolean?) -> Unit,
    private val invalidateMy: () -> Unit,
    private val invalidateHall: (FeeProject?) -> Unit,
    private val activate: (ReadPage) -> Unit,
) {
    fun onTransaction(update: TransactionUpdate, owner: String?, page: ReadPage?) {
        if (owner.isNullOrBlank() || update.username != owner) return
        if (update.balance) invalidateBalance()
        invalidateLedger(update.income)
        if (update.hall) invalidateHall(update.project)
        when (page) {
            ReadPage.Bill, ReadPage.My, ReadPage.Profile -> activate(page)
            ReadPage.Hall -> if (update.hall) activate(page)
            else -> Unit
        }
    }

    fun onBalanceChange(change: BalanceChange, owner: String?, page: ReadPage?) {
        if (owner.isNullOrBlank() || change.username != owner) return
        invalidateLedger(change.increased)
        // A balance read already obtained the new value and does not establish payment success.
        if (page == ReadPage.Bill || page == ReadPage.My) activate(page)
    }

    private fun invalidateLedger(income: Boolean?) {
        invalidateBills(income)
        invalidateMy()
    }
}
