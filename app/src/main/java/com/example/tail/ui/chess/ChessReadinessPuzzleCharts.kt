package com.example.tail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.PuzzleTimePoint
import com.example.tail.data.RushScorePoint
import com.example.tail.data.RushSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness — simple over-time charts for the in-test puzzle
 *  telemetry (rated-puzzle solve times & Puzzle Rush scores)
 * ════════════════════════════════════════════════════════════════════════
 *
 *  Design follows the page's cleanest charts (compliance bars, rating
 *  history): points are laid out at EVENLY SPACED index slots — one slot
 *  per test — so tests can never bunch together no matter how many were
 *  taken in a day. Each chart draws a single orange line with dots, a
 *  handful of gridlines, and a date label under every few points. All the
 *  detail (individual puzzle times, strikes, record status) lives in the
 *  tap-to-open dialog instead of on the canvas. The hosting screen splits
 *  long series into several charts of ≤ [MAX_POINTS_PER_CHART] points.
 */

/** Max points per chart before the hosting screen splits the series. */
const val MAX_POINTS_PER_CHART: Int = 40

// ── Palette (private copies of the screen's warm orange theme) ───────────────
private val ChartOrange = Color(0xFFF2994A)
private val ChartGold = Color(0xFFFFC24D)
private val ChartRed = Color(0xFFEF4444)
private val ChartGreen = Color(0xFF22C55E)
private val ChartYellow = Color(0xFFEAB308)
private val ChartDim = Color(0xFF9C8B77)
private val ChartGrid = Color(0xFF3A2E1E)
private val ChartLabel = Color(0xFFE6C79C)
private val ChartWhite = Color.White
private val ChartSectionBg = Color(0xFF231A10)

private val DIALOG_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM")

private fun fmtDateTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DIALOG_FMT)

private fun fmtShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(SHORT_FMT)

private fun stateColor(state: String): Color = when (state) {
    "GREEN_LIGHT" -> ChartGreen
    "YELLOW_LIGHT" -> ChartYellow
    "RED_LIGHT" -> ChartRed
    else -> ChartDim
}

private fun stateName(state: String): String = when (state) {
    "GREEN_LIGHT" -> "GREEN"
    "YELLOW_LIGHT" -> "YELLOW"
    "RED_LIGHT" -> "RED"
    else -> "—"
}

/** Nice y-axis step for a seconds range (puzzle solve times). */
private fun niceSecStep(range: Int): Int {
    val target = maxOf(5, range / 4)
    return listOf(5, 10, 15, 30, 60, 120, 300, 600).firstOrNull { it >= target } ?: 600
}

// ── Rated puzzle solve times over time ───────────────────────────────────────

/**
 * Simple line chart of each test's average rated-puzzle solve time, one
 * evenly-spaced slot per test (compliance-chart layout, so points never
 * bunch). Tapping near a test selects it (gold guide line) and opens a
 * dialog listing every individual puzzle time for that test.
 */
@Composable
fun PuzzleTimesChart(
    points: List<PuzzleTimePoint>,
    chartHeight: Dp = 150.dp,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var canvasWidth by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    val padL = with(density) { 38.dp.toPx() }
    val padR = with(density) { 10.dp.toPx() }
    val n = points.size

    // Evenly-spaced index slots — the shared x-mapping for drawing + taps.
    fun xAt(i: Int, w: Float): Float {
        val chartW = (w - padL - padR).coerceAtLeast(1f)
        return if (n < 2) padL + chartW / 2f else padL + chartW * i / (n - 1)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(points, canvasWidth) {
                detectTapGestures { pos ->
                    if (canvasWidth <= 0f || n == 0) return@detectTapGestures
                    val threshold = with(density) { 24.dp.toPx() }
                    if (n == 1) {
                        if (abs(pos.x - xAt(0, canvasWidth)) <= threshold) {
                            selected = if (selected == 0) null else 0
                        }
                        return@detectTapGestures
                    }
                    val span = (canvasWidth - padL - padR).coerceAtLeast(1f)
                    val nearest = (
                        (pos.x - padL) / span * (n - 1)
                        ).roundToInt().coerceIn(0, n - 1)
                    if (abs(xAt(nearest, canvasWidth) - pos.x) <= threshold) {
                        selected = if (selected == nearest) null else nearest
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartH = h - padT - padB

        // Scale on the per-test averages (the only drawn series).
        val aMin = points.minOf { it.avgSec }
        val aMax = points.maxOf { it.avgSec }
        val vPad = maxOf(2.0, (aMax - aMin) * 0.15)
        val lo = (aMin - vPad).coerceAtLeast(0.0)
        val hi = aMax + vPad
        val vSpan = maxOf(1.0, hi - lo)

        fun y(v: Double) = padT + chartH * (1f - ((v - lo) / vSpan).toFloat())

        // Gridlines + y labels (seconds)
        val step = niceSecStep((hi - lo).toInt())
        var v = ceil(lo / step) * step
        while (v <= hi) {
            val gy = y(v)
            drawLine(ChartGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText("${v.toInt()}s", 0f, gy + labelPx / 3, labelPaint)
            v += step
        }

        // Average line + dots
        val path = Path()
        points.forEachIndexed { i, p ->
            val c = Offset(xAt(i, w), y(p.avgSec))
            if (i == 0) path.moveTo(c.x, c.y) else path.lineTo(c.x, c.y)
        }
        drawPath(path, ChartOrange, style = Stroke(width = 2.dp.toPx()))
        points.forEachIndexed { i, p ->
            drawCircle(ChartOrange, radius = 3.dp.toPx(), center = Offset(xAt(i, w), y(p.avgSec)))
        }

        // Selection guide + highlighted point
        selected?.let { idx ->
            points.getOrNull(idx)?.let { p ->
                val sx = xAt(idx, w)
                drawLine(ChartGold, Offset(sx, padT), Offset(sx, padT + chartH), strokeWidth = 1.5f)
                drawCircle(ChartGold, radius = 5.dp.toPx(), center = Offset(sx, y(p.avgSec)))
                drawCircle(ChartWhite, radius = 2.dp.toPx(), center = Offset(sx, y(p.avgSec)))
            }
        }

        // Date label under every few points (no final label — it
        // overlaps the previous one when the last slot is close)
        val labelInterval = ((n - 1) / 4).coerceAtLeast(1)
        points.forEachIndexed { i, p ->
            if (i % labelInterval == 0) {
                val label = fmtShort(p.timestampMs)
                val tw = labelPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label, xAt(i, w) - tw / 2, h - 6.dp.toPx(), labelPaint
                )
            }
        }
    }

    selected?.let { idx ->
        points.getOrNull(idx)?.let { p ->
            PuzzleDetailDialog(p, onDismiss = { selected = null })
        }
    }
}

@Composable
private fun PuzzleDetailDialog(p: PuzzleTimePoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ChartOrange, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                "♟ Rated puzzles",
                color = ChartWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        },
        text = {
            Column {
                Text(fmtDateTime(p.timestampMs), color = ChartLabel, fontSize = 13.sp)
                Text(
                    "CCRS ${p.ccrs} · ${stateName(p.state)}",
                    color = stateColor(p.state),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                p.timesSec.forEachIndexed { i, t ->
                    Text(
                        "Puzzle ${i + 1} — $t s",
                        color = ChartLabel,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Avg %.1f s · best ${p.bestSec} s · worst ${p.worstSec} s"
                        .format(p.avgSec),
                    color = ChartGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        containerColor = ChartSectionBg
    )
}

// ── Puzzle Rush over time ────────────────────────────────────────────────────

/**
 * Simple line chart of each test's Puzzle Rush score, one evenly-spaced
 * slot per test, with a dashed gold line at the all-time record. Tapping
 * near a test selects it and opens a dialog with the score, strikes and
 * record status for that run.
 */
@Composable
fun RushScoreChart(
    points: List<RushScorePoint>,
    chartHeight: Dp = 150.dp,
    modifier: Modifier = Modifier
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var canvasWidth by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    val goldPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFC24D")
        textSize = labelPx
        isAntiAlias = true
        isFakeBoldText = true
    }
    val padL = with(density) { 38.dp.toPx() }
    val padR = with(density) { 10.dp.toPx() }
    val n = points.size

    fun xAt(i: Int, w: Float): Float {
        val chartW = (w - padL - padR).coerceAtLeast(1f)
        return if (n < 2) padL + chartW / 2f else padL + chartW * i / (n - 1)
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(points, canvasWidth) {
                detectTapGestures { pos ->
                    if (canvasWidth <= 0f || n == 0) return@detectTapGestures
                    val threshold = with(density) { 24.dp.toPx() }
                    if (n == 1) {
                        if (abs(pos.x - xAt(0, canvasWidth)) <= threshold) {
                            selected = if (selected == 0) null else 0
                        }
                        return@detectTapGestures
                    }
                    val span = (canvasWidth - padL - padR).coerceAtLeast(1f)
                    val nearest = (
                        (pos.x - padL) / span * (n - 1)
                        ).roundToInt().coerceIn(0, n - 1)
                    if (abs(xAt(nearest, canvasWidth) - pos.x) <= threshold) {
                        selected = if (selected == nearest) null else nearest
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas
        val padT = 14.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartH = h - padT - padB

        val record = points.maxOf { maxOf(it.score, it.allTimeHigh) }
        val hi = (record + 2).toFloat()
        val lo = 0f
        val vSpan = maxOf(1f, hi - lo)

        fun y(v: Int) = padT + chartH * (1f - (v - lo) / vSpan)

        // Gridlines + y labels (score)
        val step = when {
            hi <= 12 -> 2
            hi <= 30 -> 5
            hi <= 60 -> 10
            else -> 20
        }
        var v = 0
        while (v <= hi) {
            val gy = y(v)
            drawLine(ChartGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.toString(), 0f, gy + labelPx / 3, labelPaint)
            v += step
        }

        // Dashed all-time-record line + small label
        val recY = y(record)
        val dash = 6.dp.toPx()
        val gap = 4.dp.toPx()
        var xx = padL
        while (xx < w - padR) {
            drawLine(
                ChartGold.copy(alpha = 0.55f),
                Offset(xx, recY),
                Offset(minOf(xx + dash, w - padR), recY),
                strokeWidth = 1.5f
            )
            xx += dash + gap
        }
        val recTxt = "record $record"
        drawContext.canvas.nativeCanvas.drawText(
            recTxt, w - padR - goldPaint.measureText(recTxt) - 2f, recY - 4.dp.toPx(), goldPaint
        )

        // Score line + dots
        val path = Path()
        points.forEachIndexed { i, p ->
            val c = Offset(xAt(i, w), y(p.score))
            if (i == 0) path.moveTo(c.x, c.y) else path.lineTo(c.x, c.y)
        }
        drawPath(path, ChartOrange, style = Stroke(width = 2.dp.toPx()))
        points.forEachIndexed { i, p ->
            drawCircle(ChartOrange, radius = 3.dp.toPx(), center = Offset(xAt(i, w), y(p.score)))
        }

        // Selection guide + highlighted point
        selected?.let { idx ->
            points.getOrNull(idx)?.let { p ->
                val sx = xAt(idx, w)
                drawLine(ChartGold, Offset(sx, padT), Offset(sx, padT + chartH), strokeWidth = 1.5f)
                drawCircle(ChartGold, radius = 5.dp.toPx(), center = Offset(sx, y(p.score)))
                drawCircle(ChartWhite, radius = 2.dp.toPx(), center = Offset(sx, y(p.score)))
            }
        }

        // Date label under every few points (no final label — it
        // overlaps the previous one when the last slot is close)
        val labelInterval = ((n - 1) / 4).coerceAtLeast(1)
        points.forEachIndexed { i, p ->
            if (i % labelInterval == 0) {
                val label = fmtShort(p.timestampMs)
                val tw = labelPaint.measureText(label)
                drawContext.canvas.nativeCanvas.drawText(
                    label, xAt(i, w) - tw / 2, h - 6.dp.toPx(), labelPaint
                )
            }
        }
    }

    selected?.let { idx ->
        points.getOrNull(idx)?.let { p ->
            RushDetailDialog(p, onDismiss = { selected = null })
        }
    }
}

@Composable
private fun RushDetailDialog(p: RushScorePoint, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = ChartOrange, fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                "⚡ Puzzle Rush",
                color = ChartWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        },
        text = {
            Column {
                Text(fmtDateTime(p.timestampMs), color = ChartLabel, fontSize = 13.sp)
                if (p.source == RushSource.TIMER) {
                    // Standalone timer run — no readiness context exists.
                    Text(
                        "Puzzle Rush timer session",
                        color = ChartOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    p.durationSec?.let { sec ->
                        Text(
                            "Session length ${sec / 60} m ${sec % 60} s",
                            color = ChartLabel,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    Text(
                        "CCRS ${p.ccrs} · ${stateName(p.state)}",
                        color = stateColor(p.state),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Score ${p.score} in 3 min",
                    color = ChartGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (p.strikes == 1) "1 strike" else "${p.strikes} strikes",
                    color = if (p.strikes >= 2) ChartRed else ChartLabel,
                    fontSize = 13.sp
                )
                p.reviewedWrong?.let { reviewed ->
                    Text(
                        if (reviewed) "✓ Reviewed the wrong puzzles"
                        else "✗ Wrong puzzles not reviewed",
                        color = if (reviewed) ChartGreen else ChartRed,
                        fontSize = 13.sp
                    )
                }
                Text(
                    if (p.isNewHigh) "★ Matched or raised the all-time high (${p.allTimeHigh})"
                    else "All-time high at test time: ${p.allTimeHigh}",
                    color = if (p.isNewHigh) ChartGold else ChartDim,
                    fontSize = 13.sp
                )
            }
        },
        containerColor = ChartSectionBg
    )
}
