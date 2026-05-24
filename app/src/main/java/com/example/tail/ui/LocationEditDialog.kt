package com.example.tail.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
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
 * Dialog for manually setting the location for a given day.
 * Shows a text field pre-filled with the effective location (stored or assumed),
 * and a scrollable list of previously-entered locations to pick from.
 *
 * Includes an "auto" link in the title row that, when clicked, fetches GPS
 * and shows a popup list of candidate location names. The user picks one,
 * and it fills the text field. The GPS coordinates are NOT changed — only
 * the display label varies.
 *
 * When the user picks a candidate, [onSavePreferredCandidateIndex] is called
 * with the 0-based index of the chosen candidate in the generated list.
 * On the next day, the app re-runs candidate generation with fresh GPS data
 * and picks the same positional slot — so the location name is always fresh.
 */
@Composable
fun LocationEditDialog(
    currentLocation: String?,
    suggestions: List<String>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    onFetchCandidates: (((List<String>) -> Unit) -> Unit)? = null,
    onSavePreferredCandidateIndex: ((Int) -> Unit)? = null
) {
    var text by remember { mutableStateOf(currentLocation ?: "") }
    var fetching by remember { mutableStateOf(false) }
    var autoCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAutoPopup by remember { mutableStateOf(false) }

    // If the caller updates currentLocation externally, sync the text field.
    LaunchedEffect(currentLocation) {
        if (currentLocation != null && text != currentLocation) {
            text = currentLocation
        }
    }

    // Filter suggestions to those containing the current text (case-insensitive).
    val filteredSuggestions = remember(text, suggestions) {
        val query = text.trim().lowercase()
        if (query.isEmpty()) suggestions
        else suggestions.filter { it.lowercase().contains(query) }
    }

    // Auto candidates popup
    if (showAutoPopup && autoCandidates.isNotEmpty()) {
        AutoCandidatesPopup(
            candidates = autoCandidates,
            onSelect = { candidate ->
                val index = autoCandidates.indexOf(candidate)
                text = candidate
                showAutoPopup = false
                if (index >= 0) onSavePreferredCandidateIndex?.invoke(index)
            },
            onDismiss = { showAutoPopup = false }
        )
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
                // Title row with "auto" link
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Set location",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (onFetchCandidates != null) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (fetching) "locating…" else "auto",
                            color = if (fetching) Color(0xFF666666) else Color(0xFF44BBFF),
                            fontSize = 13.sp,
                            modifier = Modifier.clickable(enabled = !fetching) {
                                fetching = true
                                onFetchCandidates { result ->
                                    fetching = false
                                    if (result.isNotEmpty()) {
                                        autoCandidates = result
                                        showAutoPopup = true
                                    }
                                }
                            }
                        )
                    }
                }

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (currentLocation != null) {
                        TextButton(
                            onClick = { onConfirm(null) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCC4444))
                        ) {
                            Text("Delete", color = Color(0xFFCC4444))
                        }
                    }
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

/**
 * Popup that shows a scrollable list of auto-detected location candidates.
 * The user taps one to select it. The first item is the preferred candidate
 * (if one was previously saved).
 */
@Composable
private fun AutoCandidatesPopup(
    candidates: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
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
                    text = "Auto-detected locations",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Tap to select. Your choice becomes the default for future auto-detects.",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    candidates.forEachIndexed { index, candidate ->
                        if (index > 0) {
                            HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(candidate) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = candidate,
                                color = if (index == 0) Color(0xFF44BBFF) else Color(0xFFAADDFF),
                                fontSize = 14.sp,
                                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
                            )
                            if (index == 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "★",
                                    color = Color(0xFF44BBFF),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            }
        }
    }
}
