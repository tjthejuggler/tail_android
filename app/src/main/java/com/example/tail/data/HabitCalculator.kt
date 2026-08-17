package com.example.tail.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * Returns today's date string in "YYYY-MM-DD" format.
 */
fun todayString(): String = LocalDate.now().format(DATE_FMT)

/**
 * Formats any [LocalDate] as "YYYY-MM-DD".
 */
fun dateString(date: LocalDate): String = date.format(DATE_FMT)

/**
 * Parses a "YYYY-MM-DD" string back to a [LocalDate], or null if invalid.
 */
fun parseDate(s: String): LocalDate? = try {
    LocalDate.parse(s, DATE_FMT)
} catch (e: Exception) {
    null
}

/**
 * Gets the raw count for a specific date from a habit's date map.
 */
fun getCountForDate(entries: Map<String, Int>, date: LocalDate): Int {
    return entries[dateString(date)] ?: 0
}

/**
 * Gets the raw count for today from a habit's date map.
 */
fun getTodayCount(entries: Map<String, Int>): Int = getCountForDate(entries, LocalDate.now())

/**
 * Expands a sparse entries map to include every calendar day between the earliest
 * and latest recorded dates, filling missing days with 0.
 *
 * This matches the desktop behavior: habitsdb.txt has an explicit entry for every
 * calendar day, so any gap in the phone-only DB is treated as a zero (streak-breaker).
 */
/**
 * Public wrapper for expandEntriesToCalendarDays, used by buildHabit to check
 * whether the streak reaches back to the start of the phone window.
 */
fun expandEntriesToCalendarDaysPublic(entries: Map<String, Int>): Map<String, Int> =
    expandEntriesToCalendarDays(entries)

private fun expandEntriesToCalendarDays(entries: Map<String, Int>): Map<String, Int> {
    if (entries.isEmpty()) return entries
    val sortedKeys = entries.keys.sorted()
    val first = parseDate(sortedKeys.first()) ?: return entries
    val last = parseDate(sortedKeys.last()) ?: return entries
    val expanded = LinkedHashMap<String, Int>()
    var current = first
    while (!current.isAfter(last)) {
        val key = dateString(current)
        expanded[key] = entries[key] ?: 0
        current = current.plusDays(1)
    }
    return expanded
}

/**
 * Calculates current streak (positive) or antistreak (negative) matching desktop logic.
 *
 * Desktop logic (streak_helper.py):
 *   get_days_since_not_zero: index of first non-zero from most-recent end
 *   get_days_since_zero_minus: index of first zero from most-recent end, SKIPPING index 0
 *   if days_since_not_zero < 2: left_number = days_since_zero_minus (positive streak)
 *   else:                        left_number = -days_since_not_zero (negative antistreak)
 *
 * Calendar gaps (missing dates) are treated as zeros, matching the desktop where
 * habitsdb.txt has an explicit entry for every calendar day.
 *
 * [targetDate] — when non-null, the entries are guaranteed to include this date
 * (inserted with value 0 if missing) BEFORE calendar expansion.  This is critical
 * for sparse Garmin habits (run / bike / swim) whose entries only exist on
 * activity days.  Without it, navigating to a past date that falls beyond the
 * last stored entry causes [expandEntriesToCalendarDays] to stop at that last
 * entry instead of extending to the target date, producing wildly incorrect
 * streak values (e.g. +0 instead of -215).
 */
fun calculateStreakDisplay(entries: Map<String, Int>, targetDate: LocalDate? = null): Int {
    if (entries.isEmpty()) return 0
    // Ensure the target date is present so calendar expansion extends to it.
    // This mirrors the workaround already used in AppStatsScreen.
    val entriesWithTarget = if (targetDate != null) {
        val targetDateStr = dateString(targetDate)
        if (targetDateStr !in entries) entries + (targetDateStr to 0) else entries
    } else {
        entries
    }
    // Expand sparse map so calendar gaps count as zeros (matching desktop behavior)
    val expanded = expandEntriesToCalendarDays(entriesWithTarget)
    val sorted = expanded.keys.sorted().reversed()

    // days_since_not_zero: index of first non-zero entry from most recent
    val daysSinceNotZero = sorted.indexOfFirst { expanded[it] != 0 }
        .let { if (it == -1) sorted.size else it }

    return if (daysSinceNotZero < 2) {
        // Currently on a streak — use get_days_since_zero_minus:
        // skip index 0 (most recent), find first zero in the rest
        val daysSinceZeroMinus = sorted.drop(1).indexOfFirst { expanded[it] == 0 }
            .let { if (it == -1) sorted.size else it }
        daysSinceZeroMinus
    } else {
        // On an antistreak
        -daysSinceNotZero
    }
}

// ── Inverted binary helpers ──────────────────────────────────────────────────

/**
 * Points for an inverted-binary habit (e.g. coffee tracking): 1 point on days
 * the habit was NOT done (raw count 0), 0 points on days it was done.
 */
fun invertedBinaryPoints(rawCount: Int): Int = if (rawCount > 0) 0 else 1

/**
 * Builds the inverted view of a habit's entries for inverted-binary habits.
 *
 * Every calendar day between the first and last entry (plus [targetDate] when
 * given) is expanded, then flipped: raw 0 (not done) becomes 1 (success) and
 * any raw > 0 (done) becomes 0 (failure). Feeding this dense inverted map into
 * the regular streak/average calculators yields the inverted semantics:
 * consecutive not-done days form the streak, a done day breaks it.
 */
fun invertEntriesForInvertedBinary(
    entries: Map<String, Int>,
    targetDate: LocalDate? = null
): Map<String, Int> {
    if (entries.isEmpty()) return emptyMap()
    val withTarget = if (targetDate != null) {
        val targetDateStr = dateString(targetDate)
        if (targetDateStr !in entries) entries + (targetDateStr to 0) else entries
    } else {
        entries
    }
    return expandEntriesToCalendarDaysPublic(withTarget).mapValues { if (it.value == 0) 1 else 0 }
}

/**
 * Streak display for inverted-binary habits, computed on the dense INVERTED
 * map from [invertEntriesForInvertedBinary] (1 = clean/not-done day, 0 = done
 * day).
 *
 * Unlike [calculateStreakDisplay] there is no "today is still in progress"
 * forgiveness (its index-0 skip assumes a 0 day might still become non-zero).
 * For inverted habits a done day is final: if the most recent day was done,
 * the result is an antistreak of the consecutive done days ending on it;
 * otherwise it is the streak of consecutive clean days ending on the most
 * recent day.
 */
fun calculateInvertedStreakDisplay(invertedEntries: Map<String, Int>): Int {
    if (invertedEntries.isEmpty()) return 0
    val sorted = invertedEntries.keys.sorted().reversed()
    val mostRecentDone = (invertedEntries[sorted.first()] ?: 0) == 0
    var run = 0
    for (day in sorted) {
        val clean = (invertedEntries[day] ?: 0) != 0
        // Continue while the day's state matches the run's state:
        // counting done days when mostRecentDone, clean days otherwise.
        if (clean == mostRecentDone) break
        run++
    }
    return if (mostRecentDone) -run else run
}

/**
 * Calculates the longest streak of consecutive non-zero days.
 * Matches desktop get_longest_streak().
 *
 * Calendar gaps (missing dates) are treated as zeros, matching the desktop where
 * habitsdb.txt has an explicit entry for every calendar day.
 */
fun calculateLongestStreak(entries: Map<String, Int>): Int {
    // Expand sparse map so calendar gaps count as zeros (matching desktop behavior)
    val expanded = expandEntriesToCalendarDays(entries)
    var longest = 0
    var current = 0
    for ((_, value) in expanded.entries.sortedBy { it.key }) {
        if (value != 0) {
            current++
        } else {
            longest = maxOf(longest, current)
            current = 0
        }
    }
    return maxOf(longest, current)
}

/**
 * Returns the all-time high single-day count and the date it occurred.
 * Matches desktop all_time_high_values["day"] = get_all_time_high_rolling(inner_dict, 1).
 */
fun calculateAllTimeHighDay(entries: Map<String, Int>): Pair<Int, String> {
    if (entries.isEmpty()) return Pair(0, "")
    val maxEntry = entries.entries.maxByOrNull { it.value } ?: return Pair(0, "")
    return Pair(maxEntry.value, maxEntry.key)
}

/**
 * Returns the most recent entry's raw value (matching desktop current_values["day"]).
 * Desktop: inner_dict[list(inner_dict.keys())[-1]] — last key in sorted dict.
 */
fun getMostRecentValue(entries: Map<String, Int>): Int {
    if (entries.isEmpty()) return 0
    val lastKey = entries.keys.sorted().lastOrNull() ?: return 0
    return entries[lastKey] ?: 0
}

/**
 * Calculates the calendar-day average of the last N days from today.
 *
 * Divides by the full [nDays] window (e.g. 7 for a week), treating days with
 * no entry as 0. This matches the graph StatsSummary, which fills zero for
 * every calendar day in the selected period, and the Tasker relay
 * (buildTaskerStatsContent) which also divides by the day count.
 *
 * Previously this divided by the number of entries that existed (excluding
 * zero/rest days), which produced a different value than the Stats Bar.
 */
fun getAverageOfLastNDays(entries: Map<String, Int>, nDays: Int, today: LocalDate = LocalDate.now()): Double {
    if (entries.isEmpty()) return 0.0
    val cutoff = today.minusDays(nDays.toLong())
    val cutoffStr = dateString(cutoff)
    val todayStr = dateString(today)
    val sum = entries.filter { (k, _) -> k > cutoffStr && k <= todayStr }
        .values.sumOf { it.toDouble() }
    // Calendar-day average: divide by the full nDays window so that days with
    // no entry count as 0 — consistent with the graph Stats Bar.
    return sum / nDays
}

/**
 * Calculates the all-time high rolling N-day **calendar** average and the date
 * it peaked.
 *
 * Uses a sliding window of [windowSize] consecutive **calendar** days (not
 * consecutive entries). Missing days within each window are treated as 0, and
 * the sum is divided by [windowSize]. This makes the "all-time high"
 * comparable to the "current" rolling average produced by
 * [getAverageOfLastNDays], which also divides by the calendar-day count.
 *
 * Returns the peak average and the date of the last day in that window.
 */
fun getAllTimeHighRolling(entries: Map<String, Int>, windowSize: Int): RollingHigh {
    if (entries.isEmpty()) return RollingHigh(0.0, "")
    val sorted = entries.entries.sortedBy { it.key }
    val firstDate = parseDate(sorted.first().key) ?: return RollingHigh(0.0, "")
    val lastDate = parseDate(sorted.last().key) ?: return RollingHigh(0.0, "")

    // Build a dense array of daily values (calendar-day expanded, missing = 0)
    val totalDays = (lastDate.toEpochDay() - firstDate.toEpochDay()).toInt() + 1
    val dailyValues = IntArray(totalDays) { i ->
        entries[dateString(firstDate.plusDays(i.toLong()))] ?: 0
    }

    // Prefix sums for O(1) window sums
    val prefix = LongArray(totalDays + 1)
    for (i in 0 until totalDays) {
        prefix[i + 1] = prefix[i] + dailyValues[i]
    }

    var bestAvg = Double.NEGATIVE_INFINITY
    var bestEndIdx = 0

    if (totalDays <= windowSize) {
        // Fewer calendar days than window size — average all available days
        // over the full windowSize denominator (missing days count as 0).
        val sum = prefix[totalDays]
        bestAvg = sum.toDouble() / windowSize
        bestEndIdx = totalDays - 1
    } else {
        // Slide a window of [windowSize] consecutive calendar days
        for (startIdx in 0..(totalDays - windowSize)) {
            val endIdx = startIdx + windowSize - 1
            val windowSum = prefix[endIdx + 1] - prefix[startIdx]
            val avg = windowSum.toDouble() / windowSize
            if (avg > bestAvg) {
                bestAvg = avg
                bestEndIdx = endIdx
            }
        }
    }

    val bestDate = dateString(firstDate.plusDays(bestEndIdx.toLong()))
    val rounded = Math.round(bestAvg * 100.0) / 100.0
    return RollingHigh(value = rounded, date = bestDate)
}

/**
 * Merges primary entries with secondary entries for habits that have the
 * "secondary value fallback for points" feature enabled.
 *
 * For each date, if the primary value is zero (or missing) but the secondary
 * value is non-zero, the secondary value is substituted. This makes the
 * habit appear "done" on that day for streak / point / average calculations.
 *
 * When [secondaryEntries] is empty or [useFallback] is false, [primaryEntries]
 * is returned unchanged.
 */
fun effectiveEntriesWithFallback(
    primaryEntries: Map<String, Int>,
    secondaryEntries: Map<String, Int>,
    useFallback: Boolean
): Map<String, Int> {
    if (!useFallback || secondaryEntries.isEmpty()) return primaryEntries
    val result = primaryEntries.toMutableMap()
    for ((date, secVal) in secondaryEntries) {
        if (secVal > 0 && (result[date] ?: 0) <= 0) {
            result[date] = secVal
        }
    }
    return result
}

/**
 * Computes the effective "points" value for a habit on a single day, with
 * optional fallback to the secondary value when the primary value is zero.
 *
 * When [useSecondaryFallback] is true and [rawCount] is zero, the
 * [secondaryValue] is used directly as the points value (no divider applied).
 * Otherwise, the standard [applyDivider] is used.
 */
fun effectivePointsWithFallback(
    rawCount: Int,
    divider: Int,
    secondaryValue: Int = 0,
    useSecondaryFallback: Boolean = false
): Int {
    if (useSecondaryFallback && rawCount <= 0 && secondaryValue > 0) {
        return secondaryValue
    }
    return applyDivider(rawCount, divider)
}

/**
 * Builds a [Habit] display object from raw database entries for a specific [targetDate].
 * All stats (streak, antistreak, etc.) are computed as if [targetDate] is "today".
 * Matches the desktop app's full tooltip data.
 *
 * The full habitsdb.txt is used directly — no historical stats merging needed.
 *
 * [divider] — when > 1, the raw stored count is divided (rounded, min 1 if non-zero)
 * to produce the displayed points value. The raw count is stored unchanged in the DB.
 *
 * [secondaryEntries] — the secondary-value date map (from
 * `secondary_value:<habitName>`).  When [useSecondaryFallback] is true, days
 * with a zero primary value but non-zero secondary value use the secondary
 * value for points, streak, and average calculations.
 *
 * [swapPrimarySecondary] — for widget-timer habits where minutes (the
 * secondary-value slot) is the PRIMARY value: the roles are swapped, so
 * [secondaryEntries] (minutes) drives points/streak/averages with the
 * [entries] (sessions) used only as the zero-minutes fallback.
 */
fun buildHabit(
    name: String,
    entries: Map<String, Int>,
    useCustomInput: Boolean,
    divider: Int = 1,
    targetDate: java.time.LocalDate = java.time.LocalDate.now(),
    secondaryEntries: Map<String, Int> = emptyMap(),
    useSecondaryFallback: Boolean = false,
    swapPrimarySecondary: Boolean = false,
    invertedBinary: Boolean = false
): Habit {
    // Only include entries up to and including targetDate for streak/stat calculations
    val targetDateStr = dateString(targetDate)
    val filteredEntries = entries.filter { (k, _) -> k <= targetDateStr }

    // When fallback is enabled, merge secondary values into the effective entries
    // so that streak / average / ATH calculations see the substituted values.
    // When swapped (minutes primary), the secondary entries become the primary
    // series and the raw entries become the fallback.
    val filteredSecondary = secondaryEntries.filter { (k, _) -> k <= targetDateStr }
    val effectiveEntries = if (swapPrimarySecondary) {
        effectiveEntriesWithFallback(filteredSecondary, filteredEntries, true)
    } else {
        effectiveEntriesWithFallback(filteredEntries, filteredSecondary, useSecondaryFallback)
    }

    val rawCountForDate = getCountForDate(filteredEntries, targetDate)
    val secValForDate = getCountForDate(filteredSecondary, targetDate)

    // Inverted-binary habits: flip the dense day map (0 → 1 success, >0 → 0
    // failure) so streaks/averages run on the inverted series. The all-time
    // high day intentionally stays on the RAW entries (most occurrences in a
    // single day, e.g. most coffees ever drunk).
    val invertedEntries = if (invertedBinary) {
        invertEntriesForInvertedBinary(effectiveEntries, targetDate)
    } else null
    val statsEntries = invertedEntries ?: effectiveEntries

    // todayCount shown on the button is the effective points value (with fallback)
    val countForDate = when {
        invertedBinary -> invertedBinaryPoints(rawCountForDate)
        swapPrimarySecondary -> effectivePointsWithFallback(secValForDate, divider, rawCountForDate, true)
        else -> effectivePointsWithFallback(rawCountForDate, divider, secValForDate, useSecondaryFallback)
    }
    val streakDisplay = if (invertedBinary) {
        calculateInvertedStreakDisplay(invertedEntries!!)
    } else {
        calculateStreakDisplay(statsEntries, targetDate)
    }
    val longestStreak = calculateLongestStreak(statsEntries)

    val (allTimeHighDayVal, allTimeHighDayDate) = calculateAllTimeHighDay(effectiveEntries)
    val currentDayValue = getMostRecentValue(statsEntries)

    // Rolling averages for current period
    val avgLast7 = getAverageOfLastNDays(statsEntries, 7, targetDate)
    val avgLast30 = getAverageOfLastNDays(statsEntries, 30, targetDate)
    val avgLast365 = getAverageOfLastNDays(statsEntries, 365, targetDate)

    // All-time high rolling windows
    val allTimeHighWeek = getAllTimeHighRolling(statsEntries, 7)
    val allTimeHighMonth = getAllTimeHighRolling(statsEntries, 30)
    val allTimeHighYear = getAllTimeHighRolling(statsEntries, 365)

    return Habit(
        name = name,
        todayCount = countForDate,
        rawTodayCount = rawCountForDate,
        currentStreak = streakDisplay,
        longestStreak = longestStreak,
        allTimeHighDay = allTimeHighDayVal,
        allTimeHighDayDate = allTimeHighDayDate,
        useCustomInput = useCustomInput,
        divider = divider,
        currentDayValue = currentDayValue,
        avgLast7Days = avgLast7,
        avgLast30Days = avgLast30,
        avgLast365Days = avgLast365,
        allTimeHighWeek = allTimeHighWeek,
        allTimeHighMonth = allTimeHighMonth,
        allTimeHighYear = allTimeHighYear,
        invertedBinary = invertedBinary
    )
}

/**
 * Builds the Tasker relay file content (today / avg7 / avg30 point totals).
 *
 * This is the single source of truth for the Tasker stats file across every
 * write site (HabitViewModel + the IPC services). It applies dividers and,
 * crucially, EXCLUDES any habit listed in [noPointsHabits] (the "Don't affect
 * points" setting) so Garmin-imported and other no-points habits never inflate
 * the relayed totals.
 *
 * Format (matches the in-app daily-total logic in computeAppStats/getDailyTotals):
 *   today=<N>
 *   avg7=<X.XX>
 *   avg30=<X.XX>
 */
fun buildTaskerStatsContent(
    db: HabitsDatabase,
    dividers: Map<String, Int>,
    noPointsHabits: Set<String>,
    today: LocalDate = LocalDate.now(),
    secondaryValueFallbackHabits: Set<String> = emptySet(),
    timerMinutesPrimaryHabits: Set<String> = emptySet(),
    invertedBinaryHabits: Set<String> = emptySet()
): String {
    fun dayTotal(date: LocalDate): Int {
        val ds = dateString(date)
        return db.entries.sumOf { (habitName, entries) ->
            if (habitName in noPointsHabits) return@sumOf 0
            if (isSecondaryValueKey(habitName)) return@sumOf 0
            // Inverted-binary habits contribute 1 point on not-done days
            if (habitName in invertedBinaryHabits) {
                return@sumOf invertedBinaryPoints(entries[ds] ?: 0)
            }
            if (habitName in timerMinutesPrimaryHabits) {
                // Minutes (secondary slot) is primary; sessions are the fallback
                val minutes = db[secondaryValueKey(habitName)]?.get(ds) ?: 0
                effectivePointsWithFallback(
                    minutes, dividers[habitName] ?: 1, entries[ds] ?: 0, true
                )
            } else {
                val useFallback = habitName in secondaryValueFallbackHabits
                val secVal = if (useFallback) {
                    db[secondaryValueKey(habitName)]?.get(ds) ?: 0
                } else 0
                effectivePointsWithFallback(
                    entries[ds] ?: 0, dividers[habitName] ?: 1, secVal, useFallback
                )
            }
        }
    }

    val todayCount = dayTotal(today)

    fun avgOverDays(days: Int): Double {
        var total = 0
        for (i in 0 until days) {
            total += dayTotal(today.minusDays(i.toLong()))
        }
        return total.toDouble() / days
    }

    val avg7 = avgOverDays(7)
    val avg30 = avgOverDays(30)
    return "today=$todayCount\navg7=${"%.2f".format(avg7)}\navg30=${"%.2f".format(avg30)}\n"
}
