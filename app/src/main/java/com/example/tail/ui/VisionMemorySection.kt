package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.meal.VisionMemoryEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings section for the **Vision Memory** — the LLM's learned
 * image→habit associations.
 *
 * Every association taught via the tandem hold-to-capture flow (photo +
 * spoken instruction) is stored here, injected into every future vision
 * call, and can be reviewed, edited, or deleted by the user.
 */
@Composable
fun VisionMemorySection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    // Load entries whenever the section is shown
    LaunchedEffect(Unit) { viewModel.refreshVisionMemory() }
    val entries by viewModel.visionMemoryEntries.collectAsState()

    var editing by remember { mutableStateOf<VisionMemoryEntry?>(null) }

    Column {
        Text("🧠 Vision Memory", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "What the LLM has learned from your photos. Hold the camera " +
                   "capture button and speak to teach a new association " +
                   "(e.g. photograph your garden and say \"increment gardening\") " +
                   "— future photos of the same subject are then recognised " +
                   "automatically.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (entries.isEmpty()) {
            Text(
                text = "Nothing learned yet. Hold the capture button in the camera " +
                       "shortcut, take a photo, and say what it should mean.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            )
        } else {
            Text(
                text = "${entries.size} learned association${if (entries.size == 1) "" else "s"} — tap to edit",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Plain Column (not LazyColumn) — the section lives inside the
            // settings screen's own scroll container.
            entries.forEach { entry ->
                VisionMemoryEntryRow(
                    entry = entry,
                    onClick = { editing = entry }
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }

    editing?.let { entry ->
        VisionMemoryEditDialog(
            entry = entry,
            availableSubtypes = settings.habitSubtypes[entry.habitName].orEmpty(),
            onSave = { updated ->
                viewModel.updateVisionMemoryEntry(updated)
                editing = null
            },
            onDelete = {
                viewModel.deleteVisionMemoryEntry(entry.id)
                editing = null
            },
            onDismiss = { editing = null }
        )
    }
}

/** One learned-association row: description, voice note, habit target, date. */
@Composable
private fun VisionMemoryEntryRow(
    entry: VisionMemoryEntry,
    onClick: () -> Unit
) {
    val dateStr = try {
        Instant.ofEpochMilli(entry.timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } catch (e: Exception) {
        ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(
            text = entry.visualDescription.ifBlank { "(no description)" },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        if (entry.voiceNote.isNotBlank()) {
            Text(
                text = "🗣️ \"${entry.voiceNote}\"",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "→ ${entry.habitName}" +
                    (entry.subtypeName?.let { " / $it" } ?: "") +
                    (if (entry.incrementAmount != 1) " ×${entry.incrementAmount}" else ""),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = dateStr,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Edit dialog for a single memory entry — the source of truth is directly editable. */
@Composable
private fun VisionMemoryEditDialog(
    entry: VisionMemoryEntry,
    availableSubtypes: List<String>,
    onSave: (VisionMemoryEntry) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var visualDescription by remember { mutableStateOf(entry.visualDescription) }
    var voiceNote by remember { mutableStateOf(entry.voiceNote) }
    var habitName by remember { mutableStateOf(entry.habitName) }
    var subtypeName by remember { mutableStateOf(entry.subtypeName ?: "") }
    var amountText by remember { mutableStateOf(entry.incrementAmount.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(12.dp)
                )
                .padding(20.dp)
        ) {
            Text("Edit learned association", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = visualDescription,
                onValueChange = { visualDescription = it },
                label = { Text("What to recognise") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = voiceNote,
                onValueChange = { voiceNote = it },
                label = { Text("Original voice note") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = habitName,
                onValueChange = { habitName = it },
                label = { Text("Habit to increment (exact name)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = subtypeName,
                onValueChange = { subtypeName = it },
                label = {
                    Text(
                        if (availableSubtypes.isEmpty()) "Subtype (optional — habit has no subtypes)"
                        else "Subtype (optional: ${availableSubtypes.joinToString(", ")})"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() } },
                label = { Text("Increment amount") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(4.dp))
                Button(
                    onClick = {
                        val amount = amountText.toIntOrNull()?.takeIf { it > 0 } ?: 1
                        onSave(
                            entry.copy(
                                visualDescription = visualDescription.trim(),
                                voiceNote = voiceNote.trim(),
                                habitName = habitName.trim(),
                                subtypeName = subtypeName.trim().ifBlank { null },
                                incrementAmount = amount
                            )
                        )
                    },
                    enabled = visualDescription.isNotBlank() && habitName.isNotBlank()
                ) {
                    Text("Save")
                }
            }
        }
    }
}

