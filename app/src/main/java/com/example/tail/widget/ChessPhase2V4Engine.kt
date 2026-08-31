package com.example.tail.widget

/**
 * Phase 2 v4 — the DATA-DERIVED post-game audit.
 *
 * Architecture: v4 runs the ENTIRE v3 hybrid engine first (fatigue,
 * ΔE-weighted streak, tilt vector, ACWR, strain accumulator, hysteresis —
 * including the desktop Stockfish blunder evidence), then OVERLAYS the
 * personal profile built by chess-coach from 6,500+ historical games:
 *
 *  - FATIGUE: v3's fixed 90/120-minute bars become per-time-control bars
 *    derived from the historical degradation curve (e.g. rapid yellow at
 *    105 min, bullet at 135). The readiness CCRS boost is preserved.
 *  - STREAK: the ΔE-weighted loss streak is recomputed with the profile's
 *    continuous loss-weight curve (interpolated, not three hard bands)
 *    and its data-derived yellow/red thresholds.
 *  - CIRCADIAN: the tilt rule's accuracy-deficit Z is compared against
 *    the PERSONAL hourly offset — at hours where you historically play
 *    worse, a deficit must exceed that offset to count as tilt evidence.
 *  - REST: a yellow verdict carries a data-derived rest prescription
 *    (net of the pipeline latency) instead of a fixed hysteresis timer.
 *
 * The output reuses [ChessPhase2V3Engine.AuditResultV3] so the entire
 * enforcement stack, ledger and result UI keep working unchanged. With the
 * fallback profile (desktop unreachable) v4 is bit-identical to v3.
 *
 * Pure object — no persistence, no clock reads.
 */
object ChessPhase2V4Engine {

    /** Minimum rest (minutes) ever prescribed, even if data says less. */
    const val MIN_REST_MINUTES = 5

    /**
     * Refines a completed v3 audit with the personal [profile].
     *
     * @param base          the v3 result for this game
     * @param input         the v3 input (session, result, expected score…)
     * @param sessionGames  prior games of the current session (v3 form)
     */
    fun refine(
        base: ChessPhase2V3Engine.AuditResultV3,
        input: ChessPhase2V3Engine.GameInputV3,
        sessionGames: List<ChessPhase2V3Engine.SessionGameV3>,
        profile: ChessPhase2V4Profile.Profile
    ): ChessPhase2V3Engine.AuditResultV3 {
        val redRules = base.redRules.toMutableList()
        val yellowRules = base.yellowRules.toMutableList()

        // ── FATIGUE overlay: profile bars replace v3's constants ────────
        val boost = base.fatigueYellowAt - ChessPhase2V3Engine.SESSION_YELLOW_MINUTES
        val fatigue = profile.fatigueFor(input.timeControl)
        val yellowAt = fatigue.yellow + boost
        val redAt = fatigue.red + boost
        val fatigueChanged = yellowAt != base.fatigueYellowAt ||
            redAt != base.fatigueRedAt
        if (fatigueChanged) {
            redRules.remove("RULE_1_TIME_ON_TASK")
            yellowRules.remove("RULE_1_TIME_ON_TASK")
            if (base.sessionMinutes > redAt) redRules += "RULE_1_TIME_ON_TASK"
            else if (base.sessionMinutes > yellowAt) yellowRules += "RULE_1_TIME_ON_TASK"
        }

        // ── STREAK overlay: continuous curve + derived thresholds ──────
        val weightedStreak = round2(
            weightedLossStreakCurve(input, sessionGames, profile)
        )
        val streakChanged = input.result == ChessPhase2Engine.GameResult.LOSS &&
            (weightedStreak != base.weightedStreak ||
                profile.streak.yellowWeight !=
                ChessPhase2V3Engine.STREAK_YELLOW_WEIGHT ||
                profile.streak.redWeight !=
                ChessPhase2V3Engine.STREAK_RED_WEIGHT)
        if (streakChanged) {
            redRules.remove("RULE_2_WEIGHTED_STREAK")
            yellowRules.remove("RULE_2_WEIGHTED_STREAK")
            if (input.result == ChessPhase2Engine.GameResult.LOSS) {
                if (weightedStreak >= profile.streak.redWeight)
                    redRules += "RULE_2_WEIGHTED_STREAK"
                else if (weightedStreak >= profile.streak.yellowWeight)
                    yellowRules += "RULE_2_WEIGHTED_STREAK"
            }
        }

        // ── BLUNDER overlay: personal allowance replaces the per-TC
        //    constant. The violation and the strain it feeds are recomputed
        //    against the profile's cap (weighted p75 of this TC's history).
        val blProfile = profile.baselines[input.timeControl.name.lowercase()]
        val personalCap = blProfile?.blunderCap
        val effCap = personalCap ?: input.timeControl.maxBlunders
        val blunderViolation = if (personalCap != null &&
            input.unforcedBlunders != null) {
            input.unforcedBlunders >= effCap
        } else base.blunderViolation
        val strain = if (personalCap != null && input.unforcedBlunders != null) {
            ChessPhase2Engine.strainFor(
                input.deltaE, base.floors, base.accViolation, blunderViolation
            )
        } else base.strain
        val strainChanged = strain != base.strain
        val sessionStrain = if (strainChanged)
            base.sessionStrain - base.strain + strain else base.sessionStrain
        val strainForgiven = if (strainChanged)
            base.strainForgiven &&
                strain <= ChessPhase2Engine.SEVERE_STRAIN
        else base.strainForgiven
        if (strainChanged) {
            redRules.remove("RULE_5_STRAIN")
            yellowRules.remove("RULE_5_STRAIN")
            if (base.catastrophic ||
                strain >= ChessPhase2Engine.STRAIN_TERMINATE_BASE ||
                sessionStrain >= base.strainTerminateAt
            ) redRules += "RULE_5_STRAIN"
            else if (!strainForgiven &&
                strain >= ChessPhase2Engine.MODERATE_STRAIN
            ) yellowRules += "RULE_5_STRAIN"
        }

        // ── CIRCADIAN overlay: personal hourly offset relaxes tilt ─────
        // A deficit Z at a historically-bad hour must CLEAR that hour's
        // offset before it counts as tilt evidence (the deficit that is
        // normal for you at 23:00 is not tilt).
        val hourOffset = profile.circadianOffsetZ(input.localHour)
        val circadianRelaxed = hourOffset > 0.15 &&
            base.zDeficit != null && base.zDeficit <= hourOffset &&
            base.yellowRules.contains("RULE_3_TILT_VECTOR") &&
            !base.redRules.contains("RULE_3_TILT_VECTOR")
        if (circadianRelaxed) yellowRules.remove("RULE_3_TILT_VECTOR")

        // ── Verdict: worst of the refined rule sets; hysteresis only
        //    applies when nothing else flags (same ordering as v3).
        val state = when {
            redRules.isNotEmpty() ->
                ChessPhase2Engine.OutputState.TERMINATE_SESSION
            yellowRules.isNotEmpty() ->
                ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS
            base.hysteresisHeld ->
                ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS
            else -> ChessPhase2Engine.OutputState.CONTINUE_RATED
        }

        // ── REST prescription on yellow (data-derived, latency-netted) ─
        val restMinutes = if (state == ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS) {
            (profile.rest.restMinutes - profile.pipelineLatencyMin)
                .coerceAtLeast(MIN_REST_MINUTES)
        } else null

        val reason = when {
            redRules.isNotEmpty() -> redRules.joinToString(" + ")
            base.hysteresisHeld && yellowRules.isEmpty() -> "RULE_6_HYSTERESIS"
            yellowRules.isNotEmpty() -> yellowRules.joinToString(" + ")
            else -> base.reason
        }

        val message = if (profile.isReal) {
            buildGameMessage(
                base, input, profile, state, redRules, yellowRules,
                yellowAt, redAt, weightedStreak, restMinutes,
                effCap, strain, sessionStrain
            )
        } else {
            base.message
        }

        return base.copy(
            outputState = state,
            redRules = redRules,
            yellowRules = yellowRules,
            weightedStreak = weightedStreak,
            fatigueYellowAt = yellowAt,
            fatigueRedAt = redAt,
            strain = strain,
            sessionStrain = sessionStrain,
            strainForgiven = strainForgiven,
            blunderViolation = blunderViolation,
            reason = reason,
            message = message
        )
    }

    /**
     * ΔE-weighted consecutive-loss total using the profile's CONTINUOUS
     * loss-weight curve (this game + the unbroken chain of losses).
     */
    fun weightedLossStreakCurve(
        input: ChessPhase2V3Engine.GameInputV3,
        sessionGames: List<ChessPhase2V3Engine.SessionGameV3>,
        profile: ChessPhase2V4Profile.Profile
    ): Double {
        if (input.result != ChessPhase2Engine.GameResult.LOSS) return 0.0
        var weight = profile.lossWeight(input.expectedScore)
        for (g in sessionGames.asReversed()) {
            if (g.result != ChessPhase2Engine.GameResult.LOSS) break
            weight += profile.lossWeight(
                g.expectedScore ?: ChessPhase2V3Engine.UNKNOWN_EXPECTED_DEFAULT
            )
        }
        return weight
    }

    /**
     * v4 message: THIS game's concrete inputs and the verdict they produced.
     * No rule codes, no version comparisons, no corpus-size meta — just the
     * numbers from the game that just happened measured against the
     * personal bars.
     */
    private fun buildGameMessage(
        base: ChessPhase2V3Engine.AuditResultV3,
        input: ChessPhase2V3Engine.GameInputV3,
        profile: ChessPhase2V4Profile.Profile,
        state: ChessPhase2Engine.OutputState,
        redRules: List<String>,
        yellowRules: List<String>,
        yellowAt: Int,
        redAt: Int,
        weightedStreak: Double,
        restMinutes: Int?,
        effCap: Int,
        strain: Double,
        sessionStrain: Double
    ): String {
        val tcName = input.timeControl.name.lowercase()
        val f2 = { v: Double -> String.format("%.2f", v) }
        val f1 = { v: Double -> String.format("%.1f", v) }

        // Lead with the engine numbers of THIS game (same as the audit lead).
        val lead = if (input.unforcedBlunders != null) {
            val parts = listOfNotNull(
                "unforced blunders " + input.unforcedBlunders +
                    " (max " + effCap + " for " + tcName + ")",
                input.blunderCount?.let { "blunders $it" },
                input.mistakeCount?.let { "mistakes $it" },
                input.inaccuracyCount?.let { "inaccuracies $it" },
                input.analysisAcpl?.let { "ACPL " + it.toInt() },
                input.analysisMoves?.let { "moves $it" }
            )
            "♟ Stockfish: " + parts.joinToString(" · ") + "."
        } else {
            "⚠ No engine data for this game — verdict from play history only."
        }

        val verdictWord = when (state) {
            ChessPhase2Engine.OutputState.TERMINATE_SESSION -> "STOP"
            ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS -> "PAUSE RATED PLAY"
            ChessPhase2Engine.OutputState.CONTINUE_RATED -> "CLEARED TO CONTINUE"
        }
        val why = (redRules + yellowRules).map { plainRuleName(it) }
            .distinct().joinToString(" + ")
        val verdict = if (why.isEmpty()) {
            when {
                base.hysteresisHeld -> "$verdictWord — still inside the recovery " +
                    "window from an earlier flag this session."
                else -> "$verdictWord — nothing crossed a bar."
            }
        } else {
            "$verdictWord — $why."
        }

        // ── This game's inputs, measured against the personal bars ──
        val lines = mutableListOf<String>()
        lines += "• session: " + base.sessionMinutes + " min in (yellow at " +
            yellowAt + ", red at " + redAt + " for " + tcName + ")"
        if (input.result == ChessPhase2Engine.GameResult.LOSS) {
            lines += "• this loss: expected " + f2(input.expectedScore) +
                ", ΔE " + String.format("%+.3f", input.deltaE) +
                " → weight " + f2(profile.lossWeight(input.expectedScore)) +
                "; streak " + f2(weightedStreak) + " (yellow at " +
                f2(profile.streak.yellowWeight) + ", red at " +
                f2(profile.streak.redWeight) + ")"
        }
        val bl = profile.baselines[tcName]
        if (input.unforcedBlunders != null && bl != null && bl.blunderSd > 0) {
            val z = (input.unforcedBlunders - bl.blunderMean) / bl.blunderSd
            lines += "• blunders " + input.unforcedBlunders + " vs norm " +
                f1(bl.blunderMean) + "±" + f1(bl.blunderSd) +
                " (z " + String.format("%+.2f", z) + "), cap " + effCap
        }
        if (input.mistakeCount != null && bl != null && bl.mistakeSd > 0) {
            val z = (input.mistakeCount - bl.mistakeMean) / bl.mistakeSd
            lines += "• mistakes " + input.mistakeCount + " vs norm " +
                f1(bl.mistakeMean) + "±" + f1(bl.mistakeSd) +
                " (z " + String.format("%+.2f", z) + ")"
        }
        if (input.inaccuracyCount != null && bl != null && bl.inaccuracySd > 0) {
            val z = (input.inaccuracyCount - bl.inaccuracyMean) / bl.inaccuracySd
            lines += "• inaccuracies " + input.inaccuracyCount + " vs norm " +
                f1(bl.inaccuracyMean) + "±" + f1(bl.inaccuracySd) +
                " (z " + String.format("%+.2f", z) + ")"
        }
        if (input.analysisAcpl != null && bl != null && bl.acplSd > 0) {
            val z = (input.analysisAcpl - bl.acplMean) / bl.acplSd
            lines += "• ACPL " + input.analysisAcpl.toInt() + " vs norm " +
                f1(bl.acplMean) + "±" + f1(bl.acplSd) +
                " (z " + String.format("%+.2f", z) + ")"
        }
        if (base.zMoveTime != null || base.zDeficit != null) {
            val circZ = profile.circadianOffsetZ(input.localHour)
            lines += "• speed z " + String.format("%+.2f", base.zMoveTime ?: 0.0) +
                ", accuracy z " + String.format("%+.2f", base.zDeficit ?: 0.0) +
                " at " + input.localHour + ":00" +
                (if (kotlin.math.abs(circZ) >= 0.15)
                    " (your offset there " + String.format("%+.2f", circZ) + "σ)"
                else "")
        }
        if (base.acwr != null) {
            lines += "• workload ratio " + f2(base.acwr) + " (7-day vs 28-day)"
        }
        lines += "• strain " + strain.toInt() + " this game, session " +
            sessionStrain.toInt() + " of " + base.strainTerminateAt.toInt()

        val restLine = if (restMinutes != null) {
            "\n\nrest prescription " + restMinutes + " min (net of " +
                profile.pipelineLatencyMin + " min pipeline latency)."
        } else ""

        return lead + "\n\n" + verdict + "\n\nThis game's inputs:\n" +
            lines.joinToString("\n") + restLine
    }

    /** Plain-English names — no rule codes in the v4 popup. */
    private fun plainRuleName(rule: String): String = when (rule) {
        "RULE_1_TIME_ON_TASK" -> "session fatigue"
        "RULE_2_WEIGHTED_STREAK" -> "loss streak"
        "RULE_3_TILT_VECTOR" -> "tilt signals"
        "RULE_4_CHRONIC_OVERLOAD" -> "chronic overload"
        "RULE_5_STRAIN" -> "strain accumulator"
        "RULE_6_HYSTERESIS" -> "recovery window"
        else -> rule
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
