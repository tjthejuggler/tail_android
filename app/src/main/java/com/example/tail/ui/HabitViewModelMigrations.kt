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
 * One-time migration: moves pre-2026-03-12 session counts from the primary
 * slot to the secondary_value slot for "Apnea apb" and "Apnea practiced".
 *
 * Before wags integration, these habits stored session counts (1-5) in the
 * primary slot. After March 12, 2026, wags started writing minutes there.
 * This migration moves old session-count data to secondary_value so the
 * fallback mechanism can use it for points on days with 0 minutes.
 */
internal suspend fun HabitViewModel.performApneaSecondaryMigration(uri: Uri) {
    val cutoff = "2026-03-12"
    val habitsToMigrate = listOf("Apnea apb", "Apnea practiced")
    var totalMoved = 0

    val db = cachedPhoneDb.toMutableMap()
    for (habitName in habitsToMigrate) {
        val primary = db[habitName] ?: continue
        val secKey = com.example.tail.data.secondaryValueKey(habitName)
        val secondary = db[secKey]?.toMutableMap() ?: mutableMapOf()
        val mutablePrimary = primary.toMutableMap()

        for ((dateStr, value) in primary) {
            if (dateStr >= cutoff) continue
            if (value <= 0) continue

            // Move to secondary (max merge)
            secondary[dateStr] = maxOf(secondary[dateStr] ?: 0, value)
            // Zero out primary
            mutablePrimary[dateStr] = 0
            totalMoved++
        }

        db[habitName] = mutablePrimary
        db[secKey] = secondary
    }

    if (totalMoved > 0) {
        Log.i(TAG, "Apnea secondary migration: moved $totalMoved dates from primary → secondary (pre-$cutoff)")
        cachedPhoneDb = db
        habitsRepo.persistDatabase(uri, context, db)
    }

    settingsRepo.setApneaSecondaryMigrationDone()
    Log.i(TAG, "Apnea secondary migration complete.")
}

/**
 * One-time migration: moves pre-2026-08-08 session counts from the primary
 * slot to the secondary_value slot for "Resonance Breathing".
 *
 * Before 2026-08-08, wags wrote session counts (+1 per session) to the
 * primary slot. After that date, wags writes minutes there. The wags
 * backfill has already replaced the primary values with minutes for every
 * date that has a resonance/RF record, so the only stale session counts
 * remaining are SMALL values (≤ 3) on pre-cutoff dates — days with no wags
 * record (manual increments or sessions whose wags record is gone).
 *
 * Unlike the apnea migration (which moves ALL pre-cutoff values), this one
 * only moves values ≤ [MAX_LEGACY_SESSION_COUNT] because larger pre-cutoff
 * values are legitimate backfilled minutes.
 *
 * Also auto-enables the secondary-value track for the habit so Value 2
 * (sessions) is visible in the UI, mirroring Meditations.
 */


/**
 * One-time migration: moves pre-2026-08-08 session counts from the primary
 * slot to the secondary_value slot for "Resonance Breathing".
 *
 * Before 2026-08-08, wags wrote session counts (+1 per session) to the
 * primary slot. After that date, wags writes minutes there. The wags
 * backfill has already replaced the primary values with minutes for every
 * date that has a resonance/RF record, so the only stale session counts
 * remaining are SMALL values (≤ 3) on pre-cutoff dates — days with no wags
 * record (manual increments or sessions whose wags record is gone).
 *
 * Unlike the apnea migration (which moves ALL pre-cutoff values), this one
 * only moves values ≤ [MAX_LEGACY_SESSION_COUNT] because larger pre-cutoff
 * values are legitimate backfilled minutes.
 *
 * Also auto-enables the secondary-value track for the habit so Value 2
 * (sessions) is visible in the UI, mirroring Meditations.
 */
internal suspend fun HabitViewModel.performResonanceSecondaryMigration(uri: Uri) {
    val cutoff = "2026-08-08"
    val habitName = "Resonance Breathing"
    var totalMoved = 0

    val db = cachedPhoneDb.toMutableMap()
    val primary = db[habitName]
    if (primary != null) {
        val secKey = com.example.tail.data.secondaryValueKey(habitName)
        val secondary = db[secKey]?.toMutableMap() ?: mutableMapOf()
        val mutablePrimary = primary.toMutableMap()

        for ((dateStr, value) in primary) {
            if (dateStr >= cutoff) continue
            if (value <= 0) continue
            if (value > MAX_LEGACY_RESONANCE_SESSION_COUNT) continue // real backfilled minutes

            // Move to secondary (max merge)
            secondary[dateStr] = maxOf(secondary[dateStr] ?: 0, value)
            // Zero out primary
            mutablePrimary[dateStr] = 0
            totalMoved++
        }

        db[habitName] = mutablePrimary
        db[secKey] = secondary
    }

    if (totalMoved > 0) {
        Log.i(TAG, "Resonance secondary migration: moved $totalMoved dates from primary → secondary " +
                "(pre-$cutoff, values ≤ $MAX_LEGACY_RESONANCE_SESSION_COUNT)")
        cachedPhoneDb = db
        habitsRepo.persistDatabase(uri, context, db)
    }

    // Ensure the secondary-value track is enabled so Value 2 (sessions) shows
    // up in the grid/graph — same setup the user has for Meditations.
    if (habitName !in _settings.value.secondaryValueHabits) {
        val updated = _settings.value.secondaryValueHabits + habitName
        _settings.value = _settings.value.copy(secondaryValueHabits = updated)
        settingsRepo.saveSecondaryValueHabits(updated)
    }

    settingsRepo.setResonanceSecondaryMigrationDone()
    Log.i(TAG, "Resonance secondary migration complete.")
}

/**
 * One-time migration to the FIRST-CLASS MINUTES slot (`minutes:<habit>`).
 *
 * Timer-based features (phone bubble, PC widget, trigger apps, media
 * tracking, chess readiness) used to write minutes into the GENERIC
 * secondary-value slot (`secondary_value:<habit>`), which required per-habit
 * setup plus manual "Minutes" display labels. Every habit now has a real
 * minutes slot automatically — no setup, semantically known by the app.
 *
 * For every habit fed by those timer features this moves the
 * `secondary_value:` data into `minutes:` (max-merge), removes the stale
 * legacy settings (secondary-value membership, fallback membership,
 * Sessions/Minutes display labels) and keeps minutes-primary habits
 * minutes-primary. "Good Posture" is additionally switched to
 * minutes-primary (user request, 2026-08-18).
 *
 * Habits that use the generic secondary slot for OTHER data (Meditations/
 * Apnea/Resonance session counts, chess.com games, JugCoach seconds, IMDb
 * ratings) are NOT touched. Idempotent: safe to re-run.
 */


/**
 * One-time migration to the FIRST-CLASS MINUTES slot (`minutes:<habit>`).
 *
 * Timer-based features (phone bubble, PC widget, trigger apps, media
 * tracking, chess readiness) used to write minutes into the GENERIC
 * secondary-value slot (`secondary_value:<habit>`), which required per-habit
 * setup plus manual "Minutes" display labels. Every habit now has a real
 * minutes slot automatically — no setup, semantically known by the app.
 *
 * For every habit fed by those timer features this moves the
 * `secondary_value:` data into `minutes:` (max-merge), removes the stale
 * legacy settings (secondary-value membership, fallback membership,
 * Sessions/Minutes display labels) and keeps minutes-primary habits
 * minutes-primary. "Good Posture" is additionally switched to
 * minutes-primary (user request, 2026-08-18).
 *
 * Habits that use the generic secondary slot for OTHER data (Meditations/
 * Apnea/Resonance session counts, chess.com games, JugCoach seconds, IMDb
 * ratings) are NOT touched. Idempotent: safe to re-run.
 */
internal suspend fun HabitViewModel.performMinutesSlotMigration(uri: Uri) {
    try {
        val s = _settings.value
        val chessLinked = setOf(
            com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
            com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
        ).filter { it.isNotBlank() }
        val timerFed = s.widgetTimerMinutesPrimary +
            s.pcWidgetHabits +
            s.widgetTriggerApps.values.toSet() +
            s.mediaHabits +
            chessLinked +
            setOf("Good Posture")
        val db = cachedPhoneDb.toMutableMap()
        var changed = false
        val secHabits = s.secondaryValueHabits.toMutableSet()
        val fallback = s.secondaryValueFallbackHabits.toMutableSet()
        val labels = s.valueDisplayLabels.toMutableMap()
        val minutesPrimary = s.widgetTimerMinutesPrimary.toMutableSet()
        for (habit in timerFed) {
            val secKey = secondaryValueKey(habit)
            val secData = db[secKey] ?: continue
            val minKey = minutesKey(habit)
            val merged = (db[minKey] ?: emptyMap()).toMutableMap()
            for ((d, v) in secData) merged[d] = maxOf(merged[d] ?: 0, v)
            db[minKey] = merged
            db.remove(secKey)
            changed = true
            secHabits.remove(habit)
            fallback.remove(habit)
            labels.remove(habit)
            if (habit == "Good Posture") minutesPrimary.add(habit)
        }
        if (changed) {
            habitsRepo.saveDatabase(uri, context, db)
            cachedPhoneDb = db
        }
        settingsRepo.saveSecondaryValueHabits(secHabits)
        settingsRepo.saveSecondaryValueFallbackHabits(fallback)
        settingsRepo.saveValueDisplayLabels(labels)
        settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
        _settings.value = _settings.value.copy(
            secondaryValueHabits = secHabits,
            secondaryValueFallbackHabits = fallback,
            valueDisplayLabels = labels,
            widgetTimerMinutesPrimary = minutesPrimary
        )
        settingsRepo.setMinutesSlotMigrationDone()
        Log.i(TAG, "performMinutesSlotMigration: done")
    } catch (e: Exception) {
        Log.e(TAG, "performMinutesSlotMigration failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time initialisation of the per-habit minutes-enabled set
 * ([com.example.tail.data.AppSettings.minutesEnabledHabits]).
 *
 * Before the minutes toggle existed, every habit implicitly had minutes.
 * To keep the effective state identical on day one, the explicit set is
 * seeded with every habit that is connected to a timer feature (PC
 * widget, phone bubble trigger, media tracker), is minutes-primary, or
 * already has data in its `minutes:` slot — minus max-1 habits, which
 * never have minutes. Habits with no minutes data and no timer
 * connection start with minutes OFF (the new default).
 */


/**
 * One-time initialisation of the per-habit minutes-enabled set
 * ([com.example.tail.data.AppSettings.minutesEnabledHabits]).
 *
 * Before the minutes toggle existed, every habit implicitly had minutes.
 * To keep the effective state identical on day one, the explicit set is
 * seeded with every habit that is connected to a timer feature (PC
 * widget, phone bubble trigger, media tracker), is minutes-primary, or
 * already has data in its `minutes:` slot — minus max-1 habits, which
 * never have minutes. Habits with no minutes data and no timer
 * connection start with minutes OFF (the new default).
 */
internal suspend fun HabitViewModel.performMinutesToggleInit() {
    try {
        val s = _settings.value
        val withData = cachedPhoneDb.keys
            .filter { com.example.tail.data.isMinutesKey(it) }
            .mapNotNull { minutesHabitName(it) }
            .filter { !cachedPhoneDb[minutesKey(it)].isNullOrEmpty() }
        val derived = (
            s.widgetTimerMinutesPrimary +
                s.pcWidgetHabits +
                s.widgetTriggerHabits +
                s.mediaHabits +
                s.bridgeMovieHabits +
                withData
            ) - s.maxOneHabits
        settingsRepo.saveMinutesEnabledHabits(derived)
        _settings.value = _settings.value.copy(minutesEnabledHabits = derived)
        settingsRepo.setMinutesToggleInitDone()
        Log.i(TAG, "performMinutesToggleInit: ${derived.size} habits start with minutes enabled")
    } catch (e: Exception) {
        Log.e(TAG, "performMinutesToggleInit failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time backfill: every habit connected to a timer widget (PC widget,
 * phone bubble trigger), a media tracker, the movie bridge, or using
 * minutes as its primary value gets its EXPLICIT minutes toggle turned
 * ON. Runs after [performMinutesToggleInit] so habits connected after
 * the initial seeding are also covered.
 */


/**
 * One-time backfill: every habit connected to a timer widget (PC widget,
 * phone bubble trigger), a media tracker, the movie bridge, or using
 * minutes as its primary value gets its EXPLICIT minutes toggle turned
 * ON. Runs after [performMinutesToggleInit] so habits connected after
 * the initial seeding are also covered.
 */
internal suspend fun HabitViewModel.performMinutesWidgetBackfill() {
    try {
        val s = _settings.value
        val forced = (
            s.pcWidgetHabits +
                s.widgetTriggerHabits +
                s.mediaHabits +
                s.bridgeMovieHabits +
                s.widgetTimerMinutesPrimary
            ) - s.maxOneHabits
        val merged = s.minutesEnabledHabits + forced
        settingsRepo.saveMinutesEnabledHabits(merged)
        _settings.value = _settings.value.copy(minutesEnabledHabits = merged)
        settingsRepo.setMinutesWidgetBackfillDone()
        Log.i(TAG, "performMinutesWidgetBackfill: ${merged.size} habits now have minutes enabled")
    } catch (e: Exception) {
        Log.e(TAG, "performMinutesWidgetBackfill failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time repair for Wags-fed habits wrongly classified as minutes-primary
 * by the Aug-18-2026 minutes-slot rollout.
 *
 * The Wags IPC protocol stores MINUTES in the primary key and SESSIONS in
 * the legacy `secondary_value:` slot. The minutes-primary role instead
 * expects minutes in the first-class `minutes:` slot — which Wags never
 * writes — so points fell back to the raw undivided primary minutes
 * (16 min ÷ divider 10 showed as 16 points) and the sessions metric
 * disappeared from the graph.
 *
 * This restores the pre-breakage Meditations/Resonance pattern for every
 * false minutes-primary habit
 * ([com.example.tail.data.falseMinutesPrimaryHabits]):
 * • minutes stay in the primary key (Value1; the divider applies for
 *   points),
 * • sessions stay in `secondary_value:` (Value2 metric + zero-minutes
 *   fallback for points — preserving the legacy Apnea apb / Apnea spb /
 *   Apnea practiced session history),
 * • any stray `minutes:` slot data is max-merged into the primary key so
 *   nothing entered via the minutes editor is lost.
 */


/**
 * One-time repair for Wags-fed habits wrongly classified as minutes-primary
 * by the Aug-18-2026 minutes-slot rollout.
 *
 * The Wags IPC protocol stores MINUTES in the primary key and SESSIONS in
 * the legacy `secondary_value:` slot. The minutes-primary role instead
 * expects minutes in the first-class `minutes:` slot — which Wags never
 * writes — so points fell back to the raw undivided primary minutes
 * (16 min ÷ divider 10 showed as 16 points) and the sessions metric
 * disappeared from the graph.
 *
 * This restores the pre-breakage Meditations/Resonance pattern for every
 * false minutes-primary habit
 * ([com.example.tail.data.falseMinutesPrimaryHabits]):
 * • minutes stay in the primary key (Value1; the divider applies for
 *   points),
 * • sessions stay in `secondary_value:` (Value2 metric + zero-minutes
 *   fallback for points — preserving the legacy Apnea apb / Apnea spb /
 *   Apnea practiced session history),
 * • any stray `minutes:` slot data is max-merged into the primary key so
 *   nothing entered via the minutes editor is lost.
 */
internal suspend fun HabitViewModel.performWagsMinutesPrimaryRepair(uri: Uri) {
    try {
        val s = _settings.value
        val chessLinked = setOf(
            com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
            com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
        ).filter { it.isNotBlank() }
        val targets = com.example.tail.data.falseMinutesPrimaryHabits(
            widgetTimerMinutesPrimary = s.widgetTimerMinutesPrimary,
            pcWidgetHabits = s.pcWidgetHabits,
            widgetTriggerHabits = s.widgetTriggerHabits,
            mediaHabits = s.mediaHabits,
            bridgeMovieHabits = s.bridgeMovieHabits,
            chessLinked = chessLinked.toSet(),
            db = cachedPhoneDb
        )
        if (targets.isEmpty()) {
            settingsRepo.setWagsMinutesPrimaryRepairDone()
            Log.i(TAG, "performWagsMinutesPrimaryRepair: nothing to repair")
            return
        }
        // Max-merge stray minutes: data into the primary key, then drop
        // the slot so no hand-entered minutes are orphaned.
        val db = cachedPhoneDb.toMutableMap()
        var dbChanged = false
        for (habit in targets) {
            val stray = db[minutesKey(habit)] ?: continue
            val merged = (db[habit] ?: emptyMap()).toMutableMap()
            for ((d, v) in stray) merged[d] = maxOf(merged[d] ?: 0, v)
            db[habit] = merged
            db.remove(minutesKey(habit))
            dbChanged = true
        }
        if (dbChanged) {
            habitsRepo.saveDatabase(uri, context, db)
            cachedPhoneDb = db
        }
        val minutesPrimary = s.widgetTimerMinutesPrimary - targets
        val secHabits = s.secondaryValueHabits + targets
        val fallback = s.secondaryValueFallbackHabits + targets
        val minutesEnabled = s.minutesEnabledHabits - targets
        val primaryFallbacks = s.minutesPrimaryFallbacks - targets
        val labels = s.valueDisplayLabels.toMutableMap()
        for (habit in targets) {
            val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
            if (inner[GRAPH_METRIC_VALUE1].isNullOrBlank()) {
                inner[GRAPH_METRIC_VALUE1] = "minutes"
            }
            if (inner[GRAPH_METRIC_VALUE2].isNullOrBlank()) {
                inner[GRAPH_METRIC_VALUE2] = "sessions"
            }
            labels[habit] = inner
        }
        settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
        settingsRepo.saveSecondaryValueHabits(secHabits)
        settingsRepo.saveSecondaryValueFallbackHabits(fallback)
        settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
        settingsRepo.saveMinutesPrimaryFallbacks(primaryFallbacks)
        settingsRepo.saveValueDisplayLabels(labels)
        _settings.value = _settings.value.copy(
            widgetTimerMinutesPrimary = minutesPrimary,
            secondaryValueHabits = secHabits,
            secondaryValueFallbackHabits = fallback,
            minutesEnabledHabits = minutesEnabled,
            minutesPrimaryFallbacks = primaryFallbacks,
            valueDisplayLabels = labels
        )
        settingsRepo.setWagsMinutesPrimaryRepairDone()
        Log.i(
            TAG,
            "performWagsMinutesPrimaryRepair: repaired ${targets.size} Wags-fed habits: ${targets.sorted()}"
        )
    } catch (e: Exception) {
        Log.e(TAG, "performWagsMinutesPrimaryRepair failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time repair (Aug-23-2026) for habits broken by the graph long-press
 * "Minutes value" migration: that action MOVED the primary-key history
 * into the first-class `minutes:` slot and DELETED the primary key, which
 * blanks the graph for every metric (the graph loader needs the primary
 * key to exist). Most visibly hit Garmin-linked habits — e.g. a Sleep
 * Length habit whose raw minute values live in the Garmin cache while the
 * JSON key only held the derived per-day points.
 *
 * For every broken habit ([com.example.tail.data.brokenMinutesMigrationHabits]):
 * • the migrated `minutes:` data is max-merged back into the primary key
 *   (nothing entered between breakage and repair is lost), then the slot
 *   is dropped;
 * • the minutes-primary and minutes-enabled flags are cleared;
 * • the graph metric selection is pointed back at Value1 so the restored
 *   history (or the live Garmin series) is visible immediately.
 */


/**
 * One-time repair (Aug-23-2026) for habits broken by the graph long-press
 * "Minutes value" migration: that action MOVED the primary-key history
 * into the first-class `minutes:` slot and DELETED the primary key, which
 * blanks the graph for every metric (the graph loader needs the primary
 * key to exist). Most visibly hit Garmin-linked habits — e.g. a Sleep
 * Length habit whose raw minute values live in the Garmin cache while the
 * JSON key only held the derived per-day points.
 *
 * For every broken habit ([com.example.tail.data.brokenMinutesMigrationHabits]):
 * • the migrated `minutes:` data is max-merged back into the primary key
 *   (nothing entered between breakage and repair is lost), then the slot
 *   is dropped;
 * • the minutes-primary and minutes-enabled flags are cleared;
 * • the graph metric selection is pointed back at Value1 so the restored
 *   history (or the live Garmin series) is visible immediately.
 */
internal suspend fun HabitViewModel.performBrokenMinutesMigrationRepair(uri: Uri) {
    try {
        val s = _settings.value
        val chessLinked = setOf(
            com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
            com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
        ).filter { it.isNotBlank() }
        val targets = com.example.tail.data.brokenMinutesMigrationHabits(
            widgetTimerMinutesPrimary = s.widgetTimerMinutesPrimary,
            garminHabitLinks = s.garminHabitLinks,
            pcWidgetHabits = s.pcWidgetHabits,
            widgetTriggerHabits = s.widgetTriggerHabits,
            mediaHabits = s.mediaHabits,
            bridgeMovieHabits = s.bridgeMovieHabits,
            chessLinked = chessLinked.toSet(),
            db = cachedPhoneDb
        )
        if (targets.isEmpty()) {
            settingsRepo.setBrokenMinutesMigrationRepairDone()
            Log.i(TAG, "performBrokenMinutesMigrationRepair: nothing to repair")
            return
        }
        // Max-merge the migrated minutes-slot data back into the primary
        // key, then drop the slot so no data is orphaned.
        val db = cachedPhoneDb.toMutableMap()
        var dbChanged = false
        for (habit in targets) {
            val stray = db[minutesKey(habit)] ?: continue
            val merged = (db[habit] ?: emptyMap()).toMutableMap()
            for ((d, v) in stray) merged[d] = maxOf(merged[d] ?: 0, v)
            db[habit] = merged
            db.remove(minutesKey(habit))
            dbChanged = true
        }
        if (dbChanged) {
            habitsRepo.saveDatabase(uri, context, db)
            cachedPhoneDb = db
        }
        val minutesPrimary = s.widgetTimerMinutesPrimary - targets
        val minutesEnabled = s.minutesEnabledHabits - targets
        settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
        settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
        // Point the graph back at Value1 so the restored history (or the
        // live Garmin series) shows immediately.
        val selection = s.graphMetricSelection.toMutableMap()
        var selectionChanged = false
        for (habit in targets) {
            val metrics = selection[habit]?.toMutableSet() ?: continue
            if (GRAPH_METRIC_MINUTES in metrics) {
                metrics.remove(GRAPH_METRIC_MINUTES)
                metrics.add(GRAPH_METRIC_VALUE1)
                selection[habit] = metrics
                selectionChanged = true
            }
        }
        if (selectionChanged) {
            settingsRepo.saveGraphMetricSelection(selection)
        }
        _settings.value = _settings.value.copy(
            widgetTimerMinutesPrimary = minutesPrimary,
            minutesEnabledHabits = minutesEnabled,
            graphMetricSelection = selection
        )
        settingsRepo.setBrokenMinutesMigrationRepairDone()
        Log.i(
            TAG,
            "performBrokenMinutesMigrationRepair: restored ${targets.size} habits: ${targets.sorted()}"
        )
    } catch (e: Exception) {
        Log.e(TAG, "performBrokenMinutesMigrationRepair failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time migration (Aug-21-2026): converts the five Wags-fed apnea
 * habits ([com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS]) from the
 * legacy Wags layout (minutes = primary value with divider + sessions in
 * the `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY:
 *
 *  • sessions become the PRIMARY value and the sole points source — the
 *    divider and the points fallback are removed, so points = sessions;
 *  • minutes move to the first-class `minutes:` slot with the built-in
 *    minutes value type enabled, so charts keep showing them;
 *  • the secondary-value feature and its legacy slot are dropped.
 *
 * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
 * recorded minutes but no session entry get sessions = 1 so no day loses
 * its done/points status. Runs AFTER [performWagsMinutesPrimaryRepair] so
 * stray minutes-slot data has already been merged into the primary key.
 */


/**
 * One-time migration (Aug-21-2026): converts the five Wags-fed apnea
 * habits ([com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS]) from the
 * legacy Wags layout (minutes = primary value with divider + sessions in
 * the `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY:
 *
 *  • sessions become the PRIMARY value and the sole points source — the
 *    divider and the points fallback are removed, so points = sessions;
 *  • minutes move to the first-class `minutes:` slot with the built-in
 *    minutes value type enabled, so charts keep showing them;
 *  • the secondary-value feature and its legacy slot are dropped.
 *
 * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
 * recorded minutes but no session entry get sessions = 1 so no day loses
 * its done/points status. Runs AFTER [performWagsMinutesPrimaryRepair] so
 * stray minutes-slot data has already been merged into the primary key.
 */
internal suspend fun HabitViewModel.performApneaSessionsPrimaryMigration(uri: Uri) {
    try {
        val targets = com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS
        val swapped = com.example.tail.data.swapToSessionsPrimary(cachedPhoneDb, targets)
        if (swapped != cachedPhoneDb) {
            habitsRepo.saveDatabase(uri, context, swapped)
            cachedPhoneDb = swapped
        }
        val s = _settings.value
        val secHabits = s.secondaryValueHabits - targets
        val fallback = s.secondaryValueFallbackHabits - targets
        val minutesEnabled = s.minutesEnabledHabits + targets
        val minutesPrimary = s.widgetTimerMinutesPrimary - targets
        val mpFallbacks = s.minutesPrimaryFallbacks - targets
        val dividers = s.habitDividers - targets
        val labels = s.valueDisplayLabels.toMutableMap()
        for (habit in targets) {
            val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
            inner[GRAPH_METRIC_VALUE1] = "sessions"
            inner.remove(GRAPH_METRIC_VALUE2)
            labels[habit] = inner
        }
        settingsRepo.saveSecondaryValueHabits(secHabits)
        settingsRepo.saveSecondaryValueFallbackHabits(fallback)
        settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
        settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
        settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
        settingsRepo.saveHabitDividers(dividers)
        settingsRepo.saveValueDisplayLabels(labels)
        _settings.value = s.copy(
            secondaryValueHabits = secHabits,
            secondaryValueFallbackHabits = fallback,
            minutesEnabledHabits = minutesEnabled,
            widgetTimerMinutesPrimary = minutesPrimary,
            minutesPrimaryFallbacks = mpFallbacks,
            habitDividers = dividers,
            valueDisplayLabels = labels
        )
        settingsRepo.setApneaSessionsPrimaryMigrationDone()
        Log.i(
            TAG,
            "performApneaSessionsPrimaryMigration: migrated ${targets.size} apnea habits to sessions-primary: ${targets.sorted()}"
        )
    } catch (e: Exception) {
        Log.e(TAG, "performApneaSessionsPrimaryMigration failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time migration (Aug-22-2026): converts the remaining Wags-fed
 * breathing habits ([com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS]
 * — Meditations, Resonance Breathing, Until Contraction) from the legacy
 * Wags layout (minutes = primary value with divider + sessions in the
 * `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY, the
 * same layout the five apnea habits got on Aug-21-2026:
 *
 *  • sessions become the PRIMARY value and the sole points source — the
 *    divider and the points fallback are removed, so points = sessions;
 *  • minutes move to the first-class `minutes:` slot with the built-in
 *    minutes value type enabled, so charts keep showing them;
 *  • the secondary-value feature and its legacy slot are dropped.
 *
 * "Apnea practiced" is deliberately NOT touched: it keeps its historical
 * meaning (fed by the O2/CO2 Tables conditional links, never incremented
 * on its own) and its legacy secondary data stays readable.
 *
 * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
 * recorded minutes but no session entry get sessions = 1 so no day loses
 * its done/points status. Runs AFTER [performApneaSessionsPrimaryMigration].
 */


/**
 * One-time migration (Aug-22-2026): converts the remaining Wags-fed
 * breathing habits ([com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS]
 * — Meditations, Resonance Breathing, Until Contraction) from the legacy
 * Wags layout (minutes = primary value with divider + sessions in the
 * `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY, the
 * same layout the five apnea habits got on Aug-21-2026:
 *
 *  • sessions become the PRIMARY value and the sole points source — the
 *    divider and the points fallback are removed, so points = sessions;
 *  • minutes move to the first-class `minutes:` slot with the built-in
 *    minutes value type enabled, so charts keep showing them;
 *  • the secondary-value feature and its legacy slot are dropped.
 *
 * "Apnea practiced" is deliberately NOT touched: it keeps its historical
 * meaning (fed by the O2/CO2 Tables conditional links, never incremented
 * on its own) and its legacy secondary data stays readable.
 *
 * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
 * recorded minutes but no session entry get sessions = 1 so no day loses
 * its done/points status. Runs AFTER [performApneaSessionsPrimaryMigration].
 */
internal suspend fun HabitViewModel.performBreathingSessionsPrimaryMigration(uri: Uri) {
    try {
        val targets = com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS
        val swapped = com.example.tail.data.swapToSessionsPrimary(cachedPhoneDb, targets)
        if (swapped != cachedPhoneDb) {
            habitsRepo.saveDatabase(uri, context, swapped)
            cachedPhoneDb = swapped
        }
        val s = _settings.value
        val secHabits = s.secondaryValueHabits - targets
        val fallback = s.secondaryValueFallbackHabits - targets
        val minutesEnabled = s.minutesEnabledHabits + targets
        val minutesPrimary = s.widgetTimerMinutesPrimary - targets
        val mpFallbacks = s.minutesPrimaryFallbacks - targets
        val dividers = s.habitDividers - targets
        val labels = s.valueDisplayLabels.toMutableMap()
        for (habit in targets) {
            val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
            inner[GRAPH_METRIC_VALUE1] = "sessions"
            inner.remove(GRAPH_METRIC_VALUE2)
            labels[habit] = inner
        }
        settingsRepo.saveSecondaryValueHabits(secHabits)
        settingsRepo.saveSecondaryValueFallbackHabits(fallback)
        settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
        settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
        settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
        settingsRepo.saveHabitDividers(dividers)
        settingsRepo.saveValueDisplayLabels(labels)
        _settings.value = s.copy(
            secondaryValueHabits = secHabits,
            secondaryValueFallbackHabits = fallback,
            minutesEnabledHabits = minutesEnabled,
            widgetTimerMinutesPrimary = minutesPrimary,
            minutesPrimaryFallbacks = mpFallbacks,
            habitDividers = dividers,
            valueDisplayLabels = labels
        )
        settingsRepo.setBreathingSessionsPrimaryMigrationDone()
        Log.i(
            TAG,
            "performBreathingSessionsPrimaryMigration: migrated ${targets.size} breathing habits to sessions-primary: ${targets.sorted()}"
        )
    } catch (e: Exception) {
        Log.e(TAG, "performBreathingSessionsPrimaryMigration failed (will retry next load): ${e.message}")
    }
}

/**
 * One-time cleanup: the chess.com sync used to record one timestamp per
 * MINUTE played (the minutes delta was passed to addTimestamps). Trims
 * each chess.com-linked habit's daily timestamp lists down to that day's
 * game count — one timestamp per game — keeping the earliest entries.
 */


/**
 * One-time cleanup: the chess.com sync used to record one timestamp per
 * MINUTE played (the minutes delta was passed to addTimestamps). Trims
 * each chess.com-linked habit's daily timestamp lists down to that day's
 * game count — one timestamp per game — keeping the earliest entries.
 */
internal suspend fun HabitViewModel.performChessTimestampTrim() {
    try {
        val s = _settings.value
        if (s.chessComHabitLinks.isNotEmpty()) {
            val all = timestampRepo.loadAll()
            for (habitName in s.chessComHabitLinks.keys) {
                val days = all[habitName] ?: continue
                val gamesEntries = cachedPhoneDb[secondaryValueKey(habitName)] ?: continue
                for ((dateStr, stamps) in days) {
                    val games = gamesEntries[dateStr] ?: 0
                    if (games > 0 && stamps.size > games) {
                        val date = com.example.tail.data.parseDate(dateStr) ?: continue
                        timestampRepo.setTimestampsForDay(habitName, date, stamps.take(games))
                    }
                }
            }
        }
        settingsRepo.setChessTimestampsTrimDone()
        Log.i(TAG, "performChessTimestampTrim: done")
    } catch (e: Exception) {
        Log.e(TAG, "performChessTimestampTrim failed (will retry next load): ${e.message}")
    }
}
