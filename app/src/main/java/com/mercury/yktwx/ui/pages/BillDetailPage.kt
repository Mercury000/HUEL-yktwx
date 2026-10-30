package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun BillDetailPage(record: BillRecord?, settings: ThemeSettings, onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(
                    title = "账单详情",
                    largeTitle = "账单详情",
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent,
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 16.dp,
                        bottom = padding.calculateBottomPadding() + 28.dp,
                        start = 16.dp,
                        end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (record == null) {
                        item { BillEmptyCard("未找到账单", "请返回账单列表后重试。") }
                    } else {
                        item { DetailHeader(record) }
                        item { DetailInfo(record) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(record: BillRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BillIcon(record.icon, size = 56.dp, income = record.income)
            Spacer(Modifier.height(4.dp))
            UpdatedText(
                record.payName.ifBlank { "校园卡交易" },
                numeric = false, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            )
            UpdatedText(
                record.turnoverType,
                numeric = false, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            UpdatedText(
                money(record.amountCents), prefix = if (record.income) "+¥ " else "-¥ ",
                modifier = Modifier.padding(top = 10.dp),
                fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                color = if (record.income) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            Text("交易成功", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DetailInfo(record: BillRecord) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "交易信息",
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            DetailRow("交易类型", record.turnoverType)
            DetailRow("支付方式", record.payName)
            DetailRow("商户", record.merchant.ifBlank { record.payName })
            DetailRow("交易时间", record.effectTime)
            DetailRow("记账时间", record.bookTime)
            DetailRow("支付账户", record.fromAccount)
            DetailRow("学号", record.serialNumber)
            DetailRow("终端号", record.position)
            if (record.balanceCents > 0) DetailRow("账户余额", "¥ ${money(record.balanceCents)}")
            if (record.feeCents > 0) DetailRow("手续费", "¥ ${money(record.feeCents)}")
            if (record.labelName.isNotBlank()) DetailRow("标签", record.labelName)
            if (record.orderId.isNotBlank()) DetailStacked("订单号", record.orderId, monospace = true)
            if (record.remark.isNotBlank()) DetailStacked("备注", record.remark)
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label, modifier = Modifier.width(88.dp),
                fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            UpdatedText(
                value, modifier = Modifier.weight(1f),
                numeric = false, fontSize = 14.sp, textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun DetailStacked(label: String, value: String, monospace: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        SelectionContainer {
            UpdatedText(
                value,
                numeric = false,
                fontSize = if (monospace) 13.sp else 14.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
}
