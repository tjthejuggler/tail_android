package com.example.tail

import com.example.tail.data.buildHabit
import com.example.tail.data.buildTaskerStatsContent
import com.example.tail.data.invertedBinaryPoints
import com.example.tail.data.invertEntriesForInvertedBinary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the "inverted binary" habit type (e.g. coffee tracking):
 * tapping logs an occurrence, but points and streaks are inverted —
 * a day with no taps earns 1 point and extends the streak, while a day
 * with one or more taps earns 0 points and breaks the streak.
 */
class InvertedBinaryHabitTest {

    private val day1 = LocalDate.of(2026, 8, 11)
    private val day2 = LocalDate.of(2026, 8, 12)
    private val day3 = LocalDate.of(2026, 8, 13)
    private val day4 = LocalDate.of(2026, 8, 14)

    private fun entriesOf(vararg pairs: Pair<LocalDate, Int>): Map<String, Int> =
        pairs.associate { it.first.toString() to it.second }

    // ── invertedBinaryPoints ────────────────────────────────────────────────

    @Test
    fun `zero raw count earns one point`() {
        assertEquals(1, invertedBinaryPoints(0))
    }

    @Test
    fun `any non-zero raw count earns zero points`() {
        assertEquals(0, invertedBinaryPoints(1))
        assertEquals(0, invertedBinaryPoints(3))
    }

    // ── invertEntriesForInvertedBinary ──────────────────────────────────────

    @Test
    fun `inversion flips zero to one and non-zero to zero across calendar gaps`() {
        // Sparse map: day2 missing (a clean no-coffee day via calendar expansion)
        val entries = entriesOf(day1 to 0, day3 to 2, day4 to 0)
        val inverted = invertEntriesForInvertedBinary(entries, day4)
        assertEquals(1, inverted[day1.toString()]) // 0 coffees → success
        assertEquals(1, inverted[day2.toString()]) // missing day → 0 coffees → success
        assertEquals(0, inverted[day3.toString()]) // 2 coffees → failure
        assertEquals(1, inverted[day4.toString()])
    }

    // ── buildHabit: streak semantics ────────────────────────────────────────

    @Test
    fun `clean days build a positive streak even though the square is red`() {
        // Four consecutive zero days ending on day4
        val entries = entriesOf(day1 to 0, day2 to 0, day3 to 0, day4 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(4, habit.currentStreak)
        assertEquals(1, habit.todayCount)   // clean day → 1 point
        assertEquals(0, habit.rawTodayCount)
    }

    @Test
    fun `tapping today breaks the streak into an antistreak`() {
        // Three clean days, then a coffee today
        val entries = entriesOf(day1 to 0, day2 to 0, day3 to 0, day4 to 1)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(-1, habit.currentStreak) // broken today
        assertEquals(0, habit.todayCount)     // done day → 0 points
        assertEquals(1, habit.rawTodayCount)
    }

    @Test
    fun `consecutive done days deepen the antistreak`() {
        val entries = entriesOf(day1 to 0, day2 to 1, day3 to 2, day4 to 1)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(-3, habit.currentStreak)
    }

    @Test
    fun `longest streak counts the longest run of clean days`() {
        // Clean run of 2 (day1-2), coffee, clean run of 2 (day4-5)
        val day5 = LocalDate.of(2026, 8, 15)
        val entries = entriesOf(day1 to 0, day2 to 0, day3 to 1, day4 to 0, day5 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day5, invertedBinary = true)
        assertEquals(2, habit.longestStreak)
    }

    @Test
    fun `all-time high day stays on the raw counts`() {
        // Most coffees ever drunk in one day is 3 (day2)
        val entries = entriesOf(day1 to 1, day2 to 3, day3 to 0, day4 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(3, habit.allTimeHighDay)
    }

    @Test
    fun `target date beyond last entry counts as a clean day`() {
        // Last entry on day2; viewing day4 (no entries yet) — both days clean
        val entries = entriesOf(day1 to 0, day2 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(4, habit.currentStreak) // day1..day4 all clean
        assertEquals(1, habit.todayCount)
    }

    @Test
    fun `inverted flag is carried on the habit`() {
        val habit = buildHabit("Coffee", entriesOf(day1 to 0), useCustomInput = false, targetDate = day1, invertedBinary = true)
        assertEquals(true, habit.invertedBinary)
    }

    // ── buildTaskerStatsContent ─────────────────────────────────────────────

    @Test
    fun `tasker totals include inverted points on clean days`() {
        val today = LocalDate.of(2026, 8, 14)
        val db = mapOf(
            "Coffee" to entriesOf(day4 to 0),          // clean today → 1 point
            "Pushups" to entriesOf(day4 to 25)          // normal habit → 25 points
        )
        val content = buildTaskerStatsContent(
            db = db,
            dividers = emptyMap(),
            noPointsHabits = emptySet(),
            today = today,
            invertedBinaryHabits = setOf("Coffee")
        )
        assertEquals("today=26", content.lineSequence().first())
    }

    @Test
    fun `tasker totals give zero points on done days`() {
        val today = LocalDate.of(2026, 8, 14)
        val db = mapOf(
            "Coffee" to entriesOf(day4 to 2)           // 2 coffees today → 0 points
        )
        val content = buildTaskerStatsContent(
            db = db,
            dividers = emptyMap(),
            noPointsHabits = emptySet(),
            today = today,
            invertedBinaryHabits = setOf("Coffee")
        )
        assertEquals("today=0", content.lineSequence().first())
    }
}
