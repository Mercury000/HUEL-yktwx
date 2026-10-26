package com.mercury.yktwx.ui.pages

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onCardAction: (CampusCardAction) -> Unit, cardBusy: Boolean,
    onSettings: () -> Unit, onAbout: () -> Unit,
) {
    LaunchedEffect(isActive) { if (isActive) onActivate() }
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                DashboardTopBar("我的", scrollBehavior = scroll)
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                DashboardPullToRefresh(loading = state.loading, onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    scrollBehavior = scroll) {
                    LazyColumn(Modifier.fillMaxSize().overScrollVertical(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 10.dp, bottom = bottomPadding + 24.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Surface(onClick = onProfile, color = Color.Transparent, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "个人信息" }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ProfileAvatar(state.avatar, 66.dp)
                                    Spacer(Modifier.width(14.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(state.homeIdentity?.name?.takeIf { it.isNotBlank() } ?: state.profile?.name?.takeIf { it.isNotBlank() } ?: "校园账户", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text(state.homeIdentity?.sno ?: state.profile?.sno?.takeIf { it.isNotBlank() } ?: payment.username, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        val study = state.homeIdentity?.let { listOf(it.thirdLevelDepartment, it.fourthLevelDepartment).filter(String::isNotBlank).joinToString(" · ") }
                                            ?: state.profile?.professionName.orEmpty()
                                        study.takeIf { it.isNotBlank() }?.let { value ->
                                            Text(value, fontSize = 11.sp, lineHeight = 16.sp,
                                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        }
                                    }
                                    Icon(MiuixIcons.ChevronForward, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                        if (state.profileError != null) item { InlineError(state.profileError, actionLabel = "重试", onAction = onRefresh) }
                        if (state.homeIdentityError != null) item { InlineError(state.homeIdentityError, actionLabel = "重试", onAction = onRefresh) }
                        item {
                            val primary = MiuixTheme.colorScheme.primary
                            val foreground = MiuixTheme.colorScheme.onPrimary
                            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp, colors = CardDefaults.defaultColors(color = primary)) {
                                Column(Modifier.fillMaxWidth().drawBehind {
                                    drawRect(Brush.linearGradient(listOf(primary, androidx.compose.ui.graphics.lerp(primary, Color.White, .12f))))
                                    drawCircle(foreground.copy(alpha = .045f), radius = size.width * .46f,
                                        center = Offset(size.width * .96f, size.height * .32f), style = Stroke(size.width * .08f))
                                }.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("校园卡余额", fontSize = 12.sp, color = foreground.copy(alpha = .8f))
                                        Spacer(Modifier.weight(1f))
                                        val restricted = !payment.busy && payment.problem in listOf(PaymentCodeProblem.Lost, PaymentCodeProblem.Frozen)
                                        val statusBackground = if (restricted) Color(0xFFFFE0AE) else foreground.copy(alpha = .14f)
                                        val statusForeground = if (restricted) Color(0xFF804900) else foreground
                                        Row(Modifier.clip(CircleShape).background(statusBackground).padding(horizontal = 9.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Box(Modifier.size(4.dp).background(statusForeground, CircleShape))
                                            Text(cardStatus(payment.problem, payment.account != null, payment.busy), fontSize = 10.sp,
                                                fontWeight = if (restricted) FontWeight.SemiBold else FontWeight.Normal, color = statusForeground)
                                        }
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(payment.account?.let { "¥ ${money(it.balanceCents)}" } ?: "¥ —", Modifier.weight(1f),
                                            fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = foreground)
                                        CompactButton(if (rechargeLoading) "读取中…" else "+ 充值", onClick = onRecharge, enabled = !rechargeLoading,
                                            color = foreground, textColor = primary)
                                    }
                                    HorizontalDivider(color = MiuixTheme.colorScheme.onPrimary.copy(alpha = .16f))
                                    Surface(onClick = onAccount, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(MiuixIcons.BankCards, null, Modifier.size(15.dp), tint = foreground.copy(alpha = .8f))
                                            Text(payment.account?.name ?: "统一账户", Modifier.padding(start = 6.dp).weight(1f), fontSize = 11.sp, color = foreground.copy(alpha = .8f))
                                            Text("账户详情", fontSize = 11.sp, color = foreground)
                                            Icon(MiuixIcons.ChevronForward, null, Modifier.size(13.dp), tint = foreground)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("本月概览", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                        Text(monthTitle(state.month), fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        MyAmount("支出", state.summary?.expensesCents, Modifier.weight(1f))
                                        Box(Modifier.width(1.dp).height(46.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .06f)))
                                        MyAmount("收入", state.summary?.incomeCents, Modifier.weight(1f), income = true)
                                    }
                                    HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(state.counts?.let { "本月共 ${it.income + it.expenses} 笔交易" } ?: "当月全部交易", Modifier.weight(1f), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        DashboardLink("查看账单", onClick = onBills)
                                    }
                                    if (state.summaryError != null) InlineError(state.summaryError, actionLabel = "重试", onAction = onRefresh)
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                                Text("校园卡管理", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                DashboardPreference(title = "挂失 / 解挂", startAction = { ProfileServiceIcon(MiuixIcons.BankCards) },
                                    enabled = !cardBusy, onClick = { onCardAction(CampusCardAction.ManageState) })
                                HorizontalDivider(Modifier.padding(start = 62.dp), color = MiuixTheme.colorScheme.onSurface.copy(alpha = .06f))
                                DashboardPreference(title = "修改查询密码", startAction = { ProfileServiceIcon(MiuixIcons.Lock) },
                                    enabled = !cardBusy, onClick = { onCardAction(CampusCardAction.ChangePassword) })
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

@Composable
private fun MyAmount(label: String, cents: Long?, modifier: Modifier, income: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(cents?.let { "¥ ${money(it)}" } ?: "—", fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
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
    val bitmap = remember(bytes) { bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(MiuixTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, "校园系统头像", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(MiuixIcons.Contacts, "头像", Modifier.size(size * .55f), tint = MiuixTheme.colorScheme.primary)
    }
}

@Composable
internal fun PersonalProfilePage(state: MyUiState, settings: ThemeSettings, onBack: () -> Unit, onRefresh: () -> Unit) {
    ProfileDetailScaffold("个人信息", settings, onBack, state.loading, onRefresh) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("头像", Modifier.weight(1f), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    ProfileAvatar(state.avatar, 48.dp)
                }
                ProfileField("学工号", state.profile?.sno)
                ProfileField("姓名", state.profile?.name)
                ProfileField("手机号", state.profile?.mobile)
                state.profile?.departmentName?.takeIf { it.isNotBlank() }?.let { ProfileField("部门", it) }
            }
        }
        if (state.profileError != null) item { InlineError(state.profileError, actionLabel = "重试", onAction = onRefresh) }
    }
}

@Composable
internal fun AccountDetailPage(payment: PaymentUiState, settings: ThemeSettings, onBack: () -> Unit, onRecharge: () -> Unit,
    rechargeLoading: Boolean = false) {
    ProfileDetailScaffold("账户详情", settings, onBack) {
        item {
            Card(Modifier.fillMaxWidth()) {
                ProfileField("账户", payment.account?.name)
                ProfileField("余额", payment.account?.let { "¥ ${money(it.balanceCents)}" })
                ProfileField("校园卡状态", cardStatus(payment.problem, payment.account != null, payment.busy))
            }
        }
        item { TextButton(if (rechargeLoading) "读取中…" else "充值", onClick = onRecharge, enabled = !rechargeLoading,
            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary()) }
    }
}

@Composable
private fun ProfileField(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value?.takeIf { it.isNotBlank() } ?: "未提供", Modifier.weight(1f), fontSize = 14.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun ProfileDetailScaffold(title: String, settings: ThemeSettings, onBack: () -> Unit,
    loading: Boolean = false, onRefresh: (() -> Unit)? = null,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(false, settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) { TopAppBar(title = title, largeTitle = title, scrollBehavior = scroll, color = Color.Transparent,
                navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回") } }) }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                val list: @Composable () -> Unit = {
                    LazyColumn(Modifier.fillMaxSize().overScrollVertical().then(
                        if (onRefresh == null) Modifier.nestedScroll(scroll.nestedScrollConnection) else Modifier),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp,
                            bottom = padding.calculateBottomPadding() + 28.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
                }
                if (onRefresh != null) DashboardPullToRefresh(loading, onRefresh, Modifier.fillMaxSize(),
                    PaddingValues(top = padding.calculateTopPadding() + 16.dp), scroll, list)
                else list()
            }
        }
    }
}
