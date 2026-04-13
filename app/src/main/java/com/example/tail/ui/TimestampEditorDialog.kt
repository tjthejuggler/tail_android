package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dialog for viewing, editing, deleting, and adding habit increment timestamps
 * for a specific habit on a specific day.
 *
 * @param habitName The habit being edited
 * @param timestamps Current list of "HH:mm:ss" timestamps for today
 * @param onUpdateTimestamp Called with (index, newTime) when a timestamp is edited
 * @param onDeleteTimestamp Called with (index) when a timestamp is deleted
 * @param onAddTimestamp Called with (time) when a new timestamp is added
 * @param onDismiss Called when the dialog is dismissed
 */
@Composable
fun TimestampEditorDialog(
    habitName: String,
    timestamps: List<String>,
    onUpdateTimestamp: (Int, String) -> Unit,
    onDeleteTimestamp: (Int) -> Unit,
    onAddTimestamp: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editingIndex by remember { mutableStateOf(-1) }
    var editingText by remember { mutableStateOf("") }
    var showAddField by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "🕐 Timestamps — $habitName",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                Text(
                    text = "${timestamps.size} timestamped increment${if (timestamps.size != 1) "s" else ""}",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (timestamps.isEmpty()) {
                    Text(
                        text = "No timestamps recorded for today.",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        itemsIndexed(timestamps) { index, time ->
                            if (editingIndex == index) {
                                // Inline edit mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editingText,
                                        onValueChange = { editingText = it },
                                        label = { Text("HH:mm:ss", fontSize = 10.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val validated = validateTimeString(editingText)
                                            if (validated != null) {
                                                onUpdateTimestamp(index, validated)
                                                editingIndex = -1
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A00)),
                                        modifier = Modifier.height(36.dp)
                                    ) {
                                        Text("✓", fontSize = 12.sp, color = Color(0xFF88FF88))
                                    }
                                    TextButton(
                                        onClick = { editingIndex = -1 }
                                    ) {
                                        Text("✕", fontSize = 12.sp, color = Color(0xFF888888))
                                    }
                                }
                            } else {
                                // Display mode
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (index % 2 == 0) Color(0xFF1A1A1A) else Color.Transparent
                                        )
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${index + 1}.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF666666),
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Text(
                                        text = formatTimeDisplay(time),
                                        fontSize = 14.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            editingIndex = index
                                            editingText = time
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit",
                                            tint = Color(0xFF88CCFF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteTimestamp(index) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = Color(0xFFFF6666),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Add new timestamp
                if (showAddField) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = addText,
                            onValueChange = { addText = it },
                            label = { Text("HH:mm:ss", fontSize = 10.sp) },
                            placeholder = { Text("e.g. 14:30:00", fontSize = 10.sp) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val validated = validateTimeString(addText)
                                if (validated != null) {
                                    onAddTimestamp(validated)
                                    addText = ""
                                    showAddField = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A00)),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Add", fontSize = 11.sp, color = Color(0xFF88FF88))
                        }
                        TextButton(
                            onClick = {
                                showAddField = false
                                addText = ""
                            }
                        ) {
                            Text("✕", fontSize = 12.sp, color = Color(0xFF888888))
                        }
                    }
                } else {
                    Button(
                        onClick = { showAddField = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A3A)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add",
                            tint = Color(0xFF44FFFF),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Time", fontSize = 11.sp, color = Color(0xFF44FFFF))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003355))
            ) {
                Text("OK", color = Color.White)
            }
        },
        dismissButton = {}
    )
}

/**
 * Validates and normalizes a time string to "HH:mm:ss" format.
 * Accepts formats like "14:30", "14:30:00", "1430", "143000".
 * Returns null if the input is invalid.
 */
private fun validateTimeString(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    // Try HH:mm:ss
    val colonFull = Regex("""^(\d{1,2}):(\d{2}):(\d{2})$""")
    colonFull.matchEntire(trimmed)?.let { m ->
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val s = m.groupValues[3].toIntOrNull() ?: return null
        if (h in 0..23 && min in 0..59 && s in 0..59) {
            return "%02d:%02d:%02d".format(h, min, s)
        }
        return null
    }

    // Try HH:mm
    val colonShort = Regex("""^(\d{1,2}):(\d{2})$""")
    colonShort.matchEntire(trimmed)?.let { m ->
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h in 0..23 && min in 0..59) {
            return "%02d:%02d:00".format(h, min)
        }
        return null
    }

    // Try HHMM or HHMMSS (digits only)
    val digitsOnly = Regex("""^(\d{4,6})$""")
    digitsOnly.matchEntire(trimmed)?.let { m ->
        val digits = m.groupValues[1]
        return when (digits.length) {
            4 -> {
                val h = digits.substring(0, 2).toIntOrNull() ?: return null
                val min = digits.substring(2, 4).toIntOrNull() ?: return null
                if (h in 0..23 && min in 0..59) "%02d:%02d:00".format(h, min) else null
            }
            6 -> {
                val h = digits.substring(0, 2).toIntOrNull() ?: return null
                val min = digits.substring(2, 4).toIntOrNull() ?: return null
                val s = digits.substring(4, 6).toIntOrNull() ?: return null
                if (h in 0..23 && min in 0..59 && s in 0..59) "%02d:%02d:%02d".format(h, min, s) else null
            }
            else -> null
        }
    }

    return null
}

/**
 * Formats a "HH:mm:ss" time string for display.
 * Converts to 12-hour format with AM/PM.
 */
private fun formatTimeDisplay(time: String): String {
    val parts = time.split(":")
    if (parts.size < 2) return time
    val h = parts[0].toIntOrNull() ?: return time
    val min = parts[1]
    val sec = if (parts.size >= 3) parts[2] else "00"
    val amPm = if (h < 12) "AM" else "PM"
    val h12 = when {
        h == 0 -> 12
        h > 12 -> h - 12
        else -> h
    }
    return "$h12:$min:$sec $amPm"
}
