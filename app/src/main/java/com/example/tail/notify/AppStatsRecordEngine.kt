package com.example.tail.notify

/**
 * ════════════════════════════════════════════════════════════════════════
 * App Stats record notifications — PURE evaluation engine
 * ════════════════════════════════════════════════════════════════════════
 *
 * Decides, from the app-wide daily series (total points, habits done,
 * streak aggregates), whether the user is CLOSE TO breaking an all-time
 * app-stats record or HAS JUST broken one. Only multi-habit aggregate
 * records are considered — never single-habit records.
 *
 * Everything here is PURE (no Android dependencies) so it is unit-testable
 * on the JVM, mirroring the other calculators.
 *
 * Anti-spam contract ("record episode"):
 *  - When a metric first exceeds its all-time record (computed over days
 *    STRICTLY BEFORE today), one BROKEN notification fires and the metric's
 *    episode flag is set. While the user keeps riding that record (re-
 *    breaking it every day as it grows), no further notifications fire.
 *  - The flag resets once the value falls clearly below the record
 *    (< 95% of it), so a genuinely NEW record run later notifies again.
 *  - NEAR notifications only fire while the value is below the record and
 *    no episode is active, and are deduplicated per day by notification id.
 */
object AppStatsRecordEngine {

    /** Minimum days of history before any record notification may fire. */
    const val MIN_HISTORY_DAYS = 14

    /** A metric counts as "out of the record episode" below this fraction. */
    const val EPISODE_RESET_FRACTION = 0.95

    enum class Verdict { NONE, NEAR, BROKEN }

    /** One metric's evaluation outcome. */
    data class Evaluation(
        val metric: String,
        val verdict: Verdict,
        val currentValue: Double,
        val recordValue: Double,
        val title: String,
        val message: String
    )

    /**
     * Daily aggregate series, date-sorted ascending ("yyyy-MM-dd" keys).
     * All lists must cover the SAME ordered dates.
     *
     * @param dailyTotals date → total effective points across all point habits
     * @param dailyHabitCounts date → number of habits with points > 0
     * @param dailyStreakSums date → sum of current per-habit streaks (enabled habits)
     * @param dailyAntiStreakSums date → sum of per-habit anti-streaks
     * @param dailyStreakCounts date → number of habits with an active streak
     * @param dailyAntiStreakCounts date → number of habits with an active anti-streak
     */
    data class Series(
        val dates: List<String>,
        val dailyTotals: List<Int>,
        val dailyHabitCounts: List<Int>,
        val dailyStreakSums: List<Int>,
        val dailyAntiStreakSums: List<Int>,
        val dailyStreakCounts: List<Int>,
        val dailyAntiStreakCounts: List<Int>
    )

    /** Persisted per-metric state (episode flag) — see anti-spam contract. */
    data class MetricState(val episodeNotified: Boolean = false)

    /**
     * Result of [evaluate]: the notifications worth posting (broken first,
     * then near) plus the updated per-metric episode states to persist.
     */
    data class Result(
        val evaluations: List<Evaluation>,
        val updatedStates: Map<String, MetricState>
    )

    private data class MetricDef(
        val key: String,
        val label: String,
        val unit: String,
        /** Formats a value for display (ints without decimals). */
        val fmt: (Double) -> String,
        /** Near-record threshold: value within this of the record (absolute). */
        val nearAbs: Double,
        /** Near-record threshold as a fraction of the record (used when larger). */
        val nearFrac: Double,
        /** Emoji + tone; "caution" metrics are the anti-streak family. */
        val caution: Boolean = false
    )

    private val intFmt: (Double) -> String = { v -> v.toInt().toString() }
    private val oneDecFmt: (Double) -> String = { v -> "%.1f".format(v) }
    private val twoDecFmt: (Double) -> String = { v -> "%.2f".format(v) }

    private val METRICS = listOf(
        MetricDef("best_day_points", "best single day (points)", "pts", intFmt, 2.0, 0.15),
        MetricDef("most_habits_day", "most habits done in one day", "habits", intFmt, 1.0, 0.0),
        MetricDef("avg7", "7-day average", "pts/day", oneDecFmt, 0.0, 0.05),
        MetricDef("avg30", "30-day average", "pts/day", oneDecFmt, 0.0, 0.03),
        MetricDef("avg90", "90-day average", "pts/day", oneDecFmt, 0.0, 0.02),
        MetricDef("avg365", "365-day average", "pts/day", twoDecFmt, 0.0, 0.01),
        MetricDef("total_streak_days", "total streak days (all habits)", "days", intFmt, 2.0, 0.05),
        MetricDef("habits_with_streak", "habits with an active streak", "habits", intFmt, 1.0, 0.0),
        MetricDef(
            "total_anti_streak_days", "total anti-streak days (all habits)", "days",
            intFmt, 2.0, 0.05, caution = true
        ),
        MetricDef(
            "habits_with_anti_streak", "habits with an active anti-streak", "habits",
            intFmt, 1.0, 0.0, caution = true
        ),
        MetricDef("aggregate_streak", "day streak with any points", "days", intFmt, 2.0, 0.05)
    )

    /**
     * Evaluates every metric against the series.
     *
     * @param today "yyyy-MM-dd" — the day whose value is the live candidate;
     *   records are computed only over dates strictly before it.
     */
    fun evaluate(series: Series, today: String, states: Map<String, MetricState>): Result {
        if (series.dates.isEmpty()) return Result(emptyList(), states)

        val historyIdx = series.dates.indexOfLast { it < today }
        val historyCount = historyIdx + 1
        if (historyCount < MIN_HISTORY_DAYS) return Result(emptyList(), states)

        val todayIdx = series.dates.indexOf(today)

        // Pre-compute per-metric (current, record) pairs.
        val values = mutableMapOf<String, Pair<Double, Double>>()

        fun lastTodayOr(list: List<Int>): Double =
            (if (todayIdx >= 0) list[todayIdx] else list.last()).toDouble()

        fun historyMax(list: List<Int>): Double =
            (0..historyIdx).maxOf { list[it] }.toDouble()

        values["best_day_points"] = lastTodayOr(series.dailyTotals) to
            historyMax(series.dailyTotals)
        values["most_habits_day"] = lastTodayOr(series.dailyHabitCounts) to
            historyMax(series.dailyHabitCounts)
        values["total_streak_days"] = lastTodayOr(series.dailyStreakSums) to
            historyMax(series.dailyStreakSums)
        values["habits_with_streak"] = lastTodayOr(series.dailyStreakCounts) to
            historyMax(series.dailyStreakCounts)
        values["total_anti_streak_days"] = lastTodayOr(series.dailyAntiStreakSums) to
            historyMax(series.dailyAntiStreakSums)
        values["habits_with_anti_streak"] = lastTodayOr(series.dailyAntiStreakCounts) to
            historyMax(series.dailyAntiStreakCounts)

        // Rolling averages: calendar-day windows, missing days contribute 0
        // (matches computeTaskerStats semantics).
        for (n in listOf(7, 30, 90, 365)) {
            val key = "avg$n"
            val totalsByDate = series.dates.zip(series.dailyTotals).toMap()
            val cur = calendarAvg(totalsByDate, today, n)
            var best = 0.0
            for (i in 0..historyIdx) {
                val avg = calendarAvg(totalsByDate, series.dates[i], n)
                if (avg > best) best = avg
            }
            values[key] = cur to best
        }

        // Aggregate streak: longest run of consecutive calendar days with
        // points > 0 in history, vs the run ending today.
        values["aggregate_streak"] = aggregateStreak(series, todayIdx, historyIdx)

        val out = mutableListOf<Evaluation>()
        val updated = mutableMapOf<String, MetricState>()

        for (m in METRICS) {
            val (current, record) = values[m.key] ?: continue
            val state = states[m.key] ?: MetricState()
            if (record <= 0.0) {
                updated[m.key] = state
                continue
            }
            val threshold = maxOf(m.nearAbs, m.nearFrac * record)
            val brokenNow = current > record

            when {
                brokenNow && !state.episodeNotified -> {
                    out += Evaluation(
                        metric = m.key,
                        verdict = Verdict.BROKEN,
                        currentValue = current,
                        recordValue = record,
                        title = if (m.caution) "⚠️ New all-time high: ${m.label}"
                        else "🏆 New all-time record: ${m.label}!",
                        message = if (m.caution)
                            "You've hit a new all-time high for ${m.label}: ${m.fmt(current)} ${m.unit} " +
                                "(previous record ${m.fmt(record)})."
                        else
                            "${m.fmt(current)} ${m.unit} — previous record was ${m.fmt(record)} " +
                                "set before today. Keep it going!"
                    )
                    updated[m.key] = MetricState(episodeNotified = true)
                }
                brokenNow -> updated[m.key] = MetricState(episodeNotified = true)
                // Clearly below the record → the record-holding episode is
                // over; re-arm so a future new record notifies again.
                current < record * EPISODE_RESET_FRACTION ->
                    updated[m.key] = MetricState(episodeNotified = false)
                else -> updated[m.key] = state
            }

            // Near-record nudge: only while below the record, outside an
            // active episode, and within the metric's closeness threshold.
            if (!brokenNow && !state.episodeNotified && current < record &&
                record - current <= threshold && current > 0
            ) {
                out += Evaluation(
                    metric = m.key,
                    verdict = Verdict.NEAR,
                    currentValue = current,
                    recordValue = record,
                    title = if (m.caution) "⚠️ Approaching record: ${m.label}"
                    else "📈 Record within reach: ${m.label}",
                    message = if (m.caution)
                        "You're at ${m.fmt(current)} ${m.unit} vs the all-time high of " +
                            "${m.fmt(record)} — worth turning around."
                    else
                        "Currently ${m.fmt(current)} ${m.unit} vs the all-time record of " +
                            "${m.fmt(record)} — only ${m.fmt(record - current)} to go!"
                )
            }
        }

        // Broken records first, then near-records.
        out.sortWith(compareBy({ it.verdict != Verdict.BROKEN }, { it.metric }))
        return Result(out, updated)
    }

    /** Mean daily total over the [n] calendar days ending on [endDate]. */
    private fun calendarAvg(totalsByDate: Map<String, Int>, endDate: String, n: Int): Double {
        val end = java.time.LocalDate.parse(endDate)
        var sum = 0
        for (i in 0 until n) {
            sum += totalsByDate[end.minusDays(i.toLong()).toString()] ?: 0
        }
        return sum.toDouble() / n
    }

    /**
     * (current, record) for the any-points day streak: the run ending at
     * [todayIdx] (0 when today has no points yet) vs the longest run within
     * history. Calendar gaps break runs (missing days are zero-days).
     */
    private fun aggregateStreak(series: Series, todayIdx: Int, historyIdx: Int): Pair<Double, Double> {
        fun runEndingAt(idx: Int): Int {
            var run = 0
            var i = idx
            if (i < 0 || series.dailyTotals[i] <= 0) return 0
            while (i >= 0 && series.dailyTotals[i] > 0) {
                if (run > 0) {
                    val prev = java.time.LocalDate.parse(series.dates[i])
                    val curr = java.time.LocalDate.parse(series.dates[i + 1])
                    if (java.time.temporal.ChronoUnit.DAYS.between(prev, curr) > 1L) break
                }
                run++
                i--
            }
            return run
        }

        var longest = 0
        for (i in 0..historyIdx) {
            val r = runEndingAt(i)
            if (r > longest) longest = r
        }
        val current = if (todayIdx >= 0) runEndingAt(todayIdx) else 0
        return current.toDouble() to longest.toDouble()
    }
}
