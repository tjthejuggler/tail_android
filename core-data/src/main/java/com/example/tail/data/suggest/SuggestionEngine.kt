package com.example.tail.data.suggest

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

// Shared, PURE (no Android) suggestion engine used by the ⚡ habit flash and
// the pill dialog chips. Everything here is deterministic given (history,
// today, now) so it is unit-testable on the JVM.
//
// Core idea — "comparable days": every past day gets a weight made of
//   recency × weekday-similarity × state-match × context-similarity
// where state-match compares how many times the habit was ALREADY done by
// this time of day on that day vs. today, and context-similarity compares
// the SET of other habits already done by now (so "a day that is unfolding
// like today" counts more). The probability that the habit happens in the
// coming window is then the weighted share of comparable days on which it
// did, shrunk toward a cross-weekday baseline so thin data stays modest.

/** Small numeric helpers shared by every suggester. */
object SuggestionMath {
    /** Minute-of-day sentinel for TIMELESS stamps (sorts before every real time). */
    const val TIMELESS = -1
    private const val DAY = 1440

    fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % DAY
        return if (d > DAY / 2) DAY - d else d
    }

    fun gaussian(distance: Double, sigma: Double): Double {
        val z = distance / sigma
        return exp(-0.5 * z * z)
    }

    fun recency(daysAgo: Long, halfLifeDays: Double): Double =
        exp(-daysAgo.toDouble() * ln(2.0) / halfLifeDays)

    fun isWeekend(d: LocalDate): Boolean = d.dayOfWeek.value >= 6

    /** 1.0 same weekday, 0.45 same weekday-class (Mon–Fri / Sat–Sun), 0.2 otherwise. */
    fun dowSimilarity(past: LocalDate, today: LocalDate): Double = when {
        past.dayOfWeek == today.dayOfWeek -> 1.0
        isWeekend(past) == isWeekend(today) -> 0.45
        else -> 0.2
    }

    /** "HH:mm:ss" (or "HH:mm") → minute-of-day; null for malformed / timeless. */
    fun parseMinute(hms: String): Int? {
        if (hms.length < 5 || hms[2] != ':') return null
        val h = hms.substring(0, 2).toIntOrNull() ?: return null
        val m = hms.substring(3, 5).toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** "yyyy-MM-dd HH:mm:ss[.n]" log key → (date, minute-of-day). */
    fun parseLogKey(key: String): Pair<LocalDate, Int>? {
        if (key.length < 16 || (key[10] != ' ' && key[10] != 'T')) return null
        val date = runCatching { LocalDate.parse(key.substring(0, 10)) }.getOrNull() ?: return null
        val minute = parseMinute(key.substring(11)) ?: return null
        return date to minute
    }

    /** Weighted quantile of (value, weight) pairs; null when empty. */
    fun weightedQuantile(values: List<Pair<Int, Double>>, q: Double): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sortedBy { it.first }
        val total = sorted.sumOf { it.second }
        if (total <= 0.0) return sorted.last().first
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= q * total) return v
        }
        return sorted.last().first
    }
}

/** Per-habit timestamp history parsed once: habit → day → sorted minutes-of-day. */
class StampIndex(
    raw: Map<String, Map<String, List<String>>>,
    val today: LocalDate,
    val historyDays: Int
) {
    val byHabit: Map<String, Map<LocalDate, IntArray>>
    val byDay: Map<LocalDate, Map<String, IntArray>>

    init {
        val cutoff = today.minusDays(historyDays.toLong()).toString()
        val todayStr = today.toString()
        val bh = HashMap<String, HashMap<LocalDate, IntArray>>()
        val bd = HashMap<LocalDate, HashMap<String, IntArray>>()
        for ((habit, days) in raw) {
            for ((ds, times) in days) {
                if (ds < cutoff || ds > todayStr || times.isEmpty()) continue
                val date = runCatching { LocalDate.parse(ds) }.getOrNull() ?: continue
                val arr = IntArray(times.size) {
                    SuggestionMath.parseMinute(times[it]) ?: SuggestionMath.TIMELESS
                }
                arr.sort()
                bh.getOrPut(habit) { HashMap() }[date] = arr
                bd.getOrPut(date) { HashMap() }[habit] = arr
            }
        }
        byHabit = bh
        byDay = bd
    }
}

/** Result of scoring one habit. */
data class HabitScore(
    val habit: String,
    /** Final rank score (≈ probability the habit is wanted soon, 0..~1.2). */
    val score: Double,
    /** Probability of an increment inside the coming window. */
    val pWindow: Double,
    /** Probability of ANY further increment later today. */
    val pRest: Double,
    /** True when the score comes from "usually done by now but not yet today". */
    val overdue: Boolean,
    /** Weighted usual minute-of-day around now, or -1. */
    val usualMinute: Int,
    /** Weighted median minute of the habit's FIRST stamp of the day, or -1. */
    val usualFirst: Int,
    /** Increments already stamped today. */
    val countToday: Int
)

/**
 * Scores habits for "what will the user want to do next" at [nowMin] on
 * [StampIndex.today]. Build one scorer per computation (it precomputes the
 * per-day "already done by now" sets).
 */
class HabitScorer(private val index: StampIndex, private val nowMin: Int) {

    companion object {
        const val WINDOW_MINUTES = 120
        const val MIN_ACTIVE_DAYS = 4
        private const val HALF_LIFE_DAYS = 28.0
        private const val K = 1.2          // same-weekday shrink strength
        private const val K0 = 0.6         // baseline shrink strength
        private const val OVERDUE_GRACE = 10
        /** A habit untouched for this many days is considered abandoned. */
        private const val ABANDONED_DAYS = 30L
        private val EMPTY = IntArray(0)
    }

    private val today = index.today
    private val doneBy: Map<LocalDate, Set<String>>
    private val todayDone: Set<String>
    private val interByDate = HashMap<LocalDate, Int>()
    private val unionByDate = HashMap<LocalDate, Int>()

    init {
        val m = HashMap<LocalDate, Set<String>>()
        for ((date, habits) in index.byDay) {
            val s = HashSet<String>()
            for ((h, arr) in habits) if (arr.isNotEmpty() && arr[0] <= nowMin) s.add(h)
            m[date] = s
        }
        doneBy = m
        todayDone = m[today] ?: emptySet()
        for ((date, s) in m) {
            if (date == today) continue
            val small = if (s.size <= todayDone.size) s else todayDone
            val big = if (s.size <= todayDone.size) todayDone else s
            var inter = 0
            for (x in small) if (x in big) inter++
            interByDate[date] = inter
            unionByDate[date] = s.size + todayDone.size - inter
        }
    }

    /** Jaccard similarity of the habit sets done-by-now (target habit excluded). */
    private fun similarity(date: LocalDate, habit: String): Double {
        var inter = interByDate[date] ?: 0
        var union = unionByDate[date] ?: todayDone.size
        val inA = doneBy[date]?.contains(habit) == true
        val inB = habit in todayDone
        if (inA && inB) {
            inter--; union--
        } else if (inA || inB) union--
        return if (union <= 0) 0.5 else inter.toDouble() / union
    }

    fun score(habit: String, currentStreak: Int = 0): HabitScore? {
        val days = index.byHabit[habit] ?: return null
        val recentDays = days.keys.count { ChronoUnit.DAYS.between(it, today) in 0..60 }
        if (recentDays < MIN_ACTIVE_DAYS) return null
        val lastActive = days.keys.maxOrNull() ?: return null
        if (ChronoUnit.DAYS.between(lastActive, today) > ABANDONED_DAYS) return null
        val cToday = days[today]?.size ?: 0

        var total = 0
        var timeless = 0
        for (arr in days.values) {
            total += arr.size
            timeless += arr.count { it == SuggestionMath.TIMELESS }
        }
        val timelessMode = total > 0 && timeless * 2 >= total
        // A timeless habit has no "when" — once done today it is simply done.
        if (timelessMode && cToday > 0) return null
        val effToday = if (timelessMode) 0 else cToday

        var sameW = 0.0; var sameWin = 0.0; var sameRest = 0.0
        var othW = 0.0; var othWin = 0.0; var othRest = 0.0
        var dSameW = 0.0; var dSameX = 0.0; var dOthW = 0.0; var dOthX = 0.0
        var usualNum = 0.0; var usualDen = 0.0
        val firstMinutes = ArrayList<Pair<Int, Double>>()

        // Days before the habit's first recorded stamp are not evidence of
        // "didn't do it" — the habit simply didn't exist yet.
        val firstDay = days.keys.minOrNull() ?: return null
        for (k in 1..index.historyDays) {
            val date = today.minusDays(k.toLong())
            if (date < firstDay) break
            val stamps = days[date] ?: EMPTY
            val wRec = SuggestionMath.recency(k.toLong(), HALF_LIFE_DAYS)
            val sameDow = date.dayOfWeek == today.dayOfWeek
            val classSim = if (sameDow || SuggestionMath.isWeekend(date) == SuggestionMath.isWeekend(today)) 1.0 else 0.15
            // Neutral (1.0) at similarity 0.5 — the "no information" default.
            val simW = 0.6 + 0.8 * similarity(date, habit)

            // Day-level (unconditioned) presence — drives the overdue rule.
            val done = if (stamps.isNotEmpty()) 1.0 else 0.0
            if (sameDow) {
                dSameW += wRec; dSameX += wRec * done
            } else {
                dOthW += wRec * classSim; dOthX += wRec * classSim * done
            }
            stamps.firstOrNull { it >= 0 }?.let {
                firstMinutes.add(it to wRec * classSim * (if (sameDow) 1.5 else 1.0))
            }

            // Conditional: count already done by now vs. the coming window.
            var before = 0; var inWin = 0; var rest = 0
            if (timelessMode) {
                if (stamps.isNotEmpty()) { inWin = 1; rest = 1 }
            } else {
                for (m in stamps) {
                    if (m <= nowMin) before++ else {
                        rest++
                        if (m <= nowMin + WINDOW_MINUTES) inWin++
                    }
                }
            }
            val cmp = when (abs(before - effToday)) { 0 -> 1.0; 1 -> 0.35; else -> 0.1 }
            val w = wRec * cmp * simW
            if (sameDow) {
                sameW += w
                if (inWin > 0) sameWin += w
                if (rest > 0) sameRest += w
            } else {
                val ww = w * classSim
                othW += ww
                if (inWin > 0) othWin += ww
                if (rest > 0) othRest += ww
            }
            if (!timelessMode) {
                for (m in stamps) {
                    if (m >= nowMin - 90 && m <= nowMin + WINDOW_MINUTES) {
                        usualNum += w * m; usualDen += w
                    }
                }
            }
        }

        val baseWin = othWin / (othW + K0)
        val baseRest = othRest / (othW + K0)
        var pWin = (sameWin + K * baseWin) / (sameW + K)
        val pRest = (sameRest + K * baseRest) / (sameW + K)
        if (timelessMode) pWin *= 0.6

        // Overdue: a habit done on (nearly) every comparable day, whose usual
        // time has passed without it being done today.
        val baseDay = dOthX / (dOthW + K0)
        val dayRate = (dSameX + K * baseDay) / (dSameW + K)
        val usualFirst = SuggestionMath.weightedQuantile(firstMinutes, 0.5) ?: -1
        var overdueScore = 0.0
        if (!timelessMode && cToday == 0 && usualFirst >= 0 &&
            usualFirst < nowMin - OVERDUE_GRACE && dayRate >= 0.55
        ) {
            overdueScore = dayRate * 0.62
        }

        val blend = pWin + 0.3 * max(0.0, pRest - pWin)
        var s = max(blend, overdueScore)
        if (cToday == 0 && currentStreak >= 3) s *= 1.0 + min(currentStreak, 30) / 150.0
        return HabitScore(
            habit = habit,
            score = s,
            pWindow = pWin,
            pRest = pRest,
            overdue = overdueScore > 0.0 && overdueScore >= blend,
            usualMinute = if (usualDen > 0) (usualNum / usualDen).toInt() else -1,
            usualFirst = usualFirst,
            countToday = cToday
        )
    }
}
