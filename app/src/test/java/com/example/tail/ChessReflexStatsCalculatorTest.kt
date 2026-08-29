package com.example.tail

import com.example.tail.data.ReadinessGameRecord
import com.example.tail.data.ReflexRunPoint
import com.example.tail.data.V2PvtRecord
import com.example.tail.data.V3ReflexRunRecord
import com.example.tail.data.buildReflexRuns
import com.example.tail.data.ReflexStatsConfig
import com.example.tail.data.computeReflexStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Unit tests for the cross-version reflex (PVT-B) stats calculator.
 */
class ChessReflexStatsCalculatorTest {

    private val zone = ZoneId.of("UTC")
    private val hour = 3_600_000L

    private fun game(endMs: Long, won: Boolean, ratingAfter: Int? = null) =
        ReadinessGameRecord(
            endTimeMs = endMs, type = "BLITZ", opponent = "opp", won = won,
            minutes = 5.0, ccrsAtPlay = null, stateAtPlay = null,
            authorized = true, ratingAfter = ratingAfter
        )

    @Test
    fun `buildReflexRuns merges v2 and v3 chronologically`() {
        val runs = buildReflexRuns(
            v2Pvt = listOf(
                V2PvtRecord(2_000, 30, 1, 2, 3.5, 285.0, 500)
            ),
            v3Reflex = listOf(
                V3ReflexRunRecord(1_000, 0, 1, 250.0)
            )
        )
        assertEquals(2, runs.size)
        assertEquals("v3", runs[0].version)
        assertEquals(2, runs[0].durationMin)
        assertEquals(1000.0 / 250.0, runs[0].meanRrt!!, 1e-9)
        assertEquals("v2", runs[1].version)
        assertEquals(3, runs[1].durationMin)
        assertEquals(500, runs[1].maxRtMs)
    }

    @Test
    fun `aggregates and trends across versions`() {
        val runs = listOf(
            ReflexRunPoint(0L, "v2", 3, 3, 1, 300.0, null, 600, 30),
            ReflexRunPoint(hour, "v2", 3, 2, 0, 290.0, null, 500, 30),
            ReflexRunPoint(2 * hour, "v3", 2, 1, 0, 280.0, null, null, null),
            ReflexRunPoint(3 * hour, "v3", 2, 0, 0, 270.0, null, null, null)
        )
        val s = computeReflexStats(runs, emptyList(), ReflexStatsConfig(zone = zone))
        assertEquals(4, s.totalRuns)
        assertEquals(mapOf("v2" to 2, "v3" to 2), s.runsByVersion)
        assertEquals(285.0, s.avgMeanRtMs!!, 1e-9)
        assertEquals(270.0, s.bestMeanRtMs!!, 1e-9)
        assertEquals(600, s.worstMaxRtMs)
        assertEquals(6, s.totalLapses)
        assertEquals(1, s.totalFalseStarts)
        // first-3 avg 290 → last-3 avg 280 → trend −10
        assertEquals(-10.0, s.rtTrendMs!!, 1e-9)
    }

    @Test
    fun `hourly buckets use local zone`() {
        val runs = listOf(
            ReflexRunPoint(0L, "v2", 3, 0, 0, 250.0, null, null, null),          // 00:00 UTC
            ReflexRunPoint(6 * hour, "v3", 2, 1, 0, 350.0, null, null, null),    // 06:00 UTC
            ReflexRunPoint(6 * hour + 60_000, "v3", 2, 2, 0, 450.0, null, null, null)
        )
        val s = computeReflexStats(runs, emptyList(), ReflexStatsConfig(zone = zone))
        assertEquals(24, s.hourly.size)
        assertEquals(1, s.hourly[0].runCount)
        assertEquals(2, s.hourly[6].runCount)
        assertEquals(400.0, s.hourly[6].avgMeanRtMs!!, 1e-9)
        assertEquals(1.5, s.hourly[6].avgLapses, 1e-9)
        assertEquals(0, s.fastestHour)
        assertEquals(6, s.slowestHour)
    }

    @Test
    fun `following session matches rated games within window`() {
        val t0 = 1_000_000L
        val runs = listOf(
            ReflexRunPoint(t0, "v3", 2, 0, 0, 250.0, null, null, null)
        )
        val games = listOf(
            game(t0 + hour, won = true, ratingAfter = 1500),        // inside
            game(t0 + 2 * hour, won = false, ratingAfter = 1490),   // inside
            game(t0 + 10 * hour, won = true, ratingAfter = 1510)    // outside 6h
        )
        val s = computeReflexStats(runs, games, ReflexStatsConfig(zone = zone))
        assertEquals(1, s.followingSessions.size)
        val sess = s.followingSessions.first()
        assertEquals(2, sess.games)
        assertEquals(1, sess.wins)
        assertEquals(50.0, sess.winRate, 1e-9)
        assertEquals(-10, sess.eloDelta)
    }

    @Test
    fun `unrated games excluded from following sessions`() {
        val t0 = 1_000_000L
        val runs = listOf(ReflexRunPoint(t0, "v3", 2, 0, 0, 250.0, null, null, null))
        val games = listOf(game(t0 + hour, won = true).copy(rated = false))
        val s = computeReflexStats(runs, games, ReflexStatsConfig(zone = zone))
        assertTrue(s.followingSessions.isEmpty())
    }

    @Test
    fun `correlation is negative when slow runs precede losses`() {
        val t0 = 1_000_000L
        // 6 runs: RT rising, following win rate falling.
        val runs = (0 until 6).map {
            ReflexRunPoint(t0 + it * 48 * hour, "v3", 2, 0, 0, 240.0 + it * 20, null, null, null)
        }
        val games = runs.flatMapIndexed { i, r ->
            val won = i < 3
            listOf(game(r.timestampMs + hour, won = won, ratingAfter = if (won) 1510 else 1490))
        }
        val s = computeReflexStats(runs, games, ReflexStatsConfig(zone = zone))
        assertEquals(6, s.followingSessions.size)
        assertNotNull(s.rtWinRateCorrelation)
        assertTrue(s.rtWinRateCorrelation!! < -0.4)
        // Fast half should out-win the slow half.
        assertNotNull(s.fastHalfFollowing)
        assertNotNull(s.slowHalfFollowing)
        assertTrue(s.fastHalfFollowing!!.avgWinRate > s.slowHalfFollowing!!.avgWinRate)
    }

    @Test
    fun `correlation null below four pairs`() {
        val t0 = 1_000_000L
        val runs = (0 until 3).map {
            ReflexRunPoint(t0 + it * 48 * hour, "v3", 2, 0, 0, 250.0 + it, null, null, null)
        }
        val games = runs.map { game(it.timestampMs + hour, won = true, ratingAfter = 1500) }
        val s = computeReflexStats(runs, games, ReflexStatsConfig(zone = zone))
        assertNull(s.rtWinRateCorrelation)
        assertNull(s.rtEloDeltaCorrelation)
    }
}
