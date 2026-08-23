package com.example.tail.widget

import kotlin.math.abs

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Phase 1 Pre-Session Diagnostic Protocol Engine (spec v3.0)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Computes a normalized Composite Cognitive Readiness Score (CCRS) bounded
 * to [0, 100] from two subjective biological indicators (raw Garmin sleep
 * score, mental clarity derived from slider questions) and two objective
 * Chess.com telemetry proxies (timed standard rated puzzles, 3-minute
 * Puzzle Rush).
 *
 * v3.0 changes (user feedback — the fixed 85/70 bar was so strict that it
 * pushed the user to skip the test and play anyway):
 *  - FINE-GRAINED SCORING: every sub-component now maps through 6–7 tiers
 *    instead of the coarse 0/15/25 bands — sleep from the RAW Garmin score
 *    (0/5/10/15/20/25), clarity from the slider average (6 tiers), rated
 *    puzzles across 7 speed tiers (quickness/slowness matters now), and
 *    Puzzle Rush across 6 ratio bands with a 3-point-per-strike penalty.
 *    The composite now distinguishes fine differences in performance,
 *    which is the raw material a percentile-based gate needs.
 *  - ADAPTIVE PERCENTILE GATE: the GREEN (rated play) and YELLOW (casual
 *    play) bars are no longer fixed scores. They are computed from the
 *    user's OWN recent history — the 60th / 35th percentile of the last
 *    ≤ [HISTORY_WINDOW_TESTS] tests inside a rolling
 *    [HISTORY_WINDOW_DAYS]-day window. Performing *relatively* better
 *    than usual authorizes play; a run of weak tests lowers the bar
 *    toward the floor instead of locking the user out.
 *  - STRICT ABSOLUTE CUTOFFS: the adaptive bar can never rise above
 *    [ABSOLUTE_GREEN] / [ABSOLUTE_YELLOW] — any score at/above those is
 *    ALWAYS a pass, no matter how strong the recent history is (no
 *    ratchet-up effect).
 *  - FLOORS: the bar can never sink below [GREEN_FLOOR] / [YELLOW_FLOOR],
 *    so the gate keeps meaning something even during a slump.
 *  - COLD START: with fewer than [MIN_HISTORY_SAMPLE] recent tests the
 *    fixed (gentler than v2.1) defaults [COLD_START_GREEN] /
 *    [COLD_START_YELLOW] apply until enough history exists.
 *
 * v3.1 changes (user feedback — the rising chess.com puzzle rating makes
 * the served puzzles harder, mechanically lowering the objective
 * sub-scores, and the 60th-percentile Green bar passed only ~40% of
 * attempts):
 *  - FORM-RELATIVE OBJECTIVE SCORING: the rated-puzzle tiers and the
 *    Puzzle Rush ratio are now measured against the user's OWN recent
 *    baselines (recent average solve time / recent median rush score),
 *    so improvement — which raises puzzle difficulty and the all-time
 *    high — no longer erodes the composite. The absolute tiers remain
 *    as the cold-start fallback.
 *  - USER-ADJUSTABLE PASS-RATE TARGET (corrected same day to ~30%):
 *    the Green bar sits at the (1 − target) percentile of the recent
 *    window, so ~target of attempts pass on a stationary distribution
 *    (default 30% — only top-of-form days clear it); Yellow trails 25
 *    percentile points below Green. Adjustable in Settings → ♟ Chess
 *    Readiness, with a warning that discourages frequent changes.
 *  - 10-POINT SELF-SURVEY: the clarity sliders went from 5 to 10
 *    positions (stored 1–5 records were migrated ×2 — see
 *    [scaleSurveyTo10]).
 *  - CALIBRATION-WEIGHTED SURVEY: how much the self-survey counts now
 *    depends on how accurately it has historically matched the
 *    objective results (mean |survey − objective| gap over the last
 *    ≤ [CALIBRATION_WINDOW] paired tests). Accurate self-reports — good
 *    OR bad — earn full weight; inflated or noisy reports are shrunk
 *    toward the objective anchor, so over-rating yourself never pays.
 *
 * The engine is PURE — no Android dependencies — so it is unit-testable.
 * Persistence lives in [ChessReadinessStore]; UI lives in
 * [ChessReadinessOverlay].
 *
 * Rate-limiting rules (anti "test-hunting"):
 *  - Max 8 tests per rolling 24-hour window.
 *  - Last test Green/Yellow → strict 60-minute cool-down.
 *  - Last test Red → mandatory 30/60/120-minute biological rest break
 *    (scaled by how poor the attempt was) before a re-test is allowed.
 */
object ChessReadinessEngine {

    // ── Constants ──────────────────────────────────────────────────────────

    /** Max Phase 1 tests allowed per rolling 24-hour window. */
    const val MAX_DAILY_TESTS = 8

    /** Cool-down after a Green/Yellow result (ms). */
    const val COOLDOWN_MS = 60L * 60 * 1000

    /**
     * Recovery lock after a FAILED (Red) Phase 1 test, scaled by how poor
     * the attempt was — the worse the score, the longer the mandatory rest:
     *  - ccrs 60–69 (marginal fail) → 30 min
     *  - ccrs 40–59 (poor)          → 60 min
     *  - ccrs < 40  (severe)        → 120 min
     */
    const val REST_MS_MARGINAL = 30L * 60 * 1000
    const val REST_MS_POOR = 60L * 60 * 1000
    const val REST_MS_SEVERE = 120L * 60 * 1000

    /** A Green/Yellow authorization expires after this long (ms). */
    const val SESSION_VALIDITY_MS = 60L * 60 * 1000

    /** Cold-start floor for the Puzzle Rush baseline (new accounts). */
    const val RUSH_BASELINE_FLOOR = 10

    /** Points deducted from the Puzzle Rush band per strike. */
    const val RUSH_STRIKE_PENALTY = 3

    /** How many standard Rated Puzzles the cold-start step uses. */
    const val RATED_PUZZLE_COUNT = 3

    /** Effective solve time (seconds) contributed by a FAILED puzzle. */
    const val PUZZLE_FAIL_TIME_SEC = 180

    /**
     * Duration of the Puzzle Rush run (minutes). Credited to the linked
     * rush habit's minutes secondary value when a run is reported.
     */
    const val RUSH_RUN_MINUTES = 3

    // ── Adaptive gate constants ────────────────────────────────────────────

    /** How many recent tests feed the percentile thresholds (at most). */
    const val HISTORY_WINDOW_TESTS = 15

    /** Rolling age limit for tests counted toward the percentiles (days). */
    const val HISTORY_WINDOW_DAYS = 21

    /** Minimum recent tests required before percentiles replace cold start. */
    const val MIN_HISTORY_SAMPLE = 5

    /**
     * STRICT ABSOLUTE CUTOFFS — a score at/above these ALWAYS passes, and
     * the adaptive thresholds can never rise above them. This guarantees
     * the gate never ratchets up beyond the fixed bar, however strong the
     * recent history gets.
     */
    const val ABSOLUTE_GREEN = 80
    const val ABSOLUTE_YELLOW = 55

    /**
     * FLOORS — the adaptive thresholds can never sink below these, so a
     * prolonged slump cannot erode the gate into meaninglessness.
     */
    const val GREEN_FLOOR = 45
    const val YELLOW_FLOOR = 30

    /**
     * COLD START defaults used while fewer than [MIN_HISTORY_SAMPLE]
     * recent tests exist (deliberately gentler than the old fixed 85/70).
     */
    const val COLD_START_GREEN = 75
    const val COLD_START_YELLOW = 55

    /**
     * The share of readiness tests that should pass GREEN — the user's
     * target pass rate, ADJUSTABLE IN SETTINGS (Settings → ♟ Chess
     * Readiness → Green Pass-Rate Target; changes are discouraged with
     * a stability warning). The Green bar is placed at the
     * (1 − target) percentile of the recent window so that, on a
     * stationary score distribution, ~target of attempts clear it.
     * Default 0.30 — the corrected request ("only let me play ~30% of
     * the time, at the top of my form"); the original "~70%" was a
     * misspeak for the opposite.
     */
    const val DEFAULT_GREEN_TARGET = 0.30

    /** Allowed range for the user-set Green pass-rate target (fraction). */
    const val MIN_GREEN_TARGET = 0.05
    const val MAX_GREEN_TARGET = 0.95

    /**
     * Green-bar percentile derived from a target pass fraction: the
     * (1 − target) percentile, so ~target of a stationary distribution
     * clears the bar.
     */
    fun greenPercentileFor(targetFraction: Double): Double =
        1.0 - targetFraction.coerceIn(MIN_GREEN_TARGET, MAX_GREEN_TARGET)

    /**
     * Yellow-bar percentile derived from a target pass fraction: always
     * 25 percentile points below the Green bar, keeping a casual-only
     * band for roughly the next ~25% of attempts.
     */
    fun yellowPercentileFor(targetFraction: Double): Double =
        (greenPercentileFor(targetFraction) - 0.25).coerceAtLeast(0.02)

    // ── Form-relative baselines (v3.1) ────────────────────────────────────

    /** How many recent tests feed the puzzle-time / rush-score baselines. */
    const val BASELINE_WINDOW = 10

    /** Minimum recent samples before the relative baselines replace cold start. */
    const val MIN_BASELINE_SAMPLES = 3

    // ── Survey calibration (v3.1) ─────────────────────────────────────────

    /** How many paired tests (survey + objective) feed the calibration metric. */
    const val CALIBRATION_WINDOW = 20

    /** Minimum paired samples before the survey is down-weighted at all. */
    const val CALIBRATION_MIN_SAMPLES = 6

    /**
     * Mean |survey − objective| gap (on the 0–10 scale) at which the
     * survey weight bottoms out at [CALIBRATION_MIN_WEIGHT].
     */
    const val CALIBRATION_MAE_FULL = 3.0

    /** Floor for the survey weight — the self-report always keeps some voice. */
    const val CALIBRATION_MIN_WEIGHT = 0.35

    // ── Input models ───────────────────────────────────────────────────────

    /**
     * Maps a raw Garmin sleep score (0–100, or manual equivalent) to
     * sub-component 1 points (0–25) across 6 tiers:
     *  - ≥ 85  → 25 (restorative night)
     *  - 75–84 → 20
     *  - 65–74 → 15
     *  - 55–64 → 10
     *  - 45–54 → 5
     *  - < 45  → 0 (impaired)
     */
    fun sleepPoints(sleepScore: Int): Int {
        val s = sleepScore.coerceIn(0, 100)
        return when {
            s >= 85 -> 25
            s >= 75 -> 20
            s >= 65 -> 15
            s >= 55 -> 10
            s >= 45 -> 5
            else -> 0
        }
    }

    /**
     * Maps the average of the clarity sliders (each normalized 0–10,
     * higher = better) to sub-component 2 points (0–25) across 6 tiers:
     *  - ≥ 8.0  → 25   (sharp, calm, energized)
     *  - 6.5–7.9 → 20
     *  - 5.0–6.4 → 15
     *  - 3.5–4.9 → 10
     *  - 2.0–3.4 → 5
     *  - < 2.0  → 0    (brain fog, exhaustion)
     */
    fun clarityPoints(average: Double): Int = when {
        average >= 8.0 -> 25
        average >= 6.5 -> 20
        average >= 5.0 -> 15
        average >= 3.5 -> 10
        average >= 2.0 -> 5
        else -> 0
    }

    /**
     * Maps the raw 1–10 clarity slider answers (stress / focus / energy)
     * to the 0–10 "higher = better" clarity average consumed by
     * [clarityPoints].
     *
     * All three sliders share one convention — the positive end is 10 on
     * the right: stress 1 = very stressed → 0 … 10 = very calm → 10, and
     * focus / energy map 1 → 0 … 10 → 10. The mapping (v − 1) · 10/9
     * keeps both endpoints exact and every one of the 10 positions
     * distinct. (v3.0 stored 1–5 sliders; persisted records were
     * migrated ×2 so the stored scale matches — see [scaleSurveyTo10].)
     */
    fun clarityAverageFromSliders(stress: Int, focus: Int, energy: Int): Double {
        fun to10(v: Int): Double = (v.coerceIn(1, 10) - 1) * (10.0 / 9.0)
        val calm10 = to10(stress)
        val focus10 = to10(focus)
        val energy10 = to10(energy)
        return (calm10 + focus10 + energy10) / 3.0
    }

    /**
     * One-time 5-point → 10-point survey migration: a stored slider value
     * 1–5 doubles to 2–10; 0 (the "no data" sentinel of legacy seeded
     * records) and anything already on the 10-point scale pass through
     * untouched. Used by both persistence layers ([ChessReadinessStore]
     * for in-progress sessions, [ChessReadinessLogStore] for the
     * permanent telemetry log).
     */
    fun scaleSurveyTo10(value: Int): Int = if (value in 1..5) value * 2 else value

    /** All questionnaire inputs for a single Phase 1 test submission. */
    data class ReadinessInput(
        /** Raw sleep score 0–100 (Garmin or manual entry). */
        val sleepScore: Int,
        /** Clarity average 0–10 (see [clarityAverageFromSliders]). */
        val clarityAverage: Double,
        /**
         * Effective solve times (seconds) of the standard rated puzzles, in
         * order. A failed puzzle should be passed as [PUZZLE_FAIL_TIME_SEC].
         */
        val puzzleTimesSec: List<Int>,
        /** Puzzles solved in the 3-minute Puzzle Rush run. */
        val rushScore: Int,
        /** All-time best Puzzle Rush score (maintained in Settings). */
        val rushAllTimeHigh: Int,
        /** Strikes (failures) incurred during the 3-minute run. */
        val rushStrikes: Int
    )

    // ── Result models ──────────────────────────────────────────────────────

    /** Traffic-light authorization state derived from the CCRS. */
    enum class ReadinessState(
        val colorHex: String,
        val message: String,
        val permitted: List<String>,
        val prohibited: List<String>
    ) {
        GREEN_LIGHT(
            "#22C55E",
            "Peak Executive Capacity. Rated Play & Deep Study Authorized.",
            listOf(
                "Rated Blitz / Rapid / Classical",
                "High-intensity opening research",
                "Endgame calculation"
            ),
            listOf("Casual/unrated games", "Passive video watching")
        ),
        YELLOW_LIGHT(
            "#EAB308",
            "Moderate Depletion. RATED PLAY PROHIBITED. Pivot to Casual Bots & Easy Drills.",
            listOf(
                "Unrated casual games ONLY",
                "Bot scrimmages",
                "Easy pattern repetition (Mate-in-1/2)",
                "Flashcards"
            ),
            listOf("ALL RATED PLAY", "Deep theoretical calculation")
        ),
        RED_LIGHT(
            "#EF4444",
            "Suboptimal Cognitive State. ALL CHESS PROHIBITED. Prioritize Biological Recovery.",
            listOf("Biological recovery", "Light exercise", "Outdoor breaks", "Rest"),
            listOf("ALL chess play, drilling, tactics, and study")
        );

        companion object {
            /**
             * Static mapping used only where no history is available at all
             * (cold-start defaults). The real gate decision is
             * [stateFor] with [computeThresholds].
             */
            fun fromScore(ccrs: Int): ReadinessState = when {
                ccrs >= COLD_START_GREEN -> GREEN_LIGHT
                ccrs >= COLD_START_YELLOW -> YELLOW_LIGHT
                else -> RED_LIGHT
            }
        }
    }

    /** A recorded Phase 1 test (persisted for rate limiting + percentiles). */
    data class ReadinessTest(
        /** Epoch millis at submission. */
        val timestamp: Long,
        /** Composite score 0–100. */
        val ccrs: Int,
        /** Authorization state name (see [ReadinessState]). */
        val state: String,
        // ── Optional telemetry (v3.1) — drives the form-relative baselines
        // and the survey-calibration weighting. Null on legacy records.
        /** Survey clarity average 0–10 reported with this test. */
        val clarityAverage: Double? = null,
        /** Average effective rated-puzzle solve time (seconds). */
        val puzzleAvgSec: Int? = null,
        /** Puzzle Rush score reported with this test. */
        val rushScore: Int? = null,
        /** Rated-puzzle sub-score 0–25. */
        val pPuzzle: Int? = null,
        /** Puzzle-Rush sub-score 0–25. */
        val pRush: Int? = null
    )

    /** What the adaptive thresholds were derived from. */
    enum class ThresholdBasis { PERCENTILE, COLD_START }

    /**
     * The dynamic pass bars for one evaluation: a score ≥ [green] is
     * GREEN (rated play), ≥ [yellow] is YELLOW (casual play).
     */
    data class ReadinessThreshold(
        val green: Int,
        val yellow: Int,
        val basis: ThresholdBasis,
        /** How many recent tests fed the percentiles (0 for cold start). */
        val sampleSize: Int
    )

    /** Full result of a successful evaluation. */
    data class ReadinessResult(
        val timestamp: Long,
        val ccrs: Int,
        val state: ReadinessState,
        val sSleep: Int,
        val sClarity: Int,
        val pPuzzle: Int,
        val pRush: Int,
        /** Epoch millis after which the authorization expires. */
        val validUntil: Long,
        // ── Adaptive gate transparency (shown on the result screen) ──
        /** The GREEN bar this attempt was judged against. */
        val greenThreshold: Int,
        /** The YELLOW bar this attempt was judged against. */
        val yellowThreshold: Int,
        /** Whether the bars came from recent-history percentiles. */
        val thresholdBasis: ThresholdBasis,
        /** How many recent tests fed the percentiles (0 for cold start). */
        val thresholdSampleSize: Int,
        // ── Survey calibration transparency (v3.1) ──
        /** The clarity average the user actually reported (0–10). */
        val clarityReported: Double,
        /** The clarity average used for scoring, after calibration shrinkage. */
        val clarityEffective: Double,
        /** How much weight the survey carried ([CALIBRATION_MIN_WEIGHT], 1]. */
        val surveyWeight: Double,
        /** Mean |survey − objective| gap behind the weight (null = too few samples). */
        val surveyMae: Double?,
        /** How many paired tests fed the calibration (may be < [CALIBRATION_MIN_SAMPLES]). */
        val surveySampleSize: Int
    )

    /**
     * Why a test cannot be run right now (rate limiting). [retryAt] is the
     * epoch-ms timestamp at which the block lifts (0 = unknown) — the UI
     * renders it as a clock time / remaining wait.
     */
    sealed class GateError(val message: String, val retryAt: Long) {
        class MaxDailyTests(message: String, retryAt: Long) : GateError(message, retryAt)
        class CooldownActive(message: String, retryAt: Long) : GateError(message, retryAt)
        class RestPeriodActive(message: String, retryAt: Long) : GateError(message, retryAt)
    }

    /** Outcome of the pre-flight rate-limit check. */
    sealed class GateStatus {
        /** Test allowed. [testsToday] is how many were run in the last 24 h. */
        data class Allowed(val testsToday: Int) : GateStatus()

        /** Test blocked — see [error]. */
        data class Blocked(val error: GateError) : GateStatus()
    }

    // ── Adaptive thresholds ────────────────────────────────────────────────

    /**
     * Linear-interpolation percentile (type 7, the numpy/Excel default) of
     * an UNSORTED list of values at probability `p` ∈ [0, 1]. Pure and
     * deterministic; 0 for an empty list.
     */
    fun percentileOf(values: List<Int>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0].toDouble()
        val rank = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /**
     * Computes the dynamic GREEN/YELLOW pass bars from the user's own
     * recent history:
     *
     *  1. Take the tests submitted inside the last [HISTORY_WINDOW_DAYS]
     *     days (at most the newest [HISTORY_WINDOW_TESTS] of them).
     *  2. If fewer than [MIN_HISTORY_SAMPLE] remain → cold-start defaults.
     *  3. Otherwise GREEN = [greenPercentileFor]-percentile and
     *     YELLOW = [yellowPercentileFor]-percentile of those scores
     *     (both derived from [greenTargetFraction]),
     *     clamped to [[GREEN_FLOOR], [ABSOLUTE_GREEN]] and
     *     [[YELLOW_FLOOR], [ABSOLUTE_YELLOW]] respectively.
     *
     * The clamps implement the two safety rails: the strict absolute
     * cutoffs (a great score ALWAYS passes; the bar can never ratchet
     * above them) and the floors (a slump can never erode the gate to
     * zero). Because the bars follow the user's own recent distribution,
     * a run of weak tests automatically lowers the bar — "relatively
     * better than usual" becomes enough to play.
     */
    fun computeThresholds(
        history: List<ReadinessTest>,
        now: Long,
        greenTargetFraction: Double = DEFAULT_GREEN_TARGET
    ): ReadinessThreshold {
        val cutoff = now - HISTORY_WINDOW_DAYS * 24L * 60 * 60 * 1000
        val window = history
            .filter { it.timestamp >= cutoff && it.timestamp <= now }
            .sortedBy { it.timestamp }
            .takeLast(HISTORY_WINDOW_TESTS)
            .map { it.ccrs }

        if (window.size < MIN_HISTORY_SAMPLE) {
            return ReadinessThreshold(
                green = COLD_START_GREEN,
                yellow = COLD_START_YELLOW,
                basis = ThresholdBasis.COLD_START,
                sampleSize = window.size
            )
        }

        val green = percentileOf(window, greenPercentileFor(greenTargetFraction))
            .toInt()
            .coerceIn(GREEN_FLOOR, ABSOLUTE_GREEN)
        val yellow = percentileOf(window, yellowPercentileFor(greenTargetFraction))
            .toInt()
            .coerceIn(YELLOW_FLOOR, ABSOLUTE_YELLOW)
            // Defensive: keep YELLOW strictly below GREEN so the casual
            // band never disappears (mathematically unreachable, cheap to
            // guarantee).
            .let { minOf(it, green - 1) }

        return ReadinessThreshold(
            green = green,
            yellow = yellow,
            basis = ThresholdBasis.PERCENTILE,
            sampleSize = window.size
        )
    }

    /**
     * The gate decision: GREEN at/above the dynamic green bar, YELLOW
     * at/above the dynamic yellow bar, otherwise RED.
     */
    fun stateFor(ccrs: Int, threshold: ReadinessThreshold): ReadinessState = when {
        ccrs >= threshold.green -> ReadinessState.GREEN_LIGHT
        ccrs >= threshold.yellow -> ReadinessState.YELLOW_LIGHT
        else -> ReadinessState.RED_LIGHT
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    /**
     * Validates the test attempt against the 24 h cap and the cool-down /
     * rest-period rules. Pure function of [history] and [now].
     *
     * Whether the last test "passed" is decided by its RECORDED state name
     * (the adaptive decision at the time), not a fixed score — records
     * from before this engine version (blank state) fall back to the
     * legacy ≥ 70 heuristic.
     */
    fun checkGate(history: List<ReadinessTest>, now: Long): GateStatus {
        val testsLast24h = history.filter { now - it.timestamp < 24L * 60 * 60 * 1000 }
        if (testsLast24h.size >= MAX_DAILY_TESTS) {
            // The oldest test inside the rolling 24 h window ages out first —
            // that moment is the earliest the cap can lift.
            val retryAt = testsLast24h.minOf { it.timestamp } + 24L * 60 * 60 * 1000
            return GateStatus.Blocked(
                GateError.MaxDailyTests(
                    "Maximum of $MAX_DAILY_TESTS readiness tests allowed per 24 hours " +
                        "to prevent test fatigue. Next test in ${formatWait(retryAt - now)}.",
                    retryAt
                )
            )
        }

        val lastTest = history.maxByOrNull { it.timestamp } ?: return GateStatus.Allowed(0)
        val timeSinceLast = now - lastTest.timestamp
        val lastPassed = when (lastTest.state) {
            ReadinessState.GREEN_LIGHT.name,
            ReadinessState.YELLOW_LIGHT.name -> true
            ReadinessState.RED_LIGHT.name -> false
            else -> lastTest.ccrs >= 70 // legacy records without a state name
        }

        return if (lastPassed) {
            if (timeSinceLast < COOLDOWN_MS) {
                val retryAt = lastTest.timestamp + COOLDOWN_MS
                val minsRemaining = ((COOLDOWN_MS - timeSinceLast) / 60000L).toInt() + 1
                GateStatus.Blocked(
                    GateError.CooldownActive(
                        "Session active or cool-down in progress. Please wait " +
                            "$minsRemaining more minute(s).",
                        retryAt
                    )
                )
            } else GateStatus.Allowed(testsLast24h.size)
        } else {
            val restMs = restPeriodForScore(lastTest.ccrs)
            if (timeSinceLast < restMs) {
                val retryAt = lastTest.timestamp + restMs
                val minsRemaining = ((restMs - timeSinceLast) / 60000L).toInt() + 1
                GateStatus.Blocked(
                    GateError.RestPeriodActive(
                        "Failed test (score ${lastTest.ccrs}) — recovery lock. " +
                            "Re-test in $minsRemaining more minute(s).",
                        retryAt
                    )
                )
            } else GateStatus.Allowed(testsLast24h.size)
        }
    }

    /** "1 h 23 min" / "42 min" (rounded up) — used inside block messages. */
    private fun formatWait(ms: Long): String {
        val totalMin = ((ms + 59999L) / 60000L).toInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /**
     * Recovery lock length for a given Phase 1 score: 0 for a pass,
     * otherwise one of the stepped [REST_MS_MARGINAL] / [REST_MS_POOR] /
     * [REST_MS_SEVERE] tiers — worse attempts lock the re-test for longer.
     */
    fun restPeriodForScore(ccrs: Int): Long = when {
        ccrs >= 70 -> 0L
        ccrs >= 60 -> REST_MS_MARGINAL
        ccrs >= 40 -> REST_MS_POOR
        else -> REST_MS_SEVERE
    }

    // ── Sub-component scoring ──────────────────────────────────────────────

    /**
     * Sub-component 3: Cold-Start Tactical Score over the standard rated
     * puzzles. The caller passes one effective time per puzzle (a failed
     * puzzle = [PUZZLE_FAIL_TIME_SEC]).
     *
     * With [recentAvgSec] — the user's own recent average solve time, see
     * [recentPuzzleAvgSec] — the score measures FORM: the ratio of
     * today's average to the user's recent typical, so a rising chess.com
     * puzzle rating (harder served puzzles → mechanically slower times)
     * no longer erodes this component:
     *  - ratio < 0.55 → 25   (much sharper than usual)
     *  - < 0.75       → 21
     *  - < 0.95       → 17
     *  - < 1.15       → 13
     *  - < 1.40       → 9
     *  - < 1.70       → 4
     *  - otherwise    → 0    (clearly off form / failing)
     *
     * Without a baseline (cold start, legacy records) the absolute speed
     * tiers apply as before:
     *  - avg < 30 s  → 25   (blitz-sharp)
     *  - < 45 s      → 21
     *  - < 60 s      → 17
     *  - < 90 s      → 13
     *  - < 120 s     → 9
     *  - < 150 s     → 4
     *  - otherwise   → 0    (grinding / failing)
     */
    fun ratedPuzzleScore(timesSec: List<Int>, recentAvgSec: Double? = null): Int {
        if (timesSec.isEmpty()) return 0
        val avg = timesSec.map { it.coerceAtLeast(0) }.average()
        if (recentAvgSec != null && recentAvgSec > 0.0) {
            val ratio = avg / recentAvgSec
            return when {
                ratio < 0.55 -> 25
                ratio < 0.75 -> 21
                ratio < 0.95 -> 17
                ratio < 1.15 -> 13
                ratio < 1.40 -> 9
                ratio < 1.70 -> 4
                else -> 0
            }
        }
        return when {
            avg < 30 -> 25
            avg < 45 -> 21
            avg < 60 -> 17
            avg < 90 -> 13
            avg < 120 -> 9
            avg < 150 -> 4
            else -> 0
        }
    }

    /**
     * Sub-component 4: 3-Minute Puzzle Rush Score.
     *
     * The ratio of this run to the effective baseline picks one of 6 bands
     * (25 / 21 / 17 / 13 / 8 / 4 / 0); each strike then deducts
     * [RUSH_STRIKE_PENALTY] points from the band, floored at 0. This
     * keeps a strong run that happens to include mistakes from being
     * zeroed out.
     *
     * v3.1: when [recentMedian] — the median of the user's own recent
     * rush scores, see [recentRushMedian] — is available it replaces the
     * all-time high as the baseline. The all-time high only ever ratchets
     * UP (a max grows faster than typical performance), so the typical
     * ratio decayed over time; the recent median tracks current form and
     * is immune to that ratchet. The all-time high (with its cold-start
     * floor) remains the fallback baseline.
     */
    fun rushScore(score: Int, allTimeHigh: Int, strikes: Int, recentMedian: Int? = null): Int {
        val effectiveBaseline = if (recentMedian != null && recentMedian >= RUSH_BASELINE_FLOOR)
            recentMedian
        else
            maxOf(allTimeHigh, RUSH_BASELINE_FLOOR)
        val ratio = score.toDouble() / effectiveBaseline
        val band = when {
            ratio >= 0.90 -> 25
            ratio >= 0.80 -> 21
            ratio >= 0.70 -> 17
            ratio >= 0.60 -> 13
            ratio >= 0.50 -> 8
            ratio >= 0.40 -> 4
            else -> 0
        }
        return maxOf(0, band - RUSH_STRIKE_PENALTY * strikes.coerceAtLeast(0))
    }

    /**
     * All-time-high maintenance: the stored best only ever moves UP — a
     * submitted run below (or equal to) the current best leaves it untouched.
     * The value is editable by hand in Settings and is auto-raised from the
     * widget questionnaire whenever a run beats it.
     */
    fun nextAllTimeHigh(current: Int, submittedScore: Int): Int =
        maxOf(current, submittedScore.coerceAtLeast(0))

    // ── Form-relative baselines & survey calibration (v3.1) ───────────────

    /**
     * Mean of the last ≤ [BASELINE_WINDOW] recorded puzzle averages
     * (null = not enough samples → absolute-tier cold start).
     */
    fun recentPuzzleAvgSec(history: List<ReadinessTest>): Double? =
        history.mapNotNull { it.puzzleAvgSec?.takeIf { v -> v > 0 } }
            .takeLast(BASELINE_WINDOW)
            .let { if (it.size >= MIN_BASELINE_SAMPLES) it.average() else null }

    /**
     * Median of the last ≤ [BASELINE_WINDOW] recorded rush scores
     * (null = not enough samples → all-time-high cold start).
     */
    fun recentRushMedian(history: List<ReadinessTest>): Int? {
        val scores = history.mapNotNull { it.rushScore?.takeIf { v -> v > 0 } }
            .takeLast(BASELINE_WINDOW)
        if (scores.size < MIN_BASELINE_SAMPLES) return null
        val sorted = scores.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else Math.round((sorted[mid - 1] + sorted[mid]) / 2.0).toInt()
    }

    /** Survey-vs-objective calibration state for one evaluation. */
    data class SurveyCalibration(
        /**
         * The weight the current self-survey deserves in
         * [effectiveClarityAverage], in [CALIBRATION_MIN_WEIGHT, 1].
         */
        val weight: Double,
        /**
         * Mean |survey − objective| gap over the paired window, on the
         * 0–10 scale. Null when too few paired samples exist (weight is
         * then the neutral 1.0).
         */
        val mae: Double?,
        /** How many paired tests fed the metric. */
        val sampleSize: Int,
        /**
         * Mean objective level of the paired window (0–10) — the anchor
         * an uncalibrated survey is shrunk toward. Null when weight is
         * the neutral default.
         */
        val objectiveAnchor: Double?
    )

    /**
     * Measures how accurately the user's self-survey has been representing
     * their actual objective results (rated puzzles + Puzzle Rush, mapped
     * to the same 0–10 scale) and converts that accuracy into the weight
     * the survey deserves on the NEXT test:
     *
     *  - pairs = (clarityAverage, (pPuzzle + pRush) / 5) of the last
     *    ≤ [CALIBRATION_WINDOW] tests that carry both values;
     *  - MAE = mean |survey − objective| — this catches BOTH noise (reports
     *    that don't covary with results) and level bias (consistently
     *    inflated reports), unlike a plain correlation which is blind to
     *    uniform inflation;
     *  - weight = [CALIBRATION_MIN_WEIGHT] + (1 − MIN) · clamp(1 − MAE /
     *    [CALIBRATION_MAE_FULL]) — perfect representation → 1.0, ≥ 3
     *    points mean error → the floor.
     *
     * With fewer than [CALIBRATION_MIN_SAMPLES] pairs the neutral default
     * (weight 1.0, no anchor) applies — the survey is trusted until there
     * is evidence it shouldn't be.
     */
    fun surveyCalibration(history: List<ReadinessTest>): SurveyCalibration {
        val pairs = history
            .filter { it.clarityAverage != null && it.pPuzzle != null && it.pRush != null }
            .takeLast(CALIBRATION_WINDOW)
            .map { it.clarityAverage!! to (it.pPuzzle!! + it.pRush!!) / 5.0 }
        if (pairs.size < CALIBRATION_MIN_SAMPLES) {
            return SurveyCalibration(weight = 1.0, mae = null, sampleSize = pairs.size, objectiveAnchor = null)
        }
        val mae = pairs.sumOf { abs(it.first - it.second) } / pairs.size
        val anchor = pairs.sumOf { it.second } / pairs.size
        val weight = CALIBRATION_MIN_WEIGHT +
            (1.0 - CALIBRATION_MIN_WEIGHT) * (1.0 - mae / CALIBRATION_MAE_FULL).coerceIn(0.0, 1.0)
        return SurveyCalibration(weight, mae, pairs.size, anchor)
    }

    /**
     * The clarity average actually used for scoring: the reported value
     * blended with the objective anchor according to the calibration
     * weight. A fully calibrated survey passes through untouched; an
     * uninformative one is shrunk toward the level the objective results
     * say is real — so inflating the sliders never buys clarity points,
     * and honest low reports during a slump keep the survey's full voice.
     */
    fun effectiveClarityAverage(reported: Double, calibration: SurveyCalibration): Double {
        val r = reported.coerceIn(0.0, 10.0)
        val anchor = calibration.objectiveAnchor ?: return r
        return (calibration.weight * r + (1.0 - calibration.weight) * anchor)
            .coerceIn(0.0, 10.0)
    }

    // ── Master evaluation ──────────────────────────────────────────────────

    /**
     * Runs the full evaluation. The caller is responsible for calling
     * [checkGate] first; this function performs the pure scoring math and
     * returns the composite result (no rate limiting inside).
     *
     * [history] is the user's PRIOR tests (the overlay appends the new one
     * only after this call) — it drives the adaptive percentile thresholds
     * the score is judged against. Omit it (or pass an empty list) to use
     * the cold-start bars.
     */
    fun evaluate(
        input: ReadinessInput,
        now: Long,
        history: List<ReadinessTest> = emptyList(),
        greenTargetFraction: Double = DEFAULT_GREEN_TARGET
    ): ReadinessResult {
        val sSleep = sleepPoints(input.sleepScore)
        val calibration = surveyCalibration(history)
        val clarityEffective = effectiveClarityAverage(input.clarityAverage, calibration)
        val sClarity = clarityPoints(clarityEffective)
        val pPuzzle = ratedPuzzleScore(input.puzzleTimesSec, recentPuzzleAvgSec(history))
        val pRush = rushScore(
            input.rushScore, input.rushAllTimeHigh, input.rushStrikes,
            recentRushMedian(history)
        )

        val ccrs = (sSleep + sClarity + pPuzzle + pRush).coerceIn(0, 100)
        val threshold = computeThresholds(history, now, greenTargetFraction)
        return ReadinessResult(
            timestamp = now,
            ccrs = ccrs,
            state = stateFor(ccrs, threshold),
            sSleep = sSleep,
            sClarity = sClarity,
            pPuzzle = pPuzzle,
            pRush = pRush,
            validUntil = now + SESSION_VALIDITY_MS,
            greenThreshold = threshold.green,
            yellowThreshold = threshold.yellow,
            thresholdBasis = threshold.basis,
            thresholdSampleSize = threshold.sampleSize,
            clarityReported = input.clarityAverage.coerceIn(0.0, 10.0),
            clarityEffective = clarityEffective,
            surveyWeight = calibration.weight,
            surveyMae = calibration.mae,
            surveySampleSize = calibration.sampleSize
        )
    }
}
