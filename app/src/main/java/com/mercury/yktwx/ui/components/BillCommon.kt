package com.mercury.yktwx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.BillRecord
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BillDayGroup(val date: LocalDate?, val records: List<BillRecord>) {
    val key: String get() = date?.toString() ?: "unknown:${records.firstOrNull()?.orderId.orEmpty()}"
}

internal val AppRefreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成")

internal fun money(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)

internal fun signedMoney(record: BillRecord): String =
    (if (record.income) "+" else "-") + "¥ " + money(record.amountCents)

internal fun monthTitle(month: YearMonth): String = "${month.year}年${month.monthValue}月"

internal fun monthShort(month: YearMonth): String = "${month.monthValue}月"

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun parseBillTime(value: String): LocalDateTime? {
    val text = value.trim().replace('T', ' ')
    if (text.length < 16) return null
    val normalized = when {
        text.length == 16 -> "$text:00"
        text.length >= 19 -> text.take(19)
        else -> return null
    }
    return runCatching { LocalDateTime.parse(normalized, timeFormat) }.getOrNull()
}

internal fun BillRecord.dateTime(): LocalDateTime? =
    parseBillTime(effectTime) ?: parseBillTime(bookTime)

private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun billTimeLabel(record: BillRecord): String {
    val value = record.dateTime() ?: return record.effectTime.take(16)
    val time = String.format(Locale.CHINA, "%02d:%02d", value.hour, value.minute)
    val date = value.toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 $time"
        today.minusDays(1) -> "昨天 $time"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $time"
    }
}

internal fun billDayLabel(date: LocalDate?): String {
    if (date == null) return "其他"
    val today = LocalDate.now()
    val base = "${date.monthValue}月${date.dayOfMonth}日 ${weekdays[date.dayOfWeek.value - 1]}"
    return when (date) {
        today -> "今天 · $base"
        today.minusDays(1) -> "昨天 · $base"
        else -> base
    }
}

internal fun List<BillRecord>.groupByDay(): List<BillDayGroup> {
    val groups = LinkedHashMap<LocalDate?, MutableList<BillRecord>>()
    forEach { record ->
        groups.getOrPut(record.dateTime()?.toLocalDate()) { mutableListOf() }.add(record)
    }
    return groups.map { BillDayGroup(it.key, it.value) }
}

private fun billIcon(icon: String): ImageVector = when (icon) {
    "recharge" -> MiuixIcons.AddCircle
    "refund" -> MiuixIcons.Refresh
    "qrCode-payment" -> MiuixIcons.Scan
    "subsidy" -> MiuixIcons.Promotions
    "consume" -> MiuixIcons.Store
    else -> MiuixIcons.BankCards
}

@Composable
internal fun BillIcon(icon: String, modifier: Modifier = Modifier, size: Dp = 40.dp, income: Boolean = false) {
    val background = if (income) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHigh
    val tint = if (income) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface
    Box(
        modifier = modifier.size(size).background(background, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(billIcon(icon), contentDescription = null, modifier = Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
internal fun BillFilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
internal fun BillMonthSelector(
    month: YearMonth,
    onMonthSelected: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var showSheet by remember { mutableStateOf(false) }
    val canNext = month < YearMonth.now()
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onMonthSelected(month.minusMonths(1)) }) {
                Icon(
                    MiuixIcons.ChevronBackward, contentDescription = "上个月",
                    modifier = Modifier.size(20.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Surface(onClick = { showSheet = true }, modifier = Modifier.weight(1f), color = Color.Transparent) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(monthTitle(month), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        MiuixIcons.ExpandMore, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            IconButton(onClick = { if (canNext) onMonthSelected(month.plusMonths(1)) }) {
                Icon(
                    MiuixIcons.ChevronForward, contentDescription = "下个月",
                    modifier = Modifier.size(20.dp),
                    tint = if (canNext) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
                )
            }
            actions()
        }
    }
    OverlayBottomSheet(
        show = showSheet,
        title = "选择月份",
        onDismissRequest = { showSheet = false },
        renderInRootScaffold = true,
    ) {
        val months = remember(month) {
            val current = YearMonth.now()
            val list = (0 until 24).map { current.minusMonths(it.toLong()) }.toMutableList()
            if (month !in list) list += month
            list.sortedDescending()
        }
        Card(Modifier.fillMaxWidth().bottomSheetSafeArea()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(months, key = { it.toString() }) { item ->
                    RadioButtonPreference(
                        title = monthTitle(item),
                        summary = if (item == YearMonth.now()) "本月" else null,
                        selected = item == month,
                        onClick = {
                            showSheet = false
                            onMonthSelected(item)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun BillErrorCard(message: String, actionLabel: String = "重试", onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账单加载失败", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Button(onClick = onAction, colors = ButtonDefaults.buttonColorsPrimary()) { Text(actionLabel) }
        }
    }
}

@Composable
internal fun BillEmptyCard(title: String, message: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(MiuixIcons.ListView, contentDescription = null, modifier = Modifier.size(36.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun BillLoadingCard(message: String = "正在加载账单") {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(Modifier.size(26.dp))
            Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
internal fun BillPreviewNote() {
    Text(
        "账单预览 · 以下交易为演示数据",
        modifier = Modifier.padding(horizontal = 4.dp),
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.primary,
    )
}

@Composable
internal fun BillStatusDot(color: Color) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
}
