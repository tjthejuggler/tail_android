package com.example.tail

import com.example.tail.notify.AppStatsRecordEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the pure app-stats record engine: near-record detection,
 * record-broken detection, the anti-spam "record episode" behaviour and
 * the fresh-record suppression (records set today/yesterday stay quiet).
 *
 * Records in these series are always peaked a few days back (daysAgo=5)
 * so the fresh-record rule (record date >= yesterday) doesn't mask them.
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

    /** Record of 40 set at daysAgo=5, baseline 10 elsewhere. */
    private val peakedTotals: (Int) -> Int = { if (it == 5) 40 else 10 }

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
        // Best day ever is 40 (daysAgo 5); today hits 45.
        val s = series(totals = peakedTotals, todayTotal = 45)
        val r = evaluate(s)
        val broken = r.evaluations.filter { it.verdict == AppStatsRecordEngine.Verdict.BROKEN }
        assertTrue(broken.any { it.metric == "best_day_points" })
        assertTrue(r.updatedStates.getValue("best_day_points").episodeNotified)
    }

    @Test
    fun `continuing to re-break the record does not spam`() {
        var states = emptyMap<String, AppStatsRecordEngine.MetricState>()
        // Day 1: break the record.
        var r = evaluate(series(totals = peakedTotals, todayTotal = 45), states)
        states = r.updatedStates
        assertEquals(1, r.evaluations.count { it.metric == "best_day_points" })
        // Day 2 (record now 45, set yesterday = fresh): today 50 — suppressed
        // both by the episode flag AND the fresh-record rule.
        r = evaluate(series(totals = { if (it == 1) 45 else if (it == 5) 40 else 10 }, todayTotal = 50), states)
        states = r.updatedStates
        assertTrue(
            r.evaluations.none { it.metric == "best_day_points" }
        )
        // Day 3: value collapses far below the record → episode re-arms.
        r = evaluate(series(totals = peakedTotals, todayTotal = 10), states)
        assertTrue(!r.updatedStates.getValue("best_day_points").episodeNotified)
    }

    @Test
    fun `near record detected within threshold`() {
        // Best day 40 (daysAgo 5), near threshold = max(2, 15%) = 6 → 35..39 is near.
        val r = evaluate(series(totals = peakedTotals, todayTotal = 36))
        val near = r.evaluations.filter {
            it.metric == "best_day_points" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
        }
        assertEquals(1, near.size)
        assertTrue(near[0].message.contains("40"))
    }

    @Test
    fun `record date is reported on near and broken messages`() {
        val near = evaluate(series(totals = peakedTotals, todayTotal = 36))
            .evaluations.first { it.metric == "best_day_points" }
        assertEquals(date(5), near.recordDate)
        assertTrue(near.message.contains(date(5)))

        val broken = evaluate(series(totals = peakedTotals, todayTotal = 45))
            .evaluations.first { it.metric == "best_day_points" }
        assertEquals(date(5), broken.recordDate)
        assertTrue(broken.message.contains(date(5)))
    }

    @Test
    fun `fresh record from yesterday suppresses near and broken`() {
        // Record 40 was set the day before engine-"today" (daysAgo 0 in
        // this helper, whose "today" slot is date(-1)) — riding it is not news.
        val freshTotals: (Int) -> Int = { if (it == 0) 40 else 10 }
        val near = evaluate(series(totals = freshTotals, todayTotal = 36))
        assertTrue(near.evaluations.none { it.metric == "best_day_points" })
        val broken = evaluate(series(totals = freshTotals, todayTotal = 45))
        assertTrue(broken.evaluations.none { it.metric == "best_day_points" })
    }

    @Test
    fun `far from record produces nothing for that metric`() {
        val r = evaluate(series(totals = peakedTotals, todayTotal = 20))
        assertTrue(r.evaluations.none { it.metric == "best_day_points" })
    }

    @Test
    fun `habits-done record near and broken`() {
        val peakedHabits: (Int) -> Int = { if (it == 5) 12 else 5 }
        val near = evaluate(series(habitCounts = peakedHabits, todayHabits = 11))
        assertTrue(
            near.evaluations.any {
                it.metric == "most_habits_day" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
        val broken = evaluate(series(habitCounts = peakedHabits, todayHabits = 13))
        assertTrue(
            broken.evaluations.any {
                it.metric == "most_habits_day" && it.verdict == AppStatsRecordEngine.Verdict.BROKEN
            }
        )
    }

    @Test
    fun `streak aggregates tracked`() {
        // Total streak days record 100 (daysAgo 5), today 102 → broken.
        val r = evaluate(
            series(streakSums = { if (it == 5) 100 else 20 }, todayStreakSum = 102)
        )
        assertTrue(
            r.evaluations.any {
                it.metric == "total_streak_days" && it.verdict == AppStatsRecordEngine.Verdict.BROKEN
            }
        )
        // Habits-with-streak record 8 (daysAgo 5), today 7 → near (threshold 1).
        val r2 = evaluate(
            series(streakCounts = { if (it == 5) 8 else 3 }, todayStreakCount = 7)
        )
        assertTrue(
            r2.evaluations.any {
                it.metric == "habits_with_streak" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
    }

    @Test
    fun `anti-streak records framed as caution`() {
        val r = evaluate(
            series(antiStreakSums = { if (it == 5) 50 else 5 }, todayAntiStreakSum = 55)
        )
        val ev = r.evaluations.first { it.metric == "total_anti_streak_days" }
        assertEquals(AppStatsRecordEngine.Verdict.BROKEN, ev.verdict)
        assertTrue(ev.title.startsWith("⚠️"))
    }

    @Test
    fun `rolling average near record`() {
        // Seven 24-pt days at daysAgo 3..9 → avg7 record 24.0 (dated daysAgo 3).
        // Today 32 keeps avg7 at (20+20+20+24+24+24+32)/7 = 24.0 — within the
        // 5% near window.
        val r = evaluate(
            series(
                historyDays = 30,
                totals = { if (it in 3..9) 24 else 20 },
                todayTotal = 32
            )
        )
        assertTrue(
            r.evaluations.any {
                it.metric == "avg7" && it.verdict == AppStatsRecordEngine.Verdict.NEAR
            }
        )
    }

    @Test
    fun `aggregate streak broken only when record run is not fresh`() {
        // History: 11 consecutive days ending YESTERDAY (daysAgo 10..0),
        // today also has points → run 12 > record 11 — but the record run
        // ended yesterday, so the fresh-record rule suppresses the notice.
        val s = series(
            historyDays = 20,
            totals = { daysAgo -> if (daysAgo in 0..10) 15 else 0 },
            todayTotal = 15
        )
        val r = evaluate(s)
        assertTrue(r.evaluations.none { it.metric == "aggregate_streak" })
    }

    @Test
    fun `broken records sort before near ones`() {
        val r = evaluate(
            series(
                totals = peakedTotals, todayTotal = 45,               // broken
                habitCounts = { if (it == 5) 12 else 5 }, todayHabits = 11   // near
            )
        )
        assertEquals(AppStatsRecordEngine.Verdict.BROKEN, r.evaluations.first().verdict)
    }

    // ── Daily "records held" summary ─────────────────────────────────────────

    @Test
    fun `currentRecords lists only records set the day before`() {
        // Record set "yesterday" (daysAgo 0 in this helper) → currently held.
        val records = AppStatsRecordEngine.currentRecords(
            series(totals = { if (it == 0) 40 else 10 }), date(-1)
        )
        val best = records.first { it.metric == "best_day_points" }
        assertEquals(40.0, best.value, 0.0)
        assertEquals("40", best.formatted)
    }

    @Test
    fun `currentRecords omits records not set the day before`() {
        // All-time best 40 was set daysAgo 5 — not a hill climbed yesterday.
        assertTrue(
            AppStatsRecordEngine.currentRecords(series(totals = peakedTotals), date(-1))
                .none { it.metric == "best_day_points" }
        )
    }

    @Test
    fun `currentRecords empty when all values are zero`() {
        val zeros: (Int) -> Int = { 0 }
        assertTrue(
            AppStatsRecordEngine.currentRecords(
                series(
                    historyDays = 0,
                    totals = zeros, habitCounts = zeros, streakSums = zeros,
                    antiStreakSums = zeros, streakCounts = zeros, antiStreakCounts = zeros
                ),
                date(-1)
            ).isEmpty()
        )
    }
}
