package com.example.tail.data

import com.example.tail.widget.ChessFreeplayStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import com.example.tail.data.chess.ChessReadinessEngine
import com.example.tail.data.chess.ReadinessTestRecord
import com.example.tail.data.chess.ChessComGame
import com.example.tail.data.chess.ReadinessGameRecord
import com.example.tail.data.chess.readinessContextAt
import com.example.tail.data.chess.gameToRecord
import com.example.tail.data.chess.computeFreeplayComparison
import com.example.tail.data.chess.freeplaySessionNetRatingChange
import com.example.tail.data.chess.freeplaySessionRefundDue
import com.example.tail.data.chess.freeplaySessionSettleAt
import com.example.tail.data.chess.FREEPLAY_SETTLE_IDLE_MS

/**
 * Unit tests for the weekly FREEPLAY feature:
 *  - [ChessFreeplayStore accrual math] — Monday-aligned week indices,
 *    per-week crediting, the 3-credit stock cap
 *  - [freeplay context resolution] — a flagged GREEN entry authorizes play
 *    exactly like a pass, but is distinguishable by provenance
 *  - [computeFreeplayComparison] — splits authorized games into
 *    pass-authorized vs freeplay-authorized windows
 *  - [settlement] — net rating change over a freeplay session and the
 *    refund rule (net ≥ +1 refunds the provisionally-spent credit)
 */
class ChessFreeplayTest {

    private val zone = ZoneId.of("UTC")
    private val green = ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name

    private fun ms(date: String, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.of(hour, minute), zone)
            .toInstant().toEpochMilli()

    private fun test(
        at: Long, ccrs: Int, state: String,
        freeplay: Boolean = false
    ): ReadinessTestRecord = ReadinessTestRecord(
        timestamp = at, ccrs = ccrs, state = state,
        sSleep = 0, sClarity = 0, pPuzzle = 0, pRush = 0,
        sleepScore = 80, sleepFromGarmin = true,
        stress = 3, focus = 3, energy = 3,
        puzzleTimesSec = listOf(30, 40), rushScore = 20, rushStrikes = 0,
        rushAllTimeHigh = 20, sessionStartedAt = at - 10 * 60 * 1000L,
        freeplay = freeplay
    )

    private fun game(
        endTimeSec: Long, white: String = "me", black: String = "opp",
        whiteResult: String = "win", blackResult: String = "checkmated",
        timeControl: String = "180+2"
    ): ChessComGame = ChessComGame(
        timeClass = "blitz", timeControl = timeControl, endTime = endTimeSec,
        whiteUsername = white, blackUsername = black,
        whiteResult = whiteResult, blackResult = blackResult,
        rated = true, rules = "chess",
        whiteRating = 0, blackRating = 0
    )

    private fun record(
        endTimeMs: Long, won: Boolean, ratingAfter: Int?,
        freeplay: Boolean, type: String = "BULLET", variant: String = "chess",
        minutes: Double = 2.0
    ): ReadinessGameRecord = ReadinessGameRecord(
        endTimeMs = endTimeMs, type = type, opponent = "opp", won = won,
        minutes = minutes, ccrsAtPlay = 85, stateAtPlay = green,
        authorized = true, variant = variant, rated = true,
        ratingAfter = ratingAfter, freeplayAtPlay = freeplay
    )

    // ── Accrual math (pure) ──────────────────────────────────────────────

    @Test
    fun `week index is monday aligned`() {
        // 1970-01-01 was a Thursday; Monday 1970-01-05 must start week 1.
        val thu = LocalDate.of(1970, 1, 1).toEpochDay()
        val mon = LocalDate.of(1970, 1, 5).toEpochDay()
        val sun = LocalDate.of(1970, 1, 4).toEpochDay()
        assertEquals(0L, ChessFreeplayStore.weekIndexOfEpochDay(thu))
        assertEquals(0L, ChessFreeplayStore.weekIndexOfEpochDay(sun))
        assertEquals(1L, ChessFreeplayStore.weekIndexOfEpochDay(mon))
    }

    @Test
    fun `millis overload agrees with epoch day overload`() {
        // REGRESSION (2026-09-22): the pure overload used to be a
        // `weekIndexOf(Long)` sibling of the millis variant, so every
        // default-arg call site silently bound to the EPOCH-DAY overload
        // and fed milliseconds in as days — a ~2.5e11 week index whose
        // missing-week materialization OOM'd the bubble menu on tap.
        // The millis overload must produce the SAME index as the day
        // overload for the same instant.
        val zone = ZoneId.of("UTC")
        val at = ZonedDateTime.of(LocalDate.parse("2026-09-22"), LocalTime.NOON, zone)
        val dayIdx = ChessFreeplayStore.weekIndexOfEpochDay(at.toLocalDate().toEpochDay())
        assertEquals(dayIdx, ChessFreeplayStore.weekIndexOf(at.toInstant().toEpochMilli(), zone))
        // Milliseconds must NEVER be mistaken for days (day 1.78e9 vs
        // week ~2964 for 2026) — the mix-up produced indexes in the
        // hundreds of billions.
        assertTrue(ChessFreeplayStore.weekIndexOf(at.toInstant().toEpochMilli(), zone) < 100_000L)
    }

    @Test
    fun `same week never re-credits`() {
        val idx = ChessFreeplayStore.weekIndexOfEpochDay(1000L)
        assertEquals(0L, ChessFreeplayStore.weeksElapsed(idx, idx))
        // Pure accrual: elapsed 0 → granted total unchanged.
        assertEquals(2, ChessFreeplayStore.accrue(2, 0))
    }

    @Test
    fun `accrual credits one per week`() {
        assertEquals(1, ChessFreeplayStore.accrue(0, 1))
        assertEquals(2, ChessFreeplayStore.accrue(1, 1))
    }

    // ── Freeplay context resolution ──────────────────────────────────────

    @Test
    fun `freeplay entry authorizes like a pass`() {
        val tests = listOf(
            test(ms("2026-09-21", 10), 85, green, freeplay = true)
        )
        val (ctx, authorized, freeplay) = readinessContextAt(tests, ms("2026-09-21", 10, 30))
        assertTrue(authorized)
        assertTrue(freeplay)
        assertEquals(85, ctx?.ccrs)
    }

    @Test
    fun `expired freeplay window does not authorize`() {
        val tests = listOf(
            test(ms("2026-09-21", 10), 85, green, freeplay = true)
        )
        val (_, authorized, _) = readinessContextAt(tests, ms("2026-09-21", 11, 1))
        assertFalse(authorized)
    }

    // ── Freeplay vs pass comparison ──────────────────────────────────────

    @Test
    fun `comparison splits pass and freeplay games`() {
        // Pass at 09:00 (green, not freeplay); freeplay at 12:00.
        val passTs = ms("2026-09-21", 9)
        val freeTs = ms("2026-09-21", 12)
        val tests = listOf(
            test(passTs, 85, green, freeplay = false),
            test(freeTs, 85, green, freeplay = true)
        )
        // Pass-window game (won) + freeplay-window game (lost).
        val passGame = game(ms("2026-09-21", 9, 30) / 1000, whiteResult = "win")
        val freeGame = game(ms("2026-09-21", 12, 20) / 1000, whiteResult = "checkmated")
        val records = listOf(passGame, freeGame).mapNotNull {
            gameToRecord(it, "me", tests)
        }
        assertEquals(2, records.size)
        // The pass-window game must NOT carry the freeplay marker.
        assertFalse(records[0].freeplayAtPlay)
        assertTrue(records[1].freeplayAtPlay)

        val cmp = computeFreeplayComparison(tests, records)
        assertEquals(1, cmp.pass.games)
        assertEquals(1, cmp.pass.wins)
        assertEquals(100.0, cmp.pass.winRate, 0.001)
        assertEquals(1, cmp.freeplay.games)
        assertEquals(0, cmp.freeplay.wins)
        assertEquals(0.0, cmp.freeplay.winRate, 0.001)
        // Each source used exactly one authorizing entry.
        assertEquals(1, cmp.pass.sessions)
        assertEquals(1, cmp.freeplay.sessions)
        assertEquals(85.0, cmp.pass.avgCcrs!!, 0.001)
        assertEquals(85.0, cmp.freeplay.avgCcrs!!, 0.001)
    }

    @Test
    fun `unauthorized games are excluded from both sides`() {
        val tests = listOf(test(ms("2026-09-21", 9), 85, green, freeplay = true))
        // Played 3 hours after the freeplay window — unauthorized.
        val late = gameToRecord(game(ms("2026-09-21", 12) / 1000), "me", tests)
        assertTrue(late != null && !late!!.authorized)
        val cmp = computeFreeplayComparison(tests, listOfNotNull(late))
        assertEquals(0, cmp.pass.games)
        assertEquals(0, cmp.freeplay.games)
    }

    @Test
    fun `no freeplay yields empty freeplay side`() {
        val tests = listOf(test(ms("2026-09-21", 9), 85, green, freeplay = false))
        val rec = gameToRecord(game(ms("2026-09-21", 9, 30) / 1000), "me", tests)
        val cmp = computeFreeplayComparison(tests, listOfNotNull(rec))
        assertEquals(1, cmp.pass.games)
        assertEquals(0, cmp.freeplay.games)
        assertNull(cmp.freeplay.avgCcrs)
    }

    // ── Settlement: net rating change + refund rule ──────────────────────

    @Test
    fun `net rating change sums per pool against pre-session baselines`() {
        val freeTs = ms("2026-09-21", 15)
        // Pre-session baseline in the bullet pool: 1200 (yesterday).
        val pre = record(ms("2026-09-20", 20), won = true, ratingAfter = 1200,
            freeplay = false)
        // Session: -8, +6, -11 → net -13.
        val g1 = record(freeTs + 2 * 60_000, won = false, ratingAfter = 1192,
            freeplay = true)
        val g2 = record(freeTs + 5 * 60_000, won = true, ratingAfter = 1198,
            freeplay = true)
        val g3 = record(freeTs + 9 * 60_000, won = false, ratingAfter = 1187,
            freeplay = true)
        val net = freeplaySessionNetRatingChange(listOf(g3, pre, g1, g2), freeTs)
        assertEquals(-13, net)
    }

    @Test
    fun `net gain at or above 1 refunds`() {
        assertTrue(freeplaySessionRefundDue(1))
        assertTrue(freeplaySessionRefundDue(15))
        // Below +1 (including small losses and 0) does not.
        assertFalse(freeplaySessionRefundDue(0))
        assertFalse(freeplaySessionRefundDue(-1))
        assertFalse(freeplaySessionRefundDue(null))
    }

    @Test
    fun `no pre-session baseline yields null`() {
        val freeTs = ms("2026-09-21", 15)
        // Only in-session games — first ever in this pool: no baseline.
        val g1 = record(freeTs + 2 * 60_000, won = true, ratingAfter = 1200,
            freeplay = true)
        assertNull(freeplaySessionNetRatingChange(listOf(g1), freeTs))
        // No games at all → null too.
        assertNull(freeplaySessionNetRatingChange(emptyList(), freeTs))
    }

    @Test
    fun `games outside the window are ignored`() {
        val freeTs = ms("2026-09-21", 15)
        val pre = record(freeTs - 60 * 60_000, won = true, ratingAfter = 1200,
            freeplay = false)
        // Freeplay-flagged game that STARTED after the window closed.
        val late = record(freeTs + 61 * 60_000 + 120_000, won = false,
            ratingAfter = 1100, freeplay = false)
        // Late game is outside → no in-session game in the pool → null.
        assertNull(freeplaySessionNetRatingChange(listOf(pre, late), freeTs))
    }

    @Test
    fun `multi pool net is the sum of pool deltas`() {
        val freeTs = ms("2026-09-21", 15)
        val bulletBase = record(ms("2026-09-21", 10), won = false,
            ratingAfter = 1200, freeplay = false, type = "BULLET")
        val bulletIn = record(freeTs + 120_000, won = true, ratingAfter = 1210,
            freeplay = true, type = "BULLET")
        val blitzBase = record(ms("2026-09-21", 11), won = true,
            ratingAfter = 1500, freeplay = false, type = "BLITZ")
        val blitzIn = record(freeTs + 300_000, won = false, ratingAfter = 1496,
            freeplay = true, type = "BLITZ")
        val net = freeplaySessionNetRatingChange(
            listOf(bulletBase, bulletIn, blitzBase, blitzIn), freeTs
        )
        assertEquals(+10 - 4, net)
    }

    @Test
    fun `session spanning validity edge stays inside`() {
        val freeTs = ms("2026-09-21", 15)
        val base = record(freeTs - 30 * 60_000, won = true, ratingAfter = 1200,
            freeplay = false)
        // Started at 15:59:00 (inside the 60-min window), ends 16:01.
        val edge = record(freeTs + 59 * 60_000 + 120_000, won = true,
            ratingAfter = 1215, freeplay = true)
        val net = freeplaySessionNetRatingChange(listOf(base, edge), freeTs)
        assertEquals(15, net)
    }

    @Test
    fun `settle time is min of window end and idle close`() {
        val freeTs = ms("2026-09-21", 15)
        val windowEnd = freeTs + ChessReadinessEngine.SESSION_VALIDITY_MS
        // No games → settle only at window expiry.
        assertEquals(windowEnd, freeplaySessionSettleAt(freeTs, null))
        // Games played → idle-close 15 min after the LAST game wins when
        // the user stopped early.
        val lastGame = freeTs + 20 * 60_000
        assertEquals(
            lastGame + FREEPLAY_SETTLE_IDLE_MS,
            freeplaySessionSettleAt(freeTs, lastGame)
        )
        // A game ending near the window end → window expiry wins.
        val lateGame = windowEnd - 5 * 60_000
        assertEquals(windowEnd, freeplaySessionSettleAt(freeTs, lateGame))
    }

    @Test
    fun `net positive session refunds after idle close`() {
        val freeTs = ms("2026-09-21", 15)
        // Pre-session baseline 1200; session +8 then idle-closed.
        val pre = record(freeTs - 60 * 60_000, won = true, ratingAfter = 1200,
            freeplay = false)
        val g1 = record(freeTs + 120_000, won = true, ratingAfter = 1208,
            freeplay = true)
        // Settle-at = last game + 15 min idle.
        val settleAt = freeplaySessionSettleAt(freeTs, g1.endTimeMs)
        assertEquals(g1.endTimeMs + FREEPLAY_SETTLE_IDLE_MS, settleAt)
        assertTrue(settleAt <= ms("2026-09-21", 15, 40))
        val net = freeplaySessionNetRatingChange(listOf(pre, g1), freeTs)
        assertEquals(8, net)
        assertTrue(freeplaySessionRefundDue(net))
    }
}
