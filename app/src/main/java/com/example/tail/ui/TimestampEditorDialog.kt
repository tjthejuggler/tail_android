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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A group of increments that happened at the same moment.
 *
 * The repository stores one "HH:mm:ss" string PER increment unit, so a
 * multi-increment (e.g. "+5" via IPC, widget batch, or in-app amount) is a run
 * of identical time strings. The editor aggregates them into one card showing
 * the shared time and the increment amount (group size). An amount of 0 marks
 * a text-only group — a text entry at a time with no increments.
 */
private data class TimeGroup(
    val time: String,
    val amount: Int
)

/**
 * Dialog for viewing, editing, deleting, and adding habit increment timestamps
 * for a specific habit on a specific day.
 *
 * Each same-moment increment group is rendered as a CARD showing:
 *  - the time, UNDERLINED to signal it is tappable → inline wheel editor that
 *    re-times the whole group;
 *  - the increment amount contributed at that time ("+N" chip);
 *  - any text logged at that time, abbreviated to two lines with an
 *    expand/collapse toggle;
 *  - a pencil button that temporarily makes the card EDITABLE (amount + text)
 *    — for meal habits it instead jumps to the pre-existing meal editor for
 *    the meal logged at that time;
 *  - a delete button that removes the whole group.
 */
@Composable
fun TimestampEditorDialog(
    habitName: String,
    timestamps: List<String>,
    /** Text entries for the day keyed by "HH:mm:ss" time-of-day. */
    textEntries: Map<String, String> = emptyMap(),
    /** True for meal habits — the pencil opens the meal editor instead. */
    isMealHabit: Boolean = false,
    /** True when the habit has a text log (text field shown in card edit mode). */
    canEditText: Boolean = false,
    /** Re-time every increment at [oldTime] to [newTime]. */
    onUpdateTimeGroup: (oldTime: String, newTime: String) -> Unit,
    /** Delete every increment at [time]. */
    onDeleteTimeGroup: (time: String) -> Unit,
    /** Set the increment amount contributed at [time] to [newAmount]. */
    onSetGroupAmount: (time: String, newAmount: Int) -> Unit,
    /** Upsert the text logged at [time] (empty string clears it). */
    onUpdateText: (time: String, newText: String) -> Unit,
    /** Open the pre-existing meal editor for the meal logged at [time]. */
    onEditMeal: (time: String) -> Unit,
    /** Add a new single increment at [time]. */
    onAddTimestamp: (time: String) -> Unit,
    onDismiss: () -> Unit
) {
    // Aggregate duplicate time strings into per-moment groups (chronological).
    // Text entries whose time has no increment group still get a card
    // (amount 0) so their text is visible and editable — e.g. movie
    // entries logged by the bridge without an increment at that moment.
    val groups = remember(timestamps, textEntries) {
        val tsGroups = timestamps.groupBy { it }
            .map { (time, list) -> TimeGroup(time, list.size) }
            .toMutableList()
        val covered = tsGroups.map { it.time }.toSet()
        textEntries.keys.filter { it !in covered }.sorted().forEach { time ->
            tsGroups.add(TimeGroup(time, 0))
        }
        tsGroups.sortedBy { it.time }
    }

    // null = nothing open; a time string = that group is being re-timed;
    // ADD_NEW = the "Add Time" wheel editor is open.
    val addNewSentinel = "__add_new__"
    var editingTime by remember { mutableStateOf<String?>(null) }
    // null = no card in edit mode; a time string = that card is editable.
    var editingCard by remember { mutableStateOf<String?>(null) }

    // Absolute time state for the inline wheel editor.
    var wheelHour24 by remember { mutableIntStateOf(0) }
    var wheelMinute by remember { mutableIntStateOf(0) }
    var wheelOriginalTime by remember { mutableStateOf(LocalTime.MIDNIGHT) }

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
                    text = when {
                        timestamps.isEmpty() && groups.isNotEmpty() ->
                            "${groups.size} text entr${if (groups.size != 1) "ies" else "y"}"
                        groups.size == 1 && groups.first().amount == 1 ->
                            "1 timestamped increment"
                        else ->
                            "${timestamps.size} increment${if (timestamps.size != 1) "s" else ""} " +
                                "across ${groups.size} time${if (groups.size != 1) "s" else ""}"
                    },
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Empty only when there is NOTHING to show — no increment
                // timestamps AND no text entries. Text-only days (e.g. a
                // past movie logged by the bridge) still render their cards.
                if (groups.isEmpty() && editingTime != addNewSentinel) {
                    Text(
                        text = "No timestamps recorded for today.",
                        fontSize = 12.sp,
                        color = Color(0xFF666666)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                    ) {
                        itemsIndexed(groups, key = { _, g -> g.time }) { index, group ->
                            if (editingTime == group.time) {
                                // ── Inline wheel editor: re-time this group ──
                                TimestampWheelEditor(
                                    originalTime = wheelOriginalTime,
                                    hour24 = wheelHour24,
                                    minute = wheelMinute,
                                    onTimeChange = { h, m ->
                                        wheelHour24 = h
                                        wheelMinute = m
                                    },
                                    onConfirm = {
                                        val adjusted = LocalTime.of(wheelHour24, wheelMinute)
                                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                        if (adjusted != group.time) {
                                            onUpdateTimeGroup(group.time, adjusted)
                                        }
                                        editingTime = null
                                    },
                                    onCancel = { editingTime = null }
                                )
                            } else {
                                TimestampCard(
                                    group = group,
                                    index = index,
                                    text = textEntries[group.time].orEmpty(),
                                    isEditing = editingCard == group.time,
                                    isMealHabit = isMealHabit,
                                    canEditText = canEditText,
                                    onStartEditTime = {
                                        val parsed = runCatching { LocalTime.parse(group.time) }
                                            .getOrDefault(LocalTime.now())
                                        wheelOriginalTime = parsed
                                        wheelHour24 = parsed.hour
                                        wheelMinute = parsed.minute
                                        editingTime = group.time
                                    },
                                    onStartEditInfo = {
                                        if (isMealHabit) {
                                            onEditMeal(group.time)
                                        } else {
                                            editingCard = group.time
                                        }
                                    },
                                    onCancelEditInfo = { editingCard = null },
                                    onSaveEditInfo = { newAmount, newText ->
                                        if (newAmount != group.amount) {
                                            onSetGroupAmount(group.time, newAmount)
                                        }
                                        if (canEditText && newText != textEntries[group.time].orEmpty()) {
                                            onUpdateText(group.time, newText)
                                        }
                                        editingCard = null
                                    },
                                    onDelete = {
                                        if (group.amount == 0 && canEditText &&
                                            textEntries[group.time].orEmpty().isNotBlank()
                                        ) {
                                            // Text-only card: deleting clears its text.
                                            onUpdateText(group.time, "")
                                        } else {
                                            onDeleteTimeGroup(group.time)
                                        }
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // "Add new" inline wheel editor
                        if (editingTime == addNewSentinel) {
                            item(key = addNewSentinel) {
                                TimestampWheelEditor(
                                    originalTime = wheelOriginalTime,
                                    hour24 = wheelHour24,
                                    minute = wheelMinute,
                                    onTimeChange = { h, m ->
                                        wheelHour24 = h
                                        wheelMinute = m
                                    },
                                    onConfirm = {
                                        val adjusted = LocalTime.of(wheelHour24, wheelMinute)
                                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                                        onAddTimestamp(adjusted)
                                        editingTime = null
                                    },
                                    onCancel = { editingTime = null }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // "Add Time" button — only when nothing is being edited
                if (editingTime == null && editingCard == null) {
                    Button(
                        onClick = {
                            val now = LocalTime.now()
                            wheelOriginalTime = now
                            wheelHour24 = now.hour
                            wheelMinute = now.minute
                            editingTime = addNewSentinel
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
 * A single increment card: underlined (tappable) time, amount chip, abbreviated
 * text with expand toggle, pencil + delete actions. When [isEditing] the amount
 * and text become editable inputs with Save/Cancel.
 */
@Composable
private fun TimestampCard(
    group: TimeGroup,
    index: Int,
    text: String,
    isEditing: Boolean,
    isMealHabit: Boolean,
    canEditText: Boolean,
    onStartEditTime: () -> Unit,
    onStartEditInfo: () -> Unit,
    onCancelEditInfo: () -> Unit,
    onSaveEditInfo: (newAmount: Int, newText: String) -> Unit,
    onDelete: () -> Unit
) {
    // Per-card expand state for the abbreviated text preview.
    var textExpanded by remember(group.time) { mutableStateOf(false) }
    var textOverflows by remember(group.time) { mutableStateOf(false) }
    // Editable fields — re-seeded whenever the underlying group data changes
    // so re-entering edit mode never shows a stale amount or text.
    var amountText by remember(group.time, group.amount) { mutableStateOf(group.amount.toString()) }
    var editText by remember(group.time, text) { mutableStateOf(text) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1E), RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (isEditing) Color(0xFF88CCFF) else Color(0xFF333340),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // ── Header: index • underlined time • amount chip • actions ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${index + 1}.",
                fontSize = 12.sp,
                color = Color(0xFF666666),
                modifier = Modifier.width(24.dp)
            )
            // The time itself is the re-time affordance: underlined + tappable.
            Text(
                text = formatTimeDisplay(group.time),
                fontSize = 14.sp,
                color = Color(0xFF88DDFF),
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier
                    .clickable(onClick = onStartEditTime)
                    .weight(1f)
            )
            // Amount chip: how much was contributed at this time.
            // Text-only entries (no increments at this time) show no chip.
            if (group.amount > 0) {
                Text(
                    text = "+${group.amount}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (group.amount > 1) Color(0xFF66FFAA) else Color(0xFF889988),
                    modifier = Modifier
                        .background(
                            if (group.amount > 1) Color(0xFF003322) else Color(0xFF222826),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            IconButton(
                onClick = onStartEditInfo,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = if (isMealHabit) "Edit meal" else "Edit increment info",
                    tint = Color(0xFF88CCFF),
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = onDelete,
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

        // ── Text preview (abbreviated, expandable) ──
        if (!isEditing && text.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                color = Color(0xFFBBBBCC),
                fontStyle = FontStyle.Italic,
                maxLines = if (textExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!textExpanded) textOverflows = result.hasVisualOverflow
                },
                modifier = Modifier.fillMaxWidth()
            )
            if (textOverflows || textExpanded) {
                Text(
                    text = if (textExpanded) "▾ less" else "▸ more",
                    fontSize = 11.sp,
                    color = Color(0xFF88CCFF),
                    modifier = Modifier
                        .clickable { textExpanded = !textExpanded }
                        .padding(top = 2.dp)
                )
            }
        }

        // ── Inline edit mode: amount stepper + text field ──
        if (isEditing) {
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Amount:", fontSize = 12.sp, color = Color(0xFF999999))
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                        .clickable {
                            val n = (amountText.toIntOrNull() ?: 1) - 1
                            if (n >= 0) amountText = n.toString()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("−", color = Color(0xFFAAAAAA), fontSize = 14.sp) }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF111418), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                        .width(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = amountText,
                        fontSize = 13.sp,
                        color = Color(0xFF66FFAA),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                        .clickable {
                            val n = (amountText.toIntOrNull() ?: 0) + 1
                            amountText = n.toString()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text("+", color = Color(0xFFAAAAAA), fontSize = 14.sp) }
            }

            if (canEditText) {
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    placeholder = { Text("Note at this time…", fontSize = 12.sp, color = Color(0xFF666666)) },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color(0xFFDDDDDD))
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                        .clickable {
                            // Reset local edits and leave edit mode.
                            amountText = group.amount.toString()
                            editText = text
                            onCancelEditInfo()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("Cancel", color = Color(0xFFAAAAAA), fontSize = 12.sp) }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF004488), RoundedCornerShape(6.dp))
                        .clickable {
                            val n = (amountText.toIntOrNull() ?: group.amount).coerceAtLeast(0)
                            onSaveEditInfo(n, editText.trim())
                        }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) { Text("Save", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }

        // Meal hint under the header when not editing.
        if (!isEditing && isMealHabit) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "✏️ opens the meal editor for this time",
                fontSize = 10.sp,
                color = Color(0xFF667788)
            )
        }
    }
}

/**
 * Inline wheel-based editor for a single timestamp.
 * Shows a scrolling wheel time picker and confirm/cancel.
 *
 * @param originalTime The original time before editing (for offset display)
 * @param hour24 Current hour in 24-hour format being edited
 * @param minute Current minute being edited
 * @param onTimeChange Called when the wheel changes the time
 * @param onConfirm Called when the user confirms the edit
 * @param onCancel Called when the user cancels the edit
 */
@Composable
private fun TimestampWheelEditor(
    originalTime: LocalTime,
    hour24: Int,
    minute: Int,
    onTimeChange: (Int, Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    // Compute offset from original for display
    val offsetMinutes = remember(hour24, minute, originalTime) {
        val currentTotal = hour24 * 60 + minute
        val originalTotal = originalTime.hour * 60 + originalTime.minute
        currentTotal - originalTotal
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1C1C1E), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Wheel time picker ──────────────────────────────────────────
        TimeWheelPicker(
            hour24 = hour24,
            minute = minute,
            onTimeChange = onTimeChange,
            accent = Color(0xFF88DDFF),
            compact = true
        )

        // Offset indicator
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

        // Confirm / Cancel / Reset
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF333333), RoundedCornerShape(6.dp))
                    .clickable {
                        // Reset to original
                        onTimeChange(originalTime.hour, originalTime.minute)
                    }
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
