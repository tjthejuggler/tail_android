package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val SurfaceDark = Color(0xFF1E1E1E)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)
private val ButtonPrimary = Color(0xFF2A6A4A)

/**
 * Popup dialog for viewing and writing notes/thoughts about a specific piece of advice.
 * Shows the advice text at the top, then a text field for the user's notes.
 */
@Composable
fun AdviceNoteDialog(
    adviceText: String,
    currentNotes: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onAddAdvice: (() -> Unit)? = null
) {
    var noteText by remember { mutableStateOf(currentNotes ?: "") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 480.dp)
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            // ── Header with optional "Add advice" button ────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "My Thoughts",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (onAddAdvice != null) {
                    TextButton(
                        onClick = onAddAdvice,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFF6ECF9A)
                        )
                    ) {
                        Text("Add advice", fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Advice text (read-only, scrollable) ─────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = adviceText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontStyle = FontStyle.Italic,
                        lineHeight = 16.sp
                    ),
                    color = Color(0xFFD0D0D0)
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Notes text field ─────────────────────────────────────────────
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text("Write your thoughts…", color = TextSecondary) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 120.dp, max = 240.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextSecondary,
                    unfocusedBorderColor = Color(0xFF444444),
                    cursorColor = TextSecondary
                ),
                maxLines = 12
            )

            Spacer(Modifier.height(12.dp))

            // ── Save button ─────────────────────────────────────────────────
            Button(
                onClick = {
                    onSave(noteText)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = TextPrimary
                )
            ) {
                Text("Save")
            }

            Spacer(Modifier.height(6.dp))

            // ── Cancel button ───────────────────────────────────────────────
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Cancel")
            }
        }
    }
}
