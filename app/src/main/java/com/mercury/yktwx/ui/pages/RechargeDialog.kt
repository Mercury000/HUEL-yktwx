package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.mercury.yktwx.data.FormField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewNotes
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.RechargeUiState
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import com.mercury.yktwx.ui.components.SheetTitle
import com.mercury.yktwx.ui.components.SheetPageContent
import com.mercury.yktwx.ui.components.rememberOverlaySession
import com.mercury.yktwx.ui.components.rememberWhileShown
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

private enum class RechargeSheetPage { Amount, Result }

private data class RechargeResultDisplay(
    val phase: RechargePhase, val loading: Boolean, val amountCents: Long, val error: String?, val subject: String,
)

@Composable
fun RechargeDialog(
    state: RechargeUiState,
    onDismiss: () -> Unit,
    onSubmit: (Long) -> Unit,
    onFinish: () -> Unit,
    onCheck: (() -> Unit)? = null,
    onInputEdited: (FormField) -> Unit = {},
) {
    val hasAttempt = state.pending != null
    // Keep the amount page mounted while the cashier request is in flight. The
    // existing sheet-page transition then moves directly to either WeChat or
    // the official rejection, instead of animating a second state inside it.
    val isResult = hasAttempt && state.phase != RechargePhase.Creating
    val loadingConfig = state.loading && !hasAttempt && state.config == null
    val show = state.visible || loadingConfig
    val session = rememberOverlaySession(show)
    var amount by remember(session, state.config) { mutableStateOf("") }
    var recipientSelected by remember(session, state.config) { mutableStateOf(true) }
    val targetPage = rememberWhileShown(show, if (isResult) RechargeSheetPage.Result else RechargeSheetPage.Amount)
    val title = rememberWhileShown(show,
        if (isResult) null else if (state.config?.recipient != null) "代人充值" else "卡片充值")
    OverlayBottomSheet(
        show = show,
        startAction = { key(session) { SheetTitle(title) } },
        onDismissRequest = { if (hasAttempt) onFinish() else onDismiss() },
        renderInRootScaffold = true,
        // The result has its own spacing; avoid adding the sheet's defaults again.
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        key(session) { SheetPageContent(targetPage,
            Modifier.bottomSheetSafeArea()) { page, interactive ->
        val resultPage = page == RechargeSheetPage.Result
        val enabled = interactive && state.visible
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .then(if (resultPage) Modifier.padding(horizontal = 24.dp)
                    else Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (resultPage) {
                val result = rememberWhileShown(show && isResult, RechargeResultDisplay(
                    state.phase, state.loading, state.pending?.amountCents ?: 0, state.error,
                    if (state.pending?.recipient != null) "代充值" else "充值"))
                PaymentResultContent(result.phase, result.loading, result.amountCents, result.error,
                    { if (enabled && isResult) onFinish() }, subject = result.subject, enabled = enabled && isResult,
                    onCheck = onCheck)
            } else {
                val config = rememberWhileShown(show && !isResult, state.config)
                val loading = rememberWhileShown(show && !isResult, state.loading)
                val preview = rememberWhileShown(show && !isResult, UiPreview.enabled && state.preview)
                val error = rememberWhileShown(show && !isResult, state.error)
                PaymentFormContent(config == null && loading) {
                if (config != null) {
                    if (UiPreview.enabled && preview) PreviewNotes.Recharge()
                    config.recipient?.let { recipient ->
                        RecipientSelectionCard(recipient, recipientSelected, { recipientSelected = !recipientSelected }, enabled && !loading)
                    }
                    PaymentAmountForm(config, amount, { amount = it }, listOf("微信"), 0, {}, loading,
                        !preview && recipientSelected, error, onSubmit, enabled = enabled, onInputEdited = onInputEdited,
                        submitting = state.pending != null && state.phase == RechargePhase.Creating)
                }
                }
            }
        }
        } }
    }
}
