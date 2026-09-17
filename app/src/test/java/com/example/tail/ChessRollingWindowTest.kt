package com.example.tail

import com.example.tail.data.ChessReadinessEngine
import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessPhase2Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROLLING rated-play window (idle close tightened 30 → 10 min on
 * 2026-09-12; raised to 15 min and made REAL-no-play based on
 * 2026-09-17): the idle clock re-anchors to every CONTINUE_RATED audit
 * AND to every rated game actually played whose start was still inside
 * the window, so playing well keeps the session authorized indefinitely
 * — only 15 minutes WITHOUT being in a game (or a Yellow/Red audit)
 * closes it and demands a new test. The korosh935milad false positive
 * (a real 10m22s break measured as a 17m37s audit gap) is impossible
 * under this rule.
 */
class ChessRollingWindowTest {

    private val validity =
        ChessPhase2Engine.RATED_IDLE_CLOSE_MINUTES * 60_000
    private val green = 1_789_000_000_000L
    private val cont = ChessPhase2Engine.OutputState.CONTINUE_RATED.name
    private val pivot = ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
    private val min = 60_000L

    private fun expiry(
        audits: List<Pair<Long, String>>,
        now: Long,
        games: List<Pair<Long, Long>> = emptyList()
    ): Long? = ChessPhase2Engine.rollingWindowExpiresAt(green, audits, now, games)

    @Test
    fun `no audits - idle closes the window after 15 minutes`() {
        assertEquals(green + validity, expiry(emptyList(), green + 1))
        // 14 min of idleness: still live under the 15-minute close …
        assertEquals(green + validity, expiry(emptyList(), green + 14 * min))
        // … 15 min is the boundary (>= closes) and beyond is expired.
        assertNull(expiry(emptyList(), green + validity))
        assertNull(expiry(emptyList(), green + 16 * min))
    }

    @Test
    fun `a played game extends the window to its end`() {
        // Game played 4–9 min after the GREEN test, evaluated 13 min in:
        // the pure audit chain would have gone idle at 15, but the real
        // play anchor is the game's END (9 min) → live until 24 min.
        val game = (green + 4 * min) to (green + 9 * min)
        assertEquals(
            game.second + validity,
            expiry(emptyList(), green + 13 * min, listOf(game))
        )
        // 24 min after the test (15 after the game's end): closed.
        assertNull(expiry(emptyList(), game.second + validity, listOf(game)))
    }

    @Test
    fun `a game that began after the window closed does not extend it`() {
        // Game 0–4 min after the test; a second game started 20 min in —
        // past even the first game's extension (4 + 15 = 19) — must not
        // retroactively legitimize itself.
        val early = (green) to (green + 4 * min)
        val late = (green + 20 * min) to (green + 24 * min)
        assertNull(expiry(emptyList(), green + 21 * min, listOf(early, late)))
    }

    @Test
    fun `back to back games keep the window open across a long session`() {
        // The 2026-09-17 korosh935milad regression: GREEN test at t0, game
        // A ended 7m15s in, game B STARTED 17m37s after the test — only
        // 10m22s of real no-play time, inside the 15-minute idle close →
        // authorized. The old audit-only chain measured 17m37s from the
        // test (no audit had re-anchored) and wrongly flagged game B.
        val gameA = green to green + 7 * min + 15_000L   // ended 7m15s in
        val gameBStart = green + 17 * min + 37_000L      // 10m22s after game A
        assertTrue(
            ChessPhase2Engine.rollingWindowExpiresAt(
                green, emptyList(), gameBStart, listOf(gameA)
            ) != null
        )
    }

    @Test
    fun `clean audit re-anchors the idle clock`() {
        val audit = green + 5 * min
        // 8 min after the GREEN test (only 3 min after the audit): live.
        assertEquals(
            audit + validity,
            expiry(listOf(audit to cont), green + 8 * min)
        )
        // 10 min after the audit exceeds the 10-min idle close.
        assertNull(expiry(listOf(audit to cont), audit + validity))
    }

    @Test
    fun `a chain of clean audits stays open indefinitely`() {
        // 9-minute gaps keep every audit inside the 10-min idle close.
        val audits = (1..5).map { (green + it * 9 * min) to cont }
        val last = audits.last().first
        assertEquals(last + validity, expiry(audits, last + 9 * min))
    }

    @Test
    fun `any flag revokes the window`() {
        val clean = green + 10 * 60_000
        val flag = green + 20 * 60_000
        assertNull(expiry(listOf(clean to cont, flag to pivot), flag + 1))
    }

    @Test
    fun `audits outside the window are ignored`() {
        // Audit 2 h after the test can't rescue an already-expired window.
        val late = green + 2 * validity
        assertNull(expiry(listOf(late to cont), late - 1))
    }

    // ── Historical mirror used to classify games at their START ──────────

    @Test
    fun `authorizedAtPlay honors the rolling window`() {
        fun test(state: ChessReadinessEngine.ReadinessState, ts: Long) =
            ChessReadinessEngine.ReadinessTest(
                timestamp = ts, ccrs = 80, state = state.name
            )
        val start = green + 18 * min // 18 min after GREEN …
        val audit = green + 10 * min // … but only 8 min after a clean audit
        assertTrue(
            ChessDeferredGameReconciler.authorizedAtPlay(
                listOf(test(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, green)),
                listOf(ChessDeferredGameReconciler.AuditStamp(audit, cont)),
                start
            )
        )
        // Without the clean audit the same start time is unauthorized.
        assertFalse(
            ChessDeferredGameReconciler.authorizedAtPlay(
                listOf(test(ChessReadinessEngine.ReadinessState.GREEN_LIGHT, green)),
                emptyList(),
                start
            )
        )
    }
}
