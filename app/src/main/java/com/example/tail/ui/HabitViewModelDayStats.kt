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
 * Fast (cheap) stats for [date]: only total points and 30-day monthly average.
 * No streak computation. Used on every slider tick for accent colour updates.
 */
fun HabitViewModel.getDayStatsLight(date: LocalDate): DayStats {
    val db = cachedPhoneDb
    val dateStr = dateString(date)
    val tracked = trackedHabitNames().ifEmpty { db.keys }

    var totalPoints = 0
    for (name in tracked) {
        val raw = db[name]?.get(dateStr) ?: 0
        val pts = effectivePointsForDate(name, raw, dateStr)
        if (pts > 0) totalPoints += pts
    }

    var monthlySum = 0
    for (i in 0 until 30) {
        val ds = dateString(date.minusDays(i.toLong()))
        for (name in tracked) {
            val raw = db[name]?.get(ds) ?: 0
            val pts = effectivePointsForDate(name, raw, ds)
            if (pts > 0) monthlySum += pts
        }
    }
    val monthlyAverage = monthlySum.toDouble() / 30.0

    return DayStats(
        date = date,
        totalPoints = totalPoints,
        monthlyAverage = monthlyAverage,
        streakDays = 0,
        antiStreakDays = 0
    )
}

/**
 * Bulk version of the 30-day monthly average from [getDayStatsLight] for a
 * whole collection of dates at once (date → rounded average). Used by the
 * map screen to colour every dated dot in ONE sliding-window pass instead
 * of a 30-day × per-habit re-scan per date — see
 * [com.example.tail.data.monthlyAveragesBulk] for the algorithm.
 */


/**
 * Bulk version of the 30-day monthly average from [getDayStatsLight] for a
 * whole collection of dates at once (date → rounded average). Used by the
 * map screen to colour every dated dot in ONE sliding-window pass instead
 * of a 30-day × per-habit re-scan per date — see
 * [com.example.tail.data.monthlyAveragesBulk] for the algorithm.
 */
fun HabitViewModel.getMonthlyAveragesBulk(dates: Collection<LocalDate>): Map<LocalDate, Int> =
    com.example.tail.data.monthlyAveragesBulk(
        db = cachedPhoneDb,
        tracked = trackedHabitNames().ifEmpty { cachedPhoneDb.keys },
        settings = _settings.value,
        dates = dates
    )

/**
 * Triple-metric stats for "The Orrery" loading animation: today's total
 * points, the 7-day weekly average and the 30-day monthly average, all
 * ending on [date]. Computed in a single pass over the 30-day window.
 *
 * The monthly average drives the animation's primary form and colour,
 * the weekly average its orbital halo, and today's points the central
 * spark — see [HabitLoadingSpinner].
 */


/**
 * Triple-metric stats for "The Orrery" loading animation: today's total
 * points, the 7-day weekly average and the 30-day monthly average, all
 * ending on [date]. Computed in a single pass over the 30-day window.
 *
 * The monthly average drives the animation's primary form and colour,
 * the weekly average its orbital halo, and today's points the central
 * spark — see [HabitLoadingSpinner].
 */
fun HabitViewModel.getLoadingMetrics(date: LocalDate): LoadingMetrics {
    val db = cachedPhoneDb
    // Habit set MUST mirror computeTaskerStats (the declared single
    // source of truth for today/avg7/avg30): every DB habit except
    // no-points habits and secondary-value storage keys. The previous
    // screen/order-based set (trackedHabitNames) silently dropped
    // habits that exist in the DB but not on any screen, yielding
    // lower totals — so the spinner dropped a tier once the DB load
    // replaced the value.
    val noPoints = _settings.value.noPointsHabits
    val tracked = db.keys.filter { it !in noPoints && !isInternalValueKey(it) }

    var dayTotal = 0
    var weekSum = 0
    var monthSum = 0
    for (i in 0 until 30) {
        val ds = dateString(date.minusDays(i.toLong()))
        var daySum = 0
        for (name in tracked) {
            val raw = db[name]?.get(ds) ?: 0
            val pts = effectivePointsForDate(name, raw, ds)
            if (pts > 0) daySum += pts
        }
        if (i == 0) dayTotal = daySum
        if (i < 7) weekSum += daySum
        monthSum += daySum
    }
    return LoadingMetrics(
        monthlyAverage = monthSum / 30.0,
        weeklyAverage = weekSum / 7.0,
        todayPoints = dayTotal
    )
}

/**
 * Full stats for [date]: total points, 30-day monthly average, and per-habit
 * streak/anti-streak totals. Expensive — only call when the user has paused
 * on a day (debounced in the UI).
 *
 * Streak/anti-streak are computed as the sum of each tracked habit's individual
 * streak/anti-streak ending on [date], matching computeAppStats behaviour.
 * Entries are capped at [date] so historical days are not affected by today's data.
 */


/**
 * Full stats for [date]: total points, 30-day monthly average, and per-habit
 * streak/anti-streak totals. Expensive — only call when the user has paused
 * on a day (debounced in the UI).
 *
 * Streak/anti-streak are computed as the sum of each tracked habit's individual
 * streak/anti-streak ending on [date], matching computeAppStats behaviour.
 * Entries are capped at [date] so historical days are not affected by today's data.
 */
fun HabitViewModel.getDayStats(date: LocalDate): DayStats {
    val db = cachedPhoneDb
    val dateStr = dateString(date)
    val dividers = _settings.value.habitDividers
    val tracked = trackedHabitNames().ifEmpty { db.keys.filter { !isInternalValueKey(it) } }
    val fallbackHabits = _settings.value.secondaryValueFallbackHabits

    var totalPoints = 0
    for (name in tracked) {
        val raw = db[name]?.get(dateStr) ?: 0
        val pts = effectivePointsForDate(name, raw, dateStr)
        if (pts > 0) totalPoints += pts
    }

    var monthlySum = 0
    for (i in 0 until 30) {
        val ds = dateString(date.minusDays(i.toLong()))
        for (name in tracked) {
            val raw = db[name]?.get(ds) ?: 0
            val pts = effectivePointsForDate(name, raw, ds)
            if (pts > 0) monthlySum += pts
        }
    }
    val monthlyAverage = monthlySum.toDouble() / 30.0

    // Per-habit streak/anti-streak totals ending on [date].
    // Entries are filtered to <= dateStr so the "end" of the reversed list
    // is always [date], not today (fixes anti-streak stuck on today's value).
    var totalStreakDays = 0
    var totalAntiStreakDays = 0
    val timerMinutesPrimary = _settings.value.widgetTimerMinutesPrimary
    for (name in tracked) {
        val rawEntries = db[name] ?: continue
        // Widget-timer habits with minutes primary: swap the roles so
        // minutes drive the streak; the fallback on 0-minute days is
        // configurable (sessions by default, the second value, or none).
        val swapped = name in timerMinutesPrimary
        val mpFallback = _settings.value.minutesPrimaryFallbacks[name]
            ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS
        val useFallback = name in fallbackHabits || swapped
        // Fallback source: minutes slot for minutes-primary habits; the
        // legacy secondary slot for habits that use it or have data there
        // (chess.com games, JugCoach seconds); minutes slot otherwise.
        val fallbackKey = if (swapped) {
            minutesKey(name)
        } else {
            com.example.tail.data.fallbackSlotKey(
                name, _settings.value.secondaryValueHabits, db
            )
        }
        // Apply the fallback so days with 0 primary but non-zero fallback
        // count as "done" for streak purposes.
        val secEntries = if (useFallback) db[fallbackKey] ?: emptyMap() else emptyMap()
        // The fallback VALUE for minutes-primary habits: sessions (the
        // default), the second value, or none.
        val swappedFbEntries = when {
            !swapped -> rawEntries
            mpFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE -> emptyMap()
            mpFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2 ->
                db[secondaryValueKey(name)] ?: emptyMap()
            else -> rawEntries
        }
        val swappedUseFb = swapped &&
            mpFallback != com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE
        val entries = if (swapped) {
            com.example.tail.data.effectiveEntriesWithFallback(secEntries, swappedFbEntries, swappedUseFb)
        } else {
            com.example.tail.data.effectiveEntriesWithFallback(rawEntries, secEntries, useFallback)
        }
        // Cap entries at [date] — only include days up to and including [date]
        val capped = entries.filter { it.key <= dateStr }.toMutableMap()
        if (capped.isEmpty()) continue
        // Ensure [date] itself is present (as 0 if not recorded)
        if (!capped.containsKey(dateStr)) capped[dateStr] = 0
        val expanded = expandEntriesToCalendarDaysPublic(capped)
        val reversed = expanded.entries.sortedBy { it.key }.reversed()

        val divider = dividers[name] ?: 1
        var habStreak = 0
        for (entry in reversed) {
            val rawPrimary = rawEntries[entry.key] ?: 0
            val secVal = if (useFallback) secEntries[entry.key] ?: 0 else 0
            val pts = if (swapped) {
                val fbVal = if (swappedUseFb) swappedFbEntries[entry.key] ?: 0 else 0
                com.example.tail.data.effectivePointsWithFallback(secVal, divider, fbVal, swappedUseFb)
            } else {
                com.example.tail.data.effectivePointsWithFallback(rawPrimary, divider, secVal, useFallback)
            }
            if (pts > 0) habStreak++ else break
        }
        var habAntiStreak = 0
        for (entry in reversed) {
            val rawPrimary = rawEntries[entry.key] ?: 0
            val secVal = if (useFallback) secEntries[entry.key] ?: 0 else 0
            val pts = if (swapped) {
                val fbVal = if (swappedUseFb) swappedFbEntries[entry.key] ?: 0 else 0
                com.example.tail.data.effectivePointsWithFallback(secVal, divider, fbVal, swappedUseFb)
            } else {
                com.example.tail.data.effectivePointsWithFallback(rawPrimary, divider, secVal, useFallback)
            }
            if (pts == 0) habAntiStreak++ else break
        }

        totalStreakDays += habStreak
        totalAntiStreakDays += habAntiStreak
    }

    return DayStats(
        date = date,
        totalPoints = totalPoints,
        monthlyAverage = monthlyAverage,
        streakDays = totalStreakDays,
        antiStreakDays = totalAntiStreakDays
    )
}

/**
 * All habit names that appear on any screen (or in habitOrder if no screens),
 * EXCLUDING habits flagged "Don't affect points" (noPointsHabits).
 *
 * Used by the point/total calculations behind the world-map day stats
 * (getDayStats / getDayStatsLight / getDayHabitBreakdown). Garmin-imported
 * metric habits (steps, altitude, distance, …) live on real screens but store
 * raw metric values; including them here inflated the map's daily / weekly /
 * monthly totals so heavily that every day saturated to the top colour tier
 * (all-white map). Excluding noPointsHabits keeps these totals consistent with
 * the in-app stats (computeAppStats / getDailyTotals), which already exclude them.
 */


/**
 * All habit names that appear on any screen (or in habitOrder if no screens),
 * EXCLUDING habits flagged "Don't affect points" (noPointsHabits).
 *
 * Used by the point/total calculations behind the world-map day stats
 * (getDayStats / getDayStatsLight / getDayHabitBreakdown). Garmin-imported
 * metric habits (steps, altitude, distance, …) live on real screens but store
 * raw metric values; including them here inflated the map's daily / weekly /
 * monthly totals so heavily that every day saturated to the top colour tier
 * (all-white map). Excluding noPointsHabits keeps these totals consistent with
 * the in-app stats (computeAppStats / getDailyTotals), which already exclude them.
 */
internal fun HabitViewModel.trackedHabitNames(): Set<String> {
    val s = _settings.value
    val noPoints = s.noPointsHabits
    val fromScreens = s.habitScreens.flatMap { it.habitNames }.toSet()
    val base = if (fromScreens.isNotEmpty()) fromScreens
               else s.habitOrder.toSet().ifEmpty { cachedPhoneDb.keys }
    return base - noPoints
}

/** Saves the SAF URI for the voice note markdown file. */


/**
 * Recalculates fitness age distance for all dates based on current fitness age data
 * and the currently configured date of birth.
 * This is useful when the date of birth is changed or when fitness age data is updated.
 */
fun HabitViewModel.recalculateFitnessAgeDistance() {
    val s = _settings.value
    if (s.garminDateOfBirth.isEmpty()) {
        _garminSyncStatus.value = "Date of birth not set - cannot calculate fitness age distance"
        return
    }
    if (s.fileUri.isEmpty()) {
        _garminSyncStatus.value = "Set habit database file first"
        return
    }

    viewModelScope.launch {
        try {
            _garminSyncStatus.value = "Recalculating fitness age distance..."
            
            // Get fitness age data from cache
            val fitnessAgeData = garminRepo.loadAllCachedData()[com.example.tail.data.GarminType.FITNESS_AGE]
            if (fitnessAgeData == null || fitnessAgeData.isEmpty()) {
                _garminSyncStatus.value = "No fitness age data available"
                return@launch
            }

            val dob = LocalDate.parse(s.garminDateOfBirth)
            val distanceData = mutableMapOf<String, Int>()
            
            for ((dateStr, fitnessAge) in fitnessAgeData) {
                val metricDate = LocalDate.parse(dateStr)
                // Calculate biological age in hundredths of a year
                val biologicalAgeYears = ChronoUnit.YEARS.between(dob, metricDate).toDouble()
                val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                // Distance = fitness_age - biological_age (both in hundredths of a year)
                distanceData[dateStr] = fitnessAge - biologicalAgeHundredths
            }
            
            Log.d(TAG, "Recalculated ${distanceData.size} fitness age distance values (DOB: $dob)")
            
            // Create a map with just FITNESS_AGE_DISTANCE data
            val allData = mapOf(
                com.example.tail.data.GarminType.FITNESS_AGE_DISTANCE to distanceData.toMap()
            )
            
            // Apply the recalculated data to linked habits
            applyGarminData(allData, s)
            
            _garminSyncStatus.value = "Recalculated ${distanceData.size} fitness age distance values"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recalculate fitness age distance: ${e.message}", e)
            _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
        }
    }
}

// ── Voice Trigger Methods ────────────────────────────────────────────────

/** Saves the global voice trigger enabled flag (called from Settings screen). */
