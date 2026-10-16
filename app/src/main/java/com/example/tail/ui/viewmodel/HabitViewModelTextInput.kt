package com.example.tail.ui.viewmodel


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
import com.example.tail.data.APP_LINK_PREFIX
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconGeneratorService
import com.example.tail.data.AiIconRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.BridgeClient
import com.example.tail.data.movie.BridgeMovie
import com.example.tail.data.chess.ChessComRepository
import com.example.tail.data.chess.ChessComType
import com.example.tail.data.chess.DailyStatsMap
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.DayStats
import com.example.tail.data.GRAPH_METRIC_CALORIES
import com.example.tail.data.GRAPH_METRIC_CARBS
import com.example.tail.data.GRAPH_METRIC_FAT
import com.example.tail.data.GRAPH_METRIC_GITHUB_ADDITIONS
import com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS
import com.example.tail.data.GRAPH_METRIC_GITHUB_DELETIONS
import com.example.tail.data.GRAPH_METRIC_GITHUB_LINES
import com.example.tail.data.GRAPH_METRIC_IMDB
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_DROP
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_DROP
import com.example.tail.data.GRAPH_METRIC_MINUTES
import com.example.tail.data.GRAPH_METRIC_POINTS
import com.example.tail.data.GRAPH_METRIC_PROTEIN
import com.example.tail.data.GRAPH_METRIC_RUNTIME
import com.example.tail.data.GRAPH_METRIC_VALUE1
import com.example.tail.data.GRAPH_METRIC_VALUE2
import com.example.tail.data.GRAPH_METRIC_VALUE3
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT
import com.example.tail.data.health.GarminRepository
import com.example.tail.data.health.GarminType
import com.example.tail.data.GitHubApiException
import com.example.tail.data.GitHubMetric
import com.example.tail.data.GitHubRateLimitException
import com.example.tail.data.GitHubRepository
import com.example.tail.data.GraphMetricOption
import com.example.tail.data.HABIT_ORDER
import com.example.tail.data.Habit
import com.example.tail.data.HabitNotification
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitSearchResult
import com.example.tail.data.HabitSearcher
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.movie.ImdbRatingCache
import com.example.tail.data.health.ImportResult
import com.example.tail.data.location.LocationRepository
import com.example.tail.data.movie.MovieBridgeService
import com.example.tail.data.movie.MovieCacheStore
import com.example.tail.data.NotificationStore
import com.example.tail.data.movie.OmdbOutcome
import com.example.tail.data.movie.OmdbService
import com.example.tail.data.movie.ParsedTitle
import com.example.tail.data.PcEventQueueProcessor
import com.example.tail.data.SearchStateStore
import com.example.tail.data.SearchableHabitInfo
import com.example.tail.data.location.SecondaryLocation
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.SubtypeTimedMigrator
import com.example.tail.data.TextInputRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.WallpaperMetric
import com.example.tail.data.WallpaperTarget
import com.example.tail.data.WeightsDayValues
import com.example.tail.data.appLinkKey
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.applyDivider
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.backup.BackupResult
import com.example.tail.data.backup.HabitRestorePreview
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.conditionalCappedFeedAmount
import com.example.tail.data.conditionalLinkStorageKey
import com.example.tail.data.conditionalSyncFeedAmount
import com.example.tail.data.dateString
import com.example.tail.data.effectiveConditionalLinkValueKey
import com.example.tail.data.effectiveMinutesEnabled
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.gramsToDisplayTenths
import com.example.tail.data.isAppLink
import com.example.tail.data.isInternalValueKey
import com.example.tail.data.isSecondaryValueKey
import com.example.tail.data.minutesHabitName
import com.example.tail.data.minutesKey
import com.example.tail.data.parseDate
import com.example.tail.data.positiveSyncDayDeltas
import com.example.tail.data.secondaryValue2Key
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.secondaryValueSlotKey
import com.example.tail.ui.common.HabitsDataChangedBus
import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessReadinessLogStore
import com.example.tail.widget.HabitListWidgetProvider
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

private const val TAG = "HabitVM"

/**
 * Toggles the "text input" feature on/off for [habitName].
 * When turned off, also removes the habit from the options and sharable sets
 * (both sub-features require text input to be on).
 */
fun HabitViewModel.toggleTextInput(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.textInputHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
            // Also remove from options set — options requires text input to be on
            val opts = _settings.value.textInputOptionsHabits.toMutableSet()
            opts.remove(habitName)
            settingsRepo.saveTextInputOptionsHabits(opts)
            _settings.value = _settings.value.copy(textInputOptionsHabits = opts)
            // And drop the characteristics sub-feature state with it
            removeTextInputCharacteristicsState(habitName)
            // Also remove from sharable set — sharable requires text input to be on
            val sharable = _settings.value.sharableTextHabits.toMutableSet()
            sharable.remove(habitName)
            settingsRepo.saveSharableTextHabits(sharable)
            _settings.value = _settings.value.copy(sharableTextHabits = sharable)
            // Also remove from the Inuit sharing set — it requires text input too
            val inuit = _settings.value.inuitTextHabits.toMutableSet()
            inuit.remove(habitName)
            settingsRepo.saveInuitTextHabits(inuit)
            _settings.value = _settings.value.copy(inuitTextHabits = inuit)
        } else {
            current.add(habitName)
        }
        settingsRepo.saveTextInputHabits(current)
        _settings.value = _settings.value.copy(textInputHabits = current)
    }
}

/**
 * Toggles the "show options" sub-feature on/off for [habitName].
 * Only has effect when the habit already has text input enabled.
 */


/**
 * Toggles the "show options" sub-feature on/off for [habitName].
 * Only has effect when the habit already has text input enabled.
 */
fun HabitViewModel.toggleTextInputOptions(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.textInputOptionsHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveTextInputOptionsHabits(current)
        _settings.value = _settings.value.copy(textInputOptionsHabits = current)
        // Turning options OFF also disables the characteristics sub-feature —
        // it only exists as an extension of the options picker.
        if (habitName !in current) removeTextInputCharacteristicsState(habitName)
    }
}

/** Strips every characteristics-related settings entry for [habitName]. */
private fun HabitViewModel.removeTextInputCharacteristicsState(habitName: String) {
    viewModelScope.launch {
        val s = _settings.value
        val habits = s.textInputCharacteristicsHabits
        if (habitName in habits) {
            val updated = habits - habitName
            settingsRepo.saveTextInputCharacteristicsHabits(updated)
            _settings.value = _settings.value.copy(textInputCharacteristicsHabits = updated)
        }
        if (s.textInputCharacteristics.containsKey(habitName)) {
            val updated = s.textInputCharacteristics - habitName
            settingsRepo.saveTextInputCharacteristics(updated)
            _settings.value = _settings.value.copy(textInputCharacteristics = updated)
        }
        if (s.textInputOptionCharacteristics.containsKey(habitName)) {
            val updated = s.textInputOptionCharacteristics - habitName
            settingsRepo.saveTextInputOptionCharacteristics(updated)
            _settings.value = _settings.value.copy(textInputOptionCharacteristics = updated)
        }
    }
}

/**
 * Toggles the "characteristics" sub-feature on/off for [habitName].
 * Only has effect when the habit already has the "options" sub-feature on.
 */
fun HabitViewModel.toggleTextInputCharacteristics(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.textInputCharacteristicsHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveTextInputCharacteristicsHabits(current)
        _settings.value = _settings.value.copy(textInputCharacteristicsHabits = current)
    }
}

/**
 * Replaces the characteristic name definitions for [habitName] (used by the
 * edit-mode characteristics editor). Options whose assignments reference a
 * removed characteristic are cleaned of it in the same write.
 */
fun HabitViewModel.setTextInputCharacteristics(habitName: String, names: Set<String>) {
    viewModelScope.launch {
        val s = _settings.value
        val updatedDefs = s.textInputCharacteristics.toMutableMap()
        if (names.isEmpty()) updatedDefs.remove(habitName) else updatedDefs[habitName] = names
        settingsRepo.saveTextInputCharacteristics(updatedDefs)
        _settings.value = _settings.value.copy(textInputCharacteristics = updatedDefs)
        // Drop assignments that reference a characteristic no longer defined.
        val inner = s.textInputOptionCharacteristics[habitName] ?: return@launch
        val cleaned = inner.mapValues { (_, set) -> set intersect names }
            .filterValues { it.isNotEmpty() }
        if (cleaned != inner) {
            val updated = s.textInputOptionCharacteristics.toMutableMap()
            if (cleaned.isEmpty()) updated.remove(habitName) else updated[habitName] = cleaned
            settingsRepo.saveTextInputOptionCharacteristics(updated)
            _settings.value = _settings.value.copy(textInputOptionCharacteristics = updated)
        }
    }
}

/**
 * Applies a batch of per-option characteristic assignments for [habitName]
 * (habit → option text → set). An empty set removes the option's entry.
 * Used both by the characteristics editor and by the increment popup after
 * the user tags freshly added options.
 */
fun HabitViewModel.applyTextOptionCharacteristics(
    habitName: String,
    assignments: Map<String, Set<String>>
) {
    if (assignments.isEmpty()) return
    viewModelScope.launch {
        val all = _settings.value.textInputOptionCharacteristics.toMutableMap()
        val inner = all[habitName]?.toMutableMap() ?: mutableMapOf()
        for ((option, set) in assignments) {
            if (set.isEmpty()) inner.remove(option) else inner[option] = set
        }
        if (inner.isEmpty()) all.remove(habitName) else all[habitName] = inner
        settingsRepo.saveTextInputOptionCharacteristics(all)
        _settings.value = _settings.value.copy(textInputOptionCharacteristics = all)
    }
}

/**
 * Toggles the "sharable" sub-feature on/off for [habitName].
 * When on, the habit appears in ShareTextActivity's picker so text shared
 * from anywhere on the phone can be saved into it (timestamped + counted).
 * Only has effect when the habit already has text input enabled.
 */


/**
 * Toggles the "sharable" sub-feature on/off for [habitName].
 * When on, the habit appears in ShareTextActivity's picker so text shared
 * from anywhere on the phone can be saved into it (timestamped + counted).
 * Only has effect when the habit already has text input enabled.
 */
fun HabitViewModel.toggleSharableText(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.sharableTextHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveSharableTextHabits(current)
        _settings.value = _settings.value.copy(sharableTextHabits = current)
    }
}

/**
 * Master switch for the Inuit integration. When off, the ContentProvider
 * text-habit endpoints expose nothing even if habits remain selected.
 */


/**
 * Master switch for the Inuit integration. When off, the ContentProvider
 * text-habit endpoints expose nothing even if habits remain selected.
 */
fun HabitViewModel.setInuitIntegrationEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveInuitIntegrationEnabled(enabled)
        _settings.value = _settings.value.copy(inuitIntegrationEnabled = enabled)
    }
}

/**
 * Toggles whether [habitName]'s recent text entries are shared with the
 * Inuit trivia trainer (bounded: last 14 days, ≤3 entries, 300 chars —
 * see InuitTextSharing). Only meaningful for text-input habits.
 */


/**
 * Toggles whether [habitName]'s recent text entries are shared with the
 * Inuit trivia trainer (bounded: last 14 days, ≤3 entries, 300 chars —
 * see InuitTextSharing). Only meaningful for text-input habits.
 */
fun HabitViewModel.toggleInuitTextHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.inuitTextHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveInuitTextHabits(current)
        _settings.value = _settings.value.copy(inuitTextHabits = current)
    }
}

/**
 * Master switch for the companion read API. When off, the ContentProvider
 * v2 endpoints (full history / meal rows / change feed) expose nothing
 * even if habits remain opted in.
 */
fun HabitViewModel.setCompanionApiEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveCompanionApiEnabled(enabled)
        _settings.value = _settings.value.copy(companionApiEnabled = enabled)
    }
}

/**
 * Toggles whether [habitName]'s FULL history is shared with companion apps
 * via the v2 provider endpoints. A habit that is not opted in is invisible
 * there — full-backlog read is sensitive, so consent is per habit.
 */
fun HabitViewModel.toggleCompanionReadHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.companionReadHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveCompanionReadHabits(current)
        _settings.value = _settings.value.copy(companionReadHabits = current)
    }
}

/**
 * Associates [uri] as the text-log file for [habitName].
 * Takes a persistent read+write permission on the URI.
 */


/**
 * Associates [uri] as the text-log file for [habitName].
 * Takes a persistent read+write permission on the URI.
 */
fun HabitViewModel.setTextInputFileUri(habitName: String, uri: Uri) {
    viewModelScope.launch {
        val uriString = uri.toString()
        val current = _settings.value.textInputFileUris.toMutableMap()
        current[habitName] = uriString
        settingsRepo.saveTextInputFileUris(current)
        _settings.value = _settings.value.copy(textInputFileUris = current)
    }
}

/**
 * Creates a new empty text-log file named after [habitName] inside the SAF
 * directory [treeUri], then associates it as the habit's text-log file.
 *
 * The caller must already hold (and persist) read+write permission on the
 * tree URI; the created document lives under that tree so the grant covers it.
 * The file is initialised with an empty JSON object ("{}"). If the directory
 * already contains a file with the same name the provider auto-renames the
 * new file (e.g. "habit (1).json").
 */


/**
 * Creates a new empty text-log file named after [habitName] inside the SAF
 * directory [treeUri], then associates it as the habit's text-log file.
 *
 * The caller must already hold (and persist) read+write permission on the
 * tree URI; the created document lives under that tree so the grant covers it.
 * The file is initialised with an empty JSON object ("{}"). If the directory
 * already contains a file with the same name the provider auto-renames the
 * new file (e.g. "habit (1).json").
 */
fun HabitViewModel.createTextInputFileInDir(habitName: String, treeUri: Uri) {
    viewModelScope.launch {
        val fileUri = withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                val dirUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                val displayName = sanitizeFileDisplayName(habitName) + ".json"
                val created = DocumentsContract.createDocument(
                    cr, dirUri, "application/json", displayName
                ) ?: return@withContext null
                // Initialise with an empty JSON object so the file is a valid log
                cr.openOutputStream(created, "wt")?.use { stream ->
                    stream.bufferedWriter().use { it.write("{}") }
                }
                created
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create text log file for $habitName: ${e.message}")
                null
            }
        }
        if (fileUri != null) setTextInputFileUri(habitName, fileUri)
    }
}

/** Replaces characters that are problematic in file names with underscores. */


/** Replaces characters that are problematic in file names with underscores. */
internal fun HabitViewModel.sanitizeFileDisplayName(name: String): String {
    val sanitized = name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
    return sanitized.ifEmpty { "habit_log" }
}

/**
 * Saves a text entry for [habitName] to its associated log file,
 * then also increments the habit count by 1 (so the habit is marked done for today).
 *
 * @param habitName The name of the habit
 * @param text The text entry to save
 * @param date The date to use for the timestamp. If null, uses current date.
 * @param time The time-of-day for the timestamp. If null, uses current time (when [date]
 *             is also null) or noon (when [date] is provided but [time] is null).
 */


/**
 * Saves a text entry for [habitName] to its associated log file,
 * then also increments the habit count by 1 (so the habit is marked done for today).
 *
 * @param habitName The name of the habit
 * @param text The text entry to save
 * @param date The date to use for the timestamp. If null, uses current date.
 * @param time The time-of-day for the timestamp. If null, uses current time (when [date]
 *             is also null) or noon (when [date] is provided but [time] is null).
 */
fun HabitViewModel.saveTextEntry(
    habitName: String,
    text: String,
    date: LocalDate? = null,
    time: java.time.LocalTime? = null
) {
    // Internal-first: no external file needed — null URI means internal-only.
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            // Capture ONE time for both the text-log key and the increment
            // stamp: a stamp recorded separately ("now" after the slow file
            // write) drifts seconds past its text key and renders as a
            // phantom text-less card in the timestamp editor.
            val entryDate = date ?: java.time.LocalDate.now()
            val entryClock = time ?: java.time.LocalTime.now()
            val stampTime = entryClock.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            // Save the text entry for the current date
            textInputRepo.appendTextEntry(uri, context, text, entryDate, entryClock, habitName = habitName)

            // If this is a roll forward habit, also roll forward the text
            if (habitName in _settings.value.rollForwardHabits) {
                val timestamp = java.time.LocalDateTime.of(entryDate, entryClock)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                // Find the next manual date (same logic as incrementHabit)
                val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                    com.example.tail.data.parseDate(dateStr)
                }?.sorted()?.firstOrNull { it > entryDate }

                val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                // Roll forward the text to all dates from entryDate+1 to endDate
                if (entryDate < endDate) {
                    textInputRepo.rollForwardTextEntry(
                        uri,
                        context,
                        timestamp,
                        entryDate.plusDays(1),
                        endDate,
                        habitName = habitName,
                        // Corruption guard: text may never land on a day with
                        // 0 points (the phantom "Pulled muscle" rows bug).
                        includeDate = { hasPointsOnDate(habitName, it) }
                    )
                }
            }

            // Also increment the habit count so it registers as done for
            // today. Movie-bridge habits: suppress the "now" stamp — the
            // text entry's watch-start time is THE timestamp (sync below).
            // A trailing "(N min)" annotation carries minutes, which are
            // forwarded to conditional-linked habits that have the per-link
            // "feed minutes" flag enabled.
            val entryMinutes = Regex("""\((\d+)\s*min\)\s*$""")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            incrementHabit(
                habitName, 1,
                recordTimestamp = !isMovieBridgeHabit(habitName),
                // The increment must land on the ENTRY's day, not whatever
                // day the user happens to be viewing (the movie-ask answer
                // bug: answered while browsing a past day → count on the
                // wrong day). Null date = "current date" per the doc above.
                date = entryDate,
                stampTime = stampTime,
                minutes = entryMinutes
            )

            // Movie-bridge habits: reconcile the timestamp store to the
            // text log so the entry's watch time is the single timestamp,
            // and recompute the minutes slot from "(N min)" annotations.
            if (isMovieBridgeHabit(habitName)) {
                syncMovieTimestamps(habitName)
                syncMovieMinutesSlot(habitName)
            }

            // Trigger async IMDb rating fetch for movie-bridge habits
            triggerImdbFetchForEntry(habitName, text)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save text entry: ${e.message}"
        }
    }
}

/**
 * Saves multiple text entries for [habitName] to its associated log file in a single
 * atomic write. Each entry gets a unique timestamp (offset by 1 second). Then
 * increments the habit count by 1 — selecting multiple options is a single action
 * and should not inflate the habit count.
 *
 * @param habitName The name of the habit
 * @param texts The list of text entries to save
 * @param date The date to use for timestamps. If null, uses current date.
 * @param time The base time-of-day. If null, uses current time (when [date] is also null)
 *             or noon (when [date] is provided but [time] is null).
 */


/**
 * Saves multiple text entries for [habitName] to its associated log file in a single
 * atomic write. Each entry gets a unique timestamp (offset by 1 second). Then
 * increments the habit count by 1 — selecting multiple options is a single action
 * and should not inflate the habit count.
 *
 * @param habitName The name of the habit
 * @param texts The list of text entries to save
 * @param date The date to use for timestamps. If null, uses current date.
 * @param time The base time-of-day. If null, uses current time (when [date] is also null)
 *             or noon (when [date] is provided but [time] is null).
 */
fun HabitViewModel.saveTextEntries(
    habitName: String,
    texts: List<String>,
    date: LocalDate? = null,
    time: java.time.LocalTime? = null
) {
    if (texts.isEmpty()) return
    // Single entry — delegate to the simpler path
    if (texts.size == 1) {
        saveTextEntry(habitName, texts.first(), date, time)
        return
    }
    // Internal-first: no external file needed — null URI means internal-only.
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            textInputRepo.appendMultipleTextEntries(
                uri, context, texts, date, time, habitName = habitName
            )

            // If this is a roll forward habit, also roll forward the text
            if (habitName in _settings.value.rollForwardHabits) {
                val entryDate = date ?: java.time.LocalDate.now()
                val effectiveTime = time ?: java.time.LocalTime.NOON
                val timestamp = java.time.LocalDateTime.of(entryDate, effectiveTime)
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                    com.example.tail.data.parseDate(dateStr)
                }?.sorted()?.firstOrNull { it > entryDate }

                val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                if (entryDate < endDate) {
                    textInputRepo.rollForwardTextEntry(
                        uri,
                        context,
                        timestamp,
                        entryDate.plusDays(1),
                        endDate,
                        habitName = habitName,
                        // Corruption guard: text may never land on a day with
                        // 0 points (the phantom "Pulled muscle" rows bug).
                        includeDate = { hasPointsOnDate(habitName, it) }
                    )
                }
            }

            // Increment the habit count by 1 — selecting multiple options
            // is a single action and must not count as multiple increments.
            // Movie-bridge habits: suppress the "now" stamp — the text
            // entries' watch-start times are THE timestamps (sync below).
            incrementHabit(
                habitName, 1,
                recordTimestamp = !isMovieBridgeHabit(habitName),
                // Same as the single-entry path: the increment lands on the
                // entries' day (null date = current date), never the
                // currently-viewed day.
                date = date ?: java.time.LocalDate.now()
            )

            // Movie-bridge habits: reconcile the timestamp store to the
            // text log so each entry's watch time is the single timestamp,
            // and recompute the minutes slot from "(N min)" annotations.
            if (isMovieBridgeHabit(habitName)) {
                syncMovieTimestamps(habitName)
                syncMovieMinutesSlot(habitName)
            }

            // Trigger async IMDb rating fetch for movie-bridge habits
            texts.forEach { triggerImdbFetchForEntry(habitName, it) }
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save text entries: ${e.message}"
        }
    }
}

/**
 * Loads the list of unique past text entries for [habitName] from its log file.
 * Returns an empty list if no file is configured or the file is empty.
 * Calls [onResult] on the main thread with the sorted unique options.
 */


/**
 * Loads the list of unique past text entries for [habitName] from its log file.
 * Returns an empty list if no file is configured or the file is empty.
 * Calls [onResult] on the main thread with the sorted unique options.
 */
fun HabitViewModel.loadTextOptions(habitName: String, onResult: (List<String>) -> Unit) {
    // Internal-first: options come from the internal store even with no external file.
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            val hidden = _settings.value.textInputHiddenGroupings[habitName] ?: emptySet()
            val options = textInputRepo.loadUniqueOptions(uri, context, habitName = habitName)
            // Multi-option groupings the user removed from this picker are
            // filtered here — history is untouched, only the popup list is.
            onResult(options.filter { it !in hidden })
        } catch (e: Exception) {
            onResult(emptyList())
        }
    }
}

/**
 * Loads the decomposed option inventory for [habitName] for the options
 * editor popup: singles (each option has its own identity, even when it
 * only appears as a part of a multi-option grouping) and groupings (the
 * exact multi-part combos). Calls [onResult] on the main thread; empty
 * lists when no log file is configured.
 */
fun HabitViewModel.loadTextOptionInventory(
    habitName: String,
    onResult: (TextInputRepository.TextOptionInventory) -> Unit
) {
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            onResult(textInputRepo.loadOptionInventory(uri, context, habitName = habitName))
        } catch (e: Exception) {
            onResult(TextInputRepository.TextOptionInventory(emptyList(), emptyList()))
        }
    }
}

/**
 * Retroactively renames an option value for [habitName]: every log entry
 * whose text equals [oldText] is rewritten to [newText] (one atomic file
 * write; multi-option groupings are rewritten line-wise so parts keep
 * their identity inside combos), AND any description stored for the old
 * option text is remapped to the new text so descriptions survive renames.
 * [onDone] fires when both writes have completed.
 */
fun HabitViewModel.renameTextOption(
    habitName: String,
    oldText: String,
    newText: String,
    onDone: () -> Unit = {}
) {
    if (newText == oldText) {
        onDone()
        return
    }
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            textInputRepo.renameOptionValues(
                uri, context, oldText, newText, habitName = habitName
            )
            // Rewrite hidden-grouping keys the same way: a hidden combo
            // ("Iron\nC\nB12") must stay hidden after Iron → Iron 25, so the
            // stored key is rebuilt line-wise exactly like the log entries.
            val hiddenAll = _settings.value.textInputHiddenGroupings
            val hiddenSet = hiddenAll[habitName]
            if (hiddenSet != null && hiddenSet.isNotEmpty()) {
                val remapped = hiddenSet.map { grouping ->
                    if (grouping.contains('\n')) {
                        grouping.split('\n').joinToString("\n") {
                            if (it.trim() == oldText) newText else it
                        }
                    } else if (grouping.trim() == oldText) newText else grouping
                }.toSet()
                if (remapped != hiddenSet) {
                    val updated = hiddenAll.toMutableMap()
                    if (remapped.isEmpty()) updated.remove(habitName) else updated[habitName] = remapped
                    settingsRepo.saveTextInputHiddenGroupings(updated)
                    _settings.value = _settings.value.copy(textInputHiddenGroupings = updated)
                }
            }
            // Carry the description (if any) over to the new option text
            val descriptions = _settings.value.textInputOptionDescriptions.toMutableMap()
            val inner = descriptions[habitName]
            if (inner != null && inner.containsKey(oldText)) {
                val newInner = inner.toMutableMap()
                val desc = newInner.remove(oldText)
                if (desc != null) newInner[newText] = desc
                descriptions[habitName] = newInner
                settingsRepo.saveTextInputOptionDescriptions(descriptions)
                _settings.value = _settings.value.copy(textInputOptionDescriptions = descriptions)
            }
            // Carry the option's characteristic assignments over the same way
            val charsAll = _settings.value.textInputOptionCharacteristics.toMutableMap()
            val charsInner = charsAll[habitName]
            if (charsInner != null && charsInner.containsKey(oldText)) {
                val newCharsInner = charsInner.toMutableMap()
                val set = newCharsInner.remove(oldText)
                if (set != null && set.isNotEmpty()) newCharsInner[newText] = set
                if (newCharsInner.isEmpty()) charsAll.remove(habitName) else charsAll[habitName] = newCharsInner
                settingsRepo.saveTextInputOptionCharacteristics(charsAll)
                _settings.value = _settings.value.copy(textInputOptionCharacteristics = charsAll)
            }
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to rename option: ${e.message}"
        }
        onDone()
    }
}

/**
 * Hides a multi-option grouping for [habitName]: it disappears from the
 * increment popup's options list but its history stays untouched.
 */
fun HabitViewModel.hideTextOptionGrouping(habitName: String, grouping: String) {
    viewModelScope.launch {
        val hidden = _settings.value.textInputHiddenGroupings.toMutableMap()
        val set = hidden[habitName]?.toMutableSet() ?: mutableSetOf()
        set.add(grouping)
        hidden[habitName] = set
        settingsRepo.saveTextInputHiddenGroupings(hidden)
        _settings.value = _settings.value.copy(textInputHiddenGroupings = hidden)
    }
}

/**
 * Un-hides a previously hidden multi-option grouping so it shows again in
 * the increment popup.
 */
fun HabitViewModel.unhideTextOptionGrouping(habitName: String, grouping: String) {
    viewModelScope.launch {
        val hidden = _settings.value.textInputHiddenGroupings.toMutableMap()
        val set = hidden[habitName]?.toMutableSet() ?: return@launch
        set.remove(grouping)
        if (set.isEmpty()) hidden.remove(habitName) else hidden[habitName] = set
        settingsRepo.saveTextInputHiddenGroupings(hidden)
        _settings.value = _settings.value.copy(textInputHiddenGroupings = hidden)
    }
}

/**
 * Sets (or clears, when [description] is blank) the hidden description for
 * option [optionText] of [habitName]. Descriptions are shown only in the
 * options editor popup — never in the increment picker.
 */
fun HabitViewModel.setTextOptionDescription(
    habitName: String,
    optionText: String,
    description: String
) {
    viewModelScope.launch {
        val descriptions = _settings.value.textInputOptionDescriptions.toMutableMap()
        val inner = descriptions[habitName]?.toMutableMap() ?: mutableMapOf()
        if (description.isBlank()) inner.remove(optionText) else inner[optionText] = description
        if (inner.isEmpty()) descriptions.remove(habitName) else descriptions[habitName] = inner
        settingsRepo.saveTextInputOptionDescriptions(descriptions)
        _settings.value = _settings.value.copy(textInputOptionDescriptions = descriptions)
    }
}

/**
 * AI-camera description assist: analyses [imageFile] (a fresh camera capture)
 * together with the user's [instruction] (e.g. "parse the nutritional data
 * for these pills") and the descriptions already written for the habit's
 * OTHER options, producing a description in the established style. The photo
 * is deleted afterwards — it is only a prompt payload, never stored history.
 * [onResult] fires on the main thread with the description, or null on failure.
 */
fun HabitViewModel.generateOptionDescriptionFromCamera(
    habitName: String,
    optionText: String,
    instruction: String,
    imageFile: java.io.File,
    onResult: (String?) -> Unit
) {
    viewModelScope.launch {
        val s = _settings.value
        val config = com.example.tail.data.meal.VisionConfig(
            baseUrl = s.mealBaseUrl,
            apiKey = s.mealApiKey,
            model = s.mealModel,
            userSystemPrompt = ""
        )
        // Style examples: every described option EXCEPT the one being written
        // (the current one would bias the LLM toward its old/empty text).
        val examples = (s.textInputOptionDescriptions[habitName] ?: emptyMap())
            .filterKeys { it != optionText }
        val result = try {
            com.example.tail.data.meal.VisionProcessingService()
                .describeOptionFromImage(imageFile, config, habitName, optionText, instruction, examples)
        } catch (e: Exception) {
            _errorMessage.value = "AI description failed: ${e.message}"
            null
        } finally {
            imageFile.delete()
        }
        onResult(result)
    }
}
// ── Graph mode ────────────────────────────────────────────────────────────

/** Whether graph mode is active. */
