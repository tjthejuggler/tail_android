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

/** Saves AI icon generation settings to DataStore. */
fun HabitViewModel.saveAiIconSettings(
    enabled: Boolean, apiKey: String, baseUrl: String, endpoint: String,
    model: String, quality: String = ""
) {
    viewModelScope.launch {
        settingsRepo.saveAiIconSettings(enabled, apiKey, baseUrl, endpoint, model, quality)
        _settings.value = _settings.value.copy(
            aiIconsEnabled = enabled,
            aiIconsApiKey = apiKey,
            aiIconsBaseUrl = baseUrl,
            aiIconsEndpoint = endpoint,
            aiIconsModel = model,
            aiIconsQuality = quality
        )
    }
}

/** Fetches available models from the API and updates the models list. */


/** Fetches available models from the API and updates the models list. */
fun HabitViewModel.fetchAiModels() {
    val s = _settings.value
    if (s.aiIconsApiKey.isEmpty() || s.aiIconsBaseUrl.isEmpty()) return
    viewModelScope.launch {
        try {
            val models = aiIconGenService.fetchModels(s.aiIconsApiKey, s.aiIconsBaseUrl)
            if (models.isNotEmpty()) _aiModels.value = models
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch AI models", e)
            // Keep fallback models
        }
    }
}

/** Refreshes the list of stored AI icons from disk. */


/** Refreshes the list of stored AI icons from disk. */
fun HabitViewModel.refreshAiIcons() {
    viewModelScope.launch(Dispatchers.IO) {
        _aiIcons.value = aiIconRepo.listIcons()
    }
}

/**
 * Generates a new AI icon from the given prompt, post-processes it to
 * white-on-transparent, saves it to the local database, and refreshes the list.
 *
 * When [habitName] is given, generation runs as a background job tied to
 * that habit: the caller does NOT need to wait. The habit's tile shows a
 * spinner while generation is in flight, and once the icon is ready it is
 * automatically applied to the habit (same path as [setHabitIcon]) and a
 * toast announces the result — even if the icon picker was closed long ago.
 */


/**
 * Generates a new AI icon from the given prompt, post-processes it to
 * white-on-transparent, saves it to the local database, and refreshes the list.
 *
 * When [habitName] is given, generation runs as a background job tied to
 * that habit: the caller does NOT need to wait. The habit's tile shows a
 * spinner while generation is in flight, and once the icon is ready it is
 * automatically applied to the habit (same path as [setHabitIcon]) and a
 * toast announces the result — even if the icon picker was closed long ago.
 */
fun HabitViewModel.generateAiIcon(prompt: String, habitName: String? = null) {
    val s = _settings.value
    if (!s.aiIconsEnabled || s.aiIconsApiKey.isEmpty() || s.aiIconsBaseUrl.isEmpty()) {
        _aiIconError.value = "AI icons not configured. Check Settings."
        return
    }
    _aiIconGenerating.value = true
    _aiIconError.value = null
    if (habitName != null) {
        _aiIconPendingHabits.value = _aiIconPendingHabits.value + habitName
        _aiIconMessages.tryEmit("Generating AI icon for \"$habitName\"…")
    }
    viewModelScope.launch {
        try {
            val bitmap = aiIconGenService.generateIcon(
                prompt = prompt,
                apiKey = s.aiIconsApiKey,
                baseUrl = s.aiIconsBaseUrl,
                endpoint = s.aiIconsEndpoint.ifEmpty { "/v1/images/generations" },
                model = s.aiIconsModel.ifEmpty { "nano-banana-pro" },
                quality = s.aiIconsQuality
            )
            val icon = withContext(Dispatchers.IO) {
                aiIconRepo.saveIcon(bitmap, prompt)
            }
            refreshAiIcons()
            if (habitName != null) {
                // Auto-apply the freshly generated icon to the habit
                setHabitIcon(habitName, icon.id)
                _aiIconMessages.tryEmit("AI icon applied to \"$habitName\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI icon generation failed", e)
            _aiIconError.value = e.message ?: "Unknown error"
            if (habitName != null) {
                _aiIconMessages.tryEmit(
                    "AI icon for \"$habitName\" failed: ${_aiIconError.value}"
                )
            }
        } finally {
            if (habitName != null) {
                _aiIconPendingHabits.value = _aiIconPendingHabits.value - habitName
            }
            _aiIconGenerating.value = false
        }
    }
}

/** Deletes an AI-generated icon by its id. */


/** Deletes an AI-generated icon by its id. */
fun HabitViewModel.deleteAiIcon(iconId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        aiIconRepo.deleteIcon(iconId)
        _aiIcons.value = aiIconRepo.listIcons()
    }
}

/** Returns the AiIconRepository for loading bitmaps in the UI. */


/** Returns the AiIconRepository for loading bitmaps in the UI. */
fun HabitViewModel.getAiIconRepo(): AiIconRepository = aiIconRepo

/** Clears the AI icon error message. */


/** Clears the AI icon error message. */
fun HabitViewModel.clearAiIconError() { _aiIconError.value = null }
