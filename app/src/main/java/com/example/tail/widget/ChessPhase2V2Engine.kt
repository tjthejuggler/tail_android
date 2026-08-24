package com.example.tail.widget

import com.example.tail.data.ChessComGameDetail
import kotlin.math.roundToLong

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Phase 2 Post-Game Audit Engine — v2 (research-report spec)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Second generation of the post-game (in-session) audit, built from the
 * 2026-08-24 research synthesis ("Post-Game Continuation Decisions in Rated
 * Chess Play"). v1 (see [ChessPhase2Engine]) is UNCHANGED and keeps running
 * when the Phase 2 version setting says "v1"; this engine is selected by
 * setting `chessPhase2Version = "v2"` (Settings → ♟ Chess Readiness →
 * Post-Game Audit Version). It works with BOTH pre-game readiness engines
 * (v1 diagnostic and v2 neurobiological gate) — it only consumes the game
 * itself plus personal history.
 *
 * Scientific model (report §8, "Concrete, Computable Decision Rules"):
 *
 *  RULE 1 — HARD FATIGUE LIMIT. Cumulative session time exhibits a
 *  non-linear threshold at ~120 min (pupil-constriction / executive-failure
 *  inflection). Red > 120 min, Yellow > 90 min.
 *
 *  RULE 2 — LOSS-CHASING / STREAK DYNAMICS. A single loss triggers the
 *  "bounce-back effect" (performance typically RISES after one loss — never
 *  stop on one loss). 2 consecutive losses → Yellow; ≥ 3 → Red (gambling
 *  harm-minimization stop-loss, transferred to rating points).
 *
 *  RULE 3 — TILT VECTOR (speed × accuracy). Tilt manifests as playing too
 *  fast while playing poorly. Personal Z-scores against rolling baselines
 *  (last [BASELINE_WINDOW] games per time control):
 *    Red    : Z_moveTime ≤ −1.5 AND Z_deficit ≥ +1.5 AND the game was a
 *             LOSS (the loss check prevents flagging fast, chaotic wins —
 *             report §10 false-positive mitigation).
 *    Yellow : Z_moveTime ≤ −1.0 AND Z_deficit ≥ +1.0.
 *  Z_deficit is computed on the accuracy DEFICIT (baseline − accuracy), so
 *  positive = worse than personal norm. Population thresholds are
 *  scientifically invalid (report §7) — until [MIN_BASELINE_SAMPLES] games
 *  of personal history exist, the tilt vector does NOT gate.
 *
 *  CIRCADIAN ADJUSTMENT (report §5): late evening / night play (20:00–04:00
 *  local) naturally shifts humans to a faster, riskier "promotion focus".
 *  Before computing Z_moveTime the baseline mean (seconds/move) is divided
 *  by [CIRCADIAN_SPEED_RELAXATION] (1.10 — the personal norm is treated as
 *  ~10 % faster at night) so normal night-speed is not mistaken for steam
 *  tilt.
 *
 *  RULE 4 — CHRONIC OVERLOAD (ACWR). Acute:Chronic Workload Ratio with the
 *  robust Rolling Average model: acute = rated games in the last 7 days,
 *  chronic = average weekly games over the last 28 days. Green 0.8–1.3
 *  (sweet spot), Yellow ≥ 1.3, Red ≥ 1.5. Fewer than
 *  [ACWR_MIN_HISTORY_DAYS] distinct playing days on file → NO_DATA, does
 *  not gate (mirrors the Phase 1 v2 onboarding convention).
 *
 *  RULE 5 — HYSTERESIS (state persistence). Biological recovery is
 *  path-dependent: a Yellow state cannot revert to Green on one normal
 *  game. Green is only re-earned when the just-audited game was a Win or
 *  Draw AND Z_deficit ≤ 0 AND ≥ [HYSTERESIS_MIN_MINUTES] minutes passed
 *  since the Yellow flag was issued.
 *
 *  RULE 6 — HRV PHYSIOLOGICAL OVERRIDE is intentionally NOT implemented:
 *  no real-time wearable sample exists at post-game decision time (the
 *  report marks it optional / hardware-dependent).
 *
 * Verdicts reuse [ChessPhase2Engine.OutputState] (CONTINUE_RATED /
 * PIVOT_TO_DRILLS / TERMINATE_SESSION) so the ENTIRE enforcement stack —
 * Chess Guard policy, yellow entry warning, red blocking wall, rated-play
 * authorization, session derivation — works unchanged.
 *
 * The engine is PURE — no Android dependencies — so it is unit-testable.
 * Persistence lives in [ChessPhase2V2Store]; the audit is triggered by
 * sharing the finished game to Tail (see [ChessDeferredGameReconciler]).
 */
object ChessPhase2V2Engine {

    // ── Rule 1: hard fatigue limit ─────────────────────────────────────────

    /** Cumulative session minutes beyond which the verdict is TERMINATE (report §1/§8). */
    const val SESSION_RED_MINUTES = 120

    /** Cumulative session minutes beyond which the verdict is PIVOT/Yellow. */
    const val SESSION_YELLOW_MINUTES = 90

    // ── Rule 2: loss-chasing ───────────────────────────────────────────────

    /** Consecutive losses that trip Yellow (2 — the bounce-back covers 1). */
    const val STREAK_YELLOW = 2

    /** Consecutive losses that trip Red (≥ 3 — loss-chasing territory). */
    const val STREAK_RED = 3

    // ── Rule 3: tilt vector ────────────────────────────────────────────────

    /** Move-time Z at/below which speed counts as "extremely fast" (Red tier). */
    const val Z_MOVE_RED = -1.5

    /** Accuracy-deficit Z at/above which play counts as "extremely poor" (Red tier). */
    const val Z_DEFICIT_RED = 1.5

    /** Move-time Z threshold for the Yellow tier. */
    const val Z_MOVE_YELLOW = -1.0

    /** Accuracy-deficit Z threshold for the Yellow tier. */
    const val Z_DEFICIT_YELLOW = 1.0

    // ── Circadian adjustment ───────────────────────────────────────────────

    /** First hour (inclusive) of the late-evening promotion-focus window. */
    const val CIRCADIAN_START_HOUR = 20

    /** Exclusive upper bound of the window (04:00) — hours 20–23 and 0–3. */
    const val CIRCADIAN_END_HOUR = 4

    /** Baseline mean × this during the circadian window (report: +10 %). */
    const val CIRCADIAN_SPEED_RELAXATION = 1.10

    // ── Rule 4: ACWR ───────────────────────────────────────────────────────

    /** ACWR at/above which chronic overload trips Yellow. */
    const val ACWR_YELLOW = 1.3

    /** ACWR at/above which chronic overload trips Red. */
    const val ACWR_RED = 1.5

    /** Acute window in days (report: games in the last 7 days). */
    const val ACWR_ACUTE_DAYS = 7

    /** Chronic window in days (report: average weekly games over 28 days). */
    const val ACWR_CHRONIC_DAYS = 28

    /** Distinct playing days required before the ACWR rule gates. */
    const val ACWR_MIN_HISTORY_DAYS = 14

    // ── Rule 5: hysteresis ─────────────────────────────────────────────────

    /** Minutes that must pass after a Yellow flag before Green is re-earnable. */
    const val HYSTERESIS_MIN_MINUTES = 15

    // ── Personal baselines (report §7) ─────────────────────────────────────

    /** Rolling personal-history window per time control (last N games). */
    const val BASELINE_WINDOW = 100

    /** Games required before a baseline's Z-score may gate. */
    const val MIN_BASELINE_SAMPLES = 20

    /** SD floors — avoid explosive Z-scores on near-constant history. */
    const val SD_FLOOR_MOVE_SEC = 1.0
    const val SD_FLOOR_ACCURACY = 1.0

    // ── Input models ───────────────────────────────────────────────────────

    /** Telemetry for one completed rated game, as needed by the v2 rules. */
    data class GameInputV2(
        val timeControl: ChessPhase2Engine.TimeControl,
        val result: ChessPhase2Engine.GameResult,
        /** CAPS2 accuracy 0–100, or null when no Game Review exists. */
        val accuracy: Double?,
        /** Average seconds per move from the PGN clocks, or null when absent. */
        val avgMoveSec: Double?,
        /** Cumulative session minutes INCLUDING this game (base-clock estimate). */
        val sessionElapsedMins: Int,
        /** Local hour (0–23) at game end — drives the circadian adjustment. */
        val localHour: Int,
        /** Game ended < 10 moves (early resignation) — accuracy is noise. */
        val shortGame: Boolean
    )

    /** A prior rated game of the current session, for streaks + hysteresis. */
    data class SessionGameV2(
        val timestamp: Long,
        val result: ChessPhase2Engine.GameResult,
        /** [ChessPhase2Engine.OutputState] name of that game's audit. */
        val outputState: String
    )

    /** Personal baseline (mean/SD/sample size) for one metric + time control. */
    data class Baseline(
        val mean: Double,
        val sd: Double,
        val sampleSize: Int
    ) {
        /** True when enough personal history exists for Z-scores to gate. */
        val ready: Boolean get() = sampleSize >= MIN_BASELINE_SAMPLES
    }

    /** Rolling-Average ACWR inputs, computed by the caller from the game log. */
    data class AcwrInput(
        /** Rated games in the last [ACWR_ACUTE_DAYS] days (incl. today). */
        val acuteGames: Int,
        /** Average weekly games over the last [ACWR_CHRONIC_DAYS] days. */
        val chronicWeekly: Double,
        /** Distinct days with ≥ 1 rated game anywhere in the log. */
        val distinctDays: Int
    ) {
        /** The ratio; +∞ when chronic is 0 but acute is not. */
        val ratio: Double =
            if (chronicWeekly > 0.0) acuteGames / chronicWeekly
            else if (acuteGames > 0) Double.POSITIVE_INFINITY
            else 0.0

        /** True when enough history exists for the rule to gate. */
        val ready: Boolean get() = distinctDays >= ACWR_MIN_HISTORY_DAYS
    }

    // ── Result model ───────────────────────────────────────────────────────

    /** Full outcome of a v2 Phase 2 evaluation. */
    data class AuditResultV2(
        val timestamp: Long,
        /** Same enum as v1 — the whole enforcement stack consumes this name. */
        val outputState: ChessPhase2Engine.OutputState,
        /** Machine-readable rule ids that fired Red (e.g. "RULE_2_LOSS_STREAK"). */
        val redRules: List<String>,
        /** Machine-readable rule ids that fired Yellow. */
        val yellowRules: List<String>,
        // Rule 1 telemetry
        val sessionMinutes: Int,
        // Rule 2 telemetry
        val consecutiveLosses: Int,
        // Rule 3 telemetry
        /** Z-score of avg move time (negative = faster than baseline; null = no data). */
        val zMoveTime: Double?,
        /** Z-score of the accuracy deficit (positive = worse than baseline; null = no data). */
        val zDeficit: Double?,
        val moveBaseline: Baseline?,
        val accBaseline: Baseline?,
        /** True when the circadian relaxation was applied to the move baseline. */
        val circadianAdjusted: Boolean,
        // Rule 4 telemetry
        val acwr: Double?,
        /** True when ACWR had enough history to gate. */
        val acwrGated: Boolean,
        // Rule 5 telemetry
        /** True when hysteresis held the verdict at Yellow. */
        val hysteresisHeld: Boolean,
        /** Short machine-readable headline reason. */
        val reason: String,
        /** Human-facing instruction (report §9 interventions). */
        val message: String
    )

    // ── Baseline statistics ────────────────────────────────────────────────

    /**
     * Mean/SD of the most recent [BASELINE_WINDOW] values (caller passes the
     * rolling window, most recent last). Null when no history exists.
     */
    fun baselineOf(history: List<Double>, sdFloor: Double): Baseline? {
        if (history.isEmpty()) return null
        val window = history.takeLast(BASELINE_WINDOW)
        val mean = window.average()
        val sd = if (window.size < 2) sdFloor
        else kotlin.math.sqrt(window.sumOf { (it - mean) * (it - mean) } / (window.size - 1))
        return Baseline(mean, maxOf(sd, sdFloor), window.size)
    }

    /** Z = (x − μ) / σ with the baseline's floored SD. */
    fun zScore(x: Double, baseline: Baseline): Double = (x - baseline.mean) / baseline.sd

    /** True when [hour] falls in the 20:00–04:00 promotion-focus window. */
    fun isCircadianWindow(hour: Int): Boolean =
        hour >= CIRCADIAN_START_HOUR || hour < CIRCADIAN_END_HOUR

    // ── PGN clock parsing (Rule 3 input) ───────────────────────────────────

    private val CLK_TAG = Regex("\\[%clk\\s*(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)?]")

    /**
     * Remaining-clock seconds for every ply in PGN order (white first), or an
     * empty list when the PGN carries no `[%clk …]` annotations.
     */
    fun parseClocks(pgn: String): List<Double> =
        CLK_TAG.findAll(pgn).mapNotNull { m ->
            val h = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val min = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val s = m.groupValues[3].toDoubleOrNull() ?: return@mapNotNull null
            h * 3600.0 + min * 60.0 + s
        }.toList()

    /**
     * Average seconds the given side spent per move, from the PGN clocks:
     * per-move time = previous own clock reading − current own reading
     * (+ increment, so gained time is not counted as negative thinking time),
     * with the first move measured from the base clock. Null when fewer than
     * two own clock readings exist (no clocks / one-move game).
     *
     * @param pgn         full PGN with `{[%clk …]}` comments
     * @param isWhite     which side the user played
     * @param baseSeconds base clock of the time control (e.g. 180 for "180+2")
     * @param incrementSec increment per move (0 when none)
     */
    fun avgSecondsPerMove(
        pgn: String,
        isWhite: Boolean,
        baseSeconds: Double,
        incrementSec: Double = 0.0
    ): Double? {
        val clocks = parseClocks(pgn)
        val own = clocks.filterIndexed { i, _ -> (i % 2 == 0) == isWhite }
        if (own.size < 2) return null
        val times = ArrayList<Double>(own.size)
        for (i in own.indices) {
            val prev = if (i == 0) baseSeconds else own[i - 1]
            times += ((prev - own[i]) + incrementSec).coerceAtLeast(0.0)
        }
        return times.average()
    }

    /** Increment seconds of a chess.com time-control string ("180+2" → 2.0). */
    fun incrementSeconds(timeControl: String): Double =
        timeControl.split("+").getOrNull(1)?.toDoubleOrNull() ?: 0.0

    // ── Master evaluation ──────────────────────────────────────────────────

    /**
     * Runs the full v2 audit. PURE — no persistence, no clock reads.
     *
     * @param input         telemetry of the just-finished game
     * @param sessionGames  prior games of the CURRENT session (most recent
     *                      last; excludes the game being audited)
     * @param accBaseline   personal accuracy baseline for this time control
     * @param moveBaseline  personal avg-seconds-per-move baseline (same TC)
     * @param acwr          rolling-average ACWR inputs (null = no game log)
     * @param now           epoch millis stamped on the result (game end)
     */
    fun evaluate(
        input: GameInputV2,
        sessionGames: List<SessionGameV2>,
        accBaseline: Baseline?,
        moveBaseline: Baseline?,
        acwr: AcwrInput?,
        now: Long
    ): AuditResultV2 {
        // ── Rule 3 telemetry: personal Z-scores (population norms never gate)
        // Circadian: the personal norm is ~10 % FASTER at night (promotion
        // focus), i.e. FEWER seconds per move — the baseline mean is divided
        // by the relaxation factor so normal night speed is not flagged.
        val circadian = isCircadianWindow(input.localHour)
        val effMoveBaseline = moveBaseline?.let {
            if (circadian) it.copy(mean = it.mean / CIRCADIAN_SPEED_RELAXATION) else it
        }
        val zMove = if (input.avgMoveSec != null && effMoveBaseline?.ready == true)
            round2(zScore(input.avgMoveSec, effMoveBaseline)) else null
        val zDeficit = if (input.accuracy != null && !input.shortGame && accBaseline?.ready == true) {
            // Deficit = baseline − accuracy → positive means WORSE than norm.
            // accuracy ~ N(mean, sd) ⇒ deficit ~ N(0, sd): the deficit's own
            // mean is 0, so Z_deficit = deficit / sd (NOT zScore against the
            // accuracy mean — that would double-subtract it).
            round2((accBaseline.mean - input.accuracy) / accBaseline.sd)
        } else null

        // ── Rule 2 telemetry: consecutive losses including this game
        val consecutiveLosses =
            if (input.result != ChessPhase2Engine.GameResult.LOSS) 0
            else 1 + sessionGames
                .asReversed()
                .takeWhile { it.result == ChessPhase2Engine.GameResult.LOSS }
                .size

        // ── Rule 4 telemetry
        val acwrRatio = acwr?.takeIf { it.ready }?.ratio

        // ── Rule evaluation: any Red wins, then any Yellow, else Green
        val redRules = ArrayList<String>()
        val yellowRules = ArrayList<String>()

        // Rule 1 — hard fatigue limit
        if (input.sessionElapsedMins > SESSION_RED_MINUTES) redRules += "RULE_1_TIME_ON_TASK"
        else if (input.sessionElapsedMins > SESSION_YELLOW_MINUTES) yellowRules += "RULE_1_TIME_ON_TASK"

        // Rule 2 — loss-chasing (a single loss NEVER flags: bounce-back)
        if (input.result == ChessPhase2Engine.GameResult.LOSS) {
            if (consecutiveLosses >= STREAK_RED) redRules += "RULE_2_LOSS_STREAK"
            else if (consecutiveLosses == STREAK_YELLOW) yellowRules += "RULE_2_LOSS_STREAK"
        }

        // Rule 3 — tilt vector (fast AND poor simultaneously)
        val tiltRed = zMove != null && zDeficit != null &&
            zMove <= Z_MOVE_RED && zDeficit >= Z_DEFICIT_RED &&
            input.result == ChessPhase2Engine.GameResult.LOSS
        val tiltYellow = zMove != null && zDeficit != null &&
            zMove <= Z_MOVE_YELLOW && zDeficit >= Z_DEFICIT_YELLOW
        if (tiltRed) redRules += "RULE_3_TILT_VECTOR"
        else if (tiltYellow) yellowRules += "RULE_3_TILT_VECTOR"

        // Rule 4 — chronic overload
        if (acwrRatio != null) {
            if (acwrRatio >= ACWR_RED) redRules += "RULE_4_CHRONIC_OVERLOAD"
            else if (acwrRatio >= ACWR_YELLOW) yellowRules += "RULE_4_CHRONIC_OVERLOAD"
        }

        // ── Rule 5 — hysteresis: Yellow persists until recovery is PROVEN
        val lastYellow = sessionGames.lastOrNull {
            it.outputState == ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
        }
        val minutesSinceYellow = lastYellow
            ?.let { (now - it.timestamp) / 60000.0 } ?: Double.MAX_VALUE
        val recoveredFromYellow = lastYellow == null ||
            (input.result != ChessPhase2Engine.GameResult.LOSS &&
                (zDeficit == null || zDeficit <= 0.0) &&
                minutesSinceYellow >= HYSTERESIS_MIN_MINUTES)

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
                // Rules say Green, but the previous Yellow has not been
                // earned off yet — biological recovery is path-dependent.
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
            hysteresisHeld -> "RULE_5_HYSTERESIS"
            yellowRules.isNotEmpty() -> yellowRules.joinToString(" + ")
            else -> "NO_RISK_SIGNALS"
        }
        val message = buildMessage(state, redRules, yellowRules, hysteresisHeld,
            input, consecutiveLosses, zMove, zDeficit, acwrRatio)

        return AuditResultV2(
            timestamp = now,
            outputState = state,
            redRules = redRules,
            yellowRules = yellowRules,
            sessionMinutes = input.sessionElapsedMins,
            consecutiveLosses = consecutiveLosses,
            zMoveTime = zMove,
            zDeficit = zDeficit,
            moveBaseline = effMoveBaseline,
            accBaseline = accBaseline,
            circadianAdjusted = circadian && moveBaseline != null,
            acwr = acwrRatio?.let { round2(it) },
            acwrGated = acwrRatio != null,
            hysteresisHeld = hysteresisHeld,
            reason = reason,
            message = message
        )
    }

    // ── Intervention copy (report §9) ──────────────────────────────────────

    private fun buildMessage(
        state: ChessPhase2Engine.OutputState,
        redRules: List<String>,
        yellowRules: List<String>,
        hysteresis: Boolean,
        input: GameInputV2,
        losses: Int,
        zMove: Double?,
        zDeficit: Double?,
        acwr: Double?
    ): String = when (state) {
        ChessPhase2Engine.OutputState.TERMINATE_SESSION -> {
            val why = redRules.joinToString(" · ") { ruleLabel(it, losses, zMove, zDeficit, acwr, input) }
            "STOP — $why.\n\n" +
                "Recovery from cognitive fatigue and sympathetic arousal needs " +
                "at least 60 minutes away from the board — ideally no more rated " +
                "play today. Move, hydrate, rest."
        }
        ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS -> {
            if (hysteresis) {
                "Still in YELLOW (hysteresis): one normal game is not enough " +
                    "proof of recovery. Green returns after a win or draw with " +
                    "accuracy at/above your norm AND 15+ minutes since the " +
                    "yellow flag. Until then: unrated, bots, or drills."
            } else {
                val why = yellowRules.joinToString(" · ") {
                    ruleLabel(it, losses, zMove, zDeficit, acwr, input)
                }
                "CAUTION — $why.\n\n" +
                    "Take a 3–5 minute breather before anything else. Rated play " +
                    "pauses here: switch to unrated games, bots, or a few slow " +
                    "tactical puzzles to reset."
            }
        }
        ChessPhase2Engine.OutputState.CONTINUE_RATED ->
            "No fatigue, tilt, loss-chasing or overload signals. A single " +
                "loss would have been fine too (bounce-back effect). Cleared " +
                "for your next rated game."
    }

    private fun ruleLabel(
        rule: String,
        losses: Int,
        zMove: Double?,
        zDeficit: Double?,
        acwr: Double?,
        input: GameInputV2
    ): String = when (rule) {
        "RULE_1_TIME_ON_TASK" ->
            "${input.sessionElapsedMins} min of continuous play (fatigue ceiling)"
        "RULE_2_LOSS_STREAK" ->
            "$losses consecutive losses (loss-chasing risk)"
        "RULE_3_TILT_VECTOR" ->
            "tilt vector: speed Z ${"%+.2f".format(zMove ?: 0.0)}, " +
                "accuracy Z ${"%+.2f".format(zDeficit ?: 0.0)}"
        "RULE_4_CHRONIC_OVERLOAD" ->
            "workload ratio ${if (acwr != null && acwr.isInfinite()) "∞" else "%.2f".format(acwr ?: 0.0)} " +
                "(7-day games vs 28-day norm)"
        else -> rule
    }

    // ── Game → input mapping (pure; mirrors v1's eligibility checks) ───────

    /** Outcome of mapping a fetched chess.com game onto the v2 engine input. */
    sealed class MappingV2 {
        data class NotAuditable(val reason: String) : MappingV2()
        data class Ready(
            val input: GameInputV2,
            /** True when the API supplied a real accuracy. */
            val accuracyKnown: Boolean,
            /** True when the PGN clocks yielded a real avg move time. */
            val moveTimeKnown: Boolean,
            /** Base-clock minutes this game adds to the session tally. */
            val estimatedMinutes: Double,
            /** Elo expected-score delta of the game — stored on the shared
             *  audit record so the v1 engine's personal floors keep working
             *  if the user switches back to v1 mid-history. */
            val deltaE: Double
        ) : MappingV2()
    }

    /**
     * Converts a fetched chess.com game into a [GameInputV2], reusing the
     * shared eligibility rules from [ChessGameAuditMapper] (rated, standard/
     * Chess960, non-daily) and extracting the v2 telemetry: result, CAPS2
     * accuracy, average seconds/move from the PGN clocks, session minutes
     * and the local hour at game end.
     */
    fun inputFrom(
        game: ChessComGameDetail,
        username: String,
        sessionMinutesBefore: Double,
        localHour: Int
    ): MappingV2 {
        if (!game.rated) return MappingV2.NotAuditable(
            "Unrated game — casual play is not audited. Only rated games count."
        )
        if (game.rules.isNotBlank() &&
            game.rules !in setOf("chess", "chess960")
        ) return MappingV2.NotAuditable(
            "Variant game (${game.rules}) — not part of the readiness system."
        )
        val tc = ChessGameAuditMapper.timeControlFor(game.timeControl)
            ?: return MappingV2.NotAuditable(
                "Daily / correspondence game — not audited."
            )

        val userLower = username.trim().lowercase()
        val isWhite = game.whiteUsername.lowercase() == userLower
        val isBlack = game.blackUsername.lowercase() == userLower
        if (!isWhite && !isBlack) return MappingV2.NotAuditable(
            "You ($username) did not play in this game."
        )

        val result = ChessGameAuditMapper.resultFor(
            if (isWhite) game.whiteResult else game.blackResult
        )
        val userRating = if (isWhite) game.whiteRating else game.blackRating
        val opponentRating = if (isWhite) game.blackRating else game.whiteRating
        val accuracy = (if (isWhite) game.whiteAccuracy else game.blackAccuracy)
        val baseSeconds = game.timeControl.split("+").firstOrNull()
            ?.toDoubleOrNull() ?: 0.0
        val avgMoveSec = if (game.pgn.isNotBlank() && baseSeconds > 0.0) {
            avgSecondsPerMove(game.pgn, isWhite, baseSeconds, incrementSeconds(game.timeControl))
        } else null
        val minutes = ChessGameAuditMapper.estimateMinutes(game.timeControl)
        val shortGame = ChessGameAuditMapper.countPgnMoves(game.pgn) in 1..9

        return MappingV2.Ready(
            input = GameInputV2(
                timeControl = tc,
                result = result,
                accuracy = accuracy,
                avgMoveSec = avgMoveSec,
                sessionElapsedMins = (sessionMinutesBefore + minutes).toInt(),
                localHour = localHour,
                shortGame = shortGame
            ),
            accuracyKnown = accuracy != null,
            moveTimeKnown = avgMoveSec != null,
            estimatedMinutes = minutes,
            deltaE = ChessPhase2Engine.deltaE(userRating, opponentRating, result)
        )
    }

    private fun round2(v: Double): Double =
        if (v.isFinite()) (v * 100.0).roundToLong() / 100.0 else v
}
