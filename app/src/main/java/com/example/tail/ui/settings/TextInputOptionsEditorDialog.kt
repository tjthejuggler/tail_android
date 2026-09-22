package com.example.tail.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

/** A single option as shown in the editor: text, usage count, description. */
data class TextOptionEntry(
    val text: String,
    val count: Int,
    val description: String
)

/**
 * Large editor popup for the text-input "options" sub-feature, opened via the
 * Edit button next to the Options toggle in the habit edit screen.
 *
 * Layout:
 *  - **Options** — every individual option text with its own identity and
 *    usage count. Multi-option groupings ("Iron\nC\nB12" saved as one shared-
 *    timestamp log value) are DECOMPOSED here: each part counts as its own
 *    option, so "Iron" is listed once whether logged alone or inside a combo.
 *  - **Groupings** — the exact multi-part combos, each shown on one line
 *    (parts joined with " + "). A grouping can be hidden (✕) so it no longer
 *    appears in the increment popup — history is NOT touched — or restored.
 *  - Selecting an option opens the inline editor: rename + private
 *    description.
 *
 * Rename semantics: RETROACTIVE — every log entry with the old text is
 * rewritten, INCLUDING the matching lines inside groupings.
 */
@Composable
internal fun TextInputOptionsEditorDialog(
    habitName: String,
    singles: List<Pair<String, Int>>,
    groupings: List<Pair<String, Int>>,
    hiddenGroupings: List<String>,
    descriptions: Map<String, String>,
    onRename: (oldText: String, newText: String) -> Unit,
    onSetDescription: (optionText: String, description: String) -> Unit,
    onHideGrouping: (grouping: String) -> Unit,
    onUnhideGrouping: (grouping: String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = remember(singles, descriptions) {
        singles.map { (text, count) ->
            TextOptionEntry(text, count, descriptions[text] ?: "")
        }
    }
    val hiddenSet = remember(hiddenGroupings) { hiddenGroupings.toSet() }

    // Currently selected option (null = no editor panel shown)
    var selected by remember { mutableStateOf<TextOptionEntry?>(null) }
    // Editable fields, re-seeded whenever a different option is selected
    var editName by remember { mutableStateOf("") }
    var editDescription by remember { mutableStateOf("") }

    LaunchedEffect(selected) {
        editName = selected?.text ?: ""
        editDescription = selected?.description ?: ""
    }

    // Cap the popup at 90% of screen height. The options list gets whatever
    // space is LEFT OVER (weight with fill=false), so the editor panel and
    // the submit buttons below it are always on-screen and reachable — the
    // list scrolls internally instead of pushing them off.
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.9f)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            // ── Title ──────────────────────────────────────────────────────
            Text(
                text = "Edit options",
                color = Color(0xFF88FFCC),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Renaming rewrites ALL past entries with that text. " +
                    "Descriptions are private notes, shown only here.",
                color = Color(0xFF666666),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .background(Color(0xFF111111), RoundedCornerShape(6.dp))
            ) {
                // ── Options (decomposed singles) ───────────────────────────
                if (options.isEmpty()) {
                    item {
                        Text(
                            text = "No past entries yet.",
                            color = Color(0xFF666666),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    item {
                        Text(
                            text = "OPTIONS",
                            color = Color(0xFF666666),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(options, key = { "opt:${it.text}" }) { option ->
                        OptionRow(
                            label = option.text,
                            sublabel = option.description.takeIf { it.isNotBlank() },
                            count = option.count,
                            isSelected = selected?.text == option.text,
                            accent = Color(0xFF88FFCC),
                            onClick = { selected = option },
                            actionIcon = {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit ${option.text}",
                                    tint = if (selected?.text == option.text)
                                        Color(0xFF88FFCC) else Color(0xFF888888),
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clickable { selected = option }
                                )
                            }
                        )
                    }
                }

                // ── Groupings (exact multi-part combos) ────────────────────
                val visibleGroupings = groupings.filter { it.first !in hiddenSet }
                val hiddenOnes = groupings.filter { it.first in hiddenSet }
                if (groupings.isNotEmpty()) {
                    item {
                        Text(
                            text = "GROUPINGS (multi-select combos)",
                            color = Color(0xFF666666),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(visibleGroupings, key = { "grp:${it.first}" }) { (text, count) ->
                        OptionRow(
                            label = text.replace("\n", " + "),
                            count = count,
                            isSelected = false,
                            accent = Color(0xFFFFAA00),
                            onClick = {},
                            actionIcon = {
                                // Hide from the increment popup — history untouched
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Hide grouping",
                                    tint = Color(0xFF888888),
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .clickable { onHideGrouping(text) }
                                )
                            }
                        )
                    }
                    if (hiddenOnes.isNotEmpty()) {
                        item {
                            Text(
                                text = "Hidden (not shown when logging):",
                                color = Color(0xFF555555),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 12.dp, top = 6.dp)
                            )
                        }
                        items(hiddenOnes, key = { "hid:${it.first}" }) { (text, count) ->
                            OptionRow(
                                label = text.replace("\n", " + "),
                                count = count,
                                isSelected = false,
                                accent = Color(0xFF444444),
                                onClick = {},
                                actionIcon = {
                                    Text(
                                        text = "restore",
                                        color = Color(0xFF888888),
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .padding(start = 6.dp)
                                            .clickable { onUnhideGrouping(text) }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // ── Editor panel for the selected option ───────────────────────
            val current = selected
            if (current != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Editing: ${current.text}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Option text", color = Color(0xFF888888)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF88FFCC),
                        unfocusedBorderColor = Color(0xFF555555),
                        cursorColor = Color(0xFF88FFCC)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = editDescription,
                    onValueChange = { editDescription = it },
                    label = { Text("Description (private)", color = Color(0xFF888888)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp, max = 110.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFAA00),
                        unfocusedBorderColor = Color(0xFF555555),
                        cursorColor = Color(0xFFFFAA00)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selected = null }) {
                        Text("Close editor", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    // Description is saved on its own — no rename required
                    Button(
                        onClick = { onSetDescription(current.text, editDescription) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00))
                    ) {
                        Text("Save description", color = Color(0xFFFFCC44))
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    // Rename applies retroactively to every log entry,
                    // including the matching lines inside groupings
                    Button(
                        onClick = {
                            val trimmed = editName.trim()
                            if (trimmed.isNotEmpty() && trimmed != current.text) {
                                onRename(current.text, trimmed)
                            }
                            selected = null
                        },
                        enabled = editName.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF004433),
                            disabledContainerColor = Color(0xFF2A2A2A)
                        )
                    ) {
                        Text("Rename", color = Color(0xFF88FFCC))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color(0xFF888888))
                }
            }
        }
    }
}

/** One row in the options/groupings list. */
@Composable
private fun OptionRow(
    label: String,
    count: Int,
    isSelected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    sublabel: String? = null,
    actionIcon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (isSelected) accent else Color(0xFFCCCCCC),
                fontSize = 13.sp,
                maxLines = 1
            )
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    color = Color(0xFF777777),
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
        Text(
            text = "×$count",
            color = Color(0xFF666666),
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(6.dp))
        actionIcon()
    }
    HorizontalDivider(
        color = Color(0xFF2A2A2A),
        thickness = 0.5.dp
    )
}
