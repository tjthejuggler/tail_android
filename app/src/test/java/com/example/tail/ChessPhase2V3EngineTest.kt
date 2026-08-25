package com.example.tail

import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2V2Engine
import com.example.tail.widget.ChessPhase2V3Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the v3 hybrid post-game audit: ΔE-weighted loss streaks,
 * the readiness-scaled fatigue ceiling, the strain accumulator with real
 * unforced-blunder inputs (and its away-from-PC fallback), one-dip
 * forgiveness, hysteresis and the tilt/ACWR passthroughs.
 */
class ChessPhase2V3EngineTest {

    private val now = 1_700_000_000_000L

    // ── Fixtures ───────────────────────────────────────────────────────────

    /** ΔE history dense around −0.5 → personal pivot floor bottoms at −0.50. */
    private val lenientFloors = (1..10).map {
        ChessPhase2Engine.DeltaERecord(now - it * 3_600_000L, -0.5)
    }

    private fun input(
        result: ChessPhase2Engine.GameResult = ChessPhase2Engine.GameResult.WIN,
        expectedScore: Double = 0.5,
        deltaE: Double = result.score - expectedScore,
        accuracy: Double? = null,
        avgMoveSec: Double? = null,
        sessionMins: Int = 10,
        localHour: Int = 14,
        shortGame: Boolean = false,
        unforcedBlunders: Int? = null,
        blunderCount: Int? = null,
        mistakeCount: Int? = null,
        inaccuracyCount: Int? = null,
        analysisAcpl: Double? = null,
        analysisMoves: Int? = null,
        accuracyHistory: List<Double> = emptyList(),
        ccrs: Int? = null,
        tc: ChessPhase2Engine.TimeControl = ChessPhase2Engine.TimeControl.BLITZ
    ) = ChessPhase2V3Engine.GameInputV3(
        timeControl = tc,
        result = result,
        accuracy = accuracy,
        avgMoveSec = avgMoveSec,
        sessionElapsedMins = sessionMins,
        localHour = localHour,
        shortGame = shortGame,
        expectedScore = expectedScore,
        deltaE = deltaE,
        unforcedBlunders = unforcedBlunders,
        blunderCount = blunderCount,
        mistakeCount = mistakeCount,
        inaccuracyCount = inaccuracyCount,
        analysisAcpl = analysisAcpl,
        analysisMoves = analysisMoves,
        accuracyHistory = accuracyHistory,
        readinessCcrs = ccrs
    )

    private fun sessionGame(
        result: ChessPhase2Engine.GameResult,
        minutesAgo: Long = 10,
        expectedScore: Double? = null,
        strain: Double = 0.0,
        outputState: String = ChessPhase2Engine.OutputState.CONTINUE_RATED.name
    ) = ChessPhase2V3Engine.SessionGameV3(
        timestamp = now - minutesAgo * 60_000L,
        result = result,
        outputState = outputState,
        expectedScore = expectedScore,
        strain = strain
    )

    private fun evaluate(
        input: ChessPhase2V3Engine.GameInputV3,
        session: List<ChessPhase2V3Engine.SessionGameV3> = emptyList(),
        accBaseline: ChessPhase2V2Engine.Baseline? = null,
        moveBaseline: ChessPhase2V2Engine.Baseline? = null,
        acwr: ChessPhase2V2Engine.AcwrInput? = null,
        deltaEHistory: List<ChessPhase2Engine.DeltaERecord> = lenientFloors
    ) = ChessPhase2V3Engine.evaluate(
        input = input,
        sessionGames = session,
        accBaseline = accBaseline,
        moveBaseline = moveBaseline,
        acwr = acwr,
        deltaEHistory = deltaEHistory,
        now = now
    )

    // ── Rule 2 — ΔE-weighted streak ────────────────────────────────────────

    @Test
    fun `loss weight bands`() {
        assertEquals(1.5, ChessPhase2V3Engine.lossWeight(0.6), 1e-9)
        assertEquals(1.0, ChessPhase2V3Engine.lossWeight(0.5), 1e-9)
        assertEquals(1.0, ChessPhase2V3Engine.lossWeight(0.4), 1e-9)
        assertEquals(0.5, ChessPhase2V3Engine.lossWeight(0.3), 1e-9)
    }

    @Test
    fun `single normal loss never flags`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
            // No other rule may fire either: lenient floors keep ΔE clean.
        )
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
        assertEquals(1.0, r.weightedStreak, 1e-9)
    }

    @Test
    fun `two normal losses reach yellow`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
            session = listOf(sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4))
        )
        assertEquals(ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_2_WEIGHTED_STREAK"))
        assertEquals(2.0, r.weightedStreak, 1e-9)
    }

    @Test
    fun `three normal losses reach red`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4)
            )
        )
        assertEquals(ChessPhase2Engine.OutputState.TERMINATE_SESSION, r.outputState)
        assertTrue(r.redRules.contains("RULE_2_WEIGHTED_STREAK"))
    }

    @Test
    fun `two favored losses reach red`() {
        // Losing twice as the favorite (E > 0.5): 1.5 + 1.5 = 3.0.
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.6),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.6)
            )
        )
        assertTrue(r.redRules.contains("RULE_2_WEIGHTED_STREAK"))
    }

    @Test
    fun `three underdog losses do not flag`() {
        // Losing to much stronger opponents: 0.5 × 3 = 1.5 — weak evidence.
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.3),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.3),
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.3)
            )
        )
        assertFalse(r.yellowRules.contains("RULE_2_WEIGHTED_STREAK"))
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
    }

    @Test
    fun `win breaks the loss chain`() {
        // Session list is most-recent-LAST: an older loss, then the win that
        // broke the chain, then this loss — streak must be this game only.
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
                sessionGame(ChessPhase2Engine.GameResult.WIN)
            )
        )
        assertEquals(1.0, r.weightedStreak, 1e-9)
    }

    @Test
    fun `pre-v3 ledger losses default to normal weight`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.4),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, expectedScore = null)
            )
        )
        assertEquals(2.0, r.weightedStreak, 1e-9)
    }

    // ── Rule 1 — readiness-scaled fatigue ──────────────────────────────────

    @Test
    fun `fatigue yellow at 91 minutes without readiness`() {
        val r = evaluate(input(sessionMins = 91))
        assertEquals(ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertTrue(r.yellowRules.contains("RULE_1_TIME_ON_TASK"))
        assertEquals(90, r.fatigueYellowAt)
        assertEquals(120, r.fatigueRedAt)
    }

    @Test
    fun `fatigue red at 121 minutes without readiness`() {
        val r = evaluate(input(sessionMins = 121))
        assertEquals(ChessPhase2Engine.OutputState.TERMINATE_SESSION, r.outputState)
        assertTrue(r.redRules.contains("RULE_1_TIME_ON_TASK"))
    }

    @Test
    fun `strong readiness lifts the fatigue ceiling`() {
        // CCRS 90 → +30 min: yellow at 120, red at 150.
        val r = evaluate(input(sessionMins = 115, ccrs = 90))
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
        assertEquals(120, r.fatigueYellowAt)
        assertEquals(150, r.fatigueRedAt)

        val r2 = evaluate(input(sessionMins = 121, ccrs = 90))
        assertTrue(r2.yellowRules.contains("RULE_1_TIME_ON_TASK"))
        assertFalse(r2.redRules.contains("RULE_1_TIME_ON_TASK"))
    }

    @Test
    fun `weak readiness adds no fatigue boost`() {
        assertEquals(0, ChessPhase2V3Engine.fatigueBoostMinutes(60))
        assertEquals(0, ChessPhase2V3Engine.fatigueBoostMinutes(null))
        assertEquals(15, ChessPhase2V3Engine.fatigueBoostMinutes(75))
        assertEquals(30, ChessPhase2V3Engine.fatigueBoostMinutes(85))
    }

    // ── Rule 5 — strain accumulator ────────────────────────────────────────

    @Test
    fun `catastrophic delta E is a hard red cutoff`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.9)
        )
        assertTrue(r.catastrophic)
        assertTrue(r.redRules.contains("RULE_5_STRAIN"))
        assertEquals(ChessPhase2Engine.OutputState.TERMINATE_SESSION, r.outputState)
    }

    @Test
    fun `accumulated session strain terminates`() {
        val r = evaluate(
            input(
                result = ChessPhase2Engine.GameResult.LOSS,
                expectedScore = 0.3,
                // Provisional accuracy rule: no history → default mean 75,
                // accuracy 55 → drop 20 > blitz tolerance 15.
                accuracy = 55.0,
                accuracyHistory = emptyList()
            ),
            // deltaE −0.3 vs lenient floors (pivot −0.50) is clean on its
            // own; the PRIOR 75 strain + accuracy violation 25 = 100.
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, strain = 75.0)
            )
        )
        assertTrue(r.redRules.contains("RULE_5_STRAIN"))
        assertEquals(ChessPhase2Engine.OutputState.TERMINATE_SESSION, r.outputState)
    }

    @Test
    fun `real unforced blunders flag the strain rule`() {
        val r = evaluate(
            input(unforcedBlunders = 2, blunderCount = 2) // blitz max = 2
        )
        assertTrue(r.accViolation.not())
        assertTrue(r.blunderViolation)
        assertTrue(r.yellowRules.contains("RULE_5_STRAIN"))
        assertTrue(r.engineBacked)
    }

    @Test
    fun `null analysis is the away-from-PC fallback`() {
        val r = evaluate(input(unforcedBlunders = null, blunderCount = null))
        assertFalse(r.blunderViolation)
        assertFalse(r.engineBacked)
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
    }

    @Test
    fun `message leads with the stockfish analysis facts`() {
        val r = evaluate(
            input(
                unforcedBlunders = 0, blunderCount = 0,
                mistakeCount = 2, inaccuracyCount = 2,
                analysisAcpl = 116.6, analysisMoves = 33
            )
        )
        assertTrue(r.message.startsWith("♟ Stockfish:"))
        assertTrue(r.message.contains("unforced blunders 0 (max 2 for blitz)"))
        assertTrue(r.message.contains("blunders 0"))
        assertTrue(r.message.contains("mistakes 2"))
        assertTrue(r.message.contains("inaccuracies 2"))
        assertTrue(r.message.contains("ACPL 117"))
        assertTrue(r.message.contains("moves 33"))
        // The old generic "verified" sentence is gone.
        assertFalse(r.message.contains("Verified"))
    }

    @Test
    fun `fallback message notes missing engine data`() {
        val r = evaluate(input(unforcedBlunders = null, blunderCount = null))
        assertTrue(r.message.startsWith("⚠ No engine data"))
        assertFalse(r.message.contains("Stockfish:"))
    }

    @Test
    fun `strong readiness forgives one moderate dip`() {
        // deltaE −0.3 vs COLD floors (pivot −0.20) → moderate strain 25;
        // CCRS 90 → buffer 25 ≥ 20 → forgiven in a clean session.
        val r = evaluate(
            input(
                result = ChessPhase2Engine.GameResult.LOSS,
                expectedScore = 0.3,
                ccrs = 90
            ),
            deltaEHistory = emptyList()
        )
        assertTrue(r.strainForgiven)
        assertFalse(r.yellowRules.contains("RULE_5_STRAIN"))
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
    }

    @Test
    fun `forgiveness needs a clean session`() {
        val r = evaluate(
            input(
                result = ChessPhase2Engine.GameResult.LOSS,
                expectedScore = 0.3,
                ccrs = 90
            ),
            session = listOf(
                sessionGame(ChessPhase2Engine.GameResult.LOSS, strain = 25.0)
            ),
            deltaEHistory = emptyList()
        )
        assertFalse(r.strainForgiven)
        assertTrue(r.yellowRules.contains("RULE_5_STRAIN"))
    }

    @Test
    fun `provisional accuracy rule uses the default mean on cold start`() {
        assertEquals(
            ChessPhase2Engine.TimeControl.BLITZ.defaultAccMean,
            ChessPhase2V3Engine.rollingAccuracyMean(
                emptyList(), ChessPhase2Engine.TimeControl.BLITZ
            ),
            1e-9
        )
        val r = evaluate(
            input(accuracy = 55.0) // 75 − 55 = 20 > 15 blitz tolerance
        )
        assertTrue(r.accViolation)
        assertTrue(r.yellowRules.contains("RULE_5_STRAIN"))
    }

    // ── Rule 3 — tilt vector passthrough ───────────────────────────────────

    private fun readyBaseline(mean: Double, sd: Double) =
        ChessPhase2V2Engine.Baseline(mean, sd, 25)

    @Test
    fun `tilt vector fires on fast AND poor`() {
        val r = evaluate(
            input(
                result = ChessPhase2Engine.GameResult.LOSS,
                expectedScore = 0.4,
                accuracy = 70.0,
                avgMoveSec = 8.0
            ),
            accBaseline = readyBaseline(75.0, 1.0),  // deficit 5 → Z +5
            moveBaseline = readyBaseline(10.0, 1.0)  // speed Z −2
        )
        assertTrue(r.yellowRules.contains("RULE_3_TILT_VECTOR") ||
            r.redRules.contains("RULE_3_TILT_VECTOR"))
    }

    @Test
    fun `tilt needs both axes`() {
        val r = evaluate(
            input(accuracy = 70.0, avgMoveSec = 10.0), // poor but normal speed
            accBaseline = readyBaseline(75.0, 1.0),
            moveBaseline = readyBaseline(10.0, 1.0)
        )
        assertFalse(r.yellowRules.contains("RULE_3_TILT_VECTOR"))
    }

    // ── Rule 4 — ACWR passthrough ──────────────────────────────────────────

    @Test
    fun `chronic overload red at ratio 2`() {
        val r = evaluate(
            input(),
            acwr = ChessPhase2V2Engine.AcwrInput(
                acuteGames = 20, chronicWeekly = 10.0, distinctDays = 20
            )
        )
        assertTrue(r.redRules.contains("RULE_4_CHRONIC_OVERLOAD"))
    }

    @Test
    fun `acwr does not gate without history`() {
        val r = evaluate(
            input(),
            acwr = ChessPhase2V2Engine.AcwrInput(
                acuteGames = 20, chronicWeekly = 10.0, distinctDays = 5
            )
        )
        assertFalse(r.acwrGated)
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
    }

    // ── Rule 6 — hysteresis ────────────────────────────────────────────────

    @Test
    fun `yellow holds until recovery is proven`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.WIN),
            session = listOf(
                sessionGame(
                    ChessPhase2Engine.GameResult.LOSS,
                    minutesAgo = 5, // < 15 min since the yellow flag
                    outputState = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
                )
            )
        )
        assertTrue(r.hysteresisHeld)
        assertEquals(ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS, r.outputState)
    }

    @Test
    fun `green returns after a proven recovery`() {
        val r = evaluate(
            input(result = ChessPhase2Engine.GameResult.WIN),
            session = listOf(
                sessionGame(
                    ChessPhase2Engine.GameResult.LOSS,
                    minutesAgo = 30,
                    outputState = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
                )
            )
        )
        assertFalse(r.hysteresisHeld)
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, r.outputState)
    }
}
