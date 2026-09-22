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
import com.example.tail.data.Phase2V2GameRecord
import com.example.tail.data.Phase2V2Point
import com.example.tail.data.Phase2V2Stats
import com.example.tail.data.Phase2AuditRecord
import com.example.tail.data.Phase2Verdicts
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
 * One dedicated section for the v2 chess-readiness system:
 *
 *  (The former v2 pre-game section was replaced by the cross-version
 *  ReflexSection — every PVT-B reflex run regardless of engine version —
 *  in ChessReflexSections.kt, so reflex stats stay comparable as the
 *  pre-game engine keeps evolving.)
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
