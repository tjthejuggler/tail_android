package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 1 Pre-Session Diagnostic Engine (spec v2.1).
 * Covers slider-derived clarity, multi-puzzle cold-start scoring, the
 * graduated strike penalty, edge cases and rate limiting.
 */
class ChessReadinessEngineTest {

    private val NOW = 1_700_000_000_000L

    private fun input(
        sleep: ChessReadinessEngine.SleepTier = ChessReadinessEngine.SleepTier.TIER_1,
        clarity: ChessReadinessEngine.ClarityTier = ChessReadinessEngine.ClarityTier.TIER_1,
        puzzleTimes: List<Int> = listOf(30, 40, 50), // avg 40 s → 25 pts
        rush: Int = 30,
        ath: Int = 30,
        strikes: Int = 0
    ) = ChessReadinessEngine.ReadinessInput(sleep, clarity, puzzleTimes, rush, ath, strikes)

    // ── Composite scoring ──────────────────────────────────────────────────

    @Test
    fun `perfect inputs yield 100 and GREEN`() {
        val r = ChessReadinessEngine.evaluate(input(), NOW)
        assertEquals(100, r.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, r.state)
        assertEquals(25, r.sSleep)
        assertEquals(25, r.sClarity)
        assertEquals(25, r.pPuzzle)
        assertEquals(25, r.pRush)
        assertEquals(NOW + ChessReadinessEngine.SESSION_VALIDITY_MS, r.validUntil)
    }

    @Test
    fun `worst inputs yield 0 and RED`() {
        val r = ChessReadinessEngine.evaluate(
            input(
                sleep = ChessReadinessEngine.SleepTier.TIER_3,
                clarity = ChessReadinessEngine.ClarityTier.TIER_3,
                puzzleTimes = listOf(180, 180, 180),
                rush = 0,
                strikes = 3
            ),
            NOW
        )
        assertEquals(0, r.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.RED_LIGHT, r.state)
    }

    @Test
    fun `mixed tiers yield 90 exactly GREEN`() {
        // sleep T1(25) + clarity T2(15) + puzzles fast(25) + rush 80%(25) = 90
        val r = ChessReadinessEngine.evaluate(
            input(clarity = ChessReadinessEngine.ClarityTier.TIER_2, rush = 80, ath = 100),
            NOW
        )
        assertEquals(90, r.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, r.state)
    }

    // ── Slider-derived clarity ─────────────────────────────────────────────

    @Test
    fun `clarity average boundaries map to tiers`() {
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_1,
            ChessReadinessEngine.clarityTierFromAverage(7.5)
        )
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_2,
            ChessReadinessEngine.clarityTierFromAverage(7.4)
        )
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_2,
            ChessReadinessEngine.clarityTierFromAverage(5.0)
        )
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_3,
            ChessReadinessEngine.clarityTierFromAverage(4.9)
        )
    }

    @Test
    fun `four strong sliders average to tier 1`() {
        // 8, 8, 7, 8 → avg 7.75 → Tier 1
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_1,
            ChessReadinessEngine.clarityTierFromAverage(listOf(8, 8, 7, 8).average())
        )
    }

    // ── Rated puzzle scoring ───────────────────────────────────────────────

    @Test
    fun `rated puzzle average under 45 seconds gives 25`() {
        assertEquals(25, ChessReadinessEngine.ratedPuzzleScore(listOf(30, 40, 50)))
        assertEquals(25, ChessReadinessEngine.ratedPuzzleScore(listOf(44, 44, 44)))
    }

    @Test
    fun `rated puzzle average 45 to 119 seconds gives 15`() {
        assertEquals(15, ChessReadinessEngine.ratedPuzzleScore(listOf(45, 45, 45)))
        assertEquals(15, ChessReadinessEngine.ratedPuzzleScore(listOf(119, 119, 119)))
        assertEquals(15, ChessReadinessEngine.ratedPuzzleScore(listOf(100, 100, 100)))
    }

    @Test
    fun `rated puzzle average 120 or more gives 0`() {
        assertEquals(0, ChessReadinessEngine.ratedPuzzleScore(listOf(120, 120, 120)))
        assertEquals(0, ChessReadinessEngine.ratedPuzzleScore(listOf(180, 180, 180)))
    }

    @Test
    fun `one failed puzzle among three still allows 15`() {
        // 60 + 60 + 180(failed) → avg 100 → 15 pts
        assertEquals(15, ChessReadinessEngine.ratedPuzzleScore(listOf(60, 60, 180)))
    }

    @Test
    fun `empty puzzle list gives 0`() {
        assertEquals(0, ChessReadinessEngine.ratedPuzzleScore(emptyList()))
    }

    // ── Puzzle Rush bands ──────────────────────────────────────────────────

    @Test
    fun `rush ratio at or above 80 percent gives 25`() {
        assertEquals(25, ChessReadinessEngine.rushScore(40, 50, 0))
        assertEquals(25, ChessReadinessEngine.rushScore(50, 50, 0))
    }

    @Test
    fun `rush ratio 65 to 79 percent gives 15`() {
        assertEquals(15, ChessReadinessEngine.rushScore(33, 50, 0))
        assertEquals(15, ChessReadinessEngine.rushScore(39, 50, 0))
    }

    @Test
    fun `rush ratio below 65 percent gives 0`() {
        assertEquals(0, ChessReadinessEngine.rushScore(32, 50, 0))
    }

    // ── Graduated strike penalty (v2.1 — no more instant zero) ─────────────

    @Test
    fun `each strike deducts 5 points from the band`() {
        // 22/25 = 88 % → band 25; 3 strikes → 25 − 15 = 10 (was 0 in v2.0)
        assertEquals(10, ChessReadinessEngine.rushScore(22, 25, 3))
        assertEquals(20, ChessReadinessEngine.rushScore(40, 50, 1))
        assertEquals(15, ChessReadinessEngine.rushScore(40, 50, 2))
    }

    @Test
    fun `strike penalty floors at zero`() {
        // 15-band with 3 strikes → 15 − 15 = 0
        assertEquals(0, ChessReadinessEngine.rushScore(33, 50, 3))
        // 0-band stays 0
        assertEquals(0, ChessReadinessEngine.rushScore(32, 50, 3))
    }

    @Test
    fun `cold start floor of 10 protects new accounts`() {
        // ATH = 2 (below floor) → baseline becomes 10; 8/10 = 0.8 → 25 pts
        assertEquals(25, ChessReadinessEngine.rushScore(8, 2, 0))
    }

    // ── State boundaries ───────────────────────────────────────────────────

    @Test
    fun `state boundaries at 70 and 85`() {
        assertEquals(
            ChessReadinessEngine.ReadinessState.GREEN_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(85)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(84)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(70)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.RED_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(69)
        )
    }

    // ── Garmin sleep score mapping ─────────────────────────────────────────

    @Test
    fun `garmin sleep score maps to tiers`() {
        assertEquals(
            ChessReadinessEngine.SleepTier.TIER_1,
            ChessReadinessEngine.sleepTierFromGarminScore(80)
        )
        assertEquals(
            ChessReadinessEngine.SleepTier.TIER_2,
            ChessReadinessEngine.sleepTierFromGarminScore(79)
        )
        assertEquals(
            ChessReadinessEngine.SleepTier.TIER_2,
            ChessReadinessEngine.sleepTierFromGarminScore(60)
        )
        assertEquals(
            ChessReadinessEngine.SleepTier.TIER_3,
            ChessReadinessEngine.sleepTierFromGarminScore(59)
        )
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    private fun test(ts: Long, ccrs: Int) =
        ChessReadinessEngine.ReadinessTest(ts, ccrs, "X")

    @Test
    fun `no history allows test`() {
        val status = ChessReadinessEngine.checkGate(emptyList(), NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Allowed)
        assertEquals(0, (status as ChessReadinessEngine.GateStatus.Allowed).testsToday)
    }

    @Test
    fun `green or yellow result enforces 60 minute cooldown`() {
        val history = listOf(test(NOW - 30L * 60 * 1000, 85)) // 30 min ago, GREEN
        val status = ChessReadinessEngine.checkGate(history, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.CooldownActive
        )
        // retryAt = last test + 60 min → 30 min from NOW
        assertEquals(
            NOW + 30L * 60 * 1000,
            status.error.retryAt
        )
    }

    @Test
    fun `cooldown expires after 60 minutes`() {
        val history = listOf(test(NOW - 61L * 60 * 1000, 85))
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    @Test
    fun `red result enforces score-scaled recovery lock`() {
        val history = listOf(test(NOW - 20L * 60 * 1000, 40)) // 20 min ago, RED
        val status = ChessReadinessEngine.checkGate(history, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.RestPeriodActive
        )
    }

    @Test
    fun `poor fail lock expires after 60 minutes`() {
        // ccrs 40 sits in the 60-minute tier
        val history = listOf(test(NOW - 61L * 60 * 1000, 40))
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    @Test
    fun `recovery lock scales with how poor the failed attempt was`() {
        val E = ChessReadinessEngine
        // Pass — no rest lock (the pass cooldown path applies instead)
        assertEquals(0L, E.restPeriodForScore(70))
        assertEquals(0L, E.restPeriodForScore(100))
        // Marginal fail (60–69) → 30 min
        assertEquals(E.REST_MS_MARGINAL, E.restPeriodForScore(69))
        assertEquals(E.REST_MS_MARGINAL, E.restPeriodForScore(60))
        // Poor fail (40–59) → 60 min
        assertEquals(E.REST_MS_POOR, E.restPeriodForScore(59))
        assertEquals(E.REST_MS_POOR, E.restPeriodForScore(40))
        // Severe fail (< 40) → 120 min
        assertEquals(E.REST_MS_SEVERE, E.restPeriodForScore(39))
        assertEquals(E.REST_MS_SEVERE, E.restPeriodForScore(0))
    }

    @Test
    fun `marginal fail re-test allowed after 31 minutes but poor fail still locked`() {
        val marginal = listOf(test(NOW - 31L * 60 * 1000, 65)) // 30-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(marginal, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
        val poor = listOf(test(NOW - 31L * 60 * 1000, 50)) // 60-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(poor, NOW)
                is ChessReadinessEngine.GateStatus.Blocked
        )
    }

    @Test
    fun `severe fail stays locked for two hours`() {
        val history = listOf(test(NOW - 90L * 60 * 1000, 10)) // 120-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Blocked
        )
        val expired = listOf(test(NOW - 121L * 60 * 1000, 10))
        assertTrue(
            ChessReadinessEngine.checkGate(expired, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    @Test
    fun `four tests in 24 hours blocks the fifth`() {
        val history = (1..4).map { test(NOW - it * 60L * 60 * 1000, 90) }
        val status = ChessReadinessEngine.checkGate(history, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.MaxDailyTests
        )
        // retryAt = oldest test in the window + 24 h → NOW - 4 h + 24 h
        assertEquals(
            NOW + 20L * 60 * 60 * 1000,
            status.error.retryAt
        )
    }

    @Test
    fun `tests older than 24 hours do not count toward the cap`() {
        val history = listOf(
            test(NOW - 25L * 60 * 60 * 1000, 90),
            test(NOW - 26L * 60 * 60 * 1000, 90),
            test(NOW - 27L * 60 * 60 * 1000, 90),
            test(NOW - 28L * 60 * 60 * 1000, 90)
        )
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    // ── All-time-high maintenance ──────────────────────────────────────────

    @Test
    fun `all-time high only rises when a run beats it`() {
        // Below the stored best → unchanged
        assertEquals(37, ChessReadinessEngine.nextAllTimeHigh(37, 20))
        // Equal to the stored best → unchanged
        assertEquals(37, ChessReadinessEngine.nextAllTimeHigh(37, 37))
        // Above the stored best → raised
        assertEquals(41, ChessReadinessEngine.nextAllTimeHigh(37, 41))
    }

    @Test
    fun `all-time high ignores negative submissions`() {
        assertEquals(12, ChessReadinessEngine.nextAllTimeHigh(12, -5))
        assertEquals(0, ChessReadinessEngine.nextAllTimeHigh(0, -1))
    }

    // ── 1–5 clarity sliders (stress / focus / energy, positive end = 5) ────

    @Test
    fun `best slider answers map to a 10 clarity average`() {
        assertEquals(10.0, ChessReadinessEngine.clarityAverageFromSliders(5, 5, 5), 1e-9)
    }

    @Test
    fun `worst slider answers map to a 0 clarity average`() {
        assertEquals(0.0, ChessReadinessEngine.clarityAverageFromSliders(1, 1, 1), 1e-9)
    }

    @Test
    fun `mid slider answers map to a 5 clarity average`() {
        assertEquals(5.0, ChessReadinessEngine.clarityAverageFromSliders(3, 3, 3), 1e-9)
    }

    @Test
    fun `stress slider works like the others - calm at 5 scores highest`() {
        val calm = ChessReadinessEngine.clarityAverageFromSliders(5, 3, 3)
        val stressed = ChessReadinessEngine.clarityAverageFromSliders(1, 3, 3)
        assertEquals(20.0 / 3.0, calm, 1e-9)
        assertEquals(10.0 / 3.0, stressed, 1e-9)
    }

    @Test
    fun `slider inputs outside 1-5 are clamped`() {
        // stress 0 → 1 (stressed), focus 9 → 5 (max), energy 3 → mid
        assertEquals(5.0, ChessReadinessEngine.clarityAverageFromSliders(0, 9, 3), 1e-9)
    }

    @Test
    fun `slider mapping feeds the tier thresholds correctly`() {
        // (5, 5, 2) → calm 10 + focus 10 + energy 2.5 = 7.5 — Tier 1 boundary
        assertEquals(7.5, ChessReadinessEngine.clarityAverageFromSliders(5, 5, 2), 1e-9)
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_1,
            ChessReadinessEngine.clarityTierFromAverage(
                ChessReadinessEngine.clarityAverageFromSliders(5, 5, 2)
            )
        )
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_2,
            ChessReadinessEngine.clarityTierFromAverage(
                ChessReadinessEngine.clarityAverageFromSliders(3, 3, 3)
            )
        )
        assertEquals(
            ChessReadinessEngine.ClarityTier.TIER_3,
            ChessReadinessEngine.clarityTierFromAverage(
                ChessReadinessEngine.clarityAverageFromSliders(1, 1, 1)
            )
        )
    }
}
