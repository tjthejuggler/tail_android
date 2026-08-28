package com.example.tail.widget

import kotlin.math.roundToLong

/**
 * Phase 2 v3 — the HYBRID post-game audit.
 *
 * v2's rule skeleton (fatigue, loss-streak, tilt vector, ACWR, hysteresis)
 * with v1's best ideas grafted in, plus REAL engine data:
 *
 *  - RULE 1 — fatigue ceiling, now READINESS-SCALED: a strong pre-game
 *    CCRS buys +0/15/30 minutes on both the yellow and red bars.
 *  - RULE 2 — ΔE-WEIGHTED loss streak: losing to a weaker opponent counts
 *    1.5, an even matchup 1.0, a much stronger opponent only 0.5. Yellow at
 *    ≥ 2.0, red at ≥ 3.0 — a single loss still NEVER flags.
 *  - RULE 3 — tilt vector (v2 unchanged): personal speed + accuracy
 *    Z-scores with the 20:00–04:00 circadian relaxation.
 *  - RULE 4 — ACWR chronic overload (v2 unchanged).
 *  - RULE 5 — STRAIN ACCUMULATOR (v1's crown jewel, as a parallel rule):
 *    per-game strain from ΔE vs personal percentile floors + accuracy-drop
 *    and unforced-blunder violations, a readiness buffer raising the
 *    termination bar, one-dip forgiveness, and the catastrophic hard cutoff.
 *    The blunder violation finally has REAL inputs — unforced blunders from
 *    the desktop Stockfish analysis via the tail bridge. When the phone is
 *    away from the PC (or the service is down) the analysis is null and the
 *    blunder term simply doesn't gate — every other rule still works.
 *  - RULE 6 — hysteresis (v2 unchanged): Yellow holds until recovery is
 *    proven, even when the fresh rules say Green.
 *
 * Selected by `chessPhase2Version = "v3"` (Settings → ♟ Chess Readiness →
 * Post-Game Audit Version). Pure object — no persistence, no clock reads.
 */
object ChessPhase2V3Engine {

    // ── Constants ──────────────────────────────────────────────────────────

    // Rule 1 — readiness-scaled fatigue (v2 base limits + CCRS boost)
    const val SESSION_YELLOW_MINUTES = 90
    const val SESSION_RED_MINUTES = 120

    // Rule 2 — ΔE-weighted streak
    const val STREAK_YELLOW_WEIGHT = 2.0
    const val STREAK_RED_WEIGHT = 3.0

    /** Loss as the FAVORITE (E ≥ 0.5) — an upset against you, strong tilt evidence. */
    const val WEIGHT_FAVORED_LOSS = 1.5

    /** Loss in the normal band (0.35 ≤ E < 0.5). */
    const val WEIGHT_NORMAL_LOSS = 1.0

    /** Loss to a much stronger opponent (E < 0.35) — expected, weak evidence. */
    const val WEIGHT_UNDERDOG_LOSS = 0.5

    /** Expected-score bands separating the loss weights. */
    const val FAVORED_BAR = 0.5
    const val UNDERDOG_BAR = 0.35

    /** Expected score assumed for ledger losses recorded before v3 (unknown). */
    const val UNKNOWN_EXPECTED_DEFAULT = 0.5

    // Rule 5 reuses v1's strain constants via [ChessPhase2Engine]:
    //  SEVERE_STRAIN 50 / MODERATE_STRAIN 25 / ACC +25 / BLUNDER +25 /
    //  STRAIN_TERMINATE_BASE 100 / CATASTROPHIC_DELTA_E −0.75 /
    //  readinessBuffer(ccrs) / computeDeltaFloors(...) / strainFor(...)

    // ── Input models ───────────────────────────────────────────────────────

    /** Telemetry for one completed rated game, as needed by the v3 rules. */
    data class GameInputV3(
        val timeControl: ChessPhase2Engine.TimeControl,
        val result: ChessPhase2Engine.GameResult,
        /** CAPS2 accuracy 0–100, or null when no Game Review exists. */
        val accuracy: Double?,
        /** Average seconds per move from the PGN clocks, or null when absent. */
        val avgMoveSec: Double?,
        /** Cumulative session minutes INCLUDING this game. */
        val sessionElapsedMins: Int,
        /** Local hour (0–23) at game end — drives the circadian adjustment. */
        val localHour: Int,
        /** Game ended < 10 moves — accuracy is noise. */
        val shortGame: Boolean,
        // ── v3 additions ──
        /** Elo expected score of this game (0–1); drives the streak weight. */
        val expectedScore: Double,
        /** S_A − E_A; drives the strain rule's ΔE terms. */
        val deltaE: Double,
        /**
         * Unforced blunders from desktop Stockfish analysis, or NULL when no
         * analysis was available (away from PC / service down) — the blunder
         * violation only gates on a KNOWN count.
         */
        val unforcedBlunders: Int?,
        /** Total blunders from the analysis (telemetry; null = unknown). */
        val blunderCount: Int?,
        /** Mistakes from the analysis (telemetry; null = unknown). */
        val mistakeCount: Int?,
        /** Inaccuracies from the analysis (telemetry; null = unknown). */
        val inaccuracyCount: Int?,
        /** Average centipawn loss from the analysis (null = unknown). */
        val analysisAcpl: Double?,
        /** Moves the user played per the analysis (null = unknown). */
        val analysisMoves: Int?,
        /** Rolling accuracy history for this time control (most recent last). */
        val accuracyHistory: List<Double>,
        /** CCRS of the readiness test authorizing this session (null unknown). */
        val readinessCcrs: Int?
    )

    /** A prior rated game of the current session, for streaks + strain + hysteresis. */
    data class SessionGameV3(
        val timestamp: Long,
        val result: ChessPhase2Engine.GameResult,
        /** [ChessPhase2Engine.OutputState] name of that game's audit. */
        val outputState: String,
        /** That game's Elo expected score (null for pre-v3 ledger rows). */
        val expectedScore: Double?,
        /** Strain that game contributed to the session (0–100). */
        val strain: Double
    )

    /** Full outcome of a v3 Phase 2 evaluation. */
    data class AuditResultV3(
        val timestamp: Long,
        /** Same enum as v1/v2 — the whole enforcement stack consumes this name. */
        val outputState: ChessPhase2Engine.OutputState,
        val redRules: List<String>,
        val yellowRules: List<String>,
        // Rule 1 telemetry
        val sessionMinutes: Int,
        /** Fatigue yellow/red bars AFTER the readiness boost (minutes). */
        val fatigueYellowAt: Int,
        val fatigueRedAt: Int,
        // Rule 2 telemetry
        /** ΔE-weighted consecutive-loss total including this game. */
        val weightedStreak: Double,
        // Rule 3 telemetry
        val zMoveTime: Double?,
        val zDeficit: Double?,
        val moveBaseline: ChessPhase2V2Engine.Baseline?,
        val accBaseline: ChessPhase2V2Engine.Baseline?,
        val circadianAdjusted: Boolean,
        // Rule 4 telemetry
        val acwr: Double?,
        val acwrGated: Boolean,
        // Rule 5 telemetry
        /** Strain this game contributed (0–100). */
        val strain: Double,
        /** Total session strain INCLUDING this game. */
        val sessionStrain: Double,
        /** Strain level the session terminates at (base + readiness buffer). */
        val strainTerminateAt: Double,
        val floors: ChessPhase2Engine.DeltaFloors,
        val readinessBuffer: Int,
        val catastrophic: Boolean,
        /** True when a strong readiness test absorbed a single moderate dip. */
        val strainForgiven: Boolean,
        val accViolation: Boolean,
        val blunderViolation: Boolean,
        /** True when real Stockfish analysis backed this audit. */
        val engineBacked: Boolean,
        // Rule 6 telemetry
        val hysteresisHeld: Boolean,
        val reason: String,
        val message: String
    )

    // ── Rule 2 helpers ─────────────────────────────────────────────────────

    /** How much one loss weighs, given its pre-game expected score. */
    fun lossWeight(expectedScore: Double): Double = when {
        expectedScore > FAVORED_BAR -> WEIGHT_FAVORED_LOSS
        expectedScore < UNDERDOG_BAR -> WEIGHT_UNDERDOG_LOSS
        else -> WEIGHT_NORMAL_LOSS
    }

    /**
     * ΔE-weighted consecutive-loss total: this game plus the unbroken chain
     * of losses ending at it. A win or draw anywhere breaks the chain.
     */
    fun weightedLossStreak(
        result: ChessPhase2Engine.GameResult,
        expectedScore: Double,
        sessionGames: List<SessionGameV3>
    ): Double {
        if (result != ChessPhase2Engine.GameResult.LOSS) return 0.0
        var weight = lossWeight(expectedScore)
        for (g in sessionGames.asReversed()) {
            if (g.result != ChessPhase2Engine.GameResult.LOSS) break
            weight += lossWeight(g.expectedScore ?: UNKNOWN_EXPECTED_DEFAULT)
        }
        return weight
    }

    // ── Rule 1 helpers ─────────────────────────────────────────────────────

    /** Extra fatigue minutes a strong pre-game CCRS buys (0/15/30). */
    fun fatigueBoostMinutes(ccrs: Int?): Int = when {
        ccrs == null -> 0
        ccrs >= 85 -> 30
        ccrs >= 75 -> 15
        else -> 0
    }

    // ── Rule 5 helpers ─────────────────────────────────────────────────────

    /**
     * Rolling accuracy mean for [tc]: the average of the last
     * [ChessPhase2Engine.ROLLING_WINDOW] known values, falling back to the
     * time control's calibrated default when no history exists (v1's
     * provisional rule — a cold start still has a bar to clear).
     */
    fun rollingAccuracyMean(
        history: List<Double>,
        tc: ChessPhase2Engine.TimeControl
    ): Double =
        if (history.isEmpty()) tc.defaultAccMean
        else history.takeLast(ChessPhase2Engine.ROLLING_WINDOW).average()

    // ── Master evaluation ──────────────────────────────────────────────────

    /**
     * Runs the full v3 audit. PURE — no persistence, no clock reads.
     *
     * @param input         telemetry of the just-finished game
     * @param sessionGames  prior games of the CURRENT session (most recent
     *                      last; excludes the game being audited)
     * @param accBaseline   personal accuracy baseline for this time control
     * @param moveBaseline  personal avg-seconds-per-move baseline (same TC)
     * @param acwr          rolling-average ACWR inputs (null = no game log)
     * @param deltaEHistory the user's recent audited ΔEs (most recent last)
     *                      — drives the personal percentile floors
     * @param now           epoch millis stamped on the result (game end)
     */
    fun evaluate(
        input: GameInputV3,
        sessionGames: List<SessionGameV3>,
        accBaseline: ChessPhase2V2Engine.Baseline?,
        moveBaseline: ChessPhase2V2Engine.Baseline?,
        acwr: ChessPhase2V2Engine.AcwrInput?,
        deltaEHistory: List<ChessPhase2Engine.DeltaERecord>,
        now: Long
    ): AuditResultV3 {
        val tc = input.timeControl

        // ── Rule 3 telemetry: personal Z-scores with circadian relaxation
        val circadian = ChessPhase2V2Engine.isCircadianWindow(input.localHour)
        val effMoveBaseline = moveBaseline?.let {
            if (circadian) it.copy(
                mean = it.mean / ChessPhase2V2Engine.CIRCADIAN_SPEED_RELAXATION
            ) else it
        }
        val zMove = if (input.avgMoveSec != null && effMoveBaseline?.ready == true)
            round2(ChessPhase2V2Engine.zScore(input.avgMoveSec, effMoveBaseline)) else null
        val zDeficit = if (input.accuracy != null && !input.shortGame && accBaseline?.ready == true) {
            round2((accBaseline.mean - input.accuracy) / accBaseline.sd)
        } else null

        // ── Rule 2 telemetry: ΔE-weighted consecutive losses
        val weightedStreak = round2(
            weightedLossStreak(input.result, input.expectedScore, sessionGames)
        )

        // ── Rule 4 telemetry
        val acwrRatio = acwr?.takeIf { it.ready }?.ratio

        // ── Rule 5 telemetry: strain accumulator (v1 math, real blunders)
        val floors = ChessPhase2Engine.computeDeltaFloors(deltaEHistory, now)
        val buffer = ChessPhase2Engine.readinessBuffer(input.readinessCcrs)
        val strainTerminateAt =
            ChessPhase2Engine.STRAIN_TERMINATE_BASE + buffer
        val catastrophic = input.deltaE <= ChessPhase2Engine.CATASTROPHIC_DELTA_E
        val accMean = rollingAccuracyMean(input.accuracyHistory, tc)
        val accViolation = input.accuracy != null && !input.shortGame &&
            (accMean - input.accuracy) > tc.accTolerance
        val blunderViolation =
            input.unforcedBlunders != null && input.unforcedBlunders >= tc.maxBlunders
        val strain = ChessPhase2Engine.strainFor(
            input.deltaE, floors, accViolation, blunderViolation
        )
        val priorStrain = sessionGames.sumOf { it.strain }
        val sessionStrain = priorStrain + strain
        val priorFlagged = sessionGames.any {
            it.strain >= ChessPhase2Engine.MODERATE_STRAIN
        }
        // Strong readiness absorbs ONE moderate-or-severe dip in a clean
        // session (v1's forgiveness) — but never a red-level collapse.
        val strainForgiven = !catastrophic &&
            strain in 1.0..ChessPhase2Engine.SEVERE_STRAIN &&
            buffer >= ChessPhase2Engine.READINESS_FORGIVE_BUFFER &&
            !priorFlagged

        // ── Rule 1 telemetry: readiness-scaled fatigue bars
        val boost = fatigueBoostMinutes(input.readinessCcrs)
        val fatigueYellowAt = SESSION_YELLOW_MINUTES + boost
        val fatigueRedAt = SESSION_RED_MINUTES + boost

        // ── Rule evaluation: any Red wins, then any Yellow, else Green
        val redRules = ArrayList<String>()
        val yellowRules = ArrayList<String>()

        // Rule 1 — readiness-scaled fatigue ceiling
        if (input.sessionElapsedMins > fatigueRedAt) redRules += "RULE_1_TIME_ON_TASK"
        else if (input.sessionElapsedMins > fatigueYellowAt) yellowRules += "RULE_1_TIME_ON_TASK"

        // Rule 2 — ΔE-weighted loss-chasing (a single loss NEVER flags)
        if (input.result == ChessPhase2Engine.GameResult.LOSS) {
            if (weightedStreak >= STREAK_RED_WEIGHT) redRules += "RULE_2_WEIGHTED_STREAK"
            else if (weightedStreak >= STREAK_YELLOW_WEIGHT) yellowRules += "RULE_2_WEIGHTED_STREAK"
        }

        // Rule 3 — tilt vector (fast AND poor simultaneously)
        val tiltRed = zMove != null && zDeficit != null &&
            zMove <= ChessPhase2V2Engine.Z_MOVE_RED &&
            zDeficit >= ChessPhase2V2Engine.Z_DEFICIT_RED &&
            input.result == ChessPhase2Engine.GameResult.LOSS
        val tiltYellow = zMove != null && zDeficit != null &&
            zMove <= ChessPhase2V2Engine.Z_MOVE_YELLOW &&
            zDeficit >= ChessPhase2V2Engine.Z_DEFICIT_YELLOW
        if (tiltRed) redRules += "RULE_3_TILT_VECTOR"
        else if (tiltYellow) yellowRules += "RULE_3_TILT_VECTOR"

        // Rule 4 — chronic overload
        if (acwrRatio != null) {
            if (acwrRatio >= ChessPhase2V2Engine.ACWR_RED) redRules += "RULE_4_CHRONIC_OVERLOAD"
            else if (acwrRatio >= ChessPhase2V2Engine.ACWR_YELLOW) yellowRules += "RULE_4_CHRONIC_OVERLOAD"
        }

        // Rule 5 — strain accumulator (catastrophic is always red)
        if (catastrophic || strain >= ChessPhase2Engine.STRAIN_TERMINATE_BASE) {
            redRules += "RULE_5_STRAIN"
        } else if (sessionStrain >= strainTerminateAt) {
            redRules += "RULE_5_STRAIN"
        } else if (!strainForgiven && strain >= ChessPhase2Engine.MODERATE_STRAIN) {
            yellowRules += "RULE_5_STRAIN"
        }

        // ── Rule 6 — hysteresis: Yellow persists until recovery is PROVEN
        val lastYellow = sessionGames.lastOrNull {
            it.outputState == ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
        }
        val minutesSinceYellow = lastYellow
            ?.let { (now - it.timestamp) / 60000.0 } ?: Double.MAX_VALUE
        // An EXPECTED loss (opponent heavily favoured, expected score below
        // UNDERDOG_BAR — e.g. a +700 rating gap) is weak evidence of poor
        // play, exactly like Rule 2's 0.5 streak weight. It therefore counts
        // as NEUTRAL for hysteresis recovery (like a draw) instead of
        // resetting it — losing to a much stronger player must not lock
        // rated play.
        val expectedLoss = input.result == ChessPhase2Engine.GameResult.LOSS &&
            input.expectedScore < UNDERDOG_BAR
        val resultNeutralForRecovery =
            input.result != ChessPhase2Engine.GameResult.LOSS || expectedLoss
        val recoveredFromYellow = lastYellow == null ||
            (resultNeutralForRecovery &&
                (zDeficit == null || zDeficit <= 0.0) &&
                minutesSinceYellow >= ChessPhase2V2Engine.HYSTERESIS_MIN_MINUTES)

        val hysteresisHeld: Boolean
        val state: ChessPhase2Engine.OutputState
        when {
            redRules.isNotEmpty() -> {
                hysteresisHeld = false
                state = ChessPhase2Engine.OutputState.TERMINATE_SESSION
            }
            yellowRules.isNotEmpty() -> {
                hysteresisHeld = false
                state = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS
            }
            !recoveredFromYellow -> {
                hysteresisHeld = true
                state = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS
            }
            else -> {
                hysteresisHeld = false
                state = ChessPhase2Engine.OutputState.CONTINUE_RATED
            }
        }

        val reason = when {
            redRules.isNotEmpty() -> redRules.joinToString(" + ")
            hysteresisHeld -> "RULE_6_HYSTERESIS"
            yellowRules.isNotEmpty() -> yellowRules.joinToString(" + ")
            else -> "NO_RISK_SIGNALS"
        }
        val message = buildMessage(
            state, redRules, yellowRules, hysteresisHeld, input,
            weightedStreak, zMove, zDeficit, acwrRatio,
            strain, sessionStrain, strainTerminateAt, strainForgiven,
            blunderViolation
        )

        return AuditResultV3(
            timestamp = now,
            outputState = state,
            redRules = redRules,
            yellowRules = yellowRules,
            sessionMinutes = input.sessionElapsedMins,
            fatigueYellowAt = fatigueYellowAt,
            fatigueRedAt = fatigueRedAt,
            weightedStreak = weightedStreak,
            zMoveTime = zMove,
            zDeficit = zDeficit,
            moveBaseline = effMoveBaseline,
            accBaseline = accBaseline,
            circadianAdjusted = circadian && moveBaseline != null,
            acwr = acwrRatio?.let { round2(it) },
            acwrGated = acwrRatio != null,
            strain = strain,
            sessionStrain = round1(sessionStrain),
            strainTerminateAt = strainTerminateAt,
            floors = floors,
            readinessBuffer = buffer,
            catastrophic = catastrophic,
            strainForgiven = strainForgiven,
            accViolation = accViolation,
            blunderViolation = blunderViolation,
            engineBacked = input.unforcedBlunders != null,
            hysteresisHeld = hysteresisHeld,
            reason = reason,
            message = message
        )
    }

    // ── Intervention copy ──────────────────────────────────────────────────

    private fun buildMessage(
        state: ChessPhase2Engine.OutputState,
        redRules: List<String>,
        yellowRules: List<String>,
        hysteresis: Boolean,
        input: GameInputV3,
        weightedStreak: Double,
        zMove: Double?,
        zDeficit: Double?,
        acwr: Double?,
        strain: Double,
        sessionStrain: Double,
        strainTerminateAt: Double,
        forgiven: Boolean,
        blunderViolation: Boolean
    ): String {
        // Lead with the concrete Stockfish numbers that fed the verdict —
        // their presence also proves the audit was engine-backed.
        val analysisLine = if (input.unforcedBlunders != null) {
            val parts = listOfNotNull(
                "unforced blunders ${input.unforcedBlunders} " +
                    "(max ${input.timeControl.maxBlunders} for " +
                    "${input.timeControl.name.lowercase()})",
                input.blunderCount?.let { "blunders $it" },
                input.mistakeCount?.let { "mistakes $it" },
                input.inaccuracyCount?.let { "inaccuracies $it" },
                input.analysisAcpl?.let { "ACPL ${it.roundToInt()}" },
                input.analysisMoves?.let { "moves $it" }
            )
            "♟ Stockfish: ${parts.joinToString(" · ")}.\n\n"
        } else {
            "⚠ No engine data (bridge unreachable) — blunder rule " +
                "inactive; verdict from play-history rules only.\n\n"
        }
        return analysisLine + when (state) {
        ChessPhase2Engine.OutputState.TERMINATE_SESSION -> {
            val why = redRules.joinToString(" · ") {
                ruleLabel(it, weightedStreak, zMove, zDeficit, acwr, input,
                    strain, sessionStrain, strainTerminateAt, blunderViolation)
            }
            "STOP — $why.\n\nRecovery needs at least 60 minutes away from " +
                "the board — no more rated play today. Move, hydrate, rest."
        }
        ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS -> {
            if (hysteresis) {
                "RATED PLAY PAUSED — Rule 6 hysteresis: an earlier game in " +
                    "this session flagged YELLOW, and one game is not enough " +
                    "proof of recovery. Green (rated play) returns when ALL " +
                    "of these hold: (1) 15+ minutes since the yellow flag, " +
                    "(2) a win or draw — or a loss to a much stronger " +
                    "opponent (expected score below 0.35), which counts as " +
                    "neutral — and (3) accuracy at/above your norm. Until " +
                    "then: unrated games, bots, or drills only."
            } else if (forgiven) {
                "Your pre-game readiness (CCRS ${input.readinessCcrs}) was " +
                    "strong enough to absorb this dip (strain " +
                    "${strain.roundToInt()}). Cleared to continue — but stay " +
                    "honest about compounding fatigue."
            } else {
                val why = yellowRules.joinToString(" · ") {
                    ruleLabel(it, weightedStreak, zMove, zDeficit, acwr, input,
                        strain, sessionStrain, strainTerminateAt, blunderViolation)
                }
                "CAUTION — $why.\n\nTake a 3–5 minute breather before " +
                    "anything else. Rated play pauses here: switch to " +
                    "unrated games, bots, or a few slow tactical puzzles " +
                    "to reset."
            }
        }
        ChessPhase2Engine.OutputState.CONTINUE_RATED ->
            "No fatigue, tilt, loss-chasing, underperformance or overload " +
                "signals. Cleared for your next rated game."
        }
    }

    private fun ruleLabel(
        rule: String,
        weightedStreak: Double,
        zMove: Double?,
        zDeficit: Double?,
        acwr: Double?,
        input: GameInputV3,
        strain: Double,
        sessionStrain: Double,
        strainTerminateAt: Double,
        blunderViolation: Boolean
    ): String = when (rule) {
        "RULE_1_TIME_ON_TASK" ->
            "${input.sessionElapsedMins} min of continuous play " +
                "(fatigue ceiling scales with readiness)"
        "RULE_2_WEIGHTED_STREAK" ->
            "loss streak weighted %.1f (expected losses count more; ".format(weightedStreak) +
                "upsets count less)"
        "RULE_3_TILT_VECTOR" ->
            "tilt vector: speed Z ${"%+.2f".format(zMove ?: 0.0)}, " +
                "accuracy Z ${"%+.2f".format(zDeficit ?: 0.0)}"
        "RULE_4_CHRONIC_OVERLOAD" ->
            "workload ratio ${if (acwr != null && acwr.isInfinite()) "∞" else "%.2f".format(acwr ?: 0.0)} " +
                "(7-day games vs 28-day norm)"
        "RULE_5_STRAIN" ->
            if (input.deltaE <= ChessPhase2Engine.CATASTROPHIC_DELTA_E)
                "catastrophic loss (ΔE ${"%+.3f".format(input.deltaE)}) — hard cutoff"
            else "strain ${strain.roundToInt()}/100 this game, session " +
                "${sessionStrain.roundToInt()}/${strainTerminateAt.roundToInt()}" +
                if (blunderViolation) " — ${input.unforcedBlunders} unforced blunders" else ""
        else -> rule
    }

    // ── Rounding helpers ───────────────────────────────────────────────────

    private fun round2(v: Double): Double =
        if (v.isFinite()) (v * 100.0).roundToLong() / 100.0 else v

    private fun round1(v: Double): Double =
        if (v.isFinite()) (v * 10.0).roundToLong() / 10.0 else v

    private fun Double.roundToInt(): Int = this.roundToLong().toInt()
}
