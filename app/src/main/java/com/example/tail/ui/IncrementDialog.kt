package com.example.tail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val QUICK_AMOUNTS = listOf(1, 5, 10, 30, 50)

/**
 * Dialog for entering a custom increment amount for widget-style habits.
 * Shows quick-add buttons (+1, +5, +10, +30, +50) and a text field.
 */
@Composable
fun IncrementDialog(
    habitName: String,
    currentCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    val parsedAmount = inputText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = habitName, fontSize = 16.sp) },
        text = {
            Column {
                Text(text = "Today: $currentCount", fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Quick add:", fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    QUICK_AMOUNTS.forEach { amount ->
                        OutlinedButton(
                            onClick = { inputText = amount.toString() },
                            modifier = Modifier
                                .weight(1f)
                                .padding(0.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                        ) {
                            Text("+$amount", fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amount = parsedAmount ?: 1
                    if (amount > 0) onConfirm(amount)
                },
                enabled = parsedAmount != null && parsedAmount > 0
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
