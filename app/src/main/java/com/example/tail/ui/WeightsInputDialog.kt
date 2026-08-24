package com.example.tail.ui

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
import com.example.tail.data.kgToGrams
import com.example.tail.data.lbToGrams

/**
 * Input dialog for the "Weights" habit type: a kg/lb unit toggle, the weight
 * amount, the rep count, and a machine/free toggle.
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
    onConfirm: (weightGrams: Int, reps: Int, machine: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(if (defaultUnit == WEIGHT_UNIT_LB) WEIGHT_UNIT_LB else WEIGHT_UNIT_KG) }
    var machine by remember { mutableStateOf(true) }
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
                    onValueChange = { raw -> repsText = raw.filter { c -> c.isDigit() } },
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
                    if (grams > 0 || reps > 0) onConfirm(grams, reps, machine)
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

/** Small pill-shaped toggle option used for the unit and machine/free rows. */
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
