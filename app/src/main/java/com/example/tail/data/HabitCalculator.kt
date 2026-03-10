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
 * Gets the raw count for today from a habit's date map.
 */
fun getTodayCount(entries: Map<String, Int>): Int {
    val today = todayString()
    return entries[today] ?: 0
}

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
 * Calculates current streak (positive) or antistreak (negative) matching desktop logic.
 *
 * Desktop logic:
 *   days_since_not_zero = index of first non-zero from most-recent end
 *   days_since_zero      = index of first zero from most-recent end
 *   if days_since_not_zero < 2: left_number = days_since_zero (positive)
 *   else:                        left_number = -days_since_not_zero (negative)
 */
fun calculateStreakDisplay(entries: Map<String, Int>): Int {
    if (entries.isEmpty()) return 0
    val sorted = entries.keys.sorted().reversed()

    val daysSinceNotZero = sorted.indexOfFirst { entries[it] != 0 }
        .let { if (it == -1) sorted.size else it }

    return if (daysSinceNotZero < 2) {
        // Currently on a streak — count days since last zero (skipping index 0)
        val daysSinceZeroMinus = sorted.drop(1).indexOfFirst { entries[it] == 0 }
            .let { if (it == -1) sorted.size else it }
        daysSinceZeroMinus
    } else {
        // On an antistreak
        -daysSinceNotZero
    }
}

/**
 * Calculates the longest streak of consecutive non-zero days.
 */
fun calculateLongestStreak(entries: Map<String, Int>): Int {
    var longest = 0
    var current = 0
    for ((_, value) in entries.entries.sortedBy { it.key }) {
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
 * Returns the all-time high single-day count.
 */
fun calculateAllTimeHighDay(entries: Map<String, Int>): Int {
    return entries.values.maxOrNull() ?: 0
}

/**
 * Builds a [Habit] display object from raw database entries.
 */
fun buildHabit(
    name: String,
    entries: Map<String, Int>,
    useCustomInput: Boolean
): Habit {
    val todayCount = getTodayCount(entries)
    val streakDisplay = calculateStreakDisplay(entries)
    val longestStreak = calculateLongestStreak(entries)
    val allTimeHighDay = calculateAllTimeHighDay(entries)
    return Habit(
        name = name,
        todayCount = todayCount,
        currentStreak = streakDisplay,
        longestStreak = longestStreak,
        allTimeHighDay = allTimeHighDay,
        useCustomInput = useCustomInput
    )
}
