package com.example.tail

import com.example.tail.data.buildHabit
import com.example.tail.data.calculateStreakDisplay
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Regression tests for [calculateStreakDisplay].
 *
 * The 2026-10-19 epoch-day-walk rewrite (commit 26cf66f, "fix bad data crash")
 * introduced two bugs:
 *
 *  1. The backwards cursor only tested `days[i].first == d` instead of skipping
 *     entries newer than `d`. After re-arming at `days.lastIndex` and starting
 *     at `lastDay - 1`, the first read compared `lastDay == lastDay - 1` → false
 *     → 0, so every positive streak broke out of its run loop immediately and
 *     rendered as "+0".
 *
 *  2. An all-zero history returned `daysSinceNotZero` as a POSITIVE number,
 *     so habits that had never been done (Sunburn, Weights Triceps, Weights
 *     Legs, …) showed "+N" instead of the antistreak "-N".
 *
 * These tests pin the desktop-parity semantics documented on the function.
 */
class StreakDisplayTest {

    private val d1 = LocalDate.of(2026, 8, 11)
    private val d2 = LocalDate.of(2026, 8, 12)
    private val d3 = LocalDate.of(2026, 8, 13)
    private val d4 = LocalDate.of(2026, 8, 14)
    private val d5 = LocalDate.of(2026, 8, 15)

    private fun entriesOf(vararg pairs: Pair<LocalDate, Int>): Map<String, Int> =
        pairs.associate { it.first.toString() to it.second }

    // ── positive streaks (the "+0" regression) ──────────────────────────────

    @Test
    fun `a contiguous run ending today reports the full run length`() {
        val entries = entriesOf(d1 to 1, d2 to 1, d3 to 1, d4 to 1, d5 to 1)
        assertEquals(5, calculateStreakDisplay(entries, d5))
    }

    @Test
    fun `a ten day streak is not reported as zero`() {
        val start = LocalDate.of(2026, 9, 1)
        val entries = (0 until 10).associate { start.plusDays(it.toLong()).toString() to 1 }
        assertEquals(10, calculateStreakDisplay(entries, start.plusDays(9)))
    }

    @Test
    fun `a streak that started after an earlier zero reports the run length`() {
        // zero, then four done days ending today. Desktop parity: the run is
        // counted from the day BEFORE the most recent one, so a streak that
        // does not reach the start of history reports run - 1.
        val entries = entriesOf(d1 to 0, d2 to 1, d3 to 1, d4 to 1, d5 to 1)
        assertEquals(3, calculateStreakDisplay(entries, d5))
    }

    @Test
    fun `today still at zero keeps the streak alive via the index-0 skip`() {
        // Desktop parity: the most recent day is skipped, so a not-yet-done
        // today does not break the streak.
        val entries = entriesOf(d1 to 1, d2 to 1, d3 to 1, d4 to 1, d5 to 0)
        assertEquals(5, calculateStreakDisplay(entries, d5))
    }

    @Test
    fun `calendar gaps inside a streak break the run`() {
        // d2 missing → treated as a zero day
        val entries = entriesOf(d1 to 1, d3 to 1, d4 to 1)
        assertEquals(1, calculateStreakDisplay(entries, d4))
    }

    @Test
    fun `a single done day reports one`() {
        assertEquals(1, calculateStreakDisplay(entriesOf(d1 to 1), d1))
    }

    // ── antistreaks ─────────────────────────────────────────────────────────

    @Test
    fun `consecutive zero days report a negative antistreak`() {
        val entries = entriesOf(d1 to 1, d2 to 0, d3 to 0, d4 to 0)
        assertEquals(-3, calculateStreakDisplay(entries, d4))
    }

    @Test
    fun `a target date beyond the last entry extends the antistreak`() {
        // Sparse Garmin-style history: only activity days are stored.
        val entries = entriesOf(d1 to 1, d2 to 1)
        assertEquals(-3, calculateStreakDisplay(entries, d5))
    }

    // ── never-done habits (the bogus "+N" regression) ───────────────────────

    @Test
    fun `a habit that was never done reports a negative antistreak`() {
        val entries = entriesOf(d1 to 0, d2 to 0, d3 to 0, d4 to 0)
        assertEquals(-4, calculateStreakDisplay(entries, d4))
    }

    @Test
    fun `a habit created today and never done reports minus one`() {
        assertEquals(-1, calculateStreakDisplay(entriesOf(d5 to 0), d5))
    }

    @Test
    fun `a never-done habit with a long zero history reports the full span`() {
        val start = LocalDate.of(2026, 1, 1)
        val entries = (0 until 100).associate { start.plusDays(it.toLong()).toString() to 0 }
        assertEquals(-100, calculateStreakDisplay(entries, start.plusDays(99)))
    }

    // ── buildHabit integration ──────────────────────────────────────────────

    @Test
    fun `buildHabit surfaces a positive streak for a normal habit`() {
        val entries = entriesOf(d1 to 1, d2 to 1, d3 to 1, d4 to 1)
        val habit = buildHabit("Pushups", entries, useCustomInput = false, targetDate = d4)
        assertEquals(4, habit.currentStreak)
    }

    @Test
    fun `buildHabit surfaces an antistreak for a never-done habit`() {
        val entries = entriesOf(d1 to 0, d2 to 0, d3 to 0, d4 to 0)
        val habit = buildHabit("Sunburn", entries, useCustomInput = false, targetDate = d4)
        assertEquals(-4, habit.currentStreak)
    }

    @Test
    fun `buildHabit keeps inverted-binary streaks positive on clean days`() {
        // Inverted habits use calculateInvertedStreakDisplay and must be
        // unaffected by the normal-path fix.
        val entries = entriesOf(d1 to 1, d2 to 0, d3 to 0, d4 to 0)
        val habit = buildHabit("Coffee", entries, useCustomInput = false, targetDate = d4, invertedBinary = true)
        assertEquals(3, habit.currentStreak)
    }
}
