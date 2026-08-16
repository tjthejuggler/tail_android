package com.example.tail.widget

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Phase 2 Post-Game Performance Audit Engine (spec v1.0)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Executes a rapid post-game telemetry audit following every rated match
 * played during an active session. Because raw win/loss outcomes fail to
 * account for opponent strength or cognitive load across time controls,
 * Phase 2 evaluates match quality using the Elo-Adjusted Expected Score
 * Delta (ΔE) combined with Time-Control Calibrated Accuracy and Error
 * Thresholds.
 *
 * Outputs one of three operational commands:
 *  - [OutputState.CONTINUE_RATED]  (Green)  — cleared for the next rated game
 *  - [OutputState.PIVOT_TO_DRILLS] (Yellow) — rated play suspended; pivot to
 *    unrated matches, bot scrimmages, or low-stakes drills
 *  - [OutputState.TERMINATE_SESSION] (Red)  — all play and study halted;
 *    proceed to biological recovery
 *
 * Decision precedence (per spec §5 / §6):
 *  1. Session cap: cumulative play time ≥ 60 min → TERMINATE
 *  2. Severe failure: ΔE < −0.35 OR any prior PIVOT_TO_DRILLS audit in the
 *     current session → TERMINATE. (After a Yellow, rated play was already
 *     prohibited — any further rated audit in the same session is treated
 *     as a repeated executive failure, matching the reference pseudocode's
 *     `yellow_count_in_session >= 1` rule.)
 *  3. Moderate failure: accuracy drop exceeded, unforced blunder threshold
 *     hit, FALSE_SUCCESS, or −0.35 ≤ ΔE < −0.15 → PIVOT_TO_DRILLS
 *  4. Otherwise → CONTINUE_RATED
 *
 * Edge cases (spec §7):
 *  - Short games / early resignation (< 10 moves): pass [GameInput.shortGame]
 *    = true — ΔE is still calculated but accuracy-drop violations are
 *    bypassed (the caller should also exclude such a game's accuracy from
 *    the rolling mean, see [ChessPhase2Store.appendAccuracy]).
 *  - Time-scramble blunders are excluded by the USER leaving the
 *    "unforced blunder" checkbox unchecked (UI guidance, not engine logic).
 *  - No rolling mean available: default baselines apply per time control
 *    (Bullet 70 %, Blitz 75 %, Rapid 80 %).
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

    /** ΔE below this is Severe Executive Underperformance. */
    const val SEVERE_DELTA_E = -0.35

    /** ΔE below this (but ≥ [SEVERE_DELTA_E]) is Moderate Underperformance. */
    const val MODERATE_DELTA_E = -0.15

    /** Size of the rolling accuracy window per time control. */
    const val ROLLING_WINDOW = 10

    // ── Time control calibration ───────────────────────────────────────────

    /**
     * Time-control tiers with their calibrated thresholds (spec §2):
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

    /** All telemetry for a single completed rated game (spec §3 fields). */
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
        val outputState: String
    )

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
        /** Short machine-readable reason (e.g. "60-MINUTE CAPACITY CEILING REACHED"). */
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

    /** Classifies ΔE per spec §4 Step 1. */
    fun deltaEClassification(deltaE: Double): String = when {
        deltaE >= MODERATE_DELTA_E -> "Expected / Superior Performance"
        deltaE >= SEVERE_DELTA_E -> "Moderate Underperformance"
        else -> "Severe Executive Underperformance"
    }

    // ── Rolling mean ───────────────────────────────────────────────────────

    /**
     * The rolling [ROLLING_WINDOW]-game accuracy mean for a time control,
     * falling back to the tier's default baseline when no history exists.
     */
    fun rollingMean(history: List<Double>, timeControl: TimeControl): Double =
        if (history.isEmpty()) timeControl.defaultAccMean
        else history.takeLast(ROLLING_WINDOW).average()

    // ── Master evaluation ──────────────────────────────────────────────────

    /**
     * Runs the full Phase 2 audit (pure — no persistence, no clock reads).
     *
     * @param input          the game telemetry entered by the user
     * @param sessionHistory prior Phase 2 outputs in the CURRENT session
     *                       (the caller derives sessions, see
     *                       [ChessPhase2Store.currentSessionAudits])
     * @param now            epoch millis to stamp on the result
     */
    fun evaluate(input: GameInput, sessionHistory: List<SessionGame>, now: Long): AuditResult {
        val tc = input.timeControl

        // 1. 60-Minute Prefrontal Capacity Ceiling
        if (input.sessionElapsedMins >= SESSION_CAP_MINUTES) {
            return AuditResult(
                timestamp = now,
                outputState = OutputState.TERMINATE_SESSION,
                expectedScore = round3(expectedScore(input.userRating, input.opponentRating)),
                deltaE = deltaE(input.userRating, input.opponentRating, input.gameResult),
                accuracyDelta = 0.0,
                rollingMeanUsed = rollingMean(input.accuracyHistory, tc),
                usedDefaultMean = input.accuracyHistory.isEmpty(),
                accViolation = false,
                blunderViolation = false,
                isFalseSuccess = false,
                accuracyIgnored = input.shortGame,
                sessionCapReached = true,
                reason = "60-MINUTE CAPACITY CEILING REACHED",
                message = "Continuous high-load calculation leads to prefrontal fatigue. " +
                    "End session immediately for recovery."
            )
        }

        // 2. Elo Expected Score Delta (ΔE)
        val expected = expectedScore(input.userRating, input.opponentRating)
        val deltaE = round3(input.gameResult.score - expected)

        // 3. Accuracy & error violations (calibrated per time control)
        val mean = rollingMean(input.accuracyHistory, tc)
        val accDrop = mean - input.caps2Accuracy
        val accViolation = !input.shortGame && accDrop > tc.accTolerance
        val blunderViolation =
            input.hasUnforcedBlunder && input.blunderCount >= tc.maxBlunders

        // 4. False Success (win/draw despite cognitive collapse)
        val isFalseSuccess =
            input.gameResult.score >= 0.5 && (accViolation || blunderViolation)

        // 5. Session history — any prior Yellow in this session escalates
        val yellowCount = sessionHistory.count {
            it.outputState == OutputState.PIVOT_TO_DRILLS.name
        }

        // 6. Master decision logic
        val state: OutputState
        val reason: String
        val message: String
        when {
            deltaE < SEVERE_DELTA_E || yellowCount >= 1 -> {
                state = OutputState.TERMINATE_SESSION
                reason = if (deltaE < SEVERE_DELTA_E)
                    "SEVERE EXECUTIVE UNDERPERFORMANCE" else "REPEATED EXECUTIVE FAILURES"
                message = "Severe performance drop or repeated executive failures detected. " +
                    "Terminate session now."
            }
            accViolation || blunderViolation || isFalseSuccess ||
                (deltaE >= SEVERE_DELTA_E && deltaE < MODERATE_DELTA_E) -> {
                state = OutputState.PIVOT_TO_DRILLS
                reason = if (isFalseSuccess) "FALSE_SUCCESS" else "EXECUTIVE CALCULATION DROP"
                message = if (isFalseSuccess) {
                    "FALSE WIN DETECTED: You won, but move quality/blunders indicated " +
                        "cognitive fatigue. RATED PLAY PROHIBITED. Pivot to easy drills."
                } else {
                    "Executive calculation drop detected for ${tc.label}. RATED PLAY " +
                        "PROHIBITED. Pivot to unrated bots or easy drills."
                }
            }
            else -> {
                state = OutputState.CONTINUE_RATED
                reason = "OPTIMAL PERFORMANCE"
                message = "Move quality and Elo performance intact. Cleared for your " +
                    "next rated match."
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
            sessionCapReached = false,
            reason = reason,
            message = message
        )
    }

    // ── Rounding helpers ───────────────────────────────────────────────────

    private fun round3(v: Double): Double = (v * 1000.0).roundToLong() / 1000.0
    private fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0
}
