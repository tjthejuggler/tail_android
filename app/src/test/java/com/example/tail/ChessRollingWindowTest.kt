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
 * ROLLING rated-play window (2026-09-09 redesign): the 30-minute idle
 * clock re-anchors to every CONTINUE_RATED audit, so playing well keeps
 * the session authorized indefinitely — only 30 minutes WITHOUT being
 * in a game (or a Yellow/Red audit) closes it and demands a new test.
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
        now: Long
    ): Long? = ChessPhase2Engine.rollingWindowExpiresAt(green, audits, now)

    @Test
    fun `no audits - idle closes the window after 30 minutes`() {
        assertEquals(green + validity, expiry(emptyList(), green + 1))
        assertNull(expiry(emptyList(), green + 31 * min))
        assertNull(expiry(emptyList(), green + validity))
    }

    @Test
    fun `clean audit re-anchors the idle clock`() {
        val audit = green + 10 * min
        // 35 min after the GREEN test (only 25 min after the audit): live.
        assertEquals(
            audit + validity,
            expiry(listOf(audit to cont), green + 35 * min)
        )
        // 41 min after the GREEN test exceeds the 30-min idle close.
        assertNull(expiry(listOf(audit to cont), green + 41 * min))
        assertNull(expiry(listOf(audit to cont), audit + validity))
    }

    @Test
    fun `a chain of clean audits stays open indefinitely`() {
        val audits = (1..5).map { (green + it * 20 * min) to cont }
        val last = audits.last().first
        assertEquals(last + validity, expiry(audits, last + 29 * min))
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
        val start = green + 35 * min // 35 min after GREEN …
        val audit = green + 10 * min // … but only 25 min after a clean audit
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
