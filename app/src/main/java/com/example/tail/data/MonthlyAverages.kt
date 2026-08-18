package com.example.tail.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Bulk 30-day monthly-average computation for the world-map screen's dot
 * colours — the pure core behind [com.example.tail.ui.HabitViewModel.getMonthlyAveragesBulk].
 *
 * The map needs the rounded 30-day average (identical math to the ViewModel's
 * getDayStatsLight().monthlyAverage) for EVERY dated coordinate. Computing it
 * per date re-scanned a 30-day window × every tracked habit per date —
 * O(D·30·H) with per-call settings lookups and string allocations, which took
 * tens of seconds with years of seeded location data.
 *
 * This implementation instead:
 *
 *  1. Precomputes the "YYYY-MM-DD" strings for the contiguous day range
 *     [min(dates)−29 … max(dates)] exactly once (the DateTimeFormatter was a
 *     measurable chunk of the old cost when called D·30·H times).
 *  2. Makes ONE pass over tracked habits × day range, resolving each habit's
 *     divider / fallback flags / secondary entries once, accumulating per-day
 *     effective-point totals with the SAME branch order as the ViewModel's
 *     effectivePointsForDate().
 *  3. Builds prefix sums so any date's 30-day sum is a single subtraction,
 *     then divides by 30.0 exactly like getDayStatsLight.
 *
 * Complexity O(D·H) with no per-iteration allocations.
 *
 * @param db the cached habits database (habit name → date-string → raw count).
 * @param tracked habit names to include (same set getDayStatsLight uses:
 *   trackedHabitNames() with fallback to db.keys).
 * @param settings current app settings (dividers, inverted-binary set,
 *   widget-timer-minutes-primary set, secondary-value-fallback set).
 * @param dates the dates needing an average (the map's dated coordinates).
 * @return date → rounded 30-day average; empty for an empty [dates].
 */
fun monthlyAveragesBulk(
    db: HabitsDatabase,
    tracked: Set<String>,
    settings: AppSettings,
    dates: Collection<LocalDate>
): Map<LocalDate, Int> {
    if (dates.isEmpty()) return emptyMap()

    val minDate = dates.min()
    val maxDate = dates.max()
    val windowStart = minDate.minusDays(29)
    val nDays = ChronoUnit.DAYS.between(windowStart, maxDate).toInt() + 1

    // 1. Date strings for the whole range, computed once and reused by every
    //    habit pass below.
    val dateStrs = Array(nDays) { i -> dateString(windowStart.plusDays(i.toLong())) }

    // 2. Per-day total effective points across tracked habits.
    val totals = IntArray(nDays)
    for (name in tracked) {
        val entries = db[name] ?: continue
        val divider = settings.habitDividers[name] ?: 1
        val inverted = name in settings.invertedBinaryHabits
        val minutesPrimary = name in settings.widgetTimerMinutesPrimary
        // Minutes-primary habits read the first-class minutes slot; sessions-
        // primary fallback habits read the legacy secondary slot when they use
        // it or have data there (chess.com games, JugCoach seconds), otherwise
        // the minutes slot.
        val secEntries = when {
            inverted -> null
            minutesPrimary -> db[minutesKey(name)]
            name !in settings.secondaryValueFallbackHabits -> null
            else -> db[fallbackSlotKey(name, settings.secondaryValueHabits, db)]
        }
        for (i in 0 until nDays) {
            val ds = dateStrs[i]
            val raw = entries[ds] ?: 0
            // Mirrors the ViewModel's effectivePointsForDate() branch order.
            val pts = when {
                inverted -> invertedBinaryPoints(raw)
                minutesPrimary -> effectivePointsWithFallback(
                    secEntries?.get(ds) ?: 0, divider, raw, true
                )
                name !in settings.secondaryValueFallbackHabits -> applyDivider(raw, divider)
                else -> effectivePointsWithFallback(
                    raw, divider, secEntries?.get(ds) ?: 0, true
                )
            }
            if (pts > 0) totals[i] += pts
        }
    }

    // 3. Prefix sums → each date's 30-day window sum in O(1).
    val prefix = IntArray(nDays + 1)
    for (i in 0 until nDays) prefix[i + 1] = prefix[i] + totals[i]

    val out = HashMap<LocalDate, Int>(dates.size)
    for (d in dates) {
        val endIdx = ChronoUnit.DAYS.between(windowStart, d).toInt()
        val startIdx = (endIdx - 29).coerceAtLeast(0)
        val sum = prefix[endIdx + 1] - prefix[startIdx]
        out[d] = kotlin.math.round(sum / 30.0).toInt()
    }
    return out
}
