package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog

/**
 * Dialog shown when the user taps a habit that has "text input" enabled.
 *
 * - Always shows a free-text [OutlinedTextField] for the user to type an entry.
 * - When [showOptions] is true AND [options] is non-empty, also shows a scrollable
 *   list of all unique past entries. Tapping one populates the text field.
 * - OK saves the entry (calls [onConfirm]); Cancel dismisses without saving.
 */
@Composable
fun TextInputDialog(
    habitName: String,
    showOptions: Boolean,
    options: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────────────
            Text(
                text = habitName,
                color = Color(0xFFFFD700),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Text input field ───────────────────────────────────────────────
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Entry", color = Color(0xFF888888)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFAA00)
                )
            )

            // ── Past options list (only when showOptions = true and list non-empty) ──
            if (showOptions && options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Past entries",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                ) {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(options) { option ->
                            Text(
                                text = option,
                                color = Color(0xFFCCCCCC),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { inputText = option }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                            HorizontalDivider(
                                color = Color(0xFF2A2A2A),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Buttons ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = inputText.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5A3A00),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    )
                ) {
                    Text("OK", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}
