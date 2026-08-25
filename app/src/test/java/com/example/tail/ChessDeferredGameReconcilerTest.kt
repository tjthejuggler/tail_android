package com.example.tail

import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessDeferredGameReconciler.AuditStamp
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessReadinessEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the deferred game pipeline's PURE game-time authorization
 * classifier — the function that decides whether a game (fetched now or
 * hours later) was played inside a valid green-light window, i.e. whether
 * it lands in the approved or unapproved category.
 */
class ChessDeferredGameReconcilerTest {

    private val t0 = 1_755_000_000_000L
    private val validity = ChessReadinessEngine.SESSION_VALIDITY_MS

    private fun test(
        at: Long,
        state: ChessReadinessEngine.ReadinessState
    ) = ChessReadinessEngine.ReadinessTest(timestamp = at, ccrs = 70, state = state.name)

    private fun audit(
        at: Long,
        state: ChessPhase2Engine.OutputState
    ) = AuditStamp(timestamp = at, outputState = state.name)

    @Test
    fun `green test inside its window authorizes the game`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val gameStart = t0 + validity - 1000
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, emptyList(), gameStart)
        )
    }

    @Test
    fun `game after the window expired is unauthorized`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val gameStart = t0 + validity + 1000
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, emptyList(), gameStart)
        )
    }

    @Test
    fun `yellow latest test is unauthorized`() {
        val tests = listOf(
            test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT),
            test(t0 + 60_000, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT)
        )
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, emptyList(), t0 + 120_000)
        )
    }

    @Test
    fun `no test at all is unauthorized`() {
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(emptyList(), emptyList(), t0)
        )
    }

    @Test
    fun `test submitted after the game does not authorize it`() {
        val tests = listOf(test(t0 + 10 * 60_000, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, emptyList(), t0)
        )
    }

    @Test
    fun `red audit between the green test and the game revokes authorization`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val audits = listOf(
            audit(t0 + 60_000, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, audits, t0 + 120_000)
        )
    }

    @Test
    fun `continue audits keep the authorization alive`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val audits = listOf(
            audit(t0 + 60_000, ChessPhase2Engine.OutputState.CONTINUE_RATED),
            audit(t0 + 120_000, ChessPhase2Engine.OutputState.CONTINUE_RATED)
        )
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, audits, t0 + 180_000)
        )
    }

    @Test
    fun `audits before the test or after the game are irrelevant`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val audits = listOf(
            audit(t0 - 60_000, ChessPhase2Engine.OutputState.TERMINATE_SESSION),
            // A later session's bad audit must not retroactively revoke.
            audit(t0 + validity + 60_000, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, audits, t0 + 60_000)
        )
    }

    // ── Start-time authorization (user rule, 2026-08-25) ────────────────────

    @Test
    fun `game started inside the window but ending after it stays authorized`() {
        // The exact bug from 2026-08-25: GREEN test opens a 60-minute window,
        // a 10-minute rapid game starts 5 minutes before expiry and ends
        // 5 minutes after it. Start-based check → authorized.
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val gameStart = t0 + validity - 5 * 60_000
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtPlay(tests, emptyList(), gameStart)
        )
    }

    @Test
    fun `pgn utc start headers parse to epoch seconds`() {
        val pgn = "[Event \"Test\"]\n" +
            "[UTCDate \"2026.08.25\"]\n" +
            "[StartTime \"16:22:01\"]\n" +
            "\n1. e4 e5 1-0"
        assertEquals(1_787_674_921L, com.example.tail.data.pgnStartEpochSec(pgn))
    }

    @Test
    fun `pgn without start headers parses to null`() {
        assertNull(com.example.tail.data.pgnStartEpochSec("[Event \"Test\"]\n\n1. e4"))
        assertNull(com.example.tail.data.pgnStartEpochSec(""))
    }

    @Test
    fun `gameStartMsOf prefers the pgn start and falls back to the clock estimate`() {
        val endMs = 1_787_676_126_000L // 2026-08-25T16:42:06Z
        val withPgn = com.example.tail.data.ChessComGameDetail(
            gameId = 1L, url = "", rated = true, rules = "chess",
            timeClass = "rapid", timeControl = "600", endTime = endMs / 1000,
            whiteUsername = "a", whiteRating = 0, whiteResult = "win",
            blackUsername = "b", blackRating = 0, blackResult = "checkmated",
            whiteAccuracy = null, blackAccuracy = null,
            pgn = "[UTCDate \"2026.08.25\"]\n[StartTime \"16:22:01\"]\n"
        )
        assertEquals(1_787_674_921_000L, ChessDeferredGameReconciler.gameStartMsOf(withPgn, endMs))

        val noPgn = withPgn.copy(pgn = "")
        // 600-second base clock → start estimated 10 minutes before the end.
        assertEquals(endMs - 600_000L, ChessDeferredGameReconciler.gameStartMsOf(noPgn, endMs))
    }
}
