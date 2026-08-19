package com.example.tail.widget

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Phase 2 Post-Game Performance Audit Engine (spec v2.0 — adaptive)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Executes a rapid post-game telemetry audit following every rated match
 * played during an active session. v2.0 replaces v1.0's fixed single-game
 * thresholds (where ANY loss to an equal opponent — ΔE = −0.5 — instantly
 * terminated the session) with an EVIDENCE-WEIGHTED, PERSONALLY ADAPTIVE
 * model in the same spirit as the Phase 1 v3.0 readiness gate:
 *
 *  1. PERSONAL ΔE FLOORS — the terminate/pivot bars are percentiles
 *     (p10 / p25) of the user's OWN recent ΔE history (last 15 audited
 *     games within 21 days), clamped to sane absolute bounds. Someone who
 *     routinely posts −0.5 games is not flagged for a −0.5 game.
 *  2. STRAIN ACCUMULATION — each game adds 0–100 "strain" points based on
 *     ΔE vs the personal floors plus accuracy/blunder violations. A single
 *     bad game can at most PIVOT (pause rated play); TERMINATION requires
 *     accumulated evidence across the session (total strain ≥ 100 + buffer).
 *  3. READINESS BUFFER — the pre-game Phase 1 CCRS is combined with game
 *     results: a strong readiness test raises the termination bar and can
 *     absorb ONE moderate dip entirely.
 *  4. HARD CUTOFFS — ΔE ≤ −0.75 (e.g. losing to a far weaker opponent) or
 *     strain = 100 in a single game (bad result + accuracy drop + unforced
 *     blunders all at once) still terminates immediately, regardless of
 *     history or readiness. The 60-minute capacity ceiling is unchanged.
 *
 * Outputs one of three operational commands:
 *  - [OutputState.CONTINUE_RATED]  (Green)  — cleared for the next rated game
 *  - [OutputState.PIVOT_TO_DRILLS] (Yellow) — rated play suspended; pivot to
 *    unrated matches, bot scrimmages, or low-stakes drills
 *  - [OutputState.TERMINATE_SESSION] (Red)  — all play and study halted;
 *    proceed to biological recovery
 *
 * Edge cases:
 *  - Short games / early resignation (< 10 moves): pass [GameInput.shortGame]
 *    = true — ΔE still counts, but accuracy-drop violations are bypassed.
 *  - No rolling accuracy mean: default baselines apply per time control.
 *  - No ΔE history yet (< 5 games): cold-start floors (−0.45 / −0.20) apply;
 *    even then a single normal loss only PIVOTs, never TERMINATEs.
 *
 * The engine is PURE — no Android dependencies — so it is unit-testable.
 * Persistence lives in [ChessPhase2Store]; the audit is triggered by sharing
 * the finished game's chess.com link to Tail (see [ChessGameAuditMapper] and
 * com.example.tail.ChessGameShareActivity).
 */
object ChessPhase2Engine {

    // ── Constants ──────────────────────────────────────────────────────────

    /** Prefrontal fatigue ceiling: cumulative minutes of play per session. */
    const val SESSION_CAP_MINUTES = 60

    /**
     * HARD CUTOFF: ΔE at/below this ALWAYS terminates the session — no
     * history, no readiness buffer can absorb it (e.g. losing to an
     * opponent rated ~300+ points lower).
     */
    const val CATASTROPHIC_DELTA_E = -0.75

    /** The personal terminate floor is never STRICTER than this. */
    const val TERMINATE_FLOOR_STRICT = -0.45

    /** The personal pivot floor is never stricter (ΔE ≥ this never flags). */
    const val PIVOT_FLOOR_STRICT = -0.15

    /** The personal pivot floor is never more lenient than this. */
    const val PIVOT_FLOOR_LENIENT = -0.50

    /** ΔE history window feeding the personal percentile floors. */
    const val HISTORY_WINDOW_GAMES = 15
    const val HISTORY_WINDOW_DAYS = 21
    const val MIN_HISTORY_SAMPLE = 5

    /** Cold-start floors until [MIN_HISTORY_SAMPLE] audited games exist. */
    const val COLD_TERMINATE_FLOOR = -0.45
    const val COLD_PIVOT_FLOOR = -0.20

    /** Percentiles of the user's own ΔE distribution used for the floors. */
    const val TERMINATE_PERCENTILE = 0.10
    const val PIVOT_PERCENTILE = 0.25

    // ── Strain model ───────────────────────────────────────────────────────

    /** ΔE below the personal terminate floor (but above catastrophic). */
    const val SEVERE_STRAIN = 50.0

    /** ΔE below the personal pivot floor (but ≥ terminate floor). */
    const val MODERATE_STRAIN = 25.0

    /** Accuracy drop beyond the time-control tolerance. */
    const val ACC_VIOLATION_STRAIN = 25.0

    /** Unforced-blunder threshold hit. */
    const val BLUNDER_VIOLATION_STRAIN = 25.0

    /** Session strain at/above which the session terminates (before buffer). */
    const val STRAIN_TERMINATE_BASE = 100.0

    /**
     * Readiness buffer at/above which ONE moderate-or-severe dip in an
     * otherwise clean session is forgiven entirely (CCRS ≥ 85).
     */
    const val READINESS_FORGIVE_BUFFER = 20

    /** Size of the rolling accuracy window per time control. */
    const val ROLLING_WINDOW = 10

    // ── Time control calibration ───────────────────────────────────────────

    /**
     * Time-control tiers with their calibrated thresholds:
     *  - [accTolerance]  : max tolerated CAPS2 accuracy drop below the
     *    rolling mean (percentage points) before a violation is flagged.
     *  - [maxBlunders]   : unforced blunder count at/above which the
     *    blunder violation triggers (the game must ALSO have the
     *    "unforced" flag set).
     *  - [defaultAccMean]: baseline used when no rolling history exists.
     *  - [scrambleSec]   : remaining clock below which blunders count as
     *    time-scramble errors (UI help text / user guidance).
     */
    enum class TimeControl(
        val label: String,
        val formats: String,
        val accTolerance: Double,
        val maxBlunders: Int,
        val defaultAccMean: Double,
        val scrambleSec: Int
    ) {
        BULLET("Bullet", "1+0 · 1+1 · 2+1", 20.0, 3, 70.0, 10),
        BLITZ("Blitz", "3+0 · 3+2 · 5+0 · 5+5", 15.0, 2, 75.0, 20),
        RAPID("Rapid / Classical", "10+0 · 15+10 · 30+0 · 60+0", 10.0, 1, 80.0, 45);

        companion object {
            fun fromNameOrBlitz(name: String?): TimeControl =
                entries.firstOrNull { it.name == name } ?: BLITZ
        }
    }

    // ── Input models ───────────────────────────────────────────────────────

    /** Match result mapped to the Elo score scale. */
    enum class GameResult(val score: Double, val label: String) {
        WIN(1.0, "Win"), DRAW(0.5, "Draw"), LOSS(0.0, "Loss")
    }

    /** All telemetry for a single completed rated game. */
    data class GameInput(
        val timeControl: TimeControl,
        val userRating: Int,
        val opponentRating: Int,
        val gameResult: GameResult,
        /** CAPS2 accuracy percentage for this game (0–100). */
        val caps2Accuracy: Double,
        /** Total blunders shown under Move Classification. */
        val blunderCount: Int,
        /** True when ≥ 1 blunder happened BEFORE the time scramble. */
        val hasUnforcedBlunder: Boolean,
        /** Cumulative minutes of play in the current session. */
        val sessionElapsedMins: Int,
        /** Game ended < 10 moves (early resignation) → bypass accuracy check. */
        val shortGame: Boolean = false,
        /** Rolling accuracy window for this time control (most recent last). */
        val accuracyHistory: List<Double> = emptyList()
    )

    /** A prior Phase 2 audit belonging to the current session. */
    data class SessionGame(
        val timestamp: Long,
        val timeControl: String,
        val outputState: String,
        /** ΔE recorded for that game (0.0 for pre-v2.0 audits). */
        val deltaE: Double = 0.0,
        /** Strain that game contributed to the session (0–100). */
        val strain: Double = 0.0
    )

    /** One audited game in the user's ΔE history (feeds the percentile floors). */
    data class DeltaERecord(val timestamp: Long, val deltaE: Double)

    // ── Result models ──────────────────────────────────────────────────────

    /** Operational command issued by the audit. */
    enum class OutputState(
        val colorHex: String,
        val title: String,
        val permitted: List<String>,
        val prohibited: List<String>
    ) {
        CONTINUE_RATED(
            "#22C55E", "CLEARED FOR NEXT MATCH",
            listOf("Next rated game", "Post-game review & light study"),
            emptyList()
        ),
        PIVOT_TO_DRILLS(
            "#EAB308", "RATED PLAY PROHIBITED",
            listOf("Unrated casual games", "Bot scrimmages", "Easy pattern drills (Mate-in-1/2)"),
            listOf("ALL RATED PLAY", "Deep theoretical calculation")
        ),
        TERMINATE_SESSION(
            "#EF4444", "STOP ALL PLAY & STUDY",
            listOf("Biological recovery", "Light exercise", "Outdoor breaks", "Rest"),
            listOf("ALL chess play, drilling, tactics, and study")
        )
    }

    /** What the personal ΔE floors were derived from. */
    enum class FloorBasis { PERCENTILE, COLD_START }

    /**
     * The personal adaptive ΔE bars for one evaluation: ΔE below [terminate]
     * is severe FOR YOU; below [pivot] is moderate FOR YOU.
     */
    data class DeltaFloors(
        val terminate: Double,
        val pivot: Double,
        val basis: FloorBasis,
        /** How many recent games fed the percentiles (0 for cold start). */
        val sampleSize: Int
    )

    /** Full outcome of a Phase 2 evaluation. */
    data class AuditResult(
        val timestamp: Long,
        val outputState: OutputState,
        /** Elo expected score E_A (rounded to 3 decimals). */
        val expectedScore: Double,
        /** S_A − E_A (rounded to 3 decimals). */
        val deltaE: Double,
        /** Rolling mean (or default baseline) minus this game's accuracy. */
        val accuracyDelta: Double,
        /** Baseline the accuracy was compared against. */
        val rollingMeanUsed: Double,
        /** True when [rollingMeanUsed] is the default (no history yet). */
        val usedDefaultMean: Boolean,
        val accViolation: Boolean,
        val blunderViolation: Boolean,
        val isFalseSuccess: Boolean,
        /** True when the accuracy check was bypassed (short game). */
        val accuracyIgnored: Boolean,
        /** True when TERMINATE came from the 60-minute ceiling. */
        val sessionCapReached: Boolean,
        // ── v2.0 evidence model ──
        /** Strain this game contributed (0–100). */
        val strain: Double,
        /** Total session strain INCLUDING this game. */
        val sessionStrain: Double,
        /** Strain level the session terminates at (base + readiness buffer). */
        val strainTerminateAt: Double,
        /** The personal ΔE floors this game was judged against. */
        val floors: DeltaFloors,
        /** Readiness buffer points from the pre-game CCRS (0–30). */
        val readinessBuffer: Int,
        /** True when the hard cutoff (ΔE ≤ [CATASTROPHIC_DELTA_E]) fired. */
        val catastrophic: Boolean,
        /** Short machine-readable reason (e.g. "ACCUMULATED UNDERPERFORMANCE"). */
        val reason: String,
        /** Human-facing instruction. */
        val message: String
    )

    // ── Elo math ───────────────────────────────────────────────────────────

    /**
     * Elo expected score for the user:
     * `E_A = 1 / (1 + 10^((R_B − R_A)/400))`
     */
    fun expectedScore(userRating: Int, opponentRating: Int): Double =
        1.0 / (1.0 + 10.0.pow((opponentRating - userRating) / 400.0))

    /** ΔE = S_A − E_A, rounded to 3 decimals. */
    fun deltaE(userRating: Int, opponentRating: Int, result: GameResult): Double =
        round3(result.score - expectedScore(userRating, opponentRating))

    /** Classifies ΔE against the personal floors (display string). */
    fun deltaEClassification(deltaE: Double, floors: DeltaFloors): String = when {
        deltaE <= CATASTROPHIC_DELTA_E -> "Catastrophic loss (hard cutoff)"
        deltaE < floors.terminate -> "Severe for you — bottom 10% of your games"
        deltaE < floors.pivot -> "Below your usual bar"
        else -> "Within your normal range"
    }

    // ── Rolling mean ───────────────────────────────────────────────────────

    /**
     * The rolling [ROLLING_WINDOW]-game accuracy mean for a time control,
     * falling back to the tier's default baseline when no history exists.
     */
    fun rollingMean(history: List<Double>, timeControl: TimeControl): Double =
        if (history.isEmpty()) timeControl.defaultAccMean
        else history.takeLast(ROLLING_WINDOW).average()

    // ── Personal adaptive ΔE floors ────────────────────────────────────────

    /**
     * Linear-interpolation percentile (type 7) of a list of doubles.
     */
    fun percentileOf(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val rank = p * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /**
     * Derives the personal ΔE floors from the user's recent audited games:
     * the last [HISTORY_WINDOW_GAMES] audits within [HISTORY_WINDOW_DAYS]
     * days. The terminate floor is the p10 of that ΔE distribution (only
     * games worse than your own worst decile count as severe), clamped to
     * [CATASTROPHIC_DELTA_E]…[TERMINATE_FLOOR_STRICT]; the pivot floor is
     * the p25, clamped to [PIVOT_FLOOR_LENIENT]…[PIVOT_FLOOR_STRICT].
     *
     * The effect mirrors Phase 1 v3.0: someone who often underperforms gets
     * a LOWER bar (more lenient floors), while a hot streak never tightens
     * the bars beyond the strict clamps.
     */
    fun computeDeltaFloors(history: List<DeltaERecord>, now: Long): DeltaFloors {
        val cutoff = now - HISTORY_WINDOW_DAYS * 24L * 60 * 60 * 1000
        val window = history
            .filter { it.timestamp >= cutoff }
            .sortedBy { it.timestamp }
            .takeLast(HISTORY_WINDOW_GAMES)
            .map { it.deltaE }

        if (window.size < MIN_HISTORY_SAMPLE) {
            return DeltaFloors(
                terminate = COLD_TERMINATE_FLOOR,
                pivot = COLD_PIVOT_FLOOR,
                basis = FloorBasis.COLD_START,
                sampleSize = window.size
            )
        }

        val terminate = percentileOf(window, TERMINATE_PERCENTILE)
            .coerceIn(CATASTROPHIC_DELTA_E, TERMINATE_FLOOR_STRICT)
        val pivotRaw = percentileOf(window, PIVOT_PERCENTILE)
            .coerceIn(PIVOT_FLOOR_LENIENT, PIVOT_FLOOR_STRICT)
        return DeltaFloors(
            terminate = terminate,
            pivot = maxOf(pivotRaw, terminate),
            basis = FloorBasis.PERCENTILE,
            sampleSize = window.size
        )
    }

    // ── Readiness buffer ───────────────────────────────────────────────────

    /**
     * How many extra strain points the pre-game readiness test adds to the
     * termination bar (and whether a single dip can be forgiven — see
     * [READINESS_FORGIVE_BUFFER]). A barely-green test adds nothing; a
     * strong test adds up to 30.
     */
    fun readinessBuffer(ccrs: Int?): Int = when {
        ccrs == null -> 0
        ccrs >= 95 -> 30
        ccrs >= 90 -> 25
        ccrs >= 85 -> 20
        ccrs >= 80 -> 15
        ccrs >= 75 -> 10
        ccrs >= 70 -> 5
        else -> 0
    }

    // ── Strain ─────────────────────────────────────────────────────────────

    /**
     * Strain (0–100) one game contributes to the session:
     *  - catastrophic ΔE → 100 (hard cutoff)
     *  - ΔE below the personal terminate floor → [SEVERE_STRAIN]
     *  - ΔE below the personal pivot floor → [MODERATE_STRAIN]
     *  - accuracy-drop violation → +[ACC_VIOLATION_STRAIN]
     *  - unforced-blunder violation → +[BLUNDER_VIOLATION_STRAIN]
     */
    fun strainFor(
        deltaE: Double,
        floors: DeltaFloors,
        accViolation: Boolean,
        blunderViolation: Boolean
    ): Double {
        var s = 0.0
        s += when {
            deltaE <= CATASTROPHIC_DELTA_E -> STRAIN_TERMINATE_BASE
            deltaE < floors.terminate -> SEVERE_STRAIN
            deltaE < floors.pivot -> MODERATE_STRAIN
            else -> 0.0
        }
        if (accViolation) s += ACC_VIOLATION_STRAIN
        if (blunderViolation) s += BLUNDER_VIOLATION_STRAIN
        return minOf(s, STRAIN_TERMINATE_BASE)
    }

    // ── Master evaluation ──────────────────────────────────────────────────

    /**
     * Runs the full Phase 2 audit (pure — no persistence, no clock reads).
     *
     * @param input          the game telemetry entered by the user
     * @param sessionHistory prior Phase 2 outputs in the CURRENT session
     *                       (the caller derives sessions, see
     *                       [ChessPhase2Store.currentSessionAudits])
     * @param now            epoch millis to stamp on the result
     * @param deltaEHistory  the user's recent audited ΔEs (most recent last)
     *                       — drives the personal percentile floors
     * @param readinessCcrs  CCRS of the Phase 1 test authorizing this
     *                       session (null when unknown) — drives the buffer
     */
    fun evaluate(
        input: GameInput,
        sessionHistory: List<SessionGame>,
        now: Long,
        deltaEHistory: List<DeltaERecord> = emptyList(),
        readinessCcrs: Int? = null
    ): AuditResult {
        val tc = input.timeControl

        // 1. Elo Expected Score Delta (ΔE)
        val expected = expectedScore(input.userRating, input.opponentRating)
        val deltaE = round3(input.gameResult.score - expected)

        // 2. Accuracy & error violations (calibrated per time control)
        val mean = rollingMean(input.accuracyHistory, tc)
        val accDrop = mean - input.caps2Accuracy
        val accViolation = !input.shortGame && accDrop > tc.accTolerance
        val blunderViolation =
            input.hasUnforcedBlunder && input.blunderCount >= tc.maxBlunders
        val isFalseSuccess =
            input.gameResult.score >= 0.5 && (accViolation || blunderViolation)

        // 3. Personal floors, readiness buffer, this game's strain
        val floors = computeDeltaFloors(deltaEHistory, now)
        val buffer = readinessBuffer(readinessCcrs)
        val strainTerminateAt = STRAIN_TERMINATE_BASE + buffer
        val catastrophic = deltaE <= CATASTROPHIC_DELTA_E
        val strain = strainFor(deltaE, floors, accViolation, blunderViolation)

        // 4. Session evidence
        val priorStrain = sessionHistory.sumOf { it.strain }
        val sessionStrain = priorStrain + strain
        val priorFlagged = sessionHistory.any { it.strain >= MODERATE_STRAIN }
        val sessionCapReached = input.sessionElapsedMins >= SESSION_CAP_MINUTES

        // Strong readiness absorbs ONE moderate-or-severe dip in a clean session
        val forgiven = !sessionCapReached && !catastrophic &&
            strain <= SEVERE_STRAIN &&
            buffer >= READINESS_FORGIVE_BUFFER &&
            !priorFlagged &&
            strain > 0.0

        // 5. Master decision logic
        val state: OutputState
        val reason: String
        val message: String
        when {
            sessionCapReached -> {
                state = OutputState.TERMINATE_SESSION
                reason = "60-MINUTE CAPACITY CEILING REACHED"
                message = "Continuous high-load calculation leads to prefrontal " +
                    "fatigue. End session immediately for recovery."
            }
            catastrophic -> {
                state = OutputState.TERMINATE_SESSION
                reason = "CATASTROPHIC LOSS (HARD CUTOFF)"
                message = "ΔE ${"%+.3f".format(deltaE)} — a result this far beyond " +
                    "expectation ends the session no matter your history or " +
                    "readiness. Stop and recover."
            }
            strain >= STRAIN_TERMINATE_BASE -> {
                state = OutputState.TERMINATE_SESSION
                reason = "SEVERE COLLAPSE ACROSS ALL METRICS"
                message = "Result, accuracy AND blunder count are all bad in the " +
                    "same game (strain ${strain.roundToInt()}/100). Session " +
                    "over — recover now."
            }
            sessionStrain >= strainTerminateAt -> {
                state = OutputState.TERMINATE_SESSION
                reason = "ACCUMULATED UNDERPERFORMANCE"
                message = "Session strain ${sessionStrain.roundToInt()}/" +
                    "${strainTerminateAt.roundToInt()} — several games below " +
                    "your personal bar (this one added ${strain.roundToInt()}). " +
                    "The evidence has piled up: stop for today."
            }
            forgiven -> {
                state = OutputState.CONTINUE_RATED
                reason = "READINESS BUFFER ABSORBED"
                message = "Your pre-game readiness (CCRS $readinessCcrs) was " +
                    "strong enough to absorb this dip (strain " +
                    "${strain.roundToInt()}). Cleared to continue — but stay " +
                    "honest about compounding fatigue."
            }
            strain >= SEVERE_STRAIN -> {
                state = OutputState.PIVOT_TO_DRILLS
                reason = if (isFalseSuccess) "FALSE_SUCCESS"
                else "SEVERE UNDERPERFORMANCE VS YOUR BAR"
                message = if (isFalseSuccess) {
                    "FALSE WIN: the result was fine but move quality collapsed. " +
                        "Rated play pauses — pivot to easy drills."
                } else {
                    "ΔE ${"%+.3f".format(deltaE)} is below your personal bar " +
                        "(${floors.terminate}). One bad game does NOT end the " +
                        "session — rated play pauses here. Drills, bots, or a " +
                        "fresh readiness test later."
                }
            }
            strain >= MODERATE_STRAIN -> {
                state = OutputState.PIVOT_TO_DRILLS
                reason = if (isFalseSuccess) "FALSE_SUCCESS"
                else "MODERATE UNDERPERFORMANCE VS YOUR BAR"
                message = if (isFalseSuccess) {
                    "FALSE WIN: result fine, move quality wasn't. Rated play " +
                        "pauses — pivot to easy drills."
                } else {
                    val flags = listOfNotNull(
                        if (accViolation) "accuracy drop" else null,
                        if (blunderViolation) "unforced blunders" else null
                    ).joinToString(", ")
                    "Below your usual bar (personal pivot ${floors.pivot})" +
                        (if (flags.isEmpty()) "" else " — $flags") +
                        ". Rated play pauses: unrated, bots, or re-test later."
                }
            }
            else -> {
                state = OutputState.CONTINUE_RATED
                reason = "OPTIMAL PERFORMANCE"
                message = "Within your normal range. Cleared for your next " +
                    "rated match."
            }
        }

        return AuditResult(
            timestamp = now,
            outputState = state,
            expectedScore = round3(expected),
            deltaE = deltaE,
            accuracyDelta = round1(accDrop),
            rollingMeanUsed = round1(mean),
            usedDefaultMean = input.accuracyHistory.isEmpty(),
            accViolation = accViolation,
            blunderViolation = blunderViolation,
            isFalseSuccess = isFalseSuccess,
            accuracyIgnored = input.shortGame,
            sessionCapReached = sessionCapReached,
            strain = strain,
            sessionStrain = round1(sessionStrain),
            strainTerminateAt = strainTerminateAt,
            floors = floors,
            readinessBuffer = buffer,
            catastrophic = catastrophic,
            reason = reason,
            message = message
        )
    }

    // ── Rounding helpers ───────────────────────────────────────────────────

    private fun round3(v: Double): Double = (v * 1000.0).roundToLong() / 1000.0
    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0
}
