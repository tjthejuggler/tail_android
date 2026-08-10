package com.example.tail.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.QuickCaptureActivity
import com.example.tail.data.meal.MealLog
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private val mealDateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

/**
 * Full-screen dialog showing the Meal Detail & Logging Panel for a meal habit.
 *
 * Contains:
 *  - Daily calorie summary
 *  - Camera capture FAB (launches [QuickCaptureActivity])
 *  - Manual text entry for quick logging
 *  - Meal history feed with photo thumbnails and macro breakdown
 */
@Composable
fun MealDetailDialog(
    habitName: String,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit,
    incrementAlreadyDone: Boolean = false,
    /** Only meal logs from this date are shown in the history feed. */
    selectedDate: LocalDate = LocalDate.now()
) {
    val context = LocalContext.current
    val allMealLogs by viewModel.mealLogsForHabit.collectAsState()

    // Filter to only the selected day's meals
    val mealLogs = remember(allMealLogs, selectedDate) {
        allMealLogs.filter { isOnDate(it, selectedDate) }
    }
    val dayCalories = mealLogs.sumOf { it.calories }

    // Load meal logs when the dialog opens
    LaunchedEffect(habitName) {
        viewModel.loadMealLogs(habitName)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // ── Top bar ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
                Text(
                    text = habitName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Camera trigger button
                IconButton(onClick = {
                    val intent = Intent(context, QuickCaptureActivity::class.java).apply {
                        putExtra(QuickCaptureActivity.EXTRA_HABIT_NAME, habitName)
                    }
                    context.startActivity(intent)
                }) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = "Capture meal",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Daily summary ─────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    MacroSummaryItem(
                        if (selectedDate == LocalDate.now()) "Today" else selectedDate.toString(),
                        "$dayCalories", "kcal"
                    )
                    MacroSummaryItem("Meals", "${mealLogs.size}", "day")
                    MacroSummaryItem(
                        "Avg cal",
                        if (mealLogs.isNotEmpty()) (dayCalories / mealLogs.size).toString() else "0",
                        "/meal"
                    )
                }
            }

            // ── Manual entry ──────────────────────────────────────────────
            ManualEntrySection(
                onAdd = { title, calories ->
                    viewModel.addManualMealLog(
                        habitName, title, calories,
                        skipIncrement = incrementAlreadyDone
                    )
                }
            )

            // ── Meal history feed ─────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(mealLogs, key = { it.id }) { log ->
                    MealLogCard(log)
                }
            }
        }
    }
}

/** Quick macro summary stat in the daily summary card. */
@Composable
private fun MacroSummaryItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = "$label ($unit)",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

/** Manual text entry section for quick meal logging without a photo. */
@Composable
private fun ManualEntrySection(onAdd: (title: String, calories: Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var caloriesText by remember { mutableStateOf("") }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Quick Log", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Meal name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = caloriesText,
                    onValueChange = { caloriesText = it.filter { c -> c.isDigit() } },
                    label = { Text("kcal") },
                    singleLine = true,
                    modifier = Modifier.width(90.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        val cal = caloriesText.toIntOrNull() ?: 0
                        onAdd(title.trim(), cal)
                        title = ""
                        caloriesText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Entry", fontSize = 13.sp)
            }
        }
    }
}

/** A single meal log entry card in the history feed. */
@Composable
private fun MealLogCard(log: MealLog) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Photo thumbnail (if available)
            if (log.imageUri != null) {
                val bitmap = remember(log.imageUri) {
                    try {
                        val repo = com.example.tail.data.meal.MealLogRepository(context)
                        val file = repo.resolveImage(log.imageUri)
                        file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Meal photo",
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    // Placeholder for missing image
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            // Meal info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (!log.summary.isNullOrBlank()) {
                    Text(
                        text = log.summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                // Macros row
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (log.calories > 0) {
                        MacroChip("${log.calories} kcal", Color(0xFFE65100))
                    }
                    if (log.macronutrients.proteinGrams > 0) {
                        MacroChip("P ${log.macronutrients.proteinGrams.toInt()}g", Color(0xFF1565C0))
                    }
                    if (log.macronutrients.carbsGrams > 0) {
                        MacroChip("C ${log.macronutrients.carbsGrams.toInt()}g", Color(0xFF2E7D32))
                    }
                    if (log.macronutrients.fatGrams > 0) {
                        MacroChip("F ${log.macronutrients.fatGrams.toInt()}g", Color(0xFFF57F17))
                    }
                }
                Text(
                    text = mealDateFormat.format(Date(log.timestamp)),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            // Vegan badge
            if (log.isVeganVerified) {
                Text(
                    text = "🌱",
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

/** Small coloured chip for displaying a macro value. */
@Composable
private fun MacroChip(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 10.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

/** Checks if a meal log's timestamp falls on the given date. */
private fun isOnDate(log: MealLog, date: LocalDate): Boolean {
    val logDate = java.time.Instant.ofEpochMilli(log.timestamp)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
    return logDate == date
}
