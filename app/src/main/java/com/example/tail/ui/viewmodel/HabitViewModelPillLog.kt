package com.example.tail.ui.viewmodel

// Pill-log habit type (count-coded dosing, "Took pills") — ViewModel layer.
// Split out of HabitViewModel.kt following the per-feature file convention.

import androidx.lifecycle.viewModelScope
import com.example.tail.data.PillDayDoses
import com.example.tail.data.PillDoseRepository
import com.example.tail.data.PillLogParser
import com.example.tail.data.TextInputRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
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
            // One increment per pill line (count-coded semantics).
            incrementHabit(
                habitName, clean.size,
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
    PillLogParser.DEFAULT_CATALOG + _settings.value.pillCatalog

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
 * Most-likely-next suggestions for the pill dialog: the habit's past log
 * entries (single lines AND multi-line groupings taken together) ranked by
 * the shared TextOptionScorer — recency-weighted frequency plus time-of-day
 * affinity — exactly like the old text-input options picker.
 */
fun HabitViewModel.loadPillSuggestions(
    habitName: String,
    onResult: (List<PillSuggestion>) -> Unit
) {
    viewModelScope.launch {
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val log = textInputRepo.loadTextLog(uri, context, habitName)
        val suggestions = com.example.tail.data.TextOptionScorer
            .orderOptions(log, java.time.LocalTime.now())
            .take(8)
            .map { option ->
                val counts = LinkedHashMap<String, Int>()
                for (line in PillLogParser.splitLines(option)) {
                    counts[line] = (counts[line] ?: 0) + 1
                }
                val label = counts.entries.joinToString(" · ") { (line, n) ->
                    if (n > 1) "$line ×$n" else line
                }
                PillSuggestion(label = label, counts = counts)
            }
        onResult(suggestions)
    }
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

            // Seed catalog: defaults win only for keys the user hasn't set.
            val catalog = PillLogParser.DEFAULT_CATALOG + s.pillCatalog
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
