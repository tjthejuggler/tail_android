package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.widget.ChessReadinessV3Store
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness — V3 section of the Readiness Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * The reflex + Puzzle Rush Survival gate: verdict distribution, pass rate,
 * puzzles-solved vs the dynamic target, per-puzzle solve latency and the
 * reflex (2-min PVT-B) aggregates. Renders nothing until the first v3
 * record exists — same rule as the v2 sections.
 */

private val SectionTitleColor = Color(0xFFF2A65A)
private val LabelColor = Color(0xFFE6C79C)
private val ValueColor = Color.White
private val DimColor = Color(0xFF9C8B77)
private val SectionBg = Color(0xFF231A10)
private val DividerColor = Color(0xFF3A2E1E)
private val GreenValue = Color(0xFF80FF80)
private val RedValue = Color(0xFFFF8080)
private val GoldValue = Color(0xFFFFC24D)

private val EVENT_FMT = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")

private fun fmtTime(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(EVENT_FMT)

private fun verdictLabel(v: String): String = when (v) {
    "PASS" -> "GATE PASSED"
    "FAIL_REFLEX" -> "REFLEX FAIL (rest lockout)"
    "FAIL_STRIKE" -> "STRIKE"
    "FAIL_TIMEOUT" -> "TIMEOUT (5-min cap)"
    else -> v
}

private fun verdictColor(v: String): Color =
    if (v == "PASS") GreenValue else RedValue

@Composable
private fun V3StatRow(label: String, value: String, valueColor: Color = ValueColor) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LabelColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun V3PregameSection(
    results: List<ChessReadinessV3Store.V3ResultRecord>,
    events: List<ChessReadinessV3Store.SurvivalEventRecord>,
    startExpanded: Boolean
) {
    if (results.isEmpty()) return
    var expanded by remember { mutableStateOf(startExpanded) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(SectionBg, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "♟ Pre-Game V3 — Reflex + Survival Gate",
                color = SectionTitleColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(if (expanded) "▾" else "▸", color = SectionTitleColor, fontSize = 14.sp)
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))

            val total = results.size
            val passes = results.count { it.verdict == "PASS" }
            val strikes = results.count { it.verdict == "FAIL_STRIKE" }
            val timeouts = results.count { it.verdict == "FAIL_TIMEOUT" }
            val reflexFails = results.count { it.verdict == "FAIL_REFLEX" }
            val passRate = if (total > 0) passes * 100.0 / total else 0.0

            V3StatRow("Runs", "$total")
            V3StatRow("Gate passed", "$passes (${"%.0f".format(passRate)}%)", GreenValue)
            V3StatRow("Strike failures", "$strikes", RedValue)
            V3StatRow("Timeout failures", "$timeouts", RedValue)
            V3StatRow("Reflex lockouts", "$reflexFails", RedValue)

            // Reflex aggregates over runs that reached the reflex summary.
            val reflexRuns = results.filter { it.verdict != "FAIL_REFLEX" }
            val meanRt = reflexRuns.mapNotNull { it.reflexMeanRtMs }.takeIf { it.isNotEmpty() }
                ?.average()
            meanRt?.let {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                V3StatRow("Reflex mean RT (avg)", "%.0f ms".format(it), GoldValue)
                V3StatRow(
                    "Reflex lapses / false starts (avg)",
                    "%.1f / %.1f".format(
                        reflexRuns.map { it.reflexLapses.toDouble() }.average().takeIf { reflexRuns.isNotEmpty() } ?: 0.0,
                        reflexRuns.map { it.reflexFalseStarts.toDouble() }.average().takeIf { reflexRuns.isNotEmpty() } ?: 0.0
                    )
                )
            }

            // Survival aggregates over runs that entered the gate.
            val survivalRuns = results.filter { it.verdict != "FAIL_REFLEX" }
            if (survivalRuns.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                V3StatRow(
                    "Puzzles solved (avg)",
                    "%.1f".format(survivalRuns.map { it.puzzlesPassed.toDouble() }.average())
                )
                V3StatRow(
                    "vs target (avg)",
                    "%.1f".format(survivalRuns.map { it.target.toDouble() }.average()),
                    DimColor
                )
                val passDurations = results.filter { it.verdict == "PASS" }
                    .filter { it.survivalDurationMs > 0 }
                passDurations.takeIf { it.isNotEmpty() }?.let {
                    V3StatRow(
                        "Winning run time (avg)",
                        "%.0f s".format(it.map { r -> r.survivalDurationMs / 1000.0 }.average()),
                        GoldValue
                    )
                }
            }

            // Per-puzzle latency telemetry (PASS events only).
            val passEvents = events.filter { it.verdict == "PASS" && it.puzzleDurationMs > 0 }
            if (passEvents.isNotEmpty()) {
                val avg = passEvents.map { it.puzzleDurationMs.toDouble() }.average()
                val slowest = passEvents.maxOf { it.puzzleDurationMs }
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                V3StatRow("Puzzle solve latency (avg)", "%.1f s".format(avg / 1000.0))
                V3StatRow("Slowest solve", "%.1f s".format(slowest / 1000.0), DimColor)
            }

            // Recent runs list (latest 8).
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Recent runs",
                color = SectionTitleColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            results.sortedByDescending { it.timestamp }.take(8).forEach { r ->
                Text(
                    "${fmtTime(r.timestamp)} — ${verdictLabel(r.verdict)} · " +
                        "${r.puzzlesPassed}/${r.target} puzzles",
                    color = verdictColor(r.verdict),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}
