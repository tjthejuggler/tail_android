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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.DEFAULT_CUSTOM_INPUT_AMOUNTS

/**
 * Dialog for entering a custom increment amount for widget-style habits.
 *
 * Shows quick-add buttons from [quickAmounts] (defaults to [DEFAULT_CUSTOM_INPUT_AMOUNTS])
 * and, when [recentAmounts] is non-empty, a second row of "recent" buttons showing the
 * up-to-3 most recently used amounts for this habit.
 */
@Composable
fun IncrementDialog(
    habitName: String,
    currentCount: Int,
    quickAmounts: List<Int> = DEFAULT_CUSTOM_INPUT_AMOUNTS,
    recentAmounts: List<Int> = emptyList(),
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

                // ── Configured quick-add amounts ──────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickAmounts.forEach { amount ->
                        OutlinedButton(
                            onClick = {
                                val current = inputText.toIntOrNull() ?: 0
                                inputText = (current + amount).toString()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .padding(0.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                        ) {
                            Text("+$amount", fontSize = 11.sp)
                        }
                    }
                }

                // ── Recent amounts row (only shown when there are recent entries) ──
                if (recentAmounts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Recent:", fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        recentAmounts.forEach { amount ->
                            Button(
                                onClick = {
                                    val current = inputText.toIntOrNull() ?: 0
                                    inputText = (current + amount).toString()
                                },
                                modifier = Modifier
                                    .padding(0.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF3A2800)
                                )
                            ) {
                                Text("+$amount", fontSize = 11.sp, color = Color(0xFFFFCC44))
                            }
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
