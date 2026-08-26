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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.tail.data.BridgeMovie

/**
 * Dialog shown when the user taps a habit that has "text input" enabled.
 *
 * - Shows existing text entries for the current day with edit/delete capability.
 * - Always shows a free-text [OutlinedTextField] for the user to type a new entry.
 * - When [showOptions] is true AND [options] is non-empty, also shows a scrollable
 *   list of all unique past entries with **multi-select checkboxes**. The user can
 *   select as many as desired; each selected option is saved as a separate entry.
 *   The list has its own dedicated search field: search text only filters the list
 *   and is never saved, so typing a partial word (e.g. "luc" to find "Lucid") and
 *   checking the match registers exactly that book. A "+" button next to the entry
 *   field lets the user add a freshly-typed value as a new checked option **without
 *   closing the dialog**, so they can keep selecting more options before confirming.
 * - A time picker lets the user associate a specific time-of-day with the entries
 *   instead of defaulting to noon for past dates.
 * - When [suggestedMinutes] is non-null (movie-bridge suggestion), a separate
 *   "Length" row shows the suggested watch-length, editable with a wheel picker
 *   exactly like the time. On OK the length is appended to the free-text entry
 *   as " (N min)" — the format the rest of the app parses back out.
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
    suggestedMinutes: Int? = null,
    recentMovies: List<BridgeMovie> = emptyList(),
    suggestionLoading: Boolean = false,
    loadingMetrics: LoadingMetrics? = null,
    onConfirm: (List<String>, Int, Int) -> Unit,
    onDismiss: () -> Unit,
    onEdit: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {}
) {
    var inputText by remember { mutableStateOf(initialText) }
    // Set once the user types (or picks a recent movie) — later-arriving
    // suggestion updates must never clobber a deliberate choice.
    var userEditedText by remember { mutableStateOf(false) }
    var editingTimestamp by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    // Multi-select state for past options
    val selectedOptions = remember { mutableStateMapOf<String, Boolean>() }

    // Search query for the past-options list. Deliberately separate from the
    // entry field so filter text can never be submitted as an entry.
    var searchQuery by remember { mutableStateOf("") }

    // Time picker state — wheel-based
    var selectedHour by remember { mutableIntStateOf(initialHour) }
    var selectedMinute by remember { mutableIntStateOf(initialMinute) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Length (minutes) state — wheel-based, only for movie-bridge suggestions
    val hasLengthSuggestion = suggestedMinutes != null
    var lengthMinutes by remember { mutableIntStateOf(suggestedMinutes ?: 0) }
    var showLengthPicker by remember { mutableStateOf(false) }
    // Same guard as the text: a late length suggestion only applies until
    // the user edits the wheel or picks a movie.
    var lengthTouched by remember { mutableStateOf(false) }

    // The dialog opens instantly and the movie suggestion (text + length)
    // can arrive afterwards from the cache/bridge pipeline — apply it only
    // while the user hasn't taken over the fields.
    LaunchedEffect(initialText) {
        if (!userEditedText) inputText = initialText
    }
    LaunchedEffect(suggestedMinutes) {
        if (!lengthTouched && suggestedMinutes != null) lengthMinutes = suggestedMinutes
    }

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

            // ── Suggestion loading indicator ────────────────────────────────
            // Shown while the movie suggestion resolves (cache → bridge). Uses
            // the points-driven Orrery spinner, shrunk to fit the dialog row.
            if (suggestionLoading) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    loadingMetrics?.let { m ->
                        HabitLoadingSpinner(
                            monthlyAverage = m.monthlyAverage,
                            weeklyAverage = m.weeklyAverage,
                            todayPoints = m.todayPoints,
                            size = 20.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "Fetching suggestion from desktop…",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }

            // ── Last-watched movies (quick picker) ──────────────────────────
            if (recentMovies.isNotEmpty()) {
                var showRecentMovies by remember { mutableStateOf(false) }
                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = { showRecentMovies = !showRecentMovies },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 0.dp, end = 0.dp, top = 0.dp, bottom = 0.dp
                    )
                ) {
                    Text(
                        text = if (showRecentMovies) "🎬 Last watched ▴" else "🎬 Last watched ▾",
                        color = Color(0xFFFFAA00),
                        fontSize = 12.sp
                    )
                }
                if (showRecentMovies) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                    ) {
                        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                            items(recentMovies.take(5)) { movie ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            userEditedText = true
                                            inputText = movie.title
                                            movie.totalWatchMin?.takeIf { it > 0 }?.let {
                                                lengthTouched = true
                                                lengthMinutes = it
                                            }
                                            showRecentMovies = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = movie.title,
                                            color = Color(0xFFFFD700),
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        val meta = buildString {
                                            if (movie.lastWatched.isNotBlank()) {
                                                append("watched ${movie.lastWatched.take(10)}")
                                            }
                                            movie.totalWatchMin?.takeIf { it > 0 }?.let {
                                                if (isNotEmpty()) append(" · ")
                                                append("$it min")
                                            }
                                        }
                                        if (meta.isNotEmpty()) {
                                            Text(
                                                text = meta,
                                                color = Color(0xFF888888),
                                                fontSize = 11.sp
                                            )
                                        }
                                    }
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

                // ── Length picker (movie-bridge suggestions) ──────────────────
                if (hasLengthSuggestion) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Length",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        val lengthLabel = if (lengthMinutes >= 60) {
                            "${lengthMinutes / 60} h ${lengthMinutes % 60} min"
                        } else {
                            "$lengthMinutes min"
                        }
                        TextButton(
                            onClick = { showLengthPicker = !showLengthPicker },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                            )
                        ) {
                            Text(
                                text = if (showLengthPicker) "Done" else lengthLabel,
                                color = Color(0xFFFFAA00),
                                fontSize = 13.sp
                            )
                        }
                    }

                    if (showLengthPicker) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            DurationWheelPicker(
                                totalMinutes = lengthMinutes,
                                onDurationChange = {
                                    lengthTouched = true
                                    lengthMinutes = it
                                },
                                accent = Color(0xFFFFAA00)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Entry input field ───────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            userEditedText = true
                            inputText = it
                        },
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
                    // Filter options by the dedicated search field (case-insensitive
                    // contains). The entry field no longer filters this list, so a
                    // partial word typed to find a past entry can never be saved.
                    val filteredOptions = if (searchQuery.isBlank()) {
                        allOptions
                    } else {
                        allOptions.filter { it.contains(searchQuery, ignoreCase = true) }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Dedicated search field for the past-entries list. Whatever is
                    // typed here only filters — it is never submitted as an entry.
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text("Search past entries", color = Color(0xFF666666), fontSize = 13.sp)
                        },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search past entries",
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = Color(0xFF888888),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFFAA00),
                            unfocusedBorderColor = Color(0xFF555555),
                            cursorColor = Color(0xFFFFAA00)
                        )
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val selectedCount = selectedOptions.values.count { it }
                    val header = buildString {
                        append("Past entries")
                        if (searchQuery.isNotBlank()) {
                            append(" — ${filteredOptions.size} match")
                            if (filteredOptions.size != 1) append("es")
                        }
                        if (selectedCount > 0) {
                            append(" ($selectedCount selected)")
                        } else if (searchQuery.isBlank()) {
                            append(" (tap to select multiple)")
                        }
                    }
                    Text(
                        text = header,
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
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = "No matching entries",
                                    color = Color(0xFF555555),
                                    fontSize = 12.sp
                                )
                                // Shortcut: the searched value isn't a past entry yet,
                                // offer to add it as a new checked entry in one tap.
                                val trimmedQuery = searchQuery.trim()
                                if (trimmedQuery.isNotEmpty()) {
                                    TextButton(
                                        onClick = { selectedOptions[trimmedQuery] = true },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            start = 0.dp, end = 0.dp, top = 4.dp, bottom = 0.dp
                                        )
                                    ) {
                                        Text(
                                            text = "Add \"$trimmedQuery\" as a new entry",
                                            color = Color(0xFFFFAA00),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
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
                            // Add free text if non-empty (skip duplicates of selected
                            // options, compared case-insensitively so leftover search
                            // text can never ride along with a checked option).
                            // For movie suggestions, append the wheel-edited length as
                            // "(N min)" unless the text already carries a duration.
                            if (hasFreeText && entries.none { it.equals(trimmedInput, ignoreCase = true) }) {
                                val alreadyHasDuration = Regex("""\(\d+\s*min\)\s*$""")
                                    .containsMatchIn(trimmedInput)
                                val textWithLength = when {
                                    !hasLengthSuggestion || alreadyHasDuration -> trimmedInput
                                    lengthMinutes > 0 -> "$trimmedInput ($lengthMinutes min)"
                                    else -> trimmedInput
                                }
                                entries.add(textWithLength)
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
