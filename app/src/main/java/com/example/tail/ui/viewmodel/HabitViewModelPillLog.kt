package com.example.tail.ui.viewmodel

// Pill-log habit type (count-coded dosing, "Took pills") — ViewModel layer.
// Split out of HabitViewModel.kt following the per-feature file convention.

import androidx.lifecycle.viewModelScope
import com.example.tail.data.DEFAULT_WATER_DAILY_GOAL
import com.example.tail.data.HabitsLoadResult
import com.example.tail.data.HabitsRepository
import com.example.tail.data.PillDayDoses
import com.example.tail.data.PillDoseRepository
import com.example.tail.data.PillLogParser
import com.example.tail.data.TextInputRepository
import com.example.tail.data.suggest.PillSuggester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The habit that the one-time migration converts to the pill-log type. */
const val PILL_LOG_SOURCE_HABIT = "Took pills"

/**
 * Pill-log type: tapping opens the pill dialog; each confirmed line appends
 * verbatim to the existing text log (one line = one serving, repetitions are
 * deliberate re-dosing) and increments the habit total by the line count.
 * Structured servings/doses are recomputed per day into the sidecar.
 */
fun HabitViewModel.savePillLines(
    habitName: String,
    date: LocalDate,
    lines: List<String>
) {
    val clean = lines.map { it.trim() }.filter { it.isNotEmpty() }
    if (clean.isEmpty()) return
    val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
    viewModelScope.launch {
        try {
            val now = LocalDateTime.now()
            val entryClock = now.toLocalTime()
            val stampTime = entryClock.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            // Raw free-text stays verbatim: one log value per line.
            textInputRepo.appendMultipleTextEntries(
                uri, context, clean,
                date = if (date == LocalDate.now()) null else date,
                time = if (date == LocalDate.now()) entryClock else null,
                habitName = habitName
            )
            // A whole pill submission is ONE habit event: all lines share the
            // single timestamp and the habit gains exactly 1 point. Pill
            // counts/doses live in the structured sidecar, not the points.
            incrementHabit(
                habitName, 1,
                recordTimestamp = true,
                date = date,
                stampTime = stampTime
            )
            // Recompute the day's structured doses from ALL of the day's lines.
            recomputePillDayInternal(habitName, date)
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save pill entry: ${e.message}"
        }
    }
}

/** Live-parses the day's raw lines against the current catalog (self-healing). */
fun HabitViewModel.loadPillDay(
    habitName: String,
    date: LocalDate,
    onResult: (PillDayDoses) -> Unit
) {
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
            val log = textInputRepo.loadTextLog(uri, context, habitName)
            val dayPrefix = date.toString()
            val lines = log.entries
                .filter { it.key.startsWith(dayPrefix) }
                .sortedBy { it.key }
                .flatMap { PillLogParser.splitLines(it.value) }
            PillLogParser.parseDay(lines, effectivePillCatalog())
        }
        onResult(result)
    }
}

/** Catalog = persisted user settings overlaid on the defaults. */
fun HabitViewModel.effectivePillCatalog(): Map<String, String> =
    PillLogParser.DEFAULT_CATALOG +
        _settings.value.pillCatalog.filterKeys { it !in PillLogParser.LEGACY_REMOVED_CATALOG_KEYS }

/**
 * Pill picker options: catalog pill names ordered by historical usage
 * (most-likely-first from the raw log), never-used catalog entries appended
 * alphabetically after.
 */
fun HabitViewModel.loadPillOptions(
    habitName: String,
    onResult: (List<String>, Map<String, String>) -> Unit
) {
    viewModelScope.launch {
        val catalog = effectivePillCatalog()
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val used = textInputRepo.loadUniqueOptions(uri, context, habitName)
            .flatMap { PillLogParser.splitLines(it) }
        val ordered = buildList {
            for (line in used) {
                catalog.keys.firstOrNull { it == line }?.let { pill ->
                    if (pill !in this) add(pill)
                }
            }
            for (pill in catalog.keys.sorted()) {
                if (pill !in this) add(pill)
            }
        }
        onResult(ordered, catalog)
    }
}

/** One likely-next suggestion: a past grouping/line with per-pill counts. */
data class PillSuggestion(
    /** Concise chip label, e.g. "Omega ×2 · Creatine". */
    val label: String,
    /** Pill line → serving count in this grouping. */
    val counts: Map<String, Int>
)

/**
 * Most-likely-next suggestions for the pill dialog, grounded in the CURRENT
 * catalog and driven by recent co-taken groupings (see [buildPillSuggestions]).
 */
fun HabitViewModel.loadPillSuggestions(
    habitName: String,
    onResult: (List<PillSuggestion>) -> Unit
) {
    viewModelScope.launch {
        val catalog = effectivePillCatalog()
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val log = textInputRepo.loadTextLog(uri, context, habitName)
        onResult(buildPillSuggestions(log, catalog, LocalTime.now(), LocalDate.now()))
    }
}

/**
 * Builds likely-next pill suggestions from the raw text log. Pure and
 * testable ([loadPillSuggestions] just injects now/catalog). The model lives
 * in [PillSuggester]: a PER-PILL probability over comparable past intakes
 * (weighted by recency, time of day, weekday and "same nth intake of the
 * day"), conditioned on how many servings of each pill were already taken
 * today — so the first chip is the consensus bundle for THIS moment, not
 * the most recent exact grouping.
 */
internal fun buildPillSuggestions(
    log: Map<String, String>,
    catalog: Map<String, String>,
    now: LocalTime,
    today: LocalDate
): List<PillSuggestion> =
    PillSuggester.suggest(log, catalog, now.hour * 60 + now.minute, today, maxResults = 6)
        .map { bundle ->
            val label = bundle.counts.entries.joinToString(" · ") { (line, n) ->
                if (n > 1) "$line ×$n" else line
            }
            PillSuggestion(label = label, counts = bundle.counts)
        }

/** Adds a new pill to the persisted catalog (user catalog wins over defaults). */
fun HabitViewModel.addPillToCatalog(name: String, description: String, onDone: () -> Unit = {}) {
    viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        val catalog = _settings.value.pillCatalog + (clean to description.trim())
        settingsRepo.savePillCatalog(catalog)
        _settings.value = _settings.value.copy(pillCatalog = catalog)
        onDone()
    }
}

/**
 * Edits an existing catalog pill (long-press → edit in the pill dialog).
 *
 *  - Description: always editable; the persisted user catalog overlays the
 *    defaults, so this also customises default pills.
 *  - Rename: only for USER-ADDED pills (default catalog keys can't be removed
 *    from the shipped map, so renaming them would duplicate the entry; the
 *    description override still applies). History is NOT rewritten — the
 *    parser's containment matching keeps old lines resolving to the pill.
 */
fun HabitViewModel.updatePillInCatalog(
    oldName: String,
    newName: String,
    description: String,
    onDone: () -> Unit = {}
) {
    viewModelScope.launch {
        val cleanNew = newName.trim()
        val cleanOld = oldName.trim()
        if (cleanOld.isEmpty()) return@launch
        val rename = cleanNew.isNotEmpty() && cleanNew != cleanOld &&
            cleanOld !in PillLogParser.DEFAULT_CATALOG
        var catalog = _settings.value.pillCatalog
        if (rename) catalog = catalog - cleanOld
        val key = if (rename) cleanNew else cleanOld
        catalog = catalog + (key to description.trim())
        settingsRepo.savePillCatalog(catalog)
        _settings.value = _settings.value.copy(pillCatalog = catalog)
        onDone()
    }
}

private fun HabitViewModel.recomputePillDayInternal(habitName: String, date: LocalDate) {
    viewModelScope.launch(Dispatchers.IO) {
        val repo = PillDoseRepository()
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val log = textInputRepo.loadTextLog(uri, context, habitName)
        val dayPrefix = date.toString()
        val lines = log.entries
            .filter { it.key.startsWith(dayPrefix) }
            .flatMap { PillLogParser.splitLines(it.value) }
        repo.recomputeDay(context, habitName, date, lines, effectivePillCatalog())
    }
}

/**
 * Toggles the pill-log type for a habit. Enabling implies the text-input
 * storage layer (the raw log format is unchanged, byte-identical history);
 * disabling only removes the pill-log flag, keeping the text log intact.
 */
fun HabitViewModel.togglePillLog(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.pillLogHabits.toMutableSet()
        var textInput = _settings.value.textInputHabits
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
            if (habitName !in textInput) textInput = textInput + habitName
        }
        settingsRepo.savePillLogHabits(current)
        if (textInput != _settings.value.textInputHabits) settingsRepo.saveTextInputHabits(textInput)
        _settings.value = _settings.value.copy(
            pillLogHabits = current,
            textInputHabits = textInput
        )
    }
}

/**
 * One-time migration (flag: migration_pill_log_done):
 *  1. Converts the "Took pills" habit to the pill-log type (adds it to
 *     pillLogHabits; keeps/ensures its textInput membership — the raw log
 *     and all existing "Pill: X" presence data stay byte-identical).
 *  2. Seeds the pill catalog (defaults merged under user edits).
 *  3. Backfills the structured per-day dose sidecar over the ENTIRE
 *     historical text log — identical values to live parsing by construction.
 */
fun HabitViewModel.runPillLogMigration() {
    viewModelScope.launch {
        try {
            if (settingsRepo.isPillLogMigrationDone()) return@launch

            // Read persisted settings FRESH — at init time _settings may still
            // hold defaults (the first v1 run no-opped because of this race).
            val s = settingsRepo.settingsFlow.first()
            val pillLog = s.pillLogHabits.toMutableSet()
            var textInput = s.textInputHabits
            var changedSettings = false

            if (PILL_LOG_SOURCE_HABIT in textInput && PILL_LOG_SOURCE_HABIT !in pillLog) {
                pillLog.add(PILL_LOG_SOURCE_HABIT)
                changedSettings = true
            }

            // Seed catalog: defaults win for keys the user hasn't set; legacy
            // duplicate/standalone-only keys seeded by earlier versions are
            // scrubbed (user-added custom pills survive).
            val catalog = PillLogParser.DEFAULT_CATALOG +
                s.pillCatalog.filterKeys { it !in PillLogParser.LEGACY_REMOVED_CATALOG_KEYS }
            if (catalog != s.pillCatalog) changedSettings = true

            if (changedSettings) {
                settingsRepo.savePillLogHabits(pillLog)
                settingsRepo.savePillCatalog(catalog)
                _settings.value = _settings.value.copy(
                    pillLogHabits = pillLog,
                    pillCatalog = catalog
                )
            }

            // Backfill structured doses for every pill-log habit with a log.
            val repo = PillDoseRepository()
            for (habitName in pillLog) {
                val uri = TextInputRepository.textUriOrNull(s.textInputFileUris[habitName])
                val log = textInputRepo.loadTextLog(uri, context, habitName)
                if (log.isNotEmpty()) {
                    val days = repo.backfillFromTextLog(context, habitName, log, catalog)
                    android.util.Log.i("PillLogMigration", "Backfilled $days days for '$habitName'")
                }
            }

            settingsRepo.markPillLogMigrationDone()
            android.util.Log.i("PillLogMigration", "Pill log migration complete")
        } catch (e: Exception) {
            android.util.Log.e("PillLogMigration", "Migration failed (will retry next launch)", e)
        }
    }
}

/**
 * Toggles the "water" habit type for [habitName]. Water habits behave
 * exactly like normal habits; the type adds the daily-goal setting that
 * drives the scene widget's depletion glass. When the type is switched
 * on, a goal of [DEFAULT_WATER_DAILY_GOAL] ml is seeded unless the habit
 * already has one (e.g. from a previous enable), so the edit panel and
 * widget immediately show a sensible value.
 */
fun HabitViewModel.toggleWaterHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.waterHabits.toMutableSet()
        val goals = _settings.value.waterDailyGoals
        var newGoals = goals
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
            if (habitName !in goals || goals[habitName]!! <= 0) {
                newGoals = goals + (habitName to DEFAULT_WATER_DAILY_GOAL)
            }
        }
        settingsRepo.saveWaterHabits(current)
        if (newGoals != goals) settingsRepo.saveWaterDailyGoals(newGoals)
        _settings.value = _settings.value.copy(
            waterHabits = current,
            waterDailyGoals = newGoals
        )
    }
}

/**
 * Sets the daily drinking goal (ml) for water-type habit [habitName].
 * Clamped to a sane 1..20000 ml range; the widget's glass re-renders on
 * the next refresh (goal changes usually come through the edit panel,
 * whose save path already triggers AppHooks.refreshWidgets via settings).
 */
fun HabitViewModel.setWaterDailyGoal(habitName: String, goalMl: Int) {
    viewModelScope.launch {
        val clamped = goalMl.coerceIn(1, 20000)
        val goals = _settings.value.waterDailyGoals + (habitName to clamped)
        settingsRepo.saveWaterDailyGoals(goals)
        _settings.value = _settings.value.copy(waterDailyGoals = goals)
    }
}

/**
 * One-time migration (flag: migration_water_done): auto-enables the
 * water type on the legacy **"Water"** habit — the user's existing ml
 * habit — with the [DEFAULT_WATER_DAILY_GOAL] (3500 ml) goal, so the
 * widget's depletion glass works out of the box without hunting for the
 * toggle. A habit literally named "Water" (case-insensitive) is a
 * member too. The toggle stays fully reversible in the edit panel.
 */
fun HabitViewModel.runWaterHabitMigration() {
    viewModelScope.launch {
        try {
            if (settingsRepo.isWaterMigrationDone()) return@launch

            // Read persisted settings FRESH — at init time _settings may
            // still hold defaults (the pill-log v1 migration hit this).
            val s = settingsRepo.settingsFlow.first()
            val water = s.waterHabits.toMutableSet()
            val goals = s.waterDailyGoals

            var legacy = s.habitScreens.flatMap { it.habitNames }
                .plus(s.habitOrder)
                .map { it.trim() }
                .firstOrNull { it.equals("Water", ignoreCase = true) }
            if (legacy == null && s.fileUri.isNotEmpty()) {
                // Fallback: the settings lists can lag; the habits DB is
                // the authoritative habit-name source.
                try {
                    val result = HabitsRepository().loadDatabaseResult(
                        android.net.Uri.parse(s.fileUri), context
                    )
                    val db = (result as? HabitsLoadResult.Success)?.db
                    legacy = db?.keys?.firstOrNull { it.equals("Water", ignoreCase = true) }
                } catch (e: Exception) {
                    android.util.Log.w("WaterMigration", "DB scan failed: ${e.message}")
                }
            }
            if (legacy != null && legacy !in water) {
                water.add(legacy)
                settingsRepo.saveWaterHabits(water)
                if (legacy !in goals || goals[legacy]!! <= 0) {
                    settingsRepo.saveWaterDailyGoals(goals + (legacy to DEFAULT_WATER_DAILY_GOAL))
                }
                // Refresh the widgets NOW so the depletion glass appears
                // immediately (not just on the next increment).
                try {
                    com.example.tail.data.AppHooks.refreshWidgets?.invoke(context)
                } catch (_: Exception) {
                }
                android.util.Log.i("WaterMigration", "Water type enabled on '$legacy' (goal 3500 ml)")
            }

            settingsRepo.markWaterMigrationDone()
        } catch (e: Exception) {
            android.util.Log.e("WaterMigration", "Migration failed (will retry next launch)", e)
        }
    }
}
