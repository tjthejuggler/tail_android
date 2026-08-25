package com.example.tail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for the pure v2 stats calculator feeding the V2 sections of
 * the Chess Readiness Stats screen.
 *
 * Covers:
 *  - pre-game gate: tier tallies, pass rate, PVT aggregates (mean RT,
 *    lapses = late taps, false starts = early taps), trends, passive-module
 *    averages, and the timestamp join that attributes each reflex run the
 *    verdict it produced
 *  - post-game audit: verdict distribution, W/L/D + win rate, accuracy
 *    (only where counted), Elo delta, strain, minutes, loss streaks, and
 *    the ledger↔audit timestamp join
 */
class ChessReadinessV2StatsCalculatorTest {

    private val zone = ZoneId.of("UTC")

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()

    private fun result(
        ts: Long,
        tier: String,
        zLn: Double? = null,
        zRhr: Double? = null,
        acwr: Double? = null,
        pvtSkipped: Boolean = false,
        ccrs: Int = 90
    ) = V2ResultRecord(
        timestamp = ts,
        tier = tier,
        stateName = "GREEN_LIGHT",
        ccrs = ccrs,
        zLnRmssd = zLn,
        zRhr = zRhr,
        lapses = 0,
        falseStarts = 0,
        meanRrt = null,
        acwr = acwr,
        pvtSkipped = pvtSkipped,
        sessionStartedAt = ts
    )

    private fun pvt(
        ts: Long,
        rt: Double,
        lapses: Int = 0,
        falseStarts: Int = 0,
        maxRt: Int? = null,
        rrt: Double? = null
    ) = V2PvtRecord(
        timestamp = ts,
        validResponses = 30,
        lapses = lapses,
        falseStarts = falseStarts,
        meanRrt = rrt,
        meanRtMs = rt,
        maxRtMs = maxRt
    )

    // ── Pre-game gate ───────────────────────────────────────────────────────

    @Test
    fun `empty inputs yield zeroed pre-game stats`() {
        val s = computeV2PregameStats(emptyList(), emptyList())
        assertEquals(0, s.totalTests)
        assertEquals(0, s.pvtCount)
        assertEquals(0.0, s.passRate, 0.001)
        assertNull(s.firstTestAt)
        assertNull(s.avgMeanRtMs)
        assertTrue(s.series.isEmpty())
    }

    @Test
    fun `tier tallies and pass rate are computed over all evaluations`() {
        val base = ms("2026-08-10", 9)
        val results = listOf(
            result(base, V2Tiers.TIER1),
            result(base + 60_000, V2Tiers.TIER1),
            result(base + 3_600_000, V2Tiers.TIER2),
            result(base + 7_200_000, V2Tiers.TIER3)
        )
        val s = computeV2PregameStats(results, emptyList())
        assertEquals(4, s.totalTests)
        assertEquals(2, s.tier1Count)
        assertEquals(1, s.tier2Count)
        assertEquals(1, s.tier3Count)
        assertEquals(50.0, s.passRate, 0.001)
        assertEquals(base, s.firstTestAt)
        assertEquals(base + 7_200_000, s.lastTestAt)
    }

    @Test
    fun `reflex aggregates average RT lapses and false starts across runs`() {
        val base = ms("2026-08-10", 9)
        val runs = listOf(
            pvt(base, 300.0, lapses = 2, falseStarts = 1, maxRt = 500),
            pvt(base + 3_600_000, 350.0, lapses = 4, falseStarts = 3, maxRt = 800)
        )
        val s = computeV2PregameStats(emptyList(), runs)
        assertEquals(2, s.pvtCount)
        assertEquals(325.0, s.avgMeanRtMs!!, 0.001)
        assertEquals(300.0, s.bestMeanRtMs!!, 0.001)
        assertEquals(3.0, s.avgLapses, 0.001)
        assertEquals(2.0, s.avgFalseStarts, 0.001)
        assertEquals(6, s.totalLapses)
        assertEquals(4, s.totalFalseStarts)
        assertEquals(800, s.worstMaxRtMs)
    }

    @Test
    fun `PVT run joins to the verdict recorded within the join window`() {
        val base = ms("2026-08-10", 9)
        val results = listOf(
            result(base + 45_000, V2Tiers.TIER1)   // 45 s after the run → joins
        )
        val runs = listOf(
            pvt(base, 300.0),
            pvt(base + 86_400_000, 320.0)          // a day later → no verdict
        )
        val s = computeV2PregameStats(results, runs)
        assertEquals(V2Tiers.TIER1, s.series[0].tier)
        assertEquals(true, s.series[0].passed)
        assertNull(s.series[1].tier)
        assertNull(s.series[1].passed)
    }

    @Test
    fun `PVT run joins to the NEAREST verdict when several are in range`() {
        val base = ms("2026-08-10", 9)
        val results = listOf(
            result(base - 90_000, V2Tiers.TIER3),  // 90 s before
            result(base + 30_000, V2Tiers.TIER2)   // 30 s after → nearer
        )
        val s = computeV2PregameStats(results, listOf(pvt(base, 310.0)))
        assertEquals(V2Tiers.TIER2, s.series[0].tier)
        assertEquals(false, s.series[0].passed)
    }

    @Test
    fun `trends compare first-3 vs last-3 runs and null out below 2 samples`() {
        val base = ms("2026-08-10", 9)
        // RT improving 320 → 300 → 280 vs 380 → 400 → 420 (head avg 400, tail avg 300)
        val runs = listOf(
            pvt(base, 380.0, lapses = 4),
            pvt(base + 60_000, 400.0, lapses = 5),
            pvt(base + 120_000, 420.0, lapses = 6),
            pvt(base + 180_000, 320.0, lapses = 2),
            pvt(base + 240_000, 300.0, lapses = 1),
            pvt(base + 300_000, 280.0, lapses = 0)
        )
        val s = computeV2PregameStats(emptyList(), runs)
        assertEquals(-100.0, s.rtTrendMs!!, 0.001)
        assertEquals(-4.0, s.lapseTrend!!, 0.001)

        val single = computeV2PregameStats(emptyList(), listOf(pvt(base, 300.0)))
        assertNull(single.rtTrendMs)
        assertNull(single.lapseTrend)
        assertNull(single.falseStartTrend)
    }

    @Test
    fun `passive module averages and autonomic coverage ignore missing data`() {
        val base = ms("2026-08-10", 9)
        val results = listOf(
            result(base, V2Tiers.TIER1, zLn = 0.4, zRhr = 0.2, acwr = 1.1),
            result(base + 3_600_000, V2Tiers.TIER2, zLn = 0.8, zRhr = null, acwr = 1.3),
            result(base + 7_200_000, V2Tiers.TIER3, zLn = null, zRhr = null, acwr = null, pvtSkipped = true)
        )
        val s = computeV2PregameStats(results, emptyList())
        assertEquals(0.6, s.avgZLnRmssd!!, 0.001)
        assertEquals(0.2, s.avgZRhr!!, 0.001)
        assertEquals(1.2, s.avgAcwr!!, 0.001)
        // 2 of 3 tests had lnRMSSD data → 66.7 % coverage
        assertEquals(66.66666666666667, s.autonomicCoverage, 0.001)
        assertEquals(1, s.pvtSkippedCount)
    }

    // ── Post-game audit ─────────────────────────────────────────────────────

    private fun game(
        ts: Long,
        result: String,
        output: String = Phase2Verdicts.CONTINUE,
        minutes: Double = 10.0
    ) = Phase2V2GameRecord(
        timestamp = ts,
        result = result,
        timeControl = "BLITZ",
        outputState = output,
        estimatedMinutes = minutes
    )

    private fun audit(
        ts: Long,
        deltaE: Double,
        accuracy: Double,
        counted: Boolean = true,
        strain: Double = 20.0
    ) = Phase2AuditRecord(
        timestamp = ts,
        timeControl = "BLITZ",
        outputState = Phase2Verdicts.CONTINUE,
        deltaE = deltaE,
        caps2Accuracy = accuracy,
        accuracyCounted = counted,
        strain = strain
    )

    @Test
    fun `empty ledger yields zeroed post-game stats`() {
        val s = computePhase2V2Stats(emptyList(), emptyList())
        assertEquals(0, s.totalGames)
        assertEquals(0.0, s.continueRate, 0.001)
        assertNull(s.latestVerdict)
        assertNull(s.avgAccuracy)
        assertTrue(s.series.isEmpty())
    }

    @Test
    fun `verdict distribution continue rate and latest verdict are computed`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(
            game(base, "WIN", Phase2Verdicts.CONTINUE),
            game(base + 600_000, "WIN", Phase2Verdicts.CONTINUE),
            game(base + 1_200_000, "LOSS", Phase2Verdicts.PIVOT),
            game(base + 1_800_000, "LOSS", Phase2Verdicts.TERMINATE)
        )
        val s = computePhase2V2Stats(games, emptyList())
        assertEquals(4, s.totalGames)
        assertEquals(2, s.continueCount)
        assertEquals(1, s.pivotCount)
        assertEquals(1, s.terminateCount)
        assertEquals(50.0, s.continueRate, 0.001)
        assertEquals(Phase2Verdicts.TERMINATE, s.latestVerdict)
    }

    @Test
    fun `win rate excludes draws and loss streak is the longest consecutive run`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(
            game(base, "WIN"),
            game(base + 300_000, "LOSS"),
            game(base + 600_000, "LOSS"),
            game(base + 900_000, "LOSS"),
            game(base + 1_200_000, "DRAW"),
            game(base + 1_500_000, "LOSS"),
            game(base + 1_800_000, "WIN")
        )
        val s = computePhase2V2Stats(games, emptyList())
        assertEquals(2, s.wins)
        assertEquals(4, s.losses)
        assertEquals(1, s.draws)
        // 2 wins / 6 decided = 33.3 %
        assertEquals(33.33333333333333, s.winRate, 0.001)
        assertEquals(3, s.longestLossStreak)
    }

    @Test
    fun `ledger game joins to the shared audit within the join window`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(
            game(base, "WIN"),                          // audit 20 s later → joins
            game(base + 86_400_000, "LOSS")             // a day later → no audit
        )
        val audits = listOf(audit(base + 20_000, deltaE = 8.0, accuracy = 82.0, strain = 15.0))
        val s = computePhase2V2Stats(games, audits)
        assertEquals(82.0, s.series[0].accuracy!!, 0.001)
        assertEquals(8.0, s.series[0].deltaE!!, 0.001)
        assertEquals(15.0, s.series[0].strain!!, 0.001)
        assertNull(s.series[1].accuracy)
        assertNull(s.series[1].deltaE)
        assertNull(s.series[1].strain)
    }

    @Test
    fun `bypassed accuracy is excluded but deltaE and strain still count`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(game(base, "WIN"), game(base + 600_000, "LOSS"))
        val audits = listOf(
            audit(base + 5_000, deltaE = 8.0, accuracy = 80.0, counted = true),
            audit(base + 605_000, deltaE = -6.0, accuracy = 55.0, counted = false)
        )
        val s = computePhase2V2Stats(games, audits)
        // Only the counted audit's accuracy enters the mean.
        assertEquals(80.0, s.avgAccuracy!!, 0.001)
        assertEquals(1, s.accuracyGames)
        // Both deltas and strains count.
        assertEquals(1.0, s.avgDeltaE, 0.001)
        assertEquals(2.0, s.totalDeltaE, 0.001)
        assertEquals(40.0, s.totalStrain, 0.001)
    }

    @Test
    fun `minutes totals and accuracy trend summarize the ledger`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(
            game(base, "WIN", minutes = 10.0),
            game(base + 600_000, "LOSS", minutes = 5.0),
            game(base + 1_200_000, "WIN", minutes = 10.0),
            game(base + 1_800_000, "WIN", minutes = 10.0)
        )
        val audits = listOf(
            audit(base + 5_000, deltaE = 5.0, accuracy = 70.0),
            audit(base + 605_000, deltaE = -5.0, accuracy = 65.0),
            audit(base + 1_205_000, deltaE = 5.0, accuracy = 80.0),
            audit(base + 1_805_000, deltaE = 5.0, accuracy = 85.0)
        )
        val s = computePhase2V2Stats(games, audits)
        assertEquals(35.0, s.totalMinutes, 0.001)
        assertEquals(8.75, s.avgMinutes, 0.001)
        // Head (70, 65, 80) avg 71.67 → tail (65, 80, 85) avg 76.67 = +5 pts
        // (trend is first-3 → last-3, matching the stat's documentation/UI).
        assertEquals(5.0, s.accuracyTrend!!, 0.001)
    }

    @Test
    fun `series is sorted chronologically even from shuffled input`() {
        val base = ms("2026-08-10", 21)
        val games = listOf(
            game(base + 1_800_000, "WIN"),
            game(base, "LOSS"),
            game(base + 600_000, "DRAW")
        )
        val s = computePhase2V2Stats(games, emptyList())
        assertEquals(
            listOf(base, base + 600_000, base + 1_800_000),
            s.series.map { it.timestampMs }
        )
        assertEquals(base, s.firstGameAt)
        assertEquals(base + 1_800_000, s.lastGameAt)
    }

    // ── Pre-game hourly aggregates ───────────────────────────────────────────

    @Test
    fun `hourly v2 readiness has 24 slots with per-hour aggregates`() {
        val nine = ms("2026-08-20", 9)
        val ten = ms("2026-08-20", 10)
        val results = listOf(
            result(nine, V2Tiers.TIER1, ccrs = 80),
            result(nine + 60_000, V2Tiers.TIER3, ccrs = 40),
            result(ten, V2Tiers.TIER2, ccrs = 60)
        )
        val runs = listOf(
            pvt(nine + 5_000, 300.0),
            pvt(nine + 65_000, 400.0),
            pvt(ten + 5_000, 250.0)
        )
        val hourly = computeV2HourlyReadiness(results, runs, zone)

        assertEquals(24, hourly.size)
        assertEquals((0..23).toList(), hourly.map { it.hour })

        val h9 = hourly[9]
        assertEquals(2, h9.testCount)
        assertEquals(60.0, h9.avgCcrs, 0.001)          // (80 + 40) / 2
        assertEquals(1, h9.tier1Count)
        assertEquals(0, h9.tier2Count)
        assertEquals(1, h9.tier3Count)
        assertEquals(50.0, h9.passRate, 0.001)
        assertEquals(2, h9.pvtCount)
        assertEquals(350.0, h9.avgMeanRtMs!!, 0.001)   // (300 + 400) / 2

        val h10 = hourly[10]
        assertEquals(1, h10.testCount)
        assertEquals(60.0, h10.avgCcrs, 0.001)
        assertEquals(1, h10.tier2Count)
        assertEquals(0.0, h10.passRate, 0.001)
        assertEquals(250.0, h10.avgMeanRtMs!!, 0.001)

        // Untouched hours carry zero counts and null reflex averages.
        val empty = hourly[15]
        assertEquals(0, empty.testCount)
        assertEquals(0.0, empty.avgCcrs, 0.001)
        assertEquals(0, empty.pvtCount)
        assertNull(empty.avgMeanRtMs)
    }

    @Test
    fun `hourly v2 readiness buckets by the zone-local hour`() {
        // 23:30 UTC on the 20th == 01:30 on the 21st at UTC+2.
        val lateUtc = ms("2026-08-20", 23, 30)
        val results = listOf(result(lateUtc, V2Tiers.TIER1, ccrs = 75))
        val hourly = computeV2HourlyReadiness(results, emptyList(), ZoneId.of("UTC+2"))
        assertEquals(0, hourly[23].testCount)
        assertEquals(1, hourly[1].testCount)
        assertEquals(75.0, hourly[1].avgCcrs, 0.001)
    }

    @Test
    fun `hourly v2 readiness counts PVT runs without a mean RT for the run tally only`() {
        val noon = ms("2026-08-20", 12)
        val runs = listOf(
            pvt(noon, 300.0),
            pvt(noon + 60_000, 0.0) // engine may omit mean RT (null here via rrt-only record)
        )
        val noRt = runs[1].copy(meanRtMs = null)
        val hourly = computeV2HourlyReadiness(emptyList(), listOf(runs[0], noRt), zone)
        assertEquals(2, hourly[12].pvtCount)
        assertEquals(300.0, hourly[12].avgMeanRtMs!!, 0.001)
    }
}
