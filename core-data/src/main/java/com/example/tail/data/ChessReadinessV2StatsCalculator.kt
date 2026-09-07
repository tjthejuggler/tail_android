package com.example.tail.data

import java.time.Instant
import java.time.ZoneId

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness V2 — pure computation layer for the V2 sections of the
 *  Readiness Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * Aggregates the telemetry of the two v2 chess-readiness systems:
 *
 *  - PRE-GAME GATE (ChessReadinessV2Store): one record per completed
 *    evaluation (tier verdict + module metrics) plus one record per
 *    completed 3-minute PVT-B reflex run (response times, lapses = late
 *    taps, false starts = early taps). The two are joined by timestamp so
 *    every reflex run can be shown with the verdict it produced.
 *
 *  - POST-GAME AUDIT v2 (ChessPhase2V2Store ledger + the shared
 *    ChessPhase2Store audit history): one record per audited rated game
 *    (result + verdict + minutes), joined to the shared audit for the
 *    game's accuracy / Elo delta / strain where available.
 *
 * Everything here is PURE — no Android dependencies — mirroring
 * [ChessReadinessStatsCalculator], so it is unit-testable on the JVM.
 */

// ── Input mirrors (store types mapped 1:1 by the stats screen) ──────────────

/** One completed v2 pre-game evaluation (from ChessReadinessV2Store results log). */
data class V2ResultRecord(
    val timestamp: Long,
    /** V2Tier name: TIER1_PEAK / TIER2_RESTRICTED / TIER3_LOCKOUT. */
    val tier: String,
    /** v1-compatible state name (GREEN/YELLOW/RED_LIGHT). */
    val stateName: String,
    val ccrs: Int,
    val zLnRmssd: Double?,
    val zRhr: Double?,
    val lapses: Int,
    val falseStarts: Int,
    /** Mean reciprocal response speed (1000/RT) of the PVT, when it ran. */
    val meanRrt: Double?,
    val acwr: Double?,
    /** True when a passive module already restricted and the PVT was skipped. */
    val pvtSkipped: Boolean,
    val sessionStartedAt: Long
)

/** One completed 3-minute PVT-B reflex run (from ChessReadinessV2Store PVT log). */
data class V2PvtRecord(
    val timestamp: Long,
    val validResponses: Int,
    /** Late taps — responses ≥ the 355 ms lapse threshold. */
    val lapses: Int,
    /** Early taps — responses before/within 100 ms of the stimulus. */
    val falseStarts: Int,
    /** Mean reciprocal response speed (1000/RT ms). */
    val meanRrt: Double?,
    /** Mean raw response time (ms). */
    val meanRtMs: Double?,
    /** Slowest single response (ms). */
    val maxRtMs: Int?
)

/** One audited rated game from the Phase 2 v2 ledger (ChessPhase2V2Store). */
data class Phase2V2GameRecord(
    /** Epoch millis at game end. */
    val timestamp: Long,
    /** GameResult name: WIN / LOSS / DRAW. */
    val result: String,
    /** TimeControl name: BULLET / BLITZ / RAPID. */
    val timeControl: String,
    /** OutputState name: CONTINUE_SESSION / PIVOT_TO_DRILLS / TERMINATE_SESSION. */
    val outputState: String,
    /** Base-clock minutes the game contributed to the session tally. */
    val estimatedMinutes: Double
)

/**
 * One audit from the SHARED Phase 2 audit history (ChessPhase2Store) —
 * v2-era games are joined onto their ledger entry by timestamp.
 */
data class Phase2AuditRecord(
    val timestamp: Long,
    val timeControl: String,
    val outputState: String,
    /** Elo delta of the audited game. */
    val deltaE: Double,
    /** CAPS2 accuracy (0–100) as reported by chess.com. */
    val caps2Accuracy: Double,
    /** False when accuracy was bypassed (short game / no Game Review). */
    val accuracyCounted: Boolean,
    /** Strain (0–100) the game contributed to its session. */
    val strain: Double
)

// ── Tier / verdict vocabulary ────────────────────────────────────────────────

/** v2 pre-game tier names as persisted ([ChessReadinessV2Engine.V2Tier] .name). */
object V2Tiers {
    const val TIER1 = "TIER1_PEAK"
    const val TIER2 = "TIER2_RESTRICTED"
    const val TIER3 = "TIER3_LOCKOUT"
}

/** Phase 2 output-state names as persisted ([ChessPhase2Engine.OutputState] .name). */
object Phase2Verdicts {
    const val CONTINUE = "CONTINUE_SESSION"
    const val PIVOT = "PIVOT_TO_DRILLS"
    const val TERMINATE = "TERMINATE_SESSION"
}

// ── V2 pre-game stats ────────────────────────────────────────────────────────

/**
 * One PVT-B reflex run joined to the verdict it produced (null tier when no
 * evaluation was recorded within the join window — e.g. an abandoned test).
 */
data class V2PvtPoint(
    val timestampMs: Long,
    val validResponses: Int,
    val lapses: Int,
    val falseStarts: Int,
    val meanRtMs: Double?,
    val meanRrt: Double?,
    val maxRtMs: Int?,
    /** Verdict tier name of the evaluation this run fed (null = none). */
    val tier: String?,
    /** True when that verdict was TIER1_PEAK (rated play unlocked). */
    val passed: Boolean?
)

/** Full aggregate of the v2 pre-game gate, consumed by the stats screen. */
data class V2PregameStats(
    // Verdicts
    val totalTests: Int,
    val tier1Count: Int,
    val tier2Count: Int,
    val tier3Count: Int,
    /** Tier 1 share of all evaluations, 0–100. */
    val passRate: Double,
    val firstTestAt: Long?,
    val lastTestAt: Long?,
    // Reflex test (PVT-B)
    val pvtCount: Int,
    /** Average mean-RT across runs (ms; null when no run reported it). */
    val avgMeanRtMs: Double?,
    /** Fastest (best) run mean-RT (ms). */
    val bestMeanRtMs: Double?,
    val avgLapses: Double,
    val avgFalseStarts: Double,
    val totalLapses: Int,
    val totalFalseStarts: Int,
    /** Average response speed 1000/RT (higher = faster). */
    val avgResponseSpeed: Double?,
    /** Slowest single response across all runs (ms). */
    val worstMaxRtMs: Int?,
    /** Mean-RT change first-3 → last-3 runs (ms; negative = improving). */
    val rtTrendMs: Double?,
    /** Lapse change first-3 → last-3 runs (negative = improving). */
    val lapseTrend: Double?,
    /** False-start change first-3 → last-3 runs (negative = improving). */
    val falseStartTrend: Double?,
    // Passive modules
    val avgZLnRmssd: Double?,
    val avgZRhr: Double?,
    /** Share of evaluations that had autonomic data, 0–100. */
    val autonomicCoverage: Double,
    val avgAcwr: Double?,
    val pvtSkippedCount: Int,
    /** Chronological per-run series for the charts. */
    val series: List<V2PvtPoint>
)

/**
 * Window within which a PVT run and its evaluation are considered the same
 * test (the result is recorded moments after the PVT completes).
 */
private const val PVT_JOIN_WINDOW_MS = 120_000L

/**
 * Aggregates the v2 pre-game gate. [results] are the recorded evaluations;
 * [pvt] the completed reflex runs. The two are joined by nearest timestamp
 * within [PVT_JOIN_WINDOW_MS] so each charted reflex run carries the verdict
 * it produced (a PASS = Tier 1).
 */
fun computeV2PregameStats(
    results: List<V2ResultRecord>,
    pvt: List<V2PvtRecord>
): V2PregameStats {
    val sortedResults = results.sortedBy { it.timestamp }
    val tier1 = sortedResults.count { it.tier == V2Tiers.TIER1 }
    val tier2 = sortedResults.count { it.tier == V2Tiers.TIER2 }
    val tier3 = sortedResults.count { it.tier == V2Tiers.TIER3 }

    val series = pvt.sortedBy { it.timestamp }.map { run ->
        val verdict = sortedResults
            .filter { kotlin.math.abs(it.timestamp - run.timestamp) <= PVT_JOIN_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.timestamp - run.timestamp) }
        V2PvtPoint(
            timestampMs = run.timestamp,
            validResponses = run.validResponses,
            lapses = run.lapses,
            falseStarts = run.falseStarts,
            meanRtMs = run.meanRtMs,
            meanRrt = run.meanRrt,
            maxRtMs = run.maxRtMs,
            tier = verdict?.tier,
            passed = verdict?.let { it.tier == V2Tiers.TIER1 }
        )
    }

    val rtSamples = series.mapNotNull { it.meanRtMs }
    val speedSamples = series.mapNotNull { it.meanRrt }
    val zHrvSamples = sortedResults.mapNotNull { it.zLnRmssd }
    val zRhrSamples = sortedResults.mapNotNull { it.zRhr }
    val acwrSamples = sortedResults.mapNotNull { it.acwr }

    return V2PregameStats(
        totalTests = sortedResults.size,
        tier1Count = tier1,
        tier2Count = tier2,
        tier3Count = tier3,
        passRate = if (sortedResults.isEmpty()) 0.0
        else tier1 * 100.0 / sortedResults.size,
        firstTestAt = sortedResults.firstOrNull()?.timestamp,
        lastTestAt = sortedResults.lastOrNull()?.timestamp,
        pvtCount = series.size,
        avgMeanRtMs = rtSamples.takeIf { it.isNotEmpty() }?.average(),
        bestMeanRtMs = rtSamples.takeIf { it.isNotEmpty() }?.min(),
        avgLapses = if (series.isEmpty()) 0.0
        else series.sumOf { it.lapses }.toDouble() / series.size,
        avgFalseStarts = if (series.isEmpty()) 0.0
        else series.sumOf { it.falseStarts }.toDouble() / series.size,
        totalLapses = series.sumOf { it.lapses },
        totalFalseStarts = series.sumOf { it.falseStarts },
        avgResponseSpeed = speedSamples.takeIf { it.isNotEmpty() }?.average(),
        worstMaxRtMs = series.mapNotNull { it.maxRtMs }.takeIf { it.isNotEmpty() }?.max(),
        rtTrendMs = trend(rtSamples),
        lapseTrend = trend(series.map { it.lapses.toDouble() }),
        falseStartTrend = trend(series.map { it.falseStarts.toDouble() }),
        avgZLnRmssd = zHrvSamples.takeIf { it.isNotEmpty() }?.average(),
        avgZRhr = zRhrSamples.takeIf { it.isNotEmpty() }?.average(),
        autonomicCoverage = if (sortedResults.isEmpty()) 0.0
        else zHrvSamples.size.coerceAtLeast(zRhrSamples.size) * 100.0 / sortedResults.size,
        avgAcwr = acwrSamples.takeIf { it.isNotEmpty() }?.average(),
        pvtSkippedCount = sortedResults.count { it.pvtSkipped },
        series = series
    )
}

/** First-3 vs last-3 average change (null below 2 samples; lower = better). */
private fun trend(values: List<Double>): Double? {
    if (values.size < 2) return null
    val head = values.take(3).average()
    val tail = values.takeLast(3).average()
    return tail - head
}

// ── Phase 2 v2 post-game stats ───────────────────────────────────────────────

/**
 * One audited rated game: the v2 ledger entry joined to the shared audit
 * (accuracy / Elo delta / strain) where one exists within the join window.
 */
data class Phase2V2Point(
    val timestampMs: Long,
    val result: String,
    val timeControl: String,
    val outputState: String,
    val estimatedMinutes: Double,
    /** CAPS2 accuracy 0–100 (null when bypassed or not reported). */
    val accuracy: Double?,
    val deltaE: Double?,
    val strain: Double?
)

/** Full aggregate of the v2 post-game audit, consumed by the stats screen. */
data class Phase2V2Stats(
    val totalGames: Int,
    val continueCount: Int,
    val pivotCount: Int,
    val terminateCount: Int,
    /** CONTINUE share of all verdicts, 0–100. */
    val continueRate: Double,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Wins / decided games × 100 (draws excluded), 0–100. */
    val winRate: Double,
    val avgAccuracy: Double?,
    val accuracyGames: Int,
    val avgDeltaE: Double,
    val totalDeltaE: Double,
    val totalStrain: Double,
    val totalMinutes: Double,
    val avgMinutes: Double,
    /** Accuracy change first-3 → last-3 audited games (positive = improving). */
    val accuracyTrend: Double?,
    /** Longest run of consecutive losses in the ledger. */
    val longestLossStreak: Int,
    /** Verdict of the most recent audited game (null when empty). */
    val latestVerdict: String?,
    val firstGameAt: Long?,
    val lastGameAt: Long?,
    /** Chronological per-game series for the charts. */
    val series: List<Phase2V2Point>
)

/**
 * Window within which a ledger game and its shared audit are considered the
 * same game (both are stamped at the game's end time).
 */
private const val AUDIT_JOIN_WINDOW_MS = 60_000L

/**
 * Aggregates the Phase 2 v2 post-game audit. [games] is the v2 rated-game
 * ledger; [audits] the shared audit history — each ledger game is joined to
 * the nearest audit within [AUDIT_JOIN_WINDOW_MS] for accuracy, Elo delta
 * and strain.
 */
fun computePhase2V2Stats(
    games: List<Phase2V2GameRecord>,
    audits: List<Phase2AuditRecord>
): Phase2V2Stats {
    val sortedAudits = audits.sortedBy { it.timestamp }
    val series = games.sortedBy { it.timestamp }.map { g ->
        val audit = sortedAudits
            .filter { kotlin.math.abs(it.timestamp - g.timestamp) <= AUDIT_JOIN_WINDOW_MS }
            .minByOrNull { kotlin.math.abs(it.timestamp - g.timestamp) }
        Phase2V2Point(
            timestampMs = g.timestamp,
            result = g.result,
            timeControl = g.timeControl,
            outputState = g.outputState,
            estimatedMinutes = g.estimatedMinutes,
            accuracy = audit?.takeIf { it.accuracyCounted }?.caps2Accuracy,
            deltaE = audit?.deltaE,
            strain = audit?.strain
        )
    }

    val continues = series.count { it.outputState == Phase2Verdicts.CONTINUE }
    val pivots = series.count { it.outputState == Phase2Verdicts.PIVOT }
    val terminates = series.count { it.outputState == Phase2Verdicts.TERMINATE }
    val wins = series.count { it.result == "WIN" }
    val losses = series.count { it.result == "LOSS" }
    val draws = series.count { it.result == "DRAW" }
    val decided = wins + losses

    val accuracySamples = series.mapNotNull { it.accuracy }
    val deltaSamples = series.mapNotNull { it.deltaE }

    // Longest consecutive LOSS run.
    var streak = 0
    var longest = 0
    for (p in series) {
        if (p.result == "LOSS") {
            streak++
            if (streak > longest) longest = streak
        } else {
            streak = 0
        }
    }

    return Phase2V2Stats(
        totalGames = series.size,
        continueCount = continues,
        pivotCount = pivots,
        terminateCount = terminates,
        continueRate = if (series.isEmpty()) 0.0 else continues * 100.0 / series.size,
        wins = wins,
        losses = losses,
        draws = draws,
        winRate = if (decided == 0) 0.0 else wins * 100.0 / decided,
        avgAccuracy = accuracySamples.takeIf { it.isNotEmpty() }?.average(),
        accuracyGames = accuracySamples.size,
        avgDeltaE = if (deltaSamples.isEmpty()) 0.0 else deltaSamples.average(),
        totalDeltaE = deltaSamples.sum(),
        totalStrain = series.sumOf { it.strain ?: 0.0 },
        totalMinutes = series.sumOf { it.estimatedMinutes },
        avgMinutes = if (series.isEmpty()) 0.0
        else series.sumOf { it.estimatedMinutes } / series.size,
        accuracyTrend = accuracySamples.takeIf { it.size >= 2 }?.let { trendUp(it) },
        longestLossStreak = longest,
        latestVerdict = series.lastOrNull()?.outputState,
        firstGameAt = series.firstOrNull()?.timestampMs,
        lastGameAt = series.lastOrNull()?.timestampMs,
        series = series
    )
}

/** First-3 vs last-3 average change where HIGHER is better (accuracy). */
private fun trendUp(values: List<Double>): Double? {
    if (values.size < 2) return null
    return values.takeLast(3).average() - values.take(3).average()
}

// ── V2 pre-game hourly aggregates ─────────────────────────────────────────────

/**
 * V2 pre-game aggregates for one hour of the day (0–23) — the hour-by-hour
 * companion of [V2PregameStats], so the stats screen can show at which
 * times of day the v2 gate performs best/worst.
 */
data class V2HourlyReadiness(
    val hour: Int,
    /** Completed v2 evaluations started in this hour. */
    val testCount: Int,
    val avgCcrs: Double,
    val tier1Count: Int,
    val tier2Count: Int,
    val tier3Count: Int,
    /** PVT-B reflex runs started in this hour. */
    val pvtCount: Int,
    /** Average PVT-B mean response time (ms) across this hour's runs that reported one. */
    val avgMeanRtMs: Double?
) {
    /** Tier 1 share of this hour's evaluations, 0–100. */
    val passRate: Double get() = if (testCount > 0) tier1Count * 100.0 / testCount else 0.0
}

/**
 * Average CCRS, tier split and PVT-B reflex speed per hour of day — one
 * entry for every hour 0–23 (empty hours carry zero counts), mirroring
 * [computeHourlyReadiness] for the v2 gate. Evaluations and reflex runs
 * are bucketed independently by their own timestamps.
 */
fun computeV2HourlyReadiness(
    results: List<V2ResultRecord>,
    pvt: List<V2PvtRecord>,
    zone: ZoneId = ZoneId.systemDefault()
): List<V2HourlyReadiness> {
    val counts = IntArray(24)
    val ccrsSum = IntArray(24)
    val t1 = IntArray(24)
    val t2 = IntArray(24)
    val t3 = IntArray(24)
    for (r in results) {
        val h = Instant.ofEpochMilli(r.timestamp).atZone(zone).hour
        counts[h]++
        ccrsSum[h] += r.ccrs
        when (r.tier) {
            V2Tiers.TIER1 -> t1[h]++
            V2Tiers.TIER2 -> t2[h]++
            V2Tiers.TIER3 -> t3[h]++
        }
    }
    val pvtCounts = IntArray(24)
    val rtSum = DoubleArray(24)
    val rtN = IntArray(24)
    for (p in pvt) {
        val h = Instant.ofEpochMilli(p.timestamp).atZone(zone).hour
        pvtCounts[h]++
        p.meanRtMs?.let {
            rtSum[h] += it
            rtN[h]++
        }
    }
    return (0..23).map { h ->
        V2HourlyReadiness(
            hour = h,
            testCount = counts[h],
            avgCcrs = if (counts[h] > 0) ccrsSum[h].toDouble() / counts[h] else 0.0,
            tier1Count = t1[h],
            tier2Count = t2[h],
            tier3Count = t3[h],
            pvtCount = pvtCounts[h],
            avgMeanRtMs = if (rtN[h] > 0) rtSum[h] / rtN[h] else null
        )
    }
}
