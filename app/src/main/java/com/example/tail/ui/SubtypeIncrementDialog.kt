package com.example.tail.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QUICK_AMOUNTS = listOf(1, 5, 10)

/**
 * Dialog for entering per-subtype increments for a subtyped habit.
 * Shows one row per subtype with a text field and quick-add buttons.
 * [currentTotal] is the current total count for today (displayed at top).
 * [currentBreakdown] is the existing per-subtype counts for today (read-only display).
 * [onConfirm] receives a map of subtype → increment amount (only non-zero entries).
 */
@Composable
fun SubtypeIncrementDialog(
    habitName: String,
    subtypes: List<String>,
    currentTotal: Int,
    currentBreakdown: Map<String, Int>,
    onConfirm: (Map<String, Int>) -> Unit,
    onDismiss: () -> Unit
) {
    // State: one text field per subtype
    val inputs = remember { subtypes.associateWith { mutableStateOf("") } }

    val totalIncrement = inputs.values.sumOf { it.value.toIntOrNull() ?: 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = habitName, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "Today: $currentTotal" +
                        if (totalIncrement > 0) " → ${currentTotal + totalIncrement}" else "",
                    fontSize = 14.sp
                )

                // Show current breakdown if any
                if (currentBreakdown.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val breakdownText = currentBreakdown.entries
                        .filter { it.value > 0 }
                        .joinToString(", ") { "${it.key}: ${it.value}" }
                    if (breakdownText.isNotEmpty()) {
                        Text(
                            text = "($breakdownText)",
                            fontSize = 11.sp,
                            color = androidx.compose.ui.graphics.Color(0xFF888888)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // One row per subtype
                subtypes.forEach { subtype ->
                    val inputState = inputs[subtype]!!
                    SubtypeRow(
                        subtypeName = subtype,
                        inputText = inputState.value,
                        onInputChange = { inputState.value = it.filter { c -> c.isDigit() } },
                        onQuickAdd = { amount ->
                            val current = inputState.value.toIntOrNull() ?: 0
                            inputState.value = (current + amount).toString()
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val result = inputs.mapValues { (_, state) -> state.value.toIntOrNull() ?: 0 }
                        .filter { it.value > 0 }
                    if (result.isNotEmpty()) onConfirm(result)
                },
                enabled = totalIncrement > 0
            ) {
                Text("OK (+$totalIncrement)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SubtypeRow(
    subtypeName: String,
    inputText: String,
    onInputChange: (String) -> Unit,
    onQuickAdd: (Int) -> Unit
) {
    Column {
        Text(
            text = subtypeName,
            fontSize = 12.sp,
            color = androidx.compose.ui.graphics.Color(0xFFBBBBBB)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(64.dp).height(48.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            QUICK_AMOUNTS.forEach { amount ->
                OutlinedButton(
                    onClick = { onQuickAdd(amount) },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("+$amount", fontSize = 11.sp)
                }
            }
        }
    }
}
