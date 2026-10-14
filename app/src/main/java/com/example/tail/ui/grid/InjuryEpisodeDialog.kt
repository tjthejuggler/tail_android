package com.example.tail.ui.grid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.InjuryEpisode
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.resolveInjuryEpisode
import com.example.tail.ui.viewmodel.startInjuryEpisode
import com.example.tail.ui.viewmodel.updateInjuryEpisode
import com.example.tail.ui.viewmodel.recordEpisodeDaySeverity
import java.time.LocalDate

/** Injury type options (machine key → display label). */
val INJURY_TYPES = listOf(
    "pulled_muscle" to "Pulled muscle",
    "sprain" to "Sprain",
    "cut" to "Cut",
    "soreness" to "Soreness",
    "other" to "Other"
)

/** Body-part options (machine key → display label). */
val INJURY_BODY_PARTS = listOf(
    "upper_leg" to "Upper leg",
    "leg" to "Leg",
    "back" to "Back",
    "shoulder" to "Shoulder",
    "hand" to "Hand",
    "arm" to "Arm",
    "foot" to "Foot",
    "neck" to "Neck",
    "other" to "Other"
)

val INJURY_SIDES = listOf("left" to "Left", "right" to "Right", "na" to "N/A")

/**
 * Injury (episode) dialog.
 *
 * NEW EPISODE mode ([openEpisode] == null): the 5-field schema
 * (type / body part [+ free-text "other"] / side / severity 1–5 / note),
 * created only on onset — never repeated daily.
 *
 * UPDATE EPISODE mode ([openEpisode] != null): dedupe enforcement — editing
 * severity/note plus the explicit "Mark resolved" action. No new row is
 * created while an episode is open.
 */
@Composable
fun InjuryEpisodeDialog(
    habitName: String,
    openEpisode: InjuryEpisode?,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit
) {
    val isUpdate = openEpisode != null

    var type by remember { mutableStateOf(openEpisode?.type ?: "pulled_muscle") }
    var bodyPart by remember { mutableStateOf(openEpisode?.bodyPart ?: "upper_leg") }
    var bodyPartDetail by remember { mutableStateOf(openEpisode?.bodyPartDetail ?: "") }
    var side by remember { mutableStateOf(openEpisode?.side ?: "left") }
    var severity by remember { mutableStateOf(openEpisode?.severity?.toFloat() ?: 3f) }
    var severityKnown by remember { mutableStateOf(openEpisode?.severity != null) }
    var note by remember { mutableStateOf(openEpisode?.note ?: "") }
    var showResolve by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isUpdate) "Update episode" else "New injury episode", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                if (isUpdate && openEpisode != null) {
                    Text(
                        "Onset ${openEpisode.onsetDate}" +
                            (openEpisode.resolvedDate?.let { " · resolved $it" } ?: " · OPEN"),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Text("Type", fontSize = 13.sp)
                ChipFlow(INJURY_TYPES, type) { type = it }
                Spacer(Modifier.height(8.dp))

                Text("Body part", fontSize = 13.sp)
                ChipFlow(INJURY_BODY_PARTS, bodyPart) { bodyPart = it }
                if (bodyPart == "other") {
                    OutlinedTextField(
                        value = bodyPartDetail,
                        onValueChange = { bodyPartDetail = it },
                        label = { Text("Which part?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(8.dp))

                Text("Side", fontSize = 13.sp)
                ChipFlow(INJURY_SIDES, side) { side = it }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Severity (1–10)", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Checkbox(checked = severityKnown, onCheckedChange = { severityKnown = it })
                    Text(if (severityKnown) "${severity.toInt()}" else "unknown", fontSize = 13.sp)
                }
                if (severityKnown) {
                    Slider(
                        value = severity,
                        onValueChange = { severity = it },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Per-day intensity rating — builds the severity-over-time
                // series shown in the graph.
                if (isUpdate && openEpisode != null) {
                    Spacer(Modifier.height(8.dp))
                    var daySeverity by remember(openEpisode.id) {
                        mutableStateOf(
                            (openEpisode.severityByDay?.get(LocalDate.now().toString())
                                ?: openEpisode.severity ?: 3).toFloat()
                        )
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("How does it feel TODAY? (1–10)", fontSize = 13.sp)
                    Slider(
                        value = daySeverity,
                        onValueChange = { daySeverity = it },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.recordEpisodeDaySeverity(
                                habitName, openEpisode.id,
                                LocalDate.now(), daySeverity.toInt().coerceIn(1, 10)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Log today's rating: ${daySeverity.toInt()}") }
                }

                if (isUpdate && openEpisode?.resolvedDate == null && !showResolve) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showResolve = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Mark resolved…") }
                }
                if (showResolve) {
                    Spacer(Modifier.height(8.dp))
                    Text("Resolve as of today (${LocalDate.now()})?", fontSize = 13.sp)
                    Row {
                        Button(
                            onClick = {
                                viewModel.resolveInjuryEpisode(habitName, openEpisode!!.id, LocalDate.now())
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Confirm resolve") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { showResolve = false }) { Text("Cancel") }
                    }
                }
            }
        },
        confirmButton = {
            if (!showResolve) TextButton(onClick = {
                val sev = if (severityKnown) severity.toInt().coerceIn(1, 10) else null
                if (isUpdate && openEpisode != null) {
                    viewModel.updateInjuryEpisode(
                        habitName, openEpisode.id, type, bodyPart, bodyPartDetail, side, sev, note
                    )
                } else {
                    viewModel.startInjuryEpisode(
                        habitName, LocalDate.now(), type, bodyPart, bodyPartDetail, side, sev, note
                    )
                }
                onDismiss()
            }) { Text(if (isUpdate) "Save" else "Start episode") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChipFlow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (key, label) ->
                    FilterChip(
                        selected = selected == key,
                        onClick = { onSelect(key) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
    }
}
