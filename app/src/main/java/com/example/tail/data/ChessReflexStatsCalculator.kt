package com.example.tail.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Reflex Tests (PVT-B) — cross-version stats calculator
 * ════════════════════════════════════════════════════════════════════════
 *
 * The reflex test (psychomotor vigilance task, variant B) has run inside
 * every readiness-engine version that matters — 3 minutes in v2, 2 minutes
 * in v3 — and will keep running in future versions. This calculator merges
 * every recorded reflex run into ONE version-agnostic series so long-term
 * trends survive engine switches:
 *
 *  - overall aggregates (count, avg/best RT, lapses, false starts, trends)
 *  - hour-of-day aggregates (when is the nervous system fastest?)
 *  - correlation between a run's speed and the rated games played in the
 *    hours right after it (does reflex speed predict performance?)
 *
 * Everything here is PURE — no Android dependencies — mirroring
 * [ChessReadinessV2StatsCalculator], so it is unit-testable on the JVM.
 */

/** Which engine version a reflex run came from ("v2", "v3", …). */
typealias ReflexVersion = String

/**
 * One completed reflex run, normalized across versions.
 * v2 runs carry validResponses/maxRtMs; v3 runs (2-min) may not.
 */
data class ReflexRunPoint(
    val timestampMs: Long,
    val version: ReflexVersion,
    /** Planned test duration in minutes (3 for v2, 2 for v3). */
    val durationMin: Int,
    val lapses: Int,
    val falseStarts: Int,
    val meanRtMs: Double?,
    val meanRrt: Double?,
    val maxRtMs: Int?,
    val validResponses: Int?
)

/** Hour-of-day aggregate of reflex speed (one entry per hour 0–23). */
data class ReflexHourly(
    val hour: Int,
    val runCount: Int,
    /** Average mean-RT (ms) across this hour's runs that reported one. */
    val avgMeanRtMs: Double?,
    /** Average lapses per run this hour. */
    val avgLapses: Double
)

/**
 * The rated games played within [ReflexStatsConfig.followWindowMs] after a
 * reflex run, summarized as one "following session".
 */
data class ReflexFollowingSession(
    val runTimestampMs: Long,
    val runMeanRtMs: Double?,
    val games: Int,
    val wins: Int,
    /** Win share 0–100 (decided + drawn games counted; draw = not a win). */
    val winRate: Double,
    /** Rating change across the session (last ratingAfter − first; null when unknown). */
    val eloDelta: Int?
)

/** Full cross-version reflex aggregate, consumed by the stats screen. */
data class ReflexStats(
    val totalRuns: Int,
    /** Runs per version, in the order first seen (chronological). */
    val runsByVersion: Map<ReflexVersion, Int>,
    val firstRunAt: Long?,
    val lastRunAt: Long?,
    // Speed
    val avgMeanRtMs: Double?,
    val bestMeanRtMs: Double?,
    val worstMaxRtMs: Int?,
    val avgResponseSpeed: Double?,
    // Errors
    val totalLapses: Int,
    val totalFalseStarts: Int,
    val avgLapses: Double,
    val avgFalseStarts: Double,
    /** Mean-RT change first-3 → last-3 runs (ms; negative = improving). */
    val rtTrendMs: Double?,
    val lapseTrend: Double?,
    val falseStartTrend: Double?,
    // Hour of day
    val hourly: List<ReflexHourly>,
    /** Hour (0–23) with the fastest average RT (null when no RT data). */
    val fastestHour: Int?,
    /** Hour (0–23) with the slowest average RT (null when no RT data). */
    val slowestHour: Int?,
    // Following rated sessions
    val followingSessions: List<ReflexFollowingSession>,
    /** Pearson r between run mean-RT and following-session win rate (null < 4 pairs). */
    val rtWinRateCorrelation: Double?,
    /** Pearson r between run mean-RT and following-session Elo delta (null < 4 pairs). */
    val rtEloDeltaCorrelation: Double?,
    /** Median-split comparison: following-session stats after the FASTEST half of runs. */
    val fastHalfFollowing: ReflexSplitFollowing?,
    /** Median-split comparison: following-session stats after the SLOWEST half of runs. */
    val slowHalfFollowing: ReflexSplitFollowing?
)

/** Following-session aggregate for one half (fast/slow) of the RT distribution. */
data class ReflexSplitFollowing(
    val runs: Int,
    val matchedSessions: Int,
    val avgWinRate: Double,
    /** Average Elo delta per matched session (null when no rating data). */
    val avgEloDelta: Double?
)

/** Tuning knobs for the cross-version aggregate (defaults suit the app). */
data class ReflexStatsConfig(
    /** Rated games ending within this window after a run count as its "following session". */
    val followWindowMs: Long = 6L * 60 * 60 * 1000,
    /** Minimum overlap for a game to belong to a session at all. */
    val minSessionGames: Int = 1,
    val zone: ZoneId = ZoneId.systemDefault()
)

/**
 * Merges v2 PVT runs and v3 reflex summaries into the single cross-version
 * series. Both inputs are already version-tagged record types; this only
 * normalizes them.
 */
fun buildReflexRuns(
    v2Pvt: List<V2PvtRecord>,
    v3Reflex: List<V3ReflexRunRecord>
): List<ReflexRunPoint> {
    val v2 = v2Pvt.map {
        ReflexRunPoint(
            timestampMs = it.timestamp,
            version = "v2",
            durationMin = 3,
            lapses = it.lapses,
            falseStarts = it.falseStarts,
            meanRtMs = it.meanRtMs,
            meanRrt = it.meanRrt,
            maxRtMs = it.maxRtMs,
            validResponses = it.validResponses
        )
    }
    val v3 = v3Reflex.map {
        ReflexRunPoint(
            timestampMs = it.timestamp,
            version = "v3",
            durationMin = 2,
            lapses = it.lapses,
            falseStarts = it.falseStarts,
            meanRtMs = it.meanRtMs,
            meanRrt = it.meanRtMs?.let { rt -> 1000.0 / rt },
            maxRtMs = null,
            validResponses = null
        )
    }
    return (v2 + v3).sortedBy { it.timestampMs }
}

/**
 * A v3 reflex run extracted from the v3 result log — one entry per v3 run
 * that reached the reflex summary (pass OR reflex-fail; both carry the
 * reflex telemetry).
 */
data class V3ReflexRunRecord(
    val timestamp: Long,
    val lapses: Int,
    val falseStarts: Int,
    val meanRtMs: Double?
)

/**
 * Computes the full cross-version reflex aggregate.
 *
 * @param runs   normalized reflex runs (see [buildReflexRuns])
 * @param games  the shared rated-game log; used for the "following rated
 *               session" correlation
 */
fun computeReflexStats(
    runs: List<ReflexRunPoint>,
    games: List<ReadinessGameRecord>,
    config: ReflexStatsConfig = ReflexStatsConfig()
): ReflexStats {
    val sorted = runs.sortedBy { it.timestampMs }
    val rtSamples = sorted.mapNotNull { it.meanRtMs }

    // ── Hour-of-day aggregates ──
    val hourly = computeReflexHourly(sorted, config.zone)
    val rtHours = hourly.filter { it.avgMeanRtMs != null }
    val fastestHour = rtHours.minByOrNull { it.avgMeanRtMs!! }?.hour
    val slowestHour = rtHours.maxByOrNull { it.avgMeanRtMs!! }?.hour

    // ── Following rated sessions ──
    val ratedGames = games
        .filter { it.rated }
        .sortedBy { it.endTimeMs }
    val sessions = sorted.mapNotNull { run ->
        val window = ratedGames.filter {
            it.endTimeMs > run.timestampMs && it.endTimeMs <= run.timestampMs + config.followWindowMs
        }
        if (window.size < config.minSessionGames) return@mapNotNull null
        val wins = window.count { it.won }
        val ratings = window.mapNotNull { it.ratingAfter }
        ReflexFollowingSession(
            runTimestampMs = run.timestampMs,
            runMeanRtMs = run.meanRtMs,
            games = window.size,
            wins = wins,
            winRate = wins * 100.0 / window.size,
            eloDelta = if (ratings.size >= 2) ratings.last() - ratings.first() else null
        )
    }

    // Correlations need mean-RT on the run side.
    val rtWinPairs = sessions.filter { it.runMeanRtMs != null }
    val rtEloPairs = rtWinPairs.filter { it.eloDelta != null }

    // Median split on mean-RT (fast vs slow half), then average the
    // following-session outcomes of each half.
    val withRt = rtWinPairs.filter { it.runMeanRtMs != null }
    val split = withRt
        .sortedBy { it.runMeanRtMs!! }
        .let { s -> if (s.isEmpty()) null else s.chunked((s.size + 1) / 2).let { c -> c.first() to c.last() } }
    fun halfStats(half: List<ReflexFollowingSession>): ReflexSplitFollowing? =
        if (half.isEmpty()) null
        else ReflexSplitFollowing(
            runs = half.size,
            matchedSessions = half.size,
            avgWinRate = half.sumOf { it.winRate } / half.size,
            avgEloDelta = half.mapNotNull { it.eloDelta }.takeIf { it.isNotEmpty() }?.average()
        )

    return ReflexStats(
        totalRuns = sorted.size,
        runsByVersion = sorted.groupingBy { it.version }.eachCount(),
        firstRunAt = sorted.firstOrNull()?.timestampMs,
        lastRunAt = sorted.lastOrNull()?.timestampMs,
        avgMeanRtMs = rtSamples.takeIf { it.isNotEmpty() }?.average(),
        bestMeanRtMs = rtSamples.takeIf { it.isNotEmpty() }?.min(),
        worstMaxRtMs = sorted.mapNotNull { it.maxRtMs }.takeIf { it.isNotEmpty() }?.max(),
        avgResponseSpeed = sorted.mapNotNull { it.meanRrt }.takeIf { it.isNotEmpty() }?.average(),
        totalLapses = sorted.sumOf { it.lapses },
        totalFalseStarts = sorted.sumOf { it.falseStarts },
        avgLapses = if (sorted.isEmpty()) 0.0 else sorted.sumOf { it.lapses }.toDouble() / sorted.size,
        avgFalseStarts = if (sorted.isEmpty()) 0.0 else sorted.sumOf { it.falseStarts }.toDouble() / sorted.size,
        rtTrendMs = trend(rtSamples),
        lapseTrend = trend(sorted.map { it.lapses.toDouble() }),
        falseStartTrend = trend(sorted.map { it.falseStarts.toDouble() }),
        hourly = hourly,
        fastestHour = fastestHour,
        slowestHour = slowestHour,
        followingSessions = sessions,
        rtWinRateCorrelation = pearson(
            rtWinPairs.map { it.runMeanRtMs!! },
            rtWinPairs.map { it.winRate }
        ),
        rtEloDeltaCorrelation = pearson(
            rtEloPairs.map { it.runMeanRtMs!! },
            rtEloPairs.map { it.eloDelta!!.toDouble() }
        ),
        fastHalfFollowing = split?.let { halfStats(it.first) },
        slowHalfFollowing = split?.let { halfStats(it.second) }
    )
}

/** Hour-of-day buckets 0–23 (empty hours carry zero counts). */
fun computeReflexHourly(
    runs: List<ReflexRunPoint>,
    zone: ZoneId = ZoneId.systemDefault()
): List<ReflexHourly> {
    val counts = IntArray(24)
    val rtSum = DoubleArray(24)
    val rtN = IntArray(24)
    val lapseSum = DoubleArray(24)
    for (r in runs) {
        val h = Instant.ofEpochMilli(r.timestampMs).atZone(zone).hour
        counts[h]++
        lapseSum[h] += r.lapses
        r.meanRtMs?.let {
            rtSum[h] += it
            rtN[h]++
        }
    }
    return (0..23).map { h ->
        ReflexHourly(
            hour = h,
            runCount = counts[h],
            avgMeanRtMs = if (rtN[h] > 0) rtSum[h] / rtN[h] else null,
            avgLapses = if (counts[h] > 0) lapseSum[h] / counts[h] else 0.0
        )
    }
}

/** First-3 vs last-3 average change (null below 2 samples; lower = better). */
private fun trend(values: List<Double>): Double? {
    if (values.size < 2) return null
    return values.takeLast(3).average() - values.take(3).average()
}

/** Pearson correlation; null when fewer than 4 pairs or zero variance. */
private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
    if (xs.size < 4 || xs.size != ys.size) return null
    val mx = xs.average()
    val my = ys.average()
    var num = 0.0
    var dx2 = 0.0
    var dy2 = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - mx
        val dy = ys[i] - my
        num += dx * dy
        dx2 += dx * dx
        dy2 += dy * dy
    }
    if (dx2 == 0.0 || dy2 == 0.0) return null
    val r = num / sqrt(dx2 * dy2)
    return if (abs(r) <= 1.0) r else null
}
