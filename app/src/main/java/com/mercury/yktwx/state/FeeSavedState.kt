package com.mercury.yktwx.state

import com.mercury.yktwx.FeeRoomAction
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.data.*
import kotlinx.serialization.Serializable

@Serializable
internal data class FeeEntry(val project: FeeProject, val fixed: Map<String, String>? = null, val saveOnly: Boolean = false)

@Serializable
internal data class FeeFormDraft(
    val amount: String = "", val amountStep: Boolean = false, val saveCommon: Boolean = false,
    val bindingPending: Boolean = false, val targetSelected: Boolean = true, val methodCode: String = "ACCOUNTTSM",
    val accountType: String? = null,
    val retryMethodId: String? = null, val retryMethodCode: String? = null,
) {
    val valid get() = amount.length <= 32 && methodCode in setOf("ACCOUNTTSM", "TSM") &&
        (accountType == null || RechargeProtocol.validId(accountType)) &&
        (retryMethodId == null || retryMethodId.matches(Regex("[0-9]{1,12}"))) &&
        (retryMethodCode == null || retryMethodCode in setOf("ACCOUNTTSM", "TSM"))
}

/** Query-only reference. The original third_party payload and payment keyboard are never saved. */
@Serializable
internal data class FeeOrderReference(
    val id: String, val payId: String?, val project: FeeProject, val amount: Long, val targetFingerprint: String,
) {
    val valid get() = RechargeProtocol.validId(id) && (payId == null || payId.matches(Regex("[0-9]{1,12}"))) &&
        amount > 0 && targetFingerprint.matches(Regex("[a-f0-9]{64}"))
    fun pending(owner: String) = PendingRecharge(owner, "", project.id, amount, id, payId, targetFingerprint = targetFingerprint)
    companion object {
        fun from(order: PendingRecharge, project: FeeProject): FeeOrderReference? = runCatching {
            FeeOrderReference(order.orderId ?: return null, order.payId, project, order.amountCents,
                order.targetFingerprint ?: feeTargetFingerprint(order.targetId)).takeIf { it.valid }
        }.getOrNull()
    }
}

@Serializable
internal data class FeeSavedState(
    val entry: FeeEntry? = null, val selections: Map<String, String> = emptyMap(), val form: FeeFormDraft = FeeFormDraft(),
    val confirmation: FeeRoomAction? = null, val previous: Map<String, String>? = null,
    val order: FeeOrderReference? = null, val interruptedPayment: Boolean = false,
    val paymentPrompt: FeeStage? = null,
) {
    val valid get() = form.valid && (entry == null ||
        (entry.fixed == null || FeeTargetReference(entry.project, entry.fixed).valid) &&
        (selections.isEmpty() || FeeTargetReference(entry.project, selections).valid) &&
        (previous == null || FeeTargetReference(entry.project, previous).valid) &&
        (order == null || order.valid && order.project == entry.project)) &&
        (paymentPrompt == null || paymentPrompt in setOf(FeeStage.Password, FeeStage.Methods) && order != null)
}
