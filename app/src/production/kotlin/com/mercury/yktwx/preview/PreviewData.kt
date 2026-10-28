package com.mercury.yktwx.preview

import com.mercury.yktwx.*
import com.mercury.yktwx.data.*

// Shared callers are removed at compile time by UiPreview.enabled. No fixtures are compiled here.
internal object PreviewData {
    fun paymentState(lost: Boolean): PaymentUiState = unavailable()
    fun myState(): MyUiState = unavailable()
    fun rechargeConfiguration(): RechargeConfiguration = unavailable()
    fun recipientConfiguration(recipient: RechargeRecipient): RechargeConfiguration = unavailable()
    fun recipients(): List<RechargeRecipient> = unavailable()
    fun cardConfiguration(targetAccount: String?): CardServiceConfiguration = unavailable()
    fun passwordConfiguration(): CardPasswordConfiguration = unavailable()
    fun feeConfiguration(project: FeeProject): FeeConfiguration = unavailable()
    fun hallState(multiple: Boolean = false): DormUiState = unavailable()
    fun feeQuery(project: FeeProject, detail: Boolean, level: Int, selections: Map<String, String>): FeeQuery = unavailable()
    fun billState(period: BillDateFilter = BillDateFilter.currentMonth(), statsType: Int = 2): BillUiState = unavailable()
    fun billStats(period: BillDateFilter, type: Int): BillStatsState = unavailable()

    private fun unavailable(): Nothing = throw UnsupportedOperationException()
}
