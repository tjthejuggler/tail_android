package com.example.tail.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for the pure chess.com daily-stats computation:
 * classification by time control, minute estimation, and per-day
 * aggregation of games / minutes / wins.
 */
class ChessComDailyStatsTest {

    private val zone = ZoneId.of("UTC")

    private fun game(
        timeControl: String,
        endTime: Long,
        white: String = "me",
        black: String = "opponent",
        whiteResult: String = "win",
        blackResult: String = "checkmated"
    ): ChessComGame = ChessComGame(
        timeClass = "blitz",
        timeControl = timeControl,
        endTime = endTime,
        whiteUsername = white,
        blackUsername = black,
        whiteResult = whiteResult,
        blackResult = blackResult
    )

    private fun epoch(date: String, hour: Int = 12): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 0), zone).toEpochSecond()

    @Test
    fun `classification boundaries match base time`() {
        assertEquals(ChessComType.BULLET, classifyByTimeControl("60"))
        assertEquals(ChessComType.BULLET, classifyByTimeControl("179"))
        assertEquals(ChessComType.BLITZ, classifyByTimeControl("180"))
        assertEquals(ChessComType.BLITZ, classifyByTimeControl("599"))
        assertEquals(ChessComType.RAPID, classifyByTimeControl("600"))
        assertEquals(ChessComType.RAPID, classifyByTimeControl("1800"))
        assertEquals(ChessComType.BULLET, classifyByTimeControl("120+1"))
        assertEquals(null, classifyByTimeControl("1/86400"))
        assertEquals(null, classifyByTimeControl("garbage"))
    }

    @Test
    fun `estimated minutes use base time only`() {
        assertEquals(10.0, estimateGameMinutes("600"), 0.001)
        assertEquals(3.0, estimateGameMinutes("180+2"), 0.001)
        assertEquals(0.0, estimateGameMinutes("1/86400"), 0.001)
    }

    @Test
    fun `aggregates games minutes and wins per day and type`() {
        val games = listOf(
            game("180+2", epoch("2026-08-15"), whiteResult = "win"),        // blitz win
            game("180+2", epoch("2026-08-15"), whiteResult = "checkmated"), // blitz loss
            game("180+2", epoch("2026-08-15"), whiteResult = "agreed"),     // blitz draw
            game("600", epoch("2026-08-15"), whiteResult = "win"),          // rapid win
            game("60", epoch("2026-08-16"), whiteResult = "resigned")       // bullet loss
        )
        val daily = computeDailyChessStats(games, "me", zone)

        val blitz = daily[ChessComType.BLITZ]!!["2026-08-15"]!!
        assertEquals(3, blitz.games)
        assertEquals(9.0, blitz.minutes, 0.001)
        assertEquals(1, blitz.wins) // draw and loss count as outcome 0

        val rapid = daily[ChessComType.RAPID]!!["2026-08-15"]!!
        assertEquals(1, rapid.games)
        assertEquals(10.0, rapid.minutes, 0.001)
        assertEquals(1, rapid.wins)

        val bullet = daily[ChessComType.BULLET]!!["2026-08-16"]!!
        assertEquals(1, bullet.games)
        assertEquals(1.0, bullet.minutes, 0.001)
        assertEquals(0, bullet.wins)
    }

    @Test
    fun `win is detected when user plays black`() {
        val games = listOf(
            game(
                "300", epoch("2026-08-15"),
                white = "opponent", black = "Me",
                whiteResult = "timeout", blackResult = "win"
            )
        )
        val daily = computeDailyChessStats(games, "me", zone)
        assertEquals(1, daily[ChessComType.BLITZ]!!["2026-08-15"]!!.wins)
    }

    @Test
    fun `ignores games without the user and daily games`() {
        val games = listOf(
            game("180", epoch("2026-08-15"), white = "otherA", black = "otherB"),
            game("1/86400", epoch("2026-08-15"))
        )
        val daily = computeDailyChessStats(games, "me", zone)
        assertEquals(emptyMap<ChessComType, DailyStatsMap>(), daily)
    }

    @Test
    fun `username matching is case insensitive`() {
        val games = listOf(game("180", epoch("2026-08-15"), white = "ME"))
        val daily = computeDailyChessStats(games, "me", zone)
        assertEquals(1, daily[ChessComType.BLITZ]!!["2026-08-15"]!!.games)
    }
}
