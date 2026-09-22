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
import com.example.tail.data.WallpaperMetric
import com.example.tail.data.WallpaperTarget
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
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        _errorMessage.value = "No text log file set for '$habitName'. Select one in edit mode."
        return
    }
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
            textInputRepo.appendTextEntry(Uri.parse(uriString), context, text, entryDate, entryClock, habitName = habitName)

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
                        Uri.parse(uriString),
                        context,
                        timestamp,
                        entryDate.plusDays(1),
                        endDate,
                        habitName = habitName
                    )
                }
            }

            // Also increment the habit count so it registers as done for
            // today. Movie-bridge habits: suppress the "now" stamp — the
            // text entry's watch-start time is THE timestamp (sync below).
            incrementHabit(
                habitName, 1,
                recordTimestamp = !isMovieBridgeHabit(habitName),
                // The increment must land on the ENTRY's day, not whatever
                // day the user happens to be viewing (the movie-ask answer
                // bug: answered while browsing a past day → count on the
                // wrong day). Null date = "current date" per the doc above.
                date = entryDate,
                stampTime = stampTime
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
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        _errorMessage.value = "No text log file set for '$habitName'. Select one in edit mode."
        return
    }
    viewModelScope.launch {
        try {
            textInputRepo.appendMultipleTextEntries(
                Uri.parse(uriString), context, texts, date, time, habitName = habitName
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
                        Uri.parse(uriString),
                        context,
                        timestamp,
                        entryDate.plusDays(1),
                        endDate,
                        habitName = habitName
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
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        onResult(emptyList())
        return
    }
    viewModelScope.launch {
        try {
            val hidden = _settings.value.textInputHiddenGroupings[habitName] ?: emptySet()
            val options = textInputRepo.loadUniqueOptions(Uri.parse(uriString), context)
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
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        onResult(TextInputRepository.TextOptionInventory(emptyList(), emptyList()))
        return
    }
    viewModelScope.launch {
        try {
            onResult(textInputRepo.loadOptionInventory(Uri.parse(uriString), context))
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
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty() || newText == oldText) {
        onDone()
        return
    }
    viewModelScope.launch {
        try {
            textInputRepo.renameOptionValues(
                Uri.parse(uriString), context, oldText, newText, habitName = habitName
            )
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
// ── Graph mode ────────────────────────────────────────────────────────────

/** Whether graph mode is active. */
