package com.example.tail

import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2V3Engine
import com.example.tail.widget.ChessPhase2V4Engine
import com.example.tail.widget.ChessPhase2V4Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * Pure-engine tests for the Phase 2 v4 overlay: fallback equivalence with
 * v3, profile-driven fatigue bars, the continuous loss-weight curve and
 * profile JSON parsing.
 */
class ChessPhase2V4EngineTest {

    private fun input(
        result: ChessPhase2Engine.GameResult =
            ChessPhase2Engine.GameResult.WIN,
        sessionMins: Int = 30,
        expectedScore: Double = 0.5,
        hour: Int = 18
    ) = ChessPhase2V3Engine.GameInputV3(
        timeControl = ChessPhase2Engine.TimeControl.RAPID,
        result = result,
        accuracy = null,
        avgMoveSec = null,
        sessionElapsedMins = sessionMins,
        localHour = hour,
        shortGame = false,
        expectedScore = expectedScore,
        deltaE = result.score - expectedScore,
        unforcedBlunders = null,
        blunderCount = null,
        mistakeCount = null,
        inaccuracyCount = null,
        analysisAcpl = null,
        analysisMoves = null,
        accuracyHistory = emptyList(),
        readinessCcrs = null
    )

    private fun evaluate(
        i: ChessPhase2V3Engine.GameInputV3,
        session: List<ChessPhase2V3Engine.SessionGameV3> = emptyList()
    ): ChessPhase2V3Engine.AuditResultV3 =
        ChessPhase2V3Engine.evaluate(
            input = i,
            sessionGames = session,
            accBaseline = null,
            moveBaseline = null,
            acwr = null,
            deltaEHistory = emptyList(),
            now = 1_000_000L
        )

    private fun profile(
        fatigueYellow: Int? = null,
        fatigueRed: Int? = null,
        curve: List<ChessPhase2V4Profile.CurvePoint> = emptyList(),
        streakYellow: Double = ChessPhase2V3Engine.STREAK_YELLOW_WEIGHT,
        streakRed: Double = ChessPhase2V3Engine.STREAK_RED_WEIGHT,
        restMinutes: Int = 30
    ) = ChessPhase2V4Profile.Profile(
        version = 1,
        generatedAt = "test",
        gamesAnalyzed = 6564,
        sessionGapMin = 45.0,
        pipelineLatencyMin = 5,
        baselines = emptyMap(),
        fatigue = mapOf(
            "rapid" to ChessPhase2V4Profile.Fatigue(
                fatigueYellow, fatigueRed, 90, 120
            )
        ),
        lossWeightCurve = curve,
        lossCurveDerived = curve.isNotEmpty(),
        streak = ChessPhase2V4Profile.StreakThresholds(
            streakYellow, streakRed, derived = true
        ),
        rest = ChessPhase2V4Profile.Rest(restMinutes, derived = true),
        circadian = emptyList()
    )

    @Test
    fun `fallback profile is bit-identical to v3`() {
        val i = input(sessionMins = 200) // v3: red fatigue
        val base = evaluate(i)
        val refined = ChessPhase2V4Engine.refine(
            base, i, emptyList(), ChessPhase2V4Profile.fallback()
        )
        assertEquals(base.outputState, refined.outputState)
        assertEquals(base.redRules, refined.redRules)
        assertEquals(base.yellowRules, refined.yellowRules)
        assertEquals(base.fatigueYellowAt, refined.fatigueYellowAt)
        assertEquals(base.fatigueRedAt, refined.fatigueRedAt)
    }

    @Test
    fun `profile fatigue bars replace v3 constants`() {
        // v3 says yellow at 91+ minutes; the profile says rapid is fine
        // until 150 — the same game must flip from yellow to continue.
        val i = input(sessionMins = 100)
        val base = evaluate(i)
        assertEquals(
            ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS, base.outputState
        )
        val refined = ChessPhase2V4Engine.refine(
            base, i, emptyList(), profile(fatigueYellow = 150, fatigueRed = 200)
        )
        assertEquals(ChessPhase2Engine.OutputState.CONTINUE_RATED, refined.outputState)
        assertEquals(150, refined.fatigueYellowAt)
        assertEquals(200, refined.fatigueRedAt)
    }

    @Test
    fun `continuous loss curve recomputes the streak`() {
        // Two even-matchup losses: v3 weights 1.0 + 1.0 = 2.0 → yellow.
        val prior = ChessPhase2V3Engine.SessionGameV3(
            timestamp = 900_000L,
            result = ChessPhase2Engine.GameResult.LOSS,
            outputState = ChessPhase2Engine.OutputState.CONTINUE_RATED.name,
            expectedScore = 0.5,
            strain = 0.0
        )
        val i = input(
            result = ChessPhase2Engine.GameResult.LOSS, expectedScore = 0.5
        )
        val base = evaluate(i, listOf(prior))
        assertEquals(2.0, base.weightedStreak, 0.001)
        // Profile curve says even losses weigh only 0.6 → streak 1.2,
        // below the derived yellow bar of 1.5 → continue.
        val curve = listOf(
            ChessPhase2V4Profile.CurvePoint(0.2, 0.3),
            ChessPhase2V4Profile.CurvePoint(0.5, 0.6),
            ChessPhase2V4Profile.CurvePoint(0.8, 1.2)
        )
        val refined = ChessPhase2V4Engine.refine(
            base, i, listOf(prior),
            profile(curve = curve, streakYellow = 1.5, streakRed = 2.5)
        )
        assertEquals(1.2, refined.weightedStreak, 0.001)
        // RULE_2 is gone from the refined yellow set (the ΔE may still
        // flag via the strain rule, which v4 intentionally keeps).
        assertTrue(base.yellowRules.contains("RULE_2_WEIGHTED_STREAK"))
        assertTrue(!refined.yellowRules.contains("RULE_2_WEIGHTED_STREAK"))
        assertTrue(!refined.redRules.contains("RULE_2_WEIGHTED_STREAK"))
    }

    @Test
    fun `yellow verdict carries a latency-netted rest prescription`() {
        val i = input(sessionMins = 100) // yellow via v3 fatigue
        val base = evaluate(i)
        val refined = ChessPhase2V4Engine.refine(
            base, i, emptyList(), profile(restMinutes = 25)
        )
        assertEquals(ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS, refined.outputState)
        // 25 min prescription − 5 min pipeline latency = 20 min.
        assertTrue(refined.message.contains("rest prescription 20 min"))
    }

    @Test
    fun `personal blunder cap relaxes the strain rule`() {
        // 1 unforced blunder in rapid: v3's fixed cap (1) makes it a
        // violation (+25 strain). The profile's cap is 2 → no violation,
        // lower strain, and the popup shows the personal cap.
        val i = input().copy(unforcedBlunders = 1)
        val base = evaluate(i)
        assertTrue(base.blunderViolation)
        val p = profile().copy(
            baselines = mapOf(
                "rapid" to ChessPhase2V4Profile.Baseline(
                    acplMean = 344.0, acplSd = 399.0,
                    blunderMean = 1.65, blunderSd = 1.97, blunderCap = 2,
                    mistakeMean = 2.4, mistakeSd = 2.2,
                    inaccuracyMean = 7.7, inaccuracySd = 4.5,
                    avgGameMinutes = 11.0
                )
            )
        )
        val refined = ChessPhase2V4Engine.refine(base, i, emptyList(), p)
        assertTrue(!refined.blunderViolation)
        assertTrue(refined.strain < base.strain)
        assertTrue(refined.message.contains("max 2 for rapid"))
    }

    @Test
    fun `profile json parses with nullable fatigue minutes`() {
        val json = JSONObject("""
            {"version":1,"generatedAt":"t","gamesAnalyzed":6564,
             "sessionGapMin":45,"pipelineLatencyMin":5,
             "baselines":{"rapid":{"acplMean":344.7,"acplSd":399.7,
               "blunderMean":1.66,"blunderSd":1.97,"avgGameMinutes":11.0}},
             "fatigue":{"rapid":{"yellowMin":105,"redMin":null,
               "fallbackYellow":90,"fallbackRed":120}},
             "lossWeightCurve":{"derived":false,"points":[
               {"expected":0.2,"weight":0.5},
               {"expected":0.42,"weight":1.0},
               {"expected":0.75,"weight":1.5}]},
             "streakThresholds":{"yellowWeight":2.0,"redWeight":3.0,
               "derived":false},
             "rest":{"restMinutes":5,"derived":true},
             "circadian":[{"hour":23,"offsetZ":0.31,"n":210}]}
        """.trimIndent())
        val p = ChessPhase2V4Profile.parse(json)
        assertNotNull(p)
        assertEquals(6564, p!!.gamesAnalyzed)
        assertEquals(105, p.fatigueFor(ChessPhase2Engine.TimeControl.RAPID).yellow)
        assertEquals(120, p.fatigueFor(ChessPhase2Engine.TimeControl.RAPID).red)
        assertEquals(0.31, p.circadianOffsetZ(23), 0.001)
        assertEquals(0.0, p.circadianOffsetZ(9), 0.001)
        // Curve interpolation between 0.42 (1.0) and 0.75 (1.5).
        assertEquals(1.25, p.lossWeight(0.585), 0.01)
    }
}
