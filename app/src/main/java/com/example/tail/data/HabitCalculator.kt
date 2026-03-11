package com.example.tail.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor

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
 * Applies the special display adjustment for certain habits (matching desktop logic).
 * Used only for determining icon color tier, not for stored values.
 */
fun getDisplayValue(habitName: String, rawCount: Int): Int {
    val adjusted = when {
        "Pushups" in habitName -> floor(rawCount / 30.0 + 0.5).toInt()
        "Situps" in habitName  -> floor(rawCount / 50.0 + 0.5).toInt()
        "Squats" in habitName  -> floor(rawCount / 30.0 + 0.5).toInt()
        "Sweat" == habitName   -> floor(rawCount / 15.0 + 0.5).toInt()
        "Cold Shower" in habitName -> {
            val v = if (rawCount in 1..2) 3 else rawCount
            floor(v / 3.0 + 0.5).toInt()
        }
        else -> rawCount
    }
    return floor(adjusted + 0.5).toInt()
}

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
 */
fun calculateStreakDisplay(entries: Map<String, Int>): Int {
    if (entries.isEmpty()) return 0
    // Expand sparse map so calendar gaps count as zeros (matching desktop behavior)
    val expanded = expandEntriesToCalendarDays(entries)
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
 * Calculates the average of the last N days from today.
 * Matches desktop get_average_of_last_n_days(inner_dict, n_days).
 * Only counts entries within the last n_days calendar days from today.
 */
fun getAverageOfLastNDays(entries: Map<String, Int>, nDays: Int, today: LocalDate = LocalDate.now()): Double {
    if (entries.isEmpty()) return 0.0
    val cutoff = today.minusDays(nDays.toLong())
    val cutoffStr = dateString(cutoff)
    val todayStr = dateString(today)
    val relevant = entries.filter { (k, _) -> k > cutoffStr && k <= todayStr }
    if (relevant.isEmpty()) return 0.0
    return relevant.values.average()
}

/**
 * Calculates the all-time high rolling N-day average and the date it peaked.
 * Matches desktop get_all_time_high_rolling(inner_dict, time_period).
 *
 * Uses a sliding window of [windowSize] consecutive days (by sorted date order).
 * Returns the peak average and the date of the last day in that window.
 */
fun getAllTimeHighRolling(entries: Map<String, Int>, windowSize: Int): RollingHigh {
    if (entries.isEmpty()) return RollingHigh(0.0, "")
    val sorted = entries.entries.sortedBy { it.key }
    if (sorted.size < windowSize) {
        // Not enough data for a full window — return average of all available
        val avg = sorted.map { it.value }.average()
        return RollingHigh(
            value = Math.round(avg * 100.0) / 100.0,
            date = sorted.last().key
        )
    }

    var bestAvg = Double.MIN_VALUE
    var bestDate = ""
    for (i in windowSize - 1 until sorted.size) {
        val windowVals = sorted.subList(i - windowSize + 1, i + 1).map { it.value.toDouble() }
        val avg = windowVals.average()
        if (avg > bestAvg) {
            bestAvg = avg
            bestDate = sorted[i].key
        }
    }
    val rounded = Math.round(bestAvg * 100.0) / 100.0
    return RollingHigh(value = rounded, date = bestDate)
}

/**
 * Builds a [Habit] display object from raw database entries for a specific [targetDate].
 * All stats (streak, antistreak, etc.) are computed as if [targetDate] is "today".
 * Matches the desktop app's full tooltip data.
 *
 * If [historicalStats] is provided (from habitsdb_without_phone_totals.txt), it is used to
 * extend the streak and longest-streak beyond the phone-only 30-day window:
 *
 * - Current streak: if the phone DB shows a continuous streak from its earliest entry
 *   (i.e. the streak reaches all the way back to the start of the phone window), then
 *   the true streak = phone streak + historical daysSinceZero (the streak at the end of
 *   the historical DB, which is the day immediately before the phone window starts).
 *
 * - Longest streak: max(phone-computed longest, historical longestStreak).
 */
fun buildHabit(
    name: String,
    entries: Map<String, Int>,
    useCustomInput: Boolean,
    historicalStats: HabitHistoricalStats? = null,
    targetDate: java.time.LocalDate = java.time.LocalDate.now()
): Habit {
    // Only include entries up to and including targetDate for streak/stat calculations
    val targetDateStr = dateString(targetDate)
    val filteredEntries = entries.filter { (k, _) -> k <= targetDateStr }

    val countForDate = getCountForDate(filteredEntries, targetDate)
    val phoneStreakDisplay = calculateStreakDisplay(filteredEntries)
    val phoneLongestStreak = calculateLongestStreak(filteredEntries)

    // Combine phone streak with historical baseline if available
    val streakDisplay: Int
    val longestStreak: Int
    if (historicalStats != null) {
        // Extend current streak: if the phone DB streak reaches back to its earliest entry,
        // the streak was already ongoing when the phone window started — add the historical carry.
        val extendedStreak = if (phoneStreakDisplay > 0) {
            // Check if the streak covers the entire phone window (streak == number of non-zero
            // entries from the start). We detect this by checking whether the phone DB's
            // earliest entry is non-zero AND the streak equals the full phone window length.
            val expanded = expandEntriesToCalendarDaysPublic(filteredEntries)
            val sortedAsc = expanded.keys.sorted()
            // The streak "reaches the start" if the first entry in the phone window is non-zero
            // (meaning there was no zero break within the phone window from the beginning)
            val firstEntryNonZero = sortedAsc.isNotEmpty() && (expanded[sortedAsc.first()] ?: 0) != 0
            if (firstEntryNonZero && historicalStats.daysSinceZero > 0) {
                phoneStreakDisplay + historicalStats.daysSinceZero
            } else {
                phoneStreakDisplay
            }
        } else {
            phoneStreakDisplay
        }
        streakDisplay = extendedStreak
        longestStreak = maxOf(phoneLongestStreak, historicalStats.longestStreak)
    } else {
        streakDisplay = phoneStreakDisplay
        longestStreak = phoneLongestStreak
    }

    val (allTimeHighDayVal, allTimeHighDayDate) = calculateAllTimeHighDay(filteredEntries)
    val currentDayValue = getMostRecentValue(filteredEntries)

    // Rolling averages for current period
    val avgLast7 = getAverageOfLastNDays(filteredEntries, 7, targetDate)
    val avgLast30 = getAverageOfLastNDays(filteredEntries, 30, targetDate)
    val avgLast365 = getAverageOfLastNDays(filteredEntries, 365, targetDate)

    // All-time high rolling windows
    val allTimeHighWeek = getAllTimeHighRolling(filteredEntries, 7)
    val allTimeHighMonth = getAllTimeHighRolling(filteredEntries, 30)
    val allTimeHighYear = getAllTimeHighRolling(filteredEntries, 365)

    return Habit(
        name = name,
        todayCount = countForDate,
        currentStreak = streakDisplay,
        longestStreak = longestStreak,
        allTimeHighDay = allTimeHighDayVal,
        allTimeHighDayDate = allTimeHighDayDate,
        useCustomInput = useCustomInput,
        currentDayValue = currentDayValue,
        avgLast7Days = avgLast7,
        avgLast30Days = avgLast30,
        avgLast365Days = avgLast365,
        allTimeHighWeek = allTimeHighWeek,
        allTimeHighMonth = allTimeHighMonth,
        allTimeHighYear = allTimeHighYear
    )
}
