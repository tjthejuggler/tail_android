package com.example.tail.ui.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.runBlocking
import com.example.tail.data.MINUTES_PER_DAY
import com.example.tail.data.health.SleepDataRepository
import com.example.tail.data.health.SleepRecord
import com.example.tail.data.SLEEP_VARIANT_SLEEP_TIME
import com.example.tail.data.SLEEP_VARIANT_WAKE_TIME
import com.example.tail.data.dateString
import com.example.tail.data.isSleepVariantKey
import com.example.tail.data.sleepDurationMinutes
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val TAG = "SleepVM"

// ── Sleep habit type (sleep-time / wake-time suite) ─────────────────────────

/** Bed-half accumulator used while pairing sleep records into sessions. */
internal data class SleepBedHalf(val bed: Int?, val temp: Int?, val cond: String?)

/** Wake-half accumulator used while pairing sleep records into sessions. */
internal data class SleepWakeHalf(
    val wake: Int?,
    val aw: Int?,
    val awake: Int?,
    val q: Int?
)

/** Returns true if [habitName] has the "sleep" type enabled. */
fun HabitViewModel.isSleepHabit(habitName: String): Boolean =
    habitName in _settings.value.sleepHabits

/** All sleep-suite habit names currently configured (both variants). */
fun HabitViewModel.allSleepHabitNames(): List<String> =
    _settings.value.sleepHabits.toList()

/**
 * Returns the variant key for sleep habit [habitName]
 * ([SLEEP_VARIANT_SLEEP_TIME] or [SLEEP_VARIANT_WAKE_TIME]), or null when the
 * habit is not a sleep habit (or has no variant set).
 */
fun HabitViewModel.sleepVariantOf(habitName: String): String? {
    if (!isSleepHabit(habitName)) return null
    return _settings.value.sleepHabitVariants[habitName]
}

/**
 * Enables/disables/switches the sleep type for [habitName].
 * [variant] = [SLEEP_VARIANT_SLEEP_TIME] or [SLEEP_VARIANT_WAKE_TIME] enables
 * (or switches) the type; null disables it entirely.
 */
fun HabitViewModel.setSleepVariant(habitName: String, variant: String?) {
    viewModelScope.launch {
        val s = _settings.value
        val habits = s.sleepHabits.toMutableSet()
        val variants = s.sleepHabitVariants.toMutableMap()
        if (variant == null || !isSleepVariantKey(variant)) {
            habits.remove(habitName)
            variants.remove(habitName)
        } else {
            habits.add(habitName)
            variants[habitName] = variant
        }
        settingsRepo.saveSleepHabits(habits)
        settingsRepo.saveSleepHabitVariants(variants)
        _settings.value = s.copy(sleepHabits = habits, sleepHabitVariants = variants)
    }
}

/**
 * Loads the stored sleep record for [habitName] on [dateStr], then calls
 * [onLoaded] (always, with an empty record when none exists). Used by the
 * sleep dialog to pre-fill the current values.
 */
fun HabitViewModel.loadSleepRecord(
    habitName: String,
    dateStr: String,
    onLoaded: (SleepRecord) -> Unit
) {
    viewModelScope.launch {
        onLoaded(sleepDataRepo.getRecord(habitName, dateStr))
    }
}

/**
 * Loads the distinct past sleep-conditions strings for [habitName], most
 * recent first, then calls [onLoaded]. Powers the "options from past inputs"
 * suggestion chips in the sleep-time dialog.
 */
fun HabitViewModel.loadSleepConditionsHistory(
    habitName: String,
    onLoaded: (List<String>) -> Unit
) {
    viewModelScope.launch {
        onLoaded(sleepDataRepo.loadConditionsHistory(habitName))
    }
}

/**
 * Saves the SLEEP half of a night's record: the bed times entered in THIS
 * dialog session are APPENDED to the times already stored for the date
 * (deduped), supporting naps and post-midnight bedtimes. Temperature and
 * conditions overwrite only when provided. Increments the habit count by 1 —
 * but only when the bed half was previously unset for that date, so
 * re-editing tonight's bedtime never inflates streaks.
 *
 * @param stampTime "HH:mm:ss" matching the first bed time, so the recorded
 *                  timestamp aligns with the timeline graph.
 */
fun HabitViewModel.saveSleepTimeEntry(
    habitName: String,
    bedTimes: List<Int>,
    tempTenths: Int?,
    conditions: String?,
    date: LocalDate,
    stampTime: String
) {
    viewModelScope.launch {
        try {
            val dateStr = dateString(date)
            val existing = sleepDataRepo.getRecord(habitName, dateStr)
            val firstEntry = existing.bedTimes().isEmpty()
            val merged = (existing.bedTimes() + bedTimes).distinct()
            sleepDataRepo.mergeRecord(
                habitName, dateStr,
                SleepRecord(
                    beds = merged,
                    bed = merged.firstOrNull(),
                    temp = tempTenths,
                    conditions = conditions?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
            if (firstEntry) {
                incrementHabit(habitName, 1, date = date, stampTime = stampTime)
            }
        } catch (e: Exception) {
            Log.w(TAG, "saveSleepTimeEntry failed: ${e.message}")
            _errorMessage.value = "Failed to save sleep entry: ${e.message}"
        }
    }
}

/**
 * Saves the WAKE half of a night's record: the wake times entered in THIS
 * dialog session are APPENDED to the times already stored for the date
 * (deduped, one per sleep segment), plus the mini-survey answers
 * (awakenings count, minutes awake, perceived quality 1–10). Increments the
 * habit count by 1 — only when the wake half was previously unset for that
 * date (re-edits don't re-count).
 */
fun HabitViewModel.saveWakeSurveyEntry(
    habitName: String,
    wakeTimes: List<Int>,
    awakenings: Int?,
    awakeMin: Int?,
    quality: Int?,
    date: LocalDate,
    stampTime: String
) {
    viewModelScope.launch {
        try {
            val dateStr = dateString(date)
            val existing = sleepDataRepo.getRecord(habitName, dateStr)
            val firstEntry = existing.wakeTimes().isEmpty()
            val merged = (existing.wakeTimes() + wakeTimes).distinct()
            sleepDataRepo.mergeRecord(
                habitName, dateStr,
                SleepRecord(
                    wakes = merged,
                    wake = merged.lastOrNull(),
                    awakenings = awakenings,
                    awakeMin = awakeMin,
                    quality = quality
                )
            )
            if (firstEntry) {
                incrementHabit(habitName, 1, date = date, stampTime = stampTime)
            }
        } catch (e: Exception) {
            Log.w(TAG, "saveWakeSurveyEntry failed: ${e.message}")
            _errorMessage.value = "Failed to save wake entry: ${e.message}"
        }
    }
}

// ── Timeline graph data ─────────────────────────────────────────────────────

/** One paired bed→wake segment of a night. */
data class SleepSegment(
    /** Bed time — minutes since midnight of [bedDate]. */
    val bed: Int,
    /** The date key of the bed entry. */
    val bedDate: LocalDate,
    /** Wake time — minutes since midnight of [wakeDate]. */
    val wake: Int,
    /** The date key of the wake entry. */
    val wakeDate: LocalDate,
    /** Segment duration in minutes (midnight-wrapping). */
    val durationMin: Int
)

/**
 * One reconstructed sleep session (night) for the timeline graph. The session
 * date is the FIRST bed entry's date. Any number of bed/wake pairs (naps,
 * split nights, post-midnight bedtimes) are paired CHRONOLOGICALLY — each
 * wake matches the most recent bed time before it — and aggregated here:
 * [durationMin] is the SUM of all segment durations, [bed]/[wake] show the
 * first bed and last wake, and [segments] carries the individual pairs.
 */
data class SleepSession(
    /** Session date = the date key of the first bed entry. */
    val date: LocalDate,
    /** First bed time — minutes since midnight of [date]. Null = bed not logged. */
    val bed: Int?,
    /** Last wake time — minutes since midnight of [wakeDate]. Null = wake not logged. */
    val wake: Int?,
    /** The date key the last matched wake record came from. */
    val wakeDate: LocalDate?,
    /** TOTAL sleep duration in minutes (sum across all segments), when any wake exists. */
    val durationMin: Int?,
    /** Room temperature in tenths of °C. */
    val tempTenths: Int?,
    /** Free-text conditions recorded at bedtime. */
    val conditions: String?,
    /** Mini-survey: number of awakenings. */
    val awakenings: Int?,
    /** Mini-survey: total minutes awake during the night. */
    val awakeMin: Int?,
    /** Mini-survey: perceived overall sleep quality 1–10. */
    val quality: Int?,
    /** The individual bed→wake segments, chronological. Empty when no wake is paired yet. */
    val segments: List<SleepSegment> = emptyList()
)

/** One bed-time event, resolved to an absolute minute (epochDay × 1440 + minute). */
internal class SleepBedEvent(
    val absMin: Long,
    val date: LocalDate,
    val minutes: Int,
    val half: SleepBedHalf
)

/** One wake-time event, resolved to an absolute minute (epochDay × 1440 + minute). */
internal class SleepWakeEvent(
    val absMin: Long,
    val date: LocalDate,
    val minutes: Int,
    val half: SleepWakeHalf
)

/** Aggregates all bed→wake segments whose BED entry falls on one date. */
internal class SleepNightGroup(val date: LocalDate) {
    val segments = mutableListOf<SleepSegment>()
    var firstBed: Int? = null
    var temp: Int? = null
    var cond: String? = null
    var wakeHalf: SleepWakeHalf? = null
    var lastWakeDate: LocalDate? = null

    fun seedBed(min: Int, half: SleepBedHalf) {
        if (firstBed == null) {
            firstBed = min
            temp = half.temp
            cond = half.cond
        }
    }
}

/**
 * Builds the merged sleep-session timeline for the graph across
 * [startDate]..[endDate]. EVERY bed time and EVERY wake time from every
 * sleep/wake-variant habit becomes an absolute-minute event; events are
 * processed chronologically and each wake is paired with the MOST RECENT
 * unconsumed bed before it — so naps, split nights and post-midnight
 * bedtimes all pair correctly. Segments are grouped by their BED entry's
 * date into one [SleepSession] per night, with [SleepSession.durationMin]
 * being the SUM of the night's segment durations.
 */
fun HabitViewModel.getSleepSessions(
    sleepHabitNames: List<String>,
    wakeHabitNames: List<String>,
    startDate: LocalDate,
    endDate: LocalDate
): List<SleepSession> = runBlocking {
    try {
        // Collect bed events (first habit wins per identical absolute time).
        val bedEvents = mutableListOf<SleepBedEvent>()
        for (name in sleepHabitNames) {
            val records = sleepDataRepo.loadHabitData(name)
            for ((dateStr, rec) in records) {
                val d = com.example.tail.data.parseDate(dateStr) ?: continue
                for (m in rec.bedTimes()) {
                    val abs = d.toEpochDay() * MINUTES_PER_DAY + m
                    if (bedEvents.none { it.absMin == abs }) {
                        bedEvents.add(SleepBedEvent(abs, d, m, SleepBedHalf(m, rec.temp, rec.conditions)))
                    }
                }
            }
        }
        // Collect wake events the same way.
        val wakeEvents = mutableListOf<SleepWakeEvent>()
        for (name in wakeHabitNames) {
            val records = sleepDataRepo.loadHabitData(name)
            for ((dateStr, rec) in records) {
                val d = com.example.tail.data.parseDate(dateStr) ?: continue
                for (m in rec.wakeTimes()) {
                    val abs = d.toEpochDay() * MINUTES_PER_DAY + m
                    if (wakeEvents.none { it.absMin == abs }) {
                        wakeEvents.add(SleepWakeEvent(abs, d, m, SleepWakeHalf(m, rec.awakenings, rec.awakeMin, rec.quality)))
                    }
                }
            }
        }

        val sortedBeds = bedEvents.sortedBy { it.absMin }
        val sortedWakes = wakeEvents.sortedBy { it.absMin }
        val nights = LinkedHashMap<LocalDate, SleepNightGroup>()
        var pending: SleepBedEvent? = null
        var bi = 0
        var wi = 0
        while (bi < sortedBeds.size || wi < sortedWakes.size) {
            val b = sortedBeds.getOrNull(bi)
            val w = sortedWakes.getOrNull(wi)
            // Beds win ties so a wake at the exact same minute pairs with it.
            if (w == null || (b != null && b.absMin <= w.absMin)) {
                pending = b
                bi++
            } else {
                val bed = pending
                if (bed != null) {
                    val dur = (w.absMin - bed.absMin).toInt()
                    if (dur > 0) {
                        val g = nights.getOrPut(bed.date) { SleepNightGroup(bed.date) }
                        g.seedBed(bed.minutes, bed.half)
                        g.segments.add(SleepSegment(bed.minutes, bed.date, w.minutes, w.date, dur))
                        g.wakeHalf = w.half
                        g.lastWakeDate = w.date
                    }
                    pending = null
                }
                wi++
            }
        }
        // A trailing bed with no wake yet becomes an open session (wake = null).
        pending?.let { bed ->
            val g = nights.getOrPut(bed.date) { SleepNightGroup(bed.date) }
            g.seedBed(bed.minutes, bed.half)
        }

        // Start one day early so sessions whose wake lands in the range but
        // whose bed entry is the previous evening still render.
        nights.values
            .filter { !it.date.isBefore(startDate.minusDays(1)) && !it.date.isAfter(endDate) }
            .map { g ->
                SleepSession(
                    date = g.date,
                    bed = g.firstBed,
                    wake = g.segments.lastOrNull()?.wake,
                    wakeDate = g.lastWakeDate,
                    durationMin = g.segments.takeIf { it.isNotEmpty() }?.sumOf { it.durationMin },
                    tempTenths = g.temp,
                    conditions = g.cond,
                    awakenings = g.wakeHalf?.aw,
                    awakeMin = g.wakeHalf?.awake,
                    quality = g.wakeHalf?.q,
                    segments = g.segments.toList()
                )
            }
            .sortedBy { it.date }
    } catch (e: Exception) {
        Log.w(TAG, "getSleepSessions failed: ${e.message}")
        emptyList()
    }
}

/** Async variant of [getSleepSessions] for composable call sites. */
fun HabitViewModel.loadSleepSessions(
    sleepHabitNames: List<String>,
    wakeHabitNames: List<String>,
    startDate: LocalDate,
    endDate: LocalDate,
    onLoaded: (List<SleepSession>) -> Unit
) {
    viewModelScope.launch {
        // getSleepSessions uses runBlocking internally for the file reads; keep
        // it off the main thread here.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            getSleepSessions(sleepHabitNames, wakeHabitNames, startDate, endDate)
        }.let(onLoaded)
    }
}

// (Former DEFAULT_BED_MINUTES / DEFAULT_WAKE_MINUTES were removed: both sleep
// dialogs now pre-fill their time wheel with the CURRENT wall-clock time at
// the moment the habit tile is tapped — see nowMinutesOfDay() in SleepDialogs.kt.)

/** Rounds "minutes since midnight" into the 0..[MINUTES_PER_DAY) range. */
internal fun clampMinutes(m: Int): Int = ((m % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
