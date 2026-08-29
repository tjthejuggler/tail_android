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

/**
 * Re-attempts fetching today's location (called after the user grants permission).
 * No-op if the location is already stored for today.
 */
fun HabitViewModel.refreshTodayLocation() {
    viewModelScope.launch {
        val result = locationRepo.fetchTodayIfNeeded()
        if (result != null && _selectedDate.value == LocalDate.now()) {
            _selectedDateLocation.value = result
        }
    }
}

/**
 * Manually saves a location label for the given date and refreshes the displayed value.
 *
 * Always forward-geocodes the new label so the world-map marker is updated to
 * match the new location, even if coords were already stored for this date
 * (e.g. the user corrected a previously wrong entry).
 */


/**
 * Manually saves a location label for the given date and refreshes the displayed value.
 *
 * Always forward-geocodes the new label so the world-map marker is updated to
 * match the new location, even if coords were already stored for this date
 * (e.g. the user corrected a previously wrong entry).
 */
fun HabitViewModel.setLocationForDate(date: java.time.LocalDate, label: String) {
    locationRepo.setLocationForDate(date, label)
    if (_selectedDate.value == date) {
        _selectedDateLocation.value = label
    }
    // Always re-geocode on a manual edit so the map marker reflects the new label
    viewModelScope.launch {
        val coords = locationRepo.geocodeLocationLabel(label)
        if (coords != null) {
            locationRepo.setCoordsForDate(date, coords.first, coords.second)
        }
    }
}

/**
 * Removes the location label and coords for the given date, making it act
 * as if a location was never manually set. The day will then be assumed
 * to be at whatever the previous known location was.
 */


/**
 * Removes the location label and coords for the given date, making it act
 * as if a location was never manually set. The day will then be assumed
 * to be at whatever the previous known location was.
 */
fun HabitViewModel.removeLocationForDate(date: java.time.LocalDate) {
    locationRepo.removeLocationForDate(date)
    if (_selectedDate.value == date) {
        // Re-derive the location from the previous known day
        _selectedDateLocation.value = locationRepo.getLocationForDate(date)
    }
}

/**
 * Fetches a fresh GPS/network fix, reverse-geocodes it, and saves both
 * the label and coords for [date]. Calls [onComplete] when finished
 * (success or failure) so the caller can dismiss loading spinners.
 * Used by the "Auto Set" button in the location edit dialog.
 */


/**
 * Fetches a fresh GPS/network fix, reverse-geocodes it, and saves both
 * the label and coords for [date]. Calls [onComplete] when finished
 * (success or failure) so the caller can dismiss loading spinners.
 * Used by the "Auto Set" button in the location edit dialog.
 */
fun HabitViewModel.fetchFreshLocationForDate(date: java.time.LocalDate, onComplete: () -> Unit) {
    viewModelScope.launch {
        try {
            val label = locationRepo.fetchFreshLocationForDate(date)
            if (label != null && _selectedDate.value == date) {
                _selectedDateLocation.value = label
            }
        } catch (_: Exception) { /* already logged by repo */ }
        onComplete()
    }
}

/**
 * Generates multiple candidate location names for [date] by using the
 * stored coords (or fetching fresh ones if none exist). Returns a list
 * of candidate labels ordered from most specific to least specific.
 * The GPS coords are NOT changed — only the display label varies.
 * Used by the cycling "auto" button in the location edit dialog.
 */


/**
 * Generates multiple candidate location names for [date] by using the
 * stored coords (or fetching fresh ones if none exist). Returns a list
 * of candidate labels ordered from most specific to least specific.
 * The GPS coords are NOT changed — only the display label varies.
 * Used by the cycling "auto" button in the location edit dialog.
 */
fun HabitViewModel.fetchLocationCandidates(date: java.time.LocalDate, onResult: (List<String>) -> Unit) {
    viewModelScope.launch {
        val candidates = try {
            // Use existing coords if available, otherwise fetch fresh
            var coords = locationRepo.getCoordsForDate(date)
            if (coords == null) {
                // Fetch fresh location (this also saves coords + label)
                val label = locationRepo.fetchFreshLocationForDate(date)
                if (label != null && _selectedDate.value == date) {
                    _selectedDateLocation.value = label
                }
                coords = locationRepo.getCoordsForDate(date)
            }
            if (coords != null) {
                locationRepo.generateLocationCandidates(coords.first, coords.second)
            } else {
                emptyList()
            }
        } catch (_: Exception) { /* already logged by repo */ emptyList() }
        onResult(candidates)
    }
}

/** Saves the user's preferred auto-detected location candidate. */
/**
 * Saves the index of the candidate the user chose from the auto list.
 * On the next day, [fetchTodayIfNeeded] will re-run candidate generation
 * with fresh GPS data and pick the same positional slot.
 */


/** Saves the user's preferred auto-detected location candidate. */
/**
 * Saves the index of the candidate the user chose from the auto list.
 * On the next day, [fetchTodayIfNeeded] will re-run candidate generation
 * with fresh GPS data and pick the same positional slot.
 */
fun HabitViewModel.savePreferredAutoCandidateIndex(index: Int) {
    locationRepo.savePreferredAutoCandidateIndex(index)
}

/** Returns all previously stored location labels (for the edit dialog suggestions). */


/** Returns all previously stored location labels (for the edit dialog suggestions). */
fun HabitViewModel.getAllStoredLocations(): List<String> = locationRepo.getAllStoredLocations()

/** Returns the full date-string → label map in one SharedPrefs read. */


/** Returns the full date-string → label map in one SharedPrefs read. */
fun HabitViewModel.getAllStoredLabels(): Map<String, String> = locationRepo.getAllStoredLabels()

// ── World-map screen helpers ─────────────────────────────────────────────

/** Returns (lat, lon) for [date] if known, else null. */


/** Returns (lat, lon) for [date] if known, else null. */
fun HabitViewModel.getCoordsForDate(date: LocalDate): Pair<Double, Double>? =
    locationRepo.getCoordsForDate(date)

/** Manually sets (lat, lon) for [date]. Used for coordinate editing from the map. */


/** Manually sets (lat, lon) for [date]. Used for coordinate editing from the map. */
fun HabitViewModel.setCoordsForDate(date: LocalDate, lat: Double, lon: Double) =
    locationRepo.setCoordsForDate(date, lat, lon)

/** Returns the location label stored for [date] (or null). */


/** Returns the location label stored for [date] (or null). */
fun HabitViewModel.getLocationLabelForDate(date: LocalDate): String? =
    locationRepo.getLocationForDate(date)

/**
 * Returns the assumed location label for [date] — the most recent
 * preceding stored label when no exact entry exists for [date].
 * Returns null only if no preceding labels exist at all.
 *
 * Only looks at dates strictly before [date], so setting a location
 * for one day never changes the assumed location for earlier days.
 */


/**
 * Returns the assumed location label for [date] — the most recent
 * preceding stored label when no exact entry exists for [date].
 * Returns null only if no preceding labels exist at all.
 *
 * Only looks at dates strictly before [date], so setting a location
 * for one day never changes the assumed location for earlier days.
 */
fun HabitViewModel.getAssumedLocationForDate(date: LocalDate): String? {
    val allLabels = locationRepo.getAllStoredLabels()
    return allLabels.entries
        .mapNotNull { (k, v) ->
            runCatching { LocalDate.parse(k) }.getOrNull()?.let { it to v }
        }
        .filter { (d, _) -> d.isBefore(date) }
        .maxByOrNull { (d, _) -> d }
        ?.second
}

/** Returns all date-strings for which we have plottable coords, sorted ascending. */


/** Returns all date-strings for which we have plottable coords, sorted ascending. */
fun HabitViewModel.getDatesWithCoords(): List<String> =
    locationRepo.getAllStoredCoords().keys.sorted()

/**
 * Returns ALL stored coords as a map of [LocalDate] → (lat, lon) in ONE
 * SharedPrefs read + ONE JSON parse pass. Used by the world-map screen so
 * we don't pay per-date parse cost (which would freeze the UI thread for
 * thousands of entries).
 */


/**
 * Returns ALL stored coords as a map of [LocalDate] → (lat, lon) in ONE
 * SharedPrefs read + ONE JSON parse pass. Used by the world-map screen so
 * we don't pay per-date parse cost (which would freeze the UI thread for
 * thousands of entries).
 */
fun HabitViewModel.getAllStoredCoordsParsed(): Map<LocalDate, Pair<Double, Double>> {
    val raw = locationRepo.getAllStoredCoords()
    val out = HashMap<LocalDate, Pair<Double, Double>>(raw.size)
    for ((dateStr, coord) in raw) {
        val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
        out[d] = coord
    }
    return out
}

/**
 * Returns ALL stored location labels as a map of [LocalDate] → label in
 * ONE SharedPrefs read + parse pass (mirrors [getAllStoredCoordsParsed]).
 * Used by the travel-stats screen for city/country aggregation.
 */


/**
 * Returns ALL stored location labels as a map of [LocalDate] → label in
 * ONE SharedPrefs read + parse pass (mirrors [getAllStoredCoordsParsed]).
 * Used by the travel-stats screen for city/country aggregation.
 */
fun HabitViewModel.getAllStoredLabelsParsed(): Map<LocalDate, String> {
    val raw = locationRepo.getAllStoredLabels()
    val out = HashMap<LocalDate, String>(raw.size)
    for ((dateStr, label) in raw) {
        val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
        out[d] = label
    }
    return out
}

/**
 * One-shot, off-thread snapshot of (date, country) pairs for every stored
 * location label, sorted ascending by date. Used by the world-map screen
 * to compute "countries visited up to date X" in O(N) without re-parsing
 * SharedPrefs on every slider tick.
 *
 * Country names that match an entry in the user-managed ignore list
 * (seeded with US states on first run) are excluded from the count.
 *
 * Pair caller with [locationDataVersion] to know when to rebuild this
 * snapshot (the value bumps whenever a label or coords entry is saved).
 */


/**
 * One-shot, off-thread snapshot of (date, country) pairs for every stored
 * location label, sorted ascending by date. Used by the world-map screen
 * to compute "countries visited up to date X" in O(N) without re-parsing
 * SharedPrefs on every slider tick.
 *
 * Country names that match an entry in the user-managed ignore list
 * (seeded with US states on first run) are excluded from the count.
 *
 * Pair caller with [locationDataVersion] to know when to rebuild this
 * snapshot (the value bumps whenever a label or coords entry is saved).
 */
fun HabitViewModel.buildCountryTimeline(): List<Pair<LocalDate, String>> {
    val labels = locationRepo.getAllStoredLabels()  // single SharedPrefs read
    val ignored = locationRepo.getIgnoredCountryNames()
    val out = ArrayList<Pair<LocalDate, String>>(labels.size)
    for ((dateStr, label) in labels) {
        val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
        val country = extractCountry(label, ignored) ?: continue
        out.add(d to country)
    }
    out.sortBy { it.first }
    return out
}

/** Returns the current set of country/region names excluded from the country count. */


/** Returns the current set of country/region names excluded from the country count. */
fun HabitViewModel.getIgnoredCountryNames(): Set<String> = locationRepo.getIgnoredCountryNames()

/** Adds [name] to the ignored-country set (persisted). Bumps locationDataVersion. */


/** Adds [name] to the ignored-country set (persisted). Bumps locationDataVersion. */
fun HabitViewModel.addIgnoredCountryName(name: String) = locationRepo.addIgnoredCountryName(name)

/** Removes [name] from the ignored-country set (persisted). Bumps locationDataVersion. */


/** Removes [name] from the ignored-country set (persisted). Bumps locationDataVersion. */
fun HabitViewModel.removeIgnoredCountryName(name: String) = locationRepo.removeIgnoredCountryName(name)

/**
 * Current data version of the location store. Bumped on every save.
 * The map screen recomputes its country cache when this changes.
 */


/** Returns secondary locations for a specific date. */
fun HabitViewModel.getSecondaryLocationsForDate(date: LocalDate): List<SecondaryLocation> =
    locationRepo.getSecondaryLocationsForDate(date)

/** Returns ALL secondary locations as a map of date-string → list. */


/** Returns ALL secondary locations as a map of date-string → list. */
fun HabitViewModel.getAllSecondaryLocations(): Map<String, List<SecondaryLocation>> =
    locationRepo.getAllSecondaryLocations()

/**
 * Logs the current GPS position as a secondary location for today.
 * Called when the app is foregrounded. Silently no-ops if location
 * permission is not granted or the label duplicates an existing entry.
 */


/**
 * Logs the current GPS position as a secondary location for today.
 * Called when the app is foregrounded. Silently no-ops if location
 * permission is not granted or the label duplicates an existing entry.
 */
fun HabitViewModel.logSecondaryLocationOnForeground() {
    viewModelScope.launch {
        try {
            locationRepo.logCurrentPositionAsSecondary()
        } catch (e: Exception) {
            Log.w(TAG, "logSecondaryLocationOnForeground failed: ${e.message}")
        }
    }
}

/**
 * Manually adds a secondary location for [date] by forward-geocoding
 * a pasted address (e.g. from Google Maps). Returns the resolved label
 * on success, or null on failure. Runs on Dispatchers.IO.
 */


/**
 * Manually adds a secondary location for [date] by forward-geocoding
 * a pasted address (e.g. from Google Maps). Returns the resolved label
 * on success, or null on failure. Runs on Dispatchers.IO.
 */
suspend fun HabitViewModel.addManualSecondaryLocation(date: LocalDate, address: String, timeMinutes: Int = java.time.LocalTime.now().toSecondOfDay() / 60): String? {
    return withContext(Dispatchers.IO) {
        try {
            locationRepo.addManualSecondaryLocation(date, address, timeMinutes)
        } catch (e: Exception) {
            Log.w(TAG, "addManualSecondaryLocation failed: ${e.message}")
            null
        }
    }
}


fun HabitViewModel.removeSecondaryLocation(date: LocalDate, index: Int) {
    locationRepo.removeSecondaryLocation(date, index)
}


fun HabitViewModel.updateSecondaryLocationTime(date: LocalDate, index: Int, newTimeMinutes: Int) {
    locationRepo.updateSecondaryLocationTime(date, index, newTimeMinutes)
}

/**
 * Returns the list of habits done on [date] with their point values,
 * sorted descending by points. Only habits with points > 0 are included.
 */


/**
 * Returns the list of habits done on [date] with their point values,
 * sorted descending by points. Only habits with points > 0 are included.
 */
fun HabitViewModel.getDayHabitBreakdown(date: LocalDate): List<Pair<String, Int>> {
    val db = cachedPhoneDb
    val dateStr = dateString(date)
    val tracked = trackedHabitNames().ifEmpty { db.keys }
    return tracked
        .mapNotNull { name ->
            val raw = db[name]?.get(dateStr) ?: 0
            val pts = effectivePointsForDate(name, raw, dateStr)
            if (pts > 0) Pair(name, pts) else null
        }
        .sortedByDescending { it.second }
}

/**
 * Returns the earliest date for which a location label is stored.
 * Used by the calendar picker to set the minimum selectable year.
 */


/**
 * Returns the earliest date for which a location label is stored.
 * Used by the calendar picker to set the minimum selectable year.
 */
fun HabitViewModel.getEarliestLocationDate(): LocalDate? {
    val allCoords = locationRepo.getAllStoredCoords()
    return allCoords.keys
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
        .minOrNull()
}

/**
 * Fast (cheap) stats for [date]: only total points and 30-day monthly average.
 * No streak computation. Used on every slider tick for accent colour updates.
 */
