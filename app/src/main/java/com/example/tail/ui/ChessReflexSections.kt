package com.example.tail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.ReflexRunPoint
import com.example.tail.data.ReflexStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Reflex Tests (PVT-B) — cross-version section of the Chess Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * One section for EVERY reflex run ever recorded, no matter which readiness
 * engine version produced it (v2's 3-minute PVT-B, v3's 2-minute PVT-B, and
 * whatever future versions run). Long-term trends that survive engine
 * switches: speed over time, hour-of-day rhythm, and how reflex speed
 * correlates with the rated games played in the following hours.
 */

// ── Palette (private copies of the screen's warm orange theme) ───────────────
private val SectionTitleColor = Color(0xFFF2A65A)
private val LabelColor = Color(0xFFE6C79C)
private val ValueColor = Color.White
private val DimColor = Color(0xFF9C8B77)
private val SectionBg = Color(0xFF231A10)
private val DividerColor = Color(0xFF3A2E1E)
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val YellowValue = Color(0xFFEAB308)
private val GoldValue = Color(0xFFFFC24D)
private val ChartOrange = Color(0xFFF2994A)
private val ChartGrid = Color(0xFF3A2E1E)

/** Dot color per engine version — stable as new versions appear. */
private fun versionColor(v: String): Color = when (v) {
    "v2" -> ChartOrange
    "v3" -> Color(0xFF66CCFF)
    else -> DimColor
}

private val EVENT_FMT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun fmtTime(ts: Long?): String =
    ts?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(EVENT_FMT)
    } ?: "—"

private fun fmtShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))

// ── Shared building blocks ───────────────────────────────────────────────────

@Composable
private fun ReflexSection(
    title: String,
    startExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by rememberSectionExpansion("chess", title, startExpanded)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(SectionBg, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = SectionTitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                if (expanded) "▼" else "▶",
                color = SectionTitleColor,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor, thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))
        if (expanded) content()
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: Color = ValueColor
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = LabelColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = DimColor, fontSize = 10.sp)
    }
}

// ── The section ──────────────────────────────────────────────────────────────

/**
 * The cross-version reflex section. [stats] comes from
 * [com.example.tail.data.computeReflexStats]; [series] is the normalized
 * per-run series powering the charts.
 */
@Composable
fun ReflexSection(
    stats: ReflexStats,
    series: List<ReflexRunPoint>,
    chartHeight: Dp = 150.dp,
    startExpanded: Boolean = true,
    /** Opens the interactive landscape mean-RT chart. */
    onOpenRtChart: () -> Unit = {},
    /** Opens the interactive landscape lapses/false-starts chart. */
    onOpenLapseChart: () -> Unit = {}
) {
    if (stats.totalRuns == 0) return
    val s = stats

    ReflexSection(
        title = "⚡ Reflex Tests (PVT-B) — All Versions",
        startExpanded = startExpanded
    ) {
        Text(
            "Every reflex run ever recorded, regardless of readiness-engine " +
                "version — the one metric that stays comparable as the " +
                "pre-game system evolves. Lower mean RT and fewer lapses = a " +
                "faster, steadier nervous system.",
            color = DimColor, fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Overview ──
        StatRow(
            "Runs completed",
            "${s.totalRuns}" + s.runsByVersion.entries.joinToString("") { " · ${it.key}: ${it.value}" }
        )
        s.avgMeanRtMs?.let { StatRow("Average response time", "%.0f ms".format(it), valueColor = GoldValue) }
        s.bestMeanRtMs?.let { StatRow("Best run (fastest avg)", "%.0f ms".format(it), valueColor = GreenValue) }
        s.avgResponseSpeed?.let { StatRow("Avg response speed (1000/RT)", "%.2f".format(it)) }
        s.worstMaxRtMs?.let { StatRow("Slowest single response (v2 runs)", "$it ms", valueColor = RedValue) }
        StatRow(
            "Late taps (lapses)",
            "${s.totalLapses} total · %.1f/run".format(s.avgLapses),
            valueColor = if (s.avgLapses > 2) RedValue else ValueColor
        )
        StatRow(
            "Early taps (false starts)",
            "${s.totalFalseStarts} total · %.1f/run".format(s.avgFalseStarts),
            valueColor = if (s.avgFalseStarts > 1.5) RedValue else ValueColor
        )
        s.rtTrendMs?.let {
            StatRow(
                "Response-time trend (first 3 → last 3)",
                "%+.0f ms".format(it),
                valueColor = if (it <= 0) GreenValue else RedValue
            )
        }
        s.lapseTrend?.let {
            StatRow(
                "Late-tap trend",
                "%+.1f".format(it),
                valueColor = if (it <= 0) GreenValue else RedValue
            )
        }
        s.falseStartTrend?.let {
            StatRow(
                "Early-tap trend",
                "%+.1f".format(it),
                valueColor = if (it <= 0) GreenValue else RedValue
            )
        }
        StatRow("First run", fmtTime(s.firstRunAt))
        StatRow("Last run", fmtTime(s.lastRunAt))

        // ── Mean RT over time ──
        val rtPoints = series.filter { it.meanRtMs != null }
        if (rtPoints.size >= 2) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Average response time per run — dot color = engine version " +
                    "(tap a point for full detail):",
                color = DimColor, fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            ReflexRtChart(rtPoints, chartHeight)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendSwatch(ChartOrange, "v2 (3 min)")
                LegendSwatch(Color(0xFF66CCFF), "v3 (2 min)")
            }
            ChartLinkRow(
                "Zoomable response-time chart — pinch, scroll, tap any run",
                "Interactive 📈"
            ) { onOpenRtChart() }
        }

        // ── Lapses & false starts over time ──
        if (series.size >= 2) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Late taps (red) and early taps (yellow) per run:",
                color = DimColor, fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            ReflexLapseChart(series, chartHeight)
            Spacer(modifier = Modifier.height(4.dp))
            ChartLinkRow(
                "Zoomable late/early-tap chart — pinch, scroll, tap any run",
                "Interactive 📈"
            ) { onOpenLapseChart() }
        }

        // ── Hour of day ──
        if (s.hourly.any { it.runCount > 0 }) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("🕐 Reflex speed by hour of day", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            if (s.fastestHour != null && s.slowestHour != null) {
                StatRow(
                    "Fastest hour",
                    "%02d:00 — %s".format(
                        s.fastestHour,
                        s.hourly[s.fastestHour].avgMeanRtMs?.let { "%.0f ms".format(it) } ?: "—"
                    ),
                    valueColor = GreenValue
                )
                StatRow(
                    "Slowest hour",
                    "%02d:00 — %s".format(
                        s.slowestHour,
                        s.hourly[s.slowestHour].avgMeanRtMs?.let { "%.0f ms".format(it) } ?: "—"
                    ),
                    valueColor = RedValue
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            ReflexHourlyChart(s, chartHeight)
        }

        // ── Reflex → following rated session ──
        if (s.followingSessions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "♟ Reflex vs the following rated session",
                color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Rated games played within 6 hours after a reflex run, " +
                    "matched back to that run's speed:",
                color = DimColor, fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            StatRow("Runs with a following rated session", s.followingSessions.size.toString())
            s.rtWinRateCorrelation?.let {
                StatRow(
                    "Correlation: RT ↔ win rate",
                    "r = %+.2f".format(it),
                    valueColor = if (it < 0) GreenValue else if (it > 0.3) RedValue else ValueColor
                )
            }
            s.rtEloDeltaCorrelation?.let {
                StatRow(
                    "Correlation: RT ↔ Elo delta",
                    "r = %+.2f".format(it),
                    valueColor = if (it < 0) GreenValue else if (it > 0.3) RedValue else ValueColor
                )
            }
            val fast = s.fastHalfFollowing
            val slow = s.slowHalfFollowing
            if (fast != null && slow != null) {
                StatRow(
                    "After FASTEST half of runs",
                    "%.0f%% win rate%s".format(
                        fast.avgWinRate,
                        fast.avgEloDelta?.let { " · %+.0f Elo".format(it) } ?: ""
                    ),
                    valueColor = GreenValue
                )
                StatRow(
                    "After SLOWEST half of runs",
                    "%.0f%% win rate%s".format(
                        slow.avgWinRate,
                        slow.avgEloDelta?.let { " · %+.0f Elo".format(it) } ?: ""
                    ),
                    valueColor = RedValue
                )
            }
        }

        // ── Recent runs list ──
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Recent runs", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        series.takeLast(10).asReversed().forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    fmtTime(p.timestampMs),
                    color = LabelColor, fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    buildString {
                        append(p.version)
                        p.meanRtMs?.let { append(" · %.0f ms".format(it)) }
                        append(" · ${p.lapses} late · ${p.falseStarts} early")
                    },
                    color = versionColor(p.version),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ── Charts ───────────────────────────────────────────────────────────────────

/**
 * Mean response-time line chart, one evenly-spaced slot per run; each dot
 * is colored by the engine version that produced it. Tapping a point opens
 * a dialog with the run's full detail.
 */
@Composable
private fun ReflexRtChart(
    points: List<ReflexRunPoint>,
    chartHeight: Dp = 150.dp
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
    val padL = with(density) { 40.dp.toPx() }
    val padR = with(density) { 10.dp.toPx() }
    val n = points.size

    fun xAt(i: Int, w: Float): Float {
        val chartW = (w - padL - padR).coerceAtLeast(1f)
        return if (n < 2) padL + chartW / 2f else padL + chartW * i / (n - 1)
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(points, canvasWidth) {
                detectTapGestures { pos ->
                    if (canvasWidth <= 0f || n == 0) return@detectTapGestures
                    val threshold = with(density) { 24.dp.toPx() }
                    val nearest = ((pos.x - padL) / (canvasWidth - padL - padR).coerceAtLeast(1f)
                        * (n - 1)).roundToInt().coerceIn(0, n - 1)
                    if (abs(xAt(nearest, canvasWidth) - pos.x) <= threshold) {
                        selected = if (selected == nearest) null else nearest
                    }
                }
            }
    ) {
        if (n == 0) return@Canvas
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartH = h - padT - padB

        val values = points.mapNotNull { it.meanRtMs }
        val vMin = values.min()
        val vMax = values.max()
        val vPad = maxOf(10.0, (vMax - vMin) * 0.15)
        val lo = (vMin - vPad).coerceAtLeast(0.0)
        val hi = vMax + vPad
        val vSpan = maxOf(1.0, hi - lo)

        fun y(v: Double) = padT + chartH * (1f - ((v - lo) / vSpan).toFloat())

        // Gridlines + y labels (ms)
        val step = maxOf(10.0, (hi - lo) / 4).let { target ->
            listOf(10, 25, 50, 100, 200, 500).firstOrNull { it >= target } ?: 500
        }
        var v = ceil(lo / step) * step
        while (v <= hi) {
            val gy = y(v)
            drawLine(ChartGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                "${v.toInt()}", 0f, gy + labelPx / 3, labelPaint
            )
            v += step
        }

        // Line + version-colored dots
        val path = Path()
        points.forEachIndexed { i, p ->
            val rt = p.meanRtMs ?: return@forEachIndexed
            val c = Offset(xAt(i, w), y(rt))
            if (i == 0 || points[i - 1].meanRtMs == null) path.moveTo(c.x, c.y)
            else path.lineTo(c.x, c.y)
        }
        drawPath(path, ChartOrange, style = Stroke(width = 2.dp.toPx()))
        points.forEachIndexed { i, p ->
            val rt = p.meanRtMs ?: return@forEachIndexed
            drawCircle(versionColor(p.version), radius = 3.dp.toPx(), center = Offset(xAt(i, w), y(rt)))
        }

        // Selection guide
        selected?.let { idx ->
            points.getOrNull(idx)?.let { p ->
                val rt = p.meanRtMs ?: return@let
                val sx = xAt(idx, w)
                drawLine(GoldValue, Offset(sx, padT), Offset(sx, padT + chartH), strokeWidth = 1.5f)
                drawCircle(GoldValue, radius = 5.dp.toPx(), center = Offset(sx, y(rt)))
            }
        }

        // X-axis endpoint labels
        drawContext.canvas.nativeCanvas.drawText(
            fmtShort(points.first().timestampMs), padL, h - 6.dp.toPx(), labelPaint
        )
        val lastLabel = fmtShort(points.last().timestampMs)
        drawContext.canvas.nativeCanvas.drawText(
            lastLabel, w - padR - labelPaint.measureText(lastLabel), h - 6.dp.toPx(), labelPaint
        )
    }

    selected?.let { idx ->
        points.getOrNull(idx)?.let { p ->
            AlertDialog(
                onDismissRequest = { selected = null },
                confirmButton = {
                    TextButton(onClick = { selected = null }) {
                        Text("Close", color = GoldValue, fontWeight = FontWeight.Bold)
                    }
                },
                title = {
                    Text(
                        "Reflex run (${p.version}) · ${fmtTime(p.timestampMs)}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                },
                text = {
                    Column {
                        Text("Engine version: ${p.version} (${p.durationMin}-min PVT-B)", color = LabelColor, fontSize = 13.sp)
                        p.meanRtMs?.let { Text("Mean response time: %.0f ms".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.meanRrt?.let { Text("Response speed (1000/RT): %.2f".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.maxRtMs?.let { Text("Slowest response: $it ms", color = LabelColor, fontSize = 13.sp) }
                        p.validResponses?.let { Text("Valid responses: $it", color = LabelColor, fontSize = 13.sp) }
                        Text("Late taps (lapses): ${p.lapses}", color = LabelColor, fontSize = 13.sp)
                        Text("Early taps (false starts): ${p.falseStarts}", color = LabelColor, fontSize = 13.sp)
                    }
                },
                containerColor = SectionBg
            )
        }
    }
}

/**
 * Dual-series chart of late taps (lapses, red) and early taps (false
 * starts, yellow) per run — dots connected by faint lines, shared count
 * scale starting at zero.
 */
@Composable
private fun ReflexLapseChart(
    points: List<ReflexRunPoint>,
    chartHeight: Dp = 150.dp
) {
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
        if (points.isEmpty()) return@Canvas
        val padL = 40.dp.toPx()
        val padR = 10.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 20.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val n = points.size
        val maxCount = maxOf(1, points.maxOf { maxOf(it.lapses, it.falseStarts) })

        fun xAt(i: Int) = if (n < 2) padL + chartW / 2f else padL + chartW * i / (n - 1)
        fun y(c: Int) = padT + chartH * (1f - c.toFloat() / maxCount)

        // Gridlines at integer steps
        val step = when {
            maxCount <= 4 -> 1
            maxCount <= 10 -> 2
            else -> 5
        }
        var v = 0
        while (v <= maxCount) {
            val gy = y(v)
            drawLine(ChartGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(v.toString(), 0f, gy + labelPx / 3, labelPaint)
            v += step
        }

        fun series(color: Color, value: (ReflexRunPoint) -> Int) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val c = Offset(xAt(i), y(value(p)))
                if (i == 0) path.moveTo(c.x, c.y) else path.lineTo(c.x, c.y)
            }
            drawPath(path, color.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx()))
            points.forEachIndexed { i, p ->
                drawCircle(color, radius = 3.dp.toPx(), center = Offset(xAt(i), y(value(p))))
            }
        }
        series(Color(0xFFEF4444)) { it.lapses }
        series(YellowValue) { it.falseStarts }

        drawContext.canvas.nativeCanvas.drawText(
            fmtShort(points.first().timestampMs), padL, h - 6.dp.toPx(), labelPaint
        )
        val lastLabel = fmtShort(points.last().timestampMs)
        drawContext.canvas.nativeCanvas.drawText(
            lastLabel, w - padR - labelPaint.measureText(lastLabel), h - 6.dp.toPx(), labelPaint
        )
    }
}

/**
 * 24-bar hour-of-day chart of average mean-RT — one bar per hour (0–23),
 * taller = slower. Hours with no runs render no bar. The fastest hour's
 * bar is green, the slowest hour's red.
 */
@Composable
private fun ReflexHourlyChart(
    stats: ReflexStats,
    chartHeight: Dp = 110.dp
) {
    val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
        val hours = stats.hourly.filter { it.avgMeanRtMs != null }
        if (hours.isEmpty()) return@Canvas
        val padL = 40.dp.toPx()
        val padR = 10.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 18.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        val lo = hours.minOf { it.avgMeanRtMs!! }
        val hi = hours.maxOf { it.avgMeanRtMs!! }
        val vSpan = maxOf(1.0, hi - lo)
        val slot = chartW / 24f
        val barW = (slot * 0.6f).coerceAtLeast(2f)

        // Baseline gridline at the slowest value.
        fun y(v: Double) = padT + chartH * (1f - ((v - lo) / vSpan).toFloat())
        drawLine(ChartGrid, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), strokeWidth = 1f)
        drawContext.canvas.nativeCanvas.drawText(
            "${lo.toInt()}", 0f, y(lo) + labelPx / 3, labelPaint
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${hi.toInt()}", 0f, y(hi) + labelPx / 3, labelPaint
        )

        stats.hourly.forEach { hd ->
            val rt = hd.avgMeanRtMs ?: return@forEach
            val color = when (hd.hour) {
                stats.fastestHour -> Color(0xFF22C55E)
                stats.slowestHour -> Color(0xFFEF4444)
                else -> ChartOrange
            }
            val top = y(rt)
            val left = padL + hd.hour * slot + (slot - barW) / 2f
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barW, padT + chartH - top)
            )
        }

        // X labels: 00, 06, 12, 18
        listOf(0, 6, 12, 18).forEach { hr ->
            drawContext.canvas.nativeCanvas.drawText(
                "%02d".format(hr),
                padL + hr * slot,
                h - 4.dp.toPx(),
                labelPaint
            )
        }
    }
}
