package com.example.tail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.QuickCaptureActionType
import java.util.UUID

/**
 * Quick Capture settings section.
 *
 * **Share target** — explains the system share-sheet entry ("📸 Quick
 * Capture") that accepts images from anywhere on the phone and routes them
 * to a Note, a Habit, or a PC Action.
 *
 * **Actions subsection** — full CRUD for Quick Capture action types.
 * Each type has a name (shown in the share picker's Action sub-selection)
 * and a description (the instruction sent VERBATIM with the shared image
 * to the PC quick_capture_assist Roo Code instance).
 */
@Composable
fun QuickCaptureSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    var editing by remember { mutableStateOf<QuickCaptureActionType?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column {
        Text("📸 Quick Capture", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Share an image from anywhere on the phone to \"📸 Quick Capture\" — " +
                "route it to a Note, a Habit, or a PC Action",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── Actions subsection ───────────────────────────────────────────
        Text("💻 Actions", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            text = "Action types offered under \"Action\" in the share target. The " +
                "description is sent to the PC quick capture assist together with the " +
                "shared image — write it as an instruction (e.g. \"Wanted movies\": " +
                "\"add the movies or series shown here to the movie_organizer app via " +
                "the api and mark them as wanted\").",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (settings.quickCaptureActionTypes.isEmpty()) {
            Text(
                "No action types yet — create one to enable the Action route.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            settings.quickCaptureActionTypes.forEach { type ->
                ActionTypeRow(
                    type = type,
                    onEdit = { editing = type; showEditor = true },
                    onDelete = {
                        viewModel.saveQuickCaptureActionTypes(
                            settings.quickCaptureActionTypes.filter { it.id != type.id }
                        )
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = { editing = null; showEditor = true }) {
            Text("+ New action type")
        }
    }

    if (showEditor) {
        ActionTypeEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { name, description ->
                val newList = if (editing == null) {
                    settings.quickCaptureActionTypes +
                        QuickCaptureActionType(UUID.randomUUID().toString(), name, description)
                } else {
                    settings.quickCaptureActionTypes.map {
                        if (it.id == editing!!.id) it.copy(name = name, description = description) else it
                    }
                }
                viewModel.saveQuickCaptureActionTypes(newList)
                showEditor = false
            }
        )
    }
}

@Composable
private fun ActionTypeRow(
    type: QuickCaptureActionType,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(type.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    type.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) { Text("Edit", fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onDelete) { Text("Delete", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ActionTypeEditorDialog(
    initial: QuickCaptureActionType?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    val valid = name.isNotBlank() && description.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New action type" else "Edit action type") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (shown in share picker)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Instruction sent to the PC with the image") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), description.trim()) },
                enabled = valid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
