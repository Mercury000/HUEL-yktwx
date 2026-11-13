package com.mercury.yktwx.state

import com.mercury.yktwx.data.*
import kotlinx.serialization.Serializable

@Serializable
internal data class RechargeFormDraft(val amount: String = "", val selected: Boolean = true) {
    val valid get() = amount.length <= 32
}

@Serializable
internal data class RecipientReference(val account: String, val target: String) {
    val valid get() = RechargeProtocol.validId(account) && RechargeProtocol.validId(target)
    fun recipient() = RechargeRecipient(account, target, "原收款账户", "", "校园卡")
    companion object {
        fun from(recipient: RechargeRecipient) = RecipientReference(recipient.cardAccount, recipient.targetId)
    }
}

@Serializable
internal data class RechargeOrderReference(
    val target: String, val fee: String, val amount: Long, val id: String, val payId: String?,
    val recipient: RecipientReference? = null,
) {
    val valid get() = RechargeProtocol.validId(target) && RechargeProtocol.validId(fee) && amount > 0 &&
        RechargeProtocol.validId(id) && (payId == null || RechargeProtocol.validId(payId)) &&
        (recipient == null || recipient.valid && recipient.target == target)
    fun pending(owner: String) = PendingRecharge(owner, target, fee, amount, id, payId, recipient?.recipient())
    companion object {
        fun from(pending: PendingRecharge): RechargeOrderReference? = pending.orderId?.let {
            RechargeOrderReference(pending.targetId, pending.feeItemId, pending.amountCents, it, pending.payId,
                pending.recipient?.let(RecipientReference::from)).takeIf { reference -> reference.valid }
        }
    }
}

@Serializable
internal data class RechargeSavedState(
    val visible: Boolean = false, val target: String? = null, val recipient: RecipientReference? = null,
    val form: RechargeFormDraft = RechargeFormDraft(), val order: RechargeOrderReference? = null,
    val interrupted: Boolean = false, val submittedAmount: Long = 0,
) {
    val valid get() = form.valid && (target == null || RechargeProtocol.validId(target)) &&
        (recipient == null || recipient.valid && recipient.target == target) && (order == null || order.valid) &&
        submittedAmount >= 0
}
