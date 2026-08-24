package com.example.tail.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.WEIGHT_UNIT_KG
import com.example.tail.data.WEIGHT_UNIT_LB
import com.example.tail.data.WeightsDayValues
import com.example.tail.data.formatWeightTenths
import com.example.tail.data.gramsToDisplayTenths
import com.example.tail.data.kgToGrams
import com.example.tail.data.lbToGrams

/**
 * Input dialog for the "Weights" habit type: the exercise/machine name,
 * a kg/lb unit toggle, the weight amount, the rep count, and a
 * machine/free toggle.
 *
 * The exercise name is a free-text field with quick-choice chips for every
 * name used before on this habit; the most recent one is pre-filled so a
 * repeat set is one tap away.
 *
 * The entered weight is converted to GRAMS (the canonical storage unit — both
 * kg and lb inputs round-trip losslessly at 1 g precision) before
 * [onConfirm] fires. The graph converts grams to whatever display unit is
 * selected there, so logging in lb while the graph shows kg (or vice versa)
 * works with no extra configuration.
 */
@Composable
fun WeightsInputDialog(
    habitName: String,
    /** Initial unit for the dialog's own toggle ("kg" or "lb"); defaults to the graph's unit. */
    defaultUnit: String = WEIGHT_UNIT_KG,
    /** Previously used exercise/machine names on this habit, most recent first. */
    recentExercises: List<String> = emptyList(),
    onConfirm: (weightGrams: Int, reps: Int, machine: Boolean, exerciseName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(if (defaultUnit == WEIGHT_UNIT_LB) WEIGHT_UNIT_LB else WEIGHT_UNIT_KG) }
    var machine by remember { mutableStateOf(true) }
    // Auto-fill with the most recently used exercise name for quick repeat sets
    var exerciseName by remember { mutableStateOf(recentExercises.firstOrNull() ?: "") }
    var weightText by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }

    // Accept digits with a decimal separator (',' normalized to '.' on parse)
    val weight = weightText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val reps = repsText.toIntOrNull() ?: 0
    val canConfirm = weight > 0.0 || reps > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = habitName, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── Exercise / machine name ───────────────────────────────
                Text(
                    text = "Exercise / Machine",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it.take(40) },
                    singleLine = true,
                    placeholder = { Text("e.g. Bench Press", fontSize = 12.sp, color = Color(0xFF666666)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )

                // Quick choices — every name used before on this habit
                if (recentExercises.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        recentExercises.forEach { name ->
                            UnitToggleOption(
                                label = name,
                                selected = exerciseName == name,
                                onClick = { exerciseName = name }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Unit toggle (kg | lb) ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Unit:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    UnitToggleOption("kg", unit == WEIGHT_UNIT_KG) { unit = WEIGHT_UNIT_KG }
                    UnitToggleOption("lb", unit == WEIGHT_UNIT_LB) { unit = WEIGHT_UNIT_LB }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Machine / Free toggle ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Type:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    UnitToggleOption("Machine", machine) { machine = true }
                    UnitToggleOption("Free", !machine) { machine = false }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Weight amount (decimal, in the selected unit) ────────
                Text(
                    text = "Weight ($unit)",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { raw ->
                        // digits only, with at most one decimal separator
                        val filtered = raw.filter { c -> c.isDigit() || c == '.' || c == ',' }
                        val firstSep = filtered.indexOfAny(charArrayOf('.', ','))
                        weightText = if (firstSep >= 0) {
                            filtered.substring(0, firstSep + 1) +
                                filtered.substring(firstSep + 1).filter { it.isDigit() }
                        } else filtered
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.width(140.dp).height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ── Reps (integer) ────────────────────────────────────────
                Text(
                    text = "Reps",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { raw -> repsText = raw.filter { it.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(140.dp).height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val grams = if (unit == WEIGHT_UNIT_LB) lbToGrams(weight) else kgToGrams(weight)
                    if (grams > 0 || reps > 0) onConfirm(grams, reps, machine, exerciseName.trim())
                },
                enabled = canConfirm
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Small pill-shaped toggle option used for the unit, machine/free and quick-choice rows. */
@Composable
private fun UnitToggleOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = if (selected) ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF1A3A1A),
            contentColor = Color(0xFF88DD88)
        ) else ButtonDefaults.outlinedButtonColors()
    ) {
        Text(label, fontSize = 11.sp)
    }
}

/** Keeps digits with at most one decimal separator (',' or '.'). */
private fun filterDecimal(raw: String): String {
    val filtered = raw.filter { c -> c.isDigit() || c == '.' || c == ',' }
    val firstSep = filtered.indexOfAny(charArrayOf('.', ','))
    return if (firstSep >= 0) {
        filtered.substring(0, firstSep + 1) +
            filtered.substring(firstSep + 1).filter { it.isDigit() }
    } else filtered
}

/**
 * Edit-mode dialog for one day's weights data — mirrors the logging dialog
 * ([WeightsInputDialog]): exercise name with quick choices, kg/lb toggle,
 * Machine/Free toggle, one weight field and one reps field. Saving
 * OVERWRITES the day's values for the selected type (set semantics —
 * empty/zero clears that type's slots); the other type is untouched.
 * "Delete day" removes ALL of the day's weights data, its increment and
 * its timestamps (see HabitViewModel.deleteWeightsDay).
 */
@Composable
fun WeightsDayEditorDialog(
    habitName: String,
    initial: WeightsDayValues,
    /** Previously used exercise/machine names on this habit, most recent first. */
    recentExercises: List<String> = emptyList(),
    /** Display unit for the weight field ("kg" or "lb"); defaults to the graph's unit. */
    defaultUnit: String = WEIGHT_UNIT_KG,
    onConfirm: (values: WeightsDayValues, exerciseName: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(if (defaultUnit == WEIGHT_UNIT_LB) WEIGHT_UNIT_LB else WEIGHT_UNIT_KG) }
    // Default the toggle to the type that has data for the day (machine wins ties)
    val machineHasData = initial.machineWeightGrams > 0 || initial.machineReps > 0
    val freeHasData = initial.freeWeightGrams > 0 || initial.freeReps > 0
    var machine by remember { mutableStateOf(machineHasData || !freeHasData) }

    // Auto-fill with the most recently used exercise name, like the logging dialog
    var exerciseName by remember { mutableStateOf(recentExercises.firstOrNull() ?: "") }

    // Pre-fill from stored grams, converted to the initial display unit
    // (whole values render without a decimal, others with one).
    fun gramsToText(grams: Int): String {
        if (grams <= 0) return ""
        val tenths = gramsToDisplayTenths(grams, unit)
        return if (tenths % 10 == 0) (tenths / 10).toString() else formatWeightTenths(tenths)
    }
    fun repsToText(reps: Int): String = if (reps > 0) reps.toString() else ""
    var weightText by remember { mutableStateOf(gramsToText(if (machine) initial.machineWeightGrams else initial.freeWeightGrams)) }
    var repsText by remember { mutableStateOf(repsToText(if (machine) initial.machineReps else initial.freeReps)) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Switching Machine ↔ Free re-fills the fields from that type's day values
    fun refillFor(isMachine: Boolean) {
        weightText = gramsToText(if (isMachine) initial.machineWeightGrams else initial.freeWeightGrams)
        repsText = repsToText(if (isMachine) initial.machineReps else initial.freeReps)
    }

    fun parseWeight(text: String): Double = text.replace(',', '.').toDoubleOrNull() ?: 0.0
    fun parseReps(text: String): Int = text.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "🏋️ $habitName", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Overwrites the day's values for the selected type — empty clears it.",
                    fontSize = 11.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // ── Exercise / machine name ───────────────────────────────
                Text(
                    text = "Exercise / Machine",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it.take(40) },
                    singleLine = true,
                    placeholder = { Text("e.g. Bench Press", fontSize = 12.sp, color = Color(0xFF666666)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )
                if (recentExercises.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        recentExercises.forEach { name ->
                            UnitToggleOption(
                                label = name,
                                selected = exerciseName == name,
                                onClick = { exerciseName = name }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Unit toggle (kg | lb) ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Unit:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    UnitToggleOption("kg", unit == WEIGHT_UNIT_KG) { unit = WEIGHT_UNIT_KG }
                    UnitToggleOption("lb", unit == WEIGHT_UNIT_LB) { unit = WEIGHT_UNIT_LB }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Machine / Free toggle ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Type:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    UnitToggleOption("Machine", machine) { if (!machine) { machine = true; refillFor(true) } }
                    UnitToggleOption("Free", !machine) { if (machine) { machine = false; refillFor(false) } }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Weight amount (decimal, in the selected unit) ────────
                Text(
                    text = "Weight ($unit)",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { raw -> weightText = filterDecimal(raw) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.width(140.dp).height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ── Reps (integer) ────────────────────────────────────────
                Text(
                    text = "Reps",
                    fontSize = 12.sp,
                    color = Color(0xFFBBBBBB)
                )
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { raw -> repsText = raw.filter { it.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(140.dp).height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val toGrams: (Double) -> Int = { v ->
                    if (unit == WEIGHT_UNIT_LB) lbToGrams(v) else kgToGrams(v)
                }
                val enteredGrams = toGrams(parseWeight(weightText))
                val enteredReps = parseReps(repsText)
                val newValues = if (machine) initial.copy(
                    machineWeightGrams = enteredGrams,
                    machineReps = enteredReps
                ) else initial.copy(
                    freeWeightGrams = enteredGrams,
                    freeReps = enteredReps
                )
                onConfirm(newValues, exerciseName.trim())
            }) {
                Text("OK")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete day", color = Color(0xFFCC6666), fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete weights data?", fontSize = 15.sp) },
            text = {
                Text(
                    "Removes this day's machine/free weights and reps, the day's increment and its timestamps.",
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF662222))
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}
