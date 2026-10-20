package com.mercury.yktwx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.BillCategory
import com.mercury.yktwx.data.BillDailyPoint
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

@Composable
internal fun DailyBillChart(points: List<BillDailyPoint>, modifier: Modifier = Modifier) {
    val primary = MiuixTheme.colorScheme.primary
    val grid = MiuixTheme.colorScheme.dividerLine
    val surface = MiuixTheme.colorScheme.surface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val max = points.maxOfOrNull { it.amountCents } ?: 0L
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("每日趋势", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(if (max > 0) "最高 ¥ ${money(max)}" else "暂无数据", fontSize = 12.sp, color = summary)
        }
        Spacer(Modifier.height(12.dp))
        if (points.isEmpty() || max <= 0L) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("本月暂无数据", fontSize = 13.sp, color = summary)
            }
            return@Column
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val chartTop = 8.dp.toPx()
            val chartHeight = size.height - chartTop
            val step = if (points.size == 1) 0f else size.width / (points.size - 1)
            fun point(index: Int): Offset {
                val x = if (points.size == 1) size.width / 2 else index * step
                val ratio = points[index].amountCents.toFloat() / max.toFloat()
                return Offset(x, chartTop + chartHeight * (1f - ratio))
            }
            for (i in 0..4) {
                val y = chartTop + chartHeight * i / 4f
                drawLine(grid.copy(alpha = 0.45f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            if (points.size == 1) {
                val p = point(0)
                drawCircle(primary, radius = 3.5.dp.toPx(), center = p)
                return@Canvas
            }
            val line = Path().apply {
                moveTo(point(0).x, point(0).y)
                for (index in 1 until points.size) lineTo(point(index).x, point(index).y)
            }
            val fill = Path().apply {
                moveTo(0f, size.height)
                lineTo(point(0).x, point(0).y)
                for (index in 1 until points.size) lineTo(point(index).x, point(index).y)
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
            val maxIndex = points.indexOfFirst { it.amountCents == max }
            if (maxIndex >= 0) {
                val p = point(maxIndex)
                drawCircle(primary, radius = 4.5.dp.toPx(), center = p)
                drawCircle(surface, radius = 2.dp.toPx(), center = p)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(dayShort(points.first()), fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text(dayShort(points[points.size / 2]), fontSize = 11.sp, color = summary)
            Spacer(Modifier.weight(1f))
            Text(dayShort(points.last()), fontSize = 11.sp, color = summary)
        }
    }
}

private fun dayShort(point: BillDailyPoint) = "${point.date.monthValue}.${point.date.dayOfMonth}"

@Composable
internal fun CategoryDonutChart(categories: List<BillCategory>, type: Int, modifier: Modifier = Modifier) {
    val palette = chartPalette()
    val total = categories.sumOf { it.amountCents }
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val grid = MiuixTheme.colorScheme.dividerLine
    Column(modifier.fillMaxWidth()) {
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
                    if (total <= 0L) {
                        drawCircle(
                            color = grid,
                            radius = diameter / 2,
                            center = center,
                            style = Stroke(width = strokeWidth),
                        )
                    } else {
                        var start = -90f
                        categories.forEachIndexed { index, category ->
                            val sweep = category.amountCents.toFloat() / total.toFloat() * 360f
                            drawArc(
                                color = palette[index % palette.size],
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
                    Text("¥ ${money(total)}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (categories.isEmpty()) {
            Text(
                "本月暂无分类数据", modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                fontSize = 13.sp, color = summary, textAlign = TextAlign.Center,
            )
            return@Column
        }
        Spacer(Modifier.height(8.dp))
        categories.take(6).forEachIndexed { index, category ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(palette[index % palette.size], CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    category.name.ifBlank { "其他" }, modifier = Modifier.weight(1f),
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("¥ ${money(category.amountCents)}", fontSize = 13.sp)
                Text(
                    String.format(Locale.CHINA, "%.0f%%", category.amountCents * 100.0 / total.coerceAtLeast(1)),
                    modifier = Modifier.width(42.dp), fontSize = 12.sp, color = summary, textAlign = TextAlign.End,
                )
            }
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
