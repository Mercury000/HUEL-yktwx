package com.mercury.yktwx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.BillDateMode
import com.mercury.yktwx.data.BillRecord
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class BillDayGroup(val date: LocalDate?, val records: List<BillRecord>) {
    val key: String get() = date?.toString() ?: "unknown:${records.firstOrNull()?.stableId.orEmpty()}"
}

internal val AppRefreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新", "刷新完成")

internal val BillListMinimumDate = LocalDate.of(2010, 2, 1)

internal fun money(cents: Long): String = String.format(Locale.CHINA, "%.2f", cents / 100.0)

internal fun signedMoney(record: BillRecord): String =
    (if (record.income) "+" else "-") + "¥ " + money(record.amountCents)

internal fun monthTitle(month: YearMonth): String = "${month.year}年${month.monthValue}月"

internal fun billDateLabel(filter: BillDateFilter): String = when (filter.mode) {
    BillDateMode.Day -> fullBillDate(filter.start)
    BillDateMode.Month -> monthTitle(YearMonth.from(filter.start))
    BillDateMode.Year -> "${filter.start.year}年"
    BillDateMode.Range -> billRangeLabel(filter.start, filter.end)
}

internal fun billDateScopeLabel(filter: BillDateFilter, today: LocalDate = LocalDate.now()): String =
    when (filter.mode) {
        BillDateMode.Day -> "${filter.start.monthValue}月${filter.start.dayOfMonth}日"
        BillDateMode.Month -> {
            val month = YearMonth.from(filter.start)
            if (month == YearMonth.from(today)) "本月" else "${month.monthValue}月"
        }
        BillDateMode.Year -> "${filter.start.year}年"
        BillDateMode.Range -> billRangeScopeLabel(filter.start, filter.end)
    }

private fun fullBillDate(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

private fun billRangeLabel(start: LocalDate, end: LocalDate): String = when {
    start == end -> fullBillDate(start)
    start.year == end.year && start.month == end.month ->
        "${fullBillDate(start)} - ${end.dayOfMonth}日"
    start.year == end.year ->
        "${fullBillDate(start)} - ${end.monthValue}月${end.dayOfMonth}日"
    else -> "${fullBillDate(start)} - ${fullBillDate(end)}"
}

private fun billRangeScopeLabel(start: LocalDate, end: LocalDate): String = when {
    start.year == end.year && start.month == end.month ->
        "${start.monthValue}月${start.dayOfMonth}日 - ${end.dayOfMonth}日"
    start.year == end.year ->
        "${start.monthValue}月${start.dayOfMonth}日 - ${end.monthValue}月${end.dayOfMonth}日"
    else -> "${start.year}年${start.monthValue}月${start.dayOfMonth}日 - " +
        "${end.year}年${end.monthValue}月${end.dayOfMonth}日"
}

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
internal fun BillFilterPill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
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
internal fun BillDateSelector(
    filter: BillDateFilter,
    minimumDate: LocalDate,
    onDateSelected: (BillDateFilter) -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    var showSheet by remember(isActive) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(onClick = { showSheet = true }, enabled = isActive, color = Color.Transparent) {
                Row(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        billDateLabel(filter),
                        modifier = Modifier.weight(1f, fill = false),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        AppIcons.DateDropdown, contentDescription = "选择日期",
                        modifier = Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        actions()
    }
    BillDatePickerDialog(
        show = showSheet,
        filter = filter,
        minimumDate = minimumDate,
        onDismiss = { showSheet = false },
        onConfirm = {
            if (isActive && showSheet) {
                showSheet = false
                onDateSelected(it)
            }
        },
    )
}

@Composable
private fun BillDatePickerDialog(
    show: Boolean,
    filter: BillDateFilter,
    minimumDate: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (BillDateFilter) -> Unit,
) {
    val today = LocalDate.now()
    val session = rememberOverlaySession(show)
    var mode by remember(session) { mutableStateOf(filter.mode) }
    var rangeStart by remember(session) { mutableStateOf(filter.start.coerceIn(minimumDate, today)) }
    var rangeEnd by remember(session) { mutableStateOf(filter.end.coerceIn(rangeStart, today)) }
    var activeStart by remember(session) { mutableStateOf(true) }
    val modes = remember { listOf(BillDateMode.Day, BillDateMode.Month, BillDateMode.Year, BillDateMode.Range) }

    fun dateForMode(nextMode: BillDateMode, start: LocalDate, end: LocalDate): LocalDate = when (nextMode) {
        BillDateMode.Day -> minOf(today, end).coerceIn(start, today)
        BillDateMode.Month -> maxOf(YearMonth.from(start).atDay(1), minimumDate)
        BillDateMode.Year -> maxOf(LocalDate.of(start.year, 1, 1), minimumDate)
        BillDateMode.Range -> (if (activeStart) start else end).coerceIn(minimumDate, today)
    }

    var pickerDate by remember(session) { mutableStateOf(dateForMode(mode, rangeStart, rangeEnd)) }

    fun selectedFilter(): BillDateFilter = when (mode) {
        BillDateMode.Day -> BillDateFilter.day(pickerDate)
        BillDateMode.Month -> BillDateFilter.month(YearMonth.from(pickerDate))
        BillDateMode.Year -> BillDateFilter.year(pickerDate.year)
        BillDateMode.Range -> BillDateFilter.range(rangeStart, rangeEnd)
    }

    fun updateRangeDate(next: LocalDate) {
        pickerDate = next
        if (activeStart) {
            rangeStart = next
            if (rangeEnd < next) rangeEnd = next
        } else {
            rangeEnd = next
            if (rangeStart > next) rangeStart = next
        }
    }

    fun updatePickerDate(next: LocalDate) {
        if (!show) return
        if (next.isBefore(minimumDate) || next.isAfter(today)) return
        if (mode == BillDateMode.Range) updateRangeDate(next) else pickerDate = next
    }

    fun selectMode(nextMode: BillDateMode) {
        mode = nextMode
        activeStart = true
        if (mode == BillDateMode.Range) {
            if (rangeStart < minimumDate) rangeStart = minimumDate
            if (rangeEnd > today) rangeEnd = today
            if (rangeStart > rangeEnd) rangeStart = rangeEnd
        }
        pickerDate = dateForMode(mode, rangeStart, rangeEnd)
    }

    OverlayDialog(
        show = show,
        title = "选择日期",
        onDismissRequest = onDismiss,
    ) { key(session) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OverlayDropdownPreference(
                title = "日期范围",
                items = listOf("按日", "按月", "按年", "自定义时间段"),
                selectedIndex = modes.indexOf(mode),
                enabled = show,
                onSelectedIndexChange = { index -> if (show) selectMode(modes[index]) },
                insideMargin = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
            )
            if (mode == BillDateMode.Range) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BillRangeDateField(
                        label = "开始",
                        date = rangeStart,
                        selected = activeStart,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (show) {
                                activeStart = true
                                pickerDate = rangeStart
                            }
                        },
                    )
                    Text("至", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    BillRangeDateField(
                        label = "结束",
                        date = rangeEnd,
                        selected = !activeStart,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (show) {
                                activeStart = false
                                pickerDate = minOf(rangeEnd, today)
                            }
                        },
                    )
                }
            }
            when (mode) {
                BillDateMode.Day -> BillDayPickers(
                    date = pickerDate,
                    today = today,
                    minimumDate = minimumDate,
                    onDateChange = ::updatePickerDate,
                )
                BillDateMode.Month -> BillMonthPickers(
                    month = YearMonth.from(pickerDate),
                    today = today,
                    minimumDate = minimumDate,
                    onMonthChange = { month -> updatePickerDate(month.atDay(1)) },
                )
                BillDateMode.Year -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BillWheelPicker(
                        value = pickerDate.year,
                        onValueChange = { year ->
                            if (year in minimumDate.year..today.year) {
                                updatePickerDate(maxOf(LocalDate.of(year, 1, 1), minimumDate))
                            }
                        },
                        modifier = Modifier.width(180.dp),
                        selectableRange = minimumDate.year..today.year,
                        displayRange = minimumDate.year..(today.year + DISPLAY_YEAR_BUFFER),
                        label = { it.toString() },
                    )
                }
                BillDateMode.Range -> BillDayPickers(
                    date = pickerDate,
                    today = today,
                    minimumDate = minimumDate,
                    onDateChange = ::updatePickerDate,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "取消",
                    enabled = show,
                    minHeight = 50.dp,
                    onClick = onDismiss,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = "确定",
                    enabled = show,
                    minHeight = 50.dp,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { if (show) onConfirm(selectedFilter()) },
                )
            }
        }
    } }
}

@Composable
private fun BillDayPickers(
    date: LocalDate,
    today: LocalDate,
    minimumDate: LocalDate,
    onDateChange: (LocalDate) -> Unit,
) {
    val monthRange = 1..12
    val dayRange = 1..YearMonth.of(date.year, date.monthValue).lengthOfMonth()
    val minMonth = if (date.year == minimumDate.year) minimumDate.monthValue else 1
    val maxMonth = if (date.year == today.year) today.monthValue else 12
    val minDay = if (date.year == minimumDate.year && date.monthValue == minimumDate.monthValue)
        minimumDate.dayOfMonth else 1
    val maxDay = if (date.year == today.year && date.monthValue == today.monthValue)
        today.dayOfMonth else YearMonth.of(date.year, date.monthValue).lengthOfMonth()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BillWheelPicker(
            value = date.year,
            onValueChange = { year ->
                if (year in minimumDate.year..today.year) {
                    onDateChange(boundedBillDate(year, date.monthValue, date.dayOfMonth, minimumDate, today))
                }
            },
            modifier = Modifier.weight(1f),
            selectableRange = minimumDate.year..today.year,
            displayRange = minimumDate.year..(today.year + DISPLAY_YEAR_BUFFER),
            label = { it.toString() },
        )
        BillWheelPicker(
            value = date.monthValue,
            onValueChange = { month ->
                if (month in minMonth..maxMonth) {
                    onDateChange(boundedBillDate(date.year, month, date.dayOfMonth, minimumDate, today))
                }
            },
            modifier = Modifier.weight(1f),
            selectableRange = minMonth..maxMonth,
            displayRange = monthRange,
            label = { "%02d".format(it) },
        )
        BillWheelPicker(
            value = date.dayOfMonth,
            onValueChange = { day ->
                if (day in minDay..maxDay) {
                    onDateChange(boundedBillDate(date.year, date.monthValue, day, minimumDate, today))
                }
            },
            modifier = Modifier.weight(1f),
            selectableRange = minDay..maxDay,
            displayRange = dayRange,
            label = { "%02d".format(it) },
        )
    }
}

@Composable
private fun BillMonthPickers(
    month: YearMonth,
    today: LocalDate,
    minimumDate: LocalDate,
    onMonthChange: (YearMonth) -> Unit,
) {
    val minMonth = if (month.year == minimumDate.year) minimumDate.monthValue else 1
    val maxMonth = if (month.year == today.year) today.monthValue else 12
    val selectableMonths = minMonth..maxMonth
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BillWheelPicker(
            value = month.year,
            onValueChange = { year ->
                if (year in minimumDate.year..today.year) {
                    val minMonth = if (year == minimumDate.year) minimumDate.monthValue else 1
                    val maxMonth = if (year == today.year) today.monthValue else 12
                    onMonthChange(YearMonth.of(year, month.monthValue.coerceIn(minMonth, maxMonth)))
                }
            },
            modifier = Modifier.weight(1f),
            selectableRange = minimumDate.year..today.year,
            displayRange = minimumDate.year..(today.year + DISPLAY_YEAR_BUFFER),
            label = { it.toString() },
        )
        BillWheelPicker(
            value = month.monthValue,
            onValueChange = { value ->
                val minMonth = if (month.year == minimumDate.year) minimumDate.monthValue else 1
                val maxMonth = if (month.year == today.year) today.monthValue else 12
                if (value in minMonth..maxMonth) {
                    onMonthChange(YearMonth.of(month.year, value))
                }
            },
            modifier = Modifier.weight(1f),
            selectableRange = selectableMonths,
            displayRange = 1..12,
            label = { "%02d".format(it) },
        )
    }
}

@Composable
private fun BillRangeDateField(
    label: String,
    date: LocalDate,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = Color.Transparent,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(
                label,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${date.year}.${date.monthValue}.${date.dayOfMonth}",
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(2.dp).background(
                    if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.dividerLine,
                ),
            )
        }
    }
}

private fun safeBillDate(year: Int, month: Int, day: Int): LocalDate {
    val safeMonth = month.coerceIn(1, 12)
    return LocalDate.of(year, safeMonth, day.coerceIn(1, YearMonth.of(year, safeMonth).lengthOfMonth()))
}

private fun boundedBillDate(
    year: Int,
    month: Int,
    day: Int,
    minimum: LocalDate,
    maximum: LocalDate,
): LocalDate = safeBillDate(year, month, day).coerceIn(minimum, maximum)

private const val DISPLAY_YEAR_BUFFER = 5

@Composable
internal fun BillErrorCard(
    message: String,
) {
    Card(Modifier.fillMaxWidth()) { ReadUnavailable(message) }
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
    Card(Modifier.fillMaxWidth().then(delayedSkeletonModifier()).semantics { contentDescription = message }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            repeat(4) { index ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ContentPlaceholder(Modifier.size(40.dp), cornerRadius = 12.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardPlaceholder(14.sp, Modifier.fillMaxWidth(), fraction = .62f, lineHeight = 20.sp)
                        DashboardPlaceholder(11.sp, Modifier.fillMaxWidth(), fraction = .42f, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    DashboardPlaceholder(14.sp, Modifier.width(72.dp), fraction = 1f, lineHeight = 20.sp, sample = "¥ 00.00")
                }
                if (index != 3) HorizontalDivider(Modifier.padding(start = 52.dp))
            }
        }
    }
}
