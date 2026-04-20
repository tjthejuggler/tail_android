package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Dialog for viewing, editing, deleting, and adding habit increment timestamps
 * for a specific habit on a specific day.
 *
 * When editing a timestamp, shows the same +/- hour/minute offset buttons
 * as the QuickTimestampEditorDialog (increment toast "Edit Time" flow).
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
    // -1 = not editing; >= 0 = editing that index; Int.MAX_VALUE = adding new
    var editingIndex by remember { mutableStateOf(-1) }
    var editingOffsetMinutes by remember { mutableStateOf(0) }
    var editingOriginalTime by remember { mutableStateOf(LocalTime.MIDNIGHT) }

    // For "add new" mode we start from current time
    var addOffsetMinutes by remember { mutableStateOf(0) }
    var addOriginalTime by remember { mutableStateOf(LocalTime.now()) }

    val isAddingNew = editingIndex == Int.MAX_VALUE

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

                if (timestamps.isEmpty() && !isAddingNew) {
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
                                // Inline offset-button edit mode
                                TimestampOffsetEditor(
                                    originalTime = editingOriginalTime,
                                    offsetMinutes = editingOffsetMinutes,
                                    onOffsetChange = { editingOffsetMinutes += it },
                                    onConfirm = {
                                        val adjusted = editingOriginalTime.plusMinutes(editingOffsetMinutes.toLong())
                                        onUpdateTimestamp(index, adjusted.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                                        editingIndex = -1
                                    },
                                    onCancel = { editingIndex = -1 }
                                )
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
                                            editingOffsetMinutes = 0
                                            editingOriginalTime = runCatching {
                                                LocalTime.parse(time)
                                            }.getOrDefault(LocalTime.now())
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

                        // "Add new" inline offset editor
                        if (isAddingNew) {
                            item {
                                TimestampOffsetEditor(
                                    originalTime = addOriginalTime,
                                    offsetMinutes = addOffsetMinutes,
                                    onOffsetChange = { addOffsetMinutes += it },
                                    onConfirm = {
                                        val adjusted = addOriginalTime.plusMinutes(addOffsetMinutes.toLong())
                                        onAddTimestamp(adjusted.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                                        editingIndex = -1
                                    },
                                    onCancel = { editingIndex = -1 }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // "Add Time" button — only when not already adding
                if (!isAddingNew && editingIndex == -1) {
                    Button(
                        onClick = {
                            addOffsetMinutes = 0
                            addOriginalTime = LocalTime.now()
                            editingIndex = Int.MAX_VALUE
                        },
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
 * Inline offset-button editor for a single timestamp.
 * Shows the current adjusted time, +/- hour and minute buttons, and confirm/cancel.
 */
@Composable
private fun TimestampOffsetEditor(
    originalTime: LocalTime,
    offsetMinutes: Int,
    onOffsetChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val displayTime = remember(originalTime, offsetMinutes) {
        val adjusted = originalTime.plusMinutes(offsetMinutes.toLong())
        adjusted.format(DateTimeFormatter.ofPattern("h:mm a"))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Time display
        Box(
            modifier = Modifier
                .background(Color(0xFF2A2A2E), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF555555), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = displayTime,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF88DDFF),
                fontFamily = FontFamily.Monospace
            )
        }

        if (offsetMinutes != 0) {
            val offsetHours = offsetMinutes / 60
            val offsetMins = offsetMinutes % 60
            val offsetText = buildString {
                append("(")
                if (offsetMinutes > 0) append("+")
                if (offsetHours != 0) {
                    append("${offsetHours}h")
                    if (offsetMins != 0) append(" ")
                }
                if (offsetMins != 0) {
                    if (offsetHours == 0 && offsetMinutes > 0) append("+")
                    append("${offsetMins}m")
                }
                append(")")
            }
            Text(
                text = offsetText,
                fontSize = 11.sp,
                color = Color(0xFF88AA88),
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Hours row
        Text("Hours", fontSize = 10.sp, color = Color(0xFF888888))
        Spacer(modifier = Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-3, -2, -1, 1, 2, 3).forEach { offset ->
                OffsetButton(
                    label = if (offset > 0) "+${offset}h" else "${offset}h",
                    onClick = { onOffsetChange(offset * 60) },
                    size = 34.dp,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Minutes row
        Text("Minutes", fontSize = 10.sp, color = Color(0xFF888888))
        Spacer(modifier = Modifier.height(3.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(-30, -15, -5, 5, 15, 30).forEach { offset ->
                OffsetButton(
                    label = if (offset > 0) "+${offset}m" else "${offset}m",
                    onClick = { onOffsetChange(offset) },
                    size = 34.dp,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Confirm / Cancel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable { onOffsetChange(-offsetMinutes) } // reset
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Reset", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Cancel", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF004488), RoundedCornerShape(6.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Done", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
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
