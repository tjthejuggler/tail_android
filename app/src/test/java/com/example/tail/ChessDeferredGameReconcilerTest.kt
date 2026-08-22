package com.example.tail

import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessDeferredGameReconciler.AuditStamp
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessReadinessEngine
import org.junit.Assert.assertFalse
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
        val gameEnd = t0 + validity - 1000
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, emptyList(), gameEnd)
        )
    }

    @Test
    fun `game after the window expired is unauthorized`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val gameEnd = t0 + validity + 1000
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, emptyList(), gameEnd)
        )
    }

    @Test
    fun `yellow latest test is unauthorized`() {
        val tests = listOf(
            test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT),
            test(t0 + 60_000, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT)
        )
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, emptyList(), t0 + 120_000)
        )
    }

    @Test
    fun `no test at all is unauthorized`() {
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtGameEnd(emptyList(), emptyList(), t0)
        )
    }

    @Test
    fun `test submitted after the game does not authorize it`() {
        val tests = listOf(test(t0 + 10 * 60_000, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, emptyList(), t0)
        )
    }

    @Test
    fun `red audit between the green test and the game revokes authorization`() {
        val tests = listOf(test(t0, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        val audits = listOf(
            audit(t0 + 60_000, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, audits, t0 + 120_000)
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
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, audits, t0 + 180_000)
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
            ChessDeferredGameReconciler.authorizedAtGameEnd(tests, audits, t0 + 60_000)
        )
    }
}
