package com.example.tail.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// ── Time period options ───────────────────────────────────────────────────────

enum class GraphTimePeriod(val label: String, val days: Int?) {
    WEEK("1W", 7),
    TWO_WEEKS("2W", 14),
    MONTH("1M", 30),
    THREE_MONTHS("3M", 90),
    SIX_MONTHS("6M", 180),
    YEAR("1Y", 365),
    MAX("Max", null)
}

// ── Graph colors for multiple habits ──────────────────────────────────────────

private val GRAPH_COLORS = listOf(
    Color(0xFF4FC3F7),  // light blue
    Color(0xFFFF8A65),  // orange
    Color(0xFF81C784),  // green
    Color(0xFFBA68C8),  // purple
    Color(0xFFFFD54F),  // yellow
    Color(0xFFE57373),  // red
    Color(0xFF4DD0E1),  // cyan
    Color(0xFFA1887F),  // brown
    Color(0xFFAED581),  // lime
    Color(0xFFF06292),  // pink
)

private val SHORT_DATE_FMT = DateTimeFormatter.ofPattern("M/d")
private val MEDIUM_DATE_FMT = DateTimeFormatter.ofPattern("MMM d")
private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy")

// ── Main Graphs Content ───────────────────────────────────────────────────────

/**
 * The graphs panel shown below the habit grid (portrait) or fullscreen (landscape).
 *
 * Portrait: shows time period controls + chart + stats summary + legend
 * Landscape: shows time period controls + chart only (no stats, no habit chips)
 *
 * Habit selection is done by tapping the habit icons in the grid above — no extra chips here.
 */
@Composable
fun GraphsPanel(
    viewModel: HabitViewModel,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val graphSelectedHabits by viewModel.graphSelectedHabits.collectAsState()
    val selectedPeriod by viewModel.graphTimePeriod.collectAsState()

    var selectedDataPoint by remember { mutableStateOf<SelectedPoint?>(null) }
    var textEntriesForPoint by remember { mutableStateOf<List<String>>(emptyList()) }
    var datedEntriesForPoint by remember { mutableStateOf<List<String>>(emptyList()) }

    // When selection or period changes, clear the selected data point
    LaunchedEffect(graphSelectedHabits, selectedPeriod) {
        selectedDataPoint = null
        textEntriesForPoint = emptyList()
        datedEntriesForPoint = emptyList()
    }

    // When a data point is selected, load text entries and/or dated entries if applicable
    LaunchedEffect(selectedDataPoint) {
        val point = selectedDataPoint
        if (point != null) {
            if (viewModel.isTextInputHabit(point.habitName)) {
                viewModel.loadTextEntriesForDate(point.habitName, point.date) { entries ->
                    textEntriesForPoint = entries
                }
            } else {
                textEntriesForPoint = emptyList()
            }
            if (viewModel.isDatedEntryHabit(point.habitName)) {
                viewModel.loadDatedEntriesForDate(point.habitName, point.date) { chunks ->
                    datedEntriesForPoint = chunks
                }
            } else {
                datedEntriesForPoint = emptyList()
            }
        } else {
            textEntriesForPoint = emptyList()
            datedEntriesForPoint = emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A1A))
    ) {
        // ── Time period selector — shown in both portrait and landscape ────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GraphTimePeriod.entries.forEach { period ->
                val isActive = period == selectedPeriod
                Text(
                    text = period.label,
                    color = if (isActive) Color(0xFF000000) else Color(0xFF88AACC),
                    fontSize = 11.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(
                            if (isActive) Color(0xFF4FC3F7) else Color(0xFF1A1A2E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { viewModel.setGraphTimePeriod(period) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // ── Chart area ────────────────────────────────────────────────────
        if (graphSelectedHabits.isNotEmpty()) {
            val today = LocalDate.now()
            val earliestDate = viewModel.getEarliestDate(graphSelectedHabits)
            val startDate = when {
                selectedPeriod.days != null -> today.minusDays(selectedPeriod.days!!.toLong() - 1)
                earliestDate != null -> earliestDate
                else -> today.minusDays(29)
            }
            val endDate = today

            // Collect data for all selected habits
            val allSeriesData = remember(graphSelectedHabits, selectedPeriod) {
                graphSelectedHabits.toList().mapIndexed { idx, habitName ->
                    val data = viewModel.getGraphData(habitName, startDate, endDate)
                    GraphSeries(
                        habitName = habitName,
                        data = data,
                        color = GRAPH_COLORS[idx % GRAPH_COLORS.size],
                        isTextInput = viewModel.isTextInputHabit(habitName)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) Modifier.weight(1f)
                        else Modifier.height(220.dp)
                    )
                    .padding(horizontal = 4.dp)
            ) {
                HabitLineChart(
                    seriesData = allSeriesData,
                    startDate = startDate,
                    endDate = endDate,
                    onPointSelected = { point -> selectedDataPoint = point },
                    selectedPoint = selectedDataPoint,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── Selected point info tooltip ───────────────────────────────
            selectedDataPoint?.let { point ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = point.date.format(FULL_DATE_FMT),
                            color = Color(0xFFCCDDEE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${point.habitName}: ${point.value}",
                            color = point.color,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (point.rawValue != point.value) {
                        Text(
                            text = "Raw: ${point.rawValue}",
                            color = Color(0xFF888899),
                            fontSize = 10.sp
                        )
                    }
                    // Show text entries for text-input habits
                    if (textEntriesForPoint.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = Color(0xFF333344), thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Text entries:",
                            color = Color(0xFF88AACC),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        textEntriesForPoint.forEach { entry ->
                            Text(
                                text = "• $entry",
                                color = Color(0xFFCCDDEE),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }
                    // Show dated-entry chunks for dated-entry habits
                    if (datedEntriesForPoint.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        HorizontalDivider(color = Color(0xFF333344), thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Entries (${datedEntriesForPoint.size}):",
                            color = Color(0xFFFFCC44),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        datedEntriesForPoint.forEachIndexed { idx, chunk ->
                            if (idx > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                HorizontalDivider(
                                    color = Color(0xFF2A2A1A),
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Text(
                                text = chunk,
                                color = Color(0xFFEEDDAA),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }

            // ── Stats summary — portrait only ─────────────────────────────
            if (!isLandscape && selectedDataPoint == null) {
                StatsSummary(
                    seriesData = allSeriesData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            // ── Legend — portrait only ────────────────────────────────────
            if (!isLandscape && graphSelectedHabits.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    allSeriesData.forEach { series ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(series.color, CircleShape)
                            )
                            Text(
                                text = series.habitName,
                                color = series.color,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // ── Landscape legend (compact, inline) ────────────────────────
            if (isLandscape && graphSelectedHabits.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    allSeriesData.forEach { series ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(series.color, CircleShape)
                            )
                            Text(
                                text = series.habitName,
                                color = series.color,
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        } else {
            // No habits selected — show prompt
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isLandscape) Modifier.weight(1f)
                        else Modifier.height(120.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "📊",
                        fontSize = 32.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isLandscape)
                            "Tap habit icons (portrait) to add them to the graph"
                        else
                            "Tap habit icons above to add them to the graph",
                        color = Color(0xFF666688),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class GraphSeries(
    val habitName: String,
    val data: List<HabitViewModel.GraphDataPoint>,
    val color: Color,
    val isTextInput: Boolean = false
)

data class SelectedPoint(
    val habitName: String,
    val date: LocalDate,
    val value: Int,
    val rawValue: Int,
    val color: Color
)

// ── Custom Canvas Line Chart ─────────────────────────────────────────────────

@Composable
private fun HabitLineChart(
    seriesData: List<GraphSeries>,
    startDate: LocalDate,
    endDate: LocalDate,
    onPointSelected: (SelectedPoint?) -> Unit,
    selectedPoint: SelectedPoint?,
    modifier: Modifier = Modifier
) {
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1

    // Find global max for Y axis
    val globalMax = seriesData.maxOfOrNull { series ->
        series.data.maxOfOrNull { it.pointsValue } ?: 0
    } ?: 1
    val yMax = if (globalMax == 0) 1 else globalMax

    // Calculate nice Y axis ticks
    val yTicks = calculateYTicks(yMax)
    val effectiveYMax = yTicks.lastOrNull() ?: yMax

    Canvas(
        modifier = modifier
            .pointerInput(seriesData, startDate, endDate) {
                detectTapGestures { offset ->
                    val chartLeft = 40.dp.toPx()
                    val chartRight = size.width - 12.dp.toPx()
                    val chartTop = 12.dp.toPx()
                    val chartBottom = size.height - 28.dp.toPx()
                    val chartWidth = chartRight - chartLeft
                    val chartHeight = chartBottom - chartTop

                    if (totalDays <= 0 || chartWidth <= 0) return@detectTapGestures

                    val tapX = offset.x
                    val tapY = offset.y

                    var closestPoint: SelectedPoint? = null
                    var closestDist = Float.MAX_VALUE

                    for (series in seriesData) {
                        for (dp in series.data) {
                            val dayIdx =
                                ChronoUnit.DAYS.between(startDate, dp.date).toInt()
                            val x =
                                chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
                            val y =
                                chartBottom - (dp.pointsValue.toFloat() / effectiveYMax) * chartHeight

                            val dist = kotlin.math.sqrt(
                                (tapX - x) * (tapX - x) + (tapY - y) * (tapY - y)
                            )
                            if (dist < closestDist && dist < 60.dp.toPx()) {
                                closestDist = dist
                                closestPoint = SelectedPoint(
                                    habitName = series.habitName,
                                    date = dp.date,
                                    value = dp.pointsValue,
                                    rawValue = dp.rawValue,
                                    color = series.color
                                )
                            }
                        }
                    }
                    onPointSelected(closestPoint)
                }
            }
    ) {
        val chartLeft = 40.dp.toPx()
        val chartRight = size.width - 12.dp.toPx()
        val chartTop = 12.dp.toPx()
        val chartBottom = size.height - 28.dp.toPx()
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        if (chartWidth <= 0 || chartHeight <= 0) return@Canvas

        // ── Draw grid lines and Y axis labels ─────────────────────────────
        val textPaint = android.graphics.Paint().apply {
            color = 0xFF666688.toInt()
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        for (tick in yTicks) {
            val y = chartBottom - (tick.toFloat() / effectiveYMax) * chartHeight
            drawLine(
                color = Color(0xFF1A1A2E),
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

        // ── Draw X axis labels ────────────────────────────────────────────
        val xLabelPaint = android.graphics.Paint().apply {
            color = 0xFF666688.toInt()
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
                color = Color(0xFF111122),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // ── Draw zero line ────────────────────────────────────────────────
        drawLine(
            color = Color(0xFF333344),
            start = Offset(chartLeft, chartBottom),
            end = Offset(chartRight, chartBottom),
            strokeWidth = 1.dp.toPx()
        )

        // ── Draw each series ──────────────────────────────────────────────
        for (series in seriesData) {
            if (series.data.isEmpty()) continue

            val points = series.data.map { dp ->
                val dayIdx = ChronoUnit.DAYS.between(startDate, dp.date).toInt()
                val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
                val y = chartBottom - (dp.pointsValue.toFloat() / effectiveYMax) * chartHeight
                Offset(x, y)
            }

            // Draw filled area under the line
            if (points.size >= 2) {
                val areaPath = Path().apply {
                    moveTo(points.first().x, chartBottom)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, chartBottom)
                    close()
                }
                drawPath(
                    path = areaPath,
                    color = series.color.copy(alpha = 0.08f)
                )
            }

            // Draw the line
            if (points.size >= 2) {
                val linePath = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    path = linePath,
                    color = series.color.copy(alpha = 0.8f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            // Draw data point dots (only if not too many)
            if (totalDays <= 90) {
                points.forEachIndexed { idx, point ->
                    val dp = series.data[idx]
                    val isSelected = selectedPoint?.habitName == series.habitName &&
                            selectedPoint?.date == dp.date
                    val dotRadius = if (isSelected) 5.dp.toPx() else 2.5.dp.toPx()

                    if (dp.pointsValue > 0 || isSelected) {
                        drawCircle(
                            color = if (isSelected) Color.White else series.color,
                            radius = dotRadius,
                            center = point
                        )
                        if (isSelected) {
                            drawCircle(
                                color = series.color,
                                radius = dotRadius - 1.5.dp.toPx(),
                                center = point
                            )
                        }
                    }
                }
            }

            // Draw moving average line (7-day) if enough data
            if (series.data.size >= 7 && totalDays > 14) {
                drawMovingAverage(
                    data = series.data,
                    windowSize = 7,
                    color = series.color.copy(alpha = 0.4f),
                    startDate = startDate,
                    totalDays = totalDays,
                    effectiveYMax = effectiveYMax,
                    chartLeft = chartLeft,
                    chartBottom = chartBottom,
                    chartWidth = chartWidth,
                    chartHeight = chartHeight,
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        // ── Draw selected point crosshair ─────────────────────────────────
        selectedPoint?.let { sp ->
            val dayIdx = ChronoUnit.DAYS.between(startDate, sp.date).toInt()
            val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
            val y = chartBottom - (sp.value.toFloat() / effectiveYMax) * chartHeight

            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )
            drawLine(
                color = Color(0x44FFFFFF),
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            )

            val valuePaint = android.graphics.Paint().apply {
                color = 0xFFFFFFFF.toInt()
                textSize = 11.dp.toPx()
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            val bgPaint = android.graphics.Paint().apply {
                color = 0xCC1A1A2E.toInt()
                isAntiAlias = true
            }
            val label = sp.value.toString()
            val labelWidth = valuePaint.measureText(label)
            val labelX = x
            val labelY = y - 12.dp.toPx()

            drawContext.canvas.nativeCanvas.drawRoundRect(
                labelX - labelWidth / 2 - 4.dp.toPx(),
                labelY - 12.dp.toPx(),
                labelX + labelWidth / 2 + 4.dp.toPx(),
                labelY + 4.dp.toPx(),
                4.dp.toPx(),
                4.dp.toPx(),
                bgPaint
            )
            drawContext.canvas.nativeCanvas.drawText(label, labelX, labelY, valuePaint)
        }
    }
}

/**
 * Draws a moving average line on the chart.
 */
private fun DrawScope.drawMovingAverage(
    data: List<HabitViewModel.GraphDataPoint>,
    windowSize: Int,
    color: Color,
    startDate: LocalDate,
    totalDays: Int,
    effectiveYMax: Int,
    chartLeft: Float,
    chartBottom: Float,
    chartWidth: Float,
    chartHeight: Float,
    strokeWidth: Float
) {
    if (data.size < windowSize) return

    val maPoints = mutableListOf<Offset>()
    for (i in windowSize - 1 until data.size) {
        val windowAvg = data.subList(i - windowSize + 1, i + 1)
            .map { it.pointsValue.toFloat() }
            .average()
            .toFloat()
        val dp = data[i]
        val dayIdx = ChronoUnit.DAYS.between(startDate, dp.date).toInt()
        val x = chartLeft + (dayIdx.toFloat() / (totalDays - 1).coerceAtLeast(1)) * chartWidth
        val y = chartBottom - (windowAvg / effectiveYMax) * chartHeight
        maPoints.add(Offset(x, y))
    }

    if (maPoints.size >= 2) {
        val path = Path().apply {
            moveTo(maPoints.first().x, maPoints.first().y)
            for (i in 1 until maPoints.size) {
                lineTo(maPoints[i].x, maPoints[i].y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        )
    }
}

/**
 * Calculate nice Y axis tick values.
 */
private fun calculateYTicks(maxValue: Int): List<Int> {
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

// ── Stats Summary ─────────────────────────────────────────────────────────────

@Composable
private fun StatsSummary(
    seriesData: List<GraphSeries>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF0D0D1E), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        for (series in seriesData) {
            if (series.data.isEmpty()) continue
            val values = series.data.map { it.pointsValue }
            val nonZeroValues = values.filter { it > 0 }
            val total = values.sum()
            val avg = if (values.isNotEmpty()) values.average() else 0.0
            val max = values.maxOrNull() ?: 0
            val daysActive = nonZeroValues.size
            val totalDays = values.size
            val consistency = if (totalDays > 0) (daysActive * 100.0 / totalDays) else 0.0

            var currentStreak = 0
            for (v in values.reversed()) {
                if (v > 0) currentStreak++ else break
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (seriesData.size > 1) {
                    Text(
                        text = series.habitName,
                        color = series.color,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(80.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatChip("Total", total.toString(), Color(0xFF88CCFF))
                StatChip("Avg", "%.1f".format(avg), Color(0xFF81C784))
                StatChip("Max", max.toString(), Color(0xFFFFD54F))
                StatChip("Active", "$daysActive/$totalDays", Color(0xFFBA68C8))
                StatChip("${consistency.roundToInt()}%", "cons.", Color(0xFFFF8A65))
                if (currentStreak > 0) {
                    StatChip("🔥$currentStreak", "streak", Color(0xFFE57373))
                }
            }

            if (seriesData.size > 1 && series != seriesData.last()) {
                HorizontalDivider(
                    color = Color(0xFF1A1A2E),
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color(0xFF555566),
            fontSize = 8.sp
        )
    }
}
