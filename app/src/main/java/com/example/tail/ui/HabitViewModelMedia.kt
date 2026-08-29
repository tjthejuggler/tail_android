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
 * Enables or disables the "Media" type for [habitName].
 *
 * Disabling removes the habit's media app configuration; any listening
 * block in flight is flushed (recorded) by the tracker on its next tick.
 * The underlying minutes data and the widget-trigger settings are left
 * untouched.
 */
fun HabitViewModel.toggleMediaHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.mediaHabits
        val newHabits: Set<String>
        val newApps: Map<String, String>
        if (habitName in current) {
            newHabits = current - habitName
            newApps = _settings.value.mediaApps - habitName
        } else {
            newHabits = current + habitName
            newApps = _settings.value.mediaApps
        }
        settingsRepo.saveMediaHabits(newHabits)
        settingsRepo.saveMediaApps(newApps)
        // Media habits track listening minutes in the `minutes:` slot —
        // enabling the type turns minutes ON.
        var minutes = _settings.value.minutesEnabledHabits
        if (habitName in newHabits && habitName !in current &&
            habitName !in minutes && habitName !in _settings.value.maxOneHabits
        ) {
            minutes = minutes + habitName
            settingsRepo.saveMinutesEnabledHabits(minutes)
        }
        _settings.value = _settings.value.copy(
            mediaHabits = newHabits,
            mediaApps = newApps,
            minutesEnabledHabits = minutes
        )
        updateWidgetTriggerService()
    }
}

/**
 * Sets the media app [packageName] for [habitName] (the app whose media
 * session is watched for playback — a podcast app, Spotify, any audio
 * app). The habit should already be in
 * [com.example.tail.data.AppSettings.mediaHabits].
 *
 * First-time setup mirrors the widget-timer feature: the habit gets a
 * "minutes" secondary value (where both auto-detected listening AND the
 * bubble timer write), the points fallback, and minutes as the PRIMARY
 * value — the raw count (episodes/tracks finished, tapped manually)
 * becomes the fallback used only on days with zero minutes. The bubble
 * widget is also auto-enabled over the media app so the manual timer
 * fallback is available exactly like in other apps.
 */


/**
 * Sets the media app [packageName] for [habitName] (the app whose media
 * session is watched for playback — a podcast app, Spotify, any audio
 * app). The habit should already be in
 * [com.example.tail.data.AppSettings.mediaHabits].
 *
 * First-time setup mirrors the widget-timer feature: the habit gets a
 * "minutes" secondary value (where both auto-detected listening AND the
 * bubble timer write), the points fallback, and minutes as the PRIMARY
 * value — the raw count (episodes/tracks finished, tapped manually)
 * becomes the fallback used only on days with zero minutes. The bubble
 * widget is also auto-enabled over the media app so the manual timer
 * fallback is available exactly like in other apps.
 */
fun HabitViewModel.setMediaApp(habitName: String, packageName: String) {
    viewModelScope.launch {
        val settings = _settings.value
        val apps = settings.mediaApps.toMutableMap()
        apps[habitName] = packageName
        settingsRepo.saveMediaApps(apps)
        _settings.value = _settings.value.copy(mediaApps = apps)

        // First-time setup for the minutes slot + primary/fallback roles.
        if (packageName.isNotBlank() && settings.mediaApps[habitName].isNullOrBlank()) {
            val secVal = settings.secondaryValueHabits + habitName
            val fallback = settings.secondaryValueFallbackHabits + habitName
            val minutesPrimary = settings.widgetTimerMinutesPrimary + habitName
            val labels = settings.valueDisplayLabels.toMutableMap()
            labels[habitName] = mapOf(
                com.example.tail.data.GRAPH_METRIC_VALUE1 to "Plays",
                com.example.tail.data.GRAPH_METRIC_VALUE2 to "Minutes"
            )
            settingsRepo.saveSecondaryValueHabits(secVal)
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            settingsRepo.saveValueDisplayLabels(labels)
            _settings.value = _settings.value.copy(
                secondaryValueHabits = secVal,
                secondaryValueFallbackHabits = fallback,
                widgetTimerMinutesPrimary = minutesPrimary,
                valueDisplayLabels = labels
            )
        }

        // Auto-enable the bubble over the media app so the manual
        // widget timer fallback works out of the box (same as other
        // apps). An existing, different trigger app is respected.
        if (packageName.isNotBlank() &&
            _settings.value.widgetTriggerApps[habitName].isNullOrBlank()
        ) {
            val trigHabits = _settings.value.widgetTriggerHabits + habitName
            val trigApps = _settings.value.widgetTriggerApps.toMutableMap()
            trigApps[habitName] = packageName
            settingsRepo.saveWidgetTriggerHabits(trigHabits)
            settingsRepo.saveWidgetTriggerApps(trigApps)
            _settings.value = _settings.value.copy(
                widgetTriggerHabits = trigHabits,
                widgetTriggerApps = trigApps
            )
        }

        rebuildHabitList()
        updateWidgetTriggerService()
    }
}

/**
 * Returns whether the user has granted notification-listener access
 * (enabled MusicNotificationListenerService), required for automatic
 * media playback detection.
 */


/**
 * Returns whether the user has granted notification-listener access
 * (enabled MusicNotificationListenerService), required for automatic
 * media playback detection.
 */
fun HabitViewModel.hasNotificationListenerAccess(): Boolean =
    com.example.tail.data.SpotifyDetector.isNotificationListenerEnabled(context)

/**
 * Opens the system notification-access settings screen so the user can
 * grant access for automatic media playback detection.
 */


/**
 * Opens the system notification-access settings screen so the user can
 * grant access for automatic media playback detection.
 */
fun HabitViewModel.openNotificationListenerSettings() {
    com.example.tail.data.SpotifyDetector.openNotificationListenerSettings(context)
}

// ── Media per-show breakdown (edit screen podcast removal) ─────────────

/** One show/podcast (or artist-less title) heard today on a media habit. */
data class MediaShowMinutes(
    val show: String,
    /** Sum of the logged "(NN min)" episode/track durations for today. */
    val minutes: Int,
    /** How many plays were logged for this show today. */
    val plays: Int
)


/**
 * Parses one media text-log entry into (show, minutes). The show is the
 * artist/show segment; entries without one (some music apps) fall back
 * to the title itself. Returns null for entries that don't match the
 * tracker's format (e.g. manual text notes the user typed).
 */
internal fun HabitViewModel.parseMediaShowEntry(text: String): Pair<String, Int>? {
    val m = MEDIA_LOG_ENTRY_REGEX.matchEntire(text.trim()) ?: return null
    val artist = m.groupValues[2].takeIf { it.isNotBlank() }
    val show = artist ?: m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
    val minutes = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
    return show to minutes
}

/**
 * Loads today's per-show listening breakdown for a media habit from its
 * text-entry log (the play-by-play entries MediaPlaybackTracker writes).
 * Groups today's plays by show and sums the logged durations; sorted by
 * minutes descending. Habits without a text log get an empty list.
 */


/**
 * Loads today's per-show listening breakdown for a media habit from its
 * text-entry log (the play-by-play entries MediaPlaybackTracker writes).
 * Groups today's plays by show and sums the logged durations; sorted by
 * minutes descending. Habits without a text log get an empty list.
 */
fun HabitViewModel.loadMediaTodayShows(habitName: String) {
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        _mediaTodayShows.value = emptyList()
        return
    }
    val datePrefix = dateString(LocalDate.now())
    viewModelScope.launch {
        val shows = withContext(Dispatchers.IO) {
            try {
                val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                log.entries
                    .filter { (ts, _) -> ts.startsWith(datePrefix) }
                    .mapNotNull { (_, text) -> parseMediaShowEntry(text) }
                    .groupBy { it.first }
                    .map { (show, plays) ->
                        MediaShowMinutes(show, plays.sumOf { it.second }, plays.size)
                    }
                    .sortedByDescending { it.minutes }
            } catch (e: Exception) {
                Log.w(TAG, "Media show breakdown failed for '$habitName': ${e.message}")
                emptyList()
            }
        }
        _mediaTodayShows.value = shows
    }
}

/**
 * Removes [show] from a media habit's TODAY listening: deletes today's
 * log entries for that show and subtracts their logged minutes from the
 * habit's minutes secondary-value slot for today (the same slot the
 * tracker writes to, so the day total drops by exactly what that show
 * contributed). Then refreshes the breakdown, widgets and listeners.
 */


/**
 * Removes [show] from a media habit's TODAY listening: deletes today's
 * log entries for that show and subtracts their logged minutes from the
 * habit's minutes secondary-value slot for today (the same slot the
 * tracker writes to, so the day total drops by exactly what that show
 * contributed). Then refreshes the breakdown, widgets and listeners.
 */
fun HabitViewModel.removeMediaShowFromToday(habitName: String, show: String) {
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) return
    val dbUriString = _settings.value.fileUri
    val datePrefix = dateString(LocalDate.now())
    viewModelScope.launch {
        try {
            val textUri = Uri.parse(uriString)
            val log = withContext(Dispatchers.IO) {
                textInputRepo.loadTextLog(textUri, context)
            }
            val doomed = log.entries.filter { (ts, text) ->
                ts.startsWith(datePrefix) && parseMediaShowEntry(text)?.first == show
            }
            if (doomed.isNotEmpty()) {
                textInputRepo.deleteTextEntries(
                    textUri, context, doomed.map { it.key }, habitName = habitName
                )
            }
            val minutes = doomed.sumOf { parseMediaShowEntry(it.value)?.second ?: 0 }
            if (minutes > 0 && dbUriString.isNotEmpty()) {
                habitsRepo.incrementHabitWithMinutes(
                    Uri.parse(dbUriString), context, habitName, -minutes, 0
                )
                HabitIncrementBus.emit(habitName)
                HabitListWidgetProvider.refreshAll(context)
            }
            Log.i(TAG, "Removed media show '$show' for '$habitName': ${doomed.size} plays, -$minutes min")
            loadMediaTodayShows(habitName)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove media show '$show' for '$habitName': ${e.message}")
        }
    }
}

/**
 * Returns whether the user has granted Usage Access permission,
 * required for the widget trigger feature to work.
 */


/**
 * Returns whether the user has granted Usage Access permission,
 * required for the widget trigger feature to work.
 */
fun HabitViewModel.hasUsageAccess(): Boolean =
    com.example.tail.widget.WidgetTriggerService.hasUsageAccess(context)

/**
 * Opens the system Usage Access settings screen so the user can grant
 * the permission.
 */


/**
 * Opens the system Usage Access settings screen so the user can grant
 * the permission.
 */
fun HabitViewModel.openUsageAccessSettings() {
    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

/**
 * Starts or stops [WidgetTriggerService] based on whether anything needs
 * monitoring: habit trigger apps, media apps (automatic listening-time
 * tracking) OR the Chess Readiness app. Called after every widget-trigger
 * / media / chess-readiness setting change.
 */


/**
 * Starts or stops [WidgetTriggerService] based on whether anything needs
 * monitoring: habit trigger apps, media apps (automatic listening-time
 * tracking) OR the Chess Readiness app. Called after every widget-trigger
 * / media / chess-readiness setting change.
 */
internal fun HabitViewModel.updateWidgetTriggerService() {
    val s = _settings.value
    val validCount = s.widgetTriggerApps.values.count { it.isNotBlank() } +
        s.mediaApps.entries.count { it.value.isNotBlank() && it.key in s.mediaHabits } +
        if (s.chessReadinessEnabled && s.chessReadinessApp.isNotBlank()) 1 else 0
    com.example.tail.widget.WidgetTriggerService.updateServiceState(context, validCount)
}

// ── Chess Readiness methods ───────────────────────────────────────────

/**
 * Enables or disables the Chess Readiness feature (global toggle in the
 * widget section of Settings). Disabling keeps the associated app in
 * settings but stops the bubble from watching it.
 */
