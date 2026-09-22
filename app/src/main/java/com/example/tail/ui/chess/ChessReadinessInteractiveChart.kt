package com.example.tail.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.ComplianceDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Interactive landscape chart engine for the ♟ chess-readiness stats
 * screen. The portrait screen deliberately keeps its small static
 * previews; tapping one opens a popup here that locks sensor-landscape
 * and provides the full-screen exploration view:
 *
 *  - pinch to zoom the time axis (up to ×60)
 *  - drag horizontally to scroll through the zoomed window
 *  - double-tap (or ⟲ Reset) to return to the full timeline
 *  - ~7 adaptive bottom date labels that re-format with the zoom level
 *    (years → "MMM yy", months → "d MMM", days → "d MMM HH:mm",
 *    intraday → "HH:mm")
 *  - tap ANY point → callout with the exact date + every series' value
 *  - optional ◆ event markers (system changes / engine switches) that
 *    open their description when tapped
 *
 * [ComplianceChartPopup] is the stacked-bar sibling for the daily
 * compliance series (tap a bar → that day's exact authorized / denied /
 * no-fresh-test counts).
 */

// ── Model ─────────────────────────────────────────────────────────────────────

/** One tappable data point; [color] overrides the series colour per point. */
data class IChartPoint(
    val t: Long,
    val value: Double,
    val color: Color? = null
)

data class IChartSeries(
    val name: String,
    val color: Color,
    val points: List<IChartPoint>
)

/** Gold ◆ event marker; [title]/[description] make it tappable. */
data class IChartMarker(
    val t: Long,
    val label: String,
    val title: String? = null,
    val description: String? = null
)

class InteractiveChartRequest(
    val title: String,
    val series: List<IChartSeries>,
    val markers: List<IChartMarker> = emptyList(),
    /** printf-style format applied to every plotted/callout value. */
    val valueFormat: String = "%.1f",
    val valueUnit: String = "",
    /** Force the y-axis to include 0 (counts, scores). */
    val yIncludeZero: Boolean = false
)

// ── Palette (matches the hourly popups) ──────────────────────────────────────

private val IBg = Color(0xFF120E08)
private val ITitle = Color(0xFFF2A65A)
private val ILabel = Color(0xFFE6C79C)
private val IDim = Color(0xFF9C8B77)
private val IGold = Color(0xFFFFC24D)
private val IGrid = Color(0xFF3A2E1E)
private val IGreen = Color(0xFF22C55E)
private val IRed = Color(0xFFEF4444)

/** Locks sensor-landscape while composed; restores the caller's lock after. */
@Composable
private fun LockLandscape() {
    val context = LocalContext.current
    val activity = context as? Activity
    DisposableEffect(Unit) {
        val previous = activity?.requestedOrientation
            ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = previous }
    }
}

// ── Shared helpers ───────────────────────────────────────────────────────────

private fun niceYStep(range: Double): Double {
    val target = maxOf(range / 4.0, 1e-9)
    val mag = 10.0.pow(floor(log10(target)))
    for (m in listOf(1.0, 2.0, 5.0, 10.0)) {
        if (m * mag >= target) return m * mag
    }
    return 10.0 * mag
}

private fun fmtXLabel(t: Long, spanMs: Long): String {
    val z = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
    return when {
        spanMs > 3L * 365 * 24 * 3600 * 1000 ->
            z.format(DateTimeFormatter.ofPattern("MMM yy"))
        spanMs > 70L * 24 * 3600 * 1000 ->
            z.format(DateTimeFormatter.ofPattern("d MMM"))
        spanMs > 3L * 24 * 3600 * 1000 ->
            z.format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
        else -> z.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

private val SEL_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm")

// ── Interactive line/point chart popup ───────────────────────────────────────

@Composable
fun InteractiveChartPopup(
    request: InteractiveChartRequest,
    onDismiss: () -> Unit
) {
    LockLandscape()
    var zoom by remember(request) { mutableStateOf(1f) }
    var panFrac by remember(request) { mutableStateOf(0f) }
    var selectedT by remember(request) { mutableStateOf<Long?>(null) }
    var openMarker by remember(request) { mutableStateOf<IChartMarker?>(null) }
    var canvasW by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#9C8B77")
        textSize = labelPx
        isAntiAlias = true
    }
    val markerPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFC24D")
        textSize = labelPx
        isAntiAlias = true
        isFakeBoldText = true
    }

    // Full time domain across every series and marker.
    val allT = request.series.flatMap { s -> s.points.map { it.t } } +
        request.markers.map { it.t }
    val tMin = allT.minOrNull() ?: 0L
    val tMax = allT.maxOrNull() ?: 1L
    val fullSpan = maxOf(1L, tMax - tMin)

    fun winStart(span: Long): Long =
        tMin + (panFrac * (fullSpan - span)).toLong().coerceIn(0L, fullSpan - span)

    fun xOf(t: Long, span: Long, chartW: Float, padL: Float): Float {
        val frac = ((t - winStart(span)).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        return padL + chartW * frac
    }

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
                .background(IBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    request.title,
                    color = ITitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "×%.1f".format(zoom),
                    color = IGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
                TextButton(onClick = {
                    zoom = 1f; panFrac = 0f; selectedT = null
                }) { Text("⟲ Reset", color = ILabel, fontSize = 12.sp) }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = ILabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // ── Tap callout: exact date + every series' value ──
            selectedT?.let { sel ->
                val z = Instant.ofEpochMilli(sel).atZone(ZoneId.systemDefault())
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        z.format(SEL_FMT),
                        color = IGold,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        request.series.forEach { s ->
                            s.points.minByOrNull { abs(it.t - sel) }?.let { p ->
                                Text(
                                    "${s.name}: ${request.valueFormat.format(p.value)}${request.valueUnit}",
                                    color = p.color ?: s.color,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // ── Chart canvas: pinch-zoom + drag + tap ──
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { canvasW = it.width.toFloat() }
                    .pointerInput(request) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            if (canvasW <= 0f) return@detectTransformGestures
                            val oldZoom = zoom
                            val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 60f)
                            // Anchor the window at its centre while zooming…
                            val centre = panFrac + (1f / oldZoom) / 2f
                            var newPan = centre - (1f / newZoom) / 2f
                            // …then apply the drag (px → fraction of full span).
                            newPan -= (pan.x / canvasW) * (1f / newZoom)
                            val maxPan = (1f - 1f / newZoom).coerceAtLeast(0f)
                            panFrac = newPan.coerceIn(0f, maxPan)
                            zoom = newZoom
                        }
                    }
                    .pointerInput(request) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoom = 1f; panFrac = 0f; selectedT = null
                            },
                            onTap = { pos ->
                                if (canvasW <= 0f) return@detectTapGestures
                                val padL = with(density) { 44.dp.toPx() }
                                val padR = with(density) { 12.dp.toPx() }
                                val chartW = (canvasW - padL - padR).coerceAtLeast(1f)
                                val span = maxOf(1L, (fullSpan / zoom).toLong())
                                val threshold = with(density) { 28.dp.toPx() }
                                // Markers first (only those with a description).
                                val m = request.markers
                                    .filter { it.description != null }
                                    .minByOrNull {
                                        abs(xOf(it.t, span, chartW, padL) - pos.x)
                                    }
                                if (m != null &&
                                    abs(xOf(m.t, span, chartW, padL) - pos.x) <= threshold
                                ) {
                                    openMarker = m
                                    return@detectTapGestures
                                }
                                // Nearest point across all series by x distance.
                                var best: IChartPoint? = null
                                var bestDx = Float.MAX_VALUE
                                request.series.forEach { s ->
                                    s.points.forEach { p ->
                                        val dx = abs(xOf(p.t, span, chartW, padL) - pos.x)
                                        if (dx < bestDx) {
                                            bestDx = dx; best = p
                                        }
                                    }
                                }
                                selectedT = if (best != null && bestDx <= threshold) {
                                    if (selectedT == best!!.t) null else best!!.t
                                } else null
                            }
                        )
                    }
            ) {
                val padL = 44.dp.toPx()
                val padR = 12.dp.toPx()
                val padT = 14.dp.toPx()
                val padB = 26.dp.toPx()
                val w = size.width
                val h = size.height
                val chartW = w - padL - padR
                val chartH = h - padT - padB
                val span = maxOf(1L, (fullSpan / zoom).toLong())
                val ws = winStart(span)
                val we = ws + span

                fun x(t: Long) = padL + chartW * ((t - ws).toFloat() / span.toFloat())

                // Y range from the VISIBLE points only (re-scales as you zoom).
                val visVals = request.series.flatMap { s ->
                    s.points.filter { it.t in ws..we }.map { it.value }
                }.ifEmpty {
                    request.series.flatMap { s -> s.points.map { it.value } }
                }
                var lo = visVals.min()
                var hi = visVals.max()
                if (request.yIncludeZero) lo = minOf(lo, 0.0)
                val yPad = maxOf((hi - lo) * 0.08, abs(hi) * 0.02, 1e-3)
                lo -= yPad; hi += yPad
                if (hi <= lo) hi = lo + 1.0
                val ySpan = hi - lo

                fun y(v: Double) = padT + chartH * (1f - ((v - lo) / ySpan).toFloat()).toFloat()

                // Horizontal gridlines + value labels
                val step = niceYStep(ySpan)
                val yFmt = if (step >= 10.0) "%.0f" else "%.1f"
                var gv = floor(lo / step) * step
                while (gv <= hi) {
                    val gy = y(gv)
                    drawLine(IGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        yFmt.format(gv), 0f, gy + labelPx / 3, labelPaint
                    )
                    gv += step
                }

                // Series (clipped to the plot rect so lines cross the window
                // edges cleanly instead of escaping the chart area).
                clipRect(left = padL, top = padT, right = w - padR, bottom = padT + chartH) {
                    request.series.forEach { s ->
                        val vis = s.points.filter { it.t in ws..we }
                        // One extra neighbour on each side for continuity.
                        val firstVis = s.points.indexOfFirst { it.t in ws..we }
                        val draw = if (firstVis >= 0) {
                            val from = (firstVis - 1).coerceAtLeast(0)
                            val lastVis = s.points.indexOfLast { it.t in ws..we }
                            val to = (lastVis + 1).coerceAtMost(s.points.lastIndex)
                            if (to > from) s.points.subList(from, to + 1) else vis
                        } else emptyList()
                        // Segments coloured by the TO point when it overrides.
                        for (i in 1 until draw.size) {
                            val a = draw[i - 1]
                            val b = draw[i]
                            drawLine(
                                color = b.color ?: s.color,
                                start = Offset(x(a.t), y(a.value)),
                                end = Offset(x(b.t), y(b.value)),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                        // Dots when density allows.
                        if (draw.size <= 200) {
                            draw.forEach { p ->
                                drawCircle(
                                    color = p.color ?: s.color,
                                    radius = if (draw.size <= 60) 3.5.dp.toPx() else 2.5.dp.toPx(),
                                    center = Offset(x(p.t), y(p.value))
                                )
                            }
                        }
                    }
                }

                // ◆ markers with guide lines
                val visMarkers = request.markers.filter { it.t in ws..we }
                val showLabels = visMarkers.size <= 5
                visMarkers.forEach { m ->
                    val mx = x(m.t)
                    val cy = padT + 10.dp.toPx()
                    val r = 5.dp.toPx()
                    val diamond = Path().apply {
                        moveTo(mx, cy - r); lineTo(mx + r, cy)
                        lineTo(mx, cy + r); lineTo(mx - r, cy); close()
                    }
                    drawPath(diamond, IGold)
                    drawLine(
                        IGold.copy(alpha = 0.45f),
                        Offset(mx, cy + r), Offset(mx, padT + chartH), strokeWidth = 1f
                    )
                    if (showLabels) {
                        val tw = markerPaint.measureText(m.label)
                        val tx = if (mx + tw + 8f > w - padR) mx - tw - 6f else mx + 6f
                        drawContext.canvas.nativeCanvas.drawText(
                            m.label, tx, padT + labelPx, markerPaint
                        )
                    }
                }

                // Selection guide: gold line + ring on every series
                selectedT?.let { sel ->
                    val sx = x(sel).coerceIn(padL, w - padR)
                    drawLine(IGold, Offset(sx, padT), Offset(sx, padT + chartH), strokeWidth = 1.5f)
                    request.series.forEach { s ->
                        s.points.minByOrNull { abs(it.t - sel) }?.let { p ->
                            drawCircle(
                                color = IGold,
                                radius = 6.dp.toPx(),
                                center = Offset(x(p.t), y(p.value)),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }

                // Bottom date labels — ~7, adaptive to the zoom span
                val labelCount = 7
                for (i in 0 until labelCount) {
                    val t = ws + span * i / (labelCount - 1)
                    val label = fmtXLabel(t, span)
                    val tw = labelPaint.measureText(label)
                    val tx = (x(t) - tw / 2).coerceIn(padL, w - padR - tw)
                    drawContext.canvas.nativeCanvas.drawText(
                        label, tx, h - 8.dp.toPx(), labelPaint
                    )
                }
            }

            // ── Footer hint ──
            Text(
                "Pinch to zoom · drag to scroll · tap a point for exact values · " +
                    "tap ◆ for system changes · double-tap to reset",
                color = IDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    openMarker?.let { m ->
        AlertDialog(
            onDismissRequest = { openMarker = null },
            confirmButton = {
                TextButton(onClick = { openMarker = null }) {
                    Text("Close", color = IGold, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text(
                    m.title ?: m.label,
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            },
            text = {
                Text(
                    Instant.ofEpochMilli(m.t).atZone(ZoneId.systemDefault())
                        .format(SEL_FMT) + "\n\n" + m.description,
                    color = ILabel, fontSize = 13.sp
                )
            },
            containerColor = Color(0xFF231A10)
        )
    }
}

// ── Compliance stacked-bar popup ─────────────────────────────────────────────

/**
 * Full-screen landscape, zoomable version of the compliance-over-time
 * stacked bars: green = games played while authorized, red = violations.
 * Tapping a bar shows that day's exact counts.
 */
@Composable
fun ComplianceChartPopup(
    days: List<ComplianceDay>,
    onDismiss: () -> Unit
) {
    LockLandscape()
    var zoom by remember(days) { mutableStateOf(1f) }
    var panFrac by remember(days) { mutableStateOf(0f) }
    var selectedDay by remember(days) { mutableStateOf<ComplianceDay?>(null) }
    var canvasW by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#9C8B77")
        textSize = labelPx
        isAntiAlias = true
    }

    val sorted = remember(days) { days.sortedBy { it.date.toEpochDay() } }
    val dMin = sorted.firstOrNull()?.date?.toEpochDay() ?: 0L
    val dMax = (sorted.lastOrNull()?.date?.toEpochDay() ?: (dMin + 1)) + 1L
    val fullDays = maxOf(1L, dMax - dMin)

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
                .background(IBg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚖️ Compliance — games per day",
                    color = ITitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "×%.1f".format(zoom),
                    color = IGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
                TextButton(onClick = {
                    zoom = 1f; panFrac = 0f; selectedDay = null
                }) { Text("⟲ Reset", color = ILabel, fontSize = 12.sp) }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = ILabel,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            selectedDay?.let { d ->
                Text(
                    d.date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")) +
                        " — ${d.authorized} authorized · ${d.violationDenied} denied · " +
                        "${d.violationNoTest} no fresh test (${d.total} games)",
                    color = IGold,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { canvasW = it.width.toFloat() }
                    .pointerInput(sorted) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            if (canvasW <= 0f) return@detectTransformGestures
                            val oldZoom = zoom
                            val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 40f)
                            val centre = panFrac + (1f / oldZoom) / 2f
                            var newPan = centre - (1f / newZoom) / 2f
                            newPan -= (pan.x / canvasW) * (1f / newZoom)
                            val maxPan = (1f - 1f / newZoom).coerceAtLeast(0f)
                            panFrac = newPan.coerceIn(0f, maxPan)
                            zoom = newZoom
                        }
                    }
                    .pointerInput(sorted) {
                        detectTapGestures(
                            onDoubleTap = {
                                zoom = 1f; panFrac = 0f; selectedDay = null
                            },
                            onTap = { pos ->
                                if (canvasW <= 0f || sorted.isEmpty()) return@detectTapGestures
                                val padL = with(density) { 30.dp.toPx() }
                                val padR = with(density) { 8.dp.toPx() }
                                val chartW = (canvasW - padL - padR).coerceAtLeast(1f)
                                val spanD = maxOf(1L, (fullDays / zoom).toLong())
                                val wsD =
                                    dMin + (panFrac * (fullDays - spanD)).toLong()
                                        .coerceIn(0L, fullDays - spanD)
                                val threshold = with(density) { 28.dp.toPx() }
                                val vis = sorted.filter {
                                    it.date.toEpochDay() in wsD..(wsD + spanD)
                                }
                                val best = vis.minByOrNull { d ->
                                    val frac = (d.date.toEpochDay() - wsD).toFloat() / spanD
                                    abs(padL + chartW * frac - pos.x)
                                }
                                if (best != null &&
                                    abs(
                                        padL + chartW *
                                            (best.date.toEpochDay() - wsD).toFloat() / spanD -
                                            pos.x
                                    ) <= threshold
                                ) {
                                    selectedDay = if (selectedDay == best) null else best
                                } else selectedDay = null
                            }
                        )
                    }
            ) {
                if (sorted.isEmpty()) return@Canvas
                val padL = 30.dp.toPx()
                val padR = 8.dp.toPx()
                val padT = 10.dp.toPx()
                val padB = 26.dp.toPx()
                val w = size.width
                val h = size.height
                val chartW = w - padL - padR
                val chartH = h - padT - padB
                val bottom = padT + chartH
                val spanD = maxOf(1L, (fullDays / zoom).toLong())
                val wsD = dMin + (panFrac * (fullDays - spanD)).toLong()
                    .coerceIn(0L, fullDays - spanD)
                val weD = wsD + spanD

                fun x(epochDay: Long) =
                    padL + chartW * ((epochDay - wsD).toFloat() / spanD.toFloat())

                val vis = sorted.filter { it.date.toEpochDay() in wsD..weD }
                val maxTotal = maxOf(
                    1,
                    vis.maxOfOrNull { it.total } ?: sorted.maxOf { it.total }
                )

                // Gridlines + y labels
                val step = when {
                    maxTotal <= 5 -> 1
                    maxTotal <= 10 -> 2
                    maxTotal <= 25 -> 5
                    else -> 10
                }
                var v = 0
                while (v <= maxTotal) {
                    val gy = bottom - chartH * v / maxTotal
                    drawLine(IGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
                    drawContext.canvas.nativeCanvas.drawText(
                        v.toString(), 0f, gy + labelPx / 3, labelPaint
                    )
                    v += step
                }

                // Stacked bars
                val barW = (chartW / maxOf(1, vis.size) * 0.72f)
                    .coerceAtMost(18.dp.toPx())
                vis.forEach { d ->
                    val cx = x(d.date.toEpochDay())
                    val totalH = chartH * d.total / maxTotal
                    val authH = chartH * d.authorized / maxTotal
                    if (d.authorized > 0) {
                        drawRect(
                            IGreen,
                            topLeft = Offset(cx - barW / 2, bottom - authH),
                            size = androidx.compose.ui.geometry.Size(barW, authH)
                        )
                    }
                    val viol = d.violationDenied + d.violationNoTest
                    if (viol > 0) {
                        drawRect(
                            IRed,
                            topLeft = Offset(cx - barW / 2, bottom - totalH),
                            size = androidx.compose.ui.geometry.Size(barW, totalH - authH)
                        )
                    }
                    if (selectedDay == d) {
                        drawRect(
                            Color.White,
                            topLeft = Offset(cx - barW / 2 - 2f, bottom - totalH - 2f),
                            size = androidx.compose.ui.geometry.Size(barW + 4f, totalH + 4f),
                            style = Stroke(width = 1.5f)
                        )
                    }
                }

                // Bottom date labels — 7, adaptive
                val labelCount = 7
                for (i in 0 until labelCount) {
                    val d = wsD + spanD * i / (labelCount - 1)
                    val label = java.time.LocalDate.ofEpochDay(d)
                        .format(
                            if (spanD > 500) DateTimeFormatter.ofPattern("MMM yy")
                            else DateTimeFormatter.ofPattern("d MMM")
                        )
                    val tw = labelPaint.measureText(label)
                    val tx = (x(d) - tw / 2).coerceIn(padL, w - padR - tw)
                    drawContext.canvas.nativeCanvas.drawText(
                        label, tx, h - 8.dp.toPx(), labelPaint
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Spacer(modifier = Modifier.width(0.dp))
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(IGreen, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                )
                Text("Authorized", color = IDim, fontSize = 10.sp)
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(IRed, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                )
                Text("Violation (denied / no fresh test)", color = IDim, fontSize = 10.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "pinch · drag · tap a bar · double-tap resets",
                    color = IDim, fontSize = 10.sp
                )
            }
        }
    }
}
