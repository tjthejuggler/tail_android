package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import com.example.tail.widget.ChessReadinessV2Engine
import com.example.tail.widget.ChessReadinessV3Engine
import com.example.tail.widget.ChessReadinessV3Engine.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `rating-based target scales steeply and is clamped`() {
        assertEquals(8, ChessReadinessV3Engine.targetFromRating(812))    // floor
        assertEquals(11, ChessReadinessV3Engine.targetFromRating(926))
        assertEquals(17, ChessReadinessV3Engine.targetFromRating(1113))
        assertEquals(20, ChessReadinessV3Engine.targetFromRating(1200))
        assertEquals(28, ChessReadinessV3Engine.targetFromRating(1500))  // cap
        assertEquals(28, ChessReadinessV3Engine.targetFromRating(2200))  // still cap
    }

    @Test
    fun `effective pass target relaxes to personal percentile above the floor`() {
        val past = List(10) { 14 } // p70 = 14
        // Guaranteed 20, p70 14, floor ceil(20*0.6)=12 → pass at 14
        assertEquals(14, ChessReadinessV3Engine.effectivePassTarget(20, past))
        // Weak history (p70 = 6) can never drop below the floor
        assertEquals(12, ChessReadinessV3Engine.effectivePassTarget(20, List(10) { 6 }))
        // Strong history (p70 = 25) never exceeds the guaranteed target
        assertEquals(20, ChessReadinessV3Engine.effectivePassTarget(20, List(10) { 25 }))
        // No history → guaranteed target is the only bar
        assertEquals(20, ChessReadinessV3Engine.effectivePassTarget(20, listOf(1, 2)))
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
    fun `pass maps to green, strike to yellow, systemic failures to red`() {
        assertEquals(
            ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name,
            ChessReadinessV3Engine.stateNameFor(Verdict.PASS)
        )
        // A single-strike near-miss is YELLOW: casual play continues, only
        // rated play is locked.
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name,
            ChessReadinessV3Engine.stateNameFor(Verdict.FAIL_STRIKE)
        )
        for (v in listOf(Verdict.FAIL_REFLEX, Verdict.FAIL_TIMEOUT)) {
            assertEquals(
                ChessReadinessEngine.ReadinessState.RED_LIGHT.name,
                ChessReadinessV3Engine.stateNameFor(v)
            )
        }
    }

    @Test
    fun `synthetic ccrs feeds the shared rest ladder`() {
        assertEquals(85, ChessReadinessV3Engine.syntheticCcrs(Verdict.PASS))
        // Strike is a YELLOW near-miss: 65 keeps it in the standard
        // cool-down band, NOT the severe (< 40 → 120 min) rest ladder.
        assertEquals(65, ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_STRIKE))
        // Timeout sits at the 60-minute rung (40–59) of the shared ladder.
        assertEquals(40, ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_TIMEOUT))
        // Reflex failure is the harshest (120-min severe rung).
        assertEquals(20, ChessReadinessV3Engine.syntheticCcrs(Verdict.FAIL_REFLEX))
    }
}

    // ── Dual win: 70th-percentile personal target ─────────────────────────

    @Test
    fun `percentile target is null below the minimum sample count`() {
        assertNull(ChessReadinessV3Engine.percentileTarget((1..7).toList()))
    }

    @Test
    fun `percentile target activates at 8 samples`() {
        // 8 samples 1..8 → 70th percentile (nearest-rank, ceil(0.7*8)=6) = 6
        assertEquals(6, ChessReadinessV3Engine.percentileTarget((1..8).toList()))
    }

    @Test
    fun `percentile target uses only the last 30 results`() {
        val old = List(50) { 100 + it }   // huge stale values, must be ignored
        val recent = List(30) { 10 }      // 70th pct of 30×10 = 10
        assertEquals(10, ChessReadinessV3Engine.percentileTarget(old + recent))
    }

    @Test
    fun `percentile reached only at or above the target`() {
        val t = ChessReadinessV3Engine.percentileTarget((1..10).toList())
        assertEquals(7, t)
        assertFalse(ChessReadinessV3Engine.percentileReached(6, t))
        assertTrue(ChessReadinessV3Engine.percentileReached(7, t))
    }

    @Test
    fun `percentile reached is false when target is null`() {
        assertFalse(ChessReadinessV3Engine.percentileReached(50, null))
    }

    @Test
    fun `percentile win never terminates the run - absolute target still required`() {
        val pct = ChessReadinessV3Engine.percentileTarget(List(10) { 10 }) // 10
        val absolute = ChessReadinessV3Engine.targetScore(pb = 25)          // 15
        // At 10 solved: percentile secured but onPass(9, 15) is still false
        assertTrue(ChessReadinessV3Engine.percentileReached(10, pct))
        assertFalse(ChessReadinessV3Engine.onPass(9, absolute))
        // Only the absolute target terminates
        assertTrue(ChessReadinessV3Engine.onPass(14, absolute))
    }
