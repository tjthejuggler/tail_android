package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import com.example.tail.widget.ChessReadinessV2Engine
import com.example.tail.widget.ChessReadinessV3Engine
import com.example.tail.widget.ChessReadinessV3Engine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Chess Readiness V3 engine (reflex + puzzle rush
 * survival gate) — pure Kotlin, no Android dependencies.
 */
class ChessReadinessV3EngineTest {

    // ── Reflex (2-minute PVT-B) ────────────────────────────────────────────

    @Test
    fun `reflex duration is 2 minutes`() {
        assertEquals(2L * 60 * 1000, ChessReadinessV3Engine.REFLEX_DURATION_MS)
    }

    @Test
    fun `clean reflex run passes`() {
        assertTrue(ChessReadinessV3Engine.reflexPassed(lapses = 0, falseStarts = 0))
        assertTrue(ChessReadinessV3Engine.reflexPassed(lapses = 2, falseStarts = 2))
    }

    @Test
    fun `three lapses or three false starts fail the reflex`() {
        assertFalse(ChessReadinessV3Engine.reflexPassed(lapses = 3, falseStarts = 0))
        assertFalse(ChessReadinessV3Engine.reflexPassed(lapses = 0, falseStarts = 3))
        assertFalse(ChessReadinessV3Engine.reflexPassed(lapses = 7, falseStarts = 5))
    }

    @Test
    fun `summarizeReflex aggregates samples and verdict`() {
        val samples = listOf(
            ChessReadinessV2Engine.PvtSample(250),
            ChessReadinessV2Engine.PvtSample(300),
            ChessReadinessV2Engine.PvtSample(400),   // lapse
            ChessReadinessV2Engine.PvtSample(null),  // false start
            ChessReadinessV2Engine.PvtSample(280)
        )
        val s = ChessReadinessV3Engine.summarizeReflex(samples)
        assertEquals(1, s.lapses)
        assertEquals(1, s.falseStarts)
        assertTrue(s.passed)
        assertEquals(307.5, s.meanRtMs!!, 0.001)
    }

    // ── Dynamic target formula ─────────────────────────────────────────────

    @Test
    fun `target is sixty percent of the pb rounded`() {
        assertEquals(18, ChessReadinessV3Engine.targetScore(30))    // 18.0
        assertEquals(21, ChessReadinessV3Engine.targetScore(35))    // 21.0
        assertEquals(16, ChessReadinessV3Engine.targetScore(27))    // 16.2 → 16
        assertEquals(17, ChessReadinessV3Engine.targetScore(28))    // 16.8 → 17
    }

    @Test
    fun `missing pb falls back to the default`() {
        assertEquals(
            ChessReadinessV3Engine.targetScore(ChessReadinessV3Engine.DEFAULT_PB),
            ChessReadinessV3Engine.targetScore(0)
        )
    }

    @Test
    fun `tiny pb still asks the minimum target`() {
        assertEquals(ChessReadinessV3Engine.MIN_TARGET, ChessReadinessV3Engine.targetScore(1))
    }

    // ── Survival gate state machine ────────────────────────────────────────

    @Test
    fun `passing below target keeps the run going`() {
        assertFalse(ChessReadinessV3Engine.onPass(puzzlesPassed = 3, target = 18))
        assertFalse(ChessReadinessV3Engine.onPass(puzzlesPassed = 16, target = 18))
    }

    @Test
    fun `reaching the target passes the gate`() {
        assertTrue(ChessReadinessV3Engine.onPass(puzzlesPassed = 17, target = 18))
        assertTrue(ChessReadinessV3Engine.onPass(puzzlesPassed = 0, target = 1))
    }

    @Test
    fun `the global cap auto-fails at five minutes`() {
        assertTrue(ChessReadinessV3Engine.timedOut(5L * 60 * 1000))
        assertTrue(ChessReadinessV3Engine.timedOut(6L * 60 * 1000))
        assertFalse(ChessReadinessV3Engine.timedOut(5L * 60 * 1000 - 1))
    }

    // ── Verdict → shared v1 system mapping ─────────────────────────────────

    @Test
    fun `pass maps to green and failures to red`() {
        assertEquals(
            ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name,
            ChessReadinessV3Engine.stateNameFor(Verdict.PASS)
        )
        for (v in listOf(Verdict.FAIL_REFLEX, Verdict.FAIL_STRIKE, Verdict.FAIL_TIMEOUT)) {
            assertEquals(
                ChessReadinessEngine.ReadinessState.RED_LIGHT.name,
                ChessReadinessV3Engine.stateNameFor(v)
            )
        }
    }

    @Test
    fun `synthetic ccrs feeds the shared rest ladder`() {
        assertEquals(85, ChessReadinessV3Engine.syntheticCcrs(Verdict.PASS))
        // Reflex failure is the harshest, strike the canonical gate failure,
        // timeout slightly softer — all below the 120-minute bar (< 40).
        assertTrue(ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_STRIKE) < 40)
        // Timeout sits at the 60-minute rung (40–59) of the shared ladder.
        assertEquals(40, ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_TIMEOUT))
        assertTrue(
            ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_REFLEX) <
                ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_STRIKE)
        )
    }
}
