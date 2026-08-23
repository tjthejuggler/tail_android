package com.example.tail

import com.example.tail.widget.ChessReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Phase 1 Pre-Session Diagnostic Engine (spec v3.1).
 * Covers fine-grained sub-score tiers, the adaptive percentile gate
 * (cold start, bar lowering, ceiling/floor clamps, window rules), the
 * strict absolute cutoffs, edge cases, rate limiting, the form-relative
 * objective baselines, the 10-point survey mapping and the
 * survey-calibration weighting.
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
        val p = { times: List<Int> -> ChessReadinessEngine.ratedPuzzleScore(times) }
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
        val p = { s: Int, ath: Int, st: Int -> ChessReadinessEngine.rushScore(s, ath, st) }
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
        // sorted [50,55,60,65,70]: p70 rank 2.8 → 60 + 0.8×5 = 64 (≤ 80,
        // unclamped) · p45 rank 1.8 → 59, clamped down to ABSOLUTE_YELLOW 55
        val t = ChessReadinessEngine.computeThresholds(historyOf(50, 55, 60, 65, 70), NOW)
        assertEquals(64, t.green)
        assertEquals(55, t.yellow)
        assertEquals(ChessReadinessEngine.ThresholdBasis.PERCENTILE, t.basis)
        assertEquals(5, t.sampleSize)
    }

    @Test
    fun `weak recent history lowers the bar below cold start`() {
        // sorted [40,45,50,50,55]: p70 rank 2.8 → 50 (idx 2/3 both 50) ·
        // p45 rank 1.8 → 45 + 0.8×5 = 49
        val t = ChessReadinessEngine.computeThresholds(historyOf(40, 45, 50, 50, 55), NOW)
        assertEquals(50, t.green)
        assertEquals(49, t.yellow)
        assertTrue(t.green < ChessReadinessEngine.COLD_START_GREEN)
    }

    @Test
    fun `strong history cannot ratchet the bar above the absolute cutoffs`() {
        // sorted [85,90,95,100,100]: p70 → 99 (clamped to 80) · p45 → 94 (clamped to 55)
        val t = ChessReadinessEngine.computeThresholds(historyOf(85, 90, 95, 100, 100), NOW)
        assertEquals(ChessReadinessEngine.ABSOLUTE_GREEN, t.green)
        assertEquals(ChessReadinessEngine.ABSOLUTE_YELLOW, t.yellow)
    }

    @Test
    fun `very weak history sinks only to the floors`() {
        // sorted [20,25,30,30,35]: p70 → 30 (floored to 45) · p45 → 29 (floored to 30)
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
        // The newest 15 are 56..70 — p70 of those: sorted 56..70,
        // rank 0.7×14 = 9.8 → 65 + 0.8×1 = 65.8 → 65
        assertEquals(65, t.green)
    }

    @Test
    fun `green target is user-adjustable and shifts the bars`() {
        val history = historyOf(50, 55, 60, 65, 70)
        // Default 30% target → 70th-percentile green (64); 45th → 59
        // clamped to the absolute Yellow cutoff (55).
        val strict = ChessReadinessEngine.computeThresholds(history, NOW)
        assertEquals(64, strict.green)
        assertEquals(55, strict.yellow)
        // A lenient 70% target → 30th-percentile green (56); 5th → 51.
        val lenient = ChessReadinessEngine.computeThresholds(
            history, NOW, greenTargetFraction = 0.70
        )
        assertEquals(56, lenient.green)
        assertEquals(51, lenient.yellow)
    }

    @Test
    fun `target fraction is clamped to the allowed range`() {
        assertEquals(0.05, ChessReadinessEngine.greenPercentileFor(0.99), 0.001)
        assertEquals(0.95, ChessReadinessEngine.greenPercentileFor(0.01), 0.001)
        assertEquals(0.70, ChessReadinessEngine.greenPercentileFor(0.30), 0.001)
        assertEquals(0.05, ChessReadinessEngine.greenPercentileFor(0.95), 0.001)
        // Yellow always trails Green by 25 percentile points (floored at 0.02).
        assertEquals(0.45, ChessReadinessEngine.yellowPercentileFor(0.30), 0.001)
        assertEquals(0.05, ChessReadinessEngine.yellowPercentileFor(0.70), 0.001)
        assertEquals(0.02, ChessReadinessEngine.yellowPercentileFor(0.95), 0.001)
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
        assertEquals(49, r.yellowThreshold)
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

    // ── 1–10 clarity sliders (stress / focus / energy, positive end = 10) ──

    @Test
    fun `best slider answers map to a 10 clarity average`() {
        assertEquals(10.0, ChessReadinessEngine.clarityAverageFromSliders(10, 10, 10), 1e-9)
    }

    @Test
    fun `worst slider answers map to a 0 clarity average`() {
        assertEquals(0.0, ChessReadinessEngine.clarityAverageFromSliders(1, 1, 1), 1e-9)
    }

    @Test
    fun `slider answers map monotonically with exact endpoints`() {
        // (v − 1) · 10/9: 5 → 40/9, 6 → 50/9 — the two middle positions
        assertEquals(40.0 / 9.0, ChessReadinessEngine.clarityAverageFromSliders(5, 5, 5), 1e-9)
        assertEquals(50.0 / 9.0, ChessReadinessEngine.clarityAverageFromSliders(6, 6, 6), 1e-9)
    }

    @Test
    fun `stress slider works like the others - calm at 10 scores highest`() {
        val calm = ChessReadinessEngine.clarityAverageFromSliders(10, 3, 3)
        val stressed = ChessReadinessEngine.clarityAverageFromSliders(1, 3, 3)
        assertEquals(130.0 / 27.0, calm, 1e-9)
        assertEquals(40.0 / 27.0, stressed, 1e-9)
    }

    @Test
    fun `slider inputs outside 1-10 are clamped`() {
        // stress 0 → 1 (stressed → 0), focus 11 → 10 (max → 10), energy 5 → 40/9
        assertEquals(130.0 / 27.0, ChessReadinessEngine.clarityAverageFromSliders(0, 11, 5), 1e-9)
    }

    @Test
    fun `slider mapping feeds the point tiers correctly`() {
        // (10, 10, 2) → calm 10 + focus 10 + energy 10/9 = 190/27 ≈ 7.04 — 20-pt tier
        assertEquals(190.0 / 27.0, ChessReadinessEngine.clarityAverageFromSliders(10, 10, 2), 1e-9)
        assertEquals(
            20,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(10, 10, 2)
            )
        )
        assertEquals(
            15,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(6, 6, 6)
            )
        )
        assertEquals(
            0,
            ChessReadinessEngine.clarityPoints(
                ChessReadinessEngine.clarityAverageFromSliders(1, 1, 1)
            )
        )
    }

    // ── 5→10 point survey migration helper ─────────────────────────────────

    @Test
    fun `scaleSurveyTo10 doubles 1-5 values and passes everything else through`() {
        val p = ChessReadinessEngine::scaleSurveyTo10
        assertEquals(2, p(1))
        assertEquals(6, p(3))
        assertEquals(10, p(5))
        // 0 = legacy "no data" sentinel; 6–10 = already on the new scale
        assertEquals(0, p(0))
        assertEquals(7, p(7))
        assertEquals(10, p(10))
    }

    // ── Form-relative puzzle tiers (v3.1) ──────────────────────────────────

    @Test
    fun `rated puzzle form tiers score the ratio to the recent baseline`() {
        // recentAvgSec = 100 → ratio = avg / 100
        assertEquals(25, ChessReadinessEngine.ratedPuzzleScore(listOf(50, 55, 57), 100.0)) // 0.54
        assertEquals(21, ChessReadinessEngine.ratedPuzzleScore(listOf(60, 60, 60), 100.0))  // 0.60
        assertEquals(17, ChessReadinessEngine.ratedPuzzleScore(listOf(80, 80, 80), 100.0))  // 0.80
        assertEquals(13, ChessReadinessEngine.ratedPuzzleScore(listOf(100, 100, 100), 100.0)) // 1.00
        assertEquals(9, ChessReadinessEngine.ratedPuzzleScore(listOf(130, 130, 130), 100.0)) // 1.30
        assertEquals(4, ChessReadinessEngine.ratedPuzzleScore(listOf(160, 160, 160), 100.0)) // 1.60
        assertEquals(0, ChessReadinessEngine.ratedPuzzleScore(listOf(175, 175, 175), 100.0)) // 1.75
    }

    @Test
    fun `a rising rating no longer erodes the puzzle score`() {
        // Same FORM (ratio 0.8) at two very different absolute levels:
        val easyPuzzles = ChessReadinessEngine.ratedPuzzleScore(listOf(40, 40, 40), 50.0)
        val hardPuzzles = ChessReadinessEngine.ratedPuzzleScore(listOf(120, 120, 120), 150.0)
        assertEquals(easyPuzzles, hardPuzzles)
        assertEquals(17, easyPuzzles)
    }

    @Test
    fun `absolute puzzle tiers remain the cold-start fallback`() {
        // No baseline → old absolute behavior
        assertEquals(21, ChessReadinessEngine.ratedPuzzleScore(listOf(30, 30, 30), null))
        assertEquals(21, ChessReadinessEngine.ratedPuzzleScore(listOf(30, 30, 30), 0.0))
    }

    @Test
    fun `recentPuzzleAvgSec needs three samples and averages the window`() {
        val E = ChessReadinessEngine
        fun t(avg: Int?) = ChessReadinessEngine.ReadinessTest(NOW, 60, "X", puzzleAvgSec = avg)
        assertEquals(null, E.recentPuzzleAvgSec(listOf(t(30), t(50))))
        assertEquals(40.0, E.recentPuzzleAvgSec(listOf(t(30), t(50), t(40)))!!, 1e-9)
        // Zero/null records are skipped, not counted — two valid samples
        // still aren't enough
        assertEquals(null, E.recentPuzzleAvgSec(listOf(t(null), t(0), t(50), t(50))))
        assertEquals(50.0, E.recentPuzzleAvgSec(listOf(t(null), t(0), t(50), t(50), t(50)))!!, 1e-9)
    }

    // ── Form-relative rush baseline (v3.1) ─────────────────────────────────

    @Test
    fun `rush recent median replaces the ratcheting all-time high`() {
        // Typical run vs median 30 → ratio 1.0 → 25 pts (vs 13 on ATH 50)
        assertEquals(25, ChessReadinessEngine.rushScore(30, 50, 0, recentMedian = 30))
        // ATH fallback still applies when the median is below the floor
        assertEquals(21, ChessReadinessEngine.rushScore(8, 2, 0, recentMedian = 5))
        // Median missing → classic ATH behavior
        assertEquals(13, ChessReadinessEngine.rushScore(30, 50, 0, recentMedian = null))
    }

    @Test
    fun `recentRushMedian takes the median of the last ten runs`() {
        val E = ChessReadinessEngine
        fun t(rush: Int?) = ChessReadinessEngine.ReadinessTest(NOW, 60, "X", rushScore = rush)
        assertEquals(null, E.recentRushMedian(listOf(t(30), t(40))))
        assertEquals(30, E.recentRushMedian(listOf(t(20), t(30), t(40))))          // odd → middle
        assertEquals(30, E.recentRushMedian(listOf(t(20), t(30), t(30), t(40))))   // even → mean 30
        assertEquals(35, E.recentRushMedian(listOf(t(20), t(30), t(40), t(50))))   // even → 35
    }

    // ── Survey calibration weighting (v3.1) ────────────────────────────────

    /** A history test carrying the telemetry the calibration pairs need. */
    private fun pairedTest(clarity: Double, puzzle: Int, rush: Int) =
        ChessReadinessEngine.ReadinessTest(
            NOW, 60, "X",
            clarityAverage = clarity, pPuzzle = puzzle, pRush = rush
        )

    @Test
    fun `calibration is neutral until six paired samples exist`() {
        val E = ChessReadinessEngine
        listOf(0, 1, 5).forEach { n ->
            val c = E.surveyCalibration((1..n).map { pairedTest(7.0, 17, 18) })
            assertEquals(1.0, c.weight, 1e-9)
            assertEquals(null, c.mae)
            assertEquals(null, c.objectiveAnchor)
            assertEquals(n, c.sampleSize)
        }
    }

    @Test
    fun `perfect survey representation earns full weight`() {
        // survey 7.0 == objective (17+18)/5 = 7.0 on every pair
        val c = ChessReadinessEngine.surveyCalibration(
            (1..8).map { pairedTest(7.0, 17, 18) }
        )
        assertEquals(0.0, c.mae!!, 1e-9)
        assertEquals(1.0, c.weight, 1e-9)
        assertEquals(7.0, c.objectiveAnchor!!, 1e-9)
    }

    @Test
    fun `consistently inflated surveys sink to the minimum weight`() {
        // survey 9.0 vs objective 6.0 → MAE 3.0 → weight floor 0.35
        val c = ChessReadinessEngine.surveyCalibration(
            (1..6).map { pairedTest(9.0, 17, 13) } // (17+13)/5 = 6.0
        )
        assertEquals(3.0, c.mae!!, 1e-9)
        assertEquals(ChessReadinessEngine.CALIBRATION_MIN_WEIGHT, c.weight, 1e-9)
        assertEquals(6.0, c.objectiveAnchor!!, 1e-9)
    }

    @Test
    fun `calibration weight interpolates linearly with the gap`() {
        val E = ChessReadinessEngine
        // Uniform gap 0.5 → MAE 0.5 → weight 0.35 + 0.65 × (1 − 0.5/3)
        val uniform = E.surveyCalibration((1..6).map { pairedTest(7.5, 17, 18) })
        assertEquals(0.5, uniform.mae!!, 1e-9)
        assertEquals(0.35 + 0.65 * (1.0 - 0.5 / 3.0), uniform.weight, 1e-9)
        // Alternating gaps of 1.0 and 2.0 → MAE 1.5 → weight 0.35 + 0.65 × 0.5
        val mixed = listOf(
            pairedTest(8.0, 17, 18), // gap 1.0
            pairedTest(9.0, 17, 18), // gap 2.0
            pairedTest(8.0, 17, 18),
            pairedTest(9.0, 17, 18),
            pairedTest(8.0, 17, 18),
            pairedTest(9.0, 17, 18)
        )
        val m = E.surveyCalibration(mixed)
        assertEquals(1.5, m.mae!!, 1e-9)
        assertEquals(0.675, m.weight, 1e-9)
    }

    @Test
    fun `effective clarity shrinks toward the objective anchor`() {
        val E = ChessReadinessEngine
        // Inflated history → weight 0.35, anchor 6.0
        val c = E.surveyCalibration((1..6).map { pairedTest(9.0, 17, 13) })
        // Reported 9.0 → 0.35×9.0 + 0.65×6.0 = 7.05 — the 20-pt tier, not 25
        assertEquals(7.05, E.effectiveClarityAverage(9.0, c), 1e-9)
        assertEquals(20, E.clarityPoints(E.effectiveClarityAverage(9.0, c)))
        // Neutral calibration (no anchor) passes the report through
        val neutral = ChessReadinessEngine.SurveyCalibration(1.0, null, 0, null)
        assertEquals(9.0, E.effectiveClarityAverage(9.0, neutral), 1e-9)
        // Out-of-range reports are clamped
        assertEquals(10.0, E.effectiveClarityAverage(99.0, neutral), 1e-9)
    }

    @Test
    fun `evaluate wires calibration and baselines into the composite`() {
        val E = ChessReadinessEngine
        // History: inflated surveys (9.0 vs objective 6.0), typical puzzle
        // avg 100 s, typical rush 30 — enough samples for everything.
        val history = (1..6).map {
            ChessReadinessEngine.ReadinessTest(
                NOW - it * DAY, 60, "X",
                clarityAverage = 9.0, puzzleAvgSec = 100, rushScore = 30,
                pPuzzle = 17, pRush = 13
            )
        }
        val r = E.evaluate(
            input(
                clarityAvg = 9.0,             // inflated report
                puzzleTimes = listOf(50, 50, 50), // avg 50 vs recent 100 → ratio 0.5 → 25
                rush = 30,                        // vs median 30 → ratio 1.0 → 25
                ath = 50
            ),
            NOW, history
        )
        // Clarity shrunk to 7.05 → 20 pts, not the 25 an honest-math 9.0 would give
        assertEquals(20, r.sClarity)
        assertEquals(9.0, r.clarityReported, 1e-9)
        assertEquals(7.05, r.clarityEffective, 1e-9)
        assertEquals(E.CALIBRATION_MIN_WEIGHT, r.surveyWeight, 1e-9)
        assertEquals(3.0, r.surveyMae!!, 1e-9)
        assertEquals(6, r.surveySampleSize)
        assertEquals(25, r.pPuzzle)
        assertEquals(25, r.pRush)
    }
}
