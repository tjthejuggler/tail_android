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
 * testable ([loadPillSuggestions] just injects now/catalog).
 *
 *  1. CLUSTERING — entries are sorted chronologically and consecutive
 *     entries less than [PILL_CLUSTER_GAP_SECONDS] apart (same day) form ONE
 *     "taken together" occurrence. This is essential: the storage layer
 *     writes each pill line of one submission as its own entry offset by
 *     +1s, so a 4-pill submit is four 1-second-apart entries. Without
 *     clustering every suggestion degenerates to a single pill; with it,
 *     the bundle you take every morning surfaces as ONE chip.
 *     Deliberately separate doses (minutes apart) stay separate.
 *  2. CANONICALISATION — every line is matched through
 *     [PillLogParser.matchPill]; unknown lines are dropped, and identical
 *     canonical groupings merge (pooling their score).
 *  3. SCORE = recency-weighted occurrences (≈4-day half-life) + a
 *     rough-period time-of-day affinity (±3.5 h circular) + a small boost
 *     for groupings that extend what was already logged today (a partially
 *     taken bundle ranks up so one tap completes it).
 *  4. ALREADY-TAKEN FILTER — a grouping whose pills are (count-aware)
 *     majority-satisfied by today's log is NOT suggested: you rarely take
 *     the same bundle twice in a day, so suggesting it again is noise.
 *     Fully-taken groupings and fully-taken singles disappear too.
 */
internal fun buildPillSuggestions(
    log: Map<String, String>,
    catalog: Map<String, String>,
    now: LocalTime,
    today: LocalDate
): List<PillSuggestion> {
    val nowMinutes = now.hour * 60 + now.minute
    val tsFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class Occ(val minutesOfDay: Int, val daysAgo: Long)

    // Chronological entries — the log map's iteration order is NOT guaranteed.
    val entries = log.entries
        .mapNotNull { (ts, text) ->
            val dt = runCatching { LocalDateTime.parse(ts, tsFmt) }.getOrNull()
            if (dt == null) null else dt to text
        }
        .sortedBy { it.first }

    // grouping key (canonical, sorted, counts baked in) → occurrences
    val byGrouping = LinkedHashMap<String, Pair<Map<String, Int>, MutableList<Occ>>>()
    // canonical pill → servings already logged today (filter + boost context)
    val todayCounts = mutableMapOf<String, Int>()

    // Current time-proximity cluster being accumulated.
    var clusterCounts = LinkedHashMap<String, Int>()
    var clusterStart: LocalDateTime? = null
    var prev: LocalDateTime? = null

    fun flushCluster() {
        val start = clusterStart
        val counts = clusterCounts
        if (start != null && counts.isNotEmpty()) {
            if (start.toLocalDate() == today) {
                // Today's clusters don't score — they mark what's ALREADY
                // taken (and drive the already-taken filter below).
                for ((pill, n) in counts) todayCounts[pill] = (todayCounts[pill] ?: 0) + n
            } else {
                val key = counts.entries.sortedBy { it.key }.joinToString("|") { "${it.key}×${it.value}" }
                val daysAgo = java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), today)
                byGrouping.getOrPut(key) { counts to mutableListOf() }.second
                    .add(Occ(start.hour * 60 + start.minute, daysAgo))
            }
        }
        clusterCounts = LinkedHashMap()
        clusterStart = null
    }

    for ((dt, text) in entries) {
        if (dt.toLocalDate().isAfter(today)) continue // future keys are ignored
        val p = prev
        if (clusterStart != null && p != null &&
            (p.toLocalDate() != dt.toLocalDate() ||
                java.time.temporal.ChronoUnit.SECONDS.between(p, dt) > PILL_CLUSTER_GAP_SECONDS)
        ) {
            flushCluster()
        }
        if (clusterStart == null) clusterStart = dt
        for (line in PillLogParser.splitLines(text)) {
            // Canonicalise to the current catalog; drop unknown pills.
            val pill = PillLogParser.matchPill(line, catalog) ?: continue
            clusterCounts[pill] = (clusterCounts[pill] ?: 0) + 1
        }
        prev = dt
    }
    flushCluster()

    data class Scored(val counts: Map<String, Int>, val score: Double)
    return byGrouping.map { (_, pair) ->
        val (counts, occs) = pair
        var score = 0.0
        var windowed = 0.0
        for (o in occs) {
            val w = maxOf(Math.exp(-o.daysAgo / PILL_HALF_LIFE_DAYS), PILL_FLOOR_WEIGHT)
            score += w
            var dist = kotlin.math.abs(nowMinutes - o.minutesOfDay)
            if (dist > 720) dist = 1440 - dist // circular across midnight
            if (dist <= PILL_TIME_WINDOW_MINUTES) {
                windowed += w * (1.0 - dist.toDouble() / PILL_TIME_WINDOW_MINUTES)
            }
        }
        val timeAffinity = (windowed / occs.size).coerceIn(0.0, 1.0)
        // Rough-period affinity: a modest nudge, recency stays the driver.
        score += PILL_TIME_WEIGHT * timeAffinity
        // Groupings extending what was already taken today rank higher
        // (only minority-overlap ones survive the filter below).
        val overlap = todayCounts.keys.count { it in counts }
        if (overlap > 0) score *= 1.0 + PILL_TODAY_OVERLAP_BOOST * overlap
        Scored(counts, score)
    }
        // Already-taken filter: a pill counts as satisfied when today's
        // logged servings already meet the grouping's count for it. A
        // grouping with a strict MAJORITY of satisfied pills (which
        // includes all-taken, and single pills taken once) is dropped.
        .filter { scored ->
            val satisfied = scored.counts.entries
                .count { (pill, n) -> (todayCounts[pill] ?: 0) >= n }
            satisfied * 2 <= scored.counts.size
        }
        .sortedWith(compareByDescending<Scored> { it.score })
        .take(8)
        .map { scored ->
            val label = scored.counts.entries.joinToString(" · ") { (line, n) ->
                if (n > 1) "$line ×$n" else line
            }
            PillSuggestion(label = label, counts = scored.counts)
        }
}

/** Pill-specific recency decay: regimen from the last few days dominates. */
private const val PILL_HALF_LIFE_DAYS = 4.0

/** Small constant mass for pre-history groupings: listed, never on top. */
private const val PILL_FLOOR_WEIGHT = 0.02

/** Rough-period time-of-day window half-width (±3.5 h, circular). */
private const val PILL_TIME_WINDOW_MINUTES = 210

/** Contribution multiplier of the time-of-day affinity term. */
private const val PILL_TIME_WEIGHT = 0.6

/** Per-shared-pill score multiplier for groupings extending today's log. */
private const val PILL_TODAY_OVERLAP_BOOST = 0.5

/**
 * Max gap between consecutive log entries treated as ONE "taken together"
 * submission. Dialog submissions land +1s apart; anything minutes apart is
 * a deliberately separate dose.
 */
private const val PILL_CLUSTER_GAP_SECONDS = 120L

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
