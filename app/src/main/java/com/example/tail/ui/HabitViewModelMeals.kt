package com.example.tail.ui


// Split out of HabitViewModel.kt (2026-08-29) to keep individual
// Kotlin source files small enough for IR lowering on this machine.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.backup.BackupResult
import com.example.tail.data.backup.HabitRestorePreview
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconGeneratorService
import com.example.tail.data.AiIconRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.ChessComRepository
import com.example.tail.data.BridgeMovie
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.GitHubApiException
import com.example.tail.data.GitHubMetric
import com.example.tail.data.GitHubRateLimitException
import com.example.tail.data.GitHubRepository
import com.example.tail.data.ImportResult
import com.example.tail.data.MovieBridgeService
import com.example.tail.data.MovieCacheStore
import com.example.tail.data.HabitNotification
import com.example.tail.data.NotificationStore
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.DayStats
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.LocationRepository
import com.example.tail.data.SecondaryLocation
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.SubtypeTimedMigrator
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitSearchResult
import com.example.tail.data.HabitSearcher
import com.example.tail.data.SearchableHabitInfo
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.APP_LINK_PREFIX
import com.example.tail.data.appLinkKey
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.isAppLink
import com.example.tail.data.isInternalValueKey
import com.example.tail.data.isSecondaryValueKey
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.secondaryValue2Key
import com.example.tail.data.secondaryValueSlotKey
import com.example.tail.data.conditionalCappedFeedAmount
import com.example.tail.data.conditionalLinkStorageKey
import com.example.tail.data.conditionalSyncFeedAmount
import com.example.tail.data.positiveSyncDayDeltas
import com.example.tail.data.effectiveConditionalLinkValueKey
import com.example.tail.data.effectiveMinutesEnabled
import com.example.tail.data.minutesHabitName
import com.example.tail.data.DailyStatsMap
import com.example.tail.data.GRAPH_METRIC_POINTS
import com.example.tail.data.GRAPH_METRIC_VALUE1
import com.example.tail.data.GRAPH_METRIC_VALUE2
import com.example.tail.data.GRAPH_METRIC_VALUE3
import com.example.tail.data.GRAPH_METRIC_MINUTES
import com.example.tail.data.GRAPH_METRIC_CALORIES
import com.example.tail.data.GRAPH_METRIC_PROTEIN
import com.example.tail.data.GRAPH_METRIC_CARBS
import com.example.tail.data.GRAPH_METRIC_FAT
import com.example.tail.data.GRAPH_METRIC_IMDB
import com.example.tail.data.GRAPH_METRIC_RUNTIME
import com.example.tail.data.GRAPH_METRIC_GITHUB_LINES
import com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS
import com.example.tail.data.GRAPH_METRIC_GITHUB_ADDITIONS
import com.example.tail.data.GRAPH_METRIC_GITHUB_DELETIONS
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_DROP
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_DROP
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_REPS
import com.example.tail.data.gramsToDisplayTenths
import com.example.tail.data.GraphMetricOption
import com.example.tail.data.OmdbService
import com.example.tail.data.WeightsDayValues
import com.example.tail.data.OmdbOutcome
import com.example.tail.data.ImdbRatingCache
import com.example.tail.data.ParsedTitle
import com.example.tail.data.HabitsRepository
import com.example.tail.data.BridgeClient
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SearchStateStore
import com.example.tail.data.PcEventQueueProcessor
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.TextInputRepository
import com.example.tail.data.applyDivider
import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessReadinessLogStore
import com.example.tail.widget.HabitListWidgetProvider
import com.example.tail.data.dateString
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.parseDate
import com.example.tail.data.HABIT_ORDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.example.tail.wallpaper.WallpaperMetric
import com.example.tail.wallpaper.WallpaperTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val TAG = "HabitVM"

/** Saves all meal engine settings at once (called from Settings screen). */
fun HabitViewModel.saveMealSettings(
    enabled: Boolean,
    baseUrl: String,
    apiKey: String,
    model: String,
    systemPrompt: String
) {
    viewModelScope.launch {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanKey = apiKey.trim()
        settingsRepo.saveMealSettings(enabled, cleanUrl, cleanKey, model, systemPrompt)
        _settings.value = _settings.value.copy(
            mealEnabled = enabled,
            mealBaseUrl = cleanUrl,
            mealApiKey = cleanKey,
            mealModel = model,
            mealSystemPrompt = systemPrompt
        )
    }
}

// ════════════════════════════════════════════════════════════════════════
//  AI Assistant (natural-language habit database editing)
// ════════════════════════════════════════════════════════════════════════

/**
 * Controller for the AI Assistant chat: plans DB changes via an LLM,
 * shows them for confirmation, backs up, executes and can restore.
 * Lazy — created on first use (settings screen or the ⭐ dialog button).
 */


/** Toggles the "Meal" type on/off for a specific habit. */
fun HabitViewModel.toggleMealHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.mealHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
        }
        settingsRepo.saveMealHabits(current)
        _settings.value = _settings.value.copy(mealHabits = current)
    }
}

/** Toggles the "Weights" type on/off for [habitName]. */


/** Toggles the "Weights" type on/off for [habitName]. */
fun HabitViewModel.toggleWeightsHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.weightsHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
        }
        settingsRepo.saveWeightsHabits(current)
        _settings.value = _settings.value.copy(weightsHabits = current)
    }
}

/**
 * Toggles whether [habitName] appears on the day timeline (the
 * retrospective hour-by-hour view). Excluded habits are stored in a
 * set; every habit is shown by default.
 */


/**
 * Toggles the "Camera" type on/off for a specific habit. Camera-enabled
 * habits are the only ones offered to the LLM as choices when a photo is
 * captured (see [com.example.tail.data.meal.VisionHabitExecutor]).
 */
fun HabitViewModel.toggleCameraHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.cameraHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
        }
        settingsRepo.saveCameraHabits(current)
        _settings.value = _settings.value.copy(cameraHabits = current)
    }
}

// ── Vision Memory (LLM's learned image→habit associations) ──────────

/** Reloads the vision memory entries from internal storage (newest-first). */


/** Reloads the vision memory entries from internal storage (newest-first). */
fun HabitViewModel.refreshVisionMemory() {
    viewModelScope.launch(Dispatchers.IO) {
        _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
    }
}

/** Updates an edited vision memory entry in place. */


/** Updates an edited vision memory entry in place. */
fun HabitViewModel.updateVisionMemoryEntry(entry: com.example.tail.data.meal.VisionMemoryEntry) {
    viewModelScope.launch(Dispatchers.IO) {
        visionMemoryRepo.updateEntry(entry)
        _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
    }
}

/** Deletes a vision memory entry by id (also removes its example image). */


/** Deletes a vision memory entry by id (also removes its example image). */
fun HabitViewModel.deleteVisionMemoryEntry(id: String) {
    viewModelScope.launch(Dispatchers.IO) {
        visionMemoryRepo.deleteEntry(id)
        _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
    }
}

/**
 * Sets the long-press action for a habit.
 * Pass [com.example.tail.data.LONG_PRESS_APP] to reset to default behaviour
 * (which removes the entry so the default kicks in).
 */


/** Loads meal logs for a habit and updates the StateFlows. */
fun HabitViewModel.loadMealLogs(habitName: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val logs = mealLogRepo.loadLogs(habitName)
        val today = LocalDate.now().toString()
        val todayCal = logs.filter {
            java.time.Instant.ofEpochMilli(it.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().toString() == today
        }.sumOf { it.calories }
        val pending = visionQueueRepo.pendingCount()

        _mealLogsForHabit.value = logs
        _mealTodayCalories.value = todayCal
        _mealPendingCount.value = pending
        _mealQueueItems.value = visionQueueRepo.unresolvedItems()
    }
}

/** "HH:mm:ss" formatter used when recording meal increment timestamps. */


/**
 * A tap on a meal habit card: merge-or-increment. When no meal group is
 * active (nothing logged within the group window), a placeholder card is
 * created and the habit incremented (with timestamp) — so EVERY tap
 * yields a card. An active group simply reopens the meal screen.
 */
fun HabitViewModel.recordMealTap(habitName: String, date: LocalDate = LocalDate.now()) {
    viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val at = if (date == LocalDate.now()) now
        else date.atTime(java.time.LocalTime.now())
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (mealLogRepo.findActiveGroup(habitName, at) == null) {
            val log = com.example.tail.data.meal.MealLog(
                id = UUID.randomUUID().toString(),
                habitId = habitName,
                timestamp = at,
                title = "Meal",
                isManual = true,
                countedIncrement = true,
                groupStartTimestamp = at
            )
            mealLogRepo.addLog(log)
            recordMealIncrement(habitName, at)
        }
        refreshMealFlows(habitName)
    }
}

/**
 * Adds a manual meal log entry (no photo, no LLM call). Records the
 * habit increment timestamp so manually logged meals are timestamped
 * like every other increment path. Fills in the active meal group's
 * placeholder card when one exists (1-hour grouping).
 */


/**
 * Adds a manual meal log entry (no photo, no LLM call). Records the
 * habit increment timestamp so manually logged meals are timestamped
 * like every other increment path. Fills in the active meal group's
 * placeholder card when one exists (1-hour grouping).
 */
fun HabitViewModel.addManualMealLog(
    habitName: String,
    title: String,
    calories: Int,
    skipIncrement: Boolean = false
) {
    viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val active = mealLogRepo.findActiveGroup(habitName, now)
        if (active != null && active.needsDetails()) {
            // Same meal-group still open without specifics — fill its card
            mealLogRepo.updateLog(
                active.copy(
                    title = title,
                    calories = calories,
                    isManual = true,
                    timestamp = now,
                    groupStartTimestamp = active.anchorTime()
                )
            )
        } else {
            val log = com.example.tail.data.meal.MealLog(
                id = UUID.randomUUID().toString(),
                habitId = habitName,
                timestamp = now,
                title = title,
                calories = calories,
                isManual = true,
                countedIncrement = !skipIncrement,
                groupStartTimestamp = now
            )
            mealLogRepo.addLog(log)
            if (!skipIncrement) recordMealIncrement(habitName, now)
        }
        refreshMealFlows(habitName)
    }
}

/**
 * Saves an edited meal log. When the log's creation counted as a habit
 * increment and the time was changed, the recorded habit timestamp is
 * moved to the new date/time so counts stay consistent.
 */


/**
 * Saves an edited meal log. When the log's creation counted as a habit
 * increment and the time was changed, the recorded habit timestamp is
 * moved to the new date/time so counts stay consistent.
 */
fun HabitViewModel.updateMealLog(
    habitName: String,
    updated: com.example.tail.data.meal.MealLog,
    oldTimestamp: Long
) {
    viewModelScope.launch(Dispatchers.IO) {
        mealLogRepo.updateLog(updated)
        if (updated.countedIncrement && updated.timestamp != oldTimestamp) {
            try {
                val oldZdt = java.time.Instant.ofEpochMilli(oldTimestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                val newZdt = java.time.Instant.ofEpochMilli(updated.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                deleteMealStampNear(habitName, oldZdt)
                timestampRepo.addTimestamp(
                    habitName, newZdt.toLocalDate(), newZdt.toLocalTime().format(mealTimeFmt)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync meal timestamp for '$habitName'", e)
            }
        }
        refreshMealFlows(habitName)
    }
}

/**
 * Deletes a meal log. When the log's creation incremented the habit
 * (countedIncrement), the increment and its timestamp are rolled back.
 */


/**
 * Deletes a meal log. When the log's creation incremented the habit
 * (countedIncrement), the increment and its timestamp are rolled back.
 */
fun HabitViewModel.deleteMealLog(habitName: String, logId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val log = mealLogRepo.loadLogs(habitName).find { it.id == logId }
        mealLogRepo.deleteLog(habitName, logId)
        if (log != null && log.countedIncrement) {
            rollbackMealIncrement(habitName, log.timestamp)
        }
        refreshMealFlows(habitName)
    }
}

/**
 * Voice-only meal: the spoken description is parsed by the LLM into
 * title/calories/macros/tags/ratings — no photo needed. Merges into the
 * active meal group when one exists (no extra increment).
 */


/**
 * Voice-only meal: the spoken description is parsed by the LLM into
 * title/calories/macros/tags/ratings — no photo needed. Merges into the
 * active meal group when one exists (no extra increment).
 */
fun HabitViewModel.processVoiceMeal(habitName: String, transcript: String) {
    viewModelScope.launch(Dispatchers.IO) {
        _mealVoiceStatus.value = "🎤 Parsing \"${transcript.take(60)}\"…"
        val s = _settings.value
        var fd: com.example.tail.data.meal.FoodData? = null
        if (s.mealEnabled && s.mealApiKey.isNotBlank() &&
            s.mealBaseUrl.isNotBlank() && s.mealModel.isNotBlank()
        ) {
            try {
                val config = com.example.tail.data.meal.VisionConfig(
                    baseUrl = s.mealBaseUrl,
                    apiKey = s.mealApiKey,
                    model = s.mealModel,
                    userSystemPrompt = s.mealSystemPrompt
                )
                fd = com.example.tail.data.meal.VisionProcessingService()
                    .processMealText(transcript, config)
            } catch (e: Exception) {
                Log.e(TAG, "Voice meal parse failed", e)
            }
        }

        val now = System.currentTimeMillis()
        val active = mealLogRepo.findActiveGroup(habitName, now)
        if (active != null) {
            // No newTimestamp: the log must keep the time its habit stamp
            // was recorded at — drifting it orphans the stamp and shows
            // duplicate chips on the schedule.
            mealLogRepo.updateLog(
                active.mergedWith(foodData = fd, transcript = transcript)
            )
            _mealVoiceStatus.value = "Merged into \"${active.title}\""
        } else {
            val log = com.example.tail.data.meal.MealLog(
                id = UUID.randomUUID().toString(),
                habitId = habitName,
                timestamp = now,
                title = fd?.title?.takeIf { it.isNotBlank() } ?: transcript.take(40),
                summary = fd?.summary?.takeIf { it.isNotBlank() },
                calories = fd?.estimatedCalories ?: 0,
                macronutrients = fd?.macronutrients
                    ?: com.example.tail.data.meal.Macronutrients(),
                ingredientsDetected = fd?.ingredientsDetected ?: emptyList(),
                isVeganVerified = fd?.isVeganVerified ?: false,
                voiceTranscript = transcript,
                macroRatings = fd?.macroRatings,
                countedIncrement = true,
                groupStartTimestamp = now
            )
            mealLogRepo.addLog(log)
            recordMealIncrement(habitName, now)
            _mealVoiceStatus.value =
                if (fd != null) "Added \"${log.title}\""
                else "Added card — tap it to add details"
        }
        refreshMealFlows(habitName)
    }
}

/**
 * Gallery photo: copies the picked image into internal storage and queues
 * it for the vision pipeline, attaching to the active meal group when one
 * exists (close-succession grouping → single increment).
 */


/**
 * Gallery photo: copies the picked image into internal storage and queues
 * it for the vision pipeline, attaching to the active meal group when one
 * exists (close-succession grouping → single increment).
 */
fun HabitViewModel.addMealPhotoFromUri(habitName: String, uri: android.net.Uri) {
    viewModelScope.launch(Dispatchers.IO) {
        _mealVoiceStatus.value = "🖼️ Adding photo…"
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                _mealVoiceStatus.value = "Could not read the selected photo"
                return@launch
            }
            val relPath = mealLogRepo.saveImageBytes(bytes)
            val active = mealLogRepo.findActiveGroup(habitName, System.currentTimeMillis())
            visionQueueRepo.enqueue(
                imagePath = relPath,
                habitId = habitName,
                attachToMealLogId = active?.id
            )
            com.example.tail.data.meal.VisionProcessingWorker.enqueue(context)
            _mealPendingCount.value = visionQueueRepo.pendingCount()
            _mealVoiceStatus.value =
                if (active != null) "Photo queued — merging into \"${active.title}\""
                else "Photo queued for AI…"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add gallery meal photo", e)
            _mealVoiceStatus.value = "Failed to add photo"
        }
    }
}

/** Clears the voice/queue status line shown on the meal screen. */


/** Clears the voice/queue status line shown on the meal screen. */
fun HabitViewModel.clearMealVoiceStatus() {
    _mealVoiceStatus.value = null
}

/**
 * Increments the habit for the meal's date and records the increment
 * timestamp — the shared "a meal happened" bookkeeping used by every
 * meal-creation path (manual, voice, worker).
 */


/**
 * Increments the habit for the meal's date and records the increment
 * timestamp — the shared "a meal happened" bookkeeping used by every
 * meal-creation path (manual, voice, worker).
 */
internal suspend fun HabitViewModel.recordMealIncrement(habitName: String, atMillis: Long) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    try {
        val zdt = java.time.Instant.ofEpochMilli(atMillis)
            .atZone(java.time.ZoneId.systemDefault())
        val date = zdt.toLocalDate()
        if (date == LocalDate.now()) {
            habitsRepo.incrementHabit(
                android.net.Uri.parse(uriString), context, habitName, 1
            )
        } else {
            habitsRepo.incrementHabitForDate(
                android.net.Uri.parse(uriString), context, habitName, 1, date
            )
        }
        timestampRepo.addTimestamp(habitName, date, zdt.toLocalTime().format(mealTimeFmt))
        rebuildHabitList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to increment meal habit '$habitName'", e)
    }
}

/** Rolls back a counted meal increment (habit count + timestamp). */


/** Rolls back a counted meal increment (habit count + timestamp). */
internal suspend fun HabitViewModel.rollbackMealIncrement(habitName: String, atMillis: Long) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    try {
        val zdt = java.time.Instant.ofEpochMilli(atMillis)
            .atZone(java.time.ZoneId.systemDefault())
        habitsRepo.incrementHabitForDate(
            android.net.Uri.parse(uriString), context, habitName, -1, zdt.toLocalDate()
        )
        deleteMealStampNear(habitName, zdt)
        rebuildHabitList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to roll back meal increment for '$habitName'", e)
    }
}

/**
 * Deletes the meal-increment stamp for [zdt] — exact match first, then the
 * nearest stamp within ±5 minutes. Meal log timestamps can drift a few
 * seconds from their stamp (voice merges, editor saves that round to the
 * minute), so a strict exact match silently orphans stamps and leaves
 * duplicate chips on the schedule.
 */


/**
 * Deletes the meal-increment stamp for [zdt] — exact match first, then the
 * nearest stamp within ±5 minutes. Meal log timestamps can drift a few
 * seconds from their stamp (voice merges, editor saves that round to the
 * minute), so a strict exact match silently orphans stamps and leaves
 * duplicate chips on the schedule.
 */
internal suspend fun HabitViewModel.deleteMealStampNear(
    habitName: String,
    zdt: java.time.ZonedDateTime
) {
    val date = zdt.toLocalDate()
    val day = timestampRepo.getTimestampsForDay(habitName, date)
    val idx = day.indexOf(zdt.toLocalTime().format(mealTimeFmt))
    if (idx >= 0) {
        timestampRepo.deleteTimestamp(habitName, date, idx)
        return
    }
    val targetSec = zdt.toLocalTime().toSecondOfDay()
    val nearest = day.withIndex()
        .mapNotNull { (i, t) ->
            val s = runCatching {
                java.time.LocalTime.parse(t).toSecondOfDay()
            }.getOrNull() ?: return@mapNotNull null
            val dist = kotlin.math.abs(s - targetSec)
            if (dist <= 300) i to dist else null
        }
        .minByOrNull { (_, dist) -> dist }
    if (nearest != null) {
        timestampRepo.deleteTimestamp(habitName, date, nearest.first)
    }
}

/** Refreshes the unresolved vision-queue items shown in the meal details screen. */
internal fun HabitViewModel.refreshMealQueueItems() {
    viewModelScope.launch(Dispatchers.IO) {
        _mealQueueItems.value = visionQueueRepo.unresolvedItems()
        _mealPendingCount.value = visionQueueRepo.pendingCount()
    }
}

/**
 * Forces a stuck/failed/needs-review queue item back to PENDING with a
 * fresh retry budget and immediately triggers a processing pass.
 */
fun HabitViewModel.forceReprocessQueueItem(itemId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val ok = visionQueueRepo.forceRequeue(itemId)
        if (ok) {
            com.example.tail.data.meal.VisionProcessingWorker.enqueue(context)
        }
        _mealQueueItems.value = visionQueueRepo.unresolvedItems()
        _mealPendingCount.value = visionQueueRepo.pendingCount()
        _mealVoiceStatus.value = if (ok) {
            "🔄 Re-analyzing photo…"
        } else {
            "Queue item not found"
        }
    }
}

/** Reloads the meal StateFlows (logs, today's calories, queue count). */
internal suspend fun HabitViewModel.refreshMealFlows(habitName: String) {
    val logs = mealLogRepo.loadLogs(habitName)
    _mealLogsForHabit.value = logs
    val today = LocalDate.now().toString()
    _mealTodayCalories.value = logs.filter {
        java.time.Instant.ofEpochMilli(it.timestamp)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString() == today
    }.sumOf { it.calories }
    _mealPendingCount.value = visionQueueRepo.pendingCount()
    _mealQueueItems.value = visionQueueRepo.unresolvedItems()
}

/** Triggers the vision processing worker to drain the queue (called after capture). */
fun HabitViewModel.triggerVisionProcessing() {
    com.example.tail.data.meal.VisionProcessingWorker.enqueue(context)
    viewModelScope.launch(Dispatchers.IO) {
        _mealPendingCount.value = visionQueueRepo.pendingCount()
    }
}

/**
 * Tests the configured vision endpoint by sending a bundled test image
 * (banana.jpeg from assets) through the full pipeline. Updates
 * [_mealTestState] with the result for UI display.
 */


/**
 * Tests the configured vision endpoint by sending a bundled test image
 * (banana.jpeg from assets) through the full pipeline. Updates
 * [_mealTestState] with the result for UI display.
 */
fun HabitViewModel.testVisionEndpoint() {
    val s = _settings.value
    if (s.mealBaseUrl.isBlank() || s.mealApiKey.isBlank() || s.mealModel.isBlank()) {
        _mealTestState.value = HabitViewModel.MealTestState(
            isSuccess = false,
            message = "Please fill in Base URL, API Key, and Model Name first."
        )
        return
    }

    _mealTestState.value = HabitViewModel.MealTestState(
        isTesting = true,
        message = "Testing… (may retry on rate limits, up to ~15s)"
    )

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Copy banana.jpeg from assets to a temp file
            val tempFile = java.io.File(context.cacheDir, "test_banana.jpeg")
            context.assets.open("banana.jpeg").use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            val config = com.example.tail.data.meal.VisionConfig(
                baseUrl = s.mealBaseUrl,
                apiKey = s.mealApiKey,
                model = s.mealModel,
                userSystemPrompt = s.mealSystemPrompt
            )

            val service = com.example.tail.data.meal.VisionProcessingService()
            val result = service.processImage(tempFile, config)

            tempFile.delete()

            if (result == null) {
                _mealTestState.value = HabitViewModel.MealTestState(
                    isSuccess = false,
                    message = "❌ Request failed — check your URL, key, and model. " +
                              "See logcat (tag: VisionProcessing) for details."
                )
            } else if (result.classification == com.example.tail.data.meal.VisionClassification.FOOD_MEAL &&
                       result.foodData != null
            ) {
                val fd = result.foodData
                _mealTestState.value = HabitViewModel.MealTestState(
                    isSuccess = true,
                    message = "✅ Success! Detected: ${fd.title}\n" +
                              "Calories: ${fd.estimatedCalories} kcal\n" +
                              "Protein: ${fd.macronutrients.proteinGrams}g, " +
                              "Carbs: ${fd.macronutrients.carbsGrams}g, " +
                              "Fat: ${fd.macronutrients.fatGrams}g\n" +
                              "Confidence: ${(result.confidenceScore * 100).toInt()}%"
                )
            } else {
                // Distinguish API errors (rate limit, server error) from model classification issues
                val notes = result.processingNotes
                val isError = notes.contains("Rate limited", ignoreCase = true) ||
                              notes.contains("Server error", ignoreCase = true) ||
                              notes.contains("API error", ignoreCase = true)
                _mealTestState.value = HabitViewModel.MealTestState(
                    isSuccess = !isError,
                    message = if (isError) {
                        "❌ ${notes.take(300)}"
                    } else {
                        "⚠️ Got a response but classification was " +
                        "${result.classification}.\n" +
                        "Notes: $notes\n" +
                        "The endpoint works, but the model may not handle food images well."
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision test failed", e)
            _mealTestState.value = HabitViewModel.MealTestState(
                isSuccess = false,
                message = "❌ Error: ${e.message?.take(200)}"
            )
        }
    }
}
