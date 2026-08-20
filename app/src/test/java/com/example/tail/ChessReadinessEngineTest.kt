package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 1 Pre-Session Diagnostic Engine (spec v3.0).
 * Covers fine-grained sub-score tiers, the adaptive percentile gate
 * (cold start, bar lowering, ceiling/floor clamps, window rules), the
 * strict absolute cutoffs, edge cases and rate limiting.
 */
class ChessReadinessEngineTest {

    private val NOW = 1_700_000_000_000L
    private val DAY = 24L * 60 * 60 * 1000
    private val HOUR = 60L * 60 * 1000

    private fun input(
        sleep: Int = 95,                       // → 25 pts
        clarityAvg: Double = 9.0,              // → 25 pts
        puzzleTimes: List<Int> = listOf(25, 28, 30), // avg ~27.7 s → 25 pts
        rush: Int = 30,
        ath: Int = 30,                         // ratio 1.0 → 25 pts
        strikes: Int = 0
    ) = ChessReadinessEngine.ReadinessInput(sleep, clarityAvg, puzzleTimes, rush, ath, strikes)

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
                sleep = 0,
                clarityAvg = 0.0,
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
    fun `fine-grained mid case yields 70 and YELLOW on cold start`() {
        // sleep 80→20, clarity 7.0→20, puzzles avg 100 s→9, rush 0.85→21 = 70
        val r = ChessReadinessEngine.evaluate(
            input(
                sleep = 80,
                clarityAvg = 7.0,
                puzzleTimes = listOf(90, 100, 110),
                rush = 34,
                ath = 40
            ),
            NOW
        )
        assertEquals(70, r.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.YELLOW_LIGHT, r.state)
    }

    // ── Sleep points (6 tiers from the raw Garmin score) ───────────────────

    @Test
    fun `sleep tier boundaries`() {
        val p = ChessReadinessEngine::sleepPoints
        assertEquals(25, p(100))
        assertEquals(25, p(85))
        assertEquals(20, p(84))
        assertEquals(20, p(75))
        assertEquals(15, p(74))
        assertEquals(15, p(65))
        assertEquals(10, p(64))
        assertEquals(10, p(55))
        assertEquals(5, p(54))
        assertEquals(5, p(45))
        assertEquals(0, p(44))
        assertEquals(0, p(0))
    }

    @Test
    fun `sleep score is clamped to 0-100`() {
        assertEquals(0, ChessReadinessEngine.sleepPoints(-5))
        assertEquals(25, ChessReadinessEngine.sleepPoints(120))
    }

    // ── Clarity points (6 tiers from the slider average) ───────────────────

    @Test
    fun `clarity tier boundaries`() {
        val p = ChessReadinessEngine::clarityPoints
        assertEquals(25, p(10.0))
        assertEquals(25, p(8.0))
        assertEquals(20, p(7.9))
        assertEquals(20, p(6.5))
        assertEquals(15, p(6.4))
        assertEquals(15, p(5.0))
        assertEquals(10, p(4.9))
        assertEquals(10, p(3.5))
        assertEquals(5, p(3.4))
        assertEquals(5, p(2.0))
        assertEquals(0, p(1.9))
        assertEquals(0, p(0.0))
    }

    // ── Rated puzzle scoring (7 speed tiers) ───────────────────────────────

    @Test
    fun `rated puzzle speed tiers`() {
        val p = ChessReadinessEngine::ratedPuzzleScore
        assertEquals(25, p(listOf(28, 29, 30)))   // avg 29 → 25
        assertEquals(21, p(listOf(30, 30, 30)))   // avg 30 → 21
        assertEquals(21, p(listOf(44, 44, 44)))
        assertEquals(17, p(listOf(45, 45, 45)))
        assertEquals(17, p(listOf(59, 59, 59)))
        assertEquals(13, p(listOf(60, 60, 60)))
        assertEquals(13, p(listOf(89, 89, 89)))
        assertEquals(9, p(listOf(90, 90, 90)))
        assertEquals(9, p(listOf(119, 119, 119)))
        assertEquals(4, p(listOf(120, 120, 120)))
        assertEquals(4, p(listOf(149, 149, 149)))
        assertEquals(0, p(listOf(150, 150, 150)))
        assertEquals(0, p(listOf(180, 180, 180)))
    }

    @Test
    fun `one failed puzzle among three lands in a middle tier`() {
        // 60 + 60 + 180(failed) → avg 100 → 9 pts
        assertEquals(9, ChessReadinessEngine.ratedPuzzleScore(listOf(60, 60, 180)))
    }

    @Test
    fun `empty puzzle list gives 0`() {
        assertEquals(0, ChessReadinessEngine.ratedPuzzleScore(emptyList()))
    }

    // ── Puzzle Rush bands (6 ratio bands, 3 pts per strike) ────────────────

    @Test
    fun `rush ratio bands`() {
        val p = ChessReadinessEngine::rushScore
        assertEquals(25, p(50, 50, 0)) // 1.00
        assertEquals(25, p(45, 50, 0)) // 0.90
        assertEquals(21, p(40, 50, 0)) // 0.80
        assertEquals(17, p(35, 50, 0)) // 0.70
        assertEquals(13, p(30, 50, 0)) // 0.60
        assertEquals(8, p(25, 50, 0))  // 0.50
        assertEquals(4, p(20, 50, 0))  // 0.40
        assertEquals(0, p(19, 50, 0))  // 0.38
    }

    @Test
    fun `each strike deducts 3 points from the band`() {
        assertEquals(22, ChessReadinessEngine.rushScore(50, 50, 1))
        assertEquals(19, ChessReadinessEngine.rushScore(50, 50, 2))
        assertEquals(16, ChessReadinessEngine.rushScore(50, 50, 3))
        assertEquals(10, ChessReadinessEngine.rushScore(30, 50, 1)) // 13 − 3
    }

    @Test
    fun `strike penalty floors at zero`() {
        assertEquals(0, ChessReadinessEngine.rushScore(20, 50, 2)) // 4 − 6 → 0
        assertEquals(0, ChessReadinessEngine.rushScore(19, 50, 3)) // 0-band
    }

    @Test
    fun `cold start floor of 10 protects new accounts`() {
        // ATH = 2 (below floor) → baseline becomes 10; 8/10 = 0.8 → 21 pts
        assertEquals(21, ChessReadinessEngine.rushScore(8, 2, 0))
    }

    // ── Percentile helper ──────────────────────────────────────────────────

    @Test
    fun `percentile of empty list is zero`() {
        assertEquals(0.0, ChessReadinessEngine.percentileOf(emptyList(), 0.6), 1e-9)
    }

    @Test
    fun `percentile of single value is that value`() {
        assertEquals(42.0, ChessReadinessEngine.percentileOf(listOf(42), 0.35), 1e-9)
    }

    @Test
    fun `percentile interpolates linearly`() {
        val v = listOf(10, 20, 30, 40, 50) // already ascending
        assertEquals(10.0, ChessReadinessEngine.percentileOf(v, 0.0), 1e-9)
        assertEquals(50.0, ChessReadinessEngine.percentileOf(v, 1.0), 1e-9)
        assertEquals(30.0, ChessReadinessEngine.percentileOf(v, 0.5), 1e-9)
        // rank 2.4 → 30 + 0.4 × (40 − 30) = 34
        assertEquals(34.0, ChessReadinessEngine.percentileOf(v, 0.6), 1e-9)
        // order-insensitive
        assertEquals(34.0, ChessReadinessEngine.percentileOf(v.reversed(), 0.6), 1e-9)
    }

    // ── Adaptive thresholds ────────────────────────────────────────────────

    /** [count] tests ending at NOW, each one day apart, cycling [scores]. */
    private fun historyOf(vararg scores: Int, daysAgo: Int = 0): List<ChessReadinessEngine.ReadinessTest> =
        scores.mapIndexed { i, s ->
            ChessReadinessEngine.ReadinessTest(
                timestamp = NOW - (daysAgo + scores.size - 1 - i) * DAY,
                ccrs = s,
                state = "X"
            )
        }

    @Test
    fun `empty history uses cold start thresholds`() {
        val t = ChessReadinessEngine.computeThresholds(emptyList(), NOW)
        assertEquals(ChessReadinessEngine.COLD_START_GREEN, t.green)
        assertEquals(ChessReadinessEngine.COLD_START_YELLOW, t.yellow)
        assertEquals(ChessReadinessEngine.ThresholdBasis.COLD_START, t.basis)
        assertEquals(0, t.sampleSize)
    }

    @Test
    fun `fewer than five recent tests still uses cold start`() {
        val t = ChessReadinessEngine.computeThresholds(historyOf(50, 60, 70, 80), NOW)
        assertEquals(ChessReadinessEngine.ThresholdBasis.COLD_START, t.basis)
        assertEquals(4, t.sampleSize)
    }

    @Test
    fun `five recent tests switch to percentiles`() {
        // sorted [50,55,60,65,70]: p60 rank 2.4 → 62 (≤ 80, unclamped) ·
        // p35 rank 1.4 → 57 → ceiling-clamped to ABSOLUTE_YELLOW (55)
        val t = ChessReadinessEngine.computeThresholds(historyOf(50, 55, 60, 65, 70), NOW)
        assertEquals(62, t.green)
        assertEquals(ChessReadinessEngine.ABSOLUTE_YELLOW, t.yellow)
        assertEquals(ChessReadinessEngine.ThresholdBasis.PERCENTILE, t.basis)
        assertEquals(5, t.sampleSize)
    }

    @Test
    fun `weak recent history lowers the bar below cold start`() {
        // sorted [40,45,50,50,55]: p60 → 50 · p35 → 47
        val t = ChessReadinessEngine.computeThresholds(historyOf(40, 45, 50, 50, 55), NOW)
        assertEquals(50, t.green)
        assertEquals(47, t.yellow)
        assertTrue(t.green < ChessReadinessEngine.COLD_START_GREEN)
    }

    @Test
    fun `strong history cannot ratchet the bar above the absolute cutoffs`() {
        // sorted [85,90,95,100,100]: p60 → 97 (clamped to 80) · p35 → 92 (clamped to 55)
        val t = ChessReadinessEngine.computeThresholds(historyOf(85, 90, 95, 100, 100), NOW)
        assertEquals(ChessReadinessEngine.ABSOLUTE_GREEN, t.green)
        assertEquals(ChessReadinessEngine.ABSOLUTE_YELLOW, t.yellow)
    }

    @Test
    fun `very weak history sinks only to the floors`() {
        // sorted [20,25,30,30,35]: p60 → 30 (floored to 45) · p35 → 27 (floored to 30)
        val t = ChessReadinessEngine.computeThresholds(historyOf(20, 25, 30, 30, 35), NOW)
        assertEquals(ChessReadinessEngine.GREEN_FLOOR, t.green)
        assertEquals(ChessReadinessEngine.YELLOW_FLOOR, t.yellow)
    }

    @Test
    fun `tests older than the window are excluded`() {
        // 3 tests 30 days old + 2 recent → only 2 in window → cold start
        val old = historyOf(10, 20, 30, daysAgo = 30)
        val recent = historyOf(60, 65)
        val t = ChessReadinessEngine.computeThresholds(old + recent, NOW)
        assertEquals(ChessReadinessEngine.ThresholdBasis.COLD_START, t.basis)
        assertEquals(2, t.sampleSize)
    }

    @Test
    fun `future tests are excluded from the window`() {
        val future = listOf(
            ChessReadinessEngine.ReadinessTest(NOW + DAY, 100, "X")
        )
        val t = ChessReadinessEngine.computeThresholds(future, NOW)
        assertEquals(ChessReadinessEngine.ThresholdBasis.COLD_START, t.basis)
    }

    @Test
    fun `window is capped at the last 15 tests`() {
        val scores = IntArray(20) { 51 + it } // 51..70, all recent
        val t = ChessReadinessEngine.computeThresholds(historyOf(*scores), NOW)
        assertEquals(15, t.sampleSize)
        // The newest 15 are 56..70 — p60 of those: sorted 56..70,
        // rank 0.6×14 = 8.4 → 64 + 0.4×1 = 64.4 → 64
        assertEquals(64, t.green)
    }

    // ── Gate decision (stateFor / evaluate with history) ───────────────────

    @Test
    fun `strict cutoff - score 80 always passes however strong the history`() {
        val strong = historyOf(85, 90, 95, 100, 100) // green bar clamped to 80
        val r80 = ChessReadinessEngine.evaluate(
            input(sleep = 85, clarityAvg = 7.0, puzzleTimes = listOf(50, 50, 50), rush = 40, ath = 50, strikes = 1),
            NOW, strong
        ) // 25 + 20 + 17 + 18 = 80
        assertEquals(80, r80.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, r80.state)
    }

    @Test
    fun `below the strict cutoff on strong history is not green`() {
        val strong = historyOf(85, 90, 95, 100, 100) // green bar 80
        val r79 = ChessReadinessEngine.evaluate(
            input(sleep = 85, clarityAvg = 7.0, puzzleTimes = listOf(50, 50, 50), rush = 35, ath = 50),
            NOW, strong
        ) // 25 + 20 + 17 + 17 = 79
        assertEquals(79, r79.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.YELLOW_LIGHT, r79.state)
    }

    @Test
    fun `relatively good score passes on weak history`() {
        // Bar lowered to green 50 by weak history — a 60 goes GREEN
        val weak = historyOf(40, 45, 50, 50, 55)
        val r = ChessReadinessEngine.evaluate(
            input(sleep = 65, clarityAvg = 5.0, puzzleTimes = listOf(80, 80, 80), rush = 35, ath = 50),
            NOW, weak
        ) // 15 + 15 + 13 + 17 = 60
        assertEquals(60, r.ccrs)
        assertEquals(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, r.state)
        assertEquals(50, r.greenThreshold)
        assertEquals(47, r.yellowThreshold)
        assertEquals(5, r.thresholdSampleSize)
    }

    @Test
    fun `same 60 is only yellow on cold start defaults`() {
        val r = ChessReadinessEngine.evaluate(
            input(sleep = 65, clarityAvg = 5.0, puzzleTimes = listOf(80, 80, 80), rush = 35, ath = 50),
            NOW
        )
        assertEquals(ChessReadinessEngine.ReadinessState.YELLOW_LIGHT, r.state)
        assertEquals(ChessReadinessEngine.COLD_START_GREEN, r.greenThreshold)
        assertEquals(ChessReadinessEngine.ThresholdBasis.COLD_START, r.thresholdBasis)
    }

    @Test
    fun `fromScore keeps the cold start mapping`() {
        assertEquals(
            ChessReadinessEngine.ReadinessState.GREEN_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(75)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(74)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.YELLOW_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(55)
        )
        assertEquals(
            ChessReadinessEngine.ReadinessState.RED_LIGHT,
            ChessReadinessEngine.ReadinessState.fromScore(54)
        )
    }

    // ── Rate limiting ──────────────────────────────────────────────────────

    private fun test(ts: Long, ccrs: Int, state: String = "X") =
        ChessReadinessEngine.ReadinessTest(ts, ccrs, state)

    @Test
    fun `no history allows test`() {
        val status = ChessReadinessEngine.checkGate(emptyList(), NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Allowed)
        assertEquals(0, (status as ChessReadinessEngine.GateStatus.Allowed).testsToday)
    }

    @Test
    fun `green state result enforces 60 minute cooldown`() {
        val history = listOf(
            test(NOW - 30L * 60 * 1000, 62, ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name)
        ) // 30 min ago, dynamically GREEN
        val status = ChessReadinessEngine.checkGate(history, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.CooldownActive
        )
        // retryAt = last test + 60 min → 30 min from NOW
        assertEquals(NOW + 30L * 60 * 1000, status.error.retryAt)
    }

    @Test
    fun `legacy record without state falls back to score heuristic`() {
        // Blank/"X" state with ccrs ≥ 70 → treated as passed → cooldown path
        val legacy = listOf(test(NOW - 30L * 60 * 1000, 85))
        val status = ChessReadinessEngine.checkGate(legacy, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.CooldownActive
        )
        // Blank/"X" state with ccrs < 70 → rest path
        val legacyFail = listOf(test(NOW - 20L * 60 * 1000, 50))
        val status2 = ChessReadinessEngine.checkGate(legacyFail, NOW)
        assertTrue(status2 is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status2 as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.RestPeriodActive
        )
    }

    @Test
    fun `cooldown expires after 60 minutes`() {
        val history = listOf(
            test(NOW - 61L * 60 * 1000, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name)
        )
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    @Test
    fun `red state result enforces score-scaled recovery lock`() {
        val history = listOf(
            test(NOW - 20L * 60 * 1000, 40, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        )
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
        val history = listOf(
            test(NOW - 61L * 60 * 1000, 40, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        )
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
        val marginal = listOf(
            test(NOW - 31L * 60 * 1000, 65, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        ) // 30-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(marginal, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
        val poor = listOf(
            test(NOW - 31L * 60 * 1000, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        ) // 60-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(poor, NOW)
                is ChessReadinessEngine.GateStatus.Blocked
        )
    }

    @Test
    fun `severe fail stays locked for two hours`() {
        val history = listOf(
            test(NOW - 90L * 60 * 1000, 10, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        ) // 120-min tier
        assertTrue(
            ChessReadinessEngine.checkGate(history, NOW)
                is ChessReadinessEngine.GateStatus.Blocked
        )
        val expired = listOf(
            test(NOW - 121L * 60 * 1000, 10, ChessReadinessEngine.ReadinessState.RED_LIGHT.name)
        )
        assertTrue(
            ChessReadinessEngine.checkGate(expired, NOW)
                is ChessReadinessEngine.GateStatus.Allowed
        )
    }

    @Test
    fun `eight tests in 24 hours blocks the ninth`() {
        val history = (1..8).map {
            test(NOW - it * 60L * 60 * 1000, 90, ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name)
        }
        val status = ChessReadinessEngine.checkGate(history, NOW)
        assertTrue(status is ChessReadinessEngine.GateStatus.Blocked)
        assertTrue(
            (status as ChessReadinessEngine.GateStatus.Blocked).error
                is ChessReadinessEngine.GateError.MaxDailyTests
        )
        // retryAt = oldest test in the window + 24 h → NOW - 8 h + 24 h
        assertEquals(NOW + 16L * 60 * 60 * 1000, status.error.retryAt)
    }

    @Test
    fun `tests older than 24 hours do not count toward the cap`() {
        val history = listOf(
            test(NOW - 25L * 60 * 60 * 1000, 90),
            test(NOW - 26L * 60 * 60 * 1000, 90),
            test(NOW - 27L * 60 * 60 * 1000, 90),
            test(NOW - 28L * 60 * 60 * 1000, 90),
            test(NOW - 29L * 60 * 60 * 1000, 90),
            test(NOW - 30L * 60 * 60 * 1000, 90),
            test(NOW - 31L * 60 * 60 * 1000, 90),
            test(NOW - 32L * 60 * 60 * 1000, 90)
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
    fun `slider mapping feeds the point tiers correctly`() {
        // (5, 5, 2) → calm 10 + focus 10 + energy 2.5 = 7.5 — 20-pt tier
        assertEquals(7.5, ChessReadinessEngine.clarityAverageFromSliders(5, 5, 2), 1e-9)
        assertEquals(
            20,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(5, 5, 2)
            )
        )
        assertEquals(
            15,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(3, 3, 3)
            )
        )
        assertEquals(
            0,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(1, 1, 1)
            )
        )
    }
}
