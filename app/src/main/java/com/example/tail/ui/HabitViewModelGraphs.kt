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

fun HabitViewModel.setGraphTimePeriod(period: GraphTimePeriod) {
    _graphTimePeriod.value = period
    // Clear any custom zoom range when a period button is tapped
    _graphZoomStartDate.value = null
    _graphZoomEndDate.value = null
}

/**
 * Sets the graph display unit for weights habits ("kg" or "lb"). Stored
 * gram values are converted at graph-read time, so toggling re-renders
 * every weights series in the new unit.
 */


/**
 * Sets the graph display unit for weights habits ("kg" or "lb"). Stored
 * gram values are converted at graph-read time, so toggling re-renders
 * every weights series in the new unit.
 */
fun HabitViewModel.setGraphWeightUnit(unit: String) {
    viewModelScope.launch {
        settingsRepo.saveGraphWeightUnit(unit)
        _settings.value = _settings.value.copy(graphWeightUnit = unit)
    }
}

/**
 * Custom zoom date range set by pinch-to-zoom gesture.
 * When non-null, overrides the time period selection (which becomes null/deselected).
 */


fun HabitViewModel.setGraphZoomRange(startDate: LocalDate, endDate: LocalDate) {
    _graphZoomStartDate.value = startDate
    _graphZoomEndDate.value = endDate
    // Don't deselect the time period - it should remain selected during pan/swipe
    // Time period is only cleared when user taps a period button (in setGraphTimePeriod)
}


fun HabitViewModel.clearGraphZoom() {
    _graphZoomStartDate.value = null
    _graphZoomEndDate.value = null
    // Restore default period
    _graphTimePeriod.value = GraphTimePeriod.MONTH
}


fun HabitViewModel.toggleGraphMode() {
    val turningOn = !_graphMode.value
    _graphMode.value = turningOn
    if (turningOn) {
        // Deactivate other modes
        _editMode.value = false
        _scheduleMode.value = false
        // Re-anchor the graph window on the app's currently selected day:
        // drop any pinch-zoom/pan range left over from a previous session.
        _graphZoomStartDate.value = null
        _graphZoomEndDate.value = null
        // Carry the edit-mode selection into graph mode
        val carriedName = _habits.value.getOrNull(_selectedEditIndex.value)
            ?.name?.takeIf { it.isNotEmpty() }
        _selectedEditIndex.value = -1
        _movePendingSourceIndex.value = -1
        _graphSelectedHabits.value = carriedName?.let { setOf(it) } ?: emptySet()
    } else {
        _graphSelectedHabits.value = emptySet()
    }
}


fun HabitViewModel.toggleGraphHabitSelection(habitName: String) {
    val current = _graphSelectedHabits.value.toMutableSet()
    if (habitName in current) current.remove(habitName) else current.add(habitName)
    _graphSelectedHabits.value = current
}

// ── Schedule mode ──────────────────────────────────────────────────────────

/**
 * Whether daily-schedule (retrospective timeline) mode is active.
 * When on, the habit grid is replaced by an hour-by-hour timeline of
 * everything that was timestamped on the selected day.
 */


fun HabitViewModel.toggleScheduleMode() {
    val turningOn = !_scheduleMode.value
    _scheduleMode.value = turningOn
    if (turningOn) {
        // Deactivate other modes
        _editMode.value = false
        _graphMode.value = false
        _selectedEditIndex.value = -1
        _movePendingSourceIndex.value = -1
        _graphSelectedHabits.value = emptySet()
    }
}

/**
 * Sets the graph value mode for [habitName].
 * 0 = points, 1 = Value1 (raw value), 2 = Value2 (secondary value).
 * The setting is persisted per-habit.
 */


/**
 * Sets the graph value mode for [habitName].
 * 0 = points, 1 = Value1 (raw value), 2 = Value2 (secondary value).
 * The setting is persisted per-habit.
 */
fun HabitViewModel.setGraphValueMode(habitName: String, mode: Int) {
    viewModelScope.launch {
        val current = _settings.value.graphValueModeHabits.toMutableMap()
        if (mode == 0) {
            current.remove(habitName)
        } else {
            current[habitName] = mode
        }
        settingsRepo.saveGraphValueModeHabits(current)
        _settings.value = _settings.value.copy(graphValueModeHabits = current)
    }
}

/**
 * Returns the graph value mode for [habitName].
 * 0 = points (default), 1 = Value1 (raw value), 2 = Value2 (secondary value).
 */


/**
 * Returns the graph value mode for [habitName].
 * 0 = points (default), 1 = Value1 (raw value), 2 = Value2 (secondary value).
 */
fun HabitViewModel.getGraphValueMode(habitName: String): Int {
    return _settings.value.graphValueModeHabits[habitName] ?: 0
}

/** Returns true if [habitName] has the secondary value feature enabled. */


fun HabitViewModel.clearGraphSelection() {
    _graphSelectedHabits.value = emptySet()
}

// ── Multi-select graph metrics ────────────────────────────────────────

/** Returns true if [habitName] has the "Meal" type enabled. */


/**
 * Returns the list of selectable graph metrics for [habitName], depending
 * on its type. All habits get Points + Value1. Secondary-value habits also
 * get Value2. Meal habits additionally get Calories, Protein, Carbs, Fat.
 */
fun HabitViewModel.getAvailableMetrics(habitName: String): List<GraphMetricOption> {
    val labels = _settings.value.valueDisplayLabels
    val metrics = mutableListOf(
        GraphMetricOption(GRAPH_METRIC_POINTS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_POINTS, labels))
    )
    // GitHub habits use labeled metric buttons instead of generic "Value 1";
    // weights habits get their own labeled buttons below instead
    if (!isGithubHabit(habitName) && !isWeightsHabit(habitName)) {
        val v1 = GraphMetricOption(GRAPH_METRIC_VALUE1, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE1, labels))
        val v2 = GraphMetricOption(GRAPH_METRIC_VALUE2, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE2, labels))
        val hasV2 = hasSecondaryValue(habitName) ||
            habitName in _settings.value.chessComHabitLinks
        if (hasV2 && habitName in _settings.value.widgetTimerMinutesPrimary) {
            // Minutes-primary habit: the primary value (Value2 slot) comes
            // right after Points, then the secondary (Value1 slot).
            metrics.add(v2)
            metrics.add(v1)
        } else {
            metrics.add(v1)
            if (hasV2) metrics.add(v2)
        }
    }
    // First-class minutes slot — shown only when the habit's minutes
    // value is enabled (explicit toggle, or forced on by a timer-widget
    // connection / minutes-primary role; max-1 forces it off).
    val hasMinutes = isMinutesEnabled(habitName)
    if (hasMinutes) {
        metrics.add(GraphMetricOption(com.example.tail.data.GRAPH_METRIC_MINUTES, com.example.tail.data.displayLabelForValue(habitName, com.example.tail.data.GRAPH_METRIC_MINUTES, labels)))
    }
    // Value3 (second-slot secondary value, `secondary_value2:`) — written by
    // the chess.com integration (daily win percentage)
    if (habitName in _settings.value.chessComHabitLinks) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_VALUE3, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE3, labels)))
    }
    // IMDb average rating metric — available for movie-bridge habits with an OMDb API key
    if (hasImdbRatings(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_IMDB, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_IMDB, labels)))
    }
    // Runtime minutes metric — available for all movie-bridge habits; values
    // are derived from "(N min)" annotations in the text entries
    if (isMovieBridgeHabit(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_RUNTIME, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_RUNTIME, labels)))
    }
    if (isMealHabit(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_CALORIES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_CALORIES, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_PROTEIN, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_PROTEIN, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_CARBS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_CARBS, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_FAT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_FAT, labels)))
    }
    // GitHub metrics — available for habits linked to a GitHub repository
    if (isGithubHabit(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_LINES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_LINES, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_COMMITS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_COMMITS, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_ADDITIONS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_ADDITIONS, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_DELETIONS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_DELETIONS, labels)))
    }
    // JugCoach juggling metrics — available once JugCoach has written
    // session data (key-presence detection, see isJugcoachHabit)
    if (isJugcoachHabit(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME_CATCH, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME_CATCH, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME_DROP, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME_DROP, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES_CATCH, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES_CATCH, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES_DROP, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES_DROP, labels)))
    }
    // Weights habit metrics — machine/free weight (stored grams, shown in
    // the graph's kg/lb display unit) and machine/free reps. These reuse
    // the generic secondary-value slots internally, which is why the
    // generic Value 1 / Value 2 buttons are hidden for weights habits.
    if (isWeightsHabit(habitName)) {
        metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_FREE_WEIGHT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_FREE_WEIGHT, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_MACHINE_REPS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_MACHINE_REPS, labels)))
        metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_FREE_REPS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_FREE_REPS, labels)))
    }
    return metrics
}

/**
 * Returns the set of currently-selected graph metrics for [habitName].
 *
 * Migrates from the legacy single-select [AppSettings.graphValueModeHabits]
 * on first access: old mode 0 → {points}, mode 1 → {value1}, mode 2 → {value2}.
 * Defaults to {points} when nothing is stored.
 */


/**
 * Returns the set of currently-selected graph metrics for [habitName].
 *
 * Migrates from the legacy single-select [AppSettings.graphValueModeHabits]
 * on first access: old mode 0 → {points}, mode 1 → {value1}, mode 2 → {value2}.
 * Defaults to {points} when nothing is stored.
 */
fun HabitViewModel.getSelectedMetrics(habitName: String): Set<String> {
    val stored = _settings.value.graphMetricSelection[habitName]
    if (stored != null) {
        // For GitHub habits, migrate legacy "value1" to the corresponding GitHub metric
        if (isGithubHabit(habitName) && GRAPH_METRIC_VALUE1 in stored) {
            val migrated = stored.toMutableSet()
            migrated.remove(GRAPH_METRIC_VALUE1)
            migrated.add(primaryGithubMetricKey(habitName))
            return migrated
        }
        return stored
    }

    // Weights habits default to BOTH weight curves (machine + free) so the
    // two categories show together; reps are opt-in via the metric buttons.
    if (isWeightsHabit(habitName)) {
        return setOf(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, GRAPH_METRIC_WEIGHTS_FREE_WEIGHT)
    }

    // Legacy migration: convert old single-select mode to a set
    val oldMode = _settings.value.graphValueModeHabits[habitName] ?: 0
    return when (oldMode) {
        1 -> if (isGithubHabit(habitName)) setOf(primaryGithubMetricKey(habitName)) else setOf(GRAPH_METRIC_VALUE1)
        2 -> setOf(GRAPH_METRIC_VALUE2)
        else -> if (isGithubHabit(habitName)) setOf(primaryGithubMetricKey(habitName)) else setOf(GRAPH_METRIC_POINTS)
    }
}

/**
 * Returns the graph metric key corresponding to the GitHub habit's configured
 * primary metric (the one stored as value1 in the habits DB).
 */
