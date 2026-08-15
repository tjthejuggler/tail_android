package com.example.tail.widget

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Phase 1 Pre-Session Diagnostic Protocol Engine (spec v2.1)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Computes a normalized Composite Cognitive Readiness Score (CCRS) bounded
 * to [0, 100] from two subjective biological indicators (sleep quality tier,
 * mental clarity derived from slider questions) and two objective
 * Chess.com telemetry proxies (timed standard rated puzzles, 3-minute
 * Puzzle Rush).
 *
 * v2.1 changes (user feedback):
 *  - Mental clarity is derived from several 0–10 slider questions
 *    (focus / calm / energy / alertness) instead of a single tier pick.
 *  - The single Daily Puzzle (wildly varying difficulty) is replaced by
 *    3 timed standard Rated Puzzles, which are catered to the user's
 *    rating. A failed puzzle contributes [PUZZLE_FAIL_TIME_SEC] to the
 *    average.
 *  - The Puzzle Rush strike-out rule (3 strikes → 0) is softened into a
 *    graduated penalty: each strike deducts [RUSH_STRIKE_PENALTY] points
 *    from the ratio band, floored at 0.
 *  - The separate 30-minute "fast puzzle session" entry gate is gone —
 *    the puzzles are now solved DURING the step-by-step test flow.
 *
 * The engine is PURE — no Android dependencies — so it is unit-testable.
 * Persistence lives in [ChessReadinessStore]; UI lives in
 * [ChessReadinessOverlay].
 *
 * Rate-limiting rules (anti "test-hunting"):
 *  - Max 4 tests per rolling 24-hour window.
 *  - Last score ≥ 70 (Green/Yellow) → strict 60-minute cool-down.
 *  - Last score < 70 (Red) → mandatory 30-minute biological rest break
 *    before a re-test is allowed.
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
    const val RUSH_STRIKE_PENALTY = 5

    /** How many standard Rated Puzzles the cold-start step uses. */
    const val RATED_PUZZLE_COUNT = 3

    /** Effective solve time (seconds) contributed by a FAILED puzzle. */
    const val PUZZLE_FAIL_TIME_SEC = 180

    // ── Input models ───────────────────────────────────────────────────────

    /**
     * Sleep quality tier (sub-component 1).
     *  - Tier 1 (25 pts): ≥ 7.5 h uninterrupted sleep, high awakening alertness
     *  - Tier 2 (15 pts): 6.0–7.4 h, or mild interruptions
     *  - Tier 3 (0 pts):  < 6.0 h, severe interruptions, circadian disruption
     */
    enum class SleepTier(val points: Int, val label: String, val description: String) {
        TIER_1(25, "Tier 1", "≥ 7.5 h uninterrupted, high awakening alertness"),
        TIER_2(15, "Tier 2", "6.0–7.4 h, or mild sleep interruptions"),
        TIER_3(0, "Tier 3", "< 6.0 h, severe interruptions, or circadian disruption");

        companion object {
            fun fromOrdinalValue(v: Int): SleepTier = when (v) {
                1 -> TIER_1
                2 -> TIER_2
                else -> TIER_3
            }
        }
    }

    /**
     * Mental clarity tier (sub-component 2), derived from the average of
     * the clarity slider questions.
     *  - Tier 1 (25 pts): average ≥ 7.5
     *  - Tier 2 (15 pts): average 5.0–7.4
     *  - Tier 3 (0 pts):  average < 5.0
     */
    enum class ClarityTier(val points: Int, val label: String, val description: String) {
        TIER_1(25, "Tier 1", "High focus, zero stress, zero discomfort"),
        TIER_2(15, "Tier 2", "Moderate focus, slight fatigue or mild stress"),
        TIER_3(0, "Tier 3", "Brain fog, high stress, exhaustion, discomfort");

        companion object {
            fun fromOrdinalValue(v: Int): ClarityTier = when (v) {
                1 -> TIER_1
                2 -> TIER_2
                else -> TIER_3
            }
        }
    }

    /**
     * Maps a Garmin sleep score (0–100) to a [SleepTier].
     *
     * Garmin's composite sleep score is a proxy for the duration/interruption
     * tiers in the spec:
     *  - ≥ 80  → Tier 1 (restorative night)
     *  - 60–79 → Tier 2 (adequate but imperfect)
     *  - < 60  → Tier 3 (impaired)
     */
    fun sleepTierFromGarminScore(score: Int): SleepTier = when {
        score >= 80 -> SleepTier.TIER_1
        score >= 60 -> SleepTier.TIER_2
        else -> SleepTier.TIER_3
    }

    /**
     * Maps the average of the clarity sliders (each 0–10, higher = better)
     * to a [ClarityTier]:
     *  - ≥ 7.5 → Tier 1 · 5.0–7.4 → Tier 2 · < 5.0 → Tier 3
     */
    fun clarityTierFromAverage(average: Double): ClarityTier = when {
        average >= 7.5 -> ClarityTier.TIER_1
        average >= 5.0 -> ClarityTier.TIER_2
        else -> ClarityTier.TIER_3
    }

    /**
     * Maps the raw 1–5 clarity slider answers (stress / focus / energy) to
     * the 0–10 "higher = better" clarity average consumed by
     * [clarityTierFromAverage].
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
        val sleepTier: SleepTier,
        val clarityTier: ClarityTier,
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
        val minScore: Int,
        val colorHex: String,
        val message: String,
        val permitted: List<String>,
        val prohibited: List<String>
    ) {
        GREEN_LIGHT(
            85, "#22C55E",
            "Peak Executive Capacity. Rated Play & Deep Study Authorized.",
            listOf(
                "Rated Blitz / Rapid / Classical",
                "High-intensity opening research",
                "Endgame calculation"
            ),
            listOf("Casual/unrated games", "Passive video watching")
        ),
        YELLOW_LIGHT(
            70, "#EAB308",
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
            0, "#EF4444",
            "Suboptimal Cognitive State. ALL CHESS PROHIBITED. Prioritize Biological Recovery.",
            listOf("Biological recovery", "Light exercise", "Outdoor breaks", "Rest"),
            listOf("ALL chess play, drilling, tactics, and study")
        );

        companion object {
            fun fromScore(ccrs: Int): ReadinessState = when {
                ccrs >= GREEN_LIGHT.minScore -> GREEN_LIGHT
                ccrs >= YELLOW_LIGHT.minScore -> YELLOW_LIGHT
                else -> RED_LIGHT
            }
        }
    }

    /** A recorded Phase 1 test (persisted for rate limiting). */
    data class ReadinessTest(
        /** Epoch millis at submission. */
        val timestamp: Long,
        /** Composite score 0–100. */
        val ccrs: Int,
        /** Authorization state name (see [ReadinessState]). */
        val state: String
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
        val validUntil: Long
    )

    /** Why a test cannot be run right now (rate limiting). */
    sealed class GateError(val message: String) {
        class MaxDailyTests(message: String) : GateError(message)
        class CooldownActive(message: String) : GateError(message)
        class RestPeriodActive(message: String) : GateError(message)
    }

    /** Outcome of the pre-flight rate-limit check. */
    sealed class GateStatus {
        /** Test allowed. [testsToday] is how many were run in the last 24 h. */
        data class Allowed(val testsToday: Int) : GateStatus()

        /** Test blocked — see [error]. */
        data class Blocked(val error: GateError) : GateStatus()
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    /**
     * Validates the test attempt against the 24 h cap and the cool-down /
     * rest-period rules. Pure function of [history] and [now].
     */
    fun checkGate(history: List<ReadinessTest>, now: Long): GateStatus {
        val testsLast24h = history.filter { now - it.timestamp < 24L * 60 * 60 * 1000 }
        if (testsLast24h.size >= MAX_DAILY_TESTS) {
            return GateStatus.Blocked(
                GateError.MaxDailyTests(
                    "Maximum of $MAX_DAILY_TESTS readiness tests allowed per 24 hours " +
                        "to prevent test fatigue."
                )
            )
        }

        val lastTest = history.maxByOrNull { it.timestamp } ?: return GateStatus.Allowed(0)
        val timeSinceLast = now - lastTest.timestamp

        return if (lastTest.ccrs >= 70) {
            if (timeSinceLast < COOLDOWN_MS) {
                val minsRemaining = ((COOLDOWN_MS - timeSinceLast) / 60000L).toInt() + 1
                GateStatus.Blocked(
                    GateError.CooldownActive(
                        "Session active or cool-down in progress. Please wait " +
                            "$minsRemaining more minute(s)."
                    )
                )
            } else GateStatus.Allowed(testsLast24h.size)
        } else {
            val restMs = restPeriodForScore(lastTest.ccrs)
            if (timeSinceLast < restMs) {
                val minsRemaining = ((restMs - timeSinceLast) / 60000L).toInt() + 1
                GateStatus.Blocked(
                    GateError.RestPeriodActive(
                        "Failed test (score ${lastTest.ccrs}) — recovery lock. " +
                            "Re-test in $minsRemaining more minute(s)."
                    )
                )
            } else GateStatus.Allowed(testsLast24h.size)
        }
    }

    /**
     * Recovery lock length for a given Phase 1 score: 0 for a pass (≥ 70),
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
     * puzzle = [PUZZLE_FAIL_TIME_SEC]); the average maps to points:
     *  - avg < 45 s  → 25
     *  - avg < 120 s → 15
     *  - otherwise   → 0
     */
    fun ratedPuzzleScore(timesSec: List<Int>): Int {
        if (timesSec.isEmpty()) return 0
        val avg = timesSec.map { it.coerceAtLeast(0) }.average()
        return when {
            avg < 45 -> 25
            avg < 120 -> 15
            else -> 0
        }
    }

    /**
     * Sub-component 4: 3-Minute Puzzle Rush Score.
     *
     * The ratio of this run to the effective baseline (all-time best with a
     * cold-start floor) picks a band (25 / 15 / 0); each strike then deducts
     * [RUSH_STRIKE_PENALTY] points from the band, floored at 0. This keeps a
     * strong run that happens to include mistakes from being zeroed out.
     */
    fun rushScore(score: Int, allTimeHigh: Int, strikes: Int): Int {
        val effectiveBaseline = maxOf(allTimeHigh, RUSH_BASELINE_FLOOR)
        val ratio = score.toDouble() / effectiveBaseline
        val band = when {
            ratio >= 0.80 -> 25
            ratio >= 0.65 -> 15
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
     */
    fun evaluate(input: ReadinessInput, now: Long): ReadinessResult {
        val sSleep = input.sleepTier.points
        val sClarity = input.clarityTier.points
        val pPuzzle = ratedPuzzleScore(input.puzzleTimesSec)
        val pRush = rushScore(input.rushScore, input.rushAllTimeHigh, input.rushStrikes)

        val ccrs = sSleep + sClarity + pPuzzle + pRush
        return ReadinessResult(
            timestamp = now,
            ccrs = ccrs,
            state = ReadinessState.fromScore(ccrs),
            sSleep = sSleep,
            sClarity = sClarity,
            pPuzzle = pPuzzle,
            pRush = pRush,
            validUntil = now + SESSION_VALIDITY_MS
        )
    }
}
