package com.mercury.yktwx.state

import com.mercury.yktwx.data.*
import kotlinx.serialization.Serializable

@Serializable
internal data class RecipientFormDraft(
    val type: RecipientQueryType = RecipientQueryType.StudentNumber, val identifier: String = "", val amount: String = "",
) {
    val valid get() = identifier.length <= 32 && amount.length <= 32
}

@Serializable
internal data class CardOverlaySavedState(
    val visible: Boolean = false, val action: CampusCardAction = CampusCardAction.ManageState,
    val card: String? = null, val fixedCard: String? = null,
    val form: RecipientFormDraft = RecipientFormDraft(), val recipients: List<RecipientReference> = emptyList(),
    val selectedTarget: String? = null, val interrupted: Boolean = false,
) {
    val valid get() = form.valid && (card == null || RechargeProtocol.validId(card)) &&
        (fixedCard == null || RechargeProtocol.validId(fixedCard)) && recipients.size <= 12 && recipients.all { it.valid } &&
        recipients.map { it.target }.distinct().size == recipients.size &&
        (selectedTarget == null || recipients.any { it.target == selectedTarget })
}
