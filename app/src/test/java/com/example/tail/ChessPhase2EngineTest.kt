package com.example.tail

import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2Engine.FloorBasis
import com.example.tail.widget.ChessPhase2Engine.GameResult
import com.example.tail.widget.ChessPhase2Engine.OutputState
import com.example.tail.widget.ChessPhase2Engine.TimeControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 2 audit engine v2.0 — evidence-weighted strain
 * model with personal percentile ΔE floors, readiness buffer, and hard
 * cutoffs.
 */
class ChessPhase2EngineTest {

    private val E = ChessPhase2Engine
    private val NOW = 1_700_000_000_000L
    private val DAY = 24L * 60 * 60 * 1000

    private fun input(
        tc: TimeControl = TimeControl.BLITZ,
        user: Int = 1500,
        opp: Int = 1500,
        result: GameResult = GameResult.WIN,
        acc: Double = 75.0,
        blunders: Int = 0,
        unforced: Boolean = false,
        mins: Int = 20,
        short: Boolean = false,
        history: List<Double> = emptyList()
    ) = ChessPhase2Engine.GameInput(
        timeControl = tc, userRating = user, opponentRating = opp,
        gameResult = result, caps2Accuracy = acc, blunderCount = blunders,
        hasUnforcedBlunder = unforced, sessionElapsedMins = mins,
        shortGame = short, accuracyHistory = history
    )

    private fun session(vararg games: ChessPhase2Engine.SessionGame) = games.toList()

    private fun game(
        strain: Double = 0.0,
        deltaE: Double = 0.0,
        state: OutputState = OutputState.CONTINUE_RATED
    ) = ChessPhase2Engine.SessionGame(NOW - 60_000, "BLITZ", state.name, deltaE, strain)

    private fun deltaHistory(
        vararg values: Double,
        daysAgo: Int = 0
    ): List<ChessPhase2Engine.DeltaERecord> =
        values.mapIndexed { i, v ->
            ChessPhase2Engine.DeltaERecord(NOW - daysAgo * DAY - i, v)
        }

    // ── Elo math ───────────────────────────────────────────────────────────

    @Test
    fun `expected score of equal ratings is half`() {
        assertEquals(0.5, E.expectedScore(1500, 1500), 1e-9)
    }

    @Test
    fun `expected score against stronger opponent is low`() {
        assertEquals(0.091, E.expectedScore(1500, 1900), 0.001)
    }

    @Test
    fun `deltaE of loss to equal opponent is minus half`() {
        assertEquals(-0.5, E.deltaE(1500, 1500, GameResult.LOSS), 0.001)
        assertEquals(0.5, E.deltaE(1500, 1500, GameResult.WIN), 0.001)
    }

    // ── Rolling mean ───────────────────────────────────────────────────────

    @Test
    fun `rolling mean falls back to tier default`() {
        assertEquals(75.0, E.rollingMean(emptyList(), TimeControl.BLITZ), 1e-9)
        assertEquals(70.0, E.rollingMean(emptyList(), TimeControl.BULLET), 1e-9)
    }

    @Test
    fun `rolling mean averages only the last ten games`() {
        val history = (1..12).map { it.toDouble() } // last 10 → 3..12 → avg 7.5
        assertEquals(7.5, E.rollingMean(history, TimeControl.BLITZ), 1e-9)
    }

    // ── Core v2.0 decisions ────────────────────────────────────────────────

    @Test
    fun `clean win continues rated`() {
        val r = E.evaluate(input(result = GameResult.WIN), emptyList(), NOW)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertEquals(0.0, r.strain, 1e-9)
        assertEquals("OPTIMAL PERFORMANCE", r.reason)
    }

    @Test
    fun `clean draw continues rated`() {
        val r = E.evaluate(input(result = GameResult.DRAW), emptyList(), NOW)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertEquals(0.0, r.deltaE, 1e-9)
    }

    @Test
    fun `single normal loss pivots instead of terminating`() {
        // THE regression test: a coin-flip loss to an equal opponent
        // (ΔE = −0.5) must NEVER terminate the session on its own.
        val r = E.evaluate(input(result = GameResult.LOSS), emptyList(), NOW)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(E.SEVERE_STRAIN, r.strain, 1e-9)
        assertFalse(r.catastrophic)
        assertFalse(r.sessionCapReached)
        assertEquals("SEVERE UNDERPERFORMANCE VS YOUR BAR", r.reason)
    }

    @Test
    fun `strong readiness absorbs a single normal loss`() {
        val r = E.evaluate(
            input(result = GameResult.LOSS), emptyList(), NOW,
            readinessCcrs = 90
        )
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertEquals("READINESS BUFFER ABSORBED", r.reason)
        assertEquals(25, r.readinessBuffer)
        // The strain still counts toward the session tally
        assertEquals(E.SEVERE_STRAIN, r.strain, 1e-9)
    }

    @Test
    fun `weak readiness does not absorb a loss`() {
        val r = E.evaluate(
            input(result = GameResult.LOSS), emptyList(), NOW,
            readinessCcrs = 60
        )
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(0, r.readinessBuffer)
    }

    @Test
    fun `catastrophic loss terminates regardless of readiness and history`() {
        // user 1900 loses to 1500 → E ≈ 0.91 → ΔE ≈ −0.91 (hard cutoff)
        val r = E.evaluate(
            input(user = 1900, opp = 1500, result = GameResult.LOSS),
            emptyList(), NOW,
            deltaEHistory = deltaHistory(-0.5, -0.5, -0.5, -0.5, -0.5, -0.5),
            readinessCcrs = 95
        )
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertTrue(r.catastrophic)
        assertEquals("CATASTROPHIC LOSS (HARD CUTOFF)", r.reason)
    }

    @Test
    fun `severe deltaE plus both violations terminates in one game`() {
        // ΔE −0.5 (severe, 50) + accuracy drop (25) + unforced blunders (25) = 100
        val r = E.evaluate(
            input(result = GameResult.LOSS, acc = 59.0, blunders = 2, unforced = true),
            emptyList(), NOW
        )
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertEquals(E.STRAIN_TERMINATE_BASE, r.strain, 1e-9)
        assertEquals("SEVERE COLLAPSE ACROSS ALL METRICS", r.reason)
        assertFalse(r.catastrophic)
    }

    @Test
    fun `accumulated session strain terminates`() {
        // Prior games carried 75 strain; a fresh severe game (50) crosses 100.
        val r = E.evaluate(
            input(result = GameResult.LOSS),
            session(game(strain = 50.0, state = OutputState.PIVOT_TO_DRILLS),
                game(strain = 25.0, state = OutputState.PIVOT_TO_DRILLS)),
            NOW
        )
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertEquals("ACCUMULATED UNDERPERFORMANCE", r.reason)
        assertEquals(125.0, r.sessionStrain, 0.1)
        assertEquals(100.0, r.strainTerminateAt, 1e-9)
    }

    @Test
    fun `readiness buffer raises the termination bar`() {
        // Same 125 total strain, but CCRS 95 → terminate bar 130 → only pivot.
        val r = E.evaluate(
            input(result = GameResult.LOSS),
            session(game(strain = 50.0, state = OutputState.PIVOT_TO_DRILLS),
                game(strain = 25.0, state = OutputState.PIVOT_TO_DRILLS)),
            NOW,
            readinessCcrs = 95
        )
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(130.0, r.strainTerminateAt, 1e-9)
    }

    @Test
    fun `forgiveness applies only to the first flagged game`() {
        // A prior flagged game (strain 25) blocks forgiveness even with CCRS 90.
        val r = E.evaluate(
            input(result = GameResult.LOSS),
            session(game(strain = 25.0, state = OutputState.PIVOT_TO_DRILLS)),
            NOW,
            readinessCcrs = 90
        )
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals("SEVERE UNDERPERFORMANCE VS YOUR BAR", r.reason)
    }

    @Test
    fun `forgiveness never applies to strain above severe`() {
        // Moderate ΔE (−0.30) + accuracy drop + blunders = 75 strain: too much
        // to forgive even with strong readiness (terminate bar 125).
        val r = E.evaluate(
            input(user = 1500, opp = 1647, result = GameResult.LOSS,
                acc = 59.0, blunders = 2, unforced = true),
            emptyList(), NOW,
            readinessCcrs = 90
        )
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(75.0, r.strain, 1e-9)
        assertFalse(r.isFalseSuccess) // it was a loss, not a false win
    }

    @Test
    fun `session capacity ceiling terminates even a clean game`() {
        val r = E.evaluate(input(mins = 60), emptyList(), NOW)
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertTrue(r.sessionCapReached)
        assertEquals("60-MINUTE CAPACITY CEILING REACHED", r.reason)
    }

    // ── Violations ─────────────────────────────────────────────────────────

    @Test
    fun `accuracy drop alone pivots as false success`() {
        // Default blitz mean 75, tolerance 15 → acc 59 (drop 16) violates.
        val r = E.evaluate(input(result = GameResult.WIN, acc = 59.0), emptyList(), NOW)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertTrue(r.accViolation)
        assertTrue(r.isFalseSuccess)
        assertEquals("FALSE_SUCCESS", r.reason)
        // ΔE +0.5 contributes nothing — only the accuracy violation strains.
        assertEquals(E.ACC_VIOLATION_STRAIN, r.strain, 1e-9)
    }

    @Test
    fun `unforced blunders alone pivot`() {
        val r = E.evaluate(
            input(result = GameResult.WIN, blunders = 2, unforced = true),
            emptyList(), NOW
        )
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertTrue(r.blunderViolation)
        assertTrue(r.isFalseSuccess)
    }

    @Test
    fun `short game bypasses the accuracy check`() {
        val r = E.evaluate(
            input(result = GameResult.WIN, acc = 59.0, short = true),
            emptyList(), NOW
        )
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertTrue(r.accuracyIgnored)
        assertFalse(r.accViolation)
    }

    @Test
    fun `rolling accuracy history is used as baseline`() {
        val r = E.evaluate(
            input(result = GameResult.WIN, acc = 74.0, history = listOf(90.0, 90.0)),
            emptyList(), NOW
        )
        // Mean 90 − 74 = 16 > 15 tolerance → violation despite a "good" accuracy.
        assertTrue(r.accViolation)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertFalse(r.usedDefaultMean)
        assertEquals(90.0, r.rollingMeanUsed, 0.1)
    }

    // ── Personal adaptive ΔE floors ────────────────────────────────────────

    @Test
    fun `floors cold start below minimum sample`() {
        val f = E.computeDeltaFloors(deltaHistory(-0.1, -0.3, -0.2, -0.4), NOW)
        assertEquals(FloorBasis.COLD_START, f.basis)
        assertEquals(E.COLD_TERMINATE_FLOOR, f.terminate, 1e-9)
        assertEquals(E.COLD_PIVOT_FLOOR, f.pivot, 1e-9)
        assertEquals(4, f.sampleSize)
    }

    @Test
    fun `frequent losses lower the personal bar`() {
        // Someone whose recent games are all −0.5 gets floors at −0.5, so an
        // ordinary −0.5 loss is WITHIN their normal range → continue.
        val history = deltaHistory(-0.5, -0.5, -0.5, -0.5, -0.5, -0.5)
        val f = E.computeDeltaFloors(history, NOW)
        assertEquals(FloorBasis.PERCENTILE, f.basis)
        assertEquals(-0.5, f.terminate, 1e-9)
        assertEquals(-0.5, f.pivot, 1e-9)

        val r = E.evaluate(input(result = GameResult.LOSS), emptyList(), NOW,
            deltaEHistory = history)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertEquals(0.0, r.strain, 1e-9)
    }

    @Test
    fun `strong history never tightens floors beyond the strict clamps`() {
        val history = deltaHistory(0.3, 0.3, 0.3, 0.3, 0.3, 0.3)
        val f = E.computeDeltaFloors(history, NOW)
        assertEquals(E.TERMINATE_FLOOR_STRICT, f.terminate, 1e-9)
        assertEquals(E.PIVOT_FLOOR_STRICT, f.pivot, 1e-9)

        // A −0.5 loss is still severe against the clamped floor.
        val r = E.evaluate(input(result = GameResult.LOSS), emptyList(), NOW,
            deltaEHistory = history)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(E.SEVERE_STRAIN, r.strain, 1e-9)
    }

    @Test
    fun `history older than the window is ignored`() {
        val old = deltaHistory(-0.9, -0.9, -0.9, -0.9, -0.9, daysAgo = 30)
        val recent = deltaHistory(0.2, 0.2, 0.2, 0.2, 0.2)
        val f = E.computeDeltaFloors(old + recent, NOW)
        assertEquals(FloorBasis.PERCENTILE, f.basis)
        assertEquals(5, f.sampleSize)
        // p10 of [+0.2 × 5] = 0.2 → clamped to the strict bound
        assertEquals(E.TERMINATE_FLOOR_STRICT, f.terminate, 1e-9)
    }

    @Test
    fun `floor window is capped at fifteen games`() {
        val f = E.computeDeltaFloors(deltaHistory(*DoubleArray(20) { -0.3 }), NOW)
        assertEquals(E.HISTORY_WINDOW_GAMES, f.sampleSize)
    }

    @Test
    fun `pivot floor never loosens beyond its lenient clamp`() {
        val f = E.computeDeltaFloors(deltaHistory(-0.8, -0.8, -0.8, -0.8, -0.8, -0.8), NOW)
        assertEquals(E.CATASTROPHIC_DELTA_E, f.terminate, 1e-9)
        assertEquals(E.PIVOT_FLOOR_LENIENT, f.pivot, 1e-9)
        // A −0.6 game is only MODERATE against these floors.
        assertEquals(E.MODERATE_STRAIN, E.strainFor(-0.6, f, false, false), 1e-9)
    }

    // ── Readiness buffer tiers ─────────────────────────────────────────────

    @Test
    fun `readiness buffer scales with pre-game ccrs`() {
        assertEquals(0, E.readinessBuffer(null))
        assertEquals(0, E.readinessBuffer(60))
        assertEquals(0, E.readinessBuffer(69))
        assertEquals(5, E.readinessBuffer(70))
        assertEquals(10, E.readinessBuffer(75))
        assertEquals(15, E.readinessBuffer(80))
        assertEquals(20, E.readinessBuffer(85))
        assertEquals(25, E.readinessBuffer(90))
        assertEquals(30, E.readinessBuffer(95))
        assertEquals(30, E.readinessBuffer(100))
    }

    // ── Classification & percentile ────────────────────────────────────────

    @Test
    fun `deltaE classification uses the personal floors`() {
        val f = E.computeDeltaFloors(emptyList(), NOW) // cold: −0.45 / −0.20
        assertEquals("Within your normal range", E.deltaEClassification(0.3, f))
        assertEquals("Below your usual bar", E.deltaEClassification(-0.25, f))
        assertEquals("Severe for you — bottom 10% of your games",
            E.deltaEClassification(-0.5, f))
        assertEquals("Catastrophic loss (hard cutoff)",
            E.deltaEClassification(-0.8, f))
    }

    @Test
    fun `percentile interpolates linearly`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(1.0, E.percentileOf(values, 0.0), 1e-9)
        assertEquals(3.0, E.percentileOf(values, 0.5), 1e-9)
        assertEquals(5.0, E.percentileOf(values, 1.0), 1e-9)
        assertEquals(1.5, E.percentileOf(values, 0.125), 1e-9)
    }
}
