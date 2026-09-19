package com.example.tail.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.runBlocking
import com.example.tail.data.MINUTES_PER_DAY
import com.example.tail.data.SleepDataRepository
import com.example.tail.data.SleepRecord
import com.example.tail.data.SLEEP_VARIANT_SLEEP_TIME
import com.example.tail.data.SLEEP_VARIANT_WAKE_TIME
import com.example.tail.data.dateString
import com.example.tail.data.isSleepVariantKey
import com.example.tail.data.sleepDurationMinutes
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

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
 * Saves the SLEEP half of a night's record: bed time (minutes since midnight),
 * room temperature (tenths of °C) and the free-text conditions. Increments the
 * habit count by 1 — but only when the bed half was previously unset for that
 * date, so re-editing tonight's bedtime never inflates streaks.
 *
 * @param stampTime "HH:mm:ss" matching the bed time, so the recorded timestamp
 *                  aligns with the timeline graph.
 */
fun HabitViewModel.saveSleepTimeEntry(
    habitName: String,
    bedMinutes: Int,
    tempTenths: Int?,
    conditions: String?,
    date: LocalDate,
    stampTime: String
) {
    viewModelScope.launch {
        try {
            val dateStr = dateString(date)
            val existing = sleepDataRepo.getRecord(habitName, dateStr)
            val firstEntry = existing.bed == null
            sleepDataRepo.mergeRecord(
                habitName, dateStr,
                SleepRecord(bed = bedMinutes, temp = tempTenths, conditions = conditions?.trim()?.takeIf { it.isNotEmpty() })
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
 * Saves the WAKE half of a night's record: wake time (minutes since midnight),
 * the mini-survey answers (awakenings count, minutes awake, perceived quality
 * 1–5). Increments the habit count by 1 — only when the wake half was
 * previously unset for that date (re-edits don't re-count).
 */
fun HabitViewModel.saveWakeSurveyEntry(
    habitName: String,
    wakeMinutes: Int,
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
            val firstEntry = existing.wake == null
            sleepDataRepo.mergeRecord(
                habitName, dateStr,
                SleepRecord(
                    wake = wakeMinutes,
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

/**
 * One reconstructed sleep session for the timeline graph. The session date is
 * the BED entry's date; the wake half is matched from the same date or the
 * next morning, whichever produces a plausible session.
 */
data class SleepSession(
    /** Session date = the date key of the bed entry. */
    val date: LocalDate,
    /** Bed time — minutes since midnight of [date]. Null = bed not logged. */
    val bed: Int?,
    /** Wake time — minutes since midnight of [wakeDate]. Null = wake not logged. */
    val wake: Int?,
    /** The date key the matched wake record came from (usually [date] + 1). */
    val wakeDate: LocalDate?,
    /** Total sleep duration in minutes (midnight-wrapping), when both halves exist. */
    val durationMin: Int?,
    /** Room temperature in tenths of °C. */
    val tempTenths: Int?,
    /** Free-text conditions recorded at bedtime. */
    val conditions: String?,
    /** Mini-survey: number of awakenings. */
    val awakenings: Int?,
    /** Mini-survey: total minutes awake during the night. */
    val awakeMin: Int?,
    /** Mini-survey: perceived overall sleep quality 1–5. */
    val quality: Int?
)

/**
 * Builds the merged sleep-session timeline for the graph across
 * [startDate]..[endDate]. A session is keyed by the bed entry's date; its wake
 * half is searched on the same date (naps) and the next morning (overnight
 * sleep), preferring the combination that yields a positive duration ≤ 24 h.
 * Sleep habits are the SLEEP_TIME-variant habit names, wake habits the
 * WAKE_TIME-variant ones — pass the two halves explicitly so any pairing of
 * habit names works (e.g. "Sleep"/"Wake" or "Nap"/"Nap Wake").
 */
fun HabitViewModel.getSleepSessions(
    sleepHabitNames: List<String>,
    wakeHabitNames: List<String>,
    startDate: LocalDate,
    endDate: LocalDate
): List<SleepSession> = runBlocking {
    try {
        // Accumulate every bed half and every wake half, then pair them.
        val beds = mutableMapOf<LocalDate, SleepBedHalf>()
        for (name in sleepHabitNames) {
            val records = sleepDataRepo.loadHabitData(name)
            for ((dateStr, rec) in records) {
                if (rec.bed == null) continue
                val d = com.example.tail.data.parseDate(dateStr) ?: continue
                // First habit wins; a second sleep habit for the same night
                // (e.g. separate nap habit) keeps its own date key anyway.
                if (!beds.containsKey(d)) {
                    beds[d] = SleepBedHalf(rec.bed, rec.temp, rec.conditions)
                }
            }
        }
        val wakes = mutableMapOf<LocalDate, SleepWakeHalf>()
        for (name in wakeHabitNames) {
            val records = sleepDataRepo.loadHabitData(name)
            for ((dateStr, rec) in records) {
                if (rec.wake == null) continue
                val d = com.example.tail.data.parseDate(dateStr) ?: continue
                if (!wakes.containsKey(d)) {
                    wakes[d] = SleepWakeHalf(rec.wake, rec.awakenings, rec.awakeMin, rec.quality)
                }
            }
        }

        val sessions = mutableListOf<SleepSession>()
        // Start one day early so sessions whose wake lands in the range but
        // whose bed entry is the previous evening still render.
        var d = startDate.minusDays(1)
        while (!d.isAfter(endDate)) {
            val bedHalf = beds[d]
            if (bedHalf != null) {
                val bed = bedHalf.bed
                // Prefer a wake on the NEXT date (overnight); fall back to the
                // same date (nap / split log) when it produces a positive span.
                val next = wakes[d.plusDays(1)]
                val same = wakes[d]
                var wakeHalf: SleepWakeHalf? = null
                var wakeDate: LocalDate? = null
                if (next != null && sleepDurationMinutes(bed, next.wake) != null) {
                    wakeHalf = next; wakeDate = d.plusDays(1)
                } else if (same != null && sleepDurationMinutes(bed, same.wake) != null) {
                    wakeHalf = same; wakeDate = d
                } else if (next != null) {
                    wakeHalf = next; wakeDate = d.plusDays(1)
                } else if (same != null) {
                    wakeHalf = same; wakeDate = d
                }
                sessions.add(
                    SleepSession(
                        date = d,
                        bed = bed,
                        wake = wakeHalf?.wake,
                        wakeDate = wakeDate,
                        durationMin = sleepDurationMinutes(bed, wakeHalf?.wake),
                        tempTenths = bedHalf.temp,
                        conditions = bedHalf.cond,
                        awakenings = wakeHalf?.aw,
                        awakeMin = wakeHalf?.awake,
                        quality = wakeHalf?.q
                    )
                )
            }
            d = d.plusDays(1)
        }
        sessions
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

/** Default bed time used to pre-fill the sleep dialog: 22:30. */
internal val DEFAULT_BED_MINUTES: Int = LocalTime.of(22, 30).let { it.hour * 60 + it.minute }

/** Default wake time used to pre-fill the wake dialog: 07:00. */
internal val DEFAULT_WAKE_MINUTES: Int = 7 * 60

/** Rounds "minutes since midnight" into the 0..[MINUTES_PER_DAY) range. */
internal fun clampMinutes(m: Int): Int = ((m % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
