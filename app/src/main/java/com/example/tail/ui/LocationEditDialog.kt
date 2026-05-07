package com.example.tail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Dialog for manually setting the location for a given day.
 * Shows a text field pre-filled with the current location (if any),
 * and a scrollable list of previously-entered locations to pick from.
 *
 * The suggestion list is filtered in real-time as the user types, making
 * it easy to find and tap an existing location. Selecting a suggestion
 * fills the text field with that value (and its coords are already stored).
 */
@Composable
fun LocationEditDialog(
    currentLocation: String?,
    suggestions: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentLocation ?: "") }

    // Filter suggestions to those containing the current text (case-insensitive).
    // When the field is blank, show all suggestions.
    val filteredSuggestions = remember(text, suggestions) {
        val query = text.trim().lowercase()
        if (query.isEmpty()) suggestions
        else suggestions.filter { it.lowercase().contains(query) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1A1A2E),
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Set location",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Location", color = Color(0xFF888888)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF44BBFF),
                        unfocusedBorderColor = Color(0xFF444444)
                    )
                )

                // Filtered suggestions from history
                if (filteredSuggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (text.isBlank()) "Previous locations" else "Matching locations",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredSuggestions.forEach { suggestion ->
                            Text(
                                text = suggestion,
                                color = Color(0xFFAADDFF),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { text = suggestion }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            )
                            HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = { onConfirm(text.trim()) },
                        enabled = text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003355))
                    ) {
                        Text("Save", color = Color(0xFF44BBFF))
                    }
                }
            }
        }
    }
}
