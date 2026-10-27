package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** The same sheet and paid-result content used by recharge and living-fee payments. */
@Composable
internal fun PaymentSuccessDialog(amountCents: Long?, onDismiss: () -> Unit) {
    var lastAmount by remember { mutableStateOf(0L) }
    SideEffect { if (amountCents != null) lastAmount = amountCents }
    val scroll = rememberScrollState()
    OverlayBottomSheet(
        show = amountCents != null,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        // Remember the amount for the closing animation after the result is dismissed.
        val amount = amountCents ?: lastAmount
        Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(scroll).padding(horizontal = 24.dp)) {
            PaymentResultContent(RechargePhase.Paid, false, amount, null, onDismiss, subject = "支付", enabled = amountCents != null)
        }
    }
}
