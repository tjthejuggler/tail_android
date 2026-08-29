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
 *  - FRESH-RECORD SUPPRESSION: when the standing record was set today or
 *    yesterday (the user is riding a record they just set), BOTH near and
 *    broken notifications are suppressed — re-breaking your own day-old
 *    record is noise, not news.
 */
object AppStatsRecordEngine {

    /** Minimum days of history before any record notification may fire. */
    const val MIN_HISTORY_DAYS = 14

    /** A metric counts as "out of the record episode" below this fraction. */
    const val EPISODE_RESET_FRACTION = 0.95

    enum class Verdict { NONE, NEAR, BROKEN, SUMMARY }

    /** One metric's evaluation outcome. */
    data class Evaluation(
        val metric: String,
        val verdict: Verdict,
        val currentValue: Double,
        val recordValue: Double,
        val title: String,
        val message: String,
        /** "yyyy-MM-dd" the standing record was set (null when unknown). */
        val recordDate: String? = null
    )

    /** One all-time record currently standing (for the daily summary). */
    data class RecordFact(
        val metric: String,
        val label: String,
        val value: Double,
        val formatted: String,
        val unit: String,
        /** "yyyy-MM-dd" the record was set (null when unknown). */
        val date: String?
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

    /** (current, record, recordDate) for one metric. */
    private data class MetricValues(
        val current: Double,
        val record: Double,
        val recordDate: String?
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
        val values = computeValues(series, today, todayIdx, historyIdx)

        val out = mutableListOf<Evaluation>()
        val updated = mutableMapOf<String, MetricState>()

        // Records set today or yesterday are "fresh" — the user is riding a
        // record they just set, so neither near nor broken notices fire.
        val freshCutoff = java.time.LocalDate.parse(today).minusDays(1).toString()

        for (m in METRICS) {
            val v = values[m.key] ?: continue
            val current = v.current
            val record = v.record
            val state = states[m.key] ?: MetricState()
            if (record <= 0.0 || (v.recordDate != null && v.recordDate >= freshCutoff)) {
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
                        recordDate = v.recordDate,
                        title = if (m.caution) "⚠️ New all-time high: ${m.label}"
                        else "🏆 New all-time record: ${m.label}!",
                        message = if (m.caution)
                            "You've hit a new all-time high for ${m.label}: ${m.fmt(current)} ${m.unit} " +
                                "(previous record ${m.fmt(record)} set ${v.recordDate ?: "earlier"})."
                        else
                            "${m.fmt(current)} ${m.unit} — previous record was ${m.fmt(record)} " +
                                "set on ${v.recordDate ?: "an earlier day"}. Keep it going!"
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
                    recordDate = v.recordDate,
                    title = if (m.caution) "⚠️ Approaching record: ${m.label}"
                    else "📈 Record within reach: ${m.label}",
                    message = if (m.caution)
                        "You're at ${m.fmt(current)} ${m.unit} vs the all-time high of " +
                            "${m.fmt(record)} (set ${v.recordDate ?: "earlier"}) — worth turning around."
                    else
                        "Currently ${m.fmt(current)} ${m.unit} vs the all-time record of " +
                            "${m.fmt(record)} set on ${v.recordDate ?: "an earlier day"} — " +
                            "only ${m.fmt(record - current)} to go!"
                )
            }
        }

        // Broken records first, then near-records.
        out.sortWith(compareBy({ it.verdict != Verdict.BROKEN }, { it.metric }))
        return Result(out, updated)
    }

    /**
     * The records CURRENTLY being ridden: all-time bests whose record day
     * was literally the day BEFORE [today] — the hills the user is on top
     * of right now. Feeds the once-a-day morning "records held" summary.
     */
    fun currentRecords(series: Series, today: String): List<RecordFact> {
        if (series.dates.isEmpty()) return emptyList()
        val historyIdx = series.dates.indexOfLast { it < today }
        if (historyIdx < 0) return emptyList()
        val yesterday = java.time.LocalDate.parse(today).minusDays(1).toString()
        val values = computeValues(series, today, series.dates.indexOf(today), historyIdx)
        return METRICS.mapNotNull { m ->
            val v = values[m.key] ?: return@mapNotNull null
            if (v.record <= 0.0 || v.recordDate != yesterday) return@mapNotNull null
            RecordFact(
                metric = m.key,
                label = m.label,
                value = v.record,
                formatted = m.fmt(v.record),
                unit = m.unit,
                date = v.recordDate
            )
        }
    }

    // ── Per-metric (current, record, recordDate) computation ────────────────

    private fun computeValues(
        series: Series,
        today: String,
        todayIdx: Int,
        historyIdx: Int
    ): Map<String, MetricValues> {
        val values = mutableMapOf<String, MetricValues>()

        fun lastTodayOr(list: List<Int>): Double =
            (if (todayIdx >= 0) list[todayIdx] else list.last()).toDouble()

        /** Max over history plus the LATEST date achieving it. */
        fun historyMaxWithDate(list: List<Int>): Pair<Double, String> {
            var best = Int.MIN_VALUE
            var bestDate = series.dates[historyIdx]
            for (i in 0..historyIdx) {
                if (list[i] >= best) {
                    best = list[i]
                    bestDate = series.dates[i]
                }
            }
            return best.toDouble() to bestDate
        }

        fun put(key: String, current: Double, list: List<Int>) {
            val (record, date) = historyMaxWithDate(list)
            values[key] = MetricValues(current, record, date)
        }

        put("best_day_points", lastTodayOr(series.dailyTotals), series.dailyTotals)
        put("most_habits_day", lastTodayOr(series.dailyHabitCounts), series.dailyHabitCounts)
        put("total_streak_days", lastTodayOr(series.dailyStreakSums), series.dailyStreakSums)
        put("habits_with_streak", lastTodayOr(series.dailyStreakCounts), series.dailyStreakCounts)
        put("total_anti_streak_days", lastTodayOr(series.dailyAntiStreakSums), series.dailyAntiStreakSums)
        put("habits_with_anti_streak", lastTodayOr(series.dailyAntiStreakCounts), series.dailyAntiStreakCounts)

        // Rolling averages: calendar-day windows, missing days contribute 0
        // (matches computeTaskerStats semantics).
        val totalsByDate = series.dates.zip(series.dailyTotals).toMap()
        for (n in listOf(7, 30, 90, 365)) {
            val key = "avg$n"
            val cur = calendarAvg(totalsByDate, today, n)
            var best = -1.0
            var bestDate = series.dates[historyIdx]
            for (i in 0..historyIdx) {
                val avg = calendarAvg(totalsByDate, series.dates[i], n)
                if (avg >= best) {
                    best = avg
                    bestDate = series.dates[i]
                }
            }
            values[key] = MetricValues(cur, maxOf(best, 0.0), bestDate)
        }

        // Aggregate streak: longest run of consecutive calendar days with
        // points > 0 in history, vs the run ending today.
        val (curStreak, bestStreak, streakDate) = aggregateStreak(series, todayIdx, historyIdx)
        values["aggregate_streak"] = MetricValues(curStreak, bestStreak, streakDate)
        return values
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
     * (current, record, recordDate) for the any-points day streak: the run
     * ending at [todayIdx] (0 when today has no points yet) vs the longest
     * run within history plus the date that run ended on. Calendar gaps
     * break runs (missing days are zero-days).
     */
    private fun aggregateStreak(
        series: Series,
        todayIdx: Int,
        historyIdx: Int
    ): Triple<Double, Double, String?> {
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
        var longestDate: String? = null
        for (i in 0..historyIdx) {
            val r = runEndingAt(i)
            if (r >= longest && r > 0) {
                longest = r
                longestDate = series.dates[i]
            }
        }
        val current = if (todayIdx >= 0) runEndingAt(todayIdx) else 0
        return Triple(current.toDouble(), longest.toDouble(), longestDate)
    }
}
