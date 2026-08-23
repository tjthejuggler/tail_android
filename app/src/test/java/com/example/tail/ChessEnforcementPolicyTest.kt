package com.example.tail

import com.example.tail.widget.ChessEnforcementPolicy
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2Store
import com.example.tail.widget.ChessReadinessEngine
import com.example.tail.widget.ChessReadinessStore
import com.example.tail.widget.ReadinessSession
import com.example.tail.widget.SessionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Chess Guard enforcement policy — the pure decision
 * core that decides whether the chess APP may be used at all.
 */
class ChessEnforcementPolicyTest {

    private val now = 1_800_000_000_000L // arbitrary fixed epoch ms
    private val minute = 60_000L
    private val enabledAt = now - 10 * minute

    private fun test(
        minutesAgo: Long,
        ccrs: Int,
        state: ChessReadinessEngine.ReadinessState
    ) = ChessReadinessEngine.ReadinessTest(
        timestamp = now - minutesAgo * minute,
        ccrs = ccrs,
        state = state.name
    )

    private fun session(
        step: SessionStep,
        updatedMinutesAgo: Long
    ) = ReadinessSession(
        startedAt = now - 30 * minute,
        updatedAt = now - updatedMinutesAgo * minute,
        step = step,
        puzzleIndex = 0,
        sleepScore = 70,
        sleepFromGarmin = true,
        clarityScores = listOf(3, 3, 3),
        puzzleTimesSec = emptyList(),
        stepStartedAt = now - updatedMinutesAgo * minute
    )

    private fun audit(
        minutesAgo: Long,
        outputState: ChessPhase2Engine.OutputState
    ) = ChessPhase2Store.Phase2Audit(
        timestamp = now - minutesAgo * minute,
        timeControl = "RAPID",
        outputState = outputState.name,
        deltaE = -0.5,
        caps2Accuracy = 60.0,
        accuracyCounted = true
    )

    private fun evaluate(
        history: List<ChessReadinessEngine.ReadinessTest> = emptyList(),
        session: ReadinessSession? = null,
        penalties: List<ChessEnforcementPolicy.Penalty> = emptyList(),
        enforcementEnabledAt: Long = enabledAt,
        lastAudit: ChessPhase2Store.Phase2Audit? = null
    ): ChessEnforcementPolicy.Decision =
        ChessEnforcementPolicy.evaluate(
            enforcementEnabledAt = enforcementEnabledAt,
            history = history,
            session = session,
            penalties = penalties,
            now = now,
            lastAudit = lastAudit
        )

    // ── Feature toggle ──────────────────────────────────────────────────

    @Test
    fun `feature off allows everything`() {
        val decision = evaluate(
            history = listOf(test(5, 20, ChessReadinessEngine.ReadinessState.RED_LIGHT)),
            enforcementEnabledAt = 0L
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.FEATURE_OFF,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    // ── GREEN sessions ──────────────────────────────────────────────────

    @Test
    fun `green session inside validity allows the app`() {
        val decision = evaluate(
            history = listOf(test(20, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.GREEN_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `expired green session opens the trust window`() {
        // 70 min ago: validity (60 min) AND cool-down both over → a new
        // test is possible. The app OPENS so the test can be taken; if
        // the user plays instead, the reconciler penalty locks them out.
        val decision = evaluate(
            history = listOf(test(70, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.TEST_AVAILABLE,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    // ── YELLOW / RED ────────────────────────────────────────────────────

    @Test
    fun `yellow session allows casual play`() {
        // YELLOW permits unrated games and puzzles — the app opens, the
        // guard shows the rated-game warning, and only a detected RATED
        // game triggers the 24 h penalty.
        val decision = evaluate(
            history = listOf(test(10, 60, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.YELLOW_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `expired yellow session opens the trust window`() {
        // 70 min ago: validity (60 min) is over and the re-test gate is
        // open — the app opens so the test can be taken.
        val decision = evaluate(
            history = listOf(test(70, 60, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.TEST_AVAILABLE,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `red test inside recovery rest blocks`() {
        // ccrs 50 → POOR tier → 60 min rest; only 20 min have passed.
        val decision = evaluate(
            history = listOf(test(20, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
        val block = decision as ChessEnforcementPolicy.Decision.Block
        assertEquals(ChessEnforcementPolicy.Reason.REST_PERIOD, block.reason)
        assertEquals(now - 20 * minute + ChessReadinessEngine.REST_MS_POOR, block.retryAt)
    }

    @Test
    fun `red test long after rest expired opens the trust window`() {
        // ccrs 50 → POOR tier → 60 min rest; 200 min have passed, so the
        // rest (and any cool-down) is over and a new test is allowed.
        val decision = evaluate(
            history = listOf(test(200, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT))
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.TEST_AVAILABLE,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `daily test cap blocks`() {
        // 8 tests inside the rolling 24 h window (150-min spacing keeps
        // even the oldest at 1200 min < 1440 min).
        val history = (1..8).map {
            test(it * 150L, 90, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)
        }
        val decision = evaluate(history = history)
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
        assertEquals(
            ChessEnforcementPolicy.Reason.DAILY_CAP,
            (decision as ChessEnforcementPolicy.Decision.Block).reason
        )
    }

    @Test
    fun `no tests at all opens the trust window`() {
        val decision = evaluate()
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.TEST_AVAILABLE,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    // ── In-progress test = the anti-deadlock pass ───────────────────────

    @Test
    fun `in-progress test at a chess step allows even after a red test`() {
        val decision = evaluate(
            history = listOf(test(20, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT)),
            session = session(SessionStep.PUZZLE_GO, updatedMinutesAgo = 2)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.TEST_IN_PROGRESS,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `in-progress test at a tail-only step still blocks during a lockout`() {
        // The SLEEP step is not a chess step, and the RED rest period
        // (from 20 min ago) keeps the app blocked despite the session.
        val decision = evaluate(
            history = listOf(test(20, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT)),
            session = session(SessionStep.SLEEP, updatedMinutesAgo = 2)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
    }

    @Test
    fun `stale session at a chess step no longer allows during a lockout`() {
        // Session last touched 11 min ago — past the 10-minute step
        // timeout the "test pass" is dead, and the RED rest period
        // (from 20 min ago) keeps the app blocked.
        val decision = evaluate(
            history = listOf(test(20, 50, ChessReadinessEngine.ReadinessState.RED_LIGHT)),
            session = session(SessionStep.RUSH_GO, updatedMinutesAgo = 11)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
    }

    // ── Violation penalties ─────────────────────────────────────────────

    @Test
    fun `active penalty overrides even a fresh green session`() {
        val decision = evaluate(
            history = listOf(test(5, 90, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            penalties = listOf(
                ChessEnforcementPolicy.Penalty(
                    timestamp = now - 5 * minute,
                    gameId = "123",
                    expiresAt = now + 115 * minute
                )
            )
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
        val block = decision as ChessEnforcementPolicy.Decision.Block
        assertEquals(ChessEnforcementPolicy.Reason.PENALTY, block.reason)
        assertEquals(now + 115 * minute, block.retryAt)
    }

    @Test
    fun `expired penalty is ignored`() {
        val decision = evaluate(
            history = listOf(test(5, 90, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            penalties = listOf(
                ChessEnforcementPolicy.Penalty(
                    timestamp = now - 130 * minute,
                    gameId = "123",
                    expiresAt = now - 10 * minute
                )
            )
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.GREEN_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `penalty duration is a full 24 hours`() {
        // User's rule (2026-08-23): an unauthorized game costs the whole
        // next day — the earlier 2-hour deterrent was too easy to sit out.
        assertEquals(24L * 60 * 60 * 1000, ChessEnforcementPolicy.PENALTY_DURATION_MS)
    }

    // ── Phase 2 audits limiting an active session ────────────────────────

    @Test
    fun `pivot audit after a green test downgrades to casual-only`() {
        // The user passed the readiness test, then a bad audited game
        // PIVOTED the session — re-entering the chess app must show the
        // yellow casual-only warning, not stay fully unlocked.
        val decision = evaluate(
            history = listOf(test(20, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            lastAudit = audit(10, ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.YELLOW_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `terminate audit after a green test blocks until re-test opens`() {
        val decision = evaluate(
            history = listOf(test(20, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            lastAudit = audit(10, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
        val block = decision as ChessEnforcementPolicy.Decision.Block
        assertEquals(ChessEnforcementPolicy.Reason.SESSION_TERMINATED, block.reason)
        // Green test 20 min ago → the 60-min cool-down ends in 40 min.
        assertEquals(now - 20 * minute + ChessReadinessEngine.COOLDOWN_MS, block.retryAt)
    }

    @Test
    fun `continue audit after a green test leaves the session green`() {
        val decision = evaluate(
            history = listOf(test(20, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            lastAudit = audit(10, ChessPhase2Engine.OutputState.CONTINUE_RATED)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.GREEN_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `audit older than the last test does not limit the session`() {
        // The green test came AFTER the audit — a fresh pass re-authorized play.
        val decision = evaluate(
            history = listOf(test(5, 85, ChessReadinessEngine.ReadinessState.GREEN_LIGHT)),
            lastAudit = audit(30, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.GREEN_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }

    @Test
    fun `terminate audit after a yellow test blocks casual play too`() {
        val decision = evaluate(
            history = listOf(test(15, 60, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT)),
            lastAudit = audit(5, ChessPhase2Engine.OutputState.TERMINATE_SESSION)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Block)
        assertEquals(
            ChessEnforcementPolicy.Reason.SESSION_TERMINATED,
            (decision as ChessEnforcementPolicy.Decision.Block).reason
        )
    }

    @Test
    fun `pivot audit after a yellow test stays casual-only`() {
        val decision = evaluate(
            history = listOf(test(15, 60, ChessReadinessEngine.ReadinessState.YELLOW_LIGHT)),
            lastAudit = audit(5, ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS)
        )
        assertTrue(decision is ChessEnforcementPolicy.Decision.Allow)
        assertEquals(
            ChessEnforcementPolicy.Reason.YELLOW_SESSION,
            (decision as ChessEnforcementPolicy.Decision.Allow).reason
        )
    }
}
