package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.AdviceItem

private val SurfaceDark = Color(0xFF1E1E1E)
private val BackgroundDark = Color(0xFF121212)
private val TextPrimary = Color(0xFFE0E0E0)
private val TextSecondary = Color(0xFF9E9E9E)
private val ButtonPrimary = Color(0xFF2A6A4A)

/**
 * Dialog for managing advice items.
 * Shows existing advice with edit/delete, plus an input field to add new ones.
 */
@Composable
fun AdviceDialog(
    adviceList: List<AdviceItem>,
    onAdd: (String) -> Unit,
    onUpdate: (AdviceItem, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var newText by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 520.dp)
                .background(SurfaceDark, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Text(
                text = "Habits Advice",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Add reminders or tips that will appear at the top of the habits screen.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            Spacer(Modifier.height(12.dp))

            // ── Add new advice ──────────────────────────────────────────────
            OutlinedTextField(
                value = newText,
                onValueChange = { newText = it },
                placeholder = { Text("Enter new advice…", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TextSecondary,
                    unfocusedBorderColor = Color(0xFF444444),
                    cursorColor = TextSecondary
                ),
                maxLines = 3
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onAdd(newText)
                    newText = ""
                },
                enabled = newText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = TextPrimary
                )
            ) {
                Text("Add Advice")
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF333333))
            Spacer(Modifier.height(8.dp))

            // ── Existing advice list ────────────────────────────────────────
            if (adviceList.isEmpty()) {
                Text(
                    text = "No advice yet. Add one above!",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(adviceList, key = { it.id }) { item ->
                        if (editingId == item.id) {
                            // ── Inline edit mode ────────────────────────────
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BackgroundDark, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = editText,
                                    onValueChange = { editText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        color = TextPrimary
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TextSecondary,
                                        unfocusedBorderColor = Color(0xFF444444),
                                        cursorColor = TextSecondary
                                    ),
                                    maxLines = 3
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            onUpdate(item, editText)
                                            editingId = null
                                        },
                                        enabled = editText.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = ButtonPrimary,
                                            contentColor = TextPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Save", fontSize = 12.sp) }
                                    OutlinedButton(
                                        onClick = { editingId = null },
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = TextPrimary
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Cancel", fontSize = 12.sp) }
                                }
                            }
                        } else {
                            // ── Display mode ────────────────────────────────
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(BackgroundDark, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD0D0D0),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(
                                    onClick = {
                                        editingId = item.id
                                        editText = item.text
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDelete(item.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFF999999),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Close button ────────────────────────────────────────────────
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Text("Close")
            }
        }
    }
}
