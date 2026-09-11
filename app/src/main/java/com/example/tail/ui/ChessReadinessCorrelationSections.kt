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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.CorrelationScatterPoint
import com.example.tail.data.SURVIVAL_UNLIMITED_CUTOFF_MS
import com.example.tail.data.V3CorrelationStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Pre-game ↔ Performance Correlations — section of the Chess Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * Thirteen collapsible scatter subsections, one per correlation computed by
 * [com.example.tail.data.computeV3CorrelationStats] — each with its own
 * scatter chart (dot = one run), dashed least-squares fit and Pearson r:
 *
 *  Reflex stage (x = 2-min PVT-B mean RT) vs:
 *    accuracy · ACPL · blunders · unforced blunders · win rate · Elo delta
 *  Survival stage (x = puzzles solved) vs the same six session metrics
 *  Reflex stage → survival stage (same run, no session data needed)
 *
 * Only post-cutoff runs feed the correlations — solved counts before the
 * 5-minute-cap change were censored at the pass bar and are not comparable.
 * Subsections render collapsed until their series has ≥ 2 points; the outer
 * section renders a "collecting data" note until eligible runs exist.
 */

// ── Palette (private copies of the screen's warm orange theme) ───────────────
private val SectionTitleColor = Color(0xFFF2A65A)
private val LabelColor = Color(0xFFE6C79C)
private val ValueColor = Color.White
private val DimColor = Color(0xFF9C8B77)
private val SectionBg = Color(0xFF231A10)
private val SubSectionBg = Color(0xFF2B2013)
private val DividerColor = Color(0xFF3A2E1E)
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val YellowValue = Color(0xFFEAB308)
private val GoldValue = Color(0xFFFFC24D)
private val ChartGrid = Color(0xFF3A2E1E)

private val EVENT_FMT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun fmtTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(EVENT_FMT)

private fun fmtDay(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM yyyy"))

/** Colour for a correlation row: meaningful |r| green/red, weak grey. */
private fun rColor(r: Double?, invertedIsGood: Boolean): Color = when {
    r == null -> DimColor
    abs(r) < 0.2 -> DimColor
    (r < 0) == invertedIsGood -> GreenValue
    else -> RedValue
}

@Composable
private fun CorrStatRow(label: String, value: String, valueColor: Color = ValueColor) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, color = LabelColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── The section ──────────────────────────────────────────────────────────────

/**
 * The correlations section: overview rows + thirteen per-correlation
 * collapsible scatter subsections.
 */
@Composable
fun V3CorrelationSection(stats: V3CorrelationStats) {
    if (stats.totalRunsSeen == 0) return
    var expanded by rememberSectionExpansion(
        "chess", "🔮 Pre-game ↔ Performance Correlations", true
    )

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
                "🔮 Pre-game ↔ Performance Correlations",
                color = SectionTitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (expanded) "▼" else "▶", color = SectionTitleColor, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = DividerColor, thickness = 1.dp)

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Does the pre-game test predict how you actually play? Every " +
                    "v3 run since ${fmtDay(SURVIVAL_UNLIMITED_CUTOFF_MS)} — when the survival " +
                    "stage stopped ending at the target and started using the " +
                    "full 5 minutes — is correlated with the rated games played " +
                    "in the following 6 hours. ACPL and (unforced) blunders come " +
                    "from the desktop Stockfish analysis of each audited game. " +
                    "Each subsection below is one correlation.",
                color = DimColor, fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            CorrStatRow("v3 runs (all time)", "${stats.totalRunsSeen}")
            CorrStatRow("Eligible runs (post-cutoff)", "${stats.eligibleRuns}")
            CorrStatRow(
                "Runs followed by rated games",
                "${stats.matchedSessions}",
                valueColor = if (stats.matchedSessions >= stats.minPairs) GreenValue else YellowValue
            )

            if (stats.eligibleRuns == 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Collecting data — the first eligible run lands at the next " +
                        "completed pre-game test.",
                    color = DimColor, fontSize = 11.sp, fontStyle = FontStyle.Italic
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Reflex stage (x = mean RT) → session outcomes ──
            CorrSubSection(
                title = "⚡ Reflex RT → session accuracy",
                subtitle = "Slower reflexes (right) ideally mean LOWER accuracy (down).",
                points = stats.rtAccuracyPairs,
                r = stats.rtAccuracyR,
                rInvertedIsGood = true,
                xUnit = "ms",
                yFormat = { "%.1f".format(it) },
                yHint = "%"
            )
            CorrSubSection(
                title = "⚡ Reflex RT → session ACPL",
                subtitle = "Slower reflexes ideally mean HIGHER average centipawn loss (up).",
                points = stats.rtAcplPairs,
                r = stats.rtAcplR,
                rInvertedIsGood = false,
                xUnit = "ms",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "⚡ Reflex RT → session blunders",
                subtitle = "Slower reflexes ideally mean MORE blunders (up).",
                points = stats.rtBlundersPairs,
                r = stats.rtBlundersR,
                rInvertedIsGood = false,
                xUnit = "ms",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "⚡ Reflex RT → unforced blunders",
                subtitle = "Blunders NOT forced by time pressure — the pure-calculation " +
                    "failures a rested brain should avoid.",
                points = stats.rtUnforcedPairs,
                r = stats.rtUnforcedR,
                rInvertedIsGood = false,
                xUnit = "ms",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "⚡ Reflex RT → session win rate",
                subtitle = "Slower reflexes ideally mean a LOWER win share (down).",
                points = stats.rtWinRatePairs,
                r = stats.rtWinRateR,
                rInvertedIsGood = true,
                xUnit = "ms",
                yFormat = { "%.1f".format(it) },
                yHint = "%"
            )
            CorrSubSection(
                title = "⚡ Reflex RT → session Elo delta",
                subtitle = "Slower reflexes ideally mean a bigger rating drop (down).",
                points = stats.rtEloPairs,
                r = stats.rtEloR,
                rInvertedIsGood = true,
                xUnit = "ms",
                yFormat = { "%.0f".format(it) }
            )

            // ── Survival stage (x = puzzles solved) → session outcomes ──
            CorrSubSection(
                title = "♟ Puzzles solved → session accuracy",
                subtitle = "A deeper survival score before playing vs the accuracy afterwards.",
                points = stats.solvedAccuracyPairs,
                r = stats.solvedAccuracyR,
                rInvertedIsGood = false,
                xUnit = "puzzles",
                yFormat = { "%.1f".format(it) },
                yHint = "%"
            )
            CorrSubSection(
                title = "♟ Puzzles solved → session ACPL",
                subtitle = "More puzzles solved should mean LOWER average centipawn loss (down).",
                points = stats.solvedAcplPairs,
                r = stats.solvedAcplR,
                rInvertedIsGood = true,
                xUnit = "puzzles",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "♟ Puzzles solved → session blunders",
                subtitle = "More puzzles solved should mean FEWER blunders (down).",
                points = stats.solvedBlundersPairs,
                r = stats.solvedBlundersR,
                rInvertedIsGood = true,
                xUnit = "puzzles",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "♟ Puzzles solved → unforced blunders",
                subtitle = "Time-pressure-free blunders — tactical sharpness carries over?",
                points = stats.solvedUnforcedPairs,
                r = stats.solvedUnforcedR,
                rInvertedIsGood = true,
                xUnit = "puzzles",
                yFormat = { "%.0f".format(it) }
            )
            CorrSubSection(
                title = "♟ Puzzles solved → session win rate",
                subtitle = "More puzzles solved should mean a HIGHER win share (up).",
                points = stats.solvedWinRatePairs,
                r = stats.solvedWinRateR,
                rInvertedIsGood = false,
                xUnit = "puzzles",
                yFormat = { "%.1f".format(it) },
                yHint = "%"
            )
            CorrSubSection(
                title = "♟ Puzzles solved → session Elo delta",
                subtitle = "More puzzles solved should mean a better rating outcome (up).",
                points = stats.solvedEloPairs,
                r = stats.solvedEloR,
                rInvertedIsGood = false,
                xUnit = "puzzles",
                yFormat = { "%.0f".format(it) }
            )

            // ── Reflex → survival (same run) ──
            CorrSubSection(
                title = "⚡ Reflex RT → puzzles solved (same run)",
                subtitle = "Does the physiological reflex stage predict the tactical " +
                    "survival stage of the SAME pre-game test? Slower RT should " +
                    "mean fewer puzzles (down).",
                points = stats.rtSolvedPairs,
                r = stats.rtSolvedR,
                rInvertedIsGood = true,
                xUnit = "ms",
                yFormat = { "%.0f".format(it) }
            )

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "r needs ≥ ${stats.minPairs} comparable pairs. |r| < 0.2 is noise. " +
                    "For \"lower is better\" metrics (RT, ACPL, blunders) an INVERSE " +
                    "relation with the outcome is the healthy sign; subsections whose " +
                    "series has fewer than 2 points stay collapsed.",
                color = DimColor, fontSize = 10.sp
            )
        }
    }
}

/**
 * One collapsible subsection = one correlation. Collapsed by default until
 * the series has ≥ 2 points (a chart needs at least two dots to be worth
 * opening); once it does it remembers its own expansion state.
 */
@Composable
private fun CorrSubSection(
    title: String,
    subtitle: String,
    points: List<CorrelationScatterPoint>,
    r: Double?,
    rInvertedIsGood: Boolean,
    xUnit: String,
    yFormat: (Double) -> String,
    yHint: String? = null
) {
    var expanded by rememberSectionExpansion(
        "chess-corr", title, points.size >= 2
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(SubSectionBg, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = SectionTitleColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                r?.let { "r = %+.2f".format(it) } ?: "${points.size}/≥2",
                color = rColor(r, rInvertedIsGood),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", color = SectionTitleColor, fontSize = 12.sp)
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(subtitle, color = DimColor, fontSize = 11.sp)
            if (yHint != null) {
                Text("y axis: $yHint", color = DimColor, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            if (points.size >= 2) {
                ScatterChart(
                    points = points,
                    chartHeight = 150.dp,
                    xUnit = xUnit,
                    yFormat = yFormat
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(GoldValue, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "one dot = one pre-game run · dashed = least-squares fit",
                        color = DimColor, fontSize = 10.sp
                    )
                }
            } else {
                Text(
                    "Only ${points.size} point(s) so far — a chart needs ≥ 2.",
                    color = DimColor, fontSize = 11.sp, fontStyle = FontStyle.Italic
                )
            }
        }
    }
}

/**
 * Scatter plot with a dashed least-squares regression line. Tapping near a
 * dot selects it and opens a small detail dialog.
 */
@Composable
private fun ScatterChart(
    points: List<CorrelationScatterPoint>,
    chartHeight: Dp,
    xUnit: String,
    yFormat: (Double) -> String
) {
    var selected by remember { mutableStateOf<CorrelationScatterPoint?>(null) }
    var canvasWidth by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val labelPx = with(density) { 9.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#888888")
        textSize = labelPx
        isAntiAlias = true
    }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(chartHeight)
            .onSizeChanged { canvasWidth = it.width.toFloat() }
            .pointerInput(points, canvasWidth) {
                detectTapGestures { pos ->
                    if (canvasWidth <= 0f || points.isEmpty()) return@detectTapGestures
                    val padL = with(density) { 40.dp.toPx() }
                    val padR = with(density) { 12.dp.toPx() }
                    val padT = 12.dp.toPx()
                    val padB = 22.dp.toPx()
                    val w = canvasWidth
                    val xs = points.map { it.x }
                    val ys = points.map { it.y }
                    val xLo = xs.min(); val xHi = xs.max()
                    val yLo = ys.min(); val yHi = ys.max()
                    val xPad = maxOf(1e-6, (xHi - xLo) * 0.08)
                    val yPad = maxOf(1e-6, (yHi - yLo) * 0.12)
                    val xSpan = (xHi - xLo) + 2 * xPad
                    val ySpan = (yHi - yLo) + 2 * yPad
                    val chartW = (w - padL - padR).coerceAtLeast(1f)
                    val chartH = (with(density) { chartHeight.toPx() } - padT - padB)
                        .coerceAtLeast(1f)
                    val threshold = with(density) { 20.dp.toPx() }
                    var best: CorrelationScatterPoint? = null
                    var bestD = Double.MAX_VALUE
                    points.forEach { p ->
                        val px = padL + chartW * (((p.x - xLo) + xPad) / xSpan).toFloat()
                        val py = padT + chartH * (1f - (((p.y - yLo) + yPad) / ySpan).toFloat())
                        val d = hypot((px - pos.x).toDouble(), (py - pos.y).toDouble())
                        if (d < bestD) { bestD = d; best = p }
                    }
                    if (bestD <= threshold) {
                        selected = if (selected == best) null else best
                    }
                }
            }
    ) {
        if (points.isEmpty()) return@Canvas
        val padL = 40.dp.toPx()
        val padR = 12.dp.toPx()
        val padT = 12.dp.toPx()
        val padB = 22.dp.toPx()
        val w = size.width
        val h = size.height
        val chartW = (w - padL - padR).coerceAtLeast(1f)
        val chartH = (h - padT - padB).coerceAtLeast(1f)

        val xs = points.map { it.x }
        val ys = points.map { it.y }
        val xLo = xs.min(); val xHi = xs.max()
        val yLo = ys.min(); val yHi = ys.max()
        val xPad = maxOf(1e-6, (xHi - xLo) * 0.08)
        val yPad = maxOf(1e-6, (yHi - yLo) * 0.12)
        val xSpan = (xHi - xLo) + 2 * xPad
        val ySpan = (yHi - yLo) + 2 * yPad

        fun px(x: Double) = padL + chartW * (((x - xLo) + xPad) / xSpan).toFloat()
        fun py(y: Double) = padT + chartH * (1f - (((y - yLo) + yPad) / ySpan).toFloat())

        // Gridlines + y labels
        val yStep = niceStep(ySpan / 3)
        var yv = ceil((yLo - yPad) / yStep) * yStep
        while (yv <= yHi + yPad) {
            val gy = py(yv)
            if (gy >= padT - 1f && gy <= padT + chartH + 1f) {
                drawLine(ChartGrid, Offset(padL, gy), Offset(w - padR, gy), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    yFormat(yv), 0f, gy + labelPx / 3, labelPaint
                )
            }
            yv += yStep
        }

        // Least-squares fit line (dashed)
        if (points.size >= 2) {
            val mx = xs.average(); val my = ys.average()
            var num = 0.0; var den = 0.0
            points.forEach { p -> num += (p.x - mx) * (p.y - my); den += (p.x - mx) * (p.x - mx) }
            if (den > 1e-12) {
                val slope = num / den
                val fit = { x: Double -> slope * (x - mx) + my }
                val x1 = xLo - xPad; val x2 = xHi + xPad
                drawLine(
                    GoldValue.copy(alpha = 0.7f),
                    Offset(px(x1), py(fit(x1))),
                    Offset(px(x2), py(fit(x2))),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }
        }

        // Dots (orange; gold ring on the selected one)
        points.forEach { p ->
            drawCircle(
                Color(0xFFF2994A).copy(alpha = 0.85f),
                radius = 4.dp.toPx(), center = Offset(px(p.x), py(p.y))
            )
        }
        selected?.let { p ->
            drawCircle(
                GoldValue, radius = 6.dp.toPx(),
                center = Offset(px(p.x), py(p.y)), style = Stroke(width = 2.dp.toPx())
            )
        }

        // X-axis endpoint labels
        drawContext.canvas.nativeCanvas.drawText(
            "%.0f".format(xLo), padL, h - 6.dp.toPx(), labelPaint
        )
        val xHiLabel = "%.0f $xUnit".format(xHi)
        drawContext.canvas.nativeCanvas.drawText(
            xHiLabel, w - padR - labelPaint.measureText(xHiLabel), h - 6.dp.toPx(), labelPaint
        )
    }

    selected?.let { p ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text("Close", color = GoldValue, fontWeight = FontWeight.Bold)
                }
            },
            title = {
                Text(
                    "Run · ${fmtTime(p.timestampMs)}",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
            },
            text = {
                Column {
                    Text("x: ${"%.1f".format(p.x)}", color = LabelColor, fontSize = 13.sp)
                    Text("y: ${yFormat(p.y)}", color = LabelColor, fontSize = 13.sp)
                }
            },
            containerColor = SectionBg
        )
    }
}

/** Chooses a human-friendly gridline step (1/2/5 × 10^k). */
private fun niceStep(raw: Double): Double {
    if (raw <= 0) return 1.0
    val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
    val norm = raw / mag
    return when {
        norm >= 5 -> 5 * mag
        norm >= 2 -> 2 * mag
        else -> mag
    }
}
