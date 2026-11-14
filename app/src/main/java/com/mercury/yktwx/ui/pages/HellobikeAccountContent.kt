package com.mercury.yktwx.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.HellobikeCardsState
import com.mercury.yktwx.HellobikeViewModel
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.BankCards

@Composable
internal fun HellobikeAccountContent(modifier: Modifier, padding: PaddingValues, account: ManagedLifeAccount,
    state: HellobikeCardsState, model: HellobikeViewModel, active: Boolean, onLogin: () -> Unit, onChange: () -> Unit,
    scroll: ScrollBehavior? = null, listState: LazyListState = rememberLazyListState()) {
    var logout by rememberSaveable { mutableStateOf(false) }
    var renewRequested by rememberSaveable { mutableStateOf(false) }
    val bikeState by model.state.collectAsState()
    val connected = bikeState.connected
    // Account removal clears the controller before publishing the logged-out account.
    // Keep the last cloud paint during that operation instead of flashing an empty list.
    var settledCards by remember { mutableStateOf(state) }
    SideEffect { if (!bikeState.accountBusy) settledCards = state }
    val display = if (bikeState.accountBusy) settledCards else state
    var wasConnected by remember { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        if (wasConnected && !connected) {
            logout = false
            renewRequested = false
            // Reset in the same remeasure as the account transition, not after the
            // shorter list has first clamped an old footer index.
            listState.requestScrollToItem(0)
            scroll?.state?.let { it.heightOffset = 0f; it.contentOffset = 0f }
        }
        wasConnected = connected
    }
    LaunchedEffect(renewRequested, display.ordinaryExpanded) {
        if (renewRequested && display.ordinaryExpanded) {
            listState.animateScrollToItem(5)
            renewRequested = false
        }
    }
    LaunchedEffect(active, account.owner) { if (!active) logout = false }
    val enabled = active && !account.busy && !bikeState.accountBusy && !display.busy && !display.paymentInFlight
    val ownedMode = when {
        !connected -> AccountCardMode.Login
        display.cards.isNotEmpty() -> AccountCardMode.Cards
        display.cardsLoading -> AccountCardMode.Loading
        display.cardsError != null -> AccountCardMode.Error
        else -> AccountCardMode.Empty
    }
    val campusMode = when {
        !connected -> AccountCardMode.Hidden
        display.auditLoading && display.audit == null -> AccountCardMode.Loading
        display.audit?.studentApproved != true -> AccountCardMode.Auth
        display.products.isNotEmpty() -> AccountCardMode.Cards
        display.productsLoading -> AccountCardMode.Loading
        display.productsError != null -> AccountCardMode.Error
        else -> AccountCardMode.Empty
    }
    DashboardPullToRefresh(loading = display.loading, onRefresh = { if (active && connected) model.cards.refresh() },
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding()), scrollBehavior = scroll) {
        LazyColumn(modifier.testTag("hellobike-account-page"), state = listState, contentPadding = PaddingValues(
            top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "hellobike-account") {
                ContentFade(account.connection to connected, Modifier.fillMaxWidth(), contentKey = { it.first.phone }, animateSize = true) { (connection, shownConnected) ->
                    val current = connection.phone == account.connection.phone
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp)
                        .then(if (current) Modifier else Modifier.clearAndSetSemantics {}), cornerRadius = 16.dp) {
                        ArrowPreference(title = connection.phone.ifBlank { "登录哈啰" },
                            summary = connection.error ?: if (!shownConnected && connection.hasAccount) "登录已失效" else null,
                            onClick = if (shownConnected) onChange else onLogin, enabled = enabled && current,
                            endActions = { Text(if (shownConnected) "更换" else "登录", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) })
                    }
                }
            }
            item(key = "hellobike-my-cards-title") { Box(Modifier.testTag("hellobike-my-cards-title")) { SmallTitle("我的卡") } }
            item(key = "hellobike-my-cards") {
                AccountCardsReadContent(display, ownedMode, Modifier.testTag("hellobike-my-cards-section")) { shown, mode, current ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (mode) {
                            AccountCardMode.Login -> AccountEmptyCard("查看我的骑行卡", "登录后查看卡片和有效期")
                            AccountCardMode.Loading -> HellobikeCardsPlaceholder("hellobike-my-cards-loading", owned = true)
                            AccountCardMode.Empty -> AccountEmptyCard("暂无骑行卡", "购买后可在这里查看有效期")
                            AccountCardMode.Cards -> shown.cards.forEach { card -> key(card.businessType, card.guid) {
                                HellobikeUserCardContent(card, enabled && current, onRenew = {
                                    if (card.businessType == 1) { renewRequested = true; model.cards.expandOrdinary(true) }
                                })
                            } }
                            else -> Unit
                        }
                        shown.cardsError?.takeIf { mode != AccountCardMode.Login }?.let { Box(Modifier.padding(horizontal = 16.dp)) { AccountNotice(it) } }
                        if (connected && shown.nextPages.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.CenterEnd) {
                            DashboardLink("查看更多", model.cards::loadMore, enabled = enabled && current && !shown.loading, arrow = false)
                        }
                    }
                }
            }
            item(key = "hellobike-campus-title") {
                Row(Modifier.fillMaxWidth().testTag("hellobike-campus-purchase-title"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SmallTitle("校园骑行卡") }
                    val status = when {
                        !connected -> ""
                        display.auditLoading && display.audit == null -> null
                        display.audit?.studentApproved == true -> "已认证"
                        display.audit?.authState == 1 -> "审核中"
                        display.audit?.authState == 3 -> "认证失败"
                        display.auditError != null -> "查询失败"
                        else -> "未认证"
                    }
                    ReadContent(status, Modifier.padding(end = 28.dp), pending = { it == null }, contentKey = { it == null }) { text ->
                        if (text == null) ContentPlaceholder(Modifier.width(48.dp).height(10.dp).testTag("hellobike-student-loading"))
                        else if (text.isNotEmpty()) Text(text, Modifier.testTag("hellobike-student-status"), fontSize = 12.sp,
                            color = if (text == "已认证") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item(key = "hellobike-campus-cards") {
                AccountCardsReadContent(display, campusMode, Modifier.testTag("hellobike-campus-section")) { shown, mode, current ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (mode) {
                            AccountCardMode.Loading -> HellobikeCardsPlaceholder("hellobike-campus-loading")
                            AccountCardMode.Empty -> AccountEmptyCard("暂无校园卡商品", "下拉刷新后查看")
                            AccountCardMode.Cards -> shown.products.forEach { product -> key(product.key) {
                                HellobikeProductCard(product, enabled && current, { model.cards.choose(product.key) })
                            } }
                            AccountCardMode.Auth -> HellobikeStudentNotice(shown, enabled && current)
                            else -> Unit
                        }
                        if (mode == AccountCardMode.Error || mode == AccountCardMode.Cards)
                            shown.productsError?.let { Box(Modifier.padding(horizontal = 16.dp)) { AccountNotice(it) } }
                    }
                }
            }
            item(key = "hellobike-other-section") {
                AccountSectionVisibility(connected) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val angle by animateFloatAsState(if (display.ordinaryExpanded) 180f else 0f,
                            tween(ContentMotion.Layout, easing = ContentMotion.Easing), label = "OtherCardsArrow")
                        Button({ model.cards.expandOrdinary() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .testTag("hellobike-other-cards").semantics { stateDescription = if (display.ordinaryExpanded) "已展开" else "已收起" }, enabled = enabled && connected) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (display.ordinaryExpanded) "收起其他骑行卡" else "展开其他骑行卡")
                                Icon(AppIcons.DateDropdown, null, Modifier.padding(start = 8.dp).size(16.dp).rotate(angle))
                            }
                        }
                        AccountSectionVisibility(display.ordinaryExpanded && connected) {
                            val ordinaryMode = when {
                                display.ordinaryProducts.isNotEmpty() -> AccountCardMode.Cards
                                display.ordinaryLoading -> AccountCardMode.Loading
                                display.ordinaryError != null -> AccountCardMode.Error
                                display.ordinaryLoaded -> AccountCardMode.Empty
                                else -> AccountCardMode.Hidden
                            }
                            AccountCardsReadContent(display, ordinaryMode, Modifier.testTag("hellobike-other-cards-content")) { shown, mode, current ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    when (mode) {
                                        AccountCardMode.Cards -> shown.ordinaryProducts.forEach { product -> key(product.key) {
                                            HellobikeProductCard(product, enabled && current && display.ordinaryExpanded && connected, { model.cards.choose(product.key) })
                                        } }
                                        AccountCardMode.Loading -> HellobikeCardsPlaceholder("hellobike-ordinary-loading")
                                        AccountCardMode.Empty -> AccountEmptyCard("暂无其他骑行卡", "下拉刷新后查看")
                                        else -> Unit
                                    }
                                    shown.ordinaryError?.let { Box(Modifier.padding(horizontal = 16.dp)) { AccountNotice(it) } }
                                    if (shown.ordinaryError != null) Box(Modifier.padding(horizontal = 28.dp)) {
                                        DashboardLink("重试", { model.cards.expandOrdinary(true) }, enabled = enabled && current && display.ordinaryExpanded && connected, arrow = false)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "hellobike-logout") {
                AccountSectionVisibility(account.connection.hasAccount) {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp).testTag("hellobike-logout"), horizontalArrangement = Arrangement.Center) {
                        DashboardLink("退出登录", { logout = true }, enabled = enabled && account.connection.hasAccount,
                            arrow = false, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
    LifeAccountLogoutDialog(account.takeIf { logout && it.connection.hasAccount }, active, { logout = false }, { model.removeAccount() })
    HellobikeCardPaymentSheet(active && state.paymentVisible, state, model)
}

private enum class AccountCardMode { Loading, Empty, Cards, Error, Login, Auth, Hidden }

/** Keep each lazy section's identity while its cloud response fades and changes height. */
@Composable
private fun AccountCardsReadContent(state: HellobikeCardsState, mode: AccountCardMode, modifier: Modifier,
    content: @Composable (HellobikeCardsState, AccountCardMode, Boolean) -> Unit) {
    ReadContent(mode to state, modifier.fillMaxWidth(), pending = { it.first == AccountCardMode.Loading }, contentKey = { it.first }) { (shownMode, shown) ->
        val current = shownMode == mode
        Box(if (current) Modifier else Modifier.clearAndSetSemantics {}) { content(shown, shownMode, current) }
    }
}

@Composable
private fun AccountSectionVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(ContentMotion.Fade)) + expandVertically(
        tween(ContentMotion.Layout, easing = ContentMotion.Easing), expandFrom = Alignment.Top),
        exit = fadeOut(tween(ContentMotion.Fade)) + shrinkVertically(
            tween(ContentMotion.Layout, easing = ContentMotion.Easing), shrinkTowards = Alignment.Top)) { content() }
}

@Composable
private fun HellobikeStudentNotice(state: HellobikeCardsState, enabled: Boolean) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("hellobike-campus-auth-notice"), cornerRadius = 16.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when { state.auditError != null -> "认证状态暂未确认"; state.audit?.authState == 1 -> "等待学生认证审核"; else -> "学生认证后可购买" }, fontSize = 14.sp)
                if (state.auditError != null) Text(state.auditError, fontSize = 12.sp, lineHeight = 18.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            OfficialHellobikeLink(enabled, color = MiuixTheme.colorScheme.primary, label = "前往哈啰")
        }
    }
}

@Composable
private fun HellobikeCardsPlaceholder(tag: String, owned: Boolean = false) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "正在加载骑行卡" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(if (owned) 1 else 2) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), cornerRadius = 16.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DashboardPlaceholder(15.sp, Modifier.fillMaxWidth(), fraction = .65f)
                            DashboardPlaceholder(12.sp, Modifier.fillMaxWidth(), fraction = .8f)
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!owned) ContentPlaceholder(Modifier.width(48.dp).height(14.dp))
                            ContentPlaceholder(Modifier.width(70.dp).height(32.dp), cornerRadius = 12.dp)
                        }
                    }
                    if (owned) {
                        DashboardPlaceholder(12.sp, Modifier.fillMaxWidth(), fraction = .6f)
                        DashboardPlaceholder(12.sp, Modifier.fillMaxWidth(), fraction = .85f)
                    }
                }
            }
        }
    }
}
@Composable
private fun AccountNotice(text: String) { Text(text, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }

@Composable
private fun AccountEmptyCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("hellobike-cards-empty"), cornerRadius = 16.dp) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(MiuixIcons.BankCards, null, Modifier.size(28.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(detail, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun HellobikeProductCard(product: HellobikeCampusProduct, enabled: Boolean, onBuy: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("hellobike-campus-product-${product.key}"), cornerRadius = 16.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(product.name, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    if (product.description.isNotBlank()) Text(product.description, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.testTag("hellobike-product-rules-${product.key}"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    product.unavailableReason?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("¥${product.price}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary)
                    CompactButton(if (product.signingRequired) "开通" else "购买", onBuy,
                        Modifier.widthIn(min = 70.dp).testTag("hellobike-buy-${product.key}"), enabled = enabled && product.purchasable)
                }
            }
        }
    }
}

internal fun hellobikeCardExpiry(seconds: Long?): String? = seconds?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.let {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it * 1000))
}

@Composable
private fun HellobikeUserCardContent(card: HellobikeUserCard, enabled: Boolean, onRenew: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("hellobike-user-card-${card.guid}"), cornerRadius = 16.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(card.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(card.statusLabel, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
                        card.remainDay?.let { Text("剩余 $it 天", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
                if (card.renewable) {
                    if (card.businessType == 1) CompactButton("续费", onRenew,
                        Modifier.widthIn(min = 70.dp).testTag("hellobike-renew-${card.guid}"), enabled = enabled)
                    else OfficialHellobikeLink(enabled, label = "续费")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    hellobikeCardExpiry(card.expireSecond)?.let { Text("到期时间 $it", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
            }
            if (card.description.isNotBlank()) Text(card.description, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.testTag("hellobike-user-rules-${card.guid}"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

private enum class HellobikeCardPaymentPage { Methods, Result }

@Composable
private fun HellobikeCardPaymentSheet(show: Boolean, state: HellobikeCardsState, model: HellobikeViewModel) {
    val controller = model.cards
    val isResult = state.feedback != null && state.feedback.phase != com.mercury.yktwx.RechargePhase.Creating
    val page = if (isResult) HellobikeCardPaymentPage.Result else HellobikeCardPaymentPage.Methods
    PaymentSheetPages(show, page, { it == HellobikeCardPaymentPage.Result }, if (isResult) null else "付款",
        controller::dismissPayment, Modifier.testTag("hellobike-card-payment-sheet")) { shownPage, interactive ->
        val display = rememberWhileShown(interactive, state.copy(paymentLaunch = null))
        val enabled = interactive && !state.busy && !state.paymentInFlight
        val purchase = display.purchase
        val feedback = display.feedback
        if (shownPage == HellobikeCardPaymentPage.Result) {
            val amount = purchase?.attempt?.orders?.sumOf { it.amount.toBigDecimal() }?.toPlainString() ?: purchase?.amount ?: "0.00"
            val cancelled = purchase?.paymentCancelled == true && feedback?.phase == com.mercury.yktwx.RechargePhase.Failed
            PaymentResultContent(feedback?.phase ?: com.mercury.yktwx.RechargePhase.Checking,
                display.busy || feedback?.loading == true, amount.toBigDecimal().movePointRight(2).longValueExact(),
                feedback?.detail, if (cancelled) controller::cancelPayment else controller::dismissPayment,
                subject = "支付", enabled = enabled, onCheck = controller::checkPayment,
                paymentApp = when (purchase?.attempt?.channel) { "003" -> "哈啰"; "1201" -> "银联"; else -> "支付宝" },
                preserveError = true, cancelled = cancelled,
                onResumePayment = if (cancelled) controller::continueCancelledPayment else null)
        } else {
            val selected = display.options.indexOfFirst { it.channel.method == display.method }
            val option = display.options.getOrNull(selected)
            PaymentFixedAmount(option?.amount ?: purchase?.amount, display.busy && purchase == null,
                Modifier.testTag("hellobike-card-payment-amount"), detail = purchase?.name)
            if (purchase?.signingFields?.isNotEmpty() == true) AccountNotice("连续包月，需在支付宝确认自动续费签约")
            val error = display.paymentError ?: option?.channel?.remark ?: "暂无可用的付款方式，请刷新重试".takeIf {
                !display.busy && display.options.isEmpty()
            }
            PaymentMethodActions(display.options.map { it.channel.name }, selected,
                { controller.selectMethod(display.options[it].channel.method) },
                if (display.busy) "正在发起…" else if (purchase?.signingFields?.isNotEmpty() == true) "签约并支付"
                    else option?.channel?.name?.let { "${it}支付" } ?: "确认支付",
                controller::pay, enabled, option?.channel?.available == true && purchase?.attempt == null && purchase != null,
                error = error, modifier = Modifier.testTag("hellobike-card-pay-submit"), preserveError = true,
                onCancel = controller::cancelPayment, cancelModifier = Modifier.testTag("hellobike-card-pay-cancel"))
            if (purchase != null && !display.busy && display.options.isEmpty()) Button(controller::showPurchase,
                Modifier.fillMaxWidth(), enabled = enabled) { Text("刷新付款方式") }
        }
    }
}
