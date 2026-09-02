package com.example.tail.ui

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Calendar

/**
 * Dialog for selecting which habits appear in the map screen's stats panel.
 *
 * - Lists all available habits with checkboxes.
 * - For text-input habits that are selected, shows a "Show text" toggle
 *   so the user can opt in to displaying the text entries in the stats area.
 */
@Composable
fun MapSettingsDialog(
    allHabits: List<String>,
    selectedHabits: Set<String>,
    textInputHabits: Set<String>,
    showTextHabits: Set<String>,
    mapMainHabit: String?,
    mapHideZeroDays: Boolean,
    mapBeginDate: String,
    onToggleHabit: (String) -> Unit,
    onToggleShowText: (String) -> Unit,
    onToggleMainHabit: (String) -> Unit,
    onToggleHideZeroDays: () -> Unit,
    onSetBeginDate: (String) -> Unit,
    onUpdatePopulations: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D0D0D), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                // ── Map Settings Section ────────────────────────────────────────
                item {
                    Text(
                        text = "Map Settings",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Configure map display options",
                        color = Color(0xFF777777),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF222222))
                    Spacer(Modifier.height(6.dp))

                    // Hide 0 days toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Hide 0 days",
                            color = Color.White,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = mapHideZeroDays,
                            onCheckedChange = { onToggleHideZeroDays() },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = Color(0xFFFFAA00),
                                checkedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF333333),
                                uncheckedThumbColor = Color(0xFF888888)
                            )
                        )
                    }

                    // Begin date selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start date",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (mapBeginDate.isEmpty()) "Default (earliest data)" else mapBeginDate,
                                color = Color(0xFF888888),
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = { showDatePicker = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color(0xFFFFAA00)
                            )
                        ) {
                            Text("Change", fontSize = 12.sp)
                        }
                        if (mapBeginDate.isNotEmpty()) {
                            TextButton(
                                onClick = { onSetBeginDate("") },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = Color(0xFF888888)
                                )
                            ) {
                                Text("Reset", fontSize = 12.sp)
                            }
                        }
                    }

                    // Population data refresh (country facts section)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Population data",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Refresh country populations (needs internet)",
                                color = Color(0xFF888888),
                                fontSize = 11.sp
                            )
                        }
                        TextButton(
                            onClick = onUpdatePopulations,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = Color(0xFFFFAA00)
                            )
                        ) {
                            Text("Update", fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF222222))
                    Spacer(Modifier.height(6.dp))

                    // ── Habits Section ─────────────────────────────────────────────
                    Text(
                        text = "Map Stats",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Choose habits to show in the stats panel",
                        color = Color(0xFF777777),
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF222222))
                    Spacer(Modifier.height(6.dp))
                }

                items(allHabits) { habitName ->
                    val isSelected = habitName in selectedHabits
                    val isTextInput = habitName in textInputHabits
                    val showText = habitName in showTextHabits
                    val isMainHabit = mapMainHabit == habitName

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() },
                                    onClick = { onToggleHabit(habitName) }
                                )
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleHabit(habitName) },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFFFAA00),
                                    uncheckedColor = Color(0xFF666666)
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = habitName,
                                color = if (isSelected) Color.White else Color(0xFF999999),
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (isTextInput) {
                                Text(
                                    text = "✎",
                                    color = Color(0xFF888888),
                                    fontSize = 12.sp
                                )
                            }
                            if (isMainHabit) {
                                Text(
                                    text = "★",
                                    color = Color(0xFFFFAA00),
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        // Show-text toggle for selected text-input habits
                        if (isSelected && isTextInput) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Show text in stats",
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = showText,
                                    onCheckedChange = { onToggleShowText(habitName) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = Color(0xFFFFAA00),
                                        checkedThumbColor = Color.White,
                                        uncheckedTrackColor = Color(0xFF333333),
                                        uncheckedThumbColor = Color(0xFF888888)
                                    )
                                )
                            }
                        }

                        // "Use as main" toggle for selected habits
                        if (isSelected) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Use as main",
                                    color = Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = isMainHabit,
                                    onCheckedChange = { onToggleMainHabit(habitName) },
                                    colors = SwitchDefaults.colors(
                                        checkedTrackColor = Color(0xFFFFAA00),
                                        checkedThumbColor = Color.White,
                                        uncheckedTrackColor = Color(0xFF333333),
                                        uncheckedThumbColor = Color(0xFF888888)
                                    )
                                )
                            }
                        }
                    }
                }

                // Done button at bottom
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Done", color = Color(0xFFFFAA00), fontSize = 13.sp)
                        }
                    }
                }
            }

            // Date picker dialog (outside LazyColumn)
            if (showDatePicker) {
                val context = LocalContext.current
                val calendar = Calendar.getInstance()
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH)
                val day = calendar.get(Calendar.DAY_OF_MONTH)
                
                val datePickerDialog = android.app.DatePickerDialog(
                    context,
                    { _, selectedYear, selectedMonth, selectedDay ->
                        val selectedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
                        onSetBeginDate(selectedDate)
                        showDatePicker = false
                    },
                    year, month, day
                )
                datePickerDialog.show()
            }
        }
    }
}
