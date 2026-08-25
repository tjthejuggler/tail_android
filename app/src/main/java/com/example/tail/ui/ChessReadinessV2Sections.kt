package com.example.tail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.example.tail.data.Phase2V2GameRecord
import com.example.tail.data.Phase2V2Point
import com.example.tail.data.Phase2V2Stats
import com.example.tail.data.Phase2AuditRecord
import com.example.tail.data.Phase2Verdicts
import com.example.tail.data.V2HourlyReadiness
import com.example.tail.data.V2PregameStats
import com.example.tail.data.V2PvtPoint
import com.example.tail.data.V2PvtRecord
import com.example.tail.data.V2ResultRecord
import com.example.tail.data.V2Tiers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness — V2 sections of the Readiness Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * Two dedicated sections for the v2 chess-readiness systems:
 *
 *  - [V2PregameSection] — the pre-game cognitive gate: verdict tiers over
 *    time, PVT-B reflex-test detail (mean response time, late taps =
 *    lapses, early taps = false starts) with trends, and the passive
 *    module averages (autonomic Z-scores, ACWR).
 *
 *  - [Phase2V2Section] — the post-game audit v2: verdict distribution,
 *    win/loss/draw split, accuracy over time, Elo delta, strain and
 *    session minutes.
 *
 * Charts follow the puzzle-chart design: evenly-spaced index slots, one
 * slot per event, dots colored by the verdict that event produced.
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

private val EVENT_FMT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun fmtTime(ts: Long?): String =
    ts?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(EVENT_FMT)
    } ?: "—"

private fun fmtShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))

private fun tierColor(tier: String?): Color = when (tier) {
    V2Tiers.TIER1 -> Color(0xFF22C55E)
    V2Tiers.TIER2 -> YellowValue
    V2Tiers.TIER3 -> Color(0xFFEF4444)
    else -> DimColor
}

private fun tierLabel(tier: String?): String = when (tier) {
    V2Tiers.TIER1 -> "TIER 1 · PASS"
    V2Tiers.TIER2 -> "TIER 2"
    V2Tiers.TIER3 -> "TIER 3"
    else -> "no verdict"
}

private fun verdictColor(v: String?): Color = when (v) {
    Phase2Verdicts.CONTINUE -> Color(0xFF22C55E)
    Phase2Verdicts.PIVOT -> YellowValue
    Phase2Verdicts.TERMINATE -> Color(0xFFEF4444)
    else -> DimColor
}

private fun verdictLabel(v: String?): String = when (v) {
    Phase2Verdicts.CONTINUE -> "CONTINUE"
    Phase2Verdicts.PIVOT -> "PIVOT TO DRILLS"
    Phase2Verdicts.TERMINATE -> "TERMINATE"
    else -> "—"
}

private fun resultColor(r: String): Color = when (r) {
    "WIN" -> Color(0xFF22C55E)
    "LOSS" -> Color(0xFFEF4444)
    else -> DimColor
}

// ── Shared building blocks ───────────────────────────────────────────────────

@Composable
private fun V2Section(
    title: String,
    startExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember(title) { mutableStateOf(startExpanded) }
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
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, color = DimColor, fontSize = 10.sp)
    }
}

// ── V2 pre-game section ──────────────────────────────────────────────────────

/**
 * The v2 pre-game cognitive gate: verdict tiers, the PVT-B reflex test over
 * time (mean RT chart with pass/fail dot colors, lapses/false-starts chart),
 * trends, passive-module averages and the recent per-test list.
 */
@Composable
fun V2PregameSection(
    stats: V2PregameStats,
    chartHeight: Dp = 150.dp,
    /** Default expansion — true while the v2 pre-game engine is the active one. */
    startExpanded: Boolean = true,
    /** Hour-of-day aggregates powering the 24-hour popup chart. */
    hourly: List<V2HourlyReadiness> = emptyList(),
    onOpenHourly: () -> Unit = {},
    /** Opens the interactive landscape mean-RT chart. */
    onOpenRtChart: () -> Unit = {},
    /** Opens the interactive landscape lapses/false-starts chart. */
    onOpenLapseChart: () -> Unit = {}
) {
    if (stats.totalTests == 0 && stats.pvtCount == 0) return
    val s = stats

    V2Section(
        title = "🧬 V2 Pre-Game Gate — Cognitive Readiness",
        startExpanded = startExpanded
    ) {
        Text(
            "The v2 pre-game test: overnight autonomic Z-scores and cognitive " +
                "load (ACWR) combine with the 3-minute PVT-B reflex test — the " +
                "worst module decides the verdict. TIER 1 = PASS (rated play " +
                "unlocked), TIER 2 = casual only, TIER 3 = locked out.",
            color = DimColor, fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Verdict overview ──
        StatRow("Evaluations completed", s.totalTests.toString())
        StatRow("🟢 Tier 1 — PASS (rated play)", s.tier1Count.toString(), valueColor = GreenValue)
        StatRow("🟡 Tier 2 — casual only", s.tier2Count.toString(), valueColor = YellowValue)
        StatRow("🔴 Tier 3 — locked out", s.tier3Count.toString(), valueColor = RedValue)
        StatRow(
            "Pass rate", "%.0f%%".format(s.passRate),
            valueColor = when {
                s.passRate >= 60 -> GreenValue
                s.passRate >= 30 -> YellowValue
                else -> RedValue
            }
        )
        if (s.totalTests > 0) {
            StatRow("First test", fmtTime(s.firstTestAt))
            StatRow("Last test", fmtTime(s.lastTestAt))
        }

        // ── Hour-of-day chart link ──
        if (s.totalTests > 0 || s.pvtCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            ChartLinkRow(
                "V2 readiness by hour of day (00–23) — CCRS / pass rate / PVT speed",
                "24-hour chart 📈"
            ) { onOpenHourly() }
        }

        if (s.pvtCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // ── Reflex test aggregates ──
            Text("⚡ Reflex test (PVT-B)", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            StatRow("Runs completed", s.pvtCount.toString())
            s.avgMeanRtMs?.let {
                StatRow("Average response time", "%.0f ms".format(it), valueColor = GoldValue)
            }
            s.bestMeanRtMs?.let {
                StatRow("Best run (fastest avg)", "%.0f ms".format(it), valueColor = GreenValue)
            }
            s.avgResponseSpeed?.let {
                StatRow("Avg response speed (1000/RT)", "%.2f".format(it))
            }
            s.worstMaxRtMs?.let {
                StatRow("Slowest single response", "$it ms", valueColor = RedValue)
            }
            StatRow(
                "Late taps (lapses ≥ 355 ms)",
                "${s.totalLapses} total · %.1f/run".format(s.avgLapses),
                valueColor = if (s.avgLapses > 2) RedValue else ValueColor
            )
            StatRow(
                "Early taps (false starts < 100 ms)",
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

            // ── Mean RT over time ──
            val rtPoints = s.series.filter { it.meanRtMs != null }
            if (rtPoints.size >= 2) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Average response time per run — dot color = verdict " +
                        "that run produced (tap a point for full detail):",
                    color = DimColor, fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                V2RtChart(rtPoints, chartHeight)
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LegendSwatch(Color(0xFF22C55E), "Tier 1 (pass)")
                    LegendSwatch(YellowValue, "Tier 2")
                    LegendSwatch(Color(0xFFEF4444), "Tier 3")
                    LegendSwatch(DimColor, "No verdict")
                }
                ChartLinkRow(
                    "Zoomable response-time chart — pinch, scroll, tap any run",
                    "Interactive 📈"
                ) { onOpenRtChart() }
            }

            // ── Lapses & false starts over time ──
            if (s.series.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Late taps (red) and early taps (yellow) per run:",
                    color = DimColor, fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                V2LapseChart(s.series, chartHeight)
                Spacer(modifier = Modifier.height(4.dp))
                ChartLinkRow(
                    "Zoomable late/early-tap chart — pinch, scroll, tap any run",
                    "Interactive 📈"
                ) { onOpenLapseChart() }
            }

            // ── Recent runs list ──
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Recent runs", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            s.series.takeLast(10).asReversed().forEach { p ->
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
                            append(tierLabel(p.tier))
                            p.meanRtMs?.let { append(" · %.0f ms".format(it)) }
                            append(" · ${p.lapses} late · ${p.falseStarts} early")
                        },
                        color = tierColor(p.tier),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // ── Passive modules ──
        if (s.totalTests > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("🌙 Passive modules", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            s.avgZLnRmssd?.let {
                StatRow(
                    "Avg lnRMSSD Z-score", "%+.2f".format(it),
                    valueColor = if (abs(it) <= 0.5) GreenValue else YellowValue
                )
            }
            s.avgZRhr?.let {
                StatRow(
                    "Avg resting-HR Z-score", "%+.2f".format(it),
                    valueColor = if (it <= 0.5) GreenValue else YellowValue
                )
            }
            StatRow("Autonomic data coverage", "%.0f%% of tests".format(s.autonomicCoverage))
            s.avgAcwr?.let {
                StatRow(
                    "Avg cognitive ACWR", "%.2f".format(it),
                    valueColor = when {
                        it > 1.5 -> RedValue
                        it > 1.3 -> YellowValue
                        else -> GreenValue
                    }
                )
            }
            if (s.pvtSkippedCount > 0) {
                StatRow(
                    "PVT skipped (already restricted)",
                    s.pvtSkippedCount.toString(),
                    valueColor = DimColor
                )
            }
        }
    }
}

/**
 * Mean response-time line chart, one evenly-spaced slot per run; each dot
 * is colored by the verdict that run produced. Tapping a point opens a
 * dialog with the run's full detail.
 */
@Composable
private fun V2RtChart(
    points: List<V2PvtPoint>,
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

        // Line + verdict-colored dots
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
            drawCircle(tierColor(p.tier), radius = 3.dp.toPx(), center = Offset(xAt(i, w), y(rt)))
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
                        "PVT-B run · ${fmtTime(p.timestampMs)}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                },
                text = {
                    Column {
                        Text("Verdict: ${tierLabel(p.tier)}", color = tierColor(p.tier), fontSize = 13.sp)
                        p.meanRtMs?.let { Text("Mean response time: %.0f ms".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.meanRrt?.let { Text("Response speed (1000/RT): %.2f".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.maxRtMs?.let { Text("Slowest response: $it ms", color = LabelColor, fontSize = 13.sp) }
                        Text("Valid responses: ${p.validResponses}", color = LabelColor, fontSize = 13.sp)
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
private fun V2LapseChart(
    points: List<V2PvtPoint>,
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

        fun series(color: Color, value: (V2PvtPoint) -> Int) {
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

// ── Phase 2 v2 post-game section ─────────────────────────────────────────────

/**
 * The v2 post-game audit: verdict distribution (continue / pivot /
 * terminate), win/loss/draw split, accuracy over time, Elo delta, strain
 * and session minutes, plus the recent per-game audit list.
 */
@Composable
fun Phase2V2Section(
    stats: Phase2V2Stats,
    chartHeight: Dp = 150.dp,
    /** Default expansion — true while the v2 post-game engine is the active one. */
    startExpanded: Boolean = true,
    /** Opens the interactive landscape accuracy chart. */
    onOpenAccuracyChart: () -> Unit = {}
) {
    if (stats.totalGames == 0) return
    val s = stats

    V2Section(
        title = "🔬 V2 Post-Game Audit — Performance Review",
        startExpanded = startExpanded
    ) {
        Text(
            "Every rated game audited by the v2 post-game engine: verdict " +
                "(CONTINUE / PIVOT TO DRILLS / TERMINATE), result, chess.com " +
                "accuracy, Elo delta and session strain.",
            color = DimColor, fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Verdict overview ──
        StatRow("Games audited", s.totalGames.toString())
        StatRow("✓ Continue", s.continueCount.toString(), valueColor = GreenValue)
        StatRow("↻ Pivot to drills", s.pivotCount.toString(), valueColor = YellowValue)
        StatRow("✗ Terminate session", s.terminateCount.toString(), valueColor = RedValue)
        StatRow(
            "Continue rate", "%.0f%%".format(s.continueRate),
            valueColor = when {
                s.continueRate >= 70 -> GreenValue
                s.continueRate >= 40 -> YellowValue
                else -> RedValue
            }
        )
        StatRow(
            "Latest verdict",
            verdictLabel(s.latestVerdict),
            valueColor = verdictColor(s.latestVerdict)
        )
        if (s.totalGames > 0) {
            StatRow("First audited", fmtTime(s.firstGameAt))
            StatRow("Last audited", fmtTime(s.lastGameAt))
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))

        // ── Results ──
        Text("🏆 Results", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        StatRow("Wins / Losses / Draws", "${s.wins} / ${s.losses} / ${s.draws}")
        StatRow(
            "Win rate (decided games)", "%.0f%%".format(s.winRate),
            valueColor = if (s.winRate >= 50) GreenValue else RedValue
        )
        StatRow("Longest loss streak", s.longestLossStreak.toString(), valueColor = if (s.longestLossStreak >= 3) RedValue else ValueColor)

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))

        // ── Quality ──
        Text("🎯 Game quality", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        s.avgAccuracy?.let {
            StatRow(
                "Average accuracy", "%.1f%% (${s.accuracyGames} games)".format(it),
                valueColor = if (it >= 70) GreenValue else YellowValue
            )
        }
        s.accuracyTrend?.let {
            StatRow(
                "Accuracy trend (first 3 → last 3)",
                "%+.1f pts".format(it),
                valueColor = if (it >= 0) GreenValue else RedValue
            )
        }
        StatRow(
            "Elo delta (audited games)",
            "%+.0f total · %+.1f/game".format(s.totalDeltaE, s.avgDeltaE),
            valueColor = if (s.totalDeltaE >= 0) GreenValue else RedValue
        )
        StatRow(
            "Session strain accumulated",
            "%.0f / 100".format(s.totalStrain),
            valueColor = when {
                s.totalStrain >= 100 -> RedValue
                s.totalStrain >= 50 -> YellowValue
                else -> GreenValue
            }
        )
        StatRow(
            "Time on the board",
            "%.0f min total · %.0f/game".format(s.totalMinutes, s.avgMinutes)
        )

        // ── Accuracy over time ──
        val accPoints = s.series.filter { it.accuracy != null }
        if (accPoints.size >= 2) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Accuracy per audited game — dot color = verdict (tap for detail):",
                color = DimColor, fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Phase2AccuracyChart(accPoints, chartHeight)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LegendSwatch(Color(0xFF22C55E), "Continue")
                LegendSwatch(YellowValue, "Pivot")
                LegendSwatch(Color(0xFFEF4444), "Terminate")
            }
            ChartLinkRow(
                "Zoomable accuracy chart — pinch, scroll, tap any game",
                "Interactive 📈"
            ) { onOpenAccuracyChart() }
        }

        // ── Recent audits list ──
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Recent audits", color = LabelColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        s.series.takeLast(10).asReversed().forEach { p ->
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
                        append(p.result)
                        append(" · ")
                        append(verdictLabel(p.outputState))
                        p.accuracy?.let { append(" · %.0f%%".format(it)) }
                        p.deltaE?.let { append(" · %+.0f Elo".format(it)) }
                    },
                    color = verdictColor(p.outputState),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Accuracy line chart, one evenly-spaced slot per audited game; each dot is
 * colored by that game's verdict. Tapping a point opens a detail dialog.
 */
@Composable
private fun Phase2AccuracyChart(
    points: List<Phase2V2Point>,
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
    val padL = with(density) { 34.dp.toPx() }
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

        val values = points.mapNotNull { it.accuracy }
        val vMin = values.min()
        val vMax = values.max()
        val vPad = maxOf(2.0, (vMax - vMin) * 0.15)
        val lo = (vMin - vPad).coerceAtLeast(0.0)
        val hi = minOf(100.0, vMax + vPad)
        val vSpan = maxOf(1.0, hi - lo)

        fun y(v: Double) = padT + chartH * (1f - ((v - lo) / vSpan).toFloat())

        // Gridlines + y labels (%)
        val step = maxOf(1.0, (hi - lo) / 4).let { target ->
            listOf(1, 2, 5, 10, 20, 25).firstOrNull { it >= target } ?: 25
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

        // Line + verdict-colored dots
        val path = Path()
        points.forEachIndexed { i, p ->
            val acc = p.accuracy ?: return@forEachIndexed
            val c = Offset(xAt(i, w), y(acc))
            if (i == 0 || points[i - 1].accuracy == null) path.moveTo(c.x, c.y)
            else path.lineTo(c.x, c.y)
        }
        drawPath(path, ChartOrange, style = Stroke(width = 2.dp.toPx()))
        points.forEachIndexed { i, p ->
            val acc = p.accuracy ?: return@forEachIndexed
            drawCircle(verdictColor(p.outputState), radius = 3.dp.toPx(), center = Offset(xAt(i, w), y(acc)))
        }

        selected?.let { idx ->
            points.getOrNull(idx)?.let { p ->
                val acc = p.accuracy ?: return@let
                val sx = xAt(idx, w)
                drawLine(GoldValue, Offset(sx, padT), Offset(sx, padT + chartH), strokeWidth = 1.5f)
                drawCircle(GoldValue, radius = 5.dp.toPx(), center = Offset(sx, y(acc)))
            }
        }

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
                        "Audited game · ${fmtTime(p.timestampMs)}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                },
                text = {
                    Column {
                        Text("Result: ${p.result}", color = resultColor(p.result), fontSize = 13.sp)
                        Text("Verdict: ${verdictLabel(p.outputState)}", color = verdictColor(p.outputState), fontSize = 13.sp)
                        Text("Time control: ${p.timeControl.lowercase()}", color = LabelColor, fontSize = 13.sp)
                        p.accuracy?.let { Text("Accuracy: %.1f%%".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.deltaE?.let { Text("Elo delta: %+.1f".format(it), color = LabelColor, fontSize = 13.sp) }
                        p.strain?.let { Text("Strain: %.0f / 100".format(it), color = LabelColor, fontSize = 13.sp) }
                        Text("Minutes: %.0f".format(p.estimatedMinutes), color = LabelColor, fontSize = 13.sp)
                    }
                },
                containerColor = SectionBg
            )
        }
    }
}
