package com.mercury.yktwx.state

import kotlinx.serialization.Serializable

/** The already displayed local notice amount, never a payment code or transaction command. */
@Serializable
internal data class PaymentNoticeSavedState(val amount: Long? = null) {
    val valid get() = amount == null || amount > 0
}
