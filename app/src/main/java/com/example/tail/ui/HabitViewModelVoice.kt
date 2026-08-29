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

/** Saves the global voice trigger enabled flag (called from Settings screen). */
fun HabitViewModel.saveVoiceTriggerEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveVoiceTriggerEnabled(enabled)
        _settings.value = _settings.value.copy(voiceTriggerEnabled = enabled)
    }
}

/** Toggles the per-habit voice trigger on/off. */


/** Toggles the per-habit voice trigger on/off. */
fun HabitViewModel.toggleVoiceTrigger(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.voiceTriggerHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
            // Also clean up trigger words when disabling
            val words = _settings.value.voiceTriggerWords.toMutableMap()
            words.remove(habitName)
            settingsRepo.saveVoiceTriggerWords(words)
            _settings.value = _settings.value.copy(
                voiceTriggerHabits = current,
                voiceTriggerWords = words
            )
        } else {
            current.add(habitName)
            _settings.value = _settings.value.copy(voiceTriggerHabits = current)
        }
        settingsRepo.saveVoiceTriggerHabits(current)
    }
}

/** Sets the trigger words for a specific habit. */


/** Sets the trigger words for a specific habit. */
fun HabitViewModel.setVoiceTriggerWords(habitName: String, words: Set<String>) {
    viewModelScope.launch {
        val allWords = _settings.value.voiceTriggerWords.toMutableMap()
        if (words.isEmpty()) {
            allWords.remove(habitName)
        } else {
            allWords[habitName] = words
        }
        settingsRepo.saveVoiceTriggerWords(allWords)
        _settings.value = _settings.value.copy(voiceTriggerWords = allWords)
    }
}

/** Sets the fixed voice increment amount for a specific habit (0 or 1 = default). */


/** Sets the fixed voice increment amount for a specific habit (0 or 1 = default). */
fun HabitViewModel.setVoiceTriggerIncrement(habitName: String, amount: Int) {
    viewModelScope.launch {
        val allIncrements = _settings.value.voiceTriggerIncrements.toMutableMap()
        if (amount <= 1) {
            allIncrements.remove(habitName)
        } else {
            allIncrements[habitName] = amount
        }
        settingsRepo.saveVoiceTriggerIncrements(allIncrements)
        _settings.value = _settings.value.copy(voiceTriggerIncrements = allIncrements)
    }
}

/** Toggles the "use subtypes voice" feature on/off for [habitName]. */


/** Toggles the "use subtypes voice" feature on/off for [habitName]. */
fun HabitViewModel.toggleVoiceSubtype(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.voiceSubtypeHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveVoiceSubtypeHabits(current)
        _settings.value = _settings.value.copy(voiceSubtypeHabits = current)
    }
}

// ── Voice Note Dictation Methods ─────────────────────────────────────────

/** Saves the global voice note enabled flag (called from Settings screen). */


/** Saves the global voice note enabled flag (called from Settings screen). */
fun HabitViewModel.saveVoiceNoteEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveVoiceNoteEnabled(enabled)
        _settings.value = _settings.value.copy(voiceNoteEnabled = enabled)
    }
}

/**
 * Re-attempts fetching today's location (called after the user grants permission).
 * No-op if the location is already stored for today.
 */


/** Saves the SAF URI for the voice note markdown file. */
fun HabitViewModel.saveVoiceNoteFileUri(uri: String) {
    viewModelScope.launch {
        settingsRepo.saveVoiceNoteFileUri(uri)
        _settings.value = _settings.value.copy(voiceNoteFileUri = uri)
    }
}

/**
 * Toggles custom point ranges on/off for [habitName].
 * When enabled, the habit's points are calculated based on which range
 * the "true value" or "garmin value" falls into.
 */


/** Saves the AI Assistant endpoint configuration. */
fun HabitViewModel.saveAiAssistantSettings(baseUrl: String, apiKey: String, model: String) {
    viewModelScope.launch {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        val cleanKey = apiKey.trim()
        settingsRepo.saveAiAssistantSettings(cleanUrl, cleanKey, model)
        _settings.value = _settings.value.copy(
            aiAssistantBaseUrl = cleanUrl,
            aiAssistantApiKey = cleanKey,
            aiAssistantModel = model
        )
    }
}

/**
 * Reloads the whole database from disk. Called after the AI Assistant
 * (or any external writer) modified habitsdb.txt / the timestamp store.
 */
