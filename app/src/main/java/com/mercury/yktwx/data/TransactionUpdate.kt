package com.mercury.yktwx.data

/** Emitted once on a confirmed result, independent of how its sheet is closed. */
data class TransactionUpdate(val username: String, val balance: Boolean = true, val hall: Boolean = false,
    val project: FeeProject? = null, val income: Boolean? = null) {
    override fun toString() = "TransactionUpdate([redacted])"
}

/** A balance delta invalidates reads, but is never a payment-success notification. */
data class BalanceChange(val username: String, val increased: Boolean) {
    override fun toString() = "BalanceChange([redacted])"
}
