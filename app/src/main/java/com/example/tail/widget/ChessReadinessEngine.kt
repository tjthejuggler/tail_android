package com.example.tail.widget

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
 * The engine is PURE — no Android dependencies — so it is unit-testable.
 * Persistence lives in [ChessReadinessStore]; UI lives in
 * [ChessReadinessOverlay].
 *
 * Rate-limiting rules (anti "test-hunting"):
 *  - Max 4 tests per rolling 24-hour window.
 *  - Last test Green/Yellow → strict 60-minute cool-down.
 *  - Last test Red → mandatory 30/60/120-minute biological rest break
 *    (scaled by how poor the attempt was) before a re-test is allowed.
 */
object ChessReadinessEngine {

    // ── Constants ──────────────────────────────────────────────────────────

    /** Max Phase 1 tests allowed per rolling 24-hour window. */
    const val MAX_DAILY_TESTS = 4

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

    /** Which percentile of the recent window maps to the GREEN bar. */
    const val GREEN_PERCENTILE = 0.60

    /** Which percentile of the recent window maps to the YELLOW bar. */
    const val YELLOW_PERCENTILE = 0.35

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
     * Maps the raw 1–5 clarity slider answers (stress / focus / energy) to
     * the 0–10 "higher = better" clarity average consumed by
     * [clarityPoints].
     *
     * All three sliders share one convention — the positive end is 5 on
     * the right: stress 1 = very stressed → 0 … 5 = very calm → 10, and
     * focus / energy map 1 → 0 … 5 → 10. With three discrete 5-point
     * sliders every value is always selectable (no missed ticks).
     */
    fun clarityAverageFromSliders(stress: Int, focus: Int, energy: Int): Double {
        fun to10(v: Int): Double = (v.coerceIn(1, 5) - 1) * 2.5
        val calm10 = to10(stress)
        val focus10 = to10(focus)
        val energy10 = to10(energy)
        return (calm10 + focus10 + energy10) / 3.0
    }

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
        val state: String
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
        val thresholdSampleSize: Int
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
     *  3. Otherwise GREEN = [GREEN_PERCENTILE]-percentile and
     *     YELLOW = [YELLOW_PERCENTILE]-percentile of those scores,
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
    fun computeThresholds(history: List<ReadinessTest>, now: Long): ReadinessThreshold {
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

        val green = percentileOf(window, GREEN_PERCENTILE)
            .toInt()
            .coerceIn(GREEN_FLOOR, ABSOLUTE_GREEN)
        val yellow = percentileOf(window, YELLOW_PERCENTILE)
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
     * puzzle = [PUZZLE_FAIL_TIME_SEC]); the average maps to points across
     * 7 speed tiers — quickness/slowness now matters in fine steps:
     *  - avg < 30 s  → 25   (blitz-sharp)
     *  - < 45 s      → 21
     *  - < 60 s      → 17
     *  - < 90 s      → 13
     *  - < 120 s     → 9
     *  - < 150 s     → 4
     *  - otherwise   → 0    (grinding / failing)
     */
    fun ratedPuzzleScore(timesSec: List<Int>): Int {
        if (timesSec.isEmpty()) return 0
        val avg = timesSec.map { it.coerceAtLeast(0) }.average()
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
     * The ratio of this run to the effective baseline (all-time best with a
     * cold-start floor) picks one of 6 bands (25 / 21 / 17 / 13 / 8 / 4 / 0);
     * each strike then deducts [RUSH_STRIKE_PENALTY] points from the band,
     * floored at 0. This keeps a strong run that happens to include
     * mistakes from being zeroed out.
     */
    fun rushScore(score: Int, allTimeHigh: Int, strikes: Int): Int {
        val effectiveBaseline = maxOf(allTimeHigh, RUSH_BASELINE_FLOOR)
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
        history: List<ReadinessTest> = emptyList()
    ): ReadinessResult {
        val sSleep = sleepPoints(input.sleepScore)
        val sClarity = clarityPoints(input.clarityAverage)
        val pPuzzle = ratedPuzzleScore(input.puzzleTimesSec)
        val pRush = rushScore(input.rushScore, input.rushAllTimeHigh, input.rushStrikes)

        val ccrs = (sSleep + sClarity + pPuzzle + pRush).coerceIn(0, 100)
        val threshold = computeThresholds(history, now)
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
            thresholdSampleSize = threshold.sampleSize
        )
    }
}
