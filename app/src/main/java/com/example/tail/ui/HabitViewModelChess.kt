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
 * Enables or disables the Chess Readiness feature (global toggle in the
 * widget section of Settings). Disabling keeps the associated app in
 * settings but stops the bubble from watching it.
 */
fun HabitViewModel.setChessReadinessEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveChessReadinessEnabled(enabled)
        _settings.value = _settings.value.copy(chessReadinessEnabled = enabled)
        updateWidgetTriggerService()
    }
}

/**
 * Enables or disables the in-app stats overlay (StatsOverlayService) — the
 * always-on-top bar showing today / avg7 / avg30, fed by the same
 * computation as the loading spinner. Toggling starts/stops the service.
 */


/**
 * Enables or disables the in-app stats overlay (StatsOverlayService) — the
 * always-on-top bar showing today / avg7 / avg30, fed by the same
 * computation as the loading spinner. Toggling starts/stops the service.
 */
fun HabitViewModel.setStatsOverlayEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveStatsOverlayEnabled(enabled)
        _settings.value = _settings.value.copy(statsOverlayEnabled = enabled)
        if (enabled) {
            com.example.tail.widget.StatsOverlayService.start(context)
        } else {
            com.example.tail.widget.StatsOverlayService.stop(context)
        }
    }
}

/**
 * Enables or disables the app-stats record notifications ("close to a
 * new record" / "record broken" notices and the Record News feed).
 */


/**
 * Enables or disables the app-stats record notifications ("close to a
 * new record" / "record broken" notices and the Record News feed).
 */
fun HabitViewModel.setAppStatsRecordNotificationsEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveAppStatsRecordNotificationsEnabled(enabled)
        _settings.value = _settings.value.copy(appStatsRecordNotificationsEnabled = enabled)
    }
}

/**
 * Sets the app associated with Chess Readiness. The floating bubble will
 * appear over this app and its popup menu gains a "Chess Readiness"
 * option. Only meaningful while the feature is enabled.
 */


/**
 * Sets the app associated with Chess Readiness. The floating bubble will
 * appear over this app and its popup menu gains a "Chess Readiness"
 * option. Only meaningful while the feature is enabled.
 */
fun HabitViewModel.setChessReadinessApp(packageName: String) {
    viewModelScope.launch {
        settingsRepo.saveChessReadinessApp(packageName)
        _settings.value = _settings.value.copy(chessReadinessApp = packageName)
        // Mirror into the synchronous prefs store for the Chess Guard
        // accessibility service (its callback path cannot read DataStore).
        com.example.tail.widget.ChessReadinessStore.saveChessPackage(context, packageName)
        updateWidgetTriggerService()
    }
}

/**
 * Switches the chess readiness engine between "v1" (the original
 * diagnostic), "v2" (the neurobiological gate) and "v3" (the reflex +
 * puzzle rush survival gate). Persisted to DataStore and mirrored into
 * the synchronous v2 prefs store so the floating bubble service can
 * branch without reading DataStore on the window-manager path.
 */


/**
 * Switches the chess readiness engine between "v1" (the original
 * diagnostic), "v2" (the neurobiological gate) and "v3" (the reflex +
 * puzzle rush survival gate). Persisted to DataStore and mirrored into
 * the synchronous v2 prefs store so the floating bubble service can
 * branch without reading DataStore on the window-manager path.
 */
fun HabitViewModel.setChessReadinessVersion(version: String) {
    viewModelScope.launch {
        val normalized = when (version) {
            com.example.tail.widget.ChessReadinessV2Store.VERSION_V2 ->
                com.example.tail.widget.ChessReadinessV2Store.VERSION_V2
            com.example.tail.widget.ChessReadinessV2Store.VERSION_V3 ->
                com.example.tail.widget.ChessReadinessV2Store.VERSION_V3
            else -> com.example.tail.widget.ChessReadinessV2Store.VERSION_V1
        }
        settingsRepo.saveChessReadinessVersion(normalized)
        _settings.value = _settings.value.copy(chessReadinessVersion = normalized)
        com.example.tail.widget.ChessReadinessV2Store.saveReadinessVersion(context, normalized)
    }
}

/** Status line for the survival-PB Chess.com sync button (settings UI). */


/**
 * Syncs the Puzzle Rush Survival all-time PB from the Chess.com API
 * (`puzzle_rush.best.score`) using the configured Chess.com username.
 * The API cache can lag up to 12 h, so the settings view also offers a
 * manual override field.
 */
fun HabitViewModel.syncSurvivalPbFromChessCom() {
    viewModelScope.launch {
        _survivalPbSyncStatus.value = "Syncing from Chess.com…"
        try {
            val s = settingsRepo.settingsFlow.first()
            val username = s.chessComUsername.trim()
            if (username.isEmpty()) {
                _survivalPbSyncStatus.value =
                    "⚠ No Chess.com username configured (Chess.com section above)"
                return@launch
            }
            val stats = chessComRepo.fetchPuzzleStats(username)
            val best = stats.puzzleRushBestScore
            if (best <= 0) {
                _survivalPbSyncStatus.value = "⚠ Chess.com reports no puzzle rush best score"
                return@launch
            }
            com.example.tail.widget.ChessReadinessV3Store
                .saveSurvivalPbFromChessCom(context, best)
            _survivalPbSyncStatus.value = "✅ Synced PB: $best (target = " +
                com.example.tail.widget.ChessReadinessV3Engine.targetScore(best) + ")"
        } catch (e: Exception) {
            _survivalPbSyncStatus.value = "⚠ Sync failed: ${e.message ?: "network error"}"
        }
    }
}

/**
 * Switches the POST-GAME (Phase 2) audit engine between "v1" (the
 * adaptive ΔE/strain evidence model) and "v2" (the research-report
 * system: fatigue ceiling, loss-streak stop rules, tilt vector, ACWR,
 * hysteresis). Persisted to DataStore and mirrored into the synchronous
 * v2 prefs store so the share-sheet reconciler can branch without
 * reading DataStore. Fully independent of the pre-game readiness
 * version — the two toggles combine freely.
 */


/**
 * Switches the POST-GAME (Phase 2) audit engine between "v1" (the
 * adaptive ΔE/strain evidence model) and "v2" (the research-report
 * system: fatigue ceiling, loss-streak stop rules, tilt vector, ACWR,
 * hysteresis). Persisted to DataStore and mirrored into the synchronous
 * v2 prefs store so the share-sheet reconciler can branch without
 * reading DataStore. Fully independent of the pre-game readiness
 * version — the two toggles combine freely.
 */
fun HabitViewModel.setChessPhase2Version(version: String) {
    viewModelScope.launch {
        val normalized = when (version) {
            com.example.tail.widget.ChessPhase2V2Store.VERSION_V2 ->
                com.example.tail.widget.ChessPhase2V2Store.VERSION_V2
                com.example.tail.widget.ChessPhase2V2Store.VERSION_V3 ->
                    com.example.tail.widget.ChessPhase2V2Store.VERSION_V3
                com.example.tail.widget.ChessPhase2V2Store.VERSION_V4 ->
                    com.example.tail.widget.ChessPhase2V2Store.VERSION_V4
                else -> com.example.tail.widget.ChessPhase2V2Store.VERSION_V1
        }
        settingsRepo.saveChessPhase2Version(normalized)
        _settings.value = _settings.value.copy(chessPhase2Version = normalized)
        com.example.tail.widget.ChessPhase2V2Store.savePhase2Version(context, normalized)
    }
}

/**
 * Enables or disables Chess Guard — the hard enforcement layer that
 * keeps the chess app closed while the readiness policy says blocked.
 * The switch-ON timestamp is recorded so games that ended before
 * enforcement existed are never penalized retroactively.
 */


/**
 * Enables or disables Chess Guard — the hard enforcement layer that
 * keeps the chess app closed while the readiness policy says blocked.
 * The switch-ON timestamp is recorded so games that ended before
 * enforcement existed are never penalized retroactively.
 */
fun HabitViewModel.setChessEnforcementEnabled(enabled: Boolean) {
    viewModelScope.launch {
        com.example.tail.widget.ChessReadinessStore.setEnforcementEnabled(context, enabled)
    }
}

/**
 * Deletes the habit at [index] from the active screen (or flat order).
 * JSON data is preserved by default — the delete dialog offers
 * [deleteHabitData] as an explicit opt-in purge. Clears the selection
 * after deletion.
 */
