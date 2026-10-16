package com.example.tail.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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

/**
 * Edit-mode editor for the "characteristics" sub-feature of a text-input
 * habit with options: manages WHICH characteristic names exist for the
 * habit (add / rename / remove) and, for every known option, which of
 * those characteristics apply to it (tappable tag chips).
 *
 * Nothing is persisted until Apply is pressed; Cancel discards everything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextInputCharacteristicsEditorDialog(
    habitName: String,
    initialCharacteristics: Set<String>,
    options: List<String>,
    initialAssignments: Map<String, Set<String>>,
    onSaveDefinitions: (Set<String>) -> Unit,
    onApplyAssignments: (Map<String, Set<String>>) -> Unit,
    onDismiss: () -> Unit
) {
    // Local editable copies — committed only on Apply.
    val characteristics = remember {
        mutableStateOf(initialCharacteristics.sorted().toMutableList())
    }
    // option text → selected characteristics (only non-empty entries matter)
    val assignments = remember {
        mutableStateMapOf<String, Set<String>>().apply {
            initialAssignments.forEach { (k, v) -> if (v.isNotEmpty()) put(k, v) }
        }
    }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun renameCharacteristic(old: String, new: String) {
        val trimmed = new.trim()
        if (trimmed.isEmpty() || trimmed == old || trimmed in characteristics.value) return
        characteristics.value = characteristics.value.map { if (it == old) trimmed else it }.toMutableList()
        // Remap every option assignment that referenced the old name
        val keys = assignments.keys.toList()
        for (key in keys) {
            val set = assignments[key] ?: continue
            if (old in set) {
                val updated = (set - old) + trimmed
                if (updated.isEmpty()) assignments.remove(key) else assignments[key] = updated
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(habitName, color = Color(0xFF88DDFF), fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text("Characteristics", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))

            // ── Characteristic definitions ────────────────────────────────
            if (characteristics.value.isEmpty()) {
                Text(
                    "No characteristics yet — add some below.",
                    color = Color(0xFF666666), fontSize = 11.sp, fontStyle = FontStyle.Italic
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                ) {
                    LazyColumn(Modifier.padding(vertical = 4.dp)) {
                        items(characteristics.value) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (renameTarget == name) {
                                    OutlinedTextField(
                                        value = renameText,
                                        onValueChange = { renameText = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF88DDFF),
                                            unfocusedBorderColor = Color(0xFF555555),
                                            cursorColor = Color(0xFF88DDFF)
                                        )
                                    )
                                    TextButton(onClick = {
                                        renameCharacteristic(name, renameText)
                                        renameTarget = null
                                    }) { Text("✓", color = Color(0xFF88DDFF), fontSize = 14.sp) }
                                    TextButton(onClick = { renameTarget = null }) {
                                        Text("✕", color = Color(0xFF666666), fontSize = 13.sp)
                                    }
                                } else {
                                    Text(
                                        name,
                                        color = Color(0xFFCCCCCC),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        renameTarget = name
                                        renameText = name
                                    }) { Text("✎", color = Color(0xFF888888), fontSize = 14.sp) }
                                    TextButton(onClick = {
                                        characteristics.value =
                                            characteristics.value.filter { it != name }.toMutableList()
                                        val keys = assignments.keys.toList()
                                        for (key in keys) {
                                            val set = (assignments[key] ?: continue) - name
                                            if (set.isEmpty()) assignments.remove(key) else assignments[key] = set
                                        }
                                    }) { Text("✕", color = Color(0xFF666666), fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New characteristic", color = Color(0xFF666666), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF88DDFF),
                        unfocusedBorderColor = Color(0xFF555555),
                        cursorColor = Color(0xFF88DDFF)
                    )
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty() && trimmed !in characteristics.value) {
                            characteristics.value = (characteristics.value + trimmed).sorted().toMutableList()
                        }
                        newName = ""
                    },
                    enabled = newName.trim().isNotEmpty() && newName.trim() !in characteristics.value,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0A3A4A),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    )
                ) { Text("Add", color = Color(0xFF88DDFF), fontSize = 12.sp) }
            }

            // ── Per-option assignment (tag chips) ────────────────────────
            if (characteristics.value.isNotEmpty() && options.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tap chips to tag options",
                    color = Color(0xFF888888), fontSize = 11.sp
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                ) {
                    LazyColumn(Modifier.padding(vertical = 4.dp)) {
                        items(options) { option ->
                            val chosen = assignments[option] ?: emptySet()
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Text(option, color = Color(0xFFCCCCCC), fontSize = 12.sp, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    characteristics.value.forEach { char ->
                                        val on = char in chosen
                                        Text(
                                            char,
                                            fontSize = 9.sp,
                                            color = if (on) Color(0xFF1E1E1E) else Color(0xFF88AACC),
                                            modifier = Modifier
                                                .background(
                                                    if (on) Color(0xFF88DDFF) else Color(0xFF1A2A35),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    val updated = if (on) chosen - char else chosen + char
                                                    if (updated.isEmpty()) assignments.remove(option)
                                                    else assignments[option] = updated
                                                }
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = {
                        onSaveDefinitions(characteristics.value.toSet())
                        onApplyAssignments(assignments.toMap())
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A3A4A))
                ) { Text("Apply", color = Color(0xFF88DDFF), fontSize = 12.sp) }
            }
        }
    }
}
