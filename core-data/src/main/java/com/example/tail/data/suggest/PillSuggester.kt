package com.example.tail.data.suggest

import com.example.tail.data.PillLogParser
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Likely-next pill intake suggestions (pure, JVM-testable).
 *
 * The stored log writes every pill line of one dialog submission as its own
 * entry +1 s apart, so entries < [CLUSTER_GAP_SECONDS] apart on one day form
 * ONE "intake". Instead of ranking exact past bundles (which fragments as
 * soon as a single pill varies — the old approach, almost always wrong), the
 * model works PER PILL over comparable past intakes:
 *
 *   weight = recency × time-of-day proximity × weekday similarity ×
 *            "same nth intake of the day"
 *   × state match: per pill, only past intakes that happened in the same
 *     "already taken today?" state (0 / 1 / 2+ servings before) vote, so a
 *     pill already taken this morning is only suggested again if history
 *     shows the user really re-doses it at this point of the day.
 *
 * P(pill) = weighted share of comparable intakes containing it. The primary
 * suggestion is the CONSENSUS bundle (every pill with P ≥ 0.5, each at its
 * modal serving count); next come the most common exact past bundles, then
 * likely single pills.
 */
object PillSuggester {
    const val CLUSTER_GAP_SECONDS = 120L
    private const val HALF_LIFE_DAYS = 14.0
    private const val SIGMA_MINUTES = 100.0
    private const val TIME_FLOOR = 0.1
    private const val HISTORY_DAYS = 150L
    private const val PRIOR_MASS = 0.15
    private const val CONSENSUS_P = 0.5
    private const val SINGLE_P = 0.3

    private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class Bundle(val counts: Map<String, Int>, val score: Double)

    internal class Intake(
        val date: LocalDate,
        val minute: Int,
        val counts: Map<String, Int>,
        var ordinal: Int = 0,
        var before: Map<String, Int> = emptyMap()
    )

    internal fun cluster(
        log: Map<String, String>,
        catalog: Map<String, String>,
        today: LocalDate
    ): List<Intake> {
        val entries = log.entries.mapNotNull { (ts, text) ->
            val dt = runCatching { LocalDateTime.parse(ts.substringBefore('.'), TS_FMT) }.getOrNull()
            if (dt == null || dt.toLocalDate().isAfter(today)) null else dt to text
        }.sortedBy { it.first }

        val out = ArrayList<Intake>()
        var counts = LinkedHashMap<String, Int>()
        var start: LocalDateTime? = null
        var prev: LocalDateTime? = null
        fun flush() {
            val s = start
            if (s != null && counts.isNotEmpty()) out.add(Intake(s.toLocalDate(), s.hour * 60 + s.minute, counts))
            counts = LinkedHashMap(); start = null
        }
        for ((dt, text) in entries) {
            val p = prev
            if (start != null && p != null &&
                (p.toLocalDate() != dt.toLocalDate() || ChronoUnit.SECONDS.between(p, dt) > CLUSTER_GAP_SECONDS)
            ) flush()
            if (start == null) start = dt
            for (line in PillLogParser.splitLines(text)) {
                val pill = PillLogParser.matchPill(line, catalog) ?: continue
                counts[pill] = (counts[pill] ?: 0) + 1
            }
            prev = dt
        }
        flush()
        // ordinal + cumulative "before" counts per day
        for ((_, day) in out.groupBy { it.date }) {
            val running = HashMap<String, Int>()
            day.sortedBy { it.minute }.forEachIndexed { i, intake ->
                intake.ordinal = i
                intake.before = HashMap(running)
                for ((p, n) in intake.counts) running[p] = (running[p] ?: 0) + n
            }
        }
        return out
    }

    fun suggest(
        log: Map<String, String>,
        catalog: Map<String, String>,
        nowMin: Int,
        today: LocalDate,
        maxResults: Int = 6
    ): List<Bundle> {
        val all = cluster(log, catalog, today)
        val todayIntakes = all.filter { it.date == today }
        val todayCounts = HashMap<String, Int>()
        for (i in todayIntakes) for ((p, n) in i.counts) todayCounts[p] = (todayCounts[p] ?: 0) + n
        val targetOrdinal = todayIntakes.size

        // Base weight per comparable past intake.
        val past = ArrayList<Pair<Intake, Double>>()
        for (i in all) {
            val daysAgo = ChronoUnit.DAYS.between(i.date, today)
            if (daysAgo < 1 || daysAgo > HISTORY_DAYS) continue
            val rec = SuggestionMath.recency(daysAgo, HALF_LIFE_DAYS)
            val time = TIME_FLOOR + (1 - TIME_FLOOR) *
                SuggestionMath.gaussian(SuggestionMath.circularDistance(i.minute, nowMin).toDouble(), SIGMA_MINUTES)
            val dow = SuggestionMath.dowSimilarity(i.date, today)
            val ord = when (kotlin.math.abs(i.ordinal - targetOrdinal)) { 0 -> 1.0; 1 -> 0.6; else -> 0.4 }
            past.add(i to rec * time * dow * ord)
        }
        if (past.isEmpty()) return emptyList()

        fun level(n: Int) = minOf(n, 2)

        // Per-pill probability + modal count.
        val pills = past.flatMap { it.first.counts.keys }.toSet()
        val prob = HashMap<String, Double>()
        val modal = HashMap<String, Int>()
        for (p in pills) {
            val t = level(todayCounts[p] ?: 0)
            var den = 0.0
            var num = 0.0
            val byCount = HashMap<Int, Double>()
            for ((i, w) in past) {
                if (level(i.before[p] ?: 0) != t) continue
                den += w
                val c = i.counts[p] ?: 0
                if (c > 0) {
                    num += w
                    byCount[c] = (byCount[c] ?: 0.0) + w
                }
            }
            prob[p] = num / (den + PRIOR_MASS)
            modal[p] = byCount.entries.maxByOrNull { it.value }?.key ?: 1
        }

        val results = ArrayList<Bundle>()
        fun add(counts: Map<String, Int>, score: Double) {
            if (counts.isEmpty() || results.any { it.counts == counts }) return
            results.add(Bundle(counts, score))
        }

        // 1. Consensus bundle.
        var consensus = pills.filter { (prob[it] ?: 0.0) >= CONSENSUS_P }
        if (consensus.isEmpty()) {
            consensus = pills.filter { (prob[it] ?: 0.0) >= SINGLE_P }
                .sortedByDescending { prob[it] }.take(1)
        }
        if (consensus.isNotEmpty()) {
            add(
                consensus.sortedByDescending { prob[it] }.associateWith { modal[it] ?: 1 },
                consensus.sumOf { prob[it] ?: 0.0 } / consensus.size
            )
        }

        // 2. Most common exact past bundles among comparable intakes.
        val totalW = past.sumOf { it.second }.coerceAtLeast(1e-9)
        val groupings = LinkedHashMap<String, Pair<Map<String, Int>, Double>>()
        for ((i, w) in past) {
            val key = i.counts.entries.sortedBy { it.key }.joinToString("|") { "${it.key}×${it.value}" }
            val prev = groupings[key]
            groupings[key] = i.counts to ((prev?.second ?: 0.0) + w)
        }
        groupings.values
            .filter { (counts, _) ->
                // Drop bundles that are mostly already taken today.
                val satisfied = counts.entries.count { (p, n) -> (todayCounts[p] ?: 0) >= n }
                satisfied * 2 <= counts.size
            }
            .sortedByDescending { it.second }
            .take(3)
            .forEach { (counts, w) -> add(counts, w / totalW) }

        // 3. Likely single pills.
        pills.filter { (prob[it] ?: 0.0) >= SINGLE_P && (prob[it] ?: 0.0) < CONSENSUS_P }
            .sortedByDescending { prob[it] }
            .take(2)
            .forEach { add(mapOf(it to (modal[it] ?: 1)), prob[it] ?: 0.0) }

        return results.take(maxResults)
    }
}
