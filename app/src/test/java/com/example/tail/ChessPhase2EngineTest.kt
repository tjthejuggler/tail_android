package com.example.tail

import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2Engine.GameResult
import com.example.tail.widget.ChessPhase2Engine.OutputState
import com.example.tail.widget.ChessPhase2Engine.TimeControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 2 Post-Game Performance Audit Engine (spec v1.0).
 * Covers the Elo ΔE math, time-control calibrated thresholds, the master
 * decision matrix, false-success detection and the §7 edge cases.
 */
class ChessPhase2EngineTest {

    private val NOW = 1_700_000_000_000L
    private val E = ChessPhase2Engine

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
        timeControl = tc,
        userRating = user,
        opponentRating = opp,
        gameResult = result,
        caps2Accuracy = acc,
        blunderCount = blunders,
        hasUnforcedBlunder = unforced,
        sessionElapsedMins = mins,
        shortGame = short,
        accuracyHistory = history
    )

    private fun session(vararg states: OutputState) =
        states.mapIndexed { i, s ->
            ChessPhase2Engine.SessionGame(NOW - (i + 1) * 600_000L, "BLITZ", s.name)
        }

    // ── Elo expected score math ─────────────────────────────────────────────

    @Test
    fun `equal ratings give expected score of one half`() {
        assertEquals(0.5, E.expectedScore(1500, 1500), 1e-9)
    }

    @Test
    fun `400 point advantage gives ~0_909 expected score`() {
        assertEquals(0.909, E.expectedScore(1900, 1500), 0.001)
        assertEquals(0.091, E.expectedScore(1500, 1900), 0.001)
    }

    @Test
    fun `expected score is symmetric`() {
        assertEquals(1.0, E.expectedScore(1520, 1545) + E.expectedScore(1545, 1520), 1e-9)
    }

    @Test
    fun `deltaE classification boundaries`() {
        assertEquals(
            "Expected / Superior Performance",
            E.deltaEClassification(-0.15)
        )
        assertEquals(
            "Moderate Underperformance",
            E.deltaEClassification(-0.151)
        )
        assertEquals(
            "Moderate Underperformance",
            E.deltaEClassification(-0.35)
        )
        assertEquals(
            "Severe Executive Underperformance",
            E.deltaEClassification(-0.351)
        )
    }

    // ── 60-minute session cap ───────────────────────────────────────────────

    @Test
    fun `session cap of 60 minutes terminates regardless of performance`() {
        val r = E.evaluate(input(mins = 60), emptyList(), NOW)
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertTrue(r.sessionCapReached)
        assertEquals("60-MINUTE CAPACITY CEILING REACHED", r.reason)
    }

    @Test
    fun `59 minutes does not hit the cap`() {
        val r = E.evaluate(input(mins = 59), emptyList(), NOW)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertFalse(r.sessionCapReached)
    }

    // ── Severe executive underperformance ───────────────────────────────────

    @Test
    fun `loss to much weaker opponent is severe underperformance`() {
        // E_A ≈ 0.952 → ΔE ≈ −0.952 < −0.35
        val r = E.evaluate(input(user = 1520, opp = 1000, result = GameResult.LOSS), emptyList(), NOW)
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertEquals(-0.952, r.deltaE, 0.001)
        assertEquals("SEVERE EXECUTIVE UNDERPERFORMANCE", r.reason)
    }

    // ── Repeated executive failures (session history) ───────────────────────

    @Test
    fun `prior yellow game in session terminates even when current game is clean`() {
        val r = E.evaluate(
            input(),
            session(OutputState.CONTINUE_RATED, OutputState.PIVOT_TO_DRILLS),
            NOW
        )
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
        assertEquals("REPEATED EXECUTIVE FAILURES", r.reason)
    }

    @Test
    fun `prior green games do not escalate`() {
        val r = E.evaluate(
            input(),
            session(OutputState.CONTINUE_RATED, OutputState.CONTINUE_RATED),
            NOW
        )
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
    }

    // ── Calibrated accuracy thresholds ──────────────────────────────────────

    @Test
    fun `blitz accuracy drop of exactly 15 is not a violation`() {
        // default mean 75 → 60 gives ΔA = 15, needs > 15
        val r = E.evaluate(input(acc = 60.0), emptyList(), NOW)
        assertFalse(r.accViolation)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
    }

    @Test
    fun `blitz accuracy drop above 15 pivots to drills`() {
        val r = E.evaluate(input(acc = 59.0), emptyList(), NOW)
        assertTrue(r.accViolation)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals(16.0, r.accuracyDelta, 0.01)
    }

    @Test
    fun `bullet tolerance is 20 points`() {
        assertEquals(
            OutputState.CONTINUE_RATED,
            E.evaluate(
                input(tc = TimeControl.BULLET, acc = 50.0), emptyList(), NOW
            ).outputState
        )
        assertEquals(
            OutputState.PIVOT_TO_DRILLS,
            E.evaluate(
                input(tc = TimeControl.BULLET, acc = 49.5), emptyList(), NOW
            ).outputState
        )
    }

    @Test
    fun `rapid tolerance is 10 points`() {
        assertEquals(
            OutputState.CONTINUE_RATED,
            E.evaluate(
                input(tc = TimeControl.RAPID, acc = 70.0), emptyList(), NOW
            ).outputState
        )
        assertEquals(
            OutputState.PIVOT_TO_DRILLS,
            E.evaluate(
                input(tc = TimeControl.RAPID, acc = 69.9), emptyList(), NOW
            ).outputState
        )
    }

    // ── Calibrated unforced blunder thresholds ──────────────────────────────

    @Test
    fun `one unforced blunder violates rapid but not blitz or bullet`() {
        assertEquals(
            OutputState.PIVOT_TO_DRILLS,
            E.evaluate(
                input(tc = TimeControl.RAPID, acc = 80.0, blunders = 1, unforced = true),
                emptyList(), NOW
            ).outputState
        )
        assertEquals(
            OutputState.CONTINUE_RATED,
            E.evaluate(
                input(blunders = 1, unforced = true), emptyList(), NOW // blitz needs ≥ 2
            ).outputState
        )
        assertEquals(
            OutputState.CONTINUE_RATED,
            E.evaluate(
                input(tc = TimeControl.BULLET, acc = 70.0, blunders = 2, unforced = true),
                emptyList(), NOW // bullet needs ≥ 3
            ).outputState
        )
    }

    @Test
    fun `blunders without the unforced flag never violate`() {
        // Time-scramble blunders (§7.2): user leaves the checkbox unchecked.
        val r = E.evaluate(
            input(tc = TimeControl.RAPID, acc = 80.0, blunders = 5, unforced = false),
            emptyList(), NOW
        )
        assertFalse(r.blunderViolation)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
    }

    // ── False success detection ─────────────────────────────────────────────

    @Test
    fun `win with accuracy collapse is a false success`() {
        val r = E.evaluate(input(result = GameResult.WIN, acc = 55.0), emptyList(), NOW)
        assertTrue(r.accViolation)
        assertTrue(r.isFalseSuccess)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals("FALSE_SUCCESS", r.reason)
        assertTrue(r.message.contains("FALSE WIN DETECTED"))
    }

    @Test
    fun `loss with accuracy collapse is not a false success`() {
        val r = E.evaluate(
            input(result = GameResult.LOSS, acc = 55.0, user = 1500, opp = 1500),
            emptyList(), NOW
        )
        // LOSS vs equal → ΔE = −0.5 → severe anyway, but not a false success
        assertFalse(r.isFalseSuccess)
        assertEquals(OutputState.TERMINATE_SESSION, r.outputState)
    }

    // ── Moderate ΔE band ────────────────────────────────────────────────────

    @Test
    fun `moderate underperformance pivots to drills`() {
        // 1500 vs 1650 → E_A ≈ 0.297 → LOSS ΔE ≈ −0.297 (moderate band)
        val r = E.evaluate(
            input(user = 1500, opp = 1650, result = GameResult.LOSS),
            emptyList(), NOW
        )
        assertEquals(-0.297, r.deltaE, 0.001)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
        assertEquals("EXECUTIVE CALCULATION DROP", r.reason)
    }

    @Test
    fun `deltaE just above minus 0_15 is expected performance`() {
        // DRAW as 107-point favorite: E_A ≈ 0.6493 → ΔE ≈ −0.149 (≥ −0.15)
        val r = E.evaluate(
            input(user = 1500, opp = 1393, result = GameResult.DRAW),
            emptyList(), NOW
        )
        assertEquals(-0.149, r.deltaE, 0.001)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
    }

    @Test
    fun `deltaE just below minus 0_15 is moderate underperformance`() {
        // DRAW as 108-point favorite: E_A ≈ 0.6505 → ΔE ≈ −0.151 (< −0.15)
        val r = E.evaluate(
            input(user = 1500, opp = 1392, result = GameResult.DRAW),
            emptyList(), NOW
        )
        assertEquals(-0.151, r.deltaE, 0.001)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
    }

    // ── Optimal performance ─────────────────────────────────────────────────

    @Test
    fun `clean win against equal opponent continues rated`() {
        val r = E.evaluate(input(), emptyList(), NOW)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        assertEquals("#22C55E", r.outputState.colorHex)
        assertEquals(0.5, r.deltaE, 1e-9)
        assertFalse(r.accViolation)
        assertFalse(r.blunderViolation)
        assertFalse(r.isFalseSuccess)
        assertTrue(r.message.contains("Cleared"))
    }

    // ── Edge case: short game bypasses accuracy (§7.1) ──────────────────────

    @Test
    fun `short game bypasses accuracy violation`() {
        val r = E.evaluate(input(acc = 40.0, short = true), emptyList(), NOW)
        assertTrue(r.accuracyIgnored)
        assertFalse(r.accViolation)
        assertFalse(r.isFalseSuccess)
        assertEquals(OutputState.CONTINUE_RATED, r.outputState)
        // ΔE is still calculated normally
        assertEquals(0.5, r.deltaE, 1e-9)
    }

    @Test
    fun `short game still flags blunder violations`() {
        val r = E.evaluate(
            input(acc = 40.0, short = true, blunders = 2, unforced = true),
            emptyList(), NOW
        )
        assertTrue(r.blunderViolation)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
    }

    // ── Rolling mean baseline (§7.3) ────────────────────────────────────────

    @Test
    fun `default baselines apply when no history exists`() {
        assertEquals(70.0, E.rollingMean(emptyList(), TimeControl.BULLET), 1e-9)
        assertEquals(75.0, E.rollingMean(emptyList(), TimeControl.BLITZ), 1e-9)
        assertEquals(80.0, E.rollingMean(emptyList(), TimeControl.RAPID), 1e-9)
        val r = E.evaluate(input(), emptyList(), NOW)
        assertTrue(r.usedDefaultMean)
        assertEquals(75.0, r.rollingMeanUsed, 1e-9)
    }

    @Test
    fun `rolling mean is used when history exists`() {
        val history = listOf(80.0, 76.0, 78.0, 74.0, 82.0, 79.0, 77.0, 81.0, 75.0, 78.0) // avg 78.0
        val r = E.evaluate(input(history = history), emptyList(), NOW)
        assertFalse(r.usedDefaultMean)
        assertEquals(78.0, r.rollingMeanUsed, 1e-9)
    }

    @Test
    fun `only the last 10 games form the rolling window`() {
        val history = listOf(100.0, 100.0, 100.0, 100.0, 100.0) + List(10) { 50.0 }
        assertEquals(50.0, E.rollingMean(history, TimeControl.BLITZ), 1e-9)
    }

    @Test
    fun `rolling mean shifts the violation boundary`() {
        // Mean 78 → 62.5 gives ΔA = 15.5 > 15 (would pass with default 75)
        val history = listOf(80.0, 76.0, 78.0, 74.0, 82.0, 79.0, 77.0, 81.0, 75.0, 78.0)
        val r = E.evaluate(input(acc = 62.5, history = history), emptyList(), NOW)
        assertTrue(r.accViolation)
        assertEquals(OutputState.PIVOT_TO_DRILLS, r.outputState)
    }

    // ── Time control parsing ────────────────────────────────────────────────

    @Test
    fun `unknown time control name falls back to blitz`() {
        assertEquals(TimeControl.BLITZ, TimeControl.fromNameOrBlitz("NOPE"))
        assertEquals(TimeControl.RAPID, TimeControl.fromNameOrBlitz("RAPID"))
        assertEquals(TimeControl.BLITZ, TimeControl.fromNameOrBlitz(null))
    }

    // ── Decision precedence ─────────────────────────────────────────────────

    @Test
    fun `session cap outranks everything`() {
        // Severe ΔE AND cap: the cap reason wins (checked first per §6)
        val r = E.evaluate(
            input(user = 1520, opp = 1000, result = GameResult.LOSS, mins = 75),
            session(OutputState.PIVOT_TO_DRILLS),
            NOW
        )
        assertTrue(r.sessionCapReached)
        assertEquals("60-MINUTE CAPACITY CEILING REACHED", r.reason)
    }
}
