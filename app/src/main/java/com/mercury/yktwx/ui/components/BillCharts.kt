package com.mercury.yktwx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import top.yukonga.miuix.kmp.basic.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.BillCategory
import com.mercury.yktwx.data.BillDateMode
import com.mercury.yktwx.data.BillDailyPoint
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun DailyBillChart(
    points: List<BillDailyPoint>,
    mode: BillDateMode,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val grid = MiuixTheme.colorScheme.dividerLine
    val surface = MiuixTheme.colorScheme.surface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val displayed = rememberChartValues(points.associate { it.date to it.amountCents.toDouble() }).toSortedMap()
    val chartPoints = displayed.entries.toList()
    val chartMax = displayed.values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val max = points.maxOfOrNull { it.amountCents } ?: 0L
    var selectedDay by rememberSaveable(mode) { mutableStateOf<Long?>(null) }
    val selectedDate = selectedDay?.let { runCatching { java.time.LocalDate.ofEpochDay(it) }.getOrNull() }
    val selected = points.firstOrNull { it.date == selectedDate }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == BillDateMode.Year) "每月趋势" else "每日趋势", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            UpdatedText(if (max > 0) "最高 ¥ ${money(max)}" else "暂无数据", fontSize = 12.sp, color = summary)
        }
        Spacer(Modifier.height(12.dp))
        if (points.isEmpty() || max <= 0L) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("暂无数据", fontSize = 13.sp, color = summary)
            }
            return@Column
        }
        Box(Modifier.fillMaxWidth().height(150.dp)
            .dismissOnOutsidePress(selected != null) { selectedDay = null }) {
            Canvas(Modifier.fillMaxSize().testTag("bill-trend-plot")
                .semantics {
                    contentDescription = selected?.let {
                        val date = if (mode == BillDateMode.Year) "${it.date.year}年${it.date.monthValue}月" else it.date.toString()
                        "$date，金额 ${money(it.amountCents)} 元"
                    } ?: "收支趋势"
                    customActions = listOf(CustomAccessibilityAction("查看下一个时间点") {
                        val index = points.indexOfFirst { it.date == selectedDate }
                        selectedDay = points[(index + 1).coerceAtMost(points.lastIndex)].date.toEpochDay()
                        true
                    }, CustomAccessibilityAction("查看上一个时间点") {
                        val index = points.indexOfFirst { it.date == selectedDate }
                        selectedDay = points[(index - 1).coerceAtLeast(0)].date.toEpochDay()
                        true
                    }) + if (selected != null) listOf(CustomAccessibilityAction("收起金额详情") {
                        selectedDay = null
                        true
                    }) else emptyList()
                }.pointerInput(points) {
                    detectTapGestures { position ->
                        val index = if (points.size <= 1) 0 else
                            (position.x / size.width.coerceAtLeast(1) * points.lastIndex).roundToInt().coerceIn(points.indices)
                        val date = points[index].date
                        selectedDay = date.takeUnless { it == selectedDate }?.toEpochDay()
                    }
                }) {
                // Empty -> nonempty may compose before the interpolation effect acquires its target.
                if (chartPoints.isEmpty()) return@Canvas
                val chartTop = 8.dp.toPx()
                val chartHeight = size.height - chartTop
                val step = if (chartPoints.size <= 1) 0f else size.width / (chartPoints.size - 1)
                fun point(index: Int): Offset {
                    val x = if (chartPoints.size == 1) size.width / 2 else index * step
                    val ratio = (chartPoints[index].value / chartMax).toFloat()
                    return Offset(x, chartTop + chartHeight * (1f - ratio))
                }
                for (i in 0..4) {
                    val y = chartTop + chartHeight * i / 4f
                    drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                if (chartPoints.size == 1) {
                    val p = point(0)
                    drawCircle(primary, radius = 3.5.dp.toPx(), center = p)
                } else {
                    val line = Path().apply {
                        moveTo(point(0).x, point(0).y)
                        for (index in 1 until chartPoints.size) lineTo(point(index).x, point(index).y)
                    }
                    val fill = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(point(0).x, point(0).y)
                        for (index in 1 until chartPoints.size) lineTo(point(index).x, point(index).y)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(
                        fill,
                        Brush.verticalGradient(
                            colors = listOf(primary.copy(alpha = 0.26f), primary.copy(alpha = 0f)),
                            startY = chartTop, endY = size.height,
                        ),
                    )
                    drawPath(line, primary, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                val selectedIndex = chartPoints.indexOfFirst { it.key == selectedDate }
                if (selectedIndex >= 0) {
                    val selectedPoint = point(selectedIndex)
                    drawLine(primary.copy(alpha = .35f), Offset(selectedPoint.x, chartTop), Offset(selectedPoint.x, size.height), strokeWidth = 1.dp.toPx())
                    drawCircle(primary, radius = 5.dp.toPx(), center = selectedPoint)
                    drawCircle(surface, radius = 2.5.dp.toPx(), center = selectedPoint)
                }
                val maxIndex = chartPoints.indexOfFirst { it.value == chartMax }
                if (maxIndex >= 0) {
                    val p = point(maxIndex)
                    drawCircle(primary, radius = 4.5.dp.toPx(), center = p)
                    drawCircle(surface, radius = 2.dp.toPx(), center = p)
                }
            }
            val selectedIndex = chartPoints.indexOfFirst { it.key == selectedDate }
            if (selected != null && selectedIndex >= 0) {
                // Measure the label inside the plot, then keep it near the point without clipping.
                Layout(modifier = Modifier.matchParentSize(), content = {
                    val date = if (mode == BillDateMode.Year) "${selected.date.monthValue}月"
                        else "${selected.date.monthValue}月${selected.date.dayOfMonth}日"
                    Text("$date · ¥ ${money(selected.amountCents)}",
                        Modifier.testTag("bill-trend-selection")
                            .semantics { onClick(label = "收起金额详情") { selectedDay = null; true } }
                            .pointerInput(Unit) { detectTapGestures { selectedDay = null } }
                            .background(surface.copy(alpha = .96f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        fontSize = 12.sp, color = primary, fontWeight = FontWeight.Medium,
                        maxLines = 1)
                }) { measurables, constraints ->
                    val label = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    val pointX = if (chartPoints.size == 1) width / 2f
                        else width.toFloat() * selectedIndex / chartPoints.lastIndex
                    val top = 8.dp.toPx()
                    val pointY = top + (height - top) * (1f - (chartPoints[selectedIndex].value / chartMax).toFloat())
                    val gap = 10.dp.roundToPx()
                    val x = (pointX - label.width / 2f).roundToInt().coerceIn(0, (width - label.width).coerceAtLeast(0))
                    val above = pointY.roundToInt() - label.height - gap
                    val y = (if (above >= 0) above else pointY.roundToInt() + gap)
                        .coerceIn(0, (height - label.height).coerceAtLeast(0))
                    layout(width, height) { label.place(x, y) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(pointShort(points.first(), mode), fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text(pointShort(points[points.size / 2], mode), fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text(pointShort(points.last(), mode), fontSize = 11.sp, color = summary)
        }
    }
}

private fun pointShort(point: BillDailyPoint, mode: BillDateMode): String =
    if (mode == BillDateMode.Year) "${point.date.monthValue}月"
    else "${point.date.monthValue}.${point.date.dayOfMonth}"

@Composable
internal fun DailyBillChartPlaceholder(modifier: Modifier = Modifier) {
    val placeholder = MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)
    val grid = MiuixTheme.colorScheme.dividerLine
    Column(modifier.fillMaxWidth().semantics { contentDescription = "正在加载趋势图" }) {
        BillChartHeaderPlaceholder()
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(150.dp).then(delayedSkeletonModifier()).clearAndSetSemantics {}) {
            val chartTop = 8.dp.toPx()
            val chartHeight = size.height - chartTop
            for (i in 0..4) {
                val y = chartTop + chartHeight * i / 4f
                drawLine(grid.copy(alpha = .45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val heights = listOf(.64f, .42f, .5f, .26f, .44f, .18f, .32f)
            val line = Path().apply {
                heights.forEachIndexed { index, height ->
                    val x = size.width * index / heights.lastIndex
                    val y = chartTop + chartHeight * height
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(line, placeholder, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(3) { DashboardPlaceholder(11.sp, Modifier.width(36.dp), fraction = 1f, sample = "0.00") }
        }
    }
}

@Composable
internal fun CategoryDonutChartPlaceholder(modifier: Modifier = Modifier) {
    val placeholder = MiuixTheme.colorScheme.onSurface.copy(alpha = .08f)
    Column(modifier.fillMaxWidth().semantics { contentDescription = "正在加载分类占比图" }) {
        BillChartHeaderPlaceholder()
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().then(delayedSkeletonModifier()).clearAndSetSemantics {}) {
                    val strokeWidth = 22.dp.toPx()
                    drawCircle(placeholder, radius = (size.minDimension - strokeWidth) / 2,
                        center = center, style = Stroke(width = strokeWidth))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DashboardPlaceholder(12.sp, Modifier.width(56.dp), fraction = 1f)
                    DashboardPlaceholder(20.sp, Modifier.width(88.dp), fraction = 1f, sample = "¥ 0.00")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        repeat(3) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                ContentPlaceholder(Modifier.size(8.dp), cornerRadius = 4.dp)
                Spacer(Modifier.width(8.dp))
                DashboardPlaceholder(13.sp, Modifier.weight(1f), fraction = .45f)
                DashboardPlaceholder(13.sp, Modifier.width(64.dp), fraction = .8f, sample = "¥ 0.00")
                Box(Modifier.width(42.dp), contentAlignment = Alignment.CenterEnd) {
                    DashboardPlaceholder(12.sp, Modifier.width(28.dp), fraction = 1f, sample = "00%")
                }
            }
        }
    }
}

@Composable
private fun BillChartHeaderPlaceholder() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DashboardPlaceholder(15.sp, Modifier.weight(1f), fraction = .35f)
        DashboardPlaceholder(12.sp, Modifier.width(72.dp), fraction = 1f)
    }
}

@Composable
internal fun CategoryDonutChart(categories: List<BillCategory>, type: Int, modifier: Modifier = Modifier) {
    val palette = chartPalette()
    val total = categories.sumOf { it.amountCents }
    var expanded by rememberSaveable(type) { mutableStateOf(false) }
    val displayed = rememberChartValues(categories.associate { it.name to it.amountCents.toDouble() })
    val displayedTotal = displayed.values.sum()
    val assignedColors = remember(type) { linkedMapOf<String, Int>() }
    categories.forEach { assignedColors.getOrPut(it.name) { assignedColors.size } }
    fun categoryColor(name: String) = palette[(assignedColors[name] ?: 0) % palette.size]
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val grid = MiuixTheme.colorScheme.dividerLine
    ReadLayout(modifier.fillMaxWidth()) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("分类占比", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (type == 2) "支出" else "收入", fontSize = 12.sp, color = summary)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val strokeWidth = 22.dp.toPx()
                    val diameter = size.minDimension - strokeWidth
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    if (displayedTotal <= 0.0) {
                        drawCircle(
                            color = grid,
                            radius = diameter / 2,
                            center = center,
                            style = Stroke(width = strokeWidth),
                        )
                    } else {
                        var start = -90f
                        displayed.filterValues { it > 0.0 }.forEach { (name, amount) ->
                            val sweep = (amount / displayedTotal * 360).toFloat()
                            drawArc(
                                color = categoryColor(name),
                                startAngle = start + 1.5f,
                                sweepAngle = (sweep - 3f).coerceAtLeast(0.5f),
                                useCenter = false,
                                topLeft = topLeft,
                                size = Size(diameter, diameter),
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            )
                            start += sweep
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (type == 2) "支出合计" else "收入合计", fontSize = 12.sp, color = summary)
                    UpdatedText(money(total), prefix = "¥ ", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (categories.isEmpty()) {
            Text(
                "暂无分类数据", modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                fontSize = 13.sp, color = summary, textAlign = TextAlign.Center,
            )
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        (if (expanded) categories else categories.take(6)).forEach { category ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(categoryColor(category.name), CircleShape))
                Spacer(Modifier.width(8.dp))
                UpdatedText(
                    category.name.ifBlank { "其他" }, modifier = Modifier.weight(1f),
                    numeric = false, fontSize = 13.sp,
                )
                UpdatedText(money(category.amountCents), prefix = "¥ ", identity = category.name, fontSize = 13.sp)
                Text(
                    String.format(Locale.CHINA, "%.0f%%", category.amountCents * 100.0 / total.coerceAtLeast(1)),
                    modifier = Modifier.width(42.dp), fontSize = 12.sp, color = summary, textAlign = TextAlign.End,
                )
            }
        }
        if (categories.size > 6) TextButton(if (expanded) "收起分类" else "查看全部 ${categories.size} 项",
            onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth())
    }
    }
}

@Composable
private fun chartPalette(): List<Color> {
    val colors = MiuixTheme.colorScheme
    return listOf(
        colors.primary,
        colors.error,
        colors.primaryVariant,
        colors.secondaryVariant,
        colors.secondary,
        colors.onSurfaceVariantSummary,
    )
}
