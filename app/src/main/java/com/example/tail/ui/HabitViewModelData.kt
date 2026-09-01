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
import com.example.tail.data.LauncherIconTierManager
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

// Persist-verify retry for incrementHabit (mirrors HabitAsks ANSWER_RETRY_*).
private const val INCREMENT_PERSIST_ATTEMPTS = 3
private const val INCREMENT_PERSIST_BACKOFF_MS = 500L

internal suspend fun HabitViewModel.catchUpAndLoad(uri: Uri) {
    _isLoading.value = true
    _errorMessage.value = null
    try {
        // All sequential load work (SAF reads, JSON parsing, migrations,
        // roll-forward, day backfill) runs OFF the main thread. This used
        // to run on the Main dispatcher, which blocked the choreographer
        // and made the loading spinner animation visibly choppy.
        withContext(Dispatchers.Default) {
        runAutoRestoreIfNeeded(uri)

        // ── Roll forward MUST run BEFORE ensureDaysExist ──────────────
        // ensureDaysExist fills every missing date (including today) with
        // value 0. If it runs first, performRollForwardIfNeeded sees that
        // today's entry already exists and silently skips the roll forward.
        // By loading the raw DB first and running roll forward before any
        // placeholder 0s are created, yesterday's values are correctly
        // copied to today.
        val loadResult = habitsRepo.loadDatabaseResult(uri, context)
        if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) {
            throw com.example.tail.data.HabitsLoadFailedException(loadResult)
        }
        cachedPhoneDb = loadResult.db

        // ── One-time apnea secondary-value migration ──────────────────────
        // Pre-March-12-2026 apnea session counts were stored in the primary
        // slot (minutes). Move them to secondary_value so the fallback
        // mechanism can use them for points on days with 0 minutes.
        if (!settingsRepo.isApneaSecondaryMigrationDone()) {
            performApneaSecondaryMigration(uri)
        }

        // ── One-time resonance-breathing secondary-value migration ────────
        // Pre-Aug-8-2026 resonance session counts were stored in the primary
        // slot (minutes). Move them to secondary_value so the fallback
        // mechanism can use them for points on days with 0 minutes.
        if (!settingsRepo.isResonanceSecondaryMigrationDone()) {
            performResonanceSecondaryMigration(uri)
        }

        // ── One-time first-class minutes-slot migration ────────────────
        // Timer features used to write minutes into the generic secondary
        // slot; move them to the dedicated minutes: slot.
        if (!settingsRepo.isMinutesSlotMigrationDone()) {
            performMinutesSlotMigration(uri)
        }

        // ── One-time minutes-enabled set initialisation ────────────────
        // Seed the explicit per-habit minutes toggle so the state after
        // the toggle feature ships matches what the user had before.
        if (!settingsRepo.isMinutesToggleInitDone()) {
            performMinutesToggleInit()
        }
        // Backfill: habits already connected to a timer widget, media
        // tracker or the movie bridge get their explicit minutes toggle
        // turned ON (covers habits connected after the init seeding).
        if (!settingsRepo.isMinutesWidgetBackfillDone()) {
            performMinutesWidgetBackfill()
        }

        // ── One-time Wags minutes-primary repair ─────────────────────
        // The minutes-slot rollout wrongly classified Wags-fed habits as
        // minutes-primary: Wags stores minutes in the PRIMARY key and
        // sessions in secondary_value:, per the IPC protocol. Restore the
        // Meditations/Resonance pattern (minutes = Value1 with divider,
        // sessions = Value2 + zero-minutes points fallback).
        if (!settingsRepo.isWagsMinutesPrimaryRepairDone()) {
            performWagsMinutesPrimaryRepair(uri)
        }

        // ── One-time broken minutes-migration repair ────────────────
        // The graph long-press "Minutes value" action used to MOVE the
        // primary-key history into `minutes:` and delete the primary key,
        // blanking the graph (most visibly for Garmin-linked habits).
        // Restore the primary key and clear the flags.
        if (!settingsRepo.isBrokenMinutesMigrationRepairDone()) {
            performBrokenMinutesMigrationRepair(uri)
        }

        // ── One-time apnea sessions-primary migration ────────────────
        // The five Wags-fed apnea habits become sessions-primary:
        // sessions = primary value & points source (no divider, no
        // fallback); minutes move to the built-in minutes slot.
        if (!settingsRepo.isApneaSessionsPrimaryMigrationDone()) {
            performApneaSessionsPrimaryMigration(uri)
        }

        // ── One-time breathing sessions-primary migration ────────────
        // Meditations / Resonance Breathing / Until Contraction become
        // sessions-primary too, completing the secondary-value retirement
        // for non-special habits.
        if (!settingsRepo.isBreathingSessionsPrimaryMigrationDone()) {
            performBreathingSessionsPrimaryMigration(uri)
        }

        // ── One-time chess.com timestamp trim ──────────────────────────
        // The chess.com sync used to record one timestamp per minute;
        // trim to one per game.
        if (!settingsRepo.isChessTimestampsTrimDone()) {
            performChessTimestampTrim()
        }

        // ── Movie-bridge timestamp reconciliation ────────────────────
        // The text log is the source of truth for movie habits: make
        // each entry's watch-start time THE timestamp. Backfills past
        // films and removes confirm-time duplicates. Idempotent — it
        // only writes when the store differs from the log, so running
        // it on every load is cheap.
        runCatching { syncAllMovieHabitTimestamps() }

        // Perform roll forward BEFORE ensureDaysExist creates today=0
        performRollForwardIfNeeded()

        // Now fill in any remaining missing days. Today already has the
        // rolled-forward value, so ensureDaysExist won't overwrite it.
        val db = habitsRepo.ensureDaysExist(uri, context)
        cachedPhoneDb = db
        }

        // Gate opens ONLY here, after a genuinely successful load. Background
        // sync writers check this before persisting cachedPhoneDb.
        dbLoaded = true
        
        rebuildHabitList()

        // ── Movie minutes reconciliation ─────────────────────────────
        // Fill each movie habit's minutes slot from its "(N min)"
        // annotations, then (once per day) backfill lengths for entries
        // still missing one — bridge file durations first, OMDb second.
        viewModelScope.launch {
            runCatching { syncAllMovieMinutesSlots() }
            runCatching { maybeRunMovieMinutesBackfill() }
        }
    } catch (e: Exception) {
        // Load failed (transient SAF/blank file during a Syncthing write, etc.).
        // Leave dbLoaded as-is (do NOT flip it true) so sync writers stay blocked.
        _errorMessage.value = "Failed to load file: ${e.message}"
    } finally {
        _isLoading.value = false
    }
}

/**
 * AUTO-RESTORE-ON-CATASTROPHIC-LOSS (requirement 3).
 * Delegates to the repository, which compares the on-disk DB against the best
 * private snapshot and repairs a catastrophic wipe automatically before any
 * downstream write can cement the loss. Surfaces a friendly message on repair.
 */


/**
 * AUTO-RESTORE-ON-CATASTROPHIC-LOSS (requirement 3).
 * Delegates to the repository, which compares the on-disk DB against the best
 * private snapshot and repairs a catastrophic wipe automatically before any
 * downstream write can cement the loss. Surfaces a friendly message on repair.
 */
internal suspend fun HabitViewModel.runAutoRestoreIfNeeded(uri: Uri) {
    try {
        val restore = habitsRepo.loadWithAutoRestore(uri, context)
        if (restore is com.example.tail.data.HabitsRepository.AutoRestoreResult.Restored) {
            Log.e(
                TAG,
                "runAutoRestoreIfNeeded: AUTO-RESTORED from snapshot '" + restore.snapshotName +
                        "' (" + restore.onDiskEntryCount + " to " + restore.restoredEntryCount + " entries)."
            )
            _errorMessage.value =
                "Recovered " + restore.restoredEntryCount +
                        " entries from an automatic backup after detecting data loss."
        } else if (restore is com.example.tail.data.HabitsRepository.AutoRestoreResult.Unrecoverable) {
            Log.e(
                TAG,
                "runAutoRestoreIfNeeded: catastrophic loss (" + restore.onDiskEntryCount +
                        " entries) but no snapshot to restore from."
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "runAutoRestoreIfNeeded: failed: " + e.message)
    }
}

/**
 * Performs roll forward for habits that have the roll forward feature enabled.
 * This is called after the DB is loaded to ensure we have the latest data.
 * Only runs once per day (tracked by rollForwardLastDate).
 *
 * Roll forward copies:
 * 1. The increment amount from yesterday to today
 * 2. The text entry (if any) from yesterday to today
 */


/**
 * Performs roll forward for habits that have the roll forward feature enabled.
 * This is called after the DB is loaded to ensure we have the latest data.
 * Only runs once per day (tracked by rollForwardLastDate).
 *
 * Roll forward copies:
 * 1. The increment amount from yesterday to today
 * 2. The text entry (if any) from yesterday to today
 */
internal suspend fun HabitViewModel.performRollForwardIfNeeded() {
    val today = LocalDate.now()
    
    // Skip if we already performed roll forward today
    if (rollForwardLastDate == today) {
        Log.d(TAG, "Roll forward already performed for $today, skipping")
        return
    }
    
    // Skip if no roll forward habits are configured
    if (_settings.value.rollForwardHabits.isEmpty()) {
        Log.d(TAG, "No roll forward habits configured")
        return
    }
    
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        Log.d(TAG, "No file URI configured, skipping roll forward")
        return
    }
    
    val yesterday = today.minusDays(1)
    val yesterdayStr = com.example.tail.data.dateString(yesterday)
    val todayStr = com.example.tail.data.dateString(today)
    
    var dbChanged = false
    val updatedDb = cachedPhoneDb.toMutableMap()
    
    for (habitName in _settings.value.rollForwardHabits) {
        // Roll forward increment amount
        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        val yesterdayValue = habitEntries[yesterdayStr]
        
        // Set today's value if yesterday had a non-zero value and today is
        // either missing entirely or still at the 0 placeholder created by
        // ensureDaysExist (which may have been run by a widget or background
        // service before the main app opened).
        if (yesterdayValue != null && yesterdayValue != 0 &&
            (habitEntries[todayStr] == null || habitEntries[todayStr] == 0)) {
            habitEntries[todayStr] = yesterdayValue
            updatedDb[habitName] = habitEntries
            dbChanged = true
            Log.d(TAG, "Roll forward: copied $habitName increment from $yesterdayStr to $todayStr: $yesterdayValue")
        }
        
        // Roll forward text entry (if this habit has text input enabled)
        if (habitName in _settings.value.textInputHabits) {
            val textUriString = _settings.value.textInputFileUris[habitName]
            if (!textUriString.isNullOrEmpty()) {
                try {
                    // Find yesterday's text entry (noon timestamp)
                    val yesterdayTimestamp = java.time.LocalDateTime.of(yesterday, java.time.LocalTime.NOON)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    
                    // Load yesterday's text
                    val textLog = textInputRepo.loadTextLog(Uri.parse(textUriString), context)
                    val yesterdayText = textLog[yesterdayTimestamp]
                    
                    if (yesterdayText != null) {
                        // Check if today already has a text entry
                        val todayTimestamp = java.time.LocalDateTime.of(today, java.time.LocalTime.NOON)
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        
                        if (!textLog.containsKey(todayTimestamp)) {
                            // Roll forward the text
                            textInputRepo.updateTextEntry(Uri.parse(textUriString), context, todayTimestamp, yesterdayText, habitName = habitName)
                            Log.d(TAG, "Roll forward: copied $habitName text from $yesterdayStr to $todayStr: $yesterdayText")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Roll forward: failed to roll forward text for $habitName: ${e.message}")
                }
            }
        }
    }
    
    if (dbChanged) {
        cachedPhoneDb = updatedDb
        try {
            val uri = Uri.parse(uriString)
            habitsRepo.persistDatabase(uri, context, updatedDb)
            Log.d(TAG, "Roll forward: persisted changes to disk")
        } catch (e: Exception) {
            Log.e(TAG, "Roll forward: failed to save: ${e.message}")
            _errorMessage.value = "Failed to save roll forward: ${e.message}"
        }
    }
    
    // Mark that we've performed roll forward for today
    rollForwardLastDate = today
}


fun HabitViewModel.loadFromFile(uri: Uri) {
    viewModelScope.launch {
        catchUpAndLoad(uri)
    }
}

/** Rebuilds the displayed habit list from cached data for the current selectedDate.
 *  Stores the result in the per-screen cache for instant retrieval on switch.
 *
 *  Race-proofed: runs are serialized by [rebuildMutex] and each run
 *  re-checks its snapshot before publishing. Rebuilds are launched from
 *  many asynchronous triggers (screen switches, HabitIncrementBus events,
 *  Garmin/Chess/GitHub syncs, …), so a rebuild that snapshotted the DB
 *  BEFORE a tap used to be able to finish AFTER the increment's own
 *  rebuild and publish the pre-tap list over the optimistic UI update —
 *  the tap then recorded its timestamp but the square only showed the
 *  increment after the next screen switch. The snapshot guard makes
 *  "publish older data than what is currently cached" impossible. */


/** Rebuilds the displayed habit list from cached data for the current selectedDate.
 *  Stores the result in the per-screen cache for instant retrieval on switch.
 *
 *  Race-proofed: runs are serialized by [rebuildMutex] and each run
 *  re-checks its snapshot before publishing. Rebuilds are launched from
 *  many asynchronous triggers (screen switches, HabitIncrementBus events,
 *  Garmin/Chess/GitHub syncs, …), so a rebuild that snapshotted the DB
 *  BEFORE a tap used to be able to finish AFTER the increment's own
 *  rebuild and publish the pre-tap list over the optimistic UI update —
 *  the tap then recorded its timestamp but the square only showed the
 *  increment after the next screen switch. The snapshot guard makes
 *  "publish older data than what is currently cached" impossible. */
internal suspend fun HabitViewModel.rebuildHabitList() = rebuildMutex.withLock {
    while (true) {
        val effectiveOrder = activeHabitOrder()
        // If screens are configured and the active screen is empty, show nothing.
        // We must NOT fall back to HABIT_ORDER in this case.
        if (effectiveOrder.isEmpty() && _habitScreens.value.isNotEmpty()) {
            _habits.value = emptyList()
            _todayPoints.value = 0
            _loadingMetrics.value = LoadingMetrics(0.0, 0.0, 0)
            screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = emptyList()
            return@withLock
        }
        // Snapshot everything the build depends on, consistently.
        val dbSnapshot = cachedPhoneDb
        val targetDate = _selectedDate.value
        val screenIndex = _activeScreenIndex.value
        val settingsWithOrder = _settings.value.copy(habitOrder = effectiveOrder)
        // Run the heavy per-habit calculations on a background CPU thread
        val newList = withContext(Dispatchers.Default) {
            habitsRepo.buildHabitList(
                db = dbSnapshot,
                settings = settingsWithOrder,
                targetDate = targetDate
            )
        }
        // Stale-snapshot guard: if the DB, date or screen changed while we
        // were computing (e.g. a tap just landed), recompute from the
        // latest state instead of publishing a list that would undo it.
        if (dbSnapshot !== cachedPhoneDb ||
            targetDate != _selectedDate.value ||
            screenIndex != _activeScreenIndex.value
        ) {
            continue
        }
        _habits.value = newList
        _todayPoints.value = newList.sumOf { it.todayCount }
        var freshMetrics = getLoadingMetrics(targetDate)
        // The daily spark mirrors the app-open spinner: both derive from
        // the same DB totals, so every spinner in the app — grid, map,
        // reloads — renders the same tiers.
        _loadingMetrics.value = freshMetrics
        // Persist only metrics computed for today so history browsing never
        // poisons the cold-start cache.
        if (targetDate == LocalDate.now()) {
            cacheLoadingMetrics(freshMetrics, targetDate)
            // Mirror today's daily-points tier onto the launcher icon's
            // background colour (no-op unless the tier changed).
            LauncherIconTierManager.applyDailyTier(context, habitPointsTier(freshMetrics.todayPoints))
        }
        screenHabitCache[Pair(screenIndex, targetDate)] = newList
        return@withLock
    }
}


fun HabitViewModel.setFileUri(uri: Uri) {
    viewModelScope.launch {
        val uriString = uri.toString()
        lastLoadedUri = uriString
        settingsRepo.saveFileUri(uriString)
        _selectedDate.value = LocalDate.now()
        catchUpAndLoad(uri)
    }
}


/**
 * Sends a generic broadcast announcing that a habit was incremented.
 * Protected by the TAIL_INTEGRATION signature permission so only same-keystore
 * apps (e.g. VILD) can receive it. The broadcast is fire-and-forget — if no
 * receiver is registered, it's silently dropped.
 */


fun HabitViewModel.setScreensRelayFileUri(uri: Uri) {
    viewModelScope.launch {
        val uriString = uri.toString()
        settingsRepo.saveScreensRelayFileUri(uriString)
        _settings.value = _settings.value.copy(screensRelayFileUri = uriString)
        // Write current layout immediately so the file is up-to-date
        writeScreensRelayFile(_habitScreens.value, _activeScreenIndex.value, uriString)
    }
}

/** Toggles whether [habitName] appears as a timer square on the PC widget. */


/**
 * Sends a generic broadcast announcing that a habit was incremented.
 * Protected by the TAIL_INTEGRATION signature permission so only same-keystore
 * apps (e.g. VILD) can receive it. The broadcast is fire-and-forget — if no
 * receiver is registered, it's silently dropped.
 */
internal fun HabitViewModel.sendHabitIncrementedBroadcast(habitName: String, amount: Int = 1) {
    com.example.tail.ipc.HabitIncrementAnnouncer.announce(context, habitName, amount)
}


/** Toggles whether [habitName] appears as a timer square on the PC widget. */
fun HabitViewModel.togglePcWidgetHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.pcWidgetHabits.toMutableSet()
        val wasEnabled = habitName in current
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.savePcWidgetHabits(current)
        // Connecting a habit to the PC widget forces minutes ON — the
        // widget timer feeds the habit's `minutes:` slot.
        var minutes = _settings.value.minutesEnabledHabits
        if (!wasEnabled && habitName in current && habitName !in minutes && habitName !in _settings.value.maxOneHabits) {
            minutes = minutes + habitName
            settingsRepo.saveMinutesEnabledHabits(minutes)
        }
        _settings.value = _settings.value.copy(
            pcWidgetHabits = current,
            minutesEnabledHabits = minutes
        )
        pushPcWidgetConfig()
    }
}

/**
 * Navigate the selected date by [deltaDays] (negative = go back, positive = go forward).
 * Cannot navigate past today.
 *
 * The date label updates instantly, but the heavy habit-list rebuild is debounced:
 * if the user taps the arrow again within [NAV_DEBOUNCE_MS] ms the previous rebuild
 * is cancelled and the timer restarts. This prevents loading data for every
 * intermediate date when the user rapidly taps through many days.
 */


/**
 * Navigate the selected date by [deltaDays] (negative = go back, positive = go forward).
 * Cannot navigate past today.
 *
 * The date label updates instantly, but the heavy habit-list rebuild is debounced:
 * if the user taps the arrow again within [NAV_DEBOUNCE_MS] ms the previous rebuild
 * is cancelled and the timer restarts. This prevents loading data for every
 * intermediate date when the user rapidly taps through many days.
 */
fun HabitViewModel.navigateDay(deltaDays: Int) {
    val newDate = _selectedDate.value.plusDays(deltaDays.toLong())
    val today = LocalDate.now()
    // Instant UI update — date label changes immediately
    _selectedDate.value = if (newDate.isAfter(today)) today else newDate
    // Cancel any pending rebuild and restart the debounce timer
    navDebounceJob?.cancel()
    navDebounceJob = viewModelScope.launch {
        delay(NAV_DEBOUNCE_MS)
        rebuildHabitList()
    }
}

/**
 * Navigates directly to [date] (clamped to today at the latest).
 * Immediately rebuilds the habit list for that date (no debounce — this is
 * a deliberate jump, not rapid arrow tapping).
 */


/**
 * Navigates directly to [date] (clamped to today at the latest).
 * Immediately rebuilds the habit list for that date (no debounce — this is
 * a deliberate jump, not rapid arrow tapping).
 */
fun HabitViewModel.navigateToDate(date: LocalDate) {
    val today = LocalDate.now()
    val clamped = if (date.isAfter(today)) today else date
    _selectedDate.value = clamped
    navDebounceJob?.cancel()
    navDebounceJob = viewModelScope.launch {
        rebuildHabitList()
    }
}

/**
 * Returns a map of dateString → total habit points for every day in the given
 * [year]/[month]. Points are the sum of applyDivider(raw, divider) across ALL
 * habits that have data for that day.
 *
 * Only habits that are part of any screen (or the flat habitOrder) are included,
 * so the totals match what the user actually tracks.
 */


/**
 * Returns a map of dateString → total habit points for every day in the given
 * [year]/[month]. Points are the sum of applyDivider(raw, divider) across ALL
 * habits that have data for that day.
 *
 * Only habits that are part of any screen (or the flat habitOrder) are included,
 * so the totals match what the user actually tracks.
 */
fun HabitViewModel.getDailyTotals(year: Int, month: Int): Map<String, Int> {
    val db = cachedPhoneDb
    if (db.isEmpty()) return emptyMap()

    val dividers = _settings.value.habitDividers
    val noPointsHabits = _settings.value.noPointsHabits
    // Use all habit names present in the DB (covers all screens),
    // excluding secondary-value storage entries
    val habitNames = db.keys.filter { !isInternalValueKey(it) }

    val result = mutableMapOf<String, Int>()
    // Build date strings for every day in the month
    val firstDay = LocalDate.of(year, month, 1)
    val daysInMonth = firstDay.lengthOfMonth()
    for (d in 1..daysInMonth) {
        val ds = dateString(LocalDate.of(year, month, d))
        var total = 0
        for (name in habitNames) {
            // Skip habits that don't affect points
            if (name in noPointsHabits) continue
            val raw = db[name]?.get(ds) ?: 0
            total += effectivePointsForDate(name, raw, ds)
        }
        result[ds] = total
    }
    return result
}

/**
 * Increments a habit's count. When [recordTimestamp] is true (default), also
 * records the current time in the timestamp repository. Set to false for
 * "silent" increments (e.g. long-press, edit-mode counter adjustments).
 *
 * @param date The date the increment applies to. Null (default) uses the
 *             currently viewed date — right for tile taps. Callers whose
 *             action is semantically about TODAY regardless of what the
 *             user is viewing (e.g. habit-ask notification answers) must
 *             pass [java.time.LocalDate.now] explicitly.
 */


/**
 * Increments a habit's count. When [recordTimestamp] is true (default), also
 * records the current time in the timestamp repository. Set to false for
 * "silent" increments (e.g. long-press, edit-mode counter adjustments).
 *
 * @param date The date the increment applies to. Null (default) uses the
 *             currently viewed date — right for tile taps. Callers whose
 *             action is semantically about TODAY regardless of what the
 *             user is viewing (e.g. habit-ask notification answers) must
 *             pass [java.time.LocalDate.now] explicitly.
 */
fun HabitViewModel.incrementHabit(
    habitName: String,
    amount: Int = 1,
    recordTimestamp: Boolean = true,
    date: LocalDate? = null
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }

    // ANTI-WIPE / LOST-INCREMENT GATE: ask-answer paths (e.g. the in-app
    // movie "Watched this?" flash) can run before the habits DB finished
    // loading, or right after a transient SAF read failure. Incrementing
    // against an empty/stale cache is then either lost (the next successful
    // load overwrites it — the "movie logged but habit not incremented"
    // bug) or persists a near-empty DB. Self-heal like the chess path
    // (HabitViewModel chess sync); abort loudly if the file can't be read.
    if (!dbLoaded) {
        viewModelScope.launch {
            try {
                cachedPhoneDb = habitsRepo.loadDatabase(Uri.parse(uriString), context)
                dbLoaded = true
                rebuildHabitList()
                incrementHabit(habitName, amount, recordTimestamp, date)
            } catch (e: Exception) {
                Log.e(TAG, "DB not loaded and reload failed — increment of '$habitName' aborted", e)
                _errorMessage.value =
                    "Could not read the habits file — '$habitName' was NOT counted. Please try again."
            }
        }
        return
    }

    // The date this increment applies to. When it is NOT the viewed date,
    // the instant UI flip below is skipped (the visible rows belong to
    // another day) and the Step-3 rebuild refreshes everything from the
    // updated DB instead.
    val targetDate = date ?: _selectedDate.value
    val affectsVisibleDate = targetDate == _selectedDate.value

    // Step 1: instant targeted update — just flip todayCount for this one habit.
    // This is O(n) list copy with zero calculations, so it's effectively instant.
    val dateStr = com.example.tail.data.dateString(targetDate)
    val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
    val currentStored = currentEntries[dateStr] ?: 0
    // Custom point ranges: the entered amount is the RAW input for the day.
    // Store the calculated points tier (set semantics, like the Garmin path).
    val rangePoints = customRangePointsForInput(habitName, amount)
    val newCount: Int
    val dbDelta: Int
    if (rangePoints != null) {
        newCount = rangePoints
        dbDelta = newCount - currentStored
        // If the tier didn't actually change, bail out early
        if (dbDelta == 0) return
    } else {
        val rawNewCount = currentStored + amount
        // If this habit has the "1 max" cap, clamp to 1
        newCount = if (habitName in _settings.value.maxOneHabits) rawNewCount.coerceAtMost(1) else rawNewCount
        // If the count didn't actually change (e.g. already at 1 with 1-max), bail out early
        if (newCount == currentStored) return
        dbDelta = amount
    }
    if (affectsVisibleDate) {
        val divider = _settings.value.habitDividers[habitName] ?: 1
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                    com.example.tail.data.invertedBinaryPoints(newCount)
                } else applyDivider(newCount, divider),
                rawTodayCount = newCount
            ) else h
        }
        // Keep per-screen cache in sync with the instant update
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value
    }

    // Step 2: update in-memory cache
    // For roll forward habits, find the next manually set date BEFORE applying the change
    val nextManualDate = if (habitName in _settings.value.rollForwardHabits) {
        val manualDates = _settings.value.rollForwardManualDates[habitName] ?: emptySet()
        manualDates.mapNotNull { dateStr ->
            com.example.tail.data.parseDate(dateStr)
        }.sorted()
        .firstOrNull { it > targetDate }
    } else null
    
    var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, dbDelta, targetDate)
    
    // Step 2b: Track this date as manually set for roll forward habits
    if (habitName in _settings.value.rollForwardHabits) {
        val dateStr = com.example.tail.data.dateString(targetDate)
        val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
        currentManualDates.add(dateStr)
        val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
        updatedManualDates[habitName] = currentManualDates
        viewModelScope.launch {
            settingsRepo.saveRollForwardManualDates(updatedManualDates)
            _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
        }
    }

    // Step 2c: if this is a conditional habit, also increment all linked habits
    val linkedHabits = if (habitName in _settings.value.conditionalHabits) {
        _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()
    } else emptySet()

    // "Feed max1" sub-setting: Points feeds from this habit are capped at
    // 1 point per linked habit per day. currentStored is the source's count
    // for the day BEFORE this increment, so only the first increment of the
    // day feeds; secondary-slot feeds are not capped.
    val feedMaxOne = habitName in _settings.value.conditionalFeedMaxOneHabits

    // "Feed points" sub-setting: feeds send the source's POINTS delta
    // (divider-applied) instead of the raw increment amount, so a minutes
    // habit with a divider feeds its divided point value to linked habits.
    // The delta is computed from the day's totals so rounding accumulates
    // exactly like the displayed points (30+1 min at ÷2 feeds 15 then 1).
    val feedPoints = habitName in _settings.value.conditionalFeedPointsHabits
    val sourceDivider = _settings.value.habitDividers[habitName] ?: 1
    val baseFeedAmount = if (feedPoints && sourceDivider > 1) {
        applyDivider(currentStored + amount, sourceDivider) -
            applyDivider(currentStored, sourceDivider)
    } else amount

    // Linked-habit timestamps are collected here and recorded only after
    // the habits file was durably written (see Step 3) — a failed persist
    // must never leave phantom timestamps for increments that never
    // reached the file.
    val pendingLinkedTimestamps = mutableListOf<String>()
    for (linkedName in linkedHabits) {
        // Resolve which value slot of the linked habit this feed targets:
        // Points (default) = its count; Value2/Value3 = its raw secondary slots.
        val valueKey = effectiveConditionalLinkValueKey(
            _settings.value.conditionalLinkValues,
            _settings.value.secondaryValueHabits,
            _settings.value.chessComHabitLinks,
            habitName, linkedName
        )
        val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
        val feedAmount = if (targetKey == linkedName && feedMaxOne) {
            conditionalCappedFeedAmount(currentStored, baseFeedAmount)
        } else baseFeedAmount
        if (feedAmount == 0) continue
        if (targetKey != linkedName) {
            // Raw secondary slot: no max-1 cap; skip the instant row update
            // (the full rebuild below refreshes secondary displays from the DB).
            updatedDb = habitsRepo.applyIncrementToDb(updatedDb, targetKey, feedAmount, targetDate)
            if (recordTimestamp) {
                pendingLinkedTimestamps.add(linkedName)
            }
            continue
        }
        val linkedEntries = updatedDb[linkedName] ?: emptyMap()
        val linkedRaw = (linkedEntries[dateStr] ?: 0) + feedAmount
        val linkedClamped = if (linkedName in _settings.value.maxOneHabits) linkedRaw.coerceAtMost(1) else linkedRaw
        if (linkedClamped != (linkedEntries[dateStr] ?: 0)) {
            updatedDb = habitsRepo.applyIncrementToDb(updatedDb, linkedName, feedAmount, targetDate)
            if (affectsVisibleDate) {
                val linkedDivider = _settings.value.habitDividers[linkedName] ?: 1
                _habits.value = _habits.value.map { h ->
                    if (h.name == linkedName) h.copy(
                        todayCount = if (linkedName in _settings.value.invertedBinaryHabits) {
                            com.example.tail.data.invertedBinaryPoints(linkedClamped)
                        } else applyDivider(linkedClamped, linkedDivider),
                        rawTodayCount = linkedClamped
                    ) else h
                }
            }
            // Record timestamp for the linked habit too
            if (recordTimestamp) {
                pendingLinkedTimestamps.add(linkedName)
            }
        }
    }

    // Step 2d: Roll forward logic - fill subsequent days for roll forward habits
    if (habitName in _settings.value.rollForwardHabits) {
        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        val selectedDate = targetDate
        val today = java.time.LocalDate.now()
        
        // Fill all dates from selectedDate to nextManualDate (exclusive) or today (inclusive)
        var currentDate = selectedDate.plusDays(1)
        val endDate = nextManualDate?.minusDays(1) ?: today
        
        while (currentDate <= endDate) {
            val currentDateStr = com.example.tail.data.dateString(currentDate)
            habitEntries[currentDateStr] = newCount
            currentDate = currentDate.plusDays(1)
        }
        
        // Update the database with the filled entries
        updatedDb = updatedDb.toMutableMap()
        updatedDb[habitName] = habitEntries
    }
    
    cachedPhoneDb = updatedDb
    // Keep per-screen cache in sync after conditional updates — only when
    // the visible list actually reflects the incremented date
    if (affectsVisibleDate) {
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value
    }

    // Step 5 delta, computed here while the cache values are stable.
    val storedDelta = newCount - currentStored

    // Step 3: disk write FIRST, then every follow-up effect that must never
    // outlive the count. Previously the timestamps (Steps 4/5) were written
    // by separate, faster coroutines while the habits-file persist ran
    // behind an unprotected rebuildHabitList() — so a transient SAF
    // failure, a rebuild crash or process death between the two writes
    // silently left a phantom "timestamp without increment" (the
    // notification-answer bug). Now: persist → emit → rebuild → timed
    // entries → timestamps → broadcast, and NOTHING is recorded when the
    // persist fails.
    viewModelScope.launch {
        val uri = Uri.parse(uriString)
        // Persist with VERIFICATION: a SAF write can succeed without throwing
        // yet never land (provider busy, sync in flight). Re-read the file and
        // confirm the count actually reached disk; retry with backoff — the
        // same guarantee the system-notification answer path has had since
        // the earlier "movie increment failed" fix (HabitAsks.applyAnswer).
        var persisted = false
        var lastPersistError: Exception? = null
        for (attempt in 1..INCREMENT_PERSIST_ATTEMPTS) {
            try {
                habitsRepo.persistDatabase(uri, context, updatedDb)
                val reread = habitsRepo.loadDatabase(uri, context)
                val rereadVal = reread[habitName]?.get(dateStr)
                val landed = if (dbDelta >= 0) (rereadVal ?: 0) >= newCount
                             else (rereadVal ?: Int.MAX_VALUE) <= newCount
                if (landed) {
                    persisted = true
                    break
                }
                Log.e(TAG, "Increment verify failed (attempt $attempt) for '$habitName' — write did not land")
            } catch (e: Exception) {
                lastPersistError = e
                Log.e(TAG, "Failed to persist increment for '$habitName' (attempt $attempt): ${e.message}", e)
            }
            if (attempt < INCREMENT_PERSIST_ATTEMPTS) {
                delay(INCREMENT_PERSIST_BACKOFF_MS shl (attempt - 1))
            }
        }
        if (!persisted) {
            _errorMessage.value =
                "Failed to save increment for '$habitName'" +
                    (lastPersistError?.message?.let { ": $it" } ?: " — write did not land")
            // Resync the optimistic cache with the file so a later attempt
            // doesn't early-return against a stale-high count.
            try {
                cachedPhoneDb = habitsRepo.loadDatabase(uri, context)
            } catch (e2: Exception) {
                Log.w(TAG, "Cache resync after failed persist failed too: ${e2.message}")
            }
            return@launch
        }
        // Re-assert the just-persisted state: a concurrent disk reload
        // (e.g. the HabitIncrementBus collector's ensureDaysExist) may
        // have replaced cachedPhoneDb with the PRE-persist file while the
        // write was in flight. The rebuild below must never publish that
        // stale snapshot over the optimistic UI update from Step 1.
        cachedPhoneDb = updatedDb
        HabitsDataChangedBus.emit()
        // Full rebuild (streak/ATH recalc) — AFTER the write, and guarded so
        // a rebuild failure can never starve the effects below.
        try {
            rebuildHabitList()
        } catch (e: Exception) {
            Log.e(TAG, "Rebuild after increment failed: ${e.message}", e)
        }
        // Step 4: if this is a timed habit (and NOT subtyped — subtyped timed
        // habits record their timed entries in saveSubtypeIncrement instead),
        // append a timestamped session entry with subtype=null.
        if (habitName in _settings.value.timedHabits && habitName !in _settings.value.subtypedHabits) {
            try {
                timedDataRepo.appendEntries(habitName, mapOf(null to amount))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append timed entry for '$habitName': ${e.message}")
            }
        }
        // Step 5: record timestamp(s) if requested. One timestamp PER stored unit
        // (storedDelta, not amount) so the timestamp editor's increment amounts
        // always match the day's count — including max-1 clamps and point tiers.
        if (recordTimestamp && storedDelta > 0) {
            try {
                timestampRepo.addTimestamps(habitName, storedDelta, targetDate)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamps for '$habitName': ${e.message}")
            }
        }
        for (linkedName in pendingLinkedTimestamps) {
            try {
                timestampRepo.addTimestamp(linkedName, targetDate)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record linked timestamp for '$linkedName': ${e.message}")
            }
        }
        // Step 6: broadcast a generic "habit incremented" event so same-keystore apps
        // (e.g. VILD) can react — e.g. auto-switch from night to day mode on wake-up.
        sendHabitIncrementedBroadcast(habitName, storedDelta.coerceAtLeast(0))
    }
}

/**
 * Increments a habit's count with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */


/**
 * Increments a habit's count with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */
fun HabitViewModel.incrementHabitWithRollForward(
    habitName: String,
    amount: Int = 1,
    recordTimestamp: Boolean = true,
    customEndDate: LocalDate? = null
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }

    // Step 1: instant targeted update — just flip todayCount for this one habit.
    val dateStr = com.example.tail.data.dateString(_selectedDate.value)
    val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
    val currentStored = currentEntries[dateStr] ?: 0
    // Custom point ranges: the entered amount is the RAW input for the day.
    // Store the calculated points tier (set semantics, like the Garmin path).
    val rangePoints = customRangePointsForInput(habitName, amount)
    val newCount: Int
    val dbDelta: Int
    if (rangePoints != null) {
        newCount = rangePoints
        dbDelta = newCount - currentStored
        if (dbDelta == 0) return
    } else {
        val rawNewCount = currentStored + amount
        newCount = if (habitName in _settings.value.maxOneHabits) rawNewCount.coerceAtMost(1) else rawNewCount
        if (newCount == currentStored) return
        dbDelta = amount
    }
    val divider = _settings.value.habitDividers[habitName] ?: 1
    _habits.value = _habits.value.map { h ->
        if (h.name == habitName) h.copy(
            todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                com.example.tail.data.invertedBinaryPoints(newCount)
            } else applyDivider(newCount, divider),
            rawTodayCount = newCount
        ) else h
    }
    screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

    // Step 2: update in-memory cache
    var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, dbDelta, _selectedDate.value)
    
    // Step 2b: Track this date as manually set for roll forward habits
    if (habitName in _settings.value.rollForwardHabits) {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
        currentManualDates.add(dateStr)
        val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
        updatedManualDates[habitName] = currentManualDates
        viewModelScope.launch {
            settingsRepo.saveRollForwardManualDates(updatedManualDates)
            _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
        }
    }

    // Step 2c: Roll forward logic - fill subsequent days for roll forward habits
    if (habitName in _settings.value.rollForwardHabits && customEndDate != null) {
        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        val selectedDate = _selectedDate.value
        
        // Fill all dates from selectedDate to customEndDate (inclusive)
        var currentDate = selectedDate.plusDays(1)
        val endDate = customEndDate
        
        while (currentDate <= endDate) {
            val currentDateStr = com.example.tail.data.dateString(currentDate)
            habitEntries[currentDateStr] = newCount
            currentDate = currentDate.plusDays(1)
        }
        
        // Update the database with the filled entries
        updatedDb = updatedDb.toMutableMap()
        updatedDb[habitName] = habitEntries
    }
    
    cachedPhoneDb = updatedDb
    screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

    // Step 5 delta, computed here while the cache values are stable.
    val storedDelta = newCount - currentStored

    // Step 3: disk write FIRST, then the follow-up effects — same durable-
    // first ordering as incrementHabit: a failed persist must never leave
    // phantom timed entries/timestamps for a count that never reached the
    // file, and a rebuild crash must never starve the persist.
    viewModelScope.launch {
        val uri = Uri.parse(uriString)
        try {
            habitsRepo.persistDatabase(uri, context, updatedDb)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist roll-forward increment for '$habitName': ${e.message}", e)
            _errorMessage.value = "Failed to save: ${e.message}"
            try {
                cachedPhoneDb = habitsRepo.loadDatabase(uri, context)
            } catch (e2: Exception) {
                Log.w(TAG, "Cache resync after failed persist failed too: ${e2.message}")
            }
            return@launch
        }
        // Re-assert the just-persisted state (same rationale as
        // incrementHabit: a concurrent disk reload during the persist
        // must not become the snapshot the rebuild publishes).
        cachedPhoneDb = updatedDb
        HabitsDataChangedBus.emit()
        try {
            rebuildHabitList()
        } catch (e: Exception) {
            Log.e(TAG, "Rebuild after roll-forward increment failed: ${e.message}", e)
        }
        // Step 4: if this is a timed habit (and NOT subtyped)
        if (habitName in _settings.value.timedHabits && habitName !in _settings.value.subtypedHabits) {
            try {
                timedDataRepo.appendEntries(habitName, mapOf(null to amount))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to append timed entry for '$habitName': ${e.message}")
            }
        }
        // Step 5: record timestamp(s) if requested. One timestamp PER stored unit
        // (storedDelta, not amount) so the timestamp editor's increment amounts
        // always match the day's count — including max-1 clamps and point tiers.
        if (recordTimestamp && storedDelta > 0) {
            try {
                timestampRepo.addTimestamps(habitName, storedDelta, _selectedDate.value)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamps for '$habitName': ${e.message}")
            }
        }
        sendHabitIncrementedBroadcast(habitName, storedDelta.coerceAtLeast(0))
    }
}

/**
 * Updates a text entry with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */


/**
 * Updates a text entry with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */
fun HabitViewModel.updateTextEntryWithRollForward(
    habitName: String,
    oldTimestamp: String,
    newText: String,
    customEndDate: LocalDate,
    onComplete: () -> Unit = {}
) {
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        onComplete()
        return
    }
    viewModelScope.launch {
        try {
            // Update the text entry at the old timestamp
            textInputRepo.updateTextEntry(Uri.parse(uriString), context, oldTimestamp, newText, habitName = habitName)
            
            // Parse the date from the oldTimestamp
            val dateStr = oldTimestamp.substring(0, 10)
            val entryDate = com.example.tail.data.parseDate(dateStr)
            
            if (entryDate != null && habitName in _settings.value.rollForwardHabits) {
                // Roll forward the text to all dates from entryDate+1 to customEndDate
                if (entryDate < customEndDate) {
                    textInputRepo.rollForwardTextEntry(
                        Uri.parse(uriString),
                        context,
                        oldTimestamp,
                        entryDate.plusDays(1),
                        customEndDate,
                        habitName = habitName
                    )
                }
            }
            
            onComplete()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to update text entry: ${e.message}"
            onComplete()
        }
    }
}

/**
 * Sets a text entry with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */


/**
 * Sets a text entry with roll forward to a specified end date.
 * This is used when the user confirms the roll forward dialog.
 */
fun HabitViewModel.setTextEntryForDateWithRollForward(
    habitName: String,
    date: LocalDate,
    text: String,
    customEndDate: LocalDate,
    time: java.time.LocalTime? = null,
    onComplete: () -> Unit = {}
) {
    setTextEntriesForDateWithRollForward(
        habitName, date, listOf(text), customEndDate, time, onComplete
    )
}

/**
 * Multi-entry version of [setTextEntryForDateWithRollForward].
 * Saves all [texts] with unique timestamps (offset by 1 second), rolls forward
 * the first entry's text, and increments the habit count by 1 (selecting
 * multiple options counts as a single action).
 */


/**
 * Multi-entry version of [setTextEntryForDateWithRollForward].
 * Saves all [texts] with unique timestamps (offset by 1 second), rolls forward
 * the first entry's text, and increments the habit count by 1 (selecting
 * multiple options counts as a single action).
 */
fun HabitViewModel.setTextEntriesForDateWithRollForward(
    habitName: String,
    date: LocalDate,
    texts: List<String>,
    customEndDate: LocalDate,
    time: java.time.LocalTime? = null,
    onComplete: () -> Unit = {}
) {
    if (texts.isEmpty()) {
        onComplete()
        return
    }
    val uriString = _settings.value.textInputFileUris[habitName]
    if (uriString.isNullOrEmpty()) {
        onComplete()
        return
    }
    val effectiveTime = time ?: java.time.LocalTime.NOON
    val baseTimestamp = java.time.LocalDateTime.of(date, effectiveTime)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    viewModelScope.launch {
        try {
            // Save all entries atomically
            textInputRepo.appendMultipleTextEntries(
                Uri.parse(uriString), context, texts, date, effectiveTime, habitName = habitName
            )

            // If this is a roll forward habit, also roll forward the text AND increment the habit counts
            if (habitName in _settings.value.rollForwardHabits) {
                // Roll forward the first entry's text to all dates from date+1 to customEndDate
                if (date < customEndDate) {
                    textInputRepo.rollForwardTextEntry(
                        Uri.parse(uriString),
                        context,
                        baseTimestamp,
                        date.plusDays(1),
                        customEndDate,
                        habitName = habitName
                    )
                }

                // Also increment the habit counts for the roll forward dates
                val fileUriString = _settings.value.fileUri
                if (fileUriString.isNotEmpty()) {
                    val dateStr = com.example.tail.data.dateString(date)
                    val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
                    val currentCount = currentEntries[dateStr] ?: 0
                    // Selecting multiple options is a single action — always +1.
                    val incrementAmount = 1
                    val newCount = currentCount + incrementAmount

                    // Update the count for the current date
                    var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, incrementAmount, date)

                    // Track this date as manually set for roll forward habits
                    val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
                    currentManualDates.add(dateStr)
                    val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
                    updatedManualDates[habitName] = currentManualDates
                    settingsRepo.saveRollForwardManualDates(updatedManualDates)
                    _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)

                    // Roll forward the count to all dates from date+1 to customEndDate
                    val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
                    var currentDate = date.plusDays(1)
                    val endDate = customEndDate

                    while (currentDate <= endDate) {
                        val currentDateStr = com.example.tail.data.dateString(currentDate)
                        habitEntries[currentDateStr] = newCount
                        currentDate = currentDate.plusDays(1)
                    }

                    // Update the database with the filled entries
                    updatedDb = updatedDb.toMutableMap()
                    updatedDb[habitName] = habitEntries
                    cachedPhoneDb = updatedDb

                    // Persist the database
                    try {
                        val uri = Uri.parse(fileUriString)
                        habitsRepo.persistDatabase(uri, context, updatedDb)
                        HabitsDataChangedBus.emit()
                        rebuildHabitList()
                    } catch (e: Exception) {
                        _errorMessage.value = "Failed to save habit counts: ${e.message}"
                    }
                }
            }

            onComplete()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to set text entry: ${e.message}"
            onComplete()
        }
    }
}

// ── DB snapshots (crash/wipe recovery) ───────────────────────────────────

/** UI-facing view of one habit-DB snapshot. */
data class SnapshotUi(
    val fileName: String,
    val timestamp: Long,
    val entryCount: Int,
    val sizeBytes: Long
)


/** Loads the list of internal DB snapshots for the restore UI. */
fun HabitViewModel.loadSnapshots() {
    viewModelScope.launch {
        try {
            val mgr = habitsRepo.snapshots(context)
            val infos = mgr.listSnapshots()
            _snapshots.value = infos.map { info ->
                SnapshotUi(
                    fileName = info.file.name,
                    timestamp = info.timestamp,
                    entryCount = mgr.entryCountOf(info.file),
                    sizeBytes = info.sizeBytes
                )
            }
        } catch (e: Exception) {
            _snapshotStatus.value = "Failed to list snapshots: ${e.message}"
        }
    }
}

/**
 * Restores the DB from the snapshot with [fileName], writing it back to the
 * configured habits file and reloading the in-memory state.
 */


/**
 * Restores the DB from the snapshot with [fileName], writing it back to the
 * configured habits file and reloading the in-memory state.
 */
fun HabitViewModel.restoreSnapshot(fileName: String) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _snapshotStatus.value = "No habits file configured — cannot restore."
        return
    }
    viewModelScope.launch {
        _snapshotStatus.value = "Restoring…"
        try {
            val mgr = habitsRepo.snapshots(context)
            val info = mgr.listSnapshots().firstOrNull { it.file.name == fileName }
            if (info == null) {
                _snapshotStatus.value = "Snapshot no longer exists."
                return@launch
            }
            val db = mgr.readSnapshot(info.file)
            if (db == null) {
                _snapshotStatus.value = "Snapshot is unreadable — cannot restore."
                return@launch
            }
            val ok = habitsRepo.restoreDatabaseRaw(Uri.parse(uriString), context, db)
            if (ok) {
                cachedPhoneDb = db
                rebuildHabitList()
                val count = db.values.sumOf { it.size }
                _snapshotStatus.value = "Restored $count entries from ${fileName}."
                loadSnapshots()
            } else {
                _snapshotStatus.value = "Restore write failed."
            }
        } catch (e: Exception) {
            _snapshotStatus.value = "Restore failed: ${e.message}"
        }
    }
}

/** Clears the transient snapshot status message. */


/** Clears the transient snapshot status message. */
fun HabitViewModel.clearSnapshotStatus() { _snapshotStatus.value = null }

// ── Single-habit restore from a backup file ───────────────────────────

/**
 * Reads [backupUri], extracts the data for [habitName], and publishes a
 * non-destructive [HabitRestorePreview] via [habitRestorePreview] so the UI
 * can show a confirmation dialog. Does NOT modify any data.
 */


/**
 * Reads [backupUri], extracts the data for [habitName], and publishes a
 * non-destructive [HabitRestorePreview] via [habitRestorePreview] so the UI
 * can show a confirmation dialog. Does NOT modify any data.
 */
fun HabitViewModel.previewHabitRestore(backupUri: Uri, habitName: String) {
    val mgr = backupManager
    if (mgr == null) {
        _habitRestoreStatus.value = "Backup manager unavailable."
        return
    }
    _habitRestoreStatus.value = "Reading backup…"
    viewModelScope.launch {
        val preview = mgr.previewSingleHabitRestore(backupUri, habitName)
        if (preview == null) {
            _habitRestoreStatus.value =
                "Could not read that file as a Tail backup."
        } else {
            _pendingRestoreUri.value = backupUri
            _habitRestorePreview.value = preview
            _habitRestoreStatus.value = null
        }
    }
}

/** Dismisses the pending restore confirmation (no data is changed). */


/** Dismisses the pending restore confirmation (no data is changed). */
fun HabitViewModel.cancelHabitRestore() {
    _habitRestorePreview.value = null
    _pendingRestoreUri.value = null
}

/** Clears the transient single-habit restore status message. */


/** Clears the transient single-habit restore status message. */
fun HabitViewModel.clearHabitRestoreStatus() { _habitRestoreStatus.value = null }

/**
 * Applies the pending single-habit restore (the URI stashed in
 * [_pendingRestoreUri] for the habit in the current preview), then reloads
 * the in-memory habit list so the UI reflects the restored data.
 */


/**
 * Applies the pending single-habit restore (the URI stashed in
 * [_pendingRestoreUri] for the habit in the current preview), then reloads
 * the in-memory habit list so the UI reflects the restored data.
 */
fun HabitViewModel.applyHabitRestore() {
    val mgr = backupManager
    val uri = _pendingRestoreUri.value
    val preview = _habitRestorePreview.value
    if (mgr == null || uri == null || preview == null) {
        _habitRestoreStatus.value = "Nothing to restore."
        return
    }
    val habitName = preview.habitName
    _habitRestorePreview.value = null
    _pendingRestoreUri.value = null
    _habitRestoreStatus.value = "Restoring '$habitName'…"
    viewModelScope.launch {
        val res = mgr.restoreSingleHabit(uri, habitName)
        when (res) {
            is BackupResult.Success -> {
                // Refresh the in-memory cache + UI from the freshly-written DB.
                val uriString = _settings.value.fileUri
                if (uriString.isNotEmpty()) {
                    val fresh = habitsRepo.loadDatabase(Uri.parse(uriString), context)
                    cachedPhoneDb = fresh
                }
                rebuildHabitList()
                _habitRestoreStatus.value = res.message
            }
            is BackupResult.Failure ->
                _habitRestoreStatus.value = res.message
        }
    }
}

/**
 * Translates a raw manual input value into the value that should be STORED for
 * [habitName]. For habits with custom point ranges enabled, the stored value is
 * the points tier (the index of the first range containing the raw value) — the
 * same contract used by [applyGarminData] and [recalculateHabitPointsForCustomRanges].
 * Returns null when the habit does not use custom point ranges.
 */


/**
 * Reloads the whole database from disk. Called after the AI Assistant
 * (or any external writer) modified habitsdb.txt / the timestamp store.
 */
fun HabitViewModel.refreshAfterExternalDbChange() {
    val uri = _settings.value.fileUri
    if (uri.isNotEmpty()) {
        loadFromFile(Uri.parse(uri))
    }
}

/** Toggles the "Meal" type on/off for a specific habit. */


/**
 * Returns the effective ordered list of habit names for the currently active screen.
 * When screens are configured, returns the active screen's habit list (may be empty).
 * Falls back to the flat habitOrder (or HABIT_ORDER) only when NO screens exist at all.
 */
fun HabitViewModel.activeHabitOrder(): List<String> {
    val screens = _habitScreens.value
    return if (screens.isNotEmpty()) {
        // Screens are configured — use the active screen's list (even if empty)
        val idx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        screens[idx].habitNames
    } else {
        // No screens at all — fall back to flat order
        val order = _habitOrder.value
        if (order.isNotEmpty()) order else HABIT_ORDER
    }
}

/**
 * Returns the screen index that contains the given habit name, or -1 if not found
 * (or if screens are not configured).
 */


/**
 * Returns the screen index that contains the given habit name, or -1 if not found
 * (or if screens are not configured).
 */
fun HabitViewModel.screenIndexForHabit(habitName: String): Int {
    val screens = _habitScreens.value
    if (screens.isEmpty()) return -1
    return screens.indexOfFirst { habitName in it.habitNames }
}

/**
 * One-time migration: moves pre-2026-03-12 session counts from the primary
 * slot to the secondary_value slot for "Apnea apb" and "Apnea practiced".
 *
 * Before wags integration, these habits stored session counts (1-5) in the
 * primary slot. After March 12, 2026, wags started writing minutes there.
 * This migration moves old session-count data to secondary_value so the
 * fallback mechanism can use it for points on days with 0 minutes.
 */


/** Public read-only access to the cached database for stats computation. */
fun HabitViewModel.getCachedDatabase(): HabitsDatabase = cachedPhoneDb
