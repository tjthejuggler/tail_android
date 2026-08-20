package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.parseDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// ── Color palette ─────────────────────────────────────────────────────────────
private val PopupBg = Color(0xFF0D0D1A)
private val TitleColor = Color(0xFFFFD700)
private val LabelColor = Color(0xFFADD8E6)
private val DimColor = Color(0xFF888888)
private val GridLineColor = Color(0xFF1E1E30)
private val AxisLabelColor = Color(0xFF7799AA)
private val ChartBorderColor = Color(0xFF2A2A40)
private val CrosshairColor = Color(0xFFE8E8F0)
private val ChipBgColor = Color(0xF21A1A2E)
private val MaColor = Color(0xFFFFD700)

// ── Layout constants ──────────────────────────────────────────────────────────
private val Y_AXIS_WIDTH = 52.dp
private val RIGHT_PAD = 14.dp
private val TOP_PAD = 12.dp
private val BOTTOM_PAD = 26.dp

private const val MAX_ZOOM_FACTOR = 100f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val TAP_MAX_DURATION_MS = 250L

// Custom date formatters that guarantee numeric output
private fun formatShortDate(date: LocalDate): String {
    return "${date.dayOfMonth}/${date.monthValue}"
}

private fun formatYearDate(date: LocalDate): String {
    return "${date.monthValue}/${date.year.toString().takeLast(2)}"
}

private fun formatFullDate(date: LocalDate): String {
    return "${date.dayOfMonth}/${date.monthValue}/${date.year.toString().takeLast(2)}"
}

/**
 * A fullscreen landscape popup dialog showing a line chart with proper
 * pinch-to-zoom (anchored at the pinch centroid), drag-to-pan with edge
 * clamping, Y auto-fit to the visible window, a scrub crosshair with value
 * readout, and double-tap to reset/zoom.
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
    // caller so it survives the configuration change this triggers. On close,
    // restore whatever orientation the host screen had before the popup, so
    // screens that allow free rotation (e.g. Chess Readiness stats) keep it.
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val previousOrientation =
            activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = previousOrientation
        }
    }

    // Parse data once into a chart model (day indices relative to first date).
    val model = remember(data) { buildStreakChartModel(data) }
    val totalRange = (model.totalDays - 1).toFloat()

    // Viewport state, in day units over [0, totalRange].
    var windowStart by remember(model) { mutableFloatStateOf(0f) }
    var windowEnd by remember(model) { mutableFloatStateOf(totalRange) }

    // Scrub crosshair x-position (px within the chart Box), null when idle.
    var scrubX by remember { mutableStateOf<Float?>(null) }

    val windowDays = (windowEnd - windowStart).coerceAtLeast(0.001f)
    val zoomed = totalRange > 0f && windowDays < totalRange * 0.999f
    val zoomFactor = if (totalRange > 0f) totalRange / windowDays else 1f
    val showMa = model.values.size >= 7 && model.totalDays > 14

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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                        modifier = Modifier
                            .padding(start = 10.dp, end = 12.dp)
                            .background(Color(0x331E1E30), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

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
            if (model.dayIdx.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        .pointerInput(model) {
                            val chartLeftPx = Y_AXIS_WIDTH.toPx()
                            val plotWidthPx = (size.width - chartLeftPx - RIGHT_PAD.toPx())
                                .coerceAtLeast(1f)
                            val minWindowDays = max(2f, totalRange / MAX_ZOOM_FACTOR)
                                .coerceAtMost(totalRange)

                            /**
                             * Apply a pan/zoom step anchored at [centroidX] so the data
                             * under the user's fingers stays under their fingers.
                             * The anchor's fraction within the window is preserved:
                             * anchor = start + frac * windowDays must map to the same
                             * screen x before and after the transform.
                             */
                            fun applyGesture(panX: Float, zoom: Float, centroidX: Float) {
                                if (totalRange <= 0f) return
                                val start = windowStart
                                val end = windowEnd
                                val days = (end - start).coerceAtLeast(0.001f)

                                val anchorFrac =
                                    ((centroidX - chartLeftPx) / plotWidthPx).coerceIn(0f, 1f)
                                val anchor = start + anchorFrac * days

                                var newDays = days / zoom.coerceAtLeast(0.05f)
                                newDays = newDays.coerceIn(minWindowDays, totalRange)

                                var newStart = anchor - anchorFrac * newDays
                                var newEnd = newStart + newDays

                                // Pan (dragging right reveals earlier data).
                                val panDays = -panX / plotWidthPx * newDays
                                newStart += panDays
                                newEnd += panDays

                                // Clamp to the full data range.
                                if (newStart < 0f) {
                                    newEnd -= newStart
                                    newStart = 0f
                                }
                                if (newEnd > totalRange) {
                                    newStart -= (newEnd - totalRange)
                                    newEnd = totalRange
                                }
                                if (newStart < 0f) newStart = 0f

                                windowStart = newStart
                                windowEnd = newEnd
                            }

                            val touchSlop = viewConfiguration.touchSlop
                            var lastTapDownTime = 0L

                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downTime = down.uptimeMillis
                                var pastSlop = false
                                var accumulatedPan = Offset.Zero
                                var accumulatedZoom = 1f
                                var upTime = downTime

                                while (true) {
                                    val event = awaitPointerEvent()
                                    upTime = event.changes.maxOf { it.uptimeMillis }
                                    if (event.changes.all { !it.pressed }) break
                                    // Gesture canceled if someone else consumed an up change.
                                    if (event.changes.any { it.isConsumed && !it.pressed }) break

                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()

                                    if (!pastSlop) {
                                        accumulatedPan += panChange
                                        accumulatedZoom *= zoomChange
                                        val centroidSize = event.calculateCentroidSize(useCurrent = true)
                                        val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                                        val panMotion = accumulatedPan.getDistance()
                                        if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                            pastSlop = true
                                        }
                                    }

                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (centroid.isSpecified) {
                                        scrubX = centroid.x
                                    }

                                    if (pastSlop) {
                                        applyGesture(panChange.x, zoomChange, centroid.x)
                                        event.changes.forEach { change ->
                                            if (change.positionChanged()) change.consume()
                                        }
                                    }
                                }

                                // Pointer(s) released — hide crosshair, detect double-tap.
                                scrubX = null
                                val wasTap = !pastSlop && upTime - downTime < TAP_MAX_DURATION_MS
                                if (wasTap) {
                                    if (downTime - lastTapDownTime < DOUBLE_TAP_TIMEOUT_MS) {
                                        // Double-tap: toggle between reset and zoom-in at tap point.
                                        // Read the viewport fresh — the composition-scoped
                                        // `zoomed` val captured here would be stale.
                                        val isZoomed =
                                            (windowEnd - windowStart) < totalRange * 0.999f
                                        if (isZoomed) {
                                            windowStart = 0f
                                            windowEnd = totalRange
                                        } else {
                                            applyGesture(0f, DOUBLE_TAP_ZOOM, down.position.x)
                                        }
                                        lastTapDownTime = 0L
                                    } else {
                                        lastTapDownTime = downTime
                                    }
                                }
                            }
                        }
                ) {
                    StreakLineChart(
                        model = model,
                        lineColor = lineColor,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        scrubX = scrubX,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Footer: legend, visible range, zoom controls ────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showMa) {
                    Canvas(modifier = Modifier.size(width = 16.dp, height = 2.dp)) {
                        drawLine(
                            color = MaColor.copy(alpha = 0.6f),
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 2f
                        )
                    }
                    Text(
                        text = "7-day avg",
                        color = DimColor,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(start = 4.dp, end = 12.dp)
                    )
                }

                if (zoomed) {
                    val rangeLabel = "${formatFullDate(model.startDate.plusDays(windowStart.toLong()))} → " +
                        formatFullDate(model.startDate.plusDays(windowEnd.toLong()))
                    Text(
                        text = rangeLabel,
                        color = DimColor,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "%.1f×".format(zoomFactor),
                        color = LabelColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                    Text(
                        text = "Reset",
                        color = TitleColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                windowStart = 0f
                                windowEnd = totalRange
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Pinch to zoom  •  Drag to pan  •  Double-tap to reset",
                        color = DimColor,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

// ── Chart model ───────────────────────────────────────────────────────────────

/**
 * Pre-parsed chart data: day indices relative to the first date (handles gaps
 * in the date sequence), values, a 7-point rolling average, and global min/max.
 */
private class StreakChartModel(
    val startDate: LocalDate,
    val totalDays: Int,
    val dayIdx: IntArray,
    val values: IntArray,
    val ma7: FloatArray,
    val globalMin: Int,
    val globalMax: Int
)

private fun buildStreakChartModel(data: List<Pair<String, Int>>): StreakChartModel {
    val parsed = data.mapNotNull { (dateStr, value) ->
        parseDate(dateStr)?.let { it to value }
    }
    if (parsed.isEmpty()) {
        return StreakChartModel(LocalDate.now(), 1, IntArray(0), IntArray(0), FloatArray(0), 0, 1)
    }

    val startDate = parsed.first().first
    val dayIdx = IntArray(parsed.size)
    val values = IntArray(parsed.size)
    parsed.forEachIndexed { i, (date, value) ->
        dayIdx[i] = ChronoUnit.DAYS.between(startDate, date).toInt()
        values[i] = value
    }

    val ma7 = FloatArray(parsed.size) { Float.NaN }
    if (parsed.size >= 7) {
        var sum = 0f
        for (i in values.indices) {
            sum += values[i]
            if (i >= 7) sum -= values[i - 7]
            if (i >= 6) ma7[i] = sum / 7f
        }
    }

    var minV = values[0]
    var maxV = values[0]
    for (v in values) {
        if (v < minV) minV = v
        if (v > maxV) maxV = v
    }

    return StreakChartModel(
        startDate = startDate,
        totalDays = (dayIdx.last() + 1).coerceAtLeast(1),
        dayIdx = dayIdx,
        values = values,
        ma7 = ma7,
        globalMin = minV,
        globalMax = maxV.coerceAtLeast(minV + 1)
    )
}

// ── Line chart composable ─────────────────────────────────────────────────────

@Composable
private fun StreakLineChart(
    model: StreakChartModel,
    lineColor: Color,
    windowStart: Float,
    windowEnd: Float,
    scrubX: Float?,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val chartLeft = Y_AXIS_WIDTH.toPx()
        val chartRight = size.width - RIGHT_PAD.toPx()
        val chartTop = TOP_PAD.toPx()
        val chartBottom = size.height - BOTTOM_PAD.toPx()
        val plotW = chartRight - chartLeft
        val plotH = chartBottom - chartTop
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        val winStart = windowStart
        val winEnd = windowEnd
        val winDays = (winEnd - winStart).coerceAtLeast(0.001f)

        fun xFor(day: Float): Float =
            if (winDays < 0.5f) chartLeft + plotW / 2f
            else chartLeft + (day - winStart) / winDays * plotW

        fun dayForX(x: Float): Float = winStart + (x - chartLeft) / plotW * winDays

        // ── Visible value range (auto-fit Y to the window) ──────────────────
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        var visibleCount = 0
        for (i in model.dayIdx.indices) {
            val d = model.dayIdx[i].toFloat()
            if (d >= winStart - 0.5f && d <= winEnd + 0.5f) {
                val v = model.values[i]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
                visibleCount++
            }
        }
        if (visibleCount == 0) {
            minV = model.globalMin
            maxV = model.globalMax
        }

        val rawRange = (maxV - minV).toFloat()
        val yMin: Float
        val yMax: Float
        if (rawRange < 1f) {
            val v = minV.toFloat()
            yMin = if (v <= 1f) v - 1f else v * 0.9f
            yMax = if (v <= 1f) v + 1f else v * 1.1f
        } else {
            val pad = rawRange * 0.08f
            yMin = minV - pad
            yMax = maxV + pad
        }

        fun yFor(v: Float): Float =
            chartBottom - (v - yMin) / (yMax - yMin).coerceAtLeast(0.001f) * plotH

        // ── Background subtle gradient + border ─────────────────────────────
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF0F0F20), Color(0xFF0A0A15)),
                startY = chartTop,
                endY = chartBottom
            ),
            topLeft = Offset(chartLeft, chartTop),
            size = Size(plotW, plotH)
        )
        drawRect(
            color = ChartBorderColor,
            topLeft = Offset(chartLeft, chartTop),
            size = Size(plotW, plotH),
            style = Stroke(width = 1.dp.toPx())
        )

        // ── Y axis: nice ticks, grid lines, labels ──────────────────────────
        val yTicks = niceYTicks(yMin, yMax)
        val yLabelPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.toArgb()
            textSize = 10.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        for (tick in yTicks) {
            val y = yFor(tick)
            if (y < chartTop || y > chartBottom) continue
            drawLine(
                color = GridLineColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            )
            drawContext.canvas.nativeCanvas.drawText(
                formatYLabel(tick),
                chartLeft - 6.dp.toPx(),
                y + 4.dp.toPx(),
                yLabelPaint
            )
        }

        // Zero baseline (only when inside the fitted range)
        if (yMin <= 0f && yMax >= 0f) {
            val y0 = yFor(0f)
            drawLine(
                color = Color(0xFF334455),
                start = Offset(chartLeft, y0),
                end = Offset(chartRight, y0),
                strokeWidth = 1.dp.toPx()
            )
        }

        // ── X axis: calendar-aligned ticks, grid lines, labels ──────────────
        val xTicks = buildXTicks(
            model.startDate, model.totalDays, winStart, winEnd, plotW, 56.dp.toPx()
        )
        val xLabelPaint = android.graphics.Paint().apply {
            color = AxisLabelColor.toArgb()
            textSize = 9.dp.toPx()
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        }
        for (tick in xTicks) {
            val x = xFor(tick.dayIdx)
            if (x < chartLeft - 2f || x > chartRight + 2f) continue
            drawLine(
                color = GridLineColor.copy(alpha = 0.4f),
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // ── Clip data rendering to the plot area ────────────────────────────
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.clipRect(chartLeft, chartTop, chartRight, chartBottom)

        // Build the visible segment of the line (with one point of overdraw
        // on each side so the line enters/exits through the plot edges).
        val path = Path()
        var pathStarted = false
        var prevX = Float.NaN
        var prevY = Float.NaN
        val cullMargin = 8.dp.toPx()
        for (i in model.dayIdx.indices) {
            val x = xFor(model.dayIdx[i].toFloat())
            val y = yFor(model.values[i].toFloat())
            val inRange = x >= chartLeft - cullMargin && x <= chartRight + cullMargin
            if (inRange) {
                if (!pathStarted) {
                    // Include the previous (off-screen) point so the line
                    // crosses the plot edge cleanly.
                    if (!prevY.isNaN()) {
                        path.moveTo(prevX, prevY)
                        path.lineTo(x, y)
                    } else {
                        path.moveTo(x, y)
                    }
                    pathStarted = true
                } else {
                    path.lineTo(x, y)
                }
            } else if (pathStarted && x > chartRight + cullMargin) {
                path.lineTo(x, y)
                break
            }
            prevX = x
            prevY = y
        }

        // Gradient fill under the line
        if (pathStarted) {
            val areaPath = Path().apply {
                addPath(path)
                lineTo(path.getBounds().right, chartBottom)
                lineTo(path.getBounds().left, chartBottom)
                close()
            }
            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.22f),
                        lineColor.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    startY = chartTop,
                    endY = chartBottom
                )
            )

            // Main line — crisp, round joins
            drawPath(
                path = path,
                color = lineColor.copy(alpha = 0.95f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Data point dots (only when zoomed in enough for them to breathe)
        if (visibleCount > 0 && plotW / visibleCount >= 5.dp.toPx()) {
            for (i in model.dayIdx.indices) {
                val x = xFor(model.dayIdx[i].toFloat())
                if (x < chartLeft || x > chartRight) continue
                drawCircle(
                    color = lineColor,
                    radius = 2.5.dp.toPx(),
                    center = Offset(x, yFor(model.values[i].toFloat()))
                )
            }
        }

        // 7-day moving average (dashed)
        if (model.values.size >= 7 && model.totalDays > 14) {
            val maPath = Path()
            var maStarted = false
            var maPrevX = Float.NaN
            var maPrevY = Float.NaN
            for (i in model.dayIdx.indices) {
                val ma = model.ma7[i]
                if (ma.isNaN()) continue
                val x = xFor(model.dayIdx[i].toFloat())
                val y = yFor(ma)
                val inRange = x >= chartLeft - cullMargin && x <= chartRight + cullMargin
                if (inRange) {
                    if (!maStarted) {
                        if (!maPrevY.isNaN()) {
                            maPath.moveTo(maPrevX, maPrevY)
                            maPath.lineTo(x, y)
                        } else {
                            maPath.moveTo(x, y)
                        }
                        maStarted = true
                    } else {
                        maPath.lineTo(x, y)
                    }
                } else if (maStarted && x > chartRight + cullMargin) {
                    maPath.lineTo(x, y)
                    break
                }
                maPrevX = x
                maPrevY = y
            }
            if (maStarted) {
                drawPath(
                    path = maPath,
                    color = MaColor.copy(alpha = 0.55f),
                    style = Stroke(
                        width = 1.25.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f))
                    )
                )
            }
        }

        // ── Scrub crosshair (snapped to the nearest data point) ─────────────
        if (scrubX != null && scrubX >= chartLeft - 20f && scrubX <= chartRight + 20f) {
            val targetDay = dayForX(scrubX).coerceIn(0f, (model.totalDays - 1).toFloat())
            val nearest = nearestIndex(model.dayIdx, targetDay)
            val cx = xFor(model.dayIdx[nearest].toFloat())
            val cy = yFor(model.values[nearest].toFloat())

            // Vertical crosshair line
            drawLine(
                color = CrosshairColor.copy(alpha = 0.75f),
                start = Offset(cx, chartTop),
                end = Offset(cx, chartBottom),
                strokeWidth = 1.dp.toPx()
            )
            // Highlight dot
            drawCircle(
                color = CrosshairColor.copy(alpha = 0.25f),
                radius = 6.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = CrosshairColor,
                radius = 2.5.dp.toPx(),
                center = Offset(cx, cy)
            )
        }

        // Restore canvas clip
        drawContext.canvas.nativeCanvas.restore()

        // ── X axis labels (outside clip) ────────────────────────────────────
        for (tick in xTicks) {
            val x = xFor(tick.dayIdx)
            if (x < chartLeft + 14.dp.toPx() || x > chartRight - 14.dp.toPx()) continue
            drawContext.canvas.nativeCanvas.drawText(
                tick.label,
                x,
                chartBottom + 16.dp.toPx(),
                xLabelPaint
            )
        }

        // ── Crosshair value chip (outside clip, on top) ─────────────────────
        if (scrubX != null && scrubX >= chartLeft - 20f && scrubX <= chartRight + 20f) {
            val targetDay = dayForX(scrubX).coerceIn(0f, (model.totalDays - 1).toFloat())
            val nearest = nearestIndex(model.dayIdx, targetDay)
            val cx = xFor(model.dayIdx[nearest].toFloat())
            val date = model.startDate.plusDays(model.dayIdx[nearest].toLong())
            val value = model.values[nearest]

            val chipPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 10.dp.toPx()
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                textAlign = android.graphics.Paint.Align.LEFT
            }
            val dateText = formatFullDate(date)
            val sepText = " • "
            val valueText = value.toString()
            val dateW = chipPaint.measureText(dateText)
            val sepW = chipPaint.measureText(sepText)
            val valueW = chipPaint.measureText(valueText)
            val chipW = dateW + sepW + valueW + 14.dp.toPx()
            val chipH = 20.dp.toPx()
            val chipX = (cx - chipW / 2f).coerceIn(chartLeft, chartRight - chipW)
            val chipY = chartTop + 4.dp.toPx()
            val textBaseline = chipY + chipH / 2f - (chipPaint.ascent() + chipPaint.descent()) / 2f

            drawRoundRect(
                color = ChipBgColor,
                topLeft = Offset(chipX, chipY),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(5.dp.toPx())
            )
            val canvas = drawContext.canvas.nativeCanvas
            chipPaint.color = DimColor.toArgb()
            canvas.drawText(dateText, chipX + 7.dp.toPx(), textBaseline, chipPaint)
            chipPaint.color = DimColor.copy(alpha = 0.6f).toArgb()
            canvas.drawText(sepText, chipX + 7.dp.toPx() + dateW, textBaseline, chipPaint)
            chipPaint.color = lineColor.toArgb()
            canvas.drawText(valueText, chipX + 7.dp.toPx() + dateW + sepW, textBaseline, chipPaint)
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Binary-search the index of the day index nearest to [targetDay].
 */
private fun nearestIndex(dayIdx: IntArray, targetDay: Float): Int {
    var lo = 0
    var hi = dayIdx.size - 1
    var best = 0
    var bestDist = Float.MAX_VALUE
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val d = abs(dayIdx[mid] - targetDay)
        if (d < bestDist) {
            bestDist = d
            best = mid
        }
        if (dayIdx[mid] < targetDay) lo = mid + 1 else hi = mid - 1
    }
    return best
}

/**
 * Format Y axis labels nicely (e.g., 1K, 2.5K for large numbers).
 */
private fun formatYLabel(value: Float): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 10_000 -> "%.0fK".format(value / 1_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toInt().toString()
}

/**
 * Nice-number Y ticks over [min, max]: steps from the 1/2/5 × 10^k family,
 * always ≥ 1 because the data is integer-valued.
 */
private fun niceYTicks(min: Float, max: Float): List<Float> {
    val range = (max - min).coerceAtLeast(1f)
    val rough = range / 4f
    val magnitude = 10f.pow(ceil(log10(rough.toDouble())).toFloat() - 1f)
    val normalized = rough / magnitude
    val step = (when {
        normalized < 1.5f -> 1f
        normalized < 3f -> 2f
        normalized < 7f -> 5f
        else -> 10f
    } * magnitude).coerceAtLeast(1f)

    val ticks = mutableListOf<Float>()
    var tick = ceil(min / step) * step
    val top = max + step * 0.01f
    while (tick <= top && ticks.size < 10) {
        ticks.add(tick)
        tick += step
    }
    return ticks
}

/**
 * One calendar-aligned x-axis tick: a day index plus its pre-formatted label.
 */
private data class XTick(val dayIdx: Float, val label: String)

/**
 * Build calendar-aligned x-axis ticks for the visible window.
 *
 * Day-based steps (1, 2, 7, 14 days) are aligned to multiples of the step so
 * they stay stable while panning; wider steps snap to calendar boundaries
 * (first of month / quarter / half / year) like professional time axes.
 */
private fun buildXTicks(
    startDate: LocalDate,
    totalDays: Int,
    winStart: Float,
    winEnd: Float,
    plotWidthPx: Float,
    labelSlotPx: Float
): List<XTick> {
    val windowDays = (winEnd - winStart).coerceAtLeast(1f)
    val targetTicks = (plotWidthPx / labelSlotPx).coerceIn(3f, 8f)
    val approxStepDays = windowDays / targetTicks
    val ticks = mutableListOf<XTick>()

    if (approxStepDays < 30f) {
        // Day-based steps, aligned to multiples of the step.
        val step = when {
            approxStepDays <= 1.5f -> 1
            approxStepDays <= 3f -> 2
            approxStepDays <= 10f -> 7
            else -> 14
        }
        var i = (ceil(winStart / step) * step).toInt().coerceAtLeast(0)
        val lastDay = winEnd.toInt().coerceAtMost(totalDays - 1)
        while (i <= lastDay) {
            ticks.add(XTick(i.toFloat(), formatShortDate(startDate.plusDays(i.toLong()))))
            i += step
        }
    } else {
        // Calendar-aligned month steps.
        val months = when {
            approxStepDays < 76f -> 1
            approxStepDays < 200f -> 3
            approxStepDays < 400f -> 6
            else -> 12
        }
        val startEpoch = startDate.toEpochDay()
        var d = startDate.plusDays(winStart.toLong()).withDayOfMonth(1)
        // Snap to the step boundary (Jan for 12, Jan/Jul for 6, etc.)
        while ((d.monthValue - 1) % months != 0) d = d.plusMonths(1)
        while (d.toEpochDay() - startEpoch <= winEnd) {
            val idx = (d.toEpochDay() - startEpoch).toFloat()
            if (idx >= winStart && idx <= totalDays - 1) {
                val label = if (months >= 12) d.year.toString() else formatYearDate(d)
                ticks.add(XTick(idx, label))
            }
            d = d.plusMonths(months.toLong())
        }
    }
    return ticks
}
