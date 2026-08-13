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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
 * - Shows existing text entries for the current day with edit/delete capability.
 * - Always shows a free-text [OutlinedTextField] for the user to type a new entry.
 * - When [showOptions] is true AND [options] is non-empty, also shows a scrollable
 *   list of all unique past entries with **multi-select checkboxes**. The user can
 *   select as many as desired; each selected option is saved as a separate entry.
 *   A "+" button next to the text field lets the user add a freshly-typed value as
 *   a new checked option **without closing the dialog**, so they can keep selecting
 *   more options before confirming.
 * - A time picker lets the user associate a specific time-of-day with the entries
 *   instead of defaulting to noon for past dates.
 * - OK saves all entries (selected options + free text if non-empty) with the
 *   chosen time; Cancel dismisses without saving.
 *
 * @param todayEntries Pairs of (timestamp, text) for entries already logged today.
 * @param initialHour Starting hour for the time picker.
 * @param initialMinute Starting minute for the time picker.
 * @param onConfirm Called with (entries, hour, minute) when the user confirms.
 * @param onEdit Called when the user edits an existing entry: (oldTimestamp, newText).
 * @param onDelete Called when the user deletes an existing entry: (timestamp).
 */
@Composable
fun TextInputDialog(
    habitName: String,
    showOptions: Boolean,
    options: List<String>,
    todayEntries: List<Pair<String, String>> = emptyList(),
    initialHour: Int = java.time.LocalTime.now().hour,
    initialMinute: Int = java.time.LocalTime.now().minute,
    initialText: String = "",
    suggestionLabel: String = "",
    onConfirm: (List<String>, Int, Int) -> Unit,
    onDismiss: () -> Unit,
    onEdit: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {}
) {
    var inputText by remember { mutableStateOf(initialText) }
    var editingTimestamp by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    // Multi-select state for past options
    val selectedOptions = remember { mutableStateMapOf<String, Boolean>() }

    // Time picker state — wheel-based
    var selectedHour by remember { mutableIntStateOf(initialHour) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }
    var showTimePicker by remember { mutableStateOf(false) }

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

            // ── Suggestion label (from desktop bridge) ──────────────────────
            if (suggestionLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = suggestionLabel,
                    color = Color(0xFF81C784),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Today's existing entries ───────────────────────────────────────
            if (todayEntries.isNotEmpty() && editingTimestamp == null) {
                Text(
                    text = "Today's entries",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                ) {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(todayEntries) { (timestamp, text) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Display text or placeholder if empty
                                Text(
                                    text = if (text.isBlank()) "(no text)" else text,
                                    color = if (text.isBlank()) Color(0xFF666666) else Color(0xFFCCCCCC),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                // Edit button - always shown for each increment
                                TextButton(
                                    onClick = {
                                        editingTimestamp = timestamp
                                        editingText = text
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                                    )
                                ) {
                                    Text("✎", color = Color(0xFF888888), fontSize = 14.sp)
                                }
                                // Delete button
                                TextButton(
                                    onClick = { onDelete(timestamp) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                                    )
                                ) {
                                    Text("✕", color = Color(0xFF666666), fontSize = 13.sp)
                                }
                            }
                            HorizontalDivider(
                                color = Color(0xFF2A2A2A),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Edit mode for an existing entry ─────────────────────────────────
            if (editingTimestamp != null) {
                Text(
                    text = "Edit entry",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
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
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        editingTimestamp = null
                        editingText = ""
                    }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Button(
                        onClick = {
                            val trimmed = editingText.trim()
                            if (trimmed.isNotEmpty() && editingTimestamp != null) {
                                onEdit(editingTimestamp!!, trimmed)
                                editingTimestamp = null
                                editingText = ""
                            }
                        },
                        enabled = editingText.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5A3A00),
                            disabledContainerColor = Color(0xFF2A2A2A)
                        )
                    ) {
                        Text("Save", color = Color(0xFFFFAA00))
                    }
                }
            } else {
                // ── Time picker ────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Time",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    val timeLabel = String.format("%02d:%02d", selectedHour, selectedMinute)
                    TextButton(
                        onClick = { showTimePicker = !showTimePicker },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                        )
                    ) {
                        Text(
                            text = if (showTimePicker) "Done" else timeLabel,
                            color = Color(0xFFFFAA00),
                            fontSize = 13.sp
                        )
                    }
                }

                if (showTimePicker) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        TimeWheelPicker(
                            hour24 = selectedHour,
                            minute = selectedMinute,
                            onTimeChange = { h, m ->
                                selectedHour = h
                                selectedMinute = m
                            },
                            accent = Color(0xFFFFAA00)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // ── Entry input field ───────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("Entry", color = Color(0xFF888888)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFFAA00),
                            unfocusedBorderColor = Color(0xFF555555),
                            cursorColor = Color(0xFFFFAA00)
                        )
                    )
                    // Add button — only when options are shown. Lets the user add
                    // typed text as a new checked option WITHOUT closing the dialog,
                    // so they can continue selecting more options.
                    if (showOptions && options.isNotEmpty()) {
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Button(
                            onClick = {
                                val trimmed = inputText.trim()
                                if (trimmed.isNotEmpty()) {
                                    selectedOptions[trimmed] = true
                                    inputText = ""
                                }
                            },
                            enabled = inputText.trim().isNotEmpty(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp
                            ),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5A3A00),
                                disabledContainerColor = Color(0xFF2A2A2A)
                            )
                        ) {
                            Text("+", color = Color(0xFFFFAA00), fontSize = 18.sp)
                        }
                    }
                }

                // ── Past options list with multi-select ──────────────────────
                if (showOptions && options.isNotEmpty()) {
                    // Merge any newly-typed options (added via the + button) with the
                    // existing past options so they appear checked in the list.
                    val allOptions = selectedOptions.keys.filter { it !in options } + options
                    // Filter options by current input text (case-insensitive contains)
                    val filteredOptions = if (inputText.isBlank()) {
                        allOptions
                    } else {
                        allOptions.filter { it.contains(inputText, ignoreCase = true) }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val selectedCount = selectedOptions.values.count { it }
                    Text(
                        text = if (selectedCount > 0) {
                            "Past entries ($selectedCount selected)"
                        } else {
                            "Past entries (tap to select multiple)"
                        },
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
                        if (filteredOptions.isEmpty()) {
                            Text(
                                text = "No matching entries",
                                color = Color(0xFF555555),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        } else {
                            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                                items(filteredOptions) { option ->
                                    val isChecked = selectedOptions[option] == true
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedOptions[option] = !isChecked
                                            }
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                selectedOptions[option] = checked
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFFFFAA00),
                                                uncheckedColor = Color(0xFF666666),
                                                checkmarkColor = Color(0xFF1E1E1E)
                                            )
                                        )
                                        Text(
                                            text = option,
                                            color = if (isChecked) Color(0xFFFFD700) else Color(0xFFCCCCCC),
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    HorizontalDivider(
                                        color = Color(0xFF2A2A2A),
                                        thickness = 0.5.dp
                                    )
                                }
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
                    // OK is enabled when there's free text OR at least one selected option
                    val trimmedInput = inputText.trim()
                    val hasSelections = selectedOptions.values.any { it }
                    val hasFreeText = trimmedInput.isNotEmpty()
                    Button(
                        onClick = {
                            val entries = mutableListOf<String>()
                            // Add selected options first
                            selectedOptions.filterValues { it }.keys.forEach { opt ->
                                entries.add(opt)
                            }
                            // Add free text if non-empty (avoid exact duplicates of selected options)
                            if (hasFreeText && trimmedInput !in entries) {
                                entries.add(trimmedInput)
                            }
                            if (entries.isNotEmpty()) {
                                onConfirm(entries, selectedHour, selectedMinute)
                            }
                        },
                        enabled = hasFreeText || hasSelections,
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
}
