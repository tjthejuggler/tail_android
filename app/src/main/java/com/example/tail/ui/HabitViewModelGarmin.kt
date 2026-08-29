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

/** Stops the Garmin polling loop. */
internal fun HabitViewModel.stopGarminPolling() {
    garminPollingJob?.cancel()
    garminPollingJob = null
}

/**
 * Fetches current month Garmin data and applies increments to linked habits.
 * Called periodically by the polling loop.
 */


/**
 * Fetches current month Garmin data and applies increments to linked habits.
 * Called periodically by the polling loop.
 */
internal suspend fun HabitViewModel.syncGarminCurrentMonth() {
    val s = _settings.value
    if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) return
    if (s.garminHabitLinks.isEmpty()) return
    if (s.fileUri.isEmpty()) return

    try {
        _garminSyncStatus.value = "Syncing Garmin data…"
        val monthData = garminRepo.fetchCurrentMonthData(s.garminProxyUrl, s.garminAppToken, s.garminDateOfBirth)
        val today = LocalDate.now().toString()
        Log.d(TAG, "Garmin sync: fetched types=${monthData.keys}, " +
            "links=${s.garminHabitLinks}, " +
            "todayValues=" + monthData.mapValues { it.value[today] })
        // Persist the freshly-fetched recent days to cache so they survive
        // restarts. The proxy/fetch pipeline is the source of truth for recent
        // days, so these values overwrite any stale cached value for the same date.
        withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(monthData) }
        // MERGE into the displayed map — never REPLACE. Replacing here was the
        // bug that wiped historic "garmin value" fields down to the last 7 days.
        mergeIntoGarminMonthlyData(monthData)
        applyGarminData(monthData, s)
        _garminSyncStatus.value = "Last sync: ${java.time.LocalTime.now().toString().take(5)}"
    } catch (e: Exception) {
        Log.e(TAG, "Garmin sync failed: ${e.message}", e)
        _garminSyncStatus.value = "Sync failed: ${e.message?.take(50)}"
    }
}

/**
 * Merges freshly-fetched Garmin data into the displayed [_garminMonthlyData]
 * StateFlow WITHOUT discarding the historic backlog.
 *
 * This is the fix for the "all garmin values show '-'" regression: the 7-day
 * poll used to do `_garminMonthlyData.value = monthData`, which replaced the
 * full 5-year map with just the last week, so every older date rendered '-'.
 * Fresh values win for the dates they cover; every other date is preserved.
 */


/**
 * Merges freshly-fetched Garmin data into the displayed [_garminMonthlyData]
 * StateFlow WITHOUT discarding the historic backlog.
 *
 * This is the fix for the "all garmin values show '-'" regression: the 7-day
 * poll used to do `_garminMonthlyData.value = monthData`, which replaced the
 * full 5-year map with just the last week, so every older date rendered '-'.
 * Fresh values win for the dates they cover; every other date is preserved.
 */
internal fun HabitViewModel.mergeIntoGarminMonthlyData(fresh: Map<GarminType, Map<String, Int>>) {
    if (fresh.isEmpty()) return
    val merged = _garminMonthlyData.value.mapValues { it.value.toMutableMap() }.toMutableMap()
    for ((type, dayMap) in fresh) {
        val target = merged.getOrPut(type) { mutableMapOf() }
        for ((date, value) in dayMap) {
            target[date] = value
        }
    }
    _garminMonthlyData.value = merged.mapValues { it.value.toMap() }
}

/**
 * Fetches the entire Garmin health history and retroactively fills habit data.
 * Called from the Settings screen "Fetch Entire Backlog" button.
 */


/**
 * Fetches the entire Garmin health history and retroactively fills habit data.
 * Called from the Settings screen "Fetch Entire Backlog" button.
 */
fun HabitViewModel.fetchGarminBacklog() {
    val s = _settings.value
    if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) {
        _garminSyncStatus.value = "Enable Garmin and set connection settings first"
        return
    }
    if (s.fileUri.isEmpty()) {
        _garminSyncStatus.value = "Set habit database file first"
        return
    }

    viewModelScope.launch {
        try {
            // Reset all Garmin-linked habits to 0 for all dates so that
            // stale proxy values are cleared before re-applying.
            _garminSyncStatus.value = "Resetting linked habit data…"
            resetGarminHabitData(s)

            _garminSyncStatus.value = "Fetching entire backlog…"
            val allData = garminRepo.fetchEntireBacklog(
                s.garminProxyUrl,
                s.garminAppToken,
                s.garminDateOfBirth
            ) { done, total ->
                _garminSyncStatus.value = "Fetching archives: $done / $total months"
            }

            // Merge proxy data into the persistent cache WITHOUT clearing it.
            // This preserves historic data from JSON import (e.g. swim activities
            // from months ago) that the proxy may not have. Proxy values win
            // for dates they cover; all other cached dates are preserved.
            _garminSyncStatus.value = "Merging with cached data…"
            withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(allData) }

            // Load the merged cache (proxy + historic import) and apply to habits.
            _garminSyncStatus.value = "Applying backlog data to habits…"
            val mergedData = withContext(Dispatchers.IO) { garminRepo.loadAllCachedData() }
            mergeIntoGarminMonthlyData(mergedData)
            val updatedSettings = autoLinkMissingGarminHabits(mergedData)
            applyGarminData(mergedData, updatedSettings)
            _garminSyncStatus.value = "Backlog sync complete!"
        } catch (e: Exception) {
            Log.e(TAG, "Garmin backlog fetch failed: ${e.message}")
            _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
        }
    }
}

/**
 * Resets all Garmin-linked habits to 0 for all dates in the cached database.
 * Called before fetching backlog to avoid double-counting.
 */


/**
 * Resets all Garmin-linked habits to 0 for all dates in the cached database.
 * Called before fetching backlog to avoid double-counting.
 */
internal suspend fun HabitViewModel.resetGarminHabitData(settings: AppSettings) {
    val linkedHabits = settings.garminHabitLinks.keys
    if (linkedHabits.isEmpty()) return
    if (!dbLoaded) {
        Log.w(TAG, "resetGarminHabitData: DB not loaded yet, refusing to persist (anti-wipe gate)")
        return
    }

    val mutableDb = cachedPhoneDb.toMutableMap()
    var dbChanged = false

    for (habitName in linkedHabits) {
        if (habitName !in mutableDb) continue
        val habitData = mutableDb[habitName]!!.toMutableMap()
        for (date in habitData.keys) {
            habitData[date] = 0
            dbChanged = true
        }
        mutableDb[habitName] = habitData
    }

    if (dbChanged) {
        cachedPhoneDb = mutableDb
        rebuildHabitList()

        // Persist to disk
        withContext(Dispatchers.IO) {
            habitsRepo.persistDatabase(Uri.parse(settings.fileUri), context, mutableDb)
        }
    }
}

/**
 * Tests the Garmin connection by performing a comprehensive health check.
 * Validates the full chain: proxy server, app token, Garmin API, and data availability.
 *
 * On success, fetches the entire proxy backlog (whatever the laptop has cached)
 * and merges it into the displayed data, with laptop values winning for the dates
 * they cover. This makes "Test Connection" the authoritative "sync from laptop"
 * button — historic JSON data is preserved, but recent days are refreshed from
 * the proxy/fetch pipeline.
 */


/**
 * Tests the Garmin connection by performing a comprehensive health check.
 * Validates the full chain: proxy server, app token, Garmin API, and data availability.
 *
 * On success, fetches the entire proxy backlog (whatever the laptop has cached)
 * and merges it into the displayed data, with laptop values winning for the dates
 * they cover. This makes "Test Connection" the authoritative "sync from laptop"
 * button — historic JSON data is preserved, but recent days are refreshed from
 * the proxy/fetch pipeline.
 */
fun HabitViewModel.testGarminConnection() {
    val s = _settings.value
    if (s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) {
        _garminSyncStatus.value = "Set proxy URL and app token first"
        return
    }

    viewModelScope.launch {
        try {
            _garminSyncStatus.value = "Testing connection…"
            val result = garminRepo.performHealthCheck(s.garminProxyUrl, s.garminAppToken)

            if (result.success) {
                val message = buildString {
                    append("✓ Connection successful!\n")
                    append("  Proxy: ${if (result.proxyRunning) "Running" else "Not running"}\n")
                    append("  Garmin: ${if (result.garminConnected) "Connected" else "Not connected"}\n")
                    if (result.dataAvailable) {
                        append("  Data: Available")
                    }
                }
                _garminSyncStatus.value = message

                // After a successful test, fetch the full proxy backlog and merge it.
                // This is the "sync from laptop" path: historic JSON data stays intact,
                // but any dates the laptop has (recent days, corrected values) overwrite.
                Log.d(TAG, "Garmin test ok: enabled=${s.garminEnabled}, " +
                    "links=${s.garminHabitLinks.size}, fileUriSet=${s.fileUri.isNotEmpty()}")
                if (s.garminEnabled && s.garminHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()) {
                    syncGarminBacklog()
                } else {
                    Log.w(TAG, "Garmin sync skipped after test — guard not satisfied")
                }
            } else {
                _garminSyncStatus.value = "✗ Connection failed: ${result.message}"
            }
        } catch (e: Exception) {
            _garminSyncStatus.value = "✗ Error: ${e.message}"
        }
    }
}

/**
 * Fetches the entire proxy backlog (whatever the laptop has cached) and merges it
 * into the displayed data and cache, WITHOUT clearing the historic JSON data.
 *
 * This is the "sync from laptop" path used by "Test Connection". The laptop's
 * values win for the dates they cover; all other dates (deep historic past from
 * JSON) are preserved. This is distinct from `fetchGarminBacklog()` which does
 * a full clear+fetch from the proxy (useful when you want to discard everything
 * and re-fetch from Garmin's API).
 */


/**
 * Fetches the entire proxy backlog (whatever the laptop has cached) and merges it
 * into the displayed data and cache, WITHOUT clearing the historic JSON data.
 *
 * This is the "sync from laptop" path used by "Test Connection". The laptop's
 * values win for the dates they cover; all other dates (deep historic past from
 * JSON) are preserved. This is distinct from `fetchGarminBacklog()` which does
 * a full clear+fetch from the proxy (useful when you want to discard everything
 * and re-fetch from Garmin's API).
 */
internal suspend fun HabitViewModel.syncGarminBacklog() {
    val s = _settings.value
    if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) return
    if (s.garminHabitLinks.isEmpty()) return
    if (s.fileUri.isEmpty()) return

    try {
        _garminSyncStatus.value = "Fetching laptop data…"
        val allData = garminRepo.fetchEntireBacklog(
            s.garminProxyUrl,
            s.garminAppToken,
            s.garminDateOfBirth
        ) { done, total ->
            _garminSyncStatus.value = "Fetching: $done / $total months"
        }
        _garminSyncStatus.value = "Merging laptop data…"
        // Persist to cache so laptop data survives restarts.
        withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(allData) }
        // Load the merged cache (proxy + historic import) and apply to habits.
        // This ensures imported data (e.g. swim activities from months ago) is
        // applied alongside the fresh proxy data.
        val mergedData = withContext(Dispatchers.IO) { garminRepo.loadAllCachedData() }
        mergeIntoGarminMonthlyData(mergedData)
        val syncSettings = autoLinkMissingGarminHabits(mergedData)
        applyGarminData(mergedData, syncSettings)
        _garminSyncStatus.value = "Sync complete! Laptop data merged."
    } catch (e: Exception) {
        Log.e(TAG, "Garmin backlog sync failed: ${e.message}")
        _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
    }
}

/**
 * Returns search keywords for matching a habit name to a GarminType.
 * Used by [autoLinkMissingGarminHabits] to auto-create missing links.
 */


/**
 * Returns search keywords for matching a habit name to a GarminType.
 * Used by [autoLinkMissingGarminHabits] to auto-create missing links.
 */
internal fun HabitViewModel.garminTypeKeywords(type: GarminType): List<String> = when (type) {
    GarminType.RUN_MINUTES -> listOf("run", "jog")
    GarminType.BIKE_MINUTES -> listOf("bike", "cycl")
    GarminType.SWIM_MINUTES -> listOf("swim")
    GarminType.STEPS -> listOf("step")
    // "sleep score" is specific so it never steals a "Sleep Length" habit
    GarminType.SLEEP_SCORE -> listOf("sleep score", "sleep quality")
    GarminType.SLEEP_DURATION_MINUTES -> listOf("sleep length", "sleep duration", "sleep time")
    GarminType.HRV_LAST_NIGHT, GarminType.HRV_WEEKLY_AVG -> listOf("hrv")
    GarminType.RESTING_HR -> listOf("resting hr", "resting heart")
    GarminType.VO2_MAX -> listOf("vo2")
    GarminType.FITNESS_AGE -> listOf("fitness age")
    GarminType.FITNESS_AGE_DISTANCE -> listOf("fitness age distance")
    GarminType.ALTITUDE_ASCENT_METERS -> listOf("ascent", "altitude", "elevation", "climb")
    GarminType.DISTANCE_METERS -> listOf("distance")
    GarminType.CALORIES -> listOf("calorie")
    GarminType.ACTIVE_MINUTES -> listOf("active")
    GarminType.FLOORS_CLIMBED -> listOf("floor")
    GarminType.MIN_HR -> listOf("min hr")
    GarminType.MAX_HR -> listOf("max hr")
    GarminType.STRESS_LEVEL -> listOf("stress")
}

/**
 * Auto-links habits to Garmin types when data exists for a type but no habit
 * is linked to it.  Matches by keyword (e.g. a habit named "Garmin Swim" will
 * be auto-linked to SWIM_MINUTES).
 *
 * This repairs the common scenario where a Garmin habit was created but the
 * link was never saved (or was lost), causing [applyGarminData] to silently
 * skip that type.
 *
 * @return the updated [AppSettings] with any new links applied
 */


/**
 * Auto-links habits to Garmin types when data exists for a type but no habit
 * is linked to it.  Matches by keyword (e.g. a habit named "Garmin Swim" will
 * be auto-linked to SWIM_MINUTES).
 *
 * This repairs the common scenario where a Garmin habit was created but the
 * link was never saved (or was lost), causing [applyGarminData] to silently
 * skip that type.
 *
 * @return the updated [AppSettings] with any new links applied
 */
internal suspend fun HabitViewModel.autoLinkMissingGarminHabits(
    allData: Map<GarminType, Map<String, Int>>
): AppSettings {
    val currentLinks = _settings.value.garminHabitLinks
    val allHabitNames = getAllHabitNames()
    val linkedTypes = currentLinks.values.toSet()
    val linkedHabits = currentLinks.keys.toMutableSet()

    val newLinks = mutableMapOf<String, String>()

    for ((type, dayMap) in allData) {
        if (dayMap.isEmpty()) continue
        if (type.name in linkedTypes) continue  // Already linked to some habit

        val keywords = garminTypeKeywords(type)
        val match = allHabitNames.firstOrNull { habitName ->
            habitName !in linkedHabits &&
            habitName !in newLinks &&
            keywords.any { kw -> habitName.lowercase().contains(kw) }
        }

        if (match != null) {
            newLinks[match] = type.name
            linkedHabits.add(match)
            Log.i(TAG, "Auto-linked habit '$match' → ${type.name}")
        }
    }

    if (newLinks.isEmpty()) return _settings.value

    val updatedLinks = currentLinks + newLinks
    settingsRepo.saveGarminHabitLinks(updatedLinks)
    val updatedSettings = _settings.value.copy(garminHabitLinks = updatedLinks)
    _settings.value = updatedSettings
    Log.i(TAG, "Auto-linked ${newLinks.size} Garmin habit(s): $newLinks")

    // Clear stale entries for newly linked habits so that only Garmin-derived
    // data remains after applyGarminData runs.  Without this, old manual
    // entries (e.g. a bogus value-1 on recent days) would survive and produce
    // incorrect streak/antistreak values.
    if (dbLoaded && updatedSettings.fileUri.isNotEmpty()) {
        val mutableDb = cachedPhoneDb.toMutableMap()
        var dbChanged = false
        for (habitName in newLinks.keys) {
            val existing = mutableDb[habitName]
            if (existing != null && existing.isNotEmpty()) {
                Log.i(TAG, "Cleared ${existing.size} stale entries " +
                    "for newly linked habit '$habitName'")
                mutableDb[habitName] = mutableMapOf()
                dbChanged = true
            }
        }
        if (dbChanged) {
            cachedPhoneDb = mutableDb
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(
                    Uri.parse(updatedSettings.fileUri), context, mutableDb
                )
            }
        }
    }

    return updatedSettings
}

/**
 * Applies Garmin data to linked habits in the database.
 * For each linked habit, computes increments and applies them.
 */


/**
 * Applies Garmin data to linked habits in the database.
 * For each linked habit, computes increments and applies them.
 */
internal suspend fun HabitViewModel.applyGarminData(
    allData: Map<GarminType, Map<String, Int>>,
    settings: AppSettings
) {
    val linkedHabits = settings.garminHabitLinks
    if (linkedHabits.isEmpty()) return
    if (!dbLoaded) {
        Log.w(TAG, "applyGarminData: DB not loaded yet, skipping persist (anti-wipe gate)")
        return
    }

    // Pick up concurrent on-disk writes (e.g. voice-capture increments)
    // before building the snapshot — see refreshCachedDbFromDisk.
    refreshCachedDbFromDisk(settings.fileUri)

    // Diagnostic: log what data is available and what habits are linked
    Log.d(TAG, "applyGarminData: allData types=${allData.keys.map { it.name }}, " +
        "linkedHabits=$linkedHabits")
    for ((type, dayMap) in allData) {
        if (dayMap.isNotEmpty()) {
            val sortedDates = dayMap.keys.sorted()
            Log.d(TAG, "applyGarminData: ${type.name} has ${dayMap.size} entries " +
                "(${sortedDates.first()}..${sortedDates.last()})")
        }
    }

    var mutableDb = cachedPhoneDb.toMutableMap()
    var dbChanged = false
    val todayDeltas = mutableMapOf<String, Int>()
    // Today's conditional-feed deltas per linked habit (for timestamps)
    val linkedTodayDeltas = mutableMapOf<String, Int>()

    for ((habitName, garminTypeStr) in linkedHabits) {
        val garminType = GarminType.fromKey(garminTypeStr) ?: continue
        
        // For FITNESS_AGE_DISTANCE, calculate it on-demand from FITNESS_AGE
        // This is a derived metric: distance = fitness_age - biological_age
        // Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04)
        val dailyValues = if (garminType == GarminType.FITNESS_AGE_DISTANCE) {
            try {
                val fitnessAgeData = allData[GarminType.FITNESS_AGE] ?: emptyMap()
                if (fitnessAgeData.isEmpty()) {
                    Log.w(TAG, "No fitness age data available to calculate fitness age distance")
                    emptyMap()
                } else if (settings.garminDateOfBirth.isEmpty()) {
                    Log.w(TAG, "Date of birth not set - cannot calculate fitness age distance")
                    emptyMap()
                } else {
                    val dob = LocalDate.parse(settings.garminDateOfBirth)
                    val distanceData = mutableMapOf<String, Int>()
                    
                    for ((dateStr, fitnessAge) in fitnessAgeData) {
                        val metricDate = LocalDate.parse(dateStr)
                        // Calculate biological age in hundredths of a year
                        val biologicalAgeYears = ChronoUnit.YEARS.between(dob, metricDate).toDouble()
                        val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                        // Distance = fitness_age - biological_age (both in hundredths of a year)
                        // Negative means younger fitness age than biological age (good)
                        // Positive means older fitness age than biological age (bad)
                        distanceData[dateStr] = fitnessAge - biologicalAgeHundredths
                    }
                    
                    Log.d(TAG, "Calculated ${distanceData.size} fitness age distance values from ${fitnessAgeData.size} fitness age entries (DOB: $dob)")
                    distanceData.toMap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to calculate fitness age distance: ${e.message}", e)
                emptyMap()
            }
        } else {
            val dataForType = allData[garminType]
            if (dataForType == null) {
                Log.w(TAG, "applyGarminData: SKIP habit '$habitName' — " +
                    "no ${garminType.name} data in allData " +
                    "(available: ${allData.keys.map { it.name }})")
                continue
            }
            dataForType
        }

        if (dailyValues.isEmpty()) {
            Log.w(TAG, "applyGarminData: SKIP habit '$habitName' — " +
                "${garminType.name} data map is empty")
            continue
        }

        Log.d(TAG, "Processing habit '$habitName' linked to $garminTypeStr, values=${dailyValues.size}")

        // Ensure habit exists in DB
        if (habitName !in mutableDb) {
            mutableDb[habitName] = mutableMapOf()
        }

        // Custom point ranges (if enabled for this habit) map the raw Garmin
        // value directly to a points tier; otherwise we always count as 1 point.
        val useCustomRanges = habitName in settings.customPointRangesHabits
        val customRanges = settings.customPointRanges[habitName]

        val habitData = mutableDb[habitName]!!.toMutableMap()
        var appliedCount = 0
        // Positive per-day changes of this habit: (date, storedBefore, delta)
        val positiveDayDeltas = mutableListOf<Triple<String, Int, Int>>()
        for ((date, value) in dailyValues) {
            // Compute the points for this date DETERMINISTICALLY from the current
            // (read-only) Garmin value. We always write the computed result —
            // including 0 — so that a corrected value from the laptop proxy/fetch
            // pipeline flips the point both UP and DOWN.
            val newValue: Int = if (useCustomRanges && customRanges != null) {
                com.example.tail.data.calculatePointsFromRanges(value, customRanges)
            } else {
                // Always accept Garmin data - count as 1 point if data exists
                1
            }

            val existing = habitData[date] ?: 0
            if (newValue != existing) {
                habitData[date] = newValue
                dbChanged = true
                appliedCount++
                val delta = newValue - existing

                // Track delta for today (for timestamp recording)
                val today = LocalDate.now().toString()
                if (date == today && delta > 0) {
                    todayDeltas[habitName] = (todayDeltas[habitName] ?: 0) + delta
                }
                // Remember positive day deltas (with the pre-change stored
                // value) so conditional feeds can mirror them onto linked
                // habits after this habit's values are written.
                if (delta > 0) {
                    positiveDayDeltas.add(Triple(date, existing, delta))
                }
            }
        }
        Log.d(TAG, "Applied $appliedCount values for habit '$habitName'")
        mutableDb[habitName] = habitData

        // Conditional feeds: a Garmin-linked habit with the Conditional
        // type feeds its linked habits, mirroring Step 2c of the manual
        // incrementHabit path (value-slot resolution, feed-max-1 cap and
        // max-one targets included). Feeds fire only for POSITIVE day
        // deltas — a new or raised Garmin value. Downward corrections
        // never un-feed; run the conditional backfill on the linked habit
        // to true-up after a correction.
        if (habitName in settings.conditionalHabits && positiveDayDeltas.isNotEmpty()) {
            val linkedNames = settings.conditionalLinkedHabits[habitName] ?: emptySet()
            val feedMaxOne = habitName in settings.conditionalFeedMaxOneHabits
            val todayStr = LocalDate.now().toString()
            for (linkedName in linkedNames) {
                val valueKey = effectiveConditionalLinkValueKey(
                    settings.conditionalLinkValues,
                    settings.secondaryValueHabits,
                    settings.chessComHabitLinks,
                    habitName, linkedName
                )
                val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
                for ((date, storedBefore, delta) in positiveDayDeltas) {
                    // Points slot: respect the feed-max-1 cap. Raw secondary
                    // slots (Value2/Value3) are never capped, like the manual path.
                    val feedAmount = if (targetKey == linkedName) {
                        conditionalSyncFeedAmount(storedBefore, delta, feedMaxOne)
                    } else delta
                    if (feedAmount == 0) continue

                    if (targetKey == linkedName) {
                        val linkedEntries = mutableDb[targetKey] ?: emptyMap()
                        val linkedRaw = (linkedEntries[date] ?: 0) + feedAmount
                        val linkedClamped = if (linkedName in settings.maxOneHabits)
                            linkedRaw.coerceAtMost(1) else linkedRaw
                        // Max-one target already fed today — nothing changes.
                        if (linkedClamped == (linkedEntries[date] ?: 0)) continue
                    }

                    mutableDb = habitsRepo.applyIncrementToDb(
                        mutableDb, targetKey, feedAmount, LocalDate.parse(date)
                    ).toMutableMap()
                    dbChanged = true
                    Log.d(TAG, "Conditional feed: '$habitName' +$delta on $date → " +
                        "'$targetKey' +$feedAmount")
                    if (date == todayStr) {
                        linkedTodayDeltas[linkedName] =
                            (linkedTodayDeltas[linkedName] ?: 0) + feedAmount
                    }
                }
            }
        }
    }

    if (dbChanged) {
        cachedPhoneDb = mutableDb
        rebuildHabitList()

        // Persist to disk
        withContext(Dispatchers.IO) {
            habitsRepo.persistDatabase(Uri.parse(settings.fileUri), context, mutableDb)
        }

        // Record timestamps only for the NEW increments (delta), not the
        // total count. For activity-based types (run/bike/swim) prefer
        // the watch-recorded activity start time so the schedule screen
        // shows WHEN the activity happened, not when the sync ran.
        if (todayDeltas.isNotEmpty()) {
            val now = HabitTimestampRepository.nowTime()
            val today = LocalDate.now()
            for ((habitName, delta) in todayDeltas) {
                val garminType = linkedHabits[habitName]?.let { GarminType.fromKey(it) }
                val activityTime = garminType?.let {
                    garminRepo.activityStartTime(it, today.toString())
                }
                timestampRepo.addTimestamps(habitName, delta, today, activityTime ?: now)
            }
        }

        // Timestamps for linked habits fed by today's Garmin deltas
        if (linkedTodayDeltas.isNotEmpty()) {
            val now = HabitTimestampRepository.nowTime()
            val today = LocalDate.now()
            for ((linkedName, delta) in linkedTodayDeltas) {
                timestampRepo.addTimestamps(linkedName, delta, today, now)
            }
        }

        Log.d(TAG, "Garmin data applied to habits")
    }
}

/**
 * Imports historic Garmin data from a JSON file generated by the desktop import script.
 * The JSON file should contain metrics in the same format as the cache files.
 *
 * @param jsonFile The JSON file containing the imported data
 * @param onComplete Called with the import result when complete
 */


/**
 * Imports historic Garmin data from a JSON file generated by the desktop import script.
 * The JSON file should contain metrics in the same format as the cache files.
 *
 * @param jsonFile The JSON file containing the imported data
 * @param onComplete Called with the import result when complete
 */
fun HabitViewModel.importGarminHistoricData(jsonFile: File, onComplete: (ImportResult) -> Unit = {}) {
    viewModelScope.launch {
        try {
            _garminSyncStatus.value = "Clearing old cache…"
            garminRepo.clearCache()
            
            _garminSyncStatus.value = "Importing historic data…"
            val result = garminRepo.importFromJson(jsonFile) { processed, total ->
                _garminSyncStatus.value = "Processing: $processed / $total dates"
            }
            
            if (result.success) {
                _garminSyncStatus.value = result.message
                Log.d(TAG, "Import result: success=${result.success}, message=${result.message}")
                
                // Apply the imported data to linked habits
                val s = _settings.value
                Log.d(TAG, "Import: garminHabitLinks=${s.garminHabitLinks}, fileUri=${s.fileUri.isNotEmpty()}")
                
                if (s.garminHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()) {
                    // Load all imported data from cache using the new method
                    val allData = garminRepo.loadAllCachedData()
                    Log.d(TAG, "Import: Loaded ${allData.size} Garmin types from cache")
                    
                    if (allData.isNotEmpty()) {
                        _garminSyncStatus.value = "Applying data to habits…"
                        mergeIntoGarminMonthlyData(allData)
                        val importSettings = autoLinkMissingGarminHabits(allData)
                        applyGarminData(allData, importSettings)
                        _garminSyncStatus.value = "Import complete! Data applied to linked habits."
                        Log.d(TAG, "Import: Successfully applied data to ${allData.size} Garmin types")
                    } else {
                        _garminSyncStatus.value = "Import complete but no data found in cache."
                        Log.w(TAG, "Import: No data found in cache after import")
                    }
                } else {
                    _garminSyncStatus.value = "Import complete but no habits linked or no file set."
                    Log.w(TAG, "Import: No habits linked (${s.garminHabitLinks.size}) or no file (${s.fileUri.isEmpty()})")
                }
            } else {
                _garminSyncStatus.value = "Import failed: ${result.message}"
            }
            
            onComplete(result)
        } catch (e: Exception) {
            Log.e(TAG, "Garmin historic import failed: ${e.message}", e)
            _garminSyncStatus.value = "Import failed: ${e.message?.take(50)}"
            onComplete(ImportResult(false, e.message ?: "Unknown error", emptyMap()))
        }
    }
}

// ── Tail Bridge Methods (Movies + future tethered features) ───────────────

/**
 * Derives the Tail Bridge URL from the Garmin proxy URL.
 *
 * Both services run on the same PC: Garmin proxy on port 8000, the bridge
 * on port 8001. They share the same auth token (ANDROID_PROXY_KEY).
 * This means the user only needs to configure the Garmin connection once —
 * the bridge connection info is auto-derived, no manual setup required.
 */


/**
 * Derives the Tail Bridge URL from the Garmin proxy URL.
 *
 * Both services run on the same PC: Garmin proxy on port 8000, the bridge
 * on port 8001. They share the same auth token (ANDROID_PROXY_KEY).
 * This means the user only needs to configure the Garmin connection once —
 * the bridge connection info is auto-derived, no manual setup required.
 */
internal fun HabitViewModel.deriveBridgeUrl(garminProxyUrl: String): String {
    if (garminProxyUrl.isBlank()) return ""
    return try {
        val clean = garminProxyUrl.trim().trimEnd('/')
        val uri = java.net.URI(clean)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return ""
        "$scheme://$host:$BRIDGE_PORT"
    } catch (e: Exception) {
        ""
    }
}

/**
 * Returns the auto-derived bridge (url, token) pair from Garmin settings,
 * or null if Garmin isn't configured yet.
 */


/**
 * Returns the auto-derived bridge (url, token) pair from Garmin settings,
 * or null if Garmin isn't configured yet.
 */
internal fun HabitViewModel.getBridgeConnection(): Pair<String, String>? {
    val s = _settings.value
    val bridgeUrl = deriveBridgeUrl(s.garminProxyUrl)
    val bridgeToken = s.garminAppToken
    if (bridgeUrl.isEmpty() || bridgeToken.isEmpty()) return null
    return bridgeUrl to bridgeToken
}

/** Saves bridge enabled state; URL and token are auto-derived from Garmin settings. */


/** Saves bridge enabled state; URL and token are auto-derived from Garmin settings. */
fun HabitViewModel.saveBridgeSettings(enabled: Boolean) {
    viewModelScope.launch {
        val s = _settings.value
        val derivedUrl = deriveBridgeUrl(s.garminProxyUrl)
        val derivedToken = s.garminAppToken
        settingsRepo.saveBridgeSettings(enabled, derivedUrl, derivedToken)
        _settings.value = _settings.value.copy(
            bridgeEnabled = enabled,
            bridgeUrl = derivedUrl,
            bridgeToken = derivedToken
        )
    }
}

// ── Points Wallpaper ─────────────────────────────────────────────────


/** Updates the wallpaper status text (set by the Settings section). */
fun HabitViewModel.setWallpaperStatus(status: String) {
    _wallpaperStatus.value = status
}

/**
 * Saves the points-driven wallpaper settings. Every parameter is
 * optional — unspecified values keep their current state.
 */


/**
 * Saves the points-driven wallpaper settings. Every parameter is
 * optional — unspecified values keep their current state.
 */
fun HabitViewModel.saveWallpaperSettings(
    enabled: Boolean? = null,
    dirUri: String? = null,
    target: WallpaperTarget? = null,
    metric: WallpaperMetric? = null
) {
    viewModelScope.launch {
        val cur = _settings.value
        val newEnabled = enabled ?: cur.wallpaperEnabled
        val newDir = dirUri ?: cur.wallpaperDirUri
        val newTarget = target ?: cur.wallpaperTarget
        val newMetric = metric ?: cur.wallpaperMetric
        settingsRepo.saveWallpaperSettings(
            newEnabled, newDir, newTarget.name, newMetric.name
        )
        _settings.value = _settings.value.copy(
            wallpaperEnabled = newEnabled,
            wallpaperDirUri = newDir,
            wallpaperTarget = newTarget,
            wallpaperMetric = newMetric
        )
    }
}

/** Toggles whether [habitName] is linked to the movie bridge. */
