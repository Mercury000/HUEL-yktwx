package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
internal fun MyPage(
    state: MyUiState, payment: PaymentUiState, settings: ThemeSettings, bottomPadding: Dp,
    isActive: Boolean, onActivate: () -> Unit, onRefresh: () -> Unit,
    onProfile: () -> Unit, onAccount: () -> Unit, onBills: () -> Unit,
    onRecharge: () -> Unit, rechargeLoading: Boolean,
    onCardAction: (CampusCardAction) -> Unit, manageLoading: Boolean, passwordLoading: Boolean,
    onSettings: () -> Unit, onAbout: () -> Unit,
) {
    LaunchedEffect(isActive) { if (isActive) onActivate() }
    val balancePending = payment.busy || payment.accountLoading
    val balanceError = rememberInitialReadError(payment.account != null, payment.accountError, !balancePending)
    val hasContent = state.profile != null || state.homeIdentity != null ||
        state.summary != null || state.counts != null || payment.account != null
    val initialError = rememberInitialReadError(hasContent,
        state.profileError ?: state.homeIdentityError ?: state.summaryError ?: state.countsError ?: payment.accountError,
        state.loaded && !state.loading && !balancePending)
    val networkError = FeedbackText.mergedNetworkError(
        "资料" to FeedbackText.networkError(state.profileError, state.homeIdentityError, state.avatarError),
        "余额" to FeedbackText.networkError(payment.accountError, balanceError),
        "概览" to FeedbackText.networkError(state.summaryError, state.countsError)).takeIf { hasContent }
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                DashboardTopBar("我的", scrollBehavior = scroll)
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                DashboardPullToRefresh(loading = state.loading || payment.accountLoading, onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    scrollBehavior = scroll) {
                InitialReadTransition(initialError, networkError) { displayedError ->
                    val showFailure = displayedError != null
                    // Only an empty pending shell is retained for the outgoing skeleton.
                    val state = if (!showFailure && (initialError != null || state.loading &&
                        state.profile == null && state.homeIdentity == null && state.summary == null && state.counts == null))
                        MyUiState(loading = true) else state
                    val identityPending = !state.profileLoaded && state.profile == null && state.homeIdentity == null && state.profileError == null
                    val studyPending = identityPending || !state.homeIdentityLoaded && state.homeIdentity == null &&
                        state.profile?.professionName.isNullOrBlank() && state.homeIdentityError == null
                    val summaryPending = !state.summaryLoaded && state.summary == null && state.summaryError == null
                    val countsPending = !state.countsLoaded && state.counts == null && state.countsError == null
                    LazyColumn(Modifier.fillMaxSize().overScrollVertical(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 10.dp, bottom = bottomPadding + 24.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showFailure) {
                            item { ReadUnavailable(displayedError.orEmpty(), Modifier.fillParentMaxHeight()) }
                        } else {
                        if (networkError != null) item { PageNetworkNotice(networkError) }
                        item {
                            Surface(onClick = onProfile, color = Color.Transparent, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "个人信息" }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ProfileAvatar(state.avatar, 66.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        if (identityPending) {
                                            DashboardPlaceholder(22.sp, Modifier.fillMaxWidth(), lineHeight = 30.sp)
                                            DashboardPlaceholder(12.sp, Modifier.fillMaxWidth(), fraction = .5f, lineHeight = 18.sp, sample = "0")
                                        } else {
                                            Text(state.homeIdentity?.name?.takeIf { it.isNotBlank() } ?: state.profile?.name?.takeIf { it.isNotBlank() } ?: "校园账户",
                                                fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(state.homeIdentity?.sno ?: state.profile?.sno?.takeIf { it.isNotBlank() } ?: payment.username,
                                                fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                        val study = state.homeIdentity?.let { listOf(it.thirdLevelDepartment, it.fourthLevelDepartment).filter(String::isNotBlank).joinToString(" · ") }
                                            ?: state.profile?.professionName.orEmpty()
                                        if (studyPending) DashboardPlaceholder(11.sp, Modifier.fillMaxWidth(), lineHeight = 16.sp)
                                        else Text(study.ifBlank { "资料" }, modifier = if (study.isBlank()) Modifier.clearAndSetSemantics {} else Modifier,
                                            fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            color = if (study.isBlank()) Color.Transparent else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Icon(MiuixIcons.ChevronForward, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                        val identityError = state.profileError ?: state.homeIdentityError ?: state.avatarError
                        if (identityError != null) item { ReadNotice(identityError,
                            cached = state.profile != null || state.homeIdentity != null) }
                        item {
                            if (balanceError != null) {
                                Card(Modifier.fillMaxWidth().testTag("my-balance-card"), cornerRadius = 24.dp) {
                                    Text("校园卡余额", Modifier.padding(start = 20.dp, top = 16.dp), fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    ReadUnavailable(balanceError)
                                }
                            } else Column {
                                val primary = MiuixTheme.colorScheme.primary
                                val foreground = MiuixTheme.colorScheme.onPrimary
                                Card(Modifier.fillMaxWidth().testTag("my-balance-card"), cornerRadius = 24.dp, colors = CardDefaults.defaultColors(color = primary)) {
                                    Column(Modifier.fillMaxWidth().drawBehind {
                                        drawRect(Brush.linearGradient(listOf(primary, androidx.compose.ui.graphics.lerp(primary, Color.White, .12f))))
                                        drawCircle(foreground.copy(alpha = .045f), radius = size.width * .46f,
                                            center = Offset(size.width * .96f, size.height * .32f), style = Stroke(size.width * .08f))
                                    }.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text("校园卡余额", fontSize = 12.sp, color = foreground.copy(alpha = .8f))
                                            Spacer(Modifier.weight(1f))
                                            val restricted = !balancePending && payment.problem in listOf(PaymentCodeProblem.Lost, PaymentCodeProblem.Frozen)
                                            val statusBackground = if (restricted) Color(0xFFFFE0AE) else foreground.copy(alpha = .14f)
                                            val statusForeground = if (restricted) Color(0xFF804900) else foreground
                                            Row(Modifier.clip(CircleShape).background(statusBackground).padding(horizontal = 9.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Box(Modifier.size(4.dp).background(statusForeground, CircleShape))
                                                Text(cardStatus(payment.problem, payment.account != null, balancePending), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
                                                    fontWeight = if (restricted) FontWeight.SemiBold else FontWeight.Normal, color = statusForeground)
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            if (balancePending && payment.account == null) {
                                                DashboardPlaceholder(36.sp, Modifier.weight(1f), fraction = .58f,
                                                    lineHeight = 44.sp, sample = "¥ 0.00", color = foreground.copy(alpha = .18f))
                                            } else Text(payment.account?.let { "¥ ${money(it.balanceCents)}" } ?: "¥ —", Modifier.weight(1f),
                                                    fontSize = 36.sp, lineHeight = 44.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.SemiBold, color = foreground)
                                            CompactButton("+ 充值", onClick = onRecharge, enabled = !rechargeLoading,
                                                color = foreground, textColor = primary)
                                        }
                                        HorizontalDivider(color = MiuixTheme.colorScheme.onPrimary.copy(alpha = .16f))
                                        Surface(onClick = onAccount, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(MiuixIcons.BankCards, null, Modifier.size(15.dp), tint = foreground.copy(alpha = .8f))
                                                Text(payment.account?.name ?: "统一账户", Modifier.padding(start = 6.dp).weight(1f), fontSize = 11.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = foreground.copy(alpha = .8f))
                                                Text("账户详情", fontSize = 11.sp, color = foreground)
                                                Icon(MiuixIcons.ChevronForward, null, Modifier.size(13.dp), tint = foreground)
                                            }
                                        }
                                    }
                                }
                                ReadNotice(payment.accountError, cached = payment.account != null)
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth().testTag("my-summary-card"), cornerRadius = 24.dp) {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("本月概览", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        Text(monthTitle(state.month), fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        MyAmount("支出", state.summary?.expensesCents, Modifier.weight(1f), pending = summaryPending)
                                        Box(Modifier.width(1.dp).height(46.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)))
                                        MyAmount("收入", state.summary?.incomeCents, Modifier.weight(1f), income = true, pending = summaryPending)
                                    }
                                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        if (countsPending) DashboardPlaceholder(11.sp, Modifier.weight(1f), lineHeight = 16.sp)
                                        else Text(state.counts?.let { "本月共 ${it.income + it.expenses} 笔交易" } ?: "当月全部交易", Modifier.weight(1f),
                                            fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        DashboardLink("查看账单", onClick = onBills)
                                    }
                                    ReadNotice(state.summaryError ?: state.countsError,
                                        cached = state.summary != null || state.counts != null)
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                                Text("校园卡管理", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                DashboardPreference(title = "挂失 / 解挂", startAction = { ProfileServiceIcon(MiuixIcons.BankCards) },
                                    enabled = !manageLoading, onClick = { onCardAction(CampusCardAction.ManageState) })
                                HorizontalDivider(Modifier.padding(start = 62.dp), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                                DashboardPreference(title = "修改查询密码", startAction = { ProfileServiceIcon(MiuixIcons.Lock) },
                                    enabled = !passwordLoading, onClick = { onCardAction(CampusCardAction.ChangePassword) })
                            }
                        }
                        item { Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) { DashboardPreference(title = "应用设置", startAction = { Icon(MiuixIcons.Settings, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) }, onClick = onSettings) } }
                        item { Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) { DashboardPreference(title = "关于财小通", startAction = { Icon(MiuixIcons.Info, null, Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) }, onClick = onAbout) } }
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun MyAmount(label: String, cents: Long?, modifier: Modifier, income: Boolean = false, pending: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (pending) DashboardPlaceholder(24.sp, Modifier.fillMaxWidth(), lineHeight = 32.sp, sample = "0")
        else Text(cents?.let { "¥ ${money(it)}" } ?: "—", fontSize = 24.sp, lineHeight = 32.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold,
            color = if (income) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ProfileServiceIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(11.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = .05f)),
        contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.primary.copy(alpha = .7f)) }
}

internal fun cardStatus(problem: PaymentCodeProblem?, hasAccount: Boolean, busy: Boolean): String = when {
    busy -> "核验中"
    problem == PaymentCodeProblem.Lost -> "已挂失"
    problem == PaymentCodeProblem.Frozen -> "已冻结"
    problem == PaymentCodeProblem.StatusUnverified -> "状态待确认"
    hasAccount && problem == null -> "正常"
    else -> "状态待确认"
}

@Composable
internal fun ProfileAvatar(bytes: ByteArray?, size: Dp) {
    val bitmap = rememberSampledImage(bytes, size, size, crop = true)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "校园系统头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(MiuixIcons.Contacts, "头像", Modifier.size(size * .55f), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
internal fun PersonalProfilePage(state: MyUiState, settings: ThemeSettings, onBack: () -> Unit, onRefresh: () -> Unit) {
    val initialError = rememberInitialReadError(state.profile != null, state.profileError, !state.loading)
    val pending = state.profile == null && (initialError != null || !state.profileLoaded && state.profileError == null)
    ProfileDetailScaffold("个人信息", settings, onBack, state.loading, onRefresh, initialError) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("头像", Modifier.weight(1f), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    ProfileAvatar(state.avatar, 48.dp)
                }
                ProfileField("学工号", state.profile?.sno, pending)
                ProfileField("姓名", state.profile?.name, pending)
                ProfileField("手机号", state.profile?.mobile, pending)
                state.profile?.departmentName?.takeIf { it.isNotBlank() }?.let { ProfileField("部门", it) }
            }
        }
        val error = state.profileError ?: state.avatarError
        if (error != null) item { ReadNotice(error, cached = state.profile != null) }
    }
}

@Composable
internal fun AccountDetailPage(payment: PaymentUiState, settings: ThemeSettings, onBack: () -> Unit, onRecharge: () -> Unit,
    rechargeLoading: Boolean = false, onRefresh: (() -> Unit)? = null) {
    val balancePending = payment.busy || payment.accountLoading
    val initialError = rememberInitialReadError(payment.account != null, payment.accountError, !balancePending)
    ProfileDetailScaffold("账户详情", settings, onBack, payment.accountLoading, onRefresh, initialError) {
        item {
            Card(Modifier.fillMaxWidth()) {
                val pending = payment.account == null && (balancePending || initialError != null)
                ProfileField("账户", payment.account?.name, pending)
                ProfileField("余额", payment.account?.let { "¥ ${money(it.balanceCents)}" }, pending)
                ProfileField("校园卡状态", cardStatus(payment.problem, payment.account != null, balancePending))
            }
        }
        if (payment.accountError != null && payment.account != null) item { ReadNotice(payment.accountError, cached = true) }
        item { TextButton("充值", onClick = onRecharge, enabled = !rechargeLoading,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary()) }
    }
}

@Composable
private fun ProfileField(label: String, value: String?, pending: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (pending) DashboardPlaceholder(14.sp, Modifier.weight(1f), fraction = .55f, lineHeight = 20.sp)
        else Text(value?.takeIf { it.isNotBlank() } ?: "未提供", Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun ProfileDetailScaffold(title: String, settings: ThemeSettings, onBack: () -> Unit,
    loading: Boolean = false, onRefresh: (() -> Unit)? = null, initialError: String? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) { TopAppBar(title = title, largeTitle = title, scrollBehavior = scroll, color = Color.Transparent,
                navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") } }) }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                val list: @Composable () -> Unit = {
                    InitialReadTransition(initialError) { displayedError ->
                    LazyColumn(Modifier.fillMaxSize().overScrollVertical().then(
                        if (onRefresh == null) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                            bottom = padding.calculateBottomPadding() + 28.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (displayedError != null) item { ReadUnavailable(displayedError, Modifier.fillParentMaxHeight()) }
                        else content()
                    }
                    }
                }
                if (onRefresh != null) DashboardPullToRefresh(loading, onRefresh, Modifier.fillMaxSize(),
                    PaddingValues(top = padding.calculateTopPadding() + 16.dp), scroll, list)
                else list()
            }
        }
    }
}
