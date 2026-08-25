package com.example.tail.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.CcrsBandStats
import com.example.tail.data.DayOfWeekStats
import com.example.tail.data.GameCategory
import com.example.tail.data.GameCategoryAggregate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ♟ "Is the system working?" sections for the Chess Readiness stats screen:
 *
 *  - System Effectiveness: win rates of Approved vs. Denied vs. Expired vs.
 *    Pre-test games side by side (the core comparison the user asked for)
 *  - Win rate by readiness score band (does higher CCRS → more wins?)
 *  - Day-of-week rhythm (readiness + win rate per weekday)
 *
 * Pure presentation — all aggregation happens in
 * [com.example.tail.data.ChessReadinessStatsCalculator].
 */

// ── Private copies of the readiness screen's warm palette + section
//    scaffolding (kept file-private because several other files in this
//    package define identically-named private palette vals). ────────────────
private val SectionTitleColor = Color(0xFFF2A65A)   // soft orange
private val LabelColor = Color(0xFFE6C79C)          // warm sand
private val ValueColor = Color.White
private val DimColor = Color(0xFF9C8B77)            // warm grey
private val SectionBg = Color(0xFF231A10)           // dark warm brown
private val DividerColor = Color(0xFF3A2E1E)        // warm divider
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val YellowValue = Color(0xFFEAB308)
private val GoldValue = Color(0xFFFFC24D)           // warm amber

@Composable
private fun StatsSection(
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
                text = title,
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
        Text(
            text = label,
            color = LabelColor,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── System Effectiveness ─────────────────────────────────────────────────────

private fun categoryColor(cat: GameCategory): Color = when (cat) {
    GameCategory.PRE_TEST -> Color(0xFF8FA8C8)   // cool blue-grey (before the system)
    GameCategory.APPROVED -> Color(0xFF22C55E)   // green
    GameCategory.DENIED -> Color(0xFFEF4444)     // red
    GameCategory.EXPIRED -> Color(0xFFEAB308)    // yellow (bypassed, not defied)
}

/**
 * Win-rate comparison across game categories, with an "All games"
 * reference bar. Horizontal bars on a fixed 0–100% scale so the
 * categories are directly comparable.
 */
@Composable
private fun WinRateCompareChart(
    rows: List<Triple<String, Color, GameCategoryAggregate?>>,
    allGames: Int,
    allWins: Int
) {
    val labelPx = with(LocalDensity.current) { 10.dp.toPx() }
    val valPx = with(LocalDensity.current) { 10.dp.toPx() }
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#E6C79C")
        textSize = labelPx
        isAntiAlias = true
    }
    val valPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor("#FFFFFF")
        textSize = valPx
        isAntiAlias = true
        isFakeBoldText = true
    }
    val rowH = 26.dp
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowH * rows.size + 6.dp)
    ) {
        val padL = 74.dp.toPx()
        val padR = 86.dp.toPx()
        val w = size.width
        val plotW = w - padL - padR
        val rh = rowH.toPx()
        val barH = 12.dp.toPx()

        // Scale gridlines: 0 / 25 / 50 / 75 / 100 %
        listOf(0, 25, 50, 75, 100).forEach { pct ->
            val x = padL + plotW * pct / 100f
            drawLine(
                DividerColor,
                Offset(x, 0f),
                Offset(x, rows.size * rh),
                strokeWidth = 1f
            )
        }

        rows.forEachIndexed { i, (label, color, agg) ->
            val cy = i * rh + rh / 2
            drawContext.canvas.nativeCanvas.drawText(
                label, 0f, cy + labelPx / 3, labelPaint
            )
            val rate = agg?.let { if (it.games > 0) it.winRate else null }
                ?: (if (allGames > 0) allWins * 100.0 / allGames else 0.0)
            val barW = plotW * (rate / 100.0).toFloat()
            drawRect(
                color = color.copy(alpha = if (agg == null || agg.games > 0) 1f else 0.25f),
                topLeft = Offset(padL, cy - barH / 2),
                size = Size(barW.coerceAtLeast(2f), barH)
            )
            val txt = when {
                agg == null -> "${rate.roundToInt()}% · $allGames g"
                agg.games == 0 -> "no games"
                else -> "${agg.winRate.roundToInt()}% · ${agg.games} g"
            }
            drawContext.canvas.nativeCanvas.drawText(
                txt, padL + plotW + 6.dp.toPx(), cy + valPx / 3, valPaint
            )
        }
    }
}

/**
 * The "System Effectiveness" section: every logged game classified by why
 * it was (or wasn't) authorized, with win rates compared on one chart.
 * If the readiness system works, the Approved bar should clearly beat the
 * Denied / Expired bars — and Pre-test shows the baseline before it.
 */
@Composable
internal fun SystemEffectivenessSection(
    aggregates: List<GameCategoryAggregate>,
    totalGames: Int,
    totalWins: Int,
    startExpanded: Boolean = true
) {
    StatsSection(title = "🧪 System Effectiveness", startExpanded = startExpanded) {
        Text(
            "Every logged game classified by its readiness context. If the " +
                "system works, Approved games should win noticeably more than " +
                "Denied (played despite a blocking test) and Expired (played " +
                "with no fresh test) — and Pre-test shows your baseline from " +
                "before the system existed.",
            color = DimColor,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        WinRateCompareChart(
            rows = listOf(Triple("All games", GoldValue, null)) +
                GameCategory.entries.map { cat ->
                    Triple(cat.label, categoryColor(cat), aggregates.first { it.category == cat })
                },
            allGames = totalGames,
            allWins = totalWins
        )
        Spacer(modifier = Modifier.height(6.dp))
        aggregates.forEach { a ->
            StatRow(
                "${a.category.label} — ${a.category.description}",
                if (a.games > 0) {
                    "%.0f%% win · %d g".format(a.winRate, a.games) +
                        (a.avgCcrsAtPlay?.let { " · CCRS %.0f".format(it) } ?: "")
                } else {
                    "no games"
                },
                valueColor = categoryColor(a.category)
            )
        }
        val approved = aggregates.first { it.category == GameCategory.APPROVED }
        val violations = aggregates
            .filter { it.category in listOf(GameCategory.DENIED, GameCategory.EXPIRED) }
            .let { Pair(it.sumOf { it.games }, it.sumOf { it.wins }) }
        if (approved.games > 0 && violations.first > 0) {
            val violRate = violations.second * 100.0 / violations.first
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(4.dp))
            StatRow(
                "Readiness advantage (Approved − violations)",
                "%+.1f pts".format(approved.winRate - violRate),
                valueColor = if (approved.winRate >= violRate) GoldValue else RedValue
            )
        }
    }
}

// ── Win rate by readiness score band ─────────────────────────────────────────

/**
 * Vertical bars of win rate per CCRS band (readiness score at play time).
 * Only post-test games have a CCRS context, so this chart directly tests
 * the system's core claim: higher measured readiness → more wins.
 */
@Composable
internal fun CcrsBandSection(
    bands: List<CcrsBandStats>,
    chartHeight: Dp = 130.dp,
    startExpanded: Boolean = true
) {
    StatsSection(title = "🎯 Win Rate by Readiness Score", startExpanded = startExpanded) {
        Text(
            "Win rate grouped by the CCRS the latest test reported at play " +
                "time (post-test games only). Bands follow the engine's " +
                "thresholds: below 40 severe, 40–59 poor, 60–69 marginal, " +
                "70+ green-capable.",
            color = DimColor,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (bands.all { it.games == 0 }) {
            Text(
                "No games with a readiness context yet.",
                color = DimColor,
                fontSize = 12.sp
            )
            return@StatsSection
        }
        val bandColors = listOf(RedValue, Color(0xFFE67E22), YellowValue, GreenValue)
        val labelPx = with(LocalDensity.current) { 9.dp.toPx() }
        val topPx = with(LocalDensity.current) { 10.dp.toPx() }
        val topPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FFFFFF")
            textSize = topPx
            isAntiAlias = true
            isFakeBoldText = true
        }
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#888888")
            textSize = labelPx
            isAntiAlias = true
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(chartHeight)) {
            val padL = 30.dp.toPx()
            val padR = 6.dp.toPx()
            val padT = 16.dp.toPx()
            val padB = 26.dp.toPx()
            val w = size.width
            val h = size.height
            val chartW = w - padL - padR
            val chartH = h - padT - padB
            val bottom = h - padB

            // Gridlines 0 / 50 / 100 %
            listOf(0, 50, 100).forEach { pct ->
                val y = bottom - chartH * pct / 100f
                drawLine(DividerColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f)
                drawContext.canvas.nativeCanvas.drawText(
                    pct.toString(), 0f, y + labelPx / 3, labelPaint
                )
            }

            val slot = chartW / bands.size
            val barW = slot * 0.55f
            bands.forEachIndexed { i, b ->
                val cx = padL + slot * i + slot / 2
                if (b.games > 0) {
                    val bh = chartH * (b.winRate / 100.0).toFloat()
                    drawRect(
                        color = bandColors[i.coerceIn(bandColors.indices)],
                        topLeft = Offset(cx - barW / 2, bottom - bh),
                        size = Size(barW, bh.coerceAtLeast(2f))
                    )
                    val txt = "${b.winRate.roundToInt()}%"
                    drawContext.canvas.nativeCanvas.drawText(
                        txt, cx - topPaint.measureText(txt) / 2, bottom - bh - 3f, topPaint
                    )
                }
                // Band label + games count under the axis
                val l1 = b.label
                drawContext.canvas.nativeCanvas.drawText(
                    l1, cx - labelPaint.measureText(l1) / 2, bottom + labelPx + 2f, labelPaint
                )
                val l2 = "${b.games} g"
                drawContext.canvas.nativeCanvas.drawText(
                    l2, cx - labelPaint.measureText(l2) / 2, bottom + 2 * labelPx + 6f, labelPaint
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        bands.forEach { b ->
            StatRow(
                "CCRS ${b.label}",
                if (b.games > 0) "%.0f%% win · %d W / %d games".format(
                    b.winRate, b.wins, b.games
                ) else "no games",
                valueColor = if (b.games > 0) ValueColor else DimColor
            )
        }
    }
}

// ── Day-of-week rhythm ───────────────────────────────────────────────────────

private fun dayLabel(d: java.time.DayOfWeek): String =
    d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)

/**
 * Average readiness and win rate per weekday (Monday-first). Shows whether
 * e.g. weekend nights systematically tank readiness or win rate.
 */
@Composable
internal fun DayOfWeekSection(
    stats: List<DayOfWeekStats>,
    startExpanded: Boolean = true
) {
    StatsSection(title = "📅 Day of Week", startExpanded = startExpanded) {
        val rated = stats.filter { it.testCount > 0 }
        val bestDay = rated.maxByOrNull { it.avgCcrs }
        val worstDay = rated.minByOrNull { it.avgCcrs }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Day", color = DimColor, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            Text("Avg CCRS", color = DimColor, fontSize = 11.sp, modifier = Modifier.width(52.dp))
            Text("Tests", color = DimColor, fontSize = 11.sp, modifier = Modifier.width(36.dp))
            Text("Games · win", color = DimColor, fontSize = 11.sp)
        }
        stats.forEach { d ->
            val isBest = d.testCount > 0 && d.day == bestDay?.day &&
                bestDay != worstDay
            val isWorst = d.testCount > 0 && d.day == worstDay?.day &&
                bestDay != worstDay
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    dayLabel(d.day) + if (isBest) " ⭐" else if (isWorst) " ▼" else "",
                    color = LabelColor,
                    fontSize = 12.sp,
                    fontWeight = if (isBest || isWorst) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(44.dp)
                )
                Text(
                    if (d.testCount > 0) "%.1f".format(d.avgCcrs) else "—",
                    color = when {
                        isBest -> GoldValue
                        isWorst -> RedValue
                        else -> ValueColor
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(52.dp)
                )
                Text(
                    d.testCount.toString(),
                    color = DimColor,
                    fontSize = 11.sp,
                    modifier = Modifier.width(36.dp)
                )
                Text(
                    if (d.games > 0) {
                        "${d.games} · ${d.winRate.roundToInt()}%"
                    } else "—",
                    color = DimColor,
                    fontSize = 11.sp
                )
            }
        }
    }
}
