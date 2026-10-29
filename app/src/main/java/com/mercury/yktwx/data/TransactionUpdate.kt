package com.mercury.yktwx.data

/** Emitted once on a confirmed result, independent of how its sheet is closed. */
data class TransactionUpdate(val username: String, val balance: Boolean = true, val hall: Boolean = false) {
    override fun toString() = "TransactionUpdate([redacted])"
}
