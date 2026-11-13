package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import com.mercury.yktwx.data.FormField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.RechargePhase
import com.mercury.yktwx.data.FeeMethod
import com.mercury.yktwx.data.RechargeMoney
import com.mercury.yktwx.data.RechargeConfiguration
import com.mercury.yktwx.data.RechargeRecipient
import com.mercury.yktwx.ui.components.AmountTextField
import com.mercury.yktwx.ui.components.trackInputFocus
import com.mercury.yktwx.ui.components.AnimatedInlineError
import com.mercury.yktwx.ui.components.InlineErrorGroup
import com.mercury.yktwx.ui.components.PaymentSelectionCard
import com.mercury.yktwx.ui.components.ContentPlaceholder
import com.mercury.yktwx.ui.components.DashboardPlaceholder
import com.mercury.yktwx.ui.components.ReadContent
import com.mercury.yktwx.ui.components.UpdatedText
import com.mercury.yktwx.ui.components.LocalReadMotionEnabled
import com.mercury.yktwx.ui.components.SheetTitle
import com.mercury.yktwx.ui.components.rememberOverlaySession
import com.mercury.yktwx.ui.components.ReadLayout
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.LocalContentColor

/** All payment flows keep the same native sheet host, title and outer insets. */
@Composable
internal fun PaymentSheet(show: Boolean, title: String?, onDismissRequest: () -> Unit,
    insideMargin: DpSize = DpSize(20.dp, 12.dp), content: @Composable () -> Unit) {
    val session = rememberOverlaySession(show)
    OverlayBottomSheet(show = show, startAction = { key(session) { SheetTitle(title) } },
        onDismissRequest = onDismissRequest, renderInRootScaffold = true, insideMargin = insideMargin) { content() }
}

/** Method selection and submit behavior shared by fixed-price services and amount-entry payments. */
@Composable
internal fun PaymentMethodActions(methods: List<String>, selected: Int, onSelect: (Int) -> Unit,
    label: String, onSubmit: () -> Unit, enabled: Boolean, canSubmit: Boolean,
    error: String? = null, modifier: Modifier = Modifier, preserveError: Boolean = false) {
    InlineErrorGroup(error, 16.dp, preserveMessage = preserveError) {
        Text("支付方式", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (methods.isNotEmpty()) HallNeutralTabs(methods, selected) { if (enabled && it != selected) onSelect(it) }
        Button(onClick = { if (enabled && canSubmit) onSubmit() }, enabled = enabled && canSubmit,
            modifier = modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(label) }
    }
}

/** The amount and method form shared by hall fees and campus-card recharge. */
@Composable
internal fun PaymentAmountForm(
    config: RechargeConfiguration, amount: String, onAmountChange: (String) -> Unit,
    methods: List<String>, selectedMethod: Int, onMethod: (Int) -> Unit,
    loading: Boolean, canSubmit: Boolean, error: String?, onSubmit: (Long) -> Unit,
    onRetry: (() -> Unit)? = null,
    enabled: Boolean = true,
    onInputEdited: (FormField) -> Unit = {},
    submitting: Boolean = false,
) {
    val cents = RechargeMoney.cents(amount)
    val amountError = config.amountError(cents)
    val valid = amountError == null
    var focused by remember(config) { mutableStateOf(false) }
    var hasFocused by remember(config) { mutableStateOf(false) }
    var validate by remember(config) { mutableStateOf(false) }
    val validationMessage = amountError.takeIf { amount.isNotBlank() && validate && !focused }
    val keyboard = LocalSoftwareKeyboardController.current
    PaymentAmountHeader(config)
    PaymentAmountInput(config, amount, {
        if (it != amount) { validate = false; onAmountChange(it); onInputEdited(FormField.Amount) }
    }, enabled = enabled && !loading,
        onFocus = { next ->
            if (focused && !next && hasFocused) validate = true
            if (next) hasFocused = true
            focused = next
        })
    PaymentMethodActions(methods, selectedMethod, { onMethod(it); onInputEdited(FormField.PaymentMethod) },
        label = if (loading && submitting) "正在发起…" else if (cents != null && valid) "充值 ¥ " + RechargeMoney.yuan(cents) else "充值",
        onSubmit = { cents?.let { keyboard?.hide(); onSubmit(it) } }, enabled = enabled && !loading,
        canSubmit = canSubmit && valid, error = validationMessage ?: error)
}

/** Server limits wrap at the same readable size as other secondary text. */
@Composable
private fun PaymentAmountHeader(config: RechargeConfiguration) {
    val limits = listOfNotNull(config.minimumCents?.let { "最低 ¥ ${RechargeMoney.label(it)}" },
        config.maximumCents?.let { "单次最高 ¥ ${RechargeMoney.label(it)}" })
    val full = limits.joinToString(" · ")
    val compact = when {
        config.minimumCents != null && config.maximumCents != null ->
            "¥ ${RechargeMoney.label(config.minimumCents)}–${RechargeMoney.label(config.maximumCents)}"
        config.minimumCents != null -> "≥ ¥ ${RechargeMoney.label(config.minimumCents)}"
        config.maximumCents != null -> "≤ ¥ ${RechargeMoney.label(config.maximumCents)}"
        else -> ""
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fullWidth = remember(full, measurer, density.fontScale, density.density) {
        measurer.measure(AnnotatedString(full), TextStyle(fontSize = 12.sp)).size.width
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("充值金额", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (limits.isNotEmpty()) BoxWithConstraints(Modifier.weight(1f).testTag("payment-amount-limits")
            .semantics { contentDescription = full }, contentAlignment = Alignment.CenterEnd) {
            val width = with(density) { maxWidth.toPx() }
            val text = if (fullWidth <= width) full else compact
            UpdatedText(text, Modifier.fillMaxWidth(), numeric = false, fontSize = 12.sp,
                textAlign = TextAlign.End, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun PaymentAmountPlaceholder(modifier: Modifier = Modifier, config: RechargeConfiguration? = null) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "正在读取充值信息" },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .28f)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val amounts = config?.amountsCents ?: listOf(1000L, 5000L, 10000L)
            amounts.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { amount ->
                        PaymentControlPlaceholder(Modifier.weight(1f)) {
                            PaymentButtonGeometry("¥ " + RechargeMoney.label(amount))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (config?.allowCustom != false) PaymentControlPlaceholder {
                TextField(value = "", onValueChange = {}, label = paymentAmountLabel(config),
                    useLabelAsPlaceholder = true, singleLine = true, enabled = false,
                    modifier = Modifier.trackInputFocus(false).fillMaxWidth())
            }
        }
        DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .28f)
        HallNeutralTabsPlaceholder()
        PaymentControlPlaceholder { PaymentButtonGeometry("充值") }
    }
}

/** Hidden button templates use the library's real defaults without gesture or popup state. */
@Composable
internal fun PaymentButtonGeometry(label: String) {
    Box(Modifier.fillMaxWidth().defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
        .padding(ButtonDefaults.InsideMargin), contentAlignment = Alignment.Center) {
        Text(label, textAlign = TextAlign.Center)
    }
}

/** Boolean transition target keeps credentials, query payloads and orders out of animation state. */
@Composable
internal fun PaymentFormContent(pending: Boolean, content: @Composable ColumnScope.() -> Unit) {
    ReadContent(pending, Modifier.fillMaxWidth().testTag("payment-amount-form"), pending = { it }) { reading ->
        if (reading) PaymentAmountPlaceholder()
        else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** Measure with the same controls as the form so font scale and library defaults also match. */
@Composable
internal fun PaymentControlPlaceholder(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().drawWithContent {}.clearAndSetSemantics {}) {
            CompositionLocalProvider(LocalReadMotionEnabled provides false) { content() }
        }
        ContentPlaceholder(Modifier.matchParentSize(), cornerRadius = 16.dp)
    }
}

@Composable
internal fun RecipientSelectionCard(recipient: RechargeRecipient, selected: Boolean,
    onClick: () -> Unit, enabled: Boolean = true) {
    PaymentSelectionCard(selected, onClick, Modifier.testTag("recharge-recipient-${recipient.targetId}"), enabled) { contentColor ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UpdatedText(recipient.name, numeric = false, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = contentColor, modifier = Modifier.weight(1f))
            if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
        }
        UpdatedText(recipient.sno.ifBlank { recipient.cardAccount }, numeric = false, fontSize = 14.sp, color = contentColor)
        UpdatedText(recipient.accountName + " · " + recipient.targetId, numeric = false, fontSize = 13.sp,
            color = if (androidx.compose.foundation.isSystemInDarkTheme()) contentColor else contentColor.copy(alpha = .8f))
    }
}

@Composable
internal fun PaymentAmountInput(
    config: com.mercury.yktwx.data.RechargeConfiguration,
    amount: String,
    onAmountChange: (String) -> Unit,
    enabled: Boolean = true,
    onFocus: (Boolean) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cents = RechargeMoney.cents(amount)
    Column(Modifier.fillMaxWidth().testTag("payment-amount-input"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        config.amountsCents.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    Button(
                        onClick = {
                            onAmountChange(RechargeMoney.label(preset))
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }, modifier = Modifier.weight(1f), enabled = enabled,
                        colors = if (cents == preset) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    ) { UpdatedText(RechargeMoney.label(preset), prefix = "¥ ", textAlign = TextAlign.Center,
                        color = LocalContentColor.current) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (config.allowCustom) {
            AmountTextField(amount, onAmountChange, paymentAmountLabel(config), Modifier.fillMaxWidth(),
                enabled = enabled, integerOnly = config.integerOnly, onFocus = onFocus)
        }
    }
}

private fun paymentAmountLabel(config: RechargeConfiguration?) =
    if (config?.integerOnly == true) "自定义金额（元，仅限整数）" else "自定义金额（元）"

@Composable
internal fun PaymentResultContent(
    phase: RechargePhase,
    loading: Boolean,
    amountCents: Long,
    detail: String?,
    onFinish: () -> Unit,
    subject: String = "充值",
    retryMethods: List<FeeMethod> = emptyList(),
    onContinuePayment: (FeeMethod) -> Unit = {},
    enabled: Boolean = true,
    onCheck: (() -> Unit)? = null,
    loadingTitle: String? = null,
    paymentApp: String = "微信",
    retryMethodLabel: (FeeMethod) -> String = { it.name + "支付" },
    selectedRetryId: String? = null,
    selectedRetryCode: String? = null,
    onRetrySelection: ((FeeMethod?) -> Unit)? = null,
    preserveError: Boolean = false,
) {
    val busy = loading || phase == RechargePhase.AwaitingPayment
    val failed = phase in setOf(RechargePhase.Failed, RechargePhase.TimedOut) && !busy
    val queryError = phase == RechargePhase.Checking && !loading
    val methods = retryMethods.distinctBy { it.id to it.code }
    val canContinue = phase == RechargePhase.Failed && !busy && methods.isNotEmpty()
    var localRetryId by rememberSaveable { mutableStateOf<String?>(null) }
    var localRetryCode by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedMethod = methods.singleOrNull {
        it.id == (if (onRetrySelection == null) localRetryId else selectedRetryId) &&
            it.code == (if (onRetrySelection == null) localRetryCode else selectedRetryCode)
    }
    val title = loadingTitle.takeIf { loading } ?: when (phase) {
        RechargePhase.Paid -> subject + "成功"
        RechargePhase.Failed -> subject + "失败"
        RechargePhase.TimedOut -> "支付超时"
        RechargePhase.Checking -> if (loading) "正在查询结果" else "支付结果"
        RechargePhase.Creating -> "正在发起" + subject
        else -> "请在${paymentApp}中完成支付"
    }
    val color = when (phase) {
        RechargePhase.Paid -> MiuixTheme.colorScheme.primary
        RechargePhase.Failed -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 0.dp, bottom = if (busy) 36.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (!queryError) {
            ResultMark(phase, color)
        }
        if (!queryError) Spacer(Modifier.height(if (busy) 16.dp else 20.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(if (busy) 8.dp else 12.dp))
        Text(
            // Official result page: order.msg || transactionFailedDescription (status 0 and 2).
            if (failed) detail?.takeIf { it.isNotEmpty() } ?: "若扣款成功，请稍后在账单查看"
            else "¥ " + RechargeMoney.yuan(amountCents),
            modifier = Modifier.fillMaxWidth(),
            fontSize = if (failed) 17.sp else 36.sp,
            fontWeight = if (failed) FontWeight.Normal else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        AnimatedInlineError(detail.takeIf { queryError }, topSpacing = 16.dp, preserveMessage = preserveError)
        if (!queryError && !busy && !failed && phase != RechargePhase.Paid && detail != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                detail,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp, textAlign = TextAlign.Center,
            )
        }
        if (canContinue) {
            Spacer(Modifier.height(20.dp))
            Text("可选择以下方式重新支付", fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                methods.forEach { method ->
                    val selected = selectedMethod?.let { it.id == method.id && it.code == method.code } == true
                    PaymentSelectionCard(selected = selected, enabled = enabled,
                        onClick = {
                            val choice = method.takeUnless { selected }
                            if (onRetrySelection != null) onRetrySelection(choice)
                            else { localRetryId = choice?.id; localRetryCode = choice?.code }
                        }) { contentColor ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(retryMethodLabel(method), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                color = contentColor, modifier = Modifier.weight(1f))
                            if (selected) Text("已选择", fontSize = 12.sp, color = contentColor)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onFinish, enabled = enabled, modifier = Modifier.weight(1f)) { Text("取消支付") }
                Button(onClick = { selectedMethod?.let(onContinuePayment) }, enabled = enabled && selectedMethod != null,
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("继续支付") }
            }
        } else if (!busy) {
            Spacer(Modifier.height(28.dp))
            // Querying an existing order remains a normal result action, not a banner retry.
            if (queryError && onCheck != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onFinish, enabled = enabled, modifier = Modifier.weight(1f)) { Text("完成") }
                    Button(onClick = onCheck, enabled = enabled, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("查询结果") }
                }
            } else Button(onClick = onFinish, enabled = enabled, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("完成") }
        }
    }
}

@Composable
private fun ResultMark(phase: RechargePhase, color: Color) {
    val darkFailure = androidx.compose.foundation.isSystemInDarkTheme() && phase == RechargePhase.Failed
    val foreground = if (darkFailure) MiuixTheme.colorScheme.onErrorContainer else color
    val background = if (darkFailure) MiuixTheme.colorScheme.errorContainer else color.copy(alpha = 0.10f)
    Canvas(Modifier.size(76.dp)) {
        drawCircle(background)
        val stroke = 4.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            foreground, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        when (phase) {
            RechargePhase.Paid -> { line(.28f, .50f, .44f, .65f); line(.44f, .65f, .73f, .35f) }
            RechargePhase.Failed -> { line(.34f, .34f, .66f, .66f); line(.66f, .34f, .34f, .66f) }
            else -> {
                drawCircle(foreground, radius = size.width * .25f, style = Stroke(stroke))
                line(.50f, .34f, .50f, .50f); line(.50f, .50f, .62f, .57f)
            }
        }
    }
}
