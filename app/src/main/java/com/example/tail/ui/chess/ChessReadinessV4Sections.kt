package com.example.tail.ui.chess

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.widget.ChessReadinessV4Store
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 🟢 V4 Green Light section for the Chess Readiness stats screen.
 *
 * Summarises the v4 calibration event log: assessments by level, gate
 * pass rates (reflex / survival), override count, and the recent event
 * trail with the signals that drove each non-green decision. Renders
 * nothing until the first v4 event exists.
 */
@Composable
fun V4GreenLightSection(
    events: List<ChessReadinessV4Store.V4Event>,
    startExpanded: Boolean
) {
    if (events.isEmpty()) return
    var expanded by remember { mutableStateOf(startExpanded) }

    val greens = events.count { it.level == "GREEN" && !it.overridden }
    val yellows = events.count { it.level == "YELLOW" }
    val reds = events.count { it.level == "RED" }
    val overrides = events.count { it.overridden }
    val reflexRuns = events.filter { it.gateType == "reflex" && it.gatePassed != null }
    val reflexPasses = reflexRuns.count { it.gatePassed == true }
    val survivalRuns = events.filter { it.gateType == "survival" && it.gatePassed != null }
    val survivalPasses = survivalRuns.count { it.gatePassed == true }
    val reflexMedians = events.mapNotNull { it.reflexMs }
    val fmt = DateTimeFormatter.ofPattern("d MMM · HH:mm")

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    "🟢 V4 Green Light — calibration",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "−" else "+", fontSize = 18.sp)
            }
            Text(
                "Green by default. Gates fire only when validated negative " +
                    "signals stack; every outcome is logged for calibration.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Stat("Assessments", "${events.size} · ${greens} green / ${yellows} yellow / ${reds} red")
                    Stat(
                        "Instant-green rate",
                        if (events.isEmpty()) "—" else
                            "%d%%".format(100 * greens / events.size) + " (no test needed)"
                    )
                    Stat(
                        "Reflex gate (concern 2)",
                        if (reflexRuns.isEmpty()) "not run yet"
                        else "$reflexPasses/${reflexRuns.size} passed"
                    )
                    Stat(
                        "Survival gate (concern 3+)",
                        if (survivalRuns.isEmpty()) "not run yet"
                        else "$survivalPasses/${survivalRuns.size} passed"
                    )
                    Stat("Overrides (play anyway)", "$overrides")
                    if (reflexMedians.isNotEmpty()) {
                        Stat(
                            "Median tap (avg of runs)",
                            "%.0f ms".format(reflexMedians.average())
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Recent events", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    events.asReversed().take(15).forEach { e ->
                        val when_ = Instant.ofEpochMilli(e.timestampMs)
                            .atZone(ZoneId.systemDefault()).format(fmt)
                        val gate = when {
                            e.gateType == "reflex" && e.gatePassed != null ->
                                " · reflex " + (if (e.gatePassed == true) "PASS" else "FAIL")
                            e.gateType == "survival" && e.gatePassed != null ->
                                " · survival " + (if (e.gatePassed == true) "PASS" else "FAIL")
                            e.gateType.isNotEmpty() -> " · ${e.gateType}"
                            else -> ""
                        }
                        val over = if (e.overridden) " · OVERRIDE" else ""
                        Text(
                            "$when_ — ${e.level} (concern ${e.concern})$gate$over",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
