package com.example.tail.ui.debug

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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

/**
 * Dialog shown when the user taps the debug bubble.
 *
 * Two tabs:
 * - **Note** — compose a note with Save (draft) and Queue buttons
 * - **Queue** — view queued notes with Submit All and per-note delete
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugNoteDialog(
    screenContext: ScreenContext,
    currentRoute: String,
    draftText: String,
    draftType: NoteType,
    queuedNotes: List<QueuedNote>,
    noteCountOnScreen: Int,
    onDismiss: () -> Unit,
    onSaveDraft: (NoteType, String) -> Unit,
    onQueueNote: (NoteType, String) -> Unit,
    onSubmitQueue: () -> Unit,
    onRemoveFromQueue: (String) -> Unit
) {
    var noteText by remember(draftText) { mutableStateOf(draftText) }
    var selectedType by remember(draftType) { mutableStateOf(draftType) }
    var showQueue by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurfaceDark,
        shape = RoundedCornerShape(16.dp),
        title = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🐛 Debug Note",
                        style = MaterialTheme.typography.titleLarge,
                        color = DialogTextPrimary
                    )
                    if (queuedNotes.isNotEmpty()) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = DialogRed,
                                    contentColor = Color.White
                                ) {
                                    Text(queuedNotes.size.toString(), fontSize = 10.sp)
                                }
                            }
                        ) {
                            TextButton(onClick = { showQueue = !showQueue }) {
                                Text(
                                    if (showQueue) "✏️ Note" else "📋 Queue",
                                    color = DialogCyan,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
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
                Text(
                    "Functions: ${screenContext.sourceFunctions}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DialogCyan
                )
                if (noteCountOnScreen > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "$noteCountOnScreen note(s) on this screen",
                        style = MaterialTheme.typography.labelSmall,
                        color = DialogOrange
                    )
                }
            }
        },
        text = {
            if (showQueue && queuedNotes.isNotEmpty()) {
                // ── Queue view ──────────────────────────────────────────────
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(queuedNotes, key = { it.id }) { note ->
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
                                            note.noteType.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (note.noteType) {
                                                NoteType.BUG -> DialogRed
                                                NoteType.FEATURE -> DialogGreen
                                                NoteType.NOTE -> DialogCyan
                                            },
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            note.screenLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = DialogTextSecondary
                                        )
                                    }
                                    Text(
                                        note.noteText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DialogTextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        note.sourceFile,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DialogCyan.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveFromQueue(note.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Text("✕", color = DialogTextSecondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            } else {
                // ── Note compose view ───────────────────────────────────────
                showQueue = false

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Note type selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NoteType.entries.forEach { type ->
                            FilterChip(
                                selected = selectedType == type,
                                onClick = { selectedType = type },
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

                    // Note text input
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
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
        },
        confirmButton = {
            if (showQueue && queuedNotes.isNotEmpty()) {
                // Queue view buttons
                Button(
                    onClick = {
                        onSubmitQueue()
                        showQueue = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DialogGreen,
                        contentColor = DialogBackgroundDark
                    )
                ) {
                    Text("Submit All (${queuedNotes.size})")
                }
            } else {
                // Note compose buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Save draft
                    OutlinedButton(
                        onClick = { onSaveDraft(selectedType, noteText) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DialogTextPrimary)
                    ) {
                        Text("Save")
                    }
                    // Queue
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
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = DialogTextSecondary)
            }
        }
    )
}
