package com.example.tail

import com.example.tail.notify.AppStatsRecordEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the pure app-stats record engine: near-record detection,
 * record-broken detection and the anti-spam "record episode" behaviour.
 */
class AppStatsRecordEngineTest {

    private fun date(daysAgo: Int): String =
        LocalDate.now().minusDays(daysAgo.toLong()).toString()

    /** Builds a series with [historyDays] days of history plus today. */
    private fun series(
        historyDays: Int = 20,
        totals: (Int) -> Int = { 10 },           // daysAgo -> points
        habitCounts: (Int) -> Int = { 5 },
        streakSums: (Int) -> Int = { 20 },
        antiStreakSums: (Int) -> Int = { 0 },
        streakCounts: (Int) -> Int = { 4 },
        antiStreakCounts: (Int) -> Int = { 0 },
        todayTotal: Int? = null,
        todayHabits: Int? = null,
        todayStreakSum: Int? = null,
        todayAntiStreakSum: Int? = null,
        todayStreakCount: Int? = null,
        todayAntiStreakCount: Int? = null
    ): AppStatsRecordEngine.Series {
        val days = (historyDays downTo 0).map { date(it) } + listOf(date(-1))
        // Index → daysAgo (last index is "today" = -1).
        fun daysAgoOf(i: Int): Int =
            if (i == days.size - 1) -1 else historyDays - i
        fun histOrToday(i: Int, hist: (Int) -> Int, today: Int?): Int =
            if (daysAgoOf(i) == -1) (today ?: hist(1)) else hist(daysAgoOf(i))
        return AppStatsRecordEngine.Series(
            dates = days,
            dailyTotals = days.indices.map { i -> histOrToday(i, totals, todayTotal) },
            dailyHabitCounts = days.indices.map { i -> histOrToday(i, habitCounts, todayHabits) },
            dailyStreakSums = days.indices.map { i -> histOrToday(i, streakSums, todayStreakSum) },
            dailyAntiStreakSums = days.indices.map { i -> histOrToday(i, antiStreakSums, todayAntiStreakSum) },
            dailyStreakCounts = days.indices.map { i -> histOrToday(i, streakCounts, todayStreakCount) },
            dailyAntiStreakCounts = days.indices.map { i -> histOrToday(i, antiStreakCounts, todayAntiStreakCount) }
        )
    }

    private fun evaluate(
        s: AppStatsRecordEngine.Series,
        states: Map<String, AppStatsRecordEngine.MetricState> = emptyMap()
    ): AppStatsRecordEngine.Result = AppStatsRecordEngine.evaluate(s, date(-1), states)

    @Test
    fun `too little history produces nothing`() {
        val r = evaluate(series(historyDays = 5))
        assertTrue(r.evaluations.isEmpty())
    }

    @Test
    fun `record broken fires once`() {
        // Best day ever is 40; today hits 45.
        val s = series(totals = { 40 }, todayTotal = 45)
        val r = evaluate(s)
        val broken = r.evaluations.filter { it.verdict == AppStatsRecordEngine.Verdict.BROKEN }
        assertTrue(broken.any { it.metric == "best_day_points" })
        assertTrue(r.updatedStates.getValue("best_day_points").episodeNotified)
    }

    @Test
    fun `continuing to re-break the record does not spam`() {
        var states = emptyMap<String, AppStatsRecordEngine.MetricState>()
        // Day 1: break the record.
        var r = evaluate(series(totals = { 40 }, todayTotal = 45), states)
        states = r.updatedStates
        assertEquals(1, r.evaluations.count { it.metric == "best_day_points" })
        // Day 2 (record now 45): today 50 — still above, but episode already
        // notified → no further notification for this metric.
        r = evaluate(series(totals = { if (it == 1) 45 else 40 }, todayTotal = 50), states)
        states = r.updatedStates
        assertTrue(
            r.evaluations.none { it.metric == "best_day_points" }
        )
        // Day 3: value collapses far below the record → episode re-arms.
        r = evaluate(series(totals = { 40 }, todayTotal = 10), states)
        assertTrue(!r.updatedStates.getValue("best_day_points").episodeNotified)
    }

    @Test
    fun `near record detected within threshold`() {
        // Best day 40, near threshold = max(2, 15%) = 6 → 35..39 is near.
        val r = evaluate(series(totals = { 40 }, todayTotal = 36))
        val near = r.evaluations.filter {
            it.metric == "best_day_points" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
        }
        assertEquals(1, near.size)
        assertTrue(near[0].message.contains("40"))
    }

    @Test
    fun `far from record produces nothing for that metric`() {
        val r = evaluate(series(totals = { 40 }, todayTotal = 20))
        assertTrue(r.evaluations.none { it.metric == "best_day_points" })
    }

    @Test
    fun `habits-done record near and broken`() {
        val near = evaluate(series(habitCounts = { 12 }, todayHabits = 11))
        assertTrue(
            near.evaluations.any {
                it.metric == "most_habits_day" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
        val broken = evaluate(series(habitCounts = { 12 }, todayHabits = 13))
        assertTrue(
            broken.evaluations.any {
                it.metric == "most_habits_day" && it.verdict == AppStatsRecordEngine.Verdict.BROKEN
            }
        )
    }

    @Test
    fun `streak aggregates tracked`() {
        // Total streak days record 100, today 102 → broken.
        val r = evaluate(series(streakSums = { 100 }, todayStreakSum = 102))
        assertTrue(
            r.evaluations.any {
                it.metric == "total_streak_days" && it.verdict == AppStatsRecordEngine.Verdict.BROKEN
            }
        )
        // Habits-with-streak record 8, today 7 → near (threshold 1).
        val r2 = evaluate(series(streakCounts = { 8 }, todayStreakCount = 7))
        assertTrue(
            r2.evaluations.any {
                it.metric == "habits_with_streak" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
    }

    @Test
    fun `anti-streak records framed as caution`() {
        val r = evaluate(series(antiStreakSums = { 50 }, todayAntiStreakSum = 55))
        val ev = r.evaluations.first { it.metric == "total_anti_streak_days" }
        assertEquals(AppStatsRecordEngine.Verdict.BROKEN, ev.verdict)
        assertTrue(ev.title.startsWith("⚠️"))
    }

    @Test
    fun `rolling average near record`() {
        // Constant 20 pts/day history → avg7 record 20.0. Today 19 points
        // keeps avg7 at (6*20+19)/7 = 19.857 — within the 5% near window.
        val r = evaluate(series(historyDays = 30, totals = { 20 }, todayTotal = 19))
        assertTrue(
            r.evaluations.any {
                it.metric == "avg7" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
    }

    @Test
    fun `aggregate streak broken and non spamming`() {
        // History: 11 consecutive days with points (days ago 10..0), today
        // also has points → current run 12 > record 11 → broken.
        val s = series(
            historyDays = 20,
            totals = { daysAgo -> if (daysAgo in 0..10) 15 else 0 },
            todayTotal = 15
        )
        val r = evaluate(s)
        assertTrue(
            r.evaluations.any {
                it.metric == "aggregate_streak" && it.verdict == AppStatsRecordEngine.Verdict.BROKEN
            }
        )
        // Episode flag set → a further check with the same shape is silent.
        val r2 = AppStatsRecordEngine.evaluate(s, date(-1), r.updatedStates)
        assertTrue(r2.evaluations.none { it.metric == "aggregate_streak" })
    }

    @Test
    fun `broken records sort before near ones`() {
        val r = evaluate(
            series(
                totals = { 40 }, todayTotal = 45,          // broken
                habitCounts = { 12 }, todayHabits = 11     // near
            )
        )
        assertEquals(AppStatsRecordEngine.Verdict.BROKEN, r.evaluations.first().verdict)
    }
}
