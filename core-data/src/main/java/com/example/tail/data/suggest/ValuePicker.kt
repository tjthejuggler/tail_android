package com.example.tail.data.suggest

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One historical occurrence of a VALUE (an amount, a text option, a subtype)
 * for a habit. [weight] lets one physical event carry a probability
 * distribution over several candidate values (see [AmountHistory]).
 */
data class ValueEvent<V>(
    val date: LocalDate,
    /** Minute-of-day, or [SuggestionMath.TIMELESS]. */
    val minute: Int,
    val value: V,
    val weight: Double = 1.0,
    /** 0-based index of this event within its day (for "nth event today" matching). */
    val ordinal: Int = 0,
    /** Value of the previous event the same day (sequence habits), or null. */
    val prev: V? = null
)

/**
 * Picks the value the user is most likely to log NEXT — never "the last
 * one used". Every historical event votes with
 *   recency × time-of-day proximity × weekday similarity ×
 *   same-nth-event-today × same-previous-value
 * so a value that is habitual at THIS time of day / weekday / point in the
 * day wins over a merely recent one, and a one-off outlier is drowned out by
 * the habitual values.
 */
object ValuePicker {
    private const val HALF_LIFE_DAYS = 21.0
    private const val SIGMA_MINUTES = 150.0
    private const val TIME_FLOOR = 0.2
    private const val GLOBAL_PRIOR = 0.04

    /** Builds events from raw (date, minute, value) triples, computing ordinal + prev per day. */
    fun <V> events(raw: List<Triple<LocalDate, Int, V>>): List<ValueEvent<V>> {
        val out = ArrayList<ValueEvent<V>>(raw.size)
        for ((_, day) in raw.groupBy { it.first }) {
            val sorted = day.sortedBy { it.second }
            var prev: V? = null
            sorted.forEachIndexed { i, (d, m, v) ->
                out.add(ValueEvent(d, m, v, 1.0, i, prev))
                prev = v
            }
        }
        return out
    }

    /**
     * Ranked (value, probability) list, best first. [todayOrdinal] = events
     * already logged today (so the next one has this 0-based index);
     * [todayPrev] = the value of today's latest event, if known.
     */
    fun <V> rank(
        events: List<ValueEvent<V>>,
        today: LocalDate,
        nowMin: Int,
        todayOrdinal: Int,
        todayPrev: V?,
        /** Values already logged today: damped by how rarely the user repeats them within a day. */
        todayValues: Set<V> = emptySet()
    ): List<Pair<V, Double>> {
        val score = LinkedHashMap<V, Double>()
        for (e in events) {
            val daysAgo = ChronoUnit.DAYS.between(e.date, today)
            if (daysAgo < 1) continue // today's own events are context, not votes
            val rec = SuggestionMath.recency(daysAgo, HALF_LIFE_DAYS)
            val time = if (e.minute < 0 || nowMin < 0) 0.6 else TIME_FLOOR + (1 - TIME_FLOOR) *
                SuggestionMath.gaussian(SuggestionMath.circularDistance(e.minute, nowMin).toDouble(), SIGMA_MINUTES)
            val dow = SuggestionMath.dowSimilarity(e.date, today)
            val ord = when (kotlin.math.abs(e.ordinal - todayOrdinal)) { 0 -> 1.0; 1 -> 0.7; else -> 0.5 }
            val prev = if (todayPrev == null) 1.0 else if (e.prev == todayPrev) 1.0 else 0.75
            val w = e.weight * rec * (time * dow * ord * prev + GLOBAL_PRIOR)
            score[e.value] = (score[e.value] ?: 0.0) + w
        }
        val total = score.values.sum()
        if (total <= 0.0) return emptyList()
        if (todayValues.isNotEmpty()) {
            val perDay = HashMap<Pair<LocalDate, V>, Int>()
            for (e in events) {
                if (e.weight >= 0.999 && e.date.isBefore(today)) perDay.merge(e.date to e.value, 1, Int::plus)
            }
            for (v in todayValues) {
                val cur = score[v] ?: continue
                val days = perDay.keys.count { it.second == v }
                val repeats = perDay.entries.count { it.key.second == v && it.value >= 2 }
                val rate = if (days == 0) 0.0 else repeats.toDouble() / days
                score[v] = cur * kotlin.math.max(0.05, rate)
            }
        }
        // Normalised by the PRE-damping total so damped values lose probability mass.
        return score.entries.map { it.key to it.value / total }.sortedByDescending { it.second }
    }
}

/**
 * Recovers per-increment AMOUNTS for custom-input habits (water ml, …) from
 * history. The habits DB only stores DAY TOTALS; the timestamp store knows how
 * many increments happened and when. For a day with n stamps and total T,
 * every sequence of n amounts drawn from the habit's known amount set that
 * sums to T is a candidate explanation; the posterior share of each amount
 * is computed by a small DP and refined with EM so frequently used amounts
 * attract ambiguous days. Days logged AFTER the exact amount log existed use
 * the exact amounts instead.
 */
object AmountHistory {
    private const val MAX_STAMPS = 14
    private const val MAX_TOTAL = 6000
    private const val EM_ITERATIONS = 3

    private data class Day(val date: LocalDate, val minutes: List<Int>, val total: Int)

    fun build(
        stampsByDate: Map<String, List<String>>,
        dayTotals: Map<String, Int>,
        exact: Map<String, Int>,
        known: Set<Int>,
        today: LocalDate,
        historyDays: Int
    ): List<ValueEvent<Int>> {
        val cutoff = today.minusDays(historyDays.toLong())
        val events = ArrayList<ValueEvent<Int>>()

        // 1. Exact per-increment amounts (key "yyyy-MM-dd HH:mm:ss").
        val exactDates = HashSet<LocalDate>()
        val exactRaw = ArrayList<Triple<LocalDate, Int, Int>>()
        for ((key, amount) in exact) {
            val (date, minute) = SuggestionMath.parseLogKey(key) ?: continue
            if (date < cutoff || date > today || amount <= 0) continue
            exactDates.add(date)
            exactRaw.add(Triple(date, minute, amount))
        }
        events.addAll(ValuePicker.events(exactRaw))

        // 2. Days without an exact record: decompose the day total.
        val amounts = known.filter { it > 1 }.distinct().sorted()
        if (amounts.isEmpty()) return events
        val days = ArrayList<Day>()
        for ((ds, times) in stampsByDate) {
            val date = runCatching { LocalDate.parse(ds) }.getOrNull() ?: continue
            if (date < cutoff || date >= today || date in exactDates) continue
            val total = dayTotals[ds] ?: continue
            if (times.isEmpty() || times.size > MAX_STAMPS || total <= 0 || total > MAX_TOTAL) continue
            val mins = times.map { SuggestionMath.parseMinute(it) ?: SuggestionMath.TIMELESS }.sorted()
            days.add(Day(date, mins, total))
        }
        if (days.isEmpty()) return events

        var w = DoubleArray(amounts.size) { 1.0 / amounts.size }
        var posteriors: List<DoubleArray?> = emptyList()
        repeat(EM_ITERATIONS) {
            val expected = DoubleArray(amounts.size)
            posteriors = days.map { d ->
                val p = posterior(amounts, w, d.minutes.size, d.total)
                if (p != null) for (i in p.indices) expected[i] += p[i] * d.minutes.size
                p
            }
            val sum = expected.sum()
            if (sum > 0) w = DoubleArray(amounts.size) { (expected[it] + 0.01) / (sum + 0.01 * amounts.size) }
        }
        days.forEachIndexed { di, d ->
            val p = posteriors.getOrNull(di) ?: return@forEachIndexed
            var prevDominant: Int? = null
            d.minutes.forEachIndexed { i, m ->
                for (ai in amounts.indices) {
                    if (p[ai] < 0.02) continue
                    events.add(ValueEvent(d.date, m, amounts[ai], p[ai], i, prevDominant))
                }
                prevDominant = amounts[p.indices.maxByOrNull { p[it] } ?: 0]
            }
        }
        return events
    }

    /** P(a given increment of an n-increment day totalling T was amount a), or null if impossible. */
    private fun posterior(amounts: List<Int>, w: DoubleArray, n: Int, total: Int): DoubleArray? {
        // f[k][s] = weighted number of sequences of k amounts summing to s.
        val f = Array(n + 1) { DoubleArray(total + 1) }
        f[0][0] = 1.0
        for (k in 1..n) {
            for (s in 0..total) {
                var acc = 0.0
                for (i in amounts.indices) {
                    val a = amounts[i]
                    if (s >= a) acc += w[i] * f[k - 1][s - a]
                }
                f[k][s] = acc
            }
        }
        val denom = f[n][total]
        if (denom <= 0.0) return null
        val out = DoubleArray(amounts.size)
        for (i in amounts.indices) {
            val a = amounts[i]
            if (total >= a) out[i] = w[i] * f[n - 1][total - a] / denom
        }
        return out
    }
}
