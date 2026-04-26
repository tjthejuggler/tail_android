package com.example.tail.ui.debug

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.debug.DebugNoteEntry
import com.example.tail.data.debug.NoteType
import com.example.tail.data.debug.QueuedNote
import com.example.tail.data.debug.ScreenContextMapper.ScreenContext

// Semantic colors for the debug dialog
private val DialogCyan = Color(0xFF00BCD4)
private val DialogOrange = Color(0xFFFF9800)
private val DialogGreen = Color(0xFF4CAF50)
private val DialogRed = Color(0xFFF44336)
private val DialogSurfaceDark = Color(0xFF1E1E1E)
private val DialogSurfaceVariant = Color(0xFF2D2D2D)
private val DialogTextPrimary = Color(0xFFE0E0E0)
private val DialogTextSecondary = Color(0xFF9E9E9E)
private val DialogBackgroundDark = Color(0xFF121212)

private enum class DebugTab { NOTE, QUEUE, SAVED }

/**
 * Dialog shown when the user taps the debug bubble.
 *
 * Three tabs:
 * - **Note** — compose a note with Save (draft) and Queue buttons
 * - **Queue** — view queued notes (all screens) with Submit All and per-note delete
 * - **Saved** — view all submitted notes from the JSON file, grouped by screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugNoteDialog(
    screenContext: ScreenContext,
    currentRoute: String,
    draftText: String,
    draftType: NoteType,
    queuedNotes: List<QueuedNote>,
    savedNotesByScreen: Map<String, List<DebugNoteEntry>>,
    noteCountOnScreen: Int,
    onDismiss: () -> Unit,
    onSaveDraft: (NoteType, String) -> Unit,
    onQueueNote: (NoteType, String) -> Unit,
    onSubmitQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit
) {
    var noteText by remember(draftText) { mutableStateOf(draftText) }
    var selectedType by remember(draftType) { mutableStateOf(draftType) }
    var activeTab by remember { mutableStateOf(DebugTab.NOTE) }

    val totalSaved = savedNotesByScreen.values.sumOf { it.size }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                // ── Tab row ──────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DebugTabButton(
                        label = "✏️ Note",
                        selected = activeTab == DebugTab.NOTE,
                        onClick = { activeTab = DebugTab.NOTE }
                    )
                    DebugTabButton(
                        label = "📋 Queue",
                        badge = queuedNotes.size.takeIf { it > 0 },
                        badgeColor = DialogOrange,
                        selected = activeTab == DebugTab.QUEUE,
                        onClick = { activeTab = DebugTab.QUEUE }
                    )
                    DebugTabButton(
                        label = "💾 Saved",
                        badge = totalSaved.takeIf { it > 0 },
                        badgeColor = DialogGreen,
                        selected = activeTab == DebugTab.SAVED,
                        onClick = { activeTab = DebugTab.SAVED }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                // ── Screen context (shown on Note tab only) ───────────────────
                if (activeTab == DebugTab.NOTE) {
                    Text(
                        "Screen: ${screenContext.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DialogTextSecondary
                    )
                    Text(
                        "Source: ${screenContext.sourceFile}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DialogCyan
                    )
                    if (noteCountOnScreen > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "$noteCountOnScreen note(s) queued on this screen",
                            style = MaterialTheme.typography.labelSmall,
                            color = DialogOrange
                        )
                    }
                }
            }
        },
        text = {
            when (activeTab) {
                DebugTab.NOTE -> NoteComposeContent(
                    noteText = noteText,
                    selectedType = selectedType,
                    onNoteTextChange = { noteText = it },
                    onTypeChange = { selectedType = it }
                )

                DebugTab.QUEUE -> QueueContent(
                    queuedNotes = queuedNotes,
                    onRemoveFromQueue = onRemoveFromQueue
                )

                DebugTab.SAVED -> SavedContent(
                    savedNotesByScreen = savedNotesByScreen
                )
            }
        },
        confirmButton = {
            when (activeTab) {
                DebugTab.NOTE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSaveDraft(selectedType, noteText) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DialogTextPrimary)
                    ) {
                        Text("Save")
                    }
                    Button(
                        onClick = {
                            if (noteText.isNotBlank()) {
                                onQueueNote(selectedType, noteText)
                                noteText = ""
                                selectedType = NoteType.BUG
                            }
                        },
                        enabled = noteText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DialogOrange,
                            contentColor = DialogBackgroundDark,
                            disabledContainerColor = DialogSurfaceVariant,
                            disabledContentColor = DialogTextSecondary
                        )
                    ) {
                        Text("Queue")
                    }
                }

                DebugTab.QUEUE -> if (queuedNotes.isNotEmpty()) {
                    Button(
                        onClick = {
                            onSubmitQueue()
                            activeTab = DebugTab.NOTE
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DialogGreen,
                            contentColor = DialogBackgroundDark
                        )
                    ) {
                        Text("Submit All (${queuedNotes.size})")
                    }
                }

                DebugTab.SAVED -> { /* no action button needed */ }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = DialogTextSecondary)
            }
        }
    )
}

// ── Private sub-composables ───────────────────────────────────────────────────

@Composable
private fun DebugTabButton(
    label: String,
    selected: Boolean,
    badge: Int? = null,
    badgeColor: Color = DialogOrange,
    onClick: () -> Unit
) {
    BadgedBox(
        badge = {
            if (badge != null && badge > 0) {
                Badge(containerColor = badgeColor, contentColor = DialogBackgroundDark) {
                    Text(badge.toString(), fontSize = 9.sp)
                }
            }
        }
    ) {
        TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (selected) DialogCyan else DialogTextSecondary
            )
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun NoteComposeContent(
    noteText: String,
    selectedType: NoteType,
    onNoteTextChange: (String) -> Unit,
    onTypeChange: (NoteType) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NoteType.entries.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { onTypeChange(type) },
                    label = { Text(type.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (type) {
                            NoteType.BUG -> DialogRed.copy(alpha = 0.3f)
                            NoteType.FEATURE -> DialogGreen.copy(alpha = 0.3f)
                            NoteType.NOTE -> DialogCyan.copy(alpha = 0.3f)
                        },
                        selectedLabelColor = DialogTextPrimary
                    )
                )
            }
        }
        OutlinedTextField(
            value = noteText,
            onValueChange = onNoteTextChange,
            label = { Text("Describe the ${selectedType.label.lowercase()}…") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = DialogCyan,
                unfocusedBorderColor = DialogSurfaceVariant,
                cursorColor = DialogCyan,
                focusedLabelColor = DialogCyan,
                unfocusedLabelColor = DialogTextSecondary
            ),
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun QueueContent(
    queuedNotes: List<QueuedNote>,
    onRemoveFromQueue: (String) -> Unit
) {
    if (queuedNotes.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("No notes queued", color = DialogTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(queuedNotes, key = { it.id }) { note ->
                NoteCard(
                    typeLabel = note.noteType.label,
                    typeColor = noteTypeColor(note.noteType),
                    screenLabel = note.screenLabel,
                    noteText = note.noteText,
                    sourceFile = note.sourceFile,
                    trailingContent = {
                        IconButton(
                            onClick = { onRemoveFromQueue(note.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Text("✕", color = DialogTextSecondary, fontSize = 12.sp)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SavedContent(
    savedNotesByScreen: Map<String, List<DebugNoteEntry>>
) {
    if (savedNotesByScreen.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No submitted notes yet",
                color = DialogTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            savedNotesByScreen.entries
                .sortedBy { it.key }
                .forEach { (_, notes) ->
                    val screenLabel = notes.firstOrNull()?.screenLabel ?: "Unknown"
                    item(key = "header_$screenLabel") {
                        Text(
                            "📍 $screenLabel (${notes.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = DialogGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(notes, key = { it.id }) { entry ->
                        val noteType = runCatching { NoteType.valueOf(entry.noteType) }
                            .getOrDefault(NoteType.NOTE)
                        NoteCard(
                            typeLabel = noteType.label,
                            typeColor = noteTypeColor(noteType),
                            screenLabel = entry.timestamp,
                            noteText = entry.noteText,
                            sourceFile = entry.sourceFile,
                            trailingContent = null
                        )
                    }
                }
        }
    }
}

@Composable
private fun NoteCard(
    typeLabel: String,
    typeColor: Color,
    screenLabel: String,
    noteText: String,
    sourceFile: String,
    trailingContent: (@Composable () -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DialogSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = typeColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        screenLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = DialogTextSecondary
                    )
                }
                Text(
                    noteText,
                    style = MaterialTheme.typography.bodySmall,
                    color = DialogTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    sourceFile,
                    style = MaterialTheme.typography.labelSmall,
                    color = DialogCyan.copy(alpha = 0.7f)
                )
            }
            trailingContent?.invoke()
        }
    }
}

private fun noteTypeColor(type: NoteType): Color = when (type) {
    NoteType.BUG -> DialogRed
    NoteType.FEATURE -> DialogGreen
    NoteType.NOTE -> DialogCyan
}
