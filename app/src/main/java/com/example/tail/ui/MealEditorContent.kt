package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.meal.MacroRatings
import com.example.tail.data.meal.Macronutrients
import com.example.tail.data.meal.MealLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EDIT_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val EDIT_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

/** Formats a macro value for a text field (no trailing ".0"). */
fun formatMacroNum(d: Double): String =
    if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

/**
 * Full editor for a single [MealLog] — every aspect is editable:
 * title, date & time, calories, macro grams, simple 1-3 macro ratings,
 * ingredient tags, summary, vegan flag, photos (removable), and the voice
 * transcript. Shared by the meal screen's editor dialog and the capture
 * review screen, so a meal can be corrected anywhere it appears.
 */
@Composable
fun MealEditorContent(
    log: MealLog,
    note: String? = null,
    filesDir: File? = null,
    allowTimeEdit: Boolean = true,
    allowDelete: Boolean = false,
    isListening: Boolean = false,
    voiceError: String? = null,
    /** Live transcript from an in-dialog mic session (appended once per result). */
    externalTranscript: String? = null,
    onVoiceRecord: (() -> Unit)? = null,
    onSave: (MealLog) -> Unit,
    onDelete: ((MealLog) -> Unit)? = null
) {
    var title by remember { mutableStateOf(log.title) }
    var calories by remember { mutableStateOf(if (log.calories > 0) log.calories.toString() else "") }
    var protein by remember { mutableStateOf(formatMacroNum(log.macronutrients.proteinGrams)) }
    var carbs by remember { mutableStateOf(formatMacroNum(log.macronutrients.carbsGrams)) }
    var fat by remember { mutableStateOf(formatMacroNum(log.macronutrients.fatGrams)) }
    var summary by remember { mutableStateOf(log.summary ?: "") }
    var tags by remember { mutableStateOf(log.ingredientsDetected) }
    var ratings by remember { mutableStateOf(log.macroRatings ?: MacroRatings()) }
    var vegan by remember { mutableStateOf(log.isVeganVerified) }
    var removedImages by remember { mutableStateOf(setOf<String>()) }

    val logZdt = remember(log.id) {
        Instant.ofEpochMilli(log.timestamp).atZone(ZoneId.systemDefault())
    }
    var dateText by remember(log.id) { mutableStateOf(logZdt.toLocalDate().format(EDIT_DATE_FMT)) }
    var timeText by remember(log.id) { mutableStateOf(logZdt.toLocalTime().format(EDIT_TIME_FMT)) }
    var timeError by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf(log.voiceTranscript ?: "") }

    // A fresh mic result recorded while this editor is open replaces the
    // transcript field content (the field itself stays user-editable).
    LaunchedEffect(externalTranscript) {
        if (!externalTranscript.isNullOrBlank()) transcript = externalTranscript
    }

    val images = log.imageList().filter { it !in removedImages }

    Column {
        note?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // ── Photos ──────────────────────────────────────────────────────
        if (images.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                images.take(4).forEach { path ->
                    Box {
                        MealPhotoThumb(
                            path = path,
                            filesDir = filesDir,
                            modifier = Modifier.size(64.dp)
                        )
                        if (allowDelete || allowTimeEdit) {
                            IconButton(
                                onClick = { removedImages = removedImages + path },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove photo",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Meal") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // ── Time ────────────────────────────────────────────────────────
        if (allowTimeEdit) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it; timeError = false },
                    label = { Text("Date (yyyy-MM-dd)") },
                    modifier = Modifier.weight(1.4f),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { timeText = it; timeError = false },
                    label = { Text("Time (HH:mm)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }
            if (timeError) {
                Text(
                    "Invalid date/time — use yyyy-MM-dd and HH:mm (24h)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = calories,
                onValueChange = { calories = it.filter { c -> c.isDigit() } },
                label = { Text("Calories (kcal)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = protein,
                onValueChange = { protein = it },
                label = { Text("Protein g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedTextField(
                value = carbs,
                onValueChange = { carbs = it },
                label = { Text("Carbs g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedTextField(
                value = fat,
                onValueChange = { fat = it },
                label = { Text("Fat g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        // ── Simple 1-3 macro ratings ────────────────────────────────────
        Spacer(modifier = Modifier.height(10.dp))
        Text("Ratings (tap: 1 low · 2 med · 3 high)", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MacroRatingSelector("P", Color(0xFF1565C0), ratings.protein) {
                ratings = ratings.copy(protein = it)
            }
            MacroRatingSelector("C", Color(0xFF2E7D32), ratings.carbs) {
                ratings = ratings.copy(carbs = it)
            }
            MacroRatingSelector("F", Color(0xFFF57F17), ratings.fat) {
                ratings = ratings.copy(fat = it)
            }
        }

        // ── Ingredient tags ─────────────────────────────────────────────
        Spacer(modifier = Modifier.height(10.dp))
        TagChipsEditor(tags = tags, onChange = { tags = it })

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = summary,
            onValueChange = { summary = it },
            label = { Text("Summary") },
            modifier = Modifier.fillMaxWidth()
        )

        // ── Voice transcript ────────────────────────────────────────────
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = transcript,
            onValueChange = { transcript = it },
            label = { Text("🎤 Voice transcript") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        if (onVoiceRecord != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onVoiceRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isListening) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isListening) "Listening… tap to stop" else "🎤 Describe by voice (AI parses)")
            }
            voiceError?.let {
                Text("🎤 $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌱 Vegan", fontSize = 13.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(
                checked = vegan,
                onCheckedChange = { vegan = it }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                // Validate the date/time before saving
                var newTimestamp = log.timestamp
                if (allowTimeEdit) {
                    try {
                        val d = LocalDate.parse(dateText.trim(), EDIT_DATE_FMT)
                        val t = LocalTime.parse(timeText.trim(), EDIT_TIME_FMT)
                        newTimestamp = d.atTime(t)
                            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        timeError = false
                    } catch (e: Exception) {
                        timeError = true
                        return@Button
                    }
                }
                val keptImages = images
                onSave(
                    log.copy(
                        title = title.ifBlank { "Meal" },
                        timestamp = newTimestamp,
                        imageUri = keptImages.firstOrNull(),
                        imageUris = keptImages,
                        calories = calories.toIntOrNull() ?: 0,
                        macronutrients = Macronutrients(
                            proteinGrams = protein.toDoubleOrNull() ?: 0.0,
                            carbsGrams = carbs.toDoubleOrNull() ?: 0.0,
                            fatGrams = fat.toDoubleOrNull() ?: 0.0
                        ),
                        macroRatings = if (ratings.isSet()) ratings else null,
                        ingredientsDetected = tags,
                        summary = summary.ifBlank { null },
                        isVeganVerified = vegan,
                        voiceTranscript = transcript.ifBlank { null }
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save") }

        if (onDelete != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onDelete(log) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete meal", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * Simple 1-3 rating selector for one macro: three tappable dots that fill
 * up to the selected level. Works without the AI — the user's rough guess.
 */
@Composable
fun MacroRatingSelector(
    label: String,
    color: Color,
    value: Int,
    onChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(20.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..3).forEach { level ->
                val filled = value >= level
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(
                            if (filled) color else color.copy(alpha = 0.15f),
                            CircleShape
                        )
                        .clickable { onChange(if (value == level) 0 else level) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        level.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (filled) Color.White else color
                    )
                }
            }
        }
    }
}

/**
 * Ingredient TAG editor: existing tags render as removable chips; a text
 * field + button adds new ones. Tags (not a plain list) so meals can later
 * be searched, filtered, and graphed by ingredient.
 */
@Composable
fun TagChipsEditor(tags: List<String>, onChange: (List<String>) -> Unit) {
    var newTag by remember { mutableStateOf("") }

    Text("Ingredient tags", fontSize = 12.sp, fontWeight = FontWeight.Medium)
    if (tags.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Wrap manually in rows of chips (simple two-row cap keeps the
            // editor compact; the full list stays visible in the editor list)
            tags.take(8).forEach { tag ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp)
                    ) {
                        Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove $tag",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onChange(tags.filter { it != tag }) }
                                .padding(2.dp)
                        )
                    }
                }
            }
        }
        if (tags.size > 8) {
            Text("+${tags.size - 8} more", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newTag,
            onValueChange = { newTag = it },
            label = { Text("Add tag") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(
            onClick = {
                val t = newTag.trim().lowercase()
                if (t.isNotBlank() && t !in tags) onChange(tags + t)
                newTag = ""
            }
        ) { Icon(Icons.Default.Add, contentDescription = "Add tag") }
    }
}

/** Small photo thumbnail that loads from internal storage (null → placeholder). */
@Composable
fun MealPhotoThumb(
    path: String,
    filesDir: File?,
    modifier: Modifier = Modifier,
    corner: Int = 8
) {
    val bitmap = remember(path) {
        try {
            val file = if (path.startsWith("/")) File(path) else File(filesDir, path)
            if (file.exists()) android.graphics.BitmapFactory.decodeFile(file.absolutePath) else null
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Meal photo",
            modifier = modifier.clip(RoundedCornerShape(corner.dp))
        )
    } else {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(corner.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
