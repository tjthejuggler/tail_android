package com.example.tail.data

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Pre-game readiness ↔ chess performance CORRELATIONS (v3 gate)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Answers three questions from the v3 (reflex + Puzzle Rush Survival) log:
 *
 *  1. Does REFLEX performance (2-min PVT-B mean RT) predict the quality of
 *     the following rated session (CAPS2 accuracy — the on-device inverse
 *     view of ACPL — Stockfish ACPL, blunders, unforced blunders, win
 *     rate, Elo delta)?
 *  2. Does the SURVIVAL stage result (puzzles solved inside the 5-minute
 *     cap) predict the same session metrics?
 *  3. Does the reflex stage predict the survival stage (reflex RT ↔
 *     puzzles solved within the same run) — i.e. is there any predicting
 *     between the two halves of the pre-game test itself?
 *
 * DATA CUTOFF — the survival gate originally terminated the moment the
 * target was reached, so pre-change solved counts were CENSORED at the bar
 * (a solved count of 15/15 says nothing about how many more were makeable).
 * Since the "keep going to the 5:00 cap" change, every run accumulates as
 * many solves as the user can manage, making solved counts comparable.
 * Only runs recorded AT/AFTER [SURVIVAL_UNLIMITED_CUTOFF_MS] (2026-09-08
 * 00:00 Europe/Rome — the first full day the new build was in place) feed
 * these correlations. Reflex-fail runs are also excluded: they never
 * entered the survival stage, so they carry no survival measurement.
 *
 * Session metrics come from the rated games ending within the follow
 * window after a run, joined to the shared Phase-2 audit history (by
 * nearest timestamp) for CAPS2 accuracy and — on v3+ audits where the
 * desktop Stockfish pipeline answered — ACPL / blunders / unforced
 * blunders (unforced = NOT flagged as time-pressure-forced). Everything
 * here is PURE — no Android dependencies — unit-testable on the JVM,
 * mirroring [computeReflexStats].
 */

/**
 * Epoch ms of 2026-09-08 00:00 Europe/Rome (UTC+2). First full day of the
 * "survival runs always continue to the 5:00 cap" build — the earliest
 * timestamp whose runs carry UNCENSORED solved counts.
 */
const val SURVIVAL_UNLIMITED_CUTOFF_MS = 1_788_818_400_000L

/** Verdict name of a run that failed the reflex step (never solved puzzles). */
private const val VERDICT_FAIL_REFLEX = "FAIL_REFLEX"

/** One completed v3 run, reduced to the fields the correlations need. */
data class V3CorrelationRun(
    val timestamp: Long,
    /** V3 Verdict name (PASS / FAIL_REFLEX / FAIL_STRIKE / FAIL_TIMEOUT). */
    val verdict: String,
    val puzzlesPassed: Int,
    val target: Int,
    val survivalDurationMs: Long,
    val reflexLapses: Int,
    val reflexFalseStarts: Int,
    val reflexMeanRtMs: Double?
)

/** One (x, y) pair for a scatter chart, tagged with the run's timestamp. */
data class CorrelationScatterPoint(
    val x: Double,
    val y: Double,
    val timestampMs: Long
)

/** The rated games following one run, summarized. */
data class V3FollowingSessionOutcome(
    val games: Int,
    val wins: Int,
    /** Win share 0–100 (draw = not a win). */
    val winRate: Double,
    /** Average CAPS2 accuracy across games with a counted audit (null = none). */
    val avgAccuracy: Double?,
    /** How many of the session's games contributed a counted accuracy. */
    val accuracyGames: Int,
    /** Rating change across the session (null when fewer than 2 rated points). */
    val eloDelta: Int?,
    /** Total strain the session's audited games contributed. */
    val totalStrain: Double,
    /** Average Stockfish ACPL across analyzed games (null = none analyzed). */
    val avgAcpl: Double?,
    /** How many of the session's games carried a Stockfish analysis. */
    val acplGames: Int,
    /** Total blunders across analyzed games (null = none analyzed). */
    val blunders: Int?,
    /** Total time-pressure-unforced blunders across analyzed games (null = none). */
    val unforcedBlunders: Int?
)

/** One eligible run plus (optionally) the session that followed it. */
data class V3CorrelationPoint(
    val run: V3CorrelationRun,
    val session: V3FollowingSessionOutcome?
)

/** Tuning knobs (defaults suit the app). */
data class V3CorrelationConfig(
    /** Runs recorded before this epoch ms are excluded (censored solved counts). */
    val cutoffMs: Long = SURVIVAL_UNLIMITED_CUTOFF_MS,
    /** Rated games ending within this window after a run form its "following session". */
    val followWindowMs: Long = 6L * 60 * 60 * 1000,
    /** Max |Δt| between a game's end and its shared audit (same as Phase2V2 join). */
    val auditJoinWindowMs: Long = 60_000L,
    /** Minimum (x, y) pairs before any Pearson r is reported. */
    val minPairs: Int = 4
)

/** Full correlation aggregate, consumed by the stats screen. */
data class V3CorrelationStats(
    /** All v3 runs seen (pre- and post-cutoff) — drives the "collecting" hint. */
    val totalRunsSeen: Int,
    /** Post-cutoff runs that entered the survival stage. */
    val eligibleRuns: Int,
    /** How many of those were followed by at least one rated game. */
    val matchedSessions: Int,
    val cutoffMs: Long,
    /** Per-run detail series (run + following session). */
    val points: List<V3CorrelationPoint>,
    // ── Scatter series — one list per charted correlation ──
    /** Reflex mean RT (x) → following-session accuracy % (y). */
    val rtAccuracyPairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session accuracy % (y). */
    val solvedAccuracyPairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → puzzles solved in the SAME run (y). */
    val rtSolvedPairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → following-session Stockfish ACPL (y). */
    val rtAcplPairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session Stockfish ACPL (y). */
    val solvedAcplPairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → following-session blunders (y). */
    val rtBlundersPairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session blunders (y). */
    val solvedBlundersPairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → following-session unforced blunders (y). */
    val rtUnforcedPairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session unforced blunders (y). */
    val solvedUnforcedPairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → following-session win rate % (y). */
    val rtWinRatePairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session win rate % (y). */
    val solvedWinRatePairs: List<CorrelationScatterPoint>,
    /** Reflex mean RT (x) → following-session Elo delta (y). */
    val rtEloPairs: List<CorrelationScatterPoint>,
    /** Puzzles solved (x) → following-session Elo delta (y). */
    val solvedEloPairs: List<CorrelationScatterPoint>,
    // ── Pearson coefficients (null below minPairs or zero variance) ──
    val rtAccuracyR: Double?,
    val solvedAccuracyR: Double?,
    /** The reflex↔survival prediction coefficient (no session data needed). */
    val rtSolvedR: Double?,
    val rtAcplR: Double?,
    val solvedAcplR: Double?,
    val rtBlundersR: Double?,
    val solvedBlundersR: Double?,
    val rtUnforcedR: Double?,
    val solvedUnforcedR: Double?,
    val rtWinRateR: Double?,
    val solvedWinRateR: Double?,
    val rtEloR: Double?,
    val solvedEloR: Double?,
    val minPairs: Int
)

/**
 * Computes the full pre-game↔performance correlation aggregate.
 *
 * @param results v3 result log (any order)
 * @param games   the shared rated-game log (respects the screen's variant filter)
 * @param audits  the shared Phase-2 audit history (CAPS2 + Stockfish telemetry)
 */
fun computeV3CorrelationStats(
    results: List<V3CorrelationRun>,
    games: List<ReadinessGameRecord>,
    audits: List<Phase2AuditRecord>,
    config: V3CorrelationConfig = V3CorrelationConfig()
): V3CorrelationStats {
    val eligible = results
        .filter { it.timestamp >= config.cutoffMs && it.verdict != VERDICT_FAIL_REFLEX }
        .sortedBy { it.timestamp }

    val ratedGames = games.filter { it.rated }.sortedBy { it.endTimeMs }
    val sortedAudits = audits.sortedBy { it.timestamp }

    /** Nearest shared audit for a game's end time (null beyond the window). */
    fun auditFor(endTimeMs: Long) = sortedAudits
        .filter { abs(it.timestamp - endTimeMs) <= config.auditJoinWindowMs }
        .minByOrNull { abs(it.timestamp - endTimeMs) }

    // ── Following-session outcomes ──
    val points = eligible.map { run ->
        val window = ratedGames.filter {
            it.endTimeMs > run.timestamp && it.endTimeMs <= run.timestamp + config.followWindowMs
        }
        val session = if (window.isEmpty()) null else run {
            val sessionAudits = window.mapNotNull { auditFor(it.endTimeMs) }
            val accuracies = sessionAudits
                .filter { it.accuracyCounted }
                .map { it.caps2Accuracy }
            val acpls = sessionAudits.mapNotNull { it.analysisAcpl }
            val blunderCounts = sessionAudits.mapNotNull { it.blunders }
            val unforcedCounts = sessionAudits.mapNotNull { it.unforcedBlunders }
            val ratings = window.mapNotNull { it.ratingAfter }
            V3FollowingSessionOutcome(
                games = window.size,
                wins = window.count { it.won },
                winRate = window.count { it.won } * 100.0 / window.size,
                avgAccuracy = accuracies.takeIf { it.isNotEmpty() }?.average(),
                accuracyGames = accuracies.size,
                eloDelta = if (ratings.size >= 2) ratings.last() - ratings.first() else null,
                totalStrain = sessionAudits.sumOf { it.strain },
                avgAcpl = acpls.takeIf { it.isNotEmpty() }?.average(),
                acplGames = acpls.size,
                blunders = blunderCounts.takeIf { it.isNotEmpty() }?.sum(),
                unforcedBlunders = unforcedCounts.takeIf { it.isNotEmpty() }?.sum()
            )
        }
        V3CorrelationPoint(run = run, session = session)
    }

    // ── Scatter pairs ──
    val withSession = points.filter { it.session != null }

    /** (runTimestamp, y) series over ALL matched sessions; null y = skipped. */
    fun ySeries(yOf: (V3FollowingSessionOutcome) -> Double?): List<Pair<Long, Double>> =
        withSession.mapNotNull { p ->
            yOf(p.session!!)?.let { p.run.timestamp to it }
        }

    fun rtPairs(series: List<Pair<Long, Double>>) = series.mapNotNull { (ts, y) ->
        val p = withSession.first { it.run.timestamp == ts }
        p.run.reflexMeanRtMs?.let { CorrelationScatterPoint(it, y, ts) }
    }
    fun solvedPairs(series: List<Pair<Long, Double>>) = series.map { (ts, y) ->
        val p = withSession.first { it.run.timestamp == ts }
        CorrelationScatterPoint(p.run.puzzlesPassed.toDouble(), y, ts)
    }

    val yAccuracy = ySeries { it.avgAccuracy }
    val yAcpl = ySeries { it.avgAcpl }
    val yBlunders = ySeries { it.blunders?.toDouble() }
    val yUnforced = ySeries { it.unforcedBlunders?.toDouble() }
    val yWinRate = ySeries { it.winRate }
    val yElo = ySeries { it.eloDelta?.toDouble() }

    val rtAccuracyPairs = rtPairs(yAccuracy)
    val solvedAccuracyPairs = solvedPairs(yAccuracy)
    val rtAcplPairs = rtPairs(yAcpl)
    val solvedAcplPairs = solvedPairs(yAcpl)
    val rtBlundersPairs = rtPairs(yBlunders)
    val solvedBlundersPairs = solvedPairs(yBlunders)
    val rtUnforcedPairs = rtPairs(yUnforced)
    val solvedUnforcedPairs = solvedPairs(yUnforced)
    val rtWinRatePairs = rtPairs(yWinRate)
    val solvedWinRatePairs = solvedPairs(yWinRate)
    val rtEloPairs = rtPairs(yElo)
    val solvedEloPairs = solvedPairs(yElo)

    val rtSolvedPairs = eligible
        .filter { it.reflexMeanRtMs != null }
        .map { CorrelationScatterPoint(it.reflexMeanRtMs!!, it.puzzlesPassed.toDouble(), it.timestamp) }

    return V3CorrelationStats(
        totalRunsSeen = results.size,
        eligibleRuns = eligible.size,
        matchedSessions = withSession.size,
        cutoffMs = config.cutoffMs,
        points = points,
        rtAccuracyPairs = rtAccuracyPairs,
        solvedAccuracyPairs = solvedAccuracyPairs,
        rtSolvedPairs = rtSolvedPairs,
        rtAcplPairs = rtAcplPairs,
        solvedAcplPairs = solvedAcplPairs,
        rtBlundersPairs = rtBlundersPairs,
        solvedBlundersPairs = solvedBlundersPairs,
        rtUnforcedPairs = rtUnforcedPairs,
        solvedUnforcedPairs = solvedUnforcedPairs,
        rtWinRatePairs = rtWinRatePairs,
        solvedWinRatePairs = solvedWinRatePairs,
        rtEloPairs = rtEloPairs,
        solvedEloPairs = solvedEloPairs,
        rtAccuracyR = rOf(rtAccuracyPairs, config.minPairs),
        solvedAccuracyR = rOf(solvedAccuracyPairs, config.minPairs),
        rtSolvedR = rOf(rtSolvedPairs, config.minPairs),
        rtAcplR = rOf(rtAcplPairs, config.minPairs),
        solvedAcplR = rOf(solvedAcplPairs, config.minPairs),
        rtBlundersR = rOf(rtBlundersPairs, config.minPairs),
        solvedBlundersR = rOf(solvedBlundersPairs, config.minPairs),
        rtUnforcedR = rOf(rtUnforcedPairs, config.minPairs),
        solvedUnforcedR = rOf(solvedUnforcedPairs, config.minPairs),
        rtWinRateR = rOf(rtWinRatePairs, config.minPairs),
        solvedWinRateR = rOf(solvedWinRatePairs, config.minPairs),
        rtEloR = rOf(rtEloPairs, config.minPairs),
        solvedEloR = rOf(solvedEloPairs, config.minPairs),
        minPairs = config.minPairs
    )
}

/** Pearson r over the scatter pairs; null below [minPairs] or zero variance. */
private fun rOf(pairs: List<CorrelationScatterPoint>, minPairs: Int): Double? =
    pearson(pairs.map { it.x }, pairs.map { it.y }, minPairs)

/** Pearson correlation; null below [minPairs] pairs or with zero variance. */
private fun pearson(xs: List<Double>, ys: List<Double>, minPairs: Int): Double? {
    if (xs.size < minPairs || xs.size != ys.size) return null
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
