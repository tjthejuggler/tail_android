package com.example.tail

import com.example.tail.data.chess.ChessReadinessEngine
import com.example.tail.data.chess.evaluateRushRecord
import com.example.tail.widget.ChessEnforcementPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SPECIAL GREEN feature: a new all-time Puzzle Rush
 * record (3- or 5-minute mode) unlocks rated play exactly like a passed
 * pre-game readiness test.
 *
 * Covers the pure record check ([evaluateRushRecord]) and the enforcement
 * policy's treatment of the marked GREEN entry the grant appends.
 */
class ChessSpecialGreenTest {

    private val now = 1_800_000_000_000L // arbitrary fixed epoch ms
    private val minute = 60_000L

    // ── Record check (evaluateRushRecord) ───────────────────────────────

    @Test
    fun `first ever score sets the baseline without a grant`() {
        val outcome = evaluateRushRecord(previousAth = 0, score = 25)
        assertFalse(outcome.isRecord)
        assertTrue(outcome.isNewBaseline)
        assertEquals(25, outcome.newAth)
    }

    @Test
    fun `beating the mode high is a record`() {
        val outcome = evaluateRushRecord(previousAth = 24, score = 31)
        assertTrue(outcome.isRecord)
        assertFalse(outcome.isNewBaseline)
        assertEquals(31, outcome.newAth)
    }

    @Test
    fun `equaling the mode high is not a record`() {
        val outcome = evaluateRushRecord(previousAth = 30, score = 30)
        assertFalse(outcome.isRecord)
        assertEquals(30, outcome.newAth)
    }

    @Test
    fun `below the mode high is not a record and keeps the old high`() {
        val outcome = evaluateRushRecord(previousAth = 30, score = 12)
        assertFalse(outcome.isRecord)
        assertFalse(outcome.isNewBaseline)
        assertEquals(30, outcome.newAth)
    }

    @Test
    fun `zero score never records nor sets a baseline`() {
        val outcome = evaluateRushRecord(previousAth = 30, score = 0)
        assertFalse(outcome.isRecord)
        assertFalse(outcome.isNewBaseline)
        assertEquals(30, outcome.newAth)
    }

    // ── Enforcement policy: the grant behaves like a passed test ────────

    private fun specialGreen(minutesAgo: Long, score: Int = 33) =
        ChessReadinessEngine.ReadinessTest(
            timestamp = now - minutesAgo * minute,
            ccrs = score,
            state = ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name,
            rushScore = score,
            specialGreen = true
        )

    private fun evaluate(
        history: List<ChessReadinessEngine.ReadinessTest>
    ): ChessEnforcementPolicy.Decision =
        ChessEnforcementPolicy.evaluate(
            enforcementEnabledAt = now - 10 * minute,
            history = history,
            session = null,
            penalties = emptyList(),
            now = now,
            audits = emptyList()
        )

    @Test
    fun `fresh special green allows the app with GREEN_SESSION`() {
        val decision = evaluate(history = listOf(specialGreen(minutesAgo = 5)))
        assertEquals(
            ChessEnforcementPolicy.Decision.Allow(ChessEnforcementPolicy.Reason.GREEN_SESSION),
            decision
        )
    }

    @Test
    fun `special green overrides an earlier failed test`() {
        // The user failed a readiness test, then set a new record — the
        // grant (later timestamp) wins the gate.
        val fail = ChessReadinessEngine.ReadinessTest(
            timestamp = now - 30 * minute,
            ccrs = 40,
            state = ChessReadinessEngine.ReadinessState.RED_LIGHT.name
        )
        val decision = evaluate(history = listOf(fail, specialGreen(minutesAgo = 5)))
        assertEquals(
            ChessEnforcementPolicy.Decision.Allow(ChessEnforcementPolicy.Reason.GREEN_SESSION),
            decision
        )
    }

    @Test
    fun `expired special green degrades like any passed test`() {
        // 61 minutes old: validity gone. With nothing else in history the
        // gate falls through to checkGate — the re-test is AVAILABLE again
        // (trust window), exactly as after an expired normal GREEN pass.
        val decision = evaluate(history = listOf(specialGreen(minutesAgo = 61)))
        assertEquals(
            ChessEnforcementPolicy.Decision.Allow(ChessEnforcementPolicy.Reason.TEST_AVAILABLE),
            decision
        )
    }

    @Test
    fun `special green counts as a passed test for the re-test gate`() {
        // Just like a real pass: while the session is live, the engine's
        // re-test gate stays shut (cooldown until validity expires).
        val gate = ChessReadinessEngine.checkGate(
            listOf(specialGreen(minutesAgo = 10)),
            now
        )
        val blocked = gate as ChessReadinessEngine.GateStatus.Blocked
        assertTrue(blocked.error is ChessReadinessEngine.GateError.CooldownActive)
    }

    @Test
    fun `special green penalty still trumps everything`() {
        // A violation penalty outranks even a fresh record grant.
        val penalty = ChessEnforcementPolicy.Penalty(
            timestamp = now - minute,
            gameId = "g1",
            expiresAt = now + 60 * minute
        )
        val decision = ChessEnforcementPolicy.evaluate(
            enforcementEnabledAt = now - 10 * minute,
            history = listOf(specialGreen(minutesAgo = 2)),
            session = null,
            penalties = listOf(penalty),
            now = now,
            audits = emptyList()
        )
        assertEquals(
            ChessEnforcementPolicy.Decision.Block(
                ChessEnforcementPolicy.Reason.PENALTY,
                penalty.expiresAt,
                "Violation penalty — a game was played without authorization. " +
                    "Everything (testing included) stays blocked until the penalty expires."
            ),
            decision
        )
    }
}
