package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.FeeRoomAction
import com.mercury.yktwx.FeeStage
import com.mercury.yktwx.FeeUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun FeePage(
    state: FeeUiState,
    settings: ThemeSettings,
    onBack: () -> Unit,
    onSelect: (FeeLevel, FeeOption) -> Unit,
    onRefreshQuery: () -> Unit,
    onRetry: () -> Unit,
    onCreate: (Long) -> Unit,
    onMethod: (FeeMethod) -> Unit,
    onPayAccount: (String, String) -> Unit,
    onFinish: () -> Unit,
    onCancelPayment: () -> Unit,
    onRoomAction: () -> Unit = {},
    onConfirmRoomAction: () -> Unit = {},
    onDismissRoomDialog: () -> Unit = {},
    onConsumeRoomNotice: () -> Unit = {},
    interactive: Boolean = true,
    onContinuePayment: (FeeMethod) -> Unit = {},
) {
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    var amount by remember(state.config) { mutableStateOf("") }
    val config = state.config
    val cents = RechargeMoney.cents(amount)
    val orderAmount = state.order?.pending?.amountCents ?: state.paymentRetry?.amountCents ?: cents ?: 0
    val error = state.error
    val roomInteractive = interactive && !state.roomBusy && state.roomDialog == null
    val context = LocalContext.current
    LaunchedEffect(state.roomNotice, interactive) {
        if (interactive) state.roomNotice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onConsumeRoomNotice()
        }
    }
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(
                    title = config?.name ?: "生活缴费", largeTitle = config?.name ?: "生活缴费",
                    scrollBehavior = scroll, color = Color.Transparent,
                    navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") } },
                    actions = {
                        if (config?.roomBindingEnabled == true) {
                            val enabled = interactive && state.roomActionEnabled && state.roomDialog == null
                            val description = if (state.roomAction == FeeRoomAction.Unbind) "解绑房间" else "保存房间"
                            IconButton(onClick = onRoomAction, enabled = enabled,
                                modifier = Modifier.size(48.dp).semantics(mergeDescendants = true) {
                                    contentDescription = description
                                    if (!enabled) disabled()
                                }) {
                                if (state.roomBusy) CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                else Icon(
                                    if (state.roomAction == FeeRoomAction.Unbind) AppIcons.UnbindRoom else AppIcons.SaveRoom,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
                                )
                            }
                        }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                PullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = { if (roomInteractive) { if (config == null) onRetry() else onRefreshQuery() } },
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    topAppBarScrollBehavior = scroll,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = AppRefreshTexts,
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize().overScrollVertical().imePadding(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                            bottom = padding.calculateBottomPadding() + 28.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (state.preview) item { Text("界面预览 · 不发起缴费", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp) }
                        if (config == null) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        if (state.loading) CircularProgressIndicator()
                                        if (error != null) InlineError(error, actionLabel = "重试", onAction = onRetry)
                                        else Text("正在读取缴费信息", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                        } else {
                            val visibleLevels = state.levels.filterNot { it.key in state.hiddenLevels }
                            if (visibleLevels.isNotEmpty()) item {
                                Card(Modifier.fillMaxWidth()) {
                                    FeeTargetOptions(state, onSelect, roomInteractive && state.stage == FeeStage.Query)
                                }
                            }
                            state.query?.let { query ->
                                item {
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            query.display.forEach { (label, value) ->
                                                Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                            }
                                            if (query.display.isEmpty()) Text("暂无费用信息", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                    }
                                }
                            }
                            if (state.query?.thirdParty != null && state.query.canPay) item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text("缴费金额", fontWeight = FontWeight.SemiBold)
                                        PaymentAmountInput(config.amountRules, amount, { amount = it },
                                            enabled = roomInteractive && !state.loading && !state.selecting && state.stage == FeeStage.Query)
                                        if (config.amountRules.maximumCents < 999900L) Text("最高 ¥ " + RechargeMoney.label(config.amountRules.maximumCents),
                                            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        Button(onClick = { cents?.let(onCreate) }, modifier = Modifier.fillMaxWidth(),
                                            enabled = roomInteractive && !state.preview && !state.loading && !state.selecting && state.stage == FeeStage.Query && config.amountRules.amountError(cents) == null,
                                            colors = ButtonDefaults.buttonColorsPrimary()) {
                                            Text(if (cents != null && config.amountRules.amountError(cents) == null) "缴费 ¥ " + RechargeMoney.yuan(cents) else "缴费")
                                        }
                                    }
                                }
                            }
                            if (error != null && state.stage == FeeStage.Query) item {
                                InlineError(error, actionLabel = "重试", onAction = onRefreshQuery,
                                    modifier = Modifier.padding(horizontal = 4.dp))
                            }
                        }
                    }
                }
            }
            val roomDialog = state.roomDialog
            val replacing = roomDialog?.action == FeeRoomAction.Save && roomDialog.previous != null
            OverlayDialog(
                show = interactive && roomDialog != null && state.stage == FeeStage.Query,
                title = when {
                    roomDialog?.action == FeeRoomAction.Unbind -> "解绑房间"
                    replacing -> "更换房间"
                    else -> "保存房间"
                },
                summary = roomDialog?.let {
                    when {
                        it.action == FeeRoomAction.Unbind -> "解绑「${it.target.dialogLabel}」"
                        replacing -> "将「${it.previous!!.dialogLabel}」更换为「${it.target.dialogLabel}」"
                        else -> "保存「${it.target.dialogLabel}」，下次自动选择"
                    }
                },
                onDismissRequest = onDismissRoomDialog,
                renderInRootScaffold = true,
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onDismissRoomDialog, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(onClick = onConfirmRoomAction, modifier = Modifier.weight(1f),
                        colors = if (roomDialog?.action == FeeRoomAction.Unbind)
                            ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.error, contentColor = Color.White)
                        else ButtonDefaults.buttonColorsPrimary()) {
                        Text(when {
                            roomDialog?.action == FeeRoomAction.Unbind -> "解绑"
                            replacing -> "更换"
                            else -> "保存"
                        })
                    }
                }
            }
            FeePaymentDialogs(state, interactive, orderAmount, onMethod, onPayAccount,
                onCancelPayment, onFinish, onContinuePayment)
        }
    }
}

/** Native dropdown controls shared by every fee level. */
@Composable
private fun FeeTargetOptions(state: FeeUiState, onSelect: (FeeLevel, FeeOption) -> Unit, enabled: Boolean) {
    state.levels.filterNot { it.key in state.hiddenLevels }.forEach { level ->
        val options = state.options[level.number].orEmpty()
        // An explicit placeholder prevents a preselected label from looking like
        // a committed choice before the corresponding query has run.
        OverlayDropdownPreference(
            title = level.name,
            items = listOf("请选择") + options.map { it.name },
            selectedIndex = options.indexOfFirst { it.id == state.selections[level.key] }.let { if (it < 0) 0 else it + 1 },
            enabled = enabled && !state.refreshing && options.isNotEmpty(),
            renderInRootScaffold = true,
            maxHeight = 320.dp,
            onSelectedIndexChange = { index -> options.getOrNull(index - 1)?.let { onSelect(level, it) } },
        )
    }
}
