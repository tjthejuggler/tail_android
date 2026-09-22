package com.example.tail.ui

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.SettingsRepository
import com.example.tail.data.meal.VisionHabitExecutor
import com.example.tail.data.meal.VisionProcessingWorker
import com.example.tail.data.meal.VisionQueueItem
import com.example.tail.data.meal.VisionQueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val REVIEW_TIME_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d · HH:mm")

/**
 * Quick Capture History — every quick capture the AI could not act on
 * automatically (NEEDS_REVIEW items of the vision queue).
 *
 * For each kept image the user can:
 *  - assign the habit the capture was intended for, then retry it through
 *    the same background pipeline a fresh capture uses;
 *  - delete the capture (and its image) outright.
 *
 * Reached from the habit-grid banner and from the app-open system
 * notification (deep link route `quick_capture_history`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureHistoryScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<VisionQueueItem>>(emptyList()) }
    var habits by remember { mutableStateOf<List<String>>(emptyList()) }
    /** habit assignment per item id (chosen in the picker, used by Retry). */
    var assignment by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pickerFor by remember { mutableStateOf<VisionQueueItem?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val queueRepo = VisionQueueRepository(context)
            val settings = try {
                SettingsRepository(context).settingsFlow.first()
            } catch (e: Exception) {
                null
            }
            val loaded = withContext(Dispatchers.IO) { queueRepo.reviewItems() }
            val habitList = (settings?.habitScreens?.flatMap { it.habitNames }
                ?: emptyList()) + (settings?.habitOrder ?: emptyList())
            habits = habitList
                .filter { it.isNotBlank() && !it.startsWith("app_link:") }
                .distinct()
                .sortedBy { it.lowercase() }
            // Default assignment: the item's original target, else the
            // single camera-eligible habit (the quick-capture target).
            val singleCamera = settings?.let {
                VisionHabitExecutor.cameraEligibleHabits(it).singleOrNull()
            }
            assignment = loaded.associate { item ->
                item.id to (item.habitId ?: singleCamera ?: "")
            }
            items = loaded
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun retry(item: VisionQueueItem) {
        val habit = assignment[item.id]?.takeIf { it.isNotBlank() }
        busyId = item.id
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                VisionQueueRepository(context).retryWithHabit(item.id, habit)
            }
            if (ok) {
                VisionProcessingWorker.enqueue(context)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (habit != null) "↻ Re-processing for $habit" else "↻ Re-processing capture",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            busyId = null
            reload()
        }
    }

    fun delete(item: VisionQueueItem) {
        busyId = item.id
        scope.launch {
            withContext(Dispatchers.IO) {
                VisionQueueRepository(context).deleteReviewItem(item.id)
            }
            busyId = null
            reload()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Capture History") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }

            items.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Nothing to review.\n\nEvery quick capture was processed — unrecognised " +
                        "photos will appear here with a notification.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    ReviewCard(
                        item = item,
                        filesDir = context.filesDir,
                        chosenHabit = assignment[item.id] ?: "",
                        isBusy = busyId == item.id,
                        onPickHabit = { pickerFor = item },
                        onRetry = { retry(item) },
                        onDelete = { delete(item) }
                    )
                }
            }
        }
    }

    // Habit picker dialog
    pickerFor?.let { pickerItem ->
        AlertDialog(
            onDismissRequest = { pickerFor = null },
            title = { Text("Which habit was this for?") },
            text = {
                LazyColumn(
                    modifier = Modifier.height(360.dp)
                ) {
                    items(habits) { habit ->
                        Text(
                            text = habit,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    assignment = assignment + (pickerItem.id to habit)
                                    pickerFor = null
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ReviewCard(
    item: VisionQueueItem,
    filesDir: File,
    chosenHabit: String,
    isBusy: Boolean,
    onPickHabit: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MealPhotoThumb(
                path = item.imagePath,
                filesDir = filesDir,
                modifier = Modifier.size(84.dp),
                corner = 10
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Instant.ofEpochMilli(item.timestamp)
                        .atZone(ZoneId.systemDefault())
                        .format(REVIEW_TIME_FMT),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.reviewNote?.take(220) ?: "Could not be processed",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Habit assignment row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPickHabit)
                .background(
                    MaterialTheme.colorScheme.surface,
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (chosenHabit.isBlank()) "Tap to choose the habit…" else chosenHabit,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = onRetry,
                enabled = !isBusy,
                modifier = Modifier.weight(1f)
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = !isBusy
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
