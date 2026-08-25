package com.example.tail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for the standalone Puzzle Rush timer-session computation:
 * session → chart-point mapping, merging with readiness-test rush runs,
 * and the wrong-puzzle review rate.
 */
class ChessPuzzleRushSessionTest {

    private val zone = ZoneId.of("UTC")

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()

    private fun session(
        at: Long,
        score: Int,
        strikes: Int = 0,
        reviewed: Boolean = true,
        ath: Int = 20,
        durationSec: Long = 180L,
        startedAt: Long = at - durationSec * 1000
    ): PuzzleRushSessionRecord = PuzzleRushSessionRecord(
        timestamp = at, startedAt = startedAt, durationSec = durationSec,
        score = score, strikes = strikes, reviewedWrong = reviewed,
        allTimeHigh = ath
    )

    private fun test(at: Long, rushScore: Int, ath: Int = 20): ReadinessTestRecord =
        ReadinessTestRecord(
            timestamp = at, ccrs = 80, state = "GREEN_LIGHT",
            sSleep = 0, sClarity = 0, pPuzzle = 0, pRush = 0,
            sleepScore = 80, sleepFromGarmin = true,
            stress = 3, focus = 3, energy = 3,
            puzzleTimesSec = emptyList(), rushScore = rushScore, rushStrikes = 1,
            rushAllTimeHigh = ath, sessionStartedAt = at
        )

    // ── computeRushSessionPoints ──────────────────────────────────────────

    @Test
    fun `session points carry score strikes review duration and timer source`() {
        val at = ms("2026-08-24", 18)
        val points = computeRushSessionPoints(
            listOf(session(at, score = 22, strikes = 2, reviewed = false, durationSec = 195))
        )

        assertEquals(1, points.size)
        val p = points.first()
        assertEquals(at, p.timestampMs)
        assertEquals(22, p.score)
        assertEquals(2, p.strikes)
        assertEquals(RushSource.TIMER, p.source)
        assertEquals(false, p.reviewedWrong)
        assertEquals(195L, p.durationSec)
        // No readiness context for standalone runs.
        assertEquals("", p.state)
        assertEquals(0, p.ccrs)
    }

    @Test
    fun `zero-score sessions are skipped and the rest sorted chronologically`() {
        val later = session(ms("2026-08-25", 9), score = 18)
        val aborted = session(ms("2026-08-24", 20), score = 0)
        val earlier = session(ms("2026-08-24", 8), score = 21)

        val points = computeRushSessionPoints(listOf(later, aborted, earlier))

        assertEquals(2, points.size)
        assertEquals(earlier.timestamp, points[0].timestampMs)
        assertEquals(later.timestamp, points[1].timestampMs)
    }

    @Test
    fun `matching the all-time high flags a new high`() {
        val record = computeRushSessionPoints(
            listOf(session(ms("2026-08-24", 8), score = 20, ath = 20))
        ).first()
        val slump = computeRushSessionPoints(
            listOf(session(ms("2026-08-24", 9), score = 12, ath = 20))
        ).first()

        assertTrue(record.isNewHigh)
        assertFalse(slump.isNewHigh)
    }

    // ── mergeRushSeries ───────────────────────────────────────────────────

    @Test
    fun `test runs and timer sessions merge into one chronological series`() {
        val testPoint = computeRushScoreSeries(
            listOf(
                test(ms("2026-08-20", 9), rushScore = 19),
                test(ms("2026-08-26", 9), rushScore = 23, ath = 23)
            )
        )
        val sessionPoints = computeRushSessionPoints(
            listOf(
                session(ms("2026-08-22", 19), score = 21),
                session(ms("2026-08-28", 20), score = 24, ath = 23)
            )
        )

        val merged = mergeRushSeries(testPoint, sessionPoints)

        assertEquals(4, merged.size)
        assertEquals(
            listOf(
                ms("2026-08-20", 9), ms("2026-08-22", 19),
                ms("2026-08-26", 9), ms("2026-08-28", 20)
            ),
            merged.map { it.timestampMs }
        )
        // Sources survive the merge.
        assertEquals(RushSource.TEST, merged[0].source)
        assertEquals(RushSource.TIMER, merged[1].source)
        assertEquals(RushSource.TEST, merged[2].source)
        assertEquals(RushSource.TIMER, merged[3].source)
    }

    // ── rushReviewRate ────────────────────────────────────────────────────

    @Test
    fun `review rate counts only timer sessions with a review answer`() {
        val points = mergeRushSeries(
            // Test runs never carry a review answer — must not dilute the rate.
            computeRushScoreSeries(listOf(test(ms("2026-08-20", 9), rushScore = 19))),
            computeRushSessionPoints(
                listOf(
                    session(ms("2026-08-21", 9), score = 20, reviewed = true),
                    session(ms("2026-08-22", 9), score = 21, reviewed = true),
                    session(ms("2026-08-23", 9), score = 18, reviewed = false)
                )
            )
        )

        assertEquals(2.0 / 3.0 * 100.0, rushReviewRate(points)!!, 0.001)
    }

    @Test
    fun `review rate is null when no timer session reported a review`() {
        val points = computeRushScoreSeries(
            listOf(test(ms("2026-08-20", 9), rushScore = 19))
        )

        assertNull(rushReviewRate(points))
        assertNull(rushReviewRate(emptyList()))
    }
}
