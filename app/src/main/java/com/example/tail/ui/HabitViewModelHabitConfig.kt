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

/**
 * Translates a raw manual input value into the value that should be STORED for
 * [habitName]. For habits with custom point ranges enabled, the stored value is
 * the points tier (the index of the first range containing the raw value) — the
 * same contract used by [applyGarminData] and [recalculateHabitPointsForCustomRanges].
 * Returns null when the habit does not use custom point ranges.
 */
internal fun HabitViewModel.customRangePointsForInput(habitName: String, rawValue: Int): Int? {
    if (habitName !in _settings.value.customPointRangesHabits) return null
    val ranges = _settings.value.customPointRanges[habitName] ?: return null
    return com.example.tail.data.calculatePointsFromRanges(rawValue, ranges)
}

/**
 * Sets the count for [habitName] on the currently selected date to an absolute [newCount].
 * [newCount] is the raw value to store. Clamps to >= 0. Persists to the DB file.
 * For habits with custom point ranges enabled, [newCount] is treated as the raw input
 * ("true value") and the calculated points tier is stored instead — matching the
 * Garmin-linked write path.
 */


/**
 * Sets the count for [habitName] on the currently selected date to an absolute [newCount].
 * [newCount] is the raw value to store. Clamps to >= 0. Persists to the DB file.
 * For habits with custom point ranges enabled, [newCount] is treated as the raw input
 * ("true value") and the calculated points tier is stored instead — matching the
 * Garmin-linked write path.
 */
fun HabitViewModel.setHabitCount(habitName: String, newCount: Int) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    val clamped = newCount.coerceAtLeast(0)
    val divider = _settings.value.habitDividers[habitName] ?: 1
    val rangePoints = customRangePointsForInput(habitName, clamped)
    val storedValue = rangePoints ?: clamped

    // Step 1: instant targeted UI update
    _habits.value = _habits.value.map { h ->
        if (h.name == habitName) h.copy(
            todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                com.example.tail.data.invertedBinaryPoints(clamped)
            } else applyDivider(clamped, divider),
            rawTodayCount = storedValue
        ) else h
    }
    // Keep per-screen cache in sync
    screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

    // Step 2: update in-memory cache — compute delta from current stored value
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
    val currentCount = currentEntries[dateStr] ?: 0
    val delta = storedValue - currentCount
    
    // For roll forward habits, find the next manually set date BEFORE applying the change
    val nextManualDate = if (habitName in _settings.value.rollForwardHabits && delta != 0) {
        val manualDates = _settings.value.rollForwardManualDates[habitName] ?: emptySet()
        manualDates.mapNotNull { dateStr ->
            com.example.tail.data.parseDate(dateStr)
        }.sorted()
        .firstOrNull { it > _selectedDate.value }
    } else null
    
    var updatedDb = if (delta != 0) {
        habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, delta, _selectedDate.value)
    } else {
        cachedPhoneDb
    }
    
    // Step 2.5: Track this date as manually set for roll forward habits
    if (habitName in _settings.value.rollForwardHabits && delta != 0) {
        val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
        currentManualDates.add(dateStr)
        val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
        updatedManualDates[habitName] = currentManualDates
        viewModelScope.launch {
            settingsRepo.saveRollForwardManualDates(updatedManualDates)
            _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
        }
    }

    // Step 2.6: Roll forward logic - fill subsequent days for roll forward habits
    if (habitName in _settings.value.rollForwardHabits && delta != 0) {
        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        val selectedDate = _selectedDate.value
        val today = java.time.LocalDate.now()
        
        // Fill all dates from selectedDate to nextManualDate (exclusive) or today (inclusive)
        var currentDate = selectedDate.plusDays(1)
        val endDate = nextManualDate?.minusDays(1) ?: today
        
        while (currentDate <= endDate) {
            val currentDateStr = com.example.tail.data.dateString(currentDate)
            habitEntries[currentDateStr] = storedValue
            currentDate = currentDate.plusDays(1)
        }
        
        // Update the database with the filled entries
        updatedDb = updatedDb.toMutableMap()
        updatedDb[habitName] = habitEntries
    }
    
    cachedPhoneDb = updatedDb

    // Step 3: full rebuild + disk write in background
    viewModelScope.launch {
        rebuildHabitList()
        try {
            val uri = Uri.parse(uriString)
            habitsRepo.persistDatabase(uri, context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save: ${e.message}"
        }
    }
}

/**
 * Returns the SECONDARY value (e.g. timer minutes, stored under
 * `secondary_value:<habitName>`) for [habitName] on the currently
 * selected date. Returns 0 if the habit has no secondary value.
 */


/**
 * Returns the SECONDARY value (e.g. timer minutes, stored under
 * `secondary_value:<habitName>`) for [habitName] on the currently
 * selected date. Returns 0 if the habit has no secondary value.
 */
fun HabitViewModel.getSecondaryTodayCount(habitName: String): Int {
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    return cachedPhoneDb[com.example.tail.data.secondaryValueKey(habitName)]?.get(dateStr) ?: 0
}

/**
 * Sets the SECONDARY value (e.g. timer minutes, stored under
 * `secondary_value:<habitName>`) for [habitName] on the currently
 * selected date to an absolute [newCount]. Clamps to >= 0 and persists
 * to the DB file. Used by the edit-mode value picker for timer habits.
 */


/**
 * Sets the SECONDARY value (e.g. timer minutes, stored under
 * `secondary_value:<habitName>`) for [habitName] on the currently
 * selected date to an absolute [newCount]. Clamps to >= 0 and persists
 * to the DB file. Used by the edit-mode value picker for timer habits.
 */
fun HabitViewModel.setHabitSecondaryCount(habitName: String, newCount: Int) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    val clamped = newCount.coerceAtLeast(0)
    val secKey = com.example.tail.data.secondaryValueKey(habitName)
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)

    // Step 1: instant in-memory cache update
    val secEntries = cachedPhoneDb[secKey]?.toMutableMap() ?: mutableMapOf()
    secEntries[dateStr] = clamped
    val updatedDb = cachedPhoneDb.toMutableMap()
    updatedDb[secKey] = secEntries.toSortedMap()
    cachedPhoneDb = updatedDb

    // Step 2: rebuild (minutes drive points for minutes-primary habits)
    // + disk write in background
    viewModelScope.launch {
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save: ${e.message}"
        }
    }
}

/**
 * Sets the count for [habitName] on the currently selected date to an absolute [newCount]
 * with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog for count changes.
 */


/**
 * Sets the count for [habitName] on the currently selected date to an absolute [newCount]
 * with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog for count changes.
 */
fun HabitViewModel.setHabitCountWithRollForward(
    habitName: String,
    newCount: Int,
    customEndDate: LocalDate,
    onComplete: () -> Unit = {}
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        onComplete()
        return
    }
    val clamped = newCount.coerceAtLeast(0)
    val divider = _settings.value.habitDividers[habitName] ?: 1
    val rangePoints = customRangePointsForInput(habitName, clamped)
    val storedValue = rangePoints ?: clamped

    // Step 1: instant targeted UI update
    _habits.value = _habits.value.map { h ->
        if (h.name == habitName) h.copy(
            todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                com.example.tail.data.invertedBinaryPoints(clamped)
            } else applyDivider(clamped, divider),
            rawTodayCount = storedValue
        ) else h
    }
    // Keep per-screen cache in sync
    screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

    // Step 2: update in-memory cache — compute delta from current stored value
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
    val currentCount = currentEntries[dateStr] ?: 0
    val delta = storedValue - currentCount
    
    var updatedDb = if (delta != 0) {
        habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, delta, _selectedDate.value)
    } else {
        cachedPhoneDb
    }
    
    // Step 2.5: Track this date as manually set for roll forward habits
    if (habitName in _settings.value.rollForwardHabits && delta != 0) {
        val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
        currentManualDates.add(dateStr)
        val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
        updatedManualDates[habitName] = currentManualDates
        viewModelScope.launch {
            settingsRepo.saveRollForwardManualDates(updatedManualDates)
            _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
        }
    }

    // Step 2.6: Roll forward logic - fill subsequent days for roll forward habits
    if (habitName in _settings.value.rollForwardHabits && delta != 0) {
        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        val selectedDate = _selectedDate.value
        
        // Fill all dates from selectedDate+1 to customEndDate (inclusive)
        var currentDate = selectedDate.plusDays(1)
        val endDate = customEndDate
        
        while (currentDate <= endDate) {
            val currentDateStr = com.example.tail.data.dateString(currentDate)
            habitEntries[currentDateStr] = storedValue
            currentDate = currentDate.plusDays(1)
        }
        
        // Update the database with the filled entries
        updatedDb = updatedDb.toMutableMap()
        updatedDb[habitName] = habitEntries
    }
    
    cachedPhoneDb = updatedDb

    // Step 3: full rebuild + disk write in background
    viewModelScope.launch {
        rebuildHabitList()
        try {
            val uri = Uri.parse(uriString)
            habitsRepo.persistDatabase(uri, context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save: ${e.message}"
        }
        onComplete()
    }
}

/**
 * Toggles the "1 max" cap on/off for [habitName].
 * When enabled, the habit's daily count can never exceed 1 (binary done/not-done).
 */


/**
 * Toggles the "1 max" cap on/off for [habitName].
 * When enabled, the habit's daily count can never exceed 1 (binary done/not-done).
 */
fun HabitViewModel.toggleMaxOne(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.maxOneHabits.toMutableSet()
        val wasMaxOne = habitName in current
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveMaxOneHabits(current)
        // Enabling max-1 turns minutes OFF: a binary done/not-done habit
        // has no duration, so the minutes slot, minutes-primary role and
        // a minutes-sourced points fallback all stop making sense.
        var newSettings = _settings.value.copy(maxOneHabits = current)
        if (!wasMaxOne && habitName in current) {
            var primary = newSettings.widgetTimerMinutesPrimary
            var fallback = newSettings.secondaryValueFallbackHabits
            var minutes = newSettings.minutesEnabledHabits
            if (habitName in primary) primary = primary - habitName
            if (habitName in minutes) minutes = minutes - habitName
            // Only drop the fallback flag when its source is the minutes
            // slot (a legacy secondary_value: fallback keeps working).
            val legacyFallbackSource = habitName in newSettings.secondaryValueHabits ||
                !cachedPhoneDb[secondaryValueKey(habitName)].isNullOrEmpty()
            if (habitName in fallback && !legacyFallbackSource) fallback = fallback - habitName
            if (primary != newSettings.widgetTimerMinutesPrimary) {
                settingsRepo.saveWidgetTimerMinutesPrimary(primary)
            }
            if (fallback != newSettings.secondaryValueFallbackHabits) {
                settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            }
            if (minutes != newSettings.minutesEnabledHabits) {
                settingsRepo.saveMinutesEnabledHabits(minutes)
            }
            newSettings = newSettings.copy(
                widgetTimerMinutesPrimary = primary,
                secondaryValueFallbackHabits = fallback,
                minutesEnabledHabits = minutes
            )
        }
        _settings.value = newSettings
    }
}

/**
 * Toggles the "inverted binary" type on/off for [habitName].
 * When enabled, points and streaks are inverted: a day with no taps earns
 * 1 point and extends the streak; a day with one or more taps earns 0
 * points and breaks the streak (antistreak).
 */


/**
 * Toggles the "inverted binary" type on/off for [habitName].
 * When enabled, points and streaks are inverted: a day with no taps earns
 * 1 point and extends the streak; a day with one or more taps earns 0
 * points and breaks the streak (antistreak).
 */
fun HabitViewModel.toggleInvertedBinary(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.invertedBinaryHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveInvertedBinaryHabits(current)
        _settings.value = _settings.value.copy(invertedBinaryHabits = current)
        // Rebuild so streaks/points immediately reflect the new semantics
        rebuildHabitList()
        // The PC widget config carries the inverted_binary flag — refresh
        // it when a PC-widget habit's type changes.
        if (habitName in _settings.value.pcWidgetHabits) {
            pushPcWidgetConfig()
        }
    }
}

/**
 * Returns the number of historical days for [habitName] whose stored count exceeds 1
 * — i.e. the days that would be capped if "1 max" were applied retroactively.
 * Used to preview the impact before committing.
 */


/**
 * Returns the number of historical days for [habitName] whose stored count exceeds 1
 * — i.e. the days that would be capped if "1 max" were applied retroactively.
 * Used to preview the impact before committing.
 */
fun HabitViewModel.previewMaxOneAffectedDays(habitName: String): Int {
    val entries = cachedPhoneDb[habitName] ?: return 0
    return entries.values.count { it > 1 }
}

/**
 * Caps every historical entry for [habitName] to a maximum of 1.
 * Called after enabling "1 max" when the user chooses to update past totals.
 * Days already at 0 or 1 are left untouched; only counts > 1 are reduced.
 */


/**
 * Caps every historical entry for [habitName] to a maximum of 1.
 * Called after enabling "1 max" when the user chooses to update past totals.
 * Days already at 0 or 1 are left untouched; only counts > 1 are reduced.
 */
fun HabitViewModel.applyMaxOneToHistory(habitName: String) {
    val uri = _settings.value.fileUri
    if (uri.isEmpty()) return
    viewModelScope.launch {
        val loadResult = habitsRepo.loadDatabaseResult(
            android.net.Uri.parse(uri),
            context
        )
        if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return@launch

        val db = loadResult.db.toMutableMap()
        val habitEntries = db[habitName]?.toMutableMap() ?: return@launch

        var changed = false
        for ((dateStr, rawCount) in habitEntries) {
            if (rawCount > 1) {
                habitEntries[dateStr] = 1
                changed = true
            }
        }
        if (!changed) return@launch

        db[habitName] = habitEntries.toSortedMap()

        habitsRepo.saveDatabase(
            android.net.Uri.parse(uri),
            context,
            db
        )

        cachedPhoneDb = db
        rebuildHabitList()
    }
}

/**
 * Returns the number of past days for [habitName] whose stored count is lower
 * than the number of recorded timestamps for that day — i.e. days that were
 * capped by "1 max" but whose true increment count is preserved in the
 * timestamp log. Used to preview the restoration impact before committing.
 */


/**
 * Returns the number of past days for [habitName] whose stored count is lower
 * than the number of recorded timestamps for that day — i.e. days that were
 * capped by "1 max" but whose true increment count is preserved in the
 * timestamp log. Used to preview the restoration impact before committing.
 */
fun HabitViewModel.previewMaxOneRestorableDays(habitName: String): Int {
    val entries = cachedPhoneDb[habitName] ?: return 0
    val tsCounts = timestampRepo.getTimestampCountsForHabitSync(habitName)
    var count = 0
    for ((dateStr, rawCount) in entries) {
        val tsCount = tsCounts[dateStr] ?: 0
        if (tsCount > rawCount) count++
    }
    return count
}

/**
 * Restores the true increment count for every past day of [habitName] using
 * the recorded timestamps. Called after disabling "1 max" when the user
 * chooses to make past entries count fully toward totals again.
 *
 * Only days where the timestamp count exceeds the current (capped) stored
 * count are updated; all other days are left untouched.
 */


/**
 * Restores the true increment count for every past day of [habitName] using
 * the recorded timestamps. Called after disabling "1 max" when the user
 * chooses to make past entries count fully toward totals again.
 *
 * Only days where the timestamp count exceeds the current (capped) stored
 * count are updated; all other days are left untouched.
 */
fun HabitViewModel.restoreMaxOneFromTimestamps(habitName: String) {
    val uri = _settings.value.fileUri
    if (uri.isEmpty()) return
    viewModelScope.launch {
        val loadResult = habitsRepo.loadDatabaseResult(
            android.net.Uri.parse(uri),
            context
        )
        if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return@launch

        val db = loadResult.db.toMutableMap()
        val habitEntries = db[habitName]?.toMutableMap() ?: return@launch

        val tsCounts = timestampRepo.getTimestampCountsForHabitSync(habitName)

        var changed = false
        for ((dateStr, rawCount) in habitEntries) {
            val tsCount = tsCounts[dateStr] ?: 0
            if (tsCount > rawCount) {
                habitEntries[dateStr] = tsCount
                changed = true
            }
        }
        if (!changed) return@launch

        db[habitName] = habitEntries.toSortedMap()

        habitsRepo.saveDatabase(
            android.net.Uri.parse(uri),
            context,
            db
        )

        cachedPhoneDb = db
        rebuildHabitList()
    }
}


fun HabitViewModel.toggleCustomInput(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.customInputHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveCustomInputHabits(current)
        _habits.value = _habits.value.map { habit ->
            habit.copy(useCustomInput = habit.name in current)
        }
    }
}

/**
 * Saves a custom list of quick-increment button amounts for [habitName].
 * Pass an empty list to revert to the default amounts.
 */


/**
 * Saves a custom list of quick-increment button amounts for [habitName].
 * Pass an empty list to revert to the default amounts.
 */
fun HabitViewModel.setCustomInputAmounts(habitName: String, amounts: List<Int>) {
    viewModelScope.launch {
        val current = _settings.value.customInputAmounts.toMutableMap()
        if (amounts.isEmpty()) {
            current.remove(habitName)
        } else {
            current[habitName] = amounts
        }
        settingsRepo.saveCustomInputAmounts(current)
        _settings.value = _settings.value.copy(customInputAmounts = current)
    }
}

/**
 * Records [amount] as the most recently used increment for [habitName].
 * Keeps up to 3 unique recent amounts, most recent first.
 */


/**
 * Records [amount] as the most recently used increment for [habitName].
 * Keeps up to 3 unique recent amounts, most recent first.
 */
fun HabitViewModel.recordRecentIncrementAmount(habitName: String, amount: Int) {
    viewModelScope.launch {
        val current = _settings.value.customInputRecentAmounts.toMutableMap()
        val existing = current[habitName]?.toMutableList() ?: mutableListOf()
        existing.remove(amount)          // remove duplicate if present
        existing.add(0, amount)          // prepend as most recent
        if (existing.size > 3) existing.subList(3, existing.size).clear()
        current[habitName] = existing
        settingsRepo.saveCustomInputRecentAmounts(current)
        _settings.value = _settings.value.copy(customInputRecentAmounts = current)
    }
}

/**
 * Sets (or clears) the divider for [habitName].
 * [divisor] must be >= 2 to enable division; pass 1 (or 0) to disable.
 * When changed, the habit list is rebuilt so the displayed count updates immediately.
 */


/**
 * Sets (or clears) the divider for [habitName].
 * [divisor] must be >= 2 to enable division; pass 1 (or 0) to disable.
 * When changed, the habit list is rebuilt so the displayed count updates immediately.
 */
fun HabitViewModel.setHabitDivider(habitName: String, divisor: Int) {
    viewModelScope.launch {
        val current = _settings.value.habitDividers.toMutableMap()
        if (divisor <= 1) {
            current.remove(habitName)
        } else {
            current[habitName] = divisor
        }
        settingsRepo.saveHabitDividers(current)
        _settings.value = _settings.value.copy(habitDividers = current)
        rebuildHabitList()
        // The PC widget config carries the divider — refresh it when a
        // PC-widget habit's divider changes, or the PC keeps scoring with
        // the stale one (raw minutes shown as points).
        if (habitName in _settings.value.pcWidgetHabits) {
            pushPcWidgetConfig()
        }
    }
}


/**
 * Toggles the "conditional" type on/off for [habitName].
 * When enabled, tapping this habit also auto-increments all habits in its linked set.
 * When disabled, the linked set is removed as well — otherwise the orphaned entry
 * keeps showing up as a phantom "Fed by" source on the habits it used to link to.
 */
fun HabitViewModel.toggleConditional(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.conditionalHabits.toMutableSet()
        val links = _settings.value.conditionalLinkedHabits.toMutableMap()
        val values = _settings.value.conditionalLinkValues.toMutableMap()
        val feedMaxOne = _settings.value.conditionalFeedMaxOneHabits.toMutableSet()
        val feedPoints = _settings.value.conditionalFeedPointsHabits.toMutableSet()
        var linksChanged = false
        if (habitName in current) {
            current.remove(habitName)
            if (links.remove(habitName) != null) linksChanged = true
            if (values.remove(habitName) != null) linksChanged = true
            if (feedMaxOne.remove(habitName)) linksChanged = true
            if (feedPoints.remove(habitName)) linksChanged = true
        } else {
            current.add(habitName)
        }
        settingsRepo.saveConditionalHabits(current)
        if (linksChanged) {
            settingsRepo.saveConditionalLinkedHabits(links)
            settingsRepo.saveConditionalLinkValues(values)
            settingsRepo.saveConditionalFeedMaxOneHabits(feedMaxOne)
            settingsRepo.saveConditionalFeedPointsHabits(feedPoints)
            _settings.value = _settings.value.copy(
                conditionalHabits = current,
                conditionalLinkedHabits = links,
                conditionalLinkValues = values,
                conditionalFeedMaxOneHabits = feedMaxOne,
                conditionalFeedPointsHabits = feedPoints
            )
        } else {
            _settings.value = _settings.value.copy(conditionalHabits = current)
        }
    }
}

/**
 * Sets the linked habits for a conditional habit.
 * [linkedNames] is the full replacement set of habit names to auto-increment.
 * Pass an empty set to clear all links (but keep the conditional type enabled).
 */


/**
 * Performs a complete conditional backfill for [habitName].
 *
 * Overwrites [habitName]'s entire history so that, for every day, its stored count
 * equals the sum of the counts of every source habit (conditional habits that link
 * to it) on that day. This destroys any manually-entered data for [habitName] and
 * persists the recomputed values to the habits file.
 */
fun HabitViewModel.performConditionalBackfill(habitName: String) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    val slots = conditionalBackfillSlots(habitName)
    if (slots.isEmpty()) {
        _errorMessage.value = "No habits feed into \"$habitName\"."
        return
    }
    val isMaxOne = habitName in _settings.value.maxOneHabits

    // Recompute each fed slot independently: for every day, the slot's stored
    // value equals the sum of the counts of the source habits feeding it.
    // (The max-1 cap only applies to the primary count slot.)
    val updatedDb = cachedPhoneDb.toMutableMap()
    var totalApplied = 0
    var daysTouched = 0
    for ((slotKey, slotSources) in slots) {
        val capped = slotKey == habitName && isMaxOne
        val dates = mutableSetOf<String>()
        for (src in slotSources) {
            dates.addAll(cachedPhoneDb[src]?.keys ?: emptySet())
        }
        val newEntries = sortedMapOf<String, Int>()
        for (d in dates.sorted()) {
            var sum = 0
            for (src in slotSources) {
                val c = cachedPhoneDb[src]?.get(d) ?: 0
                sum += if (slotKey == habitName && src in _settings.value.conditionalFeedMaxOneHabits) {
                    c.coerceAtMost(1)
                } else c
            }
            val stored = if (capped) sum.coerceAtMost(1) else sum
            if (stored > 0) {
                newEntries[d] = stored
                totalApplied += stored
            }
        }
        daysTouched = maxOf(daysTouched, newEntries.size)
        updatedDb[slotKey] = newEntries
    }
    cachedPhoneDb = updatedDb

    viewModelScope.launch {
        rebuildHabitList()
        try {
            val uri = Uri.parse(uriString)
            habitsRepo.persistDatabase(uri, context, updatedDb)
            HabitsDataChangedBus.emit()
            _errorMessage.value =
                "Backfilled \"$habitName\": $totalApplied increments across $daysTouched day(s), ${slots.size} value slot(s)."
        } catch (e: Exception) {
            _errorMessage.value = "Backfill save failed: ${e.message}"
        }
    }
}

// ── Subtyped habit methods ──────────────────────────────────────────────

/** Toggles the "subtyped" type on/off for [habitName]. */


/** Toggles the "subtyped" type on/off for [habitName]. */
fun HabitViewModel.toggleSubtyped(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.subtypedHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveSubtypedHabits(current)
        _settings.value = _settings.value.copy(subtypedHabits = current)
    }
}

/** Sets the ordered list of subtype names for [habitName]. */


/** Sets the ordered list of subtype names for [habitName]. */
fun HabitViewModel.setHabitSubtypes(habitName: String, subtypes: List<String>) {
    viewModelScope.launch {
        val current = _settings.value.habitSubtypes.toMutableMap()
        if (subtypes.isEmpty()) current.remove(habitName) else current[habitName] = subtypes
        settingsRepo.saveHabitSubtypes(current)
        _settings.value = _settings.value.copy(habitSubtypes = current)
    }
}

/**
 * Loads today's subtype breakdown for [habitName], then calls [onLoaded] with the result.
 * Returns empty map if no data exists for today.
 */


/**
 * Loads today's subtype breakdown for [habitName], then calls [onLoaded] with the result.
 * Returns empty map if no data exists for today.
 */
fun HabitViewModel.loadSubtypeBreakdown(habitName: String, onLoaded: (Map<String, Int>) -> Unit) {
    viewModelScope.launch {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val breakdown = subtypeDataRepo.getBreakdownForDate(habitName, dateStr)
        onLoaded(breakdown)
    }
}

/**
 * Saves a subtype increment: adds [increments] to the internal subtype store for today,
 * and increments the main habit count by the total.
 */


/**
 * Saves a subtype increment: adds [increments] to the internal subtype store for today,
 * and increments the main habit count by the total.
 */
fun HabitViewModel.saveSubtypeIncrement(habitName: String, increments: Map<String, Int>) {
    val total = increments.values.sum()
    if (total <= 0) return

    // Increment the main habit count
    incrementHabit(habitName, total)

    // Save subtype breakdown (internal store)
    viewModelScope.launch {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        subtypeDataRepo.addToDate(habitName, dateStr, increments)
    }

    // If this is a timed habit, also record timestamped session entries
    if (habitName in _settings.value.timedHabits) {
        viewModelScope.launch {
            // Each subtype increment becomes a separate timed entry
            // (key type widened to String? since plain timed entries have no subtype)
            timedDataRepo.appendEntries(habitName, increments.mapKeys { (k, _) -> k as String? })
        }
    }
}

// ── Weights habit type (machine / free weight + reps logging) ─────────

/** Records an exercise/machine name for quick re-entry (most recent first, capped at 10). */


/** Records an exercise/machine name for quick re-entry (most recent first, capped at 10). */
internal fun HabitViewModel.recordRecentExercise(habitName: String, trimmedExercise: String) {
    if (trimmedExercise.isEmpty()) return
    viewModelScope.launch {
        val current = _settings.value.weightsRecentExercises.toMutableMap()
        val existing = current[habitName]?.toMutableList() ?: mutableListOf()
        existing.remove(trimmedExercise)          // move-to-front on re-use
        existing.add(0, trimmedExercise)
        if (existing.size > 10) existing.subList(10, existing.size).clear()
        current[habitName] = existing
        settingsRepo.saveWeightsRecentExercises(current)
        _settings.value = _settings.value.copy(weightsRecentExercises = current)
    }
}

/**
 * Saves a weights-habit log entry: the slot writes (weight max-merge +
 * reps accumulate) are applied to the in-memory DB FIRST, then the +1 on
 * the habit's own count is routed through the regular increment path —
 * whose single background persist then carries count + slots in ONE
 * atomic disk write. (The previous shape — incrementHabit's async
 * persist racing a separate disk load-modify-save for the slots — could
 * interleave and silently lose the +1 or the slots.)
 */


/**
 * Saves a weights-habit log entry: the slot writes (weight max-merge +
 * reps accumulate) are applied to the in-memory DB FIRST, then the +1 on
 * the habit's own count is routed through the regular increment path —
 * whose single background persist then carries count + slots in ONE
 * atomic disk write. (The previous shape — incrementHabit's async
 * persist racing a separate disk load-modify-save for the slots — could
 * interleave and silently lose the +1 or the slots.)
 */
fun HabitViewModel.saveWeightsEntry(
    habitName: String,
    weightGrams: Int,
    reps: Int,
    machine: Boolean,
    exerciseName: String = ""
) {
    if (weightGrams <= 0 && reps <= 0) return
    val uriString = _settings.value.fileUri
    if (uriString.isNullOrEmpty()) return

    // Slot writes FIRST: the increment path below snapshots
    // cachedPhoneDb (applyIncrementToDb) and persists that snapshot,
    // so the slots must already be in it.
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val countBefore = cachedPhoneDb[habitName]?.get(dateStr) ?: 0
    cachedPhoneDb = habitsRepo.applyWeightsSlotsToDb(
        cachedPhoneDb, habitName, weightGrams, reps, machine, _selectedDate.value
    )

    // One logged entry → +1 on the habit's own count (instant UI update,
    // timestamps, broadcast, conditional feeds; persists count + slots
    // together in one write).
    incrementHabit(habitName, 1)

    // incrementHabit early-returns WITHOUT persisting when the count
    // cannot change (max-1 cap already at 1, unchanged point tier). In
    // that case persist the slot writes ourselves so they are not lost.
    val countAfter = cachedPhoneDb[habitName]?.get(dateStr) ?: 0
    if (countAfter == countBefore) {
        viewModelScope.launch {
            rebuildHabitList()
            try {
                habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save weights entry: ${e.message}"
            }
        }
    }

    // Remember the exercise/machine name for quick re-entry (most recent first)
    recordRecentExercise(habitName, exerciseName.trim())
}

/** Returns the selected date's aggregated weights slots for a weights habit. */


/** Returns the selected date's aggregated weights slots for a weights habit. */
fun HabitViewModel.getWeightsDayValues(habitName: String): WeightsDayValues {
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    fun slot(key: String) = cachedPhoneDb[key]?.get(dateStr) ?: 0
    return WeightsDayValues(
        machineWeightGrams = slot(com.example.tail.data.secondaryValueKey(habitName)),
        machineReps = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 2)),
        freeWeightGrams = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 3)),
        freeReps = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 4))
    )
}

/**
 * Overwrites the selected date's weights slots absolutely (edit-mode day
 * editor). Zero values clear the slot for the day. The habit's own count
 * (number of logged entries) is not touched.
 */


/**
 * Overwrites the selected date's weights slots absolutely (edit-mode day
 * editor). Zero values clear the slot for the day. The habit's own count
 * (number of logged entries) is not touched.
 */
fun HabitViewModel.setWeightsDayValues(habitName: String, values: WeightsDayValues, exerciseName: String = "") {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val updatedDb = cachedPhoneDb.toMutableMap()
    fun setSlot(key: String, value: Int) {
        val entries = updatedDb[key]?.toMutableMap() ?: mutableMapOf()
        if (value > 0) entries[dateStr] = value else entries.remove(dateStr)
        if (entries.isEmpty()) updatedDb.remove(key) else updatedDb[key] = entries.toSortedMap()
    }
    setSlot(com.example.tail.data.secondaryValueKey(habitName), values.machineWeightGrams.coerceAtLeast(0))
    setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 2), values.machineReps.coerceAtLeast(0))
    setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 3), values.freeWeightGrams.coerceAtLeast(0))
    setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 4), values.freeReps.coerceAtLeast(0))
    cachedPhoneDb = updatedDb
    viewModelScope.launch {
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save weights values: ${e.message}"
        }
    }

    // Keep the exercise quick-choices fresh when an edited entry names one
    recordRecentExercise(habitName, exerciseName.trim())
}

/**
 * Removes ALL weights data for [habitName] on the selected date: the four
 * slots (machine/free weight + reps), the day's count (increment) and its
 * timestamps — the "totally remove it for a day" edit-screen action.
 */


/**
 * Removes ALL weights data for [habitName] on the selected date: the four
 * slots (machine/free weight + reps), the day's count (increment) and its
 * timestamps — the "totally remove it for a day" edit-screen action.
 */
fun HabitViewModel.deleteWeightsDay(habitName: String) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val updatedDb = cachedPhoneDb.toMutableMap()

    // Clear the four weights slots for the day
    for (key in listOf(
        com.example.tail.data.secondaryValueKey(habitName),
        com.example.tail.data.secondaryValueSlotKey(habitName, 2),
        com.example.tail.data.secondaryValueSlotKey(habitName, 3),
        com.example.tail.data.secondaryValueSlotKey(habitName, 4)
    )) {
        val entries = updatedDb[key]?.toMutableMap() ?: continue
        entries.remove(dateStr)
        if (entries.isEmpty()) updatedDb.remove(key) else updatedDb[key] = entries
    }

    // Remove the day's increment (count)
    updatedDb[habitName]?.let { entries ->
        val mutable = entries.toMutableMap()
        mutable.remove(dateStr)
        if (mutable.isEmpty()) updatedDb.remove(habitName) else updatedDb[habitName] = mutable
    }

    cachedPhoneDb = updatedDb
    viewModelScope.launch {
        rebuildHabitList()
        // Also drop the day's timestamps (separate store, no DB race)
        timestampRepo.setTimestampsForDay(habitName, _selectedDate.value, emptyList())
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to delete weights day: ${e.message}"
        }
    }
}

// ── Timed habit settings ──────────────────────────────────────────────

/** Toggles the "timed" feature on/off for [habitName]. */


/** Toggles the "timed" feature on/off for [habitName]. */
fun HabitViewModel.toggleTimed(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.timedHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveTimedHabits(current)
        _settings.value = _settings.value.copy(timedHabits = current)
    }
}

/** Toggles the "timeless" feature on/off for [habitName]. */


/** Toggles the "timeless" feature on/off for [habitName]. */
fun HabitViewModel.toggleTimeless(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.timelessHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveTimelessHabits(current)
        _settings.value = _settings.value.copy(timelessHabits = current)
    }
}

/** Toggles the "roll forward" feature on/off for [habitName]. */


/** Toggles the "roll forward" feature on/off for [habitName]. */
fun HabitViewModel.toggleRollForward(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.rollForwardHabits.toMutableSet()
        if (habitName in current) {
            // Disabling: remove from set and clear manual dates
            current.remove(habitName)
            val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
            updatedManualDates.remove(habitName)
            settingsRepo.saveRollForwardManualDates(updatedManualDates)
            _settings.value = _settings.value.copy(
                rollForwardHabits = current,
                rollForwardManualDates = updatedManualDates
            )
        } else {
            // Enabling: just add to set
            current.add(habitName)
            settingsRepo.saveRollForwardHabits(current)
            _settings.value = _settings.value.copy(rollForwardHabits = current)
        }
    }
}

/** Toggles edit (tap-to-select reorder) mode on/off. Clears selection when turning off. */


/**
 * Toggles the "disabled" flag for [habitName].
 * A disabled habit shows a red ✕ overlay and is excluded from stats aggregates.
 */
fun HabitViewModel.toggleDisabledHabit(habitName: String) {
    val current = _settings.value.disabledHabits.toMutableSet()
    if (habitName in current) current.remove(habitName) else current.add(habitName)
    _settings.value = _settings.value.copy(disabledHabits = current)
    viewModelScope.launch { settingsRepo.saveDisabledHabits(current) }
}

/**
 * Toggles the "no points" flag for [habitName].
 * When enabled, the habit's points are NOT included in any totals.
 * This triggers a full recalculation of all historical data and external files.
 */


/**
 * Toggles the "no points" flag for [habitName].
 * When enabled, the habit's points are NOT included in any totals.
 * This triggers a full recalculation of all historical data and external files.
 */
fun HabitViewModel.toggleNoPointsHabit(habitName: String) {
    val current = _settings.value.noPointsHabits.toMutableSet()
    if (habitName in current) current.remove(habitName) else current.add(habitName)
    _settings.value = _settings.value.copy(noPointsHabits = current)
    viewModelScope.launch {
        settingsRepo.saveNoPointsHabits(current)
        // The PC widget config carries the no_points flag — refresh it
        // when a PC-widget habit's flag changes.
        if (habitName in _settings.value.pcWidgetHabits) {
            pushPcWidgetConfig()
        }
    }
}

/**
 * Toggles the "Secondary Value" feature for [habitName].
 * When enabled, the habit stores a second integer value per day (accessible
 * via the graph screen's "Value2" button). Secondary values are stored in
 * habitsdb.txt under the key "secondary_value:<habitName>".
 */


/**
 * Toggles the "Secondary Value" feature for [habitName].
 * When enabled, the habit stores a second integer value per day (accessible
 * via the graph screen's "Value2" button). Secondary values are stored in
 * habitsdb.txt under the key "secondary_value:<habitName>".
 */
fun HabitViewModel.toggleSecondaryValueHabit(habitName: String) {
    val current = _settings.value.secondaryValueHabits.toMutableSet()
    if (habitName in current) current.remove(habitName) else current.add(habitName)
    _settings.value = _settings.value.copy(secondaryValueHabits = current)
    viewModelScope.launch { settingsRepo.saveSecondaryValueHabits(current) }
}

/**
 * Toggles the "Secondary Value Fallback for Points" feature for [habitName].
 *
 * When enabled (and the habit also has Secondary Value enabled), days where
 * the primary value (Value1) is zero will fall back to the secondary value
 * (Value2) for points calculation. The fallback points equal the raw
 * secondary value (no divider applied).
 *
 * Requires the habit to also be in [secondaryValueHabits]. If the habit is
 * not in [secondaryValueHabits], enabling this will also enable Secondary Value.
 */


/**
 * Toggles the "Secondary Value Fallback for Points" feature for [habitName].
 *
 * When enabled (and the habit also has Secondary Value enabled), days where
 * the primary value (Value1) is zero will fall back to the secondary value
 * (Value2) for points calculation. The fallback points equal the raw
 * secondary value (no divider applied).
 *
 * Requires the habit to also be in [secondaryValueHabits]. If the habit is
 * not in [secondaryValueHabits], enabling this will also enable Secondary Value.
 */
fun HabitViewModel.toggleSecondaryValueFallbackHabit(habitName: String) {
    val current = _settings.value.secondaryValueFallbackHabits.toMutableSet()
    if (habitName in current) {
        current.remove(habitName)
    } else {
        current.add(habitName)
        // Auto-enable Secondary Value if not already enabled
        if (habitName !in _settings.value.secondaryValueHabits) {
            val secValHabits = _settings.value.secondaryValueHabits.toMutableSet()
            secValHabits.add(habitName)
            _settings.value = _settings.value.copy(secondaryValueHabits = secValHabits)
            viewModelScope.launch { settingsRepo.saveSecondaryValueHabits(secValHabits) }
        }
    }
    _settings.value = _settings.value.copy(secondaryValueFallbackHabits = current)
    viewModelScope.launch {
        settingsRepo.saveSecondaryValueFallbackHabits(current)
        rebuildHabitList()
    }
}

/** Returns true if [habitName] has the secondary value fallback feature enabled. */


/** Returns true if [habitName] has the secondary value fallback feature enabled. */
fun HabitViewModel.hasSecondaryValueFallback(habitName: String): Boolean {
    return habitName in _settings.value.secondaryValueFallbackHabits
}

// ── Display-only value/subtype label overrides ──────────────────────────

/**
 * Returns the display label for [habitName]'s [valueKey], using the custom
 * override if one exists, otherwise the default label.
 *
 * This is **display-only** — the underlying [valueKey] is never modified.
 */


/**
 * Returns the display label for [habitName]'s [valueKey], using the custom
 * override if one exists, otherwise the default label.
 *
 * This is **display-only** — the underlying [valueKey] is never modified.
 */
fun HabitViewModel.getValueDisplayLabel(habitName: String, valueKey: String): String {
    return com.example.tail.data.displayLabelForValue(
        habitName, valueKey, _settings.value.valueDisplayLabels
    )
}

/**
 * Sets a custom display label for [habitName]'s [valueKey].
 *
 * If [label] is blank the override is removed (falls back to default).
 * The backend [valueKey] (e.g. `"value2"` or a subtype name) is never changed.
 */


/**
 * Sets a custom display label for [habitName]'s [valueKey].
 *
 * If [label] is blank the override is removed (falls back to default).
 * The backend [valueKey] (e.g. `"value2"` or a subtype name) is never changed.
 */
fun HabitViewModel.setValueDisplayLabel(habitName: String, valueKey: String, label: String) {
    val current = _settings.value.valueDisplayLabels.toMutableMap()
    val inner = current[habitName]?.toMutableMap() ?: mutableMapOf()
    if (label.isBlank()) {
        inner.remove(valueKey)
    } else {
        inner[valueKey] = label
    }
    if (inner.isEmpty()) {
        current.remove(habitName)
    } else {
        current[habitName] = inner
    }
    _settings.value = _settings.value.copy(valueDisplayLabels = current)
    viewModelScope.launch { settingsRepo.saveValueDisplayLabels(current) }
}

/**
 * Computes the effective points for [habitName] on the given [dateStr],
 * applying the secondary-value fallback when enabled.
 */


/**
 * Computes the effective points for [habitName] on the given [dateStr],
 * applying the secondary-value fallback when enabled.
 */
internal fun HabitViewModel.effectivePointsForDate(habitName: String, rawCount: Int, dateStr: String): Int {
    val divider = _settings.value.habitDividers[habitName] ?: 1
    // Minutes-primary habits: minutes (the first-class minutes slot) drive
    // points (divider applies), sessions are the zero-minutes fallback.
    // Inverted-binary habits: 1 point on not-done days, 0 on done days —
    // but never before the habit's first recorded entry (no retroactive
    // points for dates that predate the habit or any data on it).
    if (habitName in _settings.value.invertedBinaryHabits) {
        val firstDataDate = cachedPhoneDb[habitName]
            ?.filterValues { it != 0 }?.keys?.minOrNull()
        if (firstDataDate == null || dateStr < firstDataDate) return 0
        return com.example.tail.data.invertedBinaryPoints(rawCount)
    }
    if (habitName in _settings.value.widgetTimerMinutesPrimary) {
        val minutes = cachedPhoneDb[minutesKey(habitName)]?.get(dateStr) ?: 0
        // Minutes-primary: which value covers points on 0-minute days is
        // configurable — sessions (the default), the second value, or none.
        return when (
            _settings.value.minutesPrimaryFallbacks[habitName]
                ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS
        ) {
            com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE ->
                applyDivider(minutes, divider)
            com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2 -> {
                val v2 = cachedPhoneDb[secondaryValueKey(habitName)]?.get(dateStr) ?: 0
                com.example.tail.data.effectivePointsWithFallback(minutes, divider, v2, true)
            }
            else -> com.example.tail.data.effectivePointsWithFallback(minutes, divider, rawCount, true)
        }
    }
    val useFallback = habitName in _settings.value.secondaryValueFallbackHabits
    if (!useFallback) return applyDivider(rawCount, divider)
    // Fallback source: the legacy generic secondary slot when the habit
    // uses it or has data there (Meditations/Apnea/Resonance sessions,
    // chess.com games, JugCoach seconds), otherwise the first-class
    // minutes slot.
    val fallbackKey = com.example.tail.data.fallbackSlotKey(
        habitName, _settings.value.secondaryValueHabits, cachedPhoneDb
    )
    val secVal = cachedPhoneDb[fallbackKey]?.get(dateStr) ?: 0
    return com.example.tail.data.effectivePointsWithFallback(rawCount, divider, secVal, true)
}

/**
 * Points earned by ONE schedule instance of [habitName] with [amount]
 * increment units (divider applied). Null when points are not
 * attributable per instance — no-points habits, inverted-binary habits
 * and minutes-primary habits all derive points from day-level values
 * the schedule timeline cannot split per block.
 */


/**
 * Points earned by ONE schedule instance of [habitName] with [amount]
 * increment units (divider applied). Null when points are not
 * attributable per instance — no-points habits, inverted-binary habits
 * and minutes-primary habits all derive points from day-level values
 * the schedule timeline cannot split per block.
 */
fun HabitViewModel.scheduleInstancePoints(habitName: String, amount: Int): Int? {
    val s = _settings.value
    if (habitName in s.noPointsHabits) return null
    if (habitName in s.invertedBinaryHabits) return null
    if (habitName in s.widgetTimerMinutesPrimary) return null
    val divider = s.habitDividers[habitName] ?: 1
    return applyDivider(amount, divider)
}

/**
 * Returns the selected date's minutes for [habitName] from the first-class
 * minutes slot (`minutes:<habit>`).
 */


/**
 * Returns the selected date's minutes for [habitName] from the first-class
 * minutes slot (`minutes:<habit>`).
 */
fun HabitViewModel.getMinutesTodayCount(habitName: String): Int {
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    return cachedPhoneDb[minutesKey(habitName)]?.get(dateStr) ?: 0
}

/**
 * Sets the selected date's minutes for [habitName] in the first-class
 * minutes slot (clamped to ≥ 0), then refreshes the habit list,
 * widgets and listeners.
 *
 * Mirrors [setHabitSecondaryCount]: the minutes slot is updated in the
 * in-memory cache synchronously and the full-file write happens in the
 * background. The previous implementation reloaded and re-parsed the
 * ENTIRE habits DB on every keystroke (plus a second full reload via
 * the HabitIncrementBus collector); concurrent multi-megabyte Gson
 * parses piled up on Dispatchers.IO and exhausted the heap — an
 * OutOfMemoryError crash when editing a media habit's minutes.
 */


/**
 * Sets the selected date's minutes for [habitName] in the first-class
 * minutes slot (clamped to ≥ 0), then refreshes the habit list,
 * widgets and listeners.
 *
 * Mirrors [setHabitSecondaryCount]: the minutes slot is updated in the
 * in-memory cache synchronously and the full-file write happens in the
 * background. The previous implementation reloaded and re-parsed the
 * ENTIRE habits DB on every keystroke (plus a second full reload via
 * the HabitIncrementBus collector); concurrent multi-megabyte Gson
 * parses piled up on Dispatchers.IO and exhausted the heap — an
 * OutOfMemoryError crash when editing a media habit's minutes.
 */
fun HabitViewModel.setHabitMinutesCount(habitName: String, newCount: Int) {
    val uriString = _settings.value.fileUri
    if (uriString.isNullOrEmpty()) return
    val clamped = newCount.coerceAtLeast(0)
    val minKey = minutesKey(habitName)
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)

    // Step 1: instant in-memory cache update — no DB reload per keystroke.
    val updatedDb = cachedPhoneDb.toMutableMap()
    val entries = updatedDb[minKey]?.toMutableMap() ?: mutableMapOf()
    if (clamped > 0) entries[dateStr] = clamped else entries.remove(dateStr)
    if (entries.isEmpty()) updatedDb.remove(minKey) else updatedDb[minKey] = entries.toSortedMap()
    cachedPhoneDb = updatedDb

    // Step 2: rebuild + disk write in the background. Serialized so a
    // keystroke burst never overlaps full-file writes, and each write
    // persists the LATEST cached state (read inside the lock, not
    // captured), so a slow older write can never clobber a newer one.
    viewModelScope.launch {
        minutesWriteMutex.withLock {
            rebuildHabitList()
            try {
                habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
                HabitsDataChangedBus.emit()
                HabitListWidgetProvider.refreshAll(context)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to set minutes: ${e.message}"
            }
        }
    }
}

/** Serializes minutes-slot disk writes (see [setHabitMinutesCount]). */


/**
 * Toggles the "fall back to minutes" points fallback for a sessions-primary
 * habit. Writes the shared fallback set; the fallback SOURCE resolves to
 * the minutes slot unless the habit uses the legacy generic secondary value.
 */
fun HabitViewModel.toggleMinutesFallbackHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.secondaryValueFallbackHabits
        val updated = if (habitName in current) current - habitName else current + habitName
        settingsRepo.saveSecondaryValueFallbackHabits(updated)
        _settings.value = _settings.value.copy(secondaryValueFallbackHabits = updated)
        rebuildHabitList()
    }
}

/** The fallback source a minutes-primary habit uses on 0-minute days. */


/** The fallback source a minutes-primary habit uses on 0-minute days. */
fun HabitViewModel.getMinutesPrimaryFallback(habitName: String): String =
    _settings.value.minutesPrimaryFallbacks[habitName]
        ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS

/**
 * Sets which value covers points on 0-minute days for a MINUTES-PRIMARY
 * habit: none, the sessions value (the default), or the second value.
 * Only non-default choices are stored, so absent = sessions.
 */


/**
 * Sets which value covers points on 0-minute days for a MINUTES-PRIMARY
 * habit: none, the sessions value (the default), or the second value.
 * Only non-default choices are stored, so absent = sessions.
 */
fun HabitViewModel.setMinutesPrimaryFallback(habitName: String, source: String) {
    viewModelScope.launch {
        val current = _settings.value.minutesPrimaryFallbacks
        val updated = if (source == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS) {
            current - habitName
        } else {
            current + (habitName to source)
        }
        if (updated == current) return@launch
        settingsRepo.saveMinutesPrimaryFallbacks(updated)
        _settings.value = _settings.value.copy(minutesPrimaryFallbacks = updated)
        rebuildHabitList()
    }
}

/**
 * The EFFECTIVE minutes-enabled state for [habitName]: the explicit
 * toggle OR any timer-widget connection (which forces minutes on),
 * minus max-1 habits (which force minutes off). See
 * [com.example.tail.data.effectiveMinutesEnabled].
 */


/**
 * The EFFECTIVE minutes-enabled state for [habitName]: the explicit
 * toggle OR any timer-widget connection (which forces minutes on),
 * minus max-1 habits (which force minutes off). See
 * [com.example.tail.data.effectiveMinutesEnabled].
 */
fun HabitViewModel.isMinutesEnabled(habitName: String): Boolean {
    val s = _settings.value
    return effectiveMinutesEnabled(
        habitName,
        s.minutesEnabledHabits,
        s.pcWidgetHabits,
        s.widgetTriggerHabits,
        s.mediaHabits,
        s.bridgeMovieHabits,
        s.widgetTimerMinutesPrimary,
        s.maxOneHabits
    )
}

/**
 * True when minutes is forced ON by a timer-widget connection (PC
 * widget, phone bubble trigger), a media tracker, or the movie bridge —
 * the edit panel shows the minutes toggle as locked-on for these habits.
 */


/**
 * True when minutes is forced ON by a timer-widget connection (PC
 * widget, phone bubble trigger), a media tracker, or the movie bridge —
 * the edit panel shows the minutes toggle as locked-on for these habits.
 */
fun HabitViewModel.isMinutesForcedByWidget(habitName: String): Boolean {
    val s = _settings.value
    return habitName in s.pcWidgetHabits ||
        habitName in s.widgetTriggerHabits ||
        habitName in s.mediaHabits ||
        habitName in s.bridgeMovieHabits
}

/**
 * Toggles the per-habit minutes value on/off.
 *
 * Refuses to change habits whose minutes state is forced: max-1 habits
 * (never minutes) and timer-widget/media/movie-connected habits (always
 * minutes). Turning minutes OFF also clears the minutes-primary role,
 * its fallback choice and a minutes-sourced points fallback, since none
 * can work without the minutes value. Stored minutes DATA is never
 * deleted — only the feature flags change.
 */


/**
 * Toggles the per-habit minutes value on/off.
 *
 * Refuses to change habits whose minutes state is forced: max-1 habits
 * (never minutes) and timer-widget/media/movie-connected habits (always
 * minutes). Turning minutes OFF also clears the minutes-primary role,
 * its fallback choice and a minutes-sourced points fallback, since none
 * can work without the minutes value. Stored minutes DATA is never
 * deleted — only the feature flags change.
 */
fun HabitViewModel.toggleMinutesEnabled(habitName: String) {
    viewModelScope.launch {
        val s = _settings.value
        if (habitName in s.maxOneHabits) return@launch
        if (isMinutesForcedByWidget(habitName)) return@launch
        val enabling = habitName !in s.minutesEnabledHabits
        val minutes = if (enabling) s.minutesEnabledHabits + habitName else s.minutesEnabledHabits - habitName
        var primary = s.widgetTimerMinutesPrimary
        var fallback = s.secondaryValueFallbackHabits
        var mpFallbacks = s.minutesPrimaryFallbacks
        if (!enabling) {
            if (habitName in primary) primary = primary - habitName
            if (habitName in mpFallbacks) mpFallbacks = mpFallbacks - habitName
            // Only drop the fallback flag when its source is the minutes
            // slot; a legacy secondary_value: fallback keeps working.
            val legacyFallbackSource = habitName in s.secondaryValueHabits ||
                !cachedPhoneDb[secondaryValueKey(habitName)].isNullOrEmpty()
            if (habitName in fallback && !legacyFallbackSource) fallback = fallback - habitName
        }
        settingsRepo.saveMinutesEnabledHabits(minutes)
        if (primary != s.widgetTimerMinutesPrimary) {
            settingsRepo.saveWidgetTimerMinutesPrimary(primary)
        }
        if (fallback != s.secondaryValueFallbackHabits) {
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
        }
        if (mpFallbacks != s.minutesPrimaryFallbacks) {
            settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
        }
        _settings.value = s.copy(
            minutesEnabledHabits = minutes,
            widgetTimerMinutesPrimary = primary,
            secondaryValueFallbackHabits = fallback,
            minutesPrimaryFallbacks = mpFallbacks
        )
        rebuildHabitList()
    }
}

/** True when minutes (not sessions) is the habit's primary value. */


/** True when minutes (not sessions) is the habit's primary value. */
fun HabitViewModel.isMinutesPrimaryHabit(habitName: String): Boolean =
    habitName in _settings.value.widgetTimerMinutesPrimary

/**
 * Resolves the user-facing display label for a habit's value key,
 * honouring custom labels ([AppSettings.valueDisplayLabels]).
 */


/**
 * Resolves the user-facing display label for a habit's value key,
 * honouring custom labels ([AppSettings.valueDisplayLabels]).
 */
fun HabitViewModel.valueDisplayLabel(habitName: String, valueKey: String): String =
    com.example.tail.data.displayLabelForValue(
        habitName, valueKey, _settings.value.valueDisplayLabels
    )

/** The user's CUSTOM label for a value key, or null when none is set. */


/** The user's CUSTOM label for a value key, or null when none is set. */
fun HabitViewModel.customValueLabel(habitName: String, valueKey: String): String? =
    _settings.value.valueDisplayLabels[habitName]?.get(valueKey)?.takeIf { it.isNotBlank() }

/** True when the habit has the "1 max" daily cap enabled. */


/** True when the habit has the "1 max" daily cap enabled. */
fun HabitViewModel.isMaxOneHabit(habitName: String): Boolean =
    habitName in _settings.value.maxOneHabits

/**
 * Moves the currently selected habit to [targetScreenIndex].
 * Removes it from its current screen and appends it to the target screen.
 * Clears the selection after moving.
 */


/**
 * Toggles whether [habitName] appears on the day timeline (the
 * retrospective hour-by-hour view). Excluded habits are stored in a
 * set; every habit is shown by default.
 */
fun HabitViewModel.toggleTimelineExcluded(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.timelineExcludedHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
        }
        settingsRepo.saveTimelineExcludedHabits(current)
        _settings.value = _settings.value.copy(timelineExcludedHabits = current)
    }
}

/**
 * Toggles the "Camera" type on/off for a specific habit. Camera-enabled
 * habits are the only ones offered to the LLM as choices when a photo is
 * captured (see [com.example.tail.data.meal.VisionHabitExecutor]).
 */


/**
 * Toggles custom point ranges on/off for [habitName].
 * When enabled, the habit's points are calculated based on which range
 * the "true value" or "garmin value" falls into.
 */
fun HabitViewModel.toggleCustomPointRanges(habitName: String) {
    val current = _settings.value.customPointRangesHabits.toMutableSet()
    if (habitName in current) {
        current.remove(habitName)
        val ranges = _settings.value.customPointRanges.toMutableMap()
        ranges.remove(habitName)
        _settings.value = _settings.value.copy(
            customPointRangesHabits = current,
            customPointRanges = ranges
        )
        viewModelScope.launch {
            settingsRepo.saveCustomPointRangesHabits(current)
            settingsRepo.saveCustomPointRanges(ranges)
        }
    } else {
        current.add(habitName)
        val ranges = _settings.value.customPointRanges.toMutableMap()
        if (habitName !in ranges) {
            ranges[habitName] = List(7) { com.example.tail.data.PointRange() }
        }
        _settings.value = _settings.value.copy(
            customPointRangesHabits = current,
            customPointRanges = ranges
        )
        viewModelScope.launch {
            settingsRepo.saveCustomPointRangesHabits(current)
            settingsRepo.saveCustomPointRanges(ranges)
            recalculateHabitPointsForCustomRanges(habitName)
        }
    }
}

/**
 * Sets the custom point ranges for [habitName].
 * When ranges change, all historical entries for the habit are recalculated.
 */


/**
 * Sets the custom point ranges for [habitName].
 * When ranges change, all historical entries for the habit are recalculated.
 */
fun HabitViewModel.setCustomPointRanges(habitName: String, ranges: List<com.example.tail.data.PointRange>) {
    val rangesMap = _settings.value.customPointRanges.toMutableMap()
    rangesMap[habitName] = ranges
    _settings.value = _settings.value.copy(customPointRanges = rangesMap)
    viewModelScope.launch {
        settingsRepo.saveCustomPointRanges(rangesMap)
        // Ensure the habit is in the customPointRangesHabits set
        val currentHabits = _settings.value.customPointRangesHabits.toMutableSet()
        if (habitName !in currentHabits) {
            currentHabits.add(habitName)
            _settings.value = _settings.value.copy(customPointRangesHabits = currentHabits)
            settingsRepo.saveCustomPointRangesHabits(currentHabits)
        }
        recalculateHabitPointsForCustomRanges(habitName)
    }
}

/**
 * Recalculates all historical entries for [habitName] based on custom point ranges.
 * This is called when custom point ranges are enabled or modified.
 */


/**
 * Recalculates all historical entries for [habitName] based on custom point ranges.
 * This is called when custom point ranges are enabled or modified.
 */
internal suspend fun HabitViewModel.recalculateHabitPointsForCustomRanges(habitName: String) {
    val settings = _settings.value
    if (habitName !in settings.customPointRangesHabits) return

    val ranges = settings.customPointRanges[habitName] ?: return
    val uri = settings.fileUri
    if (uri.isEmpty()) return

    val loadResult = habitsRepo.loadDatabaseResult(
        android.net.Uri.parse(uri),
        context
    )
    if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return

    val db = loadResult.db.toMutableMap()
    val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
    if (habitEntries.isEmpty()) return

    val isGarminLinked = habitName in settings.garminHabitLinks
    val isDivider = (settings.habitDividers[habitName] ?: 1) > 1

    for ((dateStr, rawCount) in habitEntries) {
        val trueValue: Int = when {
            isGarminLinked -> {
                val garminType = com.example.tail.data.GarminType.fromKey(settings.garminHabitLinks[habitName]!!)
                val monthlyData = _garminMonthlyData.value[garminType]
                monthlyData?.get(dateStr) ?: rawCount
            }
            isDivider -> rawCount
            else -> rawCount
        }

        val newPoints = com.example.tail.data.calculatePointsFromRanges(trueValue, ranges)
        habitEntries[dateStr] = newPoints
    }

    db[habitName] = habitEntries.toSortedMap()

    habitsRepo.saveDatabase(
        android.net.Uri.parse(uri),
        context,
        db
    )

    cachedPhoneDb = db
    rebuildHabitList()
}

// ════════════════════════════════════════════════════════════════════════
//  Meal Habit Engine Methods
// ════════════════════════════════════════════════════════════════════════

/** Saves all meal engine settings at once (called from Settings screen). */


/**
 * Sets the long-press action for a habit.
 * Pass [com.example.tail.data.LONG_PRESS_APP] to reset to default behaviour
 * (which removes the entry so the default kicks in).
 */
fun HabitViewModel.setHabitLongPressAction(habitName: String, action: String) {
    viewModelScope.launch {
        val current = _settings.value.habitLongPressActions.toMutableMap()
        if (action == com.example.tail.data.LONG_PRESS_APP) {
            current.remove(habitName)
        } else {
            current[habitName] = action
        }
        settingsRepo.saveHabitLongPressActions(current)
        _settings.value = _settings.value.copy(habitLongPressActions = current)
    }
}

/**
 * Sets the URI opened when long-pressing a habit whose action is
 * [com.example.tail.data.LONG_PRESS_URL]. Passing a blank [url]
 * removes the entry (long-press then falls back to app behaviour).
 *
 * The value is normalized via [com.example.tail.data.normalizeLongPressUri]:
 * any URI scheme is preserved (obsidian://, tel:, spotify:// …), bare
 * domains get an https:// prefix, and pasted-but-unencoded characters
 * (spaces in vault/file names etc.) are percent-encoded.
 */


/**
 * Sets the URI opened when long-pressing a habit whose action is
 * [com.example.tail.data.LONG_PRESS_URL]. Passing a blank [url]
 * removes the entry (long-press then falls back to app behaviour).
 *
 * The value is normalized via [com.example.tail.data.normalizeLongPressUri]:
 * any URI scheme is preserved (obsidian://, tel:, spotify:// …), bare
 * domains get an https:// prefix, and pasted-but-unencoded characters
 * (spaces in vault/file names etc.) are percent-encoded.
 */
fun HabitViewModel.setHabitLongPressUrl(habitName: String, url: String) {
    viewModelScope.launch {
        val current = _settings.value.habitLongPressUrls.toMutableMap()
        if (url.isBlank()) {
            current.remove(habitName)
        } else {
            current[habitName] = com.example.tail.data.normalizeLongPressUri(url)
        }
        settingsRepo.saveHabitLongPressUrls(current)
        _settings.value = _settings.value.copy(habitLongPressUrls = current)
    }
}

/**
 * Sets the app that should handle the long-press URL for a habit
 * (via Intent.setPackage). Pass a null/blank [packageName] to clear it,
 * which makes the URL open in the default browser again.
 */


/**
 * Sets the app that should handle the long-press URL for a habit
 * (via Intent.setPackage). Pass a null/blank [packageName] to clear it,
 * which makes the URL open in the default browser again.
 */
fun HabitViewModel.setHabitLongPressUrlApp(habitName: String, packageName: String?) {
    viewModelScope.launch {
        val current = _settings.value.habitLongPressUrlApps.toMutableMap()
        if (packageName.isNullOrBlank()) {
            current.remove(habitName)
        } else {
            current[habitName] = packageName
        }
        settingsRepo.saveHabitLongPressUrlApps(current)
        _settings.value = _settings.value.copy(habitLongPressUrlApps = current)
    }
}

/** Loads meal logs for a habit and updates the StateFlows. */


/**
 * Sets or clears the custom icon for [habitName].
 * [iconName] is the drawable resource name without extension (e.g. "bicycle"),
 * or null to clear the override and revert to the default icon.
 */
fun HabitViewModel.setHabitIcon(habitName: String, iconName: String?) {
    viewModelScope.launch {
        val current = _settings.value.habitIcons.toMutableMap()
        if (iconName == null) {
            current.remove(habitName)
        } else {
            current[habitName] = iconName
        }
        settingsRepo.saveHabitIcons(current)
        _settings.value = _settings.value.copy(habitIcons = current)
        // Choosing an installed-app icon implies the user wants that app
        // tied to the habit: auto-create the app association so long-press
        // launches it. Idempotent (no-op when already associated); the
        // user can remove it manually in edit mode's app settings.
        appPackageNameOf(iconName)?.let { pkg ->
            addHabitAppAssociation(habitName, pkg)
        }
        // Sync icon change to relay file so PC widget picks it up
        val relayUri = _settings.value.screensRelayFileUri
        if (relayUri.isNotEmpty()) {
            writeScreensRelayFile(_habitScreens.value, _activeScreenIndex.value, relayUri)
        }
        // The PC floating-widget config embeds icon overrides — refresh it
        // when an icon changes for a habit shown on the PC widget.
        if (habitName in _settings.value.pcWidgetHabits) {
            pushPcWidgetConfig()
        }
    }
}

/**
 * Sets or clears the note for [habitName].
 * [note] is the note text, or empty string to clear the note.
 */


/**
 * Sets or clears the note for [habitName].
 * [note] is the note text, or empty string to clear the note.
 */
fun HabitViewModel.setHabitNote(habitName: String, note: String) {
    viewModelScope.launch {
        val current = _settings.value.habitNotes.toMutableMap()
        if (note.isEmpty()) {
            current.remove(habitName)
        } else {
            current[habitName] = note
        }
        settingsRepo.saveHabitNotes(current)
        _settings.value = _settings.value.copy(habitNotes = current)
    }
}

// ── AI Icon Generation methods ───────────────────────────────────────────

/** Available models fetched from the API (or fallback). */


/** Returns true if [habitName] has the "Meal" type enabled. */
fun HabitViewModel.isMealHabit(habitName: String): Boolean {
    return habitName in _settings.value.mealHabits
}

/** Returns true if [habitName] has the "Weights" type enabled. */


/** Returns true if [habitName] has the "Weights" type enabled. */
fun HabitViewModel.isWeightsHabit(habitName: String): Boolean {
    return habitName in _settings.value.weightsHabits
}

/**
 * Returns the list of selectable graph metrics for [habitName], depending
 * on its type. All habits get Points + Value1. Secondary-value habits also
 * get Value2. Meal habits additionally get Calories, Protein, Carbs, Fat.
 */


/** Returns true if [habitName] has the secondary value feature enabled. */
fun HabitViewModel.hasSecondaryValue(habitName: String): Boolean {
    return habitName in _settings.value.secondaryValueHabits
}


/** Returns true if [habitName] is linked to a GitHub repository. */
fun HabitViewModel.isGithubHabit(habitName: String): Boolean {
    return habitName in _settings.value.githubRepoUrls
}

// ── Garmin Integration ────────────────────────────────────────────────────
