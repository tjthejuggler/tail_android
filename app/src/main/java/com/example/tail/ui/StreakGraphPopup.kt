package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.parseDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// ── Color palette (matching AppStatsScreen) ───────────────────────────────────
private val PopupBg = Color(0xFF1A1A2E)
private val TitleColor = Color(0xFFFFD700)
private val LabelColor = Color(0xFFADD8E6)
private val DimColor = Color(0xFF888888)
private val GridLineColor = Color(0xFF2A2A3E)
private val AxisLabelColor = Color(0xFF668888)

// ── Time period filter options ────────────────────────────────────────────────
private enum class StreakGraphPeriod(val label: String, val days: Int?) {
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR("1Y", 365),
    MAX("Max", null)
}

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("M/d")
private val MEDIUM_DATE_FMT = DateTimeFormatter.ofPattern("MMM d")

/**
 * A popup dialog showing a line chart of historical streak/anti-streak data.
 * In landscape mode, the dialog becomes fullscreen.
 *
 * [data] is a list of (dateString, value) pairs in chronological order.
 * [lineColor] is the color of the line (green for streaks, red for anti-streaks).
 */
@Composable
fun StreakGraphPopup(
    title: String,
    data: List<Pair<String, Int>>,
    lineColor: Color,
    currentValue: Int? = null,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    // Lock to landscape when user rotates, unlock on dismiss
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        onDispose {
            // Restore sensor-based orientation when popup is dismissed
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var selectedPeriod by remember { mutableStateOf(StreakGraphPeriod.MAX) }

    // Filter data based on selected period
    val filteredData = remember(data, selectedPeriod) {
        if (selectedPeriod.days == null || data.isEmpty()) {
            data
        } else {
            val cutoffDate = LocalDate.now().minusDays(selectedPeriod.days!!.toLong())
            val cutoffStr = com.example.tail.data.dateString(cutoffDate)
            data.filter { (dateStr, _) -> dateStr >= cutoffStr }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isLandscape,
            dismissOnBackPress = true,
            dismissOnClickOutside = !isLandscape
        )
    ) {
        val modifier = if (isLandscape) {
            Modifier
                .fillMaxSize()
                .background(PopupBg)
        } else {
            Modifier
                .fillMaxWidth()
                .background(PopupBg, RoundedCornerShape(12.dp))
        }

        Column(
            modifier = modifier.padding(if (isLandscape) 8.dp else 16.dp)
        ) {
            // ── Title row ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TitleColor,
                    fontSize = if (isLandscape) 13.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!isLandscape) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
                    ) {
                        Text("Close", color = LabelColor, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

            // ── Time period selector ──────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StreakGraphPeriod.entries.forEach { period ->
                    val isActive = period == selectedPeriod
                    Text(
                        text = period.label,
                        color = if (isActive) Color(0xFF000000) else Color(0xFF8888AA),
                        fontSize = 11.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(
                                if (isActive) lineColor.copy(alpha = 0.8f) else Color(0xFF2A2A3E),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { selectedPeriod = period }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Current value display
                Spacer(modifier = Modifier.weight(1f))
                val displayValue = currentValue ?: filteredData.lastOrNull()?.second
                if (displayValue != null) {
                    Text(
                        text = "Current: $displayValue",
                        color = lineColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isLandscape) 4.dp else 8.dp))

            // ── Chart ─────────────────────────────────────────────────────
            if (filteredData.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscape) 200.dp else 250.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data available", color = DimColor, fontSize = 13.sp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isLandscape) Modifier.weight(1f)
                            else Modifier.height(250.dp)
                        )
                ) {
                    StreakLineChart(
                        data = filteredData,
                        lineColor = lineColor,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Close button in landscape ─────────────────────────────────
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
                    ) {
                        Text("Close", color = LabelColor, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ── Line chart composable ─────────────────────────────────────────────────────

@Composable
private fun StreakLineChart(
    data: List<Pair<String, Int>>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val dates = data.map { parseDate(it.first) ?: LocalDate.now() }
    val values = data.map { it.second }
    val startDate = dates.first()
    val endDate = dates.last()
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1

    val maxValue = values.maxOrNull() ?: 1
    val yMax = if (maxValue == 0) 1 else maxValue
    val yTicks = calculateStreakYTicks(yMax)
    val effectiveYMax = yTicks.lastOrNull() ?: yMax

    Canvas(modifier = modifier) {
        val chartLeft = 40.dp.toPx()
        val chartRight = size.width - 12.dp.toPx()
        val chartTop = 12.dp.toPx()
        val chartBottom = size.height - 28.dp.toPx()
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

        // ── Y axis labels and grid lines ──────────────────────────────────
        val textPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.hashCode()
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        for (tick in yTicks) {
            val y = chartBottom - (tick.toFloat() / effectiveYMax) * chartHeight
            drawLine(
                color = GridLineColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            )
            drawContext.canvas.nativeCanvas.drawText(
                tick.toString(),
                chartLeft - 4.dp.toPx(),
                y + 4.dp.toPx(),
                textPaint
            )
        }

        // ── X axis labels ─────────────────────────────────────────────────
        val xLabelPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.hashCode()
            textSize = 9.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val labelInterval = when {
            totalDays <= 7 -> 1
            totalDays <= 14 -> 2
            totalDays <= 30 -> 5
            totalDays <= 90 -> 10
            totalDays <= 180 -> 20
            totalDays <= 365 -> 30
            else -> (totalDays / 12).coerceAtLeast(30)
        }

        val dateFmt = if (totalDays <= 30) SHORT_DATE_FMT else MEDIUM_DATE_FMT

        for (i in 0 until totalDays step labelInterval) {
            val date = startDate.plusDays(i.toLong())
            val x = chartLeft + (i.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
            drawContext.canvas.nativeCanvas.drawText(
                date.format(dateFmt),
                x,
                chartBottom + 16.dp.toPx(),
                xLabelPaint
            )
            drawLine(
                color = GridLineColor.copy(alpha = 0.3f),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // ── Zero line ─────────────────────────────────────────────────────
        drawLine(
            color = Color(0xFF334444),
            start = Offset(chartLeft, chartBottom),
            end = Offset(chartRight, chartBottom),
            strokeWidth = 1.dp.toPx()
        )

        // ── Data points and line ──────────────────────────────────────────
        val points = data.mapNotNull { (dateStr, value) ->
            val date = parseDate(dateStr) ?: return@mapNotNull null
            val dayIdx = ChronoUnit.DAYS.between(startDate, date).toInt()
            val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
            val y = chartBottom - (value.toFloat() / effectiveYMax) * chartHeight
            Offset(x, y)
        }

        // Filled area
        if (points.size >= 2) {
            val areaPath = Path().apply {
                moveTo(points.first().x, chartBottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, chartBottom)
                close()
            }
            drawPath(path = areaPath, color = lineColor.copy(alpha = 0.1f))
        }

        // Line
        if (points.size >= 2) {
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            drawPath(
                path = linePath,
                color = lineColor.copy(alpha = 0.9f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Dots (only when not too many points)
        if (totalDays <= 90) {
            points.forEach { point ->
                drawCircle(
                    color = lineColor,
                    radius = 2.5.dp.toPx(),
                    center = point
                )
            }
        }

        // 7-day moving average (when enough data)
        if (data.size >= 7 && totalDays > 14) {
            val maPoints = mutableListOf<Offset>()
            for (i in 6 until data.size) {
                val windowAvg = data.subList(i - 6, i + 1)
                    .map { it.second.toFloat() }
                    .average()
                    .toFloat()
                val dateStr = data[i].first
                val date = parseDate(dateStr) ?: continue
                val dayIdx = ChronoUnit.DAYS.between(startDate, date).toInt()
                val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
                val y = chartBottom - (windowAvg / effectiveYMax) * chartHeight
                maPoints.add(Offset(x, y))
            }

            if (maPoints.size >= 2) {
                val maPath = Path().apply {
                    moveTo(maPoints.first().x, maPoints.first().y)
                    for (i in 1 until maPoints.size) lineTo(maPoints[i].x, maPoints[i].y)
                }
                drawPath(
                    path = maPath,
                    color = lineColor.copy(alpha = 0.4f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                )
            }
        }
    }
}

/**
 * Calculate nice Y axis tick values for the streak graph.
 */
private fun calculateStreakYTicks(maxValue: Int): List<Int> {
    if (maxValue <= 0) return listOf(0, 1)

    val step = when {
        maxValue <= 5 -> 1
        maxValue <= 10 -> 2
        maxValue <= 25 -> 5
        maxValue <= 50 -> 10
        maxValue <= 100 -> 20
        maxValue <= 250 -> 50
        maxValue <= 500 -> 100
        maxValue <= 1000 -> 200
        else -> (maxValue / 5.0).roundToInt().let { s ->
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(s.toDouble()))).toInt()
            if (magnitude > 0) ((s + magnitude - 1) / magnitude) * magnitude else s
        }
    }

    val ticks = mutableListOf<Int>()
    var tick = 0
    while (tick <= maxValue + step) {
        ticks.add(tick)
        tick += step
        if (ticks.size > 20) break
    }
    return ticks
}
