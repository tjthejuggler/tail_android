package com.example.tail

import com.example.tail.data.buildHabit
import com.example.tail.data.computeTaskerStats
import com.example.tail.data.invertedBinaryPoints
import com.example.tail.data.invertedBinaryPointsForDate
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
        val entries = entriesOf(day1 to 1, day3 to 2, day4 to 0)
        val inverted = invertEntriesForInvertedBinary(entries, day4)
        assertEquals(0, inverted[day1.toString()]) // 1 coffee → failure
        assertEquals(1, inverted[day2.toString()]) // missing day → 0 coffees → success
        assertEquals(0, inverted[day3.toString()]) // 2 coffees → failure
        assertEquals(1, inverted[day4.toString()])
    }

    @Test
    fun `explicit zero entries before the first non-zero day are ignored`() {
        // Desktop-synced DBs record explicit 0s for every calendar day — those
        // pre-tracking days must NOT count as clean-day successes.
        val entries = entriesOf(day1 to 0, day2 to 0, day3 to 1, day4 to 0)
        val inverted = invertEntriesForInvertedBinary(entries, day4)
        assertEquals(null, inverted[day1.toString()]) // before first data → absent
        assertEquals(null, inverted[day2.toString()]) // before first data → absent
        assertEquals(0, inverted[day3.toString()])    // first coffee → failure
        assertEquals(1, inverted[day4.toString()])    // clean day → success
    }

    // ── buildHabit: streak semantics ────────────────────────────────────────

    @Test
    fun `clean days build a positive streak with the square orange`() {
        // A coffee on day1 (first data), then three consecutive zero days
        val entries = entriesOf(day1 to 1, day2 to 0, day3 to 0, day4 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(3, habit.currentStreak)
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
        // First coffee day1, clean day2; viewing day4 (no entries yet)
        val entries = entriesOf(day1 to 1, day2 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(3, habit.currentStreak) // day2..day4 clean
        assertEquals(1, habit.todayCount)
    }

    @Test
    fun `a habit with only zero entries has no inverted streak at all`() {
        // Never actually done → tracking never started → no streak, no points
        val entries = entriesOf(day1 to 0, day2 to 0, day3 to 0, day4 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = day4, invertedBinary = true)
        assertEquals(0, habit.currentStreak)
        assertEquals(0, habit.todayCount)
        assertEquals(0, habit.longestStreak)
    }

    @Test
    fun `inverted flag is carried on the habit`() {
        val habit = buildHabit("Coffee", entriesOf(day1 to 0), useCustomInput = false, targetDate = day1, invertedBinary = true)
        assertEquals(true, habit.invertedBinary)
    }

    // ── computeTaskerStats ───────────────────────────────────────────────────

    @Test
    fun `tasker totals include inverted points on clean days`() {
        val today = LocalDate.of(2026, 8, 14)
        val db = mapOf(
            // Coffee first done day3, clean today → 1 point
            "Coffee" to entriesOf(day3 to 1, day4 to 0),
            "Pushups" to entriesOf(day4 to 25)          // normal habit → 25 points
        )
        val stats = computeTaskerStats(
            db = db,
            dividers = emptyMap(),
            noPointsHabits = emptySet(),
            today = today,
            invertedBinaryHabits = setOf("Coffee")
        )
        assertEquals(26, stats.today)
    }

    @Test
    fun `tasker totals give zero points on done days`() {
        val today = LocalDate.of(2026, 8, 14)
        val db = mapOf(
            "Coffee" to entriesOf(day4 to 2)           // 2 coffees today → 0 points
        )
        val stats = computeTaskerStats(
            db = db,
            dividers = emptyMap(),
            noPointsHabits = emptySet(),
            today = today,
            invertedBinaryHabits = setOf("Coffee")
        )
        assertEquals(0, stats.today)
    }

    // ── no retroactive points before the habit's first data ────────────────

    @Test
    fun `dates before the first non-zero entry earn no inverted points`() {
        // Leading explicit zeros predate actual tracking — they earn nothing
        val entries = entriesOf(day1 to 0, day3 to 1, day4 to 0)
        assertEquals(0, invertedBinaryPointsForDate(entries, day1.toString()))
        assertEquals(0, invertedBinaryPointsForDate(entries, day2.toString()))
        // From the first non-zero entry onward the normal inverted semantics apply
        assertEquals(0, invertedBinaryPointsForDate(entries, day3.toString())) // done day
        assertEquals(1, invertedBinaryPointsForDate(entries, day4.toString())) // clean day
    }

    @Test
    fun `a habit with no actual data earns nothing`() {
        assertEquals(0, invertedBinaryPointsForDate(emptyMap(), day4.toString()))
        assertEquals(0, invertedBinaryPointsForDate(entriesOf(day1 to 0, day2 to 0), day4.toString()))
    }

    @Test
    fun `tasker totals give no inverted points for days before first data`() {
        val today = LocalDate.of(2026, 8, 14)
        val db = mapOf(
            // Coffee first done day2 — day1 (explicit zero) must earn nothing
            "Coffee" to entriesOf(day2 to 1, day3 to 0, day4 to 0),
            "Pushups" to entriesOf(day1 to 10, day2 to 10, day3 to 10, day4 to 10)
        )
        val stats = computeTaskerStats(
            db = db,
            dividers = emptyMap(),
            noPointsHabits = emptySet(),
            today = today,
            invertedBinaryHabits = setOf("Coffee")
        )
        // today: 10 pushups + 1 clean coffee day
        assertEquals(11, stats.today)
        // avg7 covers 2026-08-08..14; coffee contributes only on day3/day4 (2),
        // pushups contribute 10 × 4 days = 40 → total 42 / 7 = 6.0
        assertEquals(6.0, stats.avg7, 0.001)
    }
}
