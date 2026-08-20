package com.example.tail.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.material3.LocalTextStyle
import com.example.tail.QuickCaptureActivity
import com.example.tail.data.meal.MealLog
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val mealTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/**
 * Full-screen Meal Detail panel for a meal habit.
 *
 * Redesigned around the "every meal gets a card" principle:
 *  - Capture row: 📷 photo, 🖼️ gallery upload, 🎤 voice describe (AI parses),
 *    ⚡ quick log — every path creates (or merges into) a card.
 *  - Every card is clickable → full editor (time, macros, ratings, tags,
 *    transcript, photos, delete).
 *  - Ingredient tags double as filters (tap to narrow the feed).
 *  - Photos taken within the group window merge into one meal/increment.
 */
@Composable
fun MealDetailDialog(
    habitName: String,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit,
    incrementAlreadyDone: Boolean = false,
    /** Only meal logs from this date are shown in the history feed. */
    selectedDate: LocalDate = LocalDate.now(),
    /**
     * When set, the full editor opens automatically for this meal log — used
     * by the timestamp editor's pencil action to jump straight to the meal
     * logged at a specific increment time.
     */
    focusLogId: String? = null
) {
    val context = LocalContext.current
    val allMealLogs by viewModel.mealLogsForHabit.collectAsState()
    val voiceStatus by viewModel.mealVoiceStatus.collectAsState()
    val pendingCount by viewModel.mealPendingCount.collectAsState()

    // Filter to only the selected day's meals (newest first)
    val mealLogs = remember(allMealLogs, selectedDate) {
        allMealLogs.filter { isOnDate(it, selectedDate) }.sortedByDescending { it.timestamp }
    }
    val dayCalories = mealLogs.sumOf { it.calories }

    // ── Tag index across ALL logs (filtering spans full history) ─────────
    val tagIndex = remember(allMealLogs) {
        allMealLogs.flatMap { it.ingredientsDetected }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .map { it.key to it.value }
            .take(24)
    }
    var activeTags by remember { mutableStateOf(setOf<String>()) }
    val visibleLogs = remember(mealLogs, activeTags) {
        if (activeTags.isEmpty()) mealLogs
        else mealLogs.filter { log -> activeTags.any { it in log.ingredientsDetected } }
    }

    // ── Editor + voice state (shared between capture row and editor) ─────
    var editingLog by remember { mutableStateOf<MealLog?>(null) }

    // Jump straight into the editor for a focused meal (timestamp editor pencil).
    LaunchedEffect(focusLogId, mealLogs) {
        if (focusLogId != null) {
            editingLog = mealLogs.firstOrNull { it.id == focusLogId }
        }
    }
    var showQuickLog by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var editorTranscript by remember { mutableStateOf<String?>(null) }
    val (speech, isListening) = rememberSpeechRecognizer(
        onResult = { text ->
            if (editingLog != null) editorTranscript = text
            else viewModel.processVoiceMeal(habitName, text)
        },
        onError = { voiceError = it }
    )

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceError = null
            speech.start()
        } else {
            voiceError = "Microphone permission needed"
        }
    }
    val startMic: () -> Unit = {
        voiceError = null
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) speech.toggle()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.addMealPhotoFromUri(habitName, uri)
    }

    // Load meal logs when the dialog opens
    LaunchedEffect(habitName) {
        viewModel.loadMealLogs(habitName)
        viewModel.clearMealVoiceStatus()
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

            // ── Capture row: every path creates (or merges) a card ────────
            CaptureActionRow(
                isListening = isListening,
                onPhoto = {
                    val intent = Intent(context, QuickCaptureActivity::class.java).apply {
                        putExtra(QuickCaptureActivity.EXTRA_HABIT_NAME, habitName)
                    }
                    context.startActivity(intent)
                },
                onGallery = {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onVoice = startMic,
                onQuick = { showQuickLog = !showQuickLog }
            )

            // Voice / queue status feedback
            voiceError?.let {
                Text(
                    "🎤 $it",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            voiceStatus?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            if (pendingCount > 0) {
                Text(
                    "⏳ $pendingCount photo(s) queued for AI…",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // ── Quick log (no photo, no AI — details editable on the card) ─
            if (showQuickLog) {
                ManualEntrySection(
                    onAdd = { title, calories ->
                        viewModel.addManualMealLog(
                            habitName, title, calories,
                            skipIncrement = incrementAlreadyDone
                        )
                    }
                )
            }

            // ── Tag filter chips ──────────────────────────────────────────
            if (tagIndex.isNotEmpty()) {
                TagFilterRow(
                    tags = tagIndex,
                    activeTags = activeTags,
                    onToggle = { tag ->
                        activeTags = if (tag in activeTags) activeTags - tag else activeTags + tag
                    },
                    onClear = { activeTags = emptySet() }
                )
            }

            // ── Meal history feed (clickable cards) ───────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleLogs, key = { it.id }) { log ->
                    MealLogCard(
                        log = log,
                        filesDir = context.filesDir,
                        onClick = { editingLog = log }
                    )
                }
                if (visibleLogs.isEmpty()) {
                    item {
                        Text(
                            if (activeTags.isEmpty()) "No meals recorded yet."
                            else "No meals match the selected tags.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    // ── Full editor dialog (opened by tapping any card) ──────────────────
    editingLog?.let { log ->
        MealEditEntryDialog(
            log = log,
            filesDir = context.filesDir,
            isListening = isListening,
            voiceError = voiceError,
            pendingTranscript = editorTranscript,
            onVoiceRecord = startMic,
            onSave = { updated ->
                viewModel.updateMealLog(habitName, updated, oldTimestamp = log.timestamp)
                editingLog = null
            },
            onDelete = { deleted ->
                viewModel.deleteMealLog(habitName, deleted.id)
                editingLog = null
            },
            onDismiss = { editingLog = null }
        )
    }
}

/** Full-screen editor dialog wrapping the shared [MealEditorContent]. */
@Composable
private fun MealEditEntryDialog(
    log: MealLog,
    filesDir: File,
    isListening: Boolean,
    voiceError: String?,
    pendingTranscript: String?,
    onVoiceRecord: () -> Unit,
    onSave: (MealLog) -> Unit,
    onDelete: (MealLog) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close editor")
                }
                Text(
                    "Edit meal",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            MealEditorContent(
                log = log,
                filesDir = filesDir,
                allowTimeEdit = true,
                allowDelete = true,
                isListening = isListening,
                voiceError = voiceError,
                externalTranscript = pendingTranscript,
                onVoiceRecord = onVoiceRecord,
                onSave = onSave,
                onDelete = onDelete
            )
        }
    }
}

/** Four capture actions — every one of them results in a meal card. */
@Composable
private fun CaptureActionRow(
    isListening: Boolean,
    onPhoto: () -> Unit,
    onGallery: () -> Unit,
    onVoice: () -> Unit,
    onQuick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CaptureActionButton(
            icon = { Icon(Icons.Default.PhotoCamera, null, tint = Color.White) },
            label = "Photo",
            container = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            onClick = onPhoto
        )
        CaptureActionButton(
            icon = { Icon(Icons.Default.PhotoLibrary, null, tint = Color.White) },
            label = "Upload",
            container = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            onClick = onGallery
        )
        CaptureActionButton(
            icon = { Icon(Icons.Default.Mic, null, tint = Color.White) },
            label = if (isListening) "Listening…" else "Voice",
            container = if (isListening) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
            onClick = onVoice
        )
        CaptureActionButton(
            icon = { Icon(Icons.Default.Add, null, tint = Color.White) },
            label = "Quick",
            container = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
            onClick = onQuick
        )
    }
}

@Composable
private fun CaptureActionButton(
    icon: @Composable () -> Unit,
    label: String,
    container: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = container),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp, vertical = 10.dp
        )
    ) {
        icon()
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

/** Horizontally scrollable ingredient-tag filter chips with usage counts. */
@Composable
private fun TagFilterRow(
    tags: List<Pair<String, Int>>,
    activeTags: Set<String>,
    onToggle: (String) -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tags.forEach { (tag, count) ->
            FilterChip(
                selected = tag in activeTags,
                onClick = { onToggle(tag) },
                label = { Text("$tag ($count)", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
        if (activeTags.isNotEmpty()) {
            FilterChip(
                selected = false,
                onClick = onClear,
                label = { Text("✕ clear", fontSize = 12.sp) }
            )
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

/** Manual text entry for quick logging without a photo. */
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
            Text(
                "Time & details editable on the card afterwards",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

/**
 * A single meal entry card. ALWAYS clickable — opens the full editor where
 * time, macros, ratings, tags, transcript and photos can be changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MealLogCard(log: MealLog, filesDir: File, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
            // Photo thumbnail (first of possibly several images)
            MealPhotoThumb(
                path = log.imageList().firstOrNull() ?: "",
                filesDir = filesDir,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))

            // Meal info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mealTimeFormat.format(Date(log.timestamp)),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (log.needsDetails()) {
                    Text(
                        "tap to add details →",
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (!log.summary.isNullOrBlank()) {
                    Text(
                        text = log.summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                // Macros + ratings row
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
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
                    log.macroRatings?.let { r ->
                        RatingDots("P", r.protein, Color(0xFF1565C0))
                        RatingDots("C", r.carbs, Color(0xFF2E7D32))
                        RatingDots("F", r.fat, Color(0xFFF57F17))
                    }
                }
                // Ingredient tags — FlowRow wraps chips to the next line.
                // A plain Row squeezes trailing chips to zero width, which
                // rendered them one letter per line with no chip background.
                if (log.ingredientsDetected.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        log.ingredientsDetected.take(4).forEach { tag ->
                            Text(
                                "#$tag",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        if (log.ingredientsDetected.size > 4) {
                            Text(
                                "+${log.ingredientsDetected.size - 4}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Vegan badge + voice indicator
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (log.isVeganVerified) {
                    Text(text = "🌱", fontSize = 18.sp)
                }
                if (!log.voiceTranscript.isNullOrBlank()) {
                    Text(text = "🎤", fontSize = 14.sp)
                }
            }
        }
    }
}

/** Compact 1-3 rating dots shown on the card (e.g. "P ●●○"). */
@Composable
private fun RatingDots(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            (1..3).forEach { level ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            if (value >= level) color else color.copy(alpha = 0.2f),
                            CircleShape
                        )
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
    val logDate = Instant.ofEpochMilli(log.timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return logDate == date
}
