package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.parseDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// ── Color palette ─────────────────────────────────────────────────────────────
private val PopupBg = Color(0xFF0D0D1A)
private val TitleColor = Color(0xFFFFD700)
private val LabelColor = Color(0xFFADD8E6)
private val DimColor = Color(0xFF888888)
private val GridLineColor = Color(0xFF1E1E30)
private val AxisLabelColor = Color(0xFF7799AA)
private val ChartBorderColor = Color(0xFF2A2A40)

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("M/d")
private val MEDIUM_DATE_FMT = DateTimeFormatter.ofPattern("MMM d")
private val YEAR_DATE_FMT = DateTimeFormatter.ofPattern("MMM ''yy")

/**
 * A fullscreen landscape popup dialog showing a professional line chart
 * with pinch-to-zoom support.
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
    // Force landscape orientation — state is saved via rememberSaveable in the
    // caller so it survives the configuration change this triggers.
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PopupBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ── Header row ──────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TitleColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // Current value badge
                val displayValue = currentValue ?: data.lastOrNull()?.second
                if (displayValue != null) {
                    Text(
                        text = "Current: $displayValue",
                        color = lineColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                // Zoom hint
                Text(
                    text = "Pinch to zoom",
                    color = DimColor,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(end = 12.dp)
                )

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = LabelColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Chart ───────────────────────────────────────────────────────
            if (data.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No data available", color = DimColor, fontSize = 13.sp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 20f)
                                // Adjust offset so zoom centers on the gesture
                                offsetX = (offsetX * (newScale / scale)) + pan.x
                                scale = newScale
                                // Clamp offset
                                val maxOffset = size.width * (scale - 1f)
                                offsetX = offsetX.coerceIn(-maxOffset, 0f)
                            }
                        }
                ) {
                    StreakLineChart(
                        data = data,
                        lineColor = lineColor,
                        scale = scale,
                        offsetX = offsetX,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Zoom reset hint ─────────────────────────────────────────────
            if (scale > 1.05f) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Zoom: %.1f×  •  Drag to pan".format(scale),
                        color = DimColor,
                        fontSize = 10.sp
                    )
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
    scale: Float,
    offsetX: Float,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return

    val dates = data.map { parseDate(it.first) ?: LocalDate.now() }
    val values = data.map { it.second }
    val startDate = dates.first()
    val endDate = dates.last()
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1

    val maxValue = values.maxOrNull() ?: 1
    val minValue = values.minOrNull() ?: 0
    val yMax = if (maxValue == 0) 1 else maxValue
    val yTicks = calculateStreakYTicks(yMax)
    val effectiveYMax = yTicks.lastOrNull() ?: yMax

    Canvas(modifier = modifier) {
        val chartLeft = 48.dp.toPx()
        val chartRight = size.width - 16.dp.toPx()
        val chartTop = 16.dp.toPx()
        val chartBottom = size.height - 32.dp.toPx()
        val baseChartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (baseChartWidth <= 0 || chartHeight <= 0) return@Canvas

        // Apply zoom: the logical chart width is scaled
        val scaledChartWidth = baseChartWidth * scale

        // ── Background subtle gradient ──────────────────────────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0F0F20), Color(0xFF0A0A15)),
                startY = chartTop,
                endY = chartBottom
            ),
            topLeft = Offset(chartLeft, chartTop),
            size = androidx.compose.ui.geometry.Size(baseChartWidth, chartHeight)
        )

        // ── Chart border ────────────────────────────────────────────────────
        drawRect(
            color = ChartBorderColor,
            topLeft = Offset(chartLeft, chartTop),
            size = androidx.compose.ui.geometry.Size(baseChartWidth, chartHeight),
            style = Stroke(width = 1.dp.toPx())
        )

        // ── Y axis labels and grid lines ────────────────────────────────────
        val textPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.hashCode()
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }

        for (tick in yTicks) {
            val y = chartBottom - (tick.toFloat() / effectiveYMax) * chartHeight
            if (y < chartTop || y > chartBottom) continue
            // Grid line
            drawLine(
                color = GridLineColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )
            // Label
            drawContext.canvas.nativeCanvas.drawText(
                formatYLabel(tick),
                chartLeft - 6.dp.toPx(),
                y + 4.dp.toPx(),
                textPaint
            )
        }

        // ── Clip to chart area for data rendering ───────────────────────────
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.clipRect(
            chartLeft, chartTop, chartRight, chartBottom
        )

        // ── X axis labels ───────────────────────────────────────────────────
        val xLabelPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.hashCode()
            textSize = 9.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }

        // Determine visible range based on zoom/pan
        val visibleStartFraction = (-offsetX / scaledChartWidth).coerceIn(0f, 1f)
        val visibleEndFraction = ((-offsetX + baseChartWidth) / scaledChartWidth).coerceIn(0f, 1f)
        val visibleDaysStart = (visibleStartFraction * (totalDays - 1)).toInt()
        val visibleDaysEnd = (visibleEndFraction * (totalDays - 1)).toInt()
        val visibleDays = visibleDaysEnd - visibleDaysStart + 1

        val labelInterval = when {
            visibleDays <= 7 -> 1
            visibleDays <= 14 -> 2
            visibleDays <= 30 -> 5
            visibleDays <= 60 -> 7
            visibleDays <= 120 -> 14
            visibleDays <= 365 -> 30
            visibleDays <= 730 -> 60
            else -> (visibleDays / 10).coerceAtLeast(30)
        }

        val dateFmt = when {
            visibleDays <= 30 -> SHORT_DATE_FMT
            visibleDays <= 365 -> MEDIUM_DATE_FMT
            else -> YEAR_DATE_FMT
        }

        // Draw x labels within visible range
        val labelStart = ((visibleDaysStart / labelInterval) * labelInterval).coerceAtLeast(0)
        for (i in labelStart..visibleDaysEnd.coerceAtMost(totalDays - 1) step labelInterval) {
            val date = startDate.plusDays(i.toLong())
            val x = chartLeft + (i.toFloat() / (totalDays - 1).coerceAtLeast(1)) * scaledChartWidth + offsetX
            if (x < chartLeft - 20.dp.toPx() || x > chartRight + 20.dp.toPx()) continue

            // Vertical grid line
            drawLine(
                color = GridLineColor.copy(alpha = 0.4f),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // ── Zero baseline ───────────────────────────────────────────────────
        drawLine(
            color = Color(0xFF334455),
            start = Offset(chartLeft, chartBottom),
            end = Offset(chartRight, chartBottom),
            strokeWidth = 1.dp.toPx()
        )

        // ── Data points and line ────────────────────────────────────────────
        val points = data.mapNotNull { (dateStr, value) ->
            val date = parseDate(dateStr) ?: return@mapNotNull null
            val dayIdx = ChronoUnit.DAYS.between(startDate, date).toInt()
            val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * scaledChartWidth + offsetX
            val y = chartBottom - (value.toFloat() / effectiveYMax) * chartHeight
            Offset(x, y)
        }

        // Gradient fill under the line
        if (points.size >= 2) {
            val areaPath = Path().apply {
                moveTo(points.first().x, chartBottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, chartBottom)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.25f),
                        lineColor.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    startY = chartTop,
                    endY = chartBottom
                )
            )
        }

        // Main line with glow effect
        if (points.size >= 2) {
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            // Glow layer
            drawPath(
                path = linePath,
                color = lineColor.copy(alpha = 0.3f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )
            // Main line
            drawPath(
                path = linePath,
                color = lineColor.copy(alpha = 0.95f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Dots (only when zoomed in enough to see individual points)
        if (visibleDays <= 60) {
            points.forEach { point ->
                if (point.x >= chartLeft && point.x <= chartRight) {
                    // Outer glow
                    drawCircle(
                        color = lineColor.copy(alpha = 0.3f),
                        radius = 4.dp.toPx(),
                        center = point
                    )
                    // Inner dot
                    drawCircle(
                        color = lineColor,
                        radius = 2.dp.toPx(),
                        center = point
                    )
                }
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
                val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * scaledChartWidth + offsetX
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
                    color = Color(0xFFFFD700).copy(alpha = 0.5f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                    )
                )
            }
        }

        // Restore canvas clip
        drawContext.canvas.nativeCanvas.restore()

        // ── X axis labels (drawn outside clip) ──────────────────────────────
        for (i in labelStart..visibleDaysEnd.coerceAtMost(totalDays - 1) step labelInterval) {
            val date = startDate.plusDays(i.toLong())
            val x = chartLeft + (i.toFloat() / (totalDays - 1).coerceAtLeast(1)) * scaledChartWidth + offsetX
            if (x < chartLeft - 20.dp.toPx() || x > chartRight + 20.dp.toPx()) continue

            drawContext.canvas.nativeCanvas.drawText(
                date.format(dateFmt),
                x,
                chartBottom + 18.dp.toPx(),
                xLabelPaint
            )
        }

        // ── Legend ───────────────────────────────────────────────────────────
        if (data.size >= 7 && totalDays > 14) {
            val legendPaint = android.graphics.Paint().apply {
                color = DimColor.hashCode()
                textSize = 8.dp.toPx()
                isAntiAlias = true
                typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
            }
            // MA legend
            val legendX = chartRight - 100.dp.toPx()
            val legendY = chartTop + 14.dp.toPx()
            drawLine(
                color = Color(0xFFFFD700).copy(alpha = 0.5f),
                start = Offset(legendX, legendY),
                end = Offset(legendX + 20.dp.toPx(), legendY),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
            )
            drawContext.canvas.nativeCanvas.drawText(
                "7-day avg",
                legendX + 24.dp.toPx(),
                legendY + 3.dp.toPx(),
                legendPaint
            )
        }
    }
}

/**
 * Format Y axis labels nicely (e.g., 1K, 2.5K for large numbers).
 */
private fun formatYLabel(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "%.0fK".format(value / 1_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
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
