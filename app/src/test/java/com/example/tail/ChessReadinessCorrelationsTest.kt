package com.example.tail

import com.example.tail.data.Phase2AuditRecord
import com.example.tail.data.ReadinessGameRecord
import com.example.tail.data.SURVIVAL_UNLIMITED_CUTOFF_MS
import com.example.tail.data.V3CorrelationConfig
import com.example.tail.data.V3CorrelationRun
import com.example.tail.data.computeV3CorrelationStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pre-game ↔ performance correlation calculator —
 * reflex/survival stage results vs the following rated session (and the
 * two stages against each other).
 */
class ChessReadinessCorrelationsTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    /** Post-cutoff base timestamp (a day after the cutoff). */
    private val t0 = SURVIVAL_UNLIMITED_CUTOFF_MS + day

    private fun run(
        timestamp: Long,
        verdict: String = "PASS",
        puzzles: Int = 15,
        rt: Double? = 280.0
    ) = V3CorrelationRun(
        timestamp = timestamp,
        verdict = verdict,
        puzzlesPassed = puzzles,
        target = 15,
        survivalDurationMs = 4 * 60_000L,
        reflexLapses = 0,
        reflexFalseStarts = 0,
        reflexMeanRtMs = rt
    )

    private fun game(endMs: Long, won: Boolean, ratingAfter: Int? = null) =
        ReadinessGameRecord(
            endTimeMs = endMs, type = "BLITZ", opponent = "opp", won = won,
            minutes = 5.0, ccrsAtPlay = null, stateAtPlay = null,
            authorized = true, ratingAfter = ratingAfter
        )

    private fun audit(
        timestamp: Long,
        accuracy: Double,
        counted: Boolean = true,
        acpl: Double? = null,
        blunders: Int? = null,
        unforced: Int? = null
    ) = Phase2AuditRecord(
        timestamp = timestamp,
        timeControl = "BLITZ",
        outputState = "CONTINUE_SESSION",
        deltaE = 0.0,
        caps2Accuracy = accuracy,
        accuracyCounted = counted,
        strain = 10.0,
        analysisAcpl = acpl,
        blunders = blunders,
        unforcedBlunders = unforced
    )

    private val cfg = V3CorrelationConfig(minPairs = 4)

    @Test
    fun `pre-cutoff runs are excluded from the correlations`() {
        val s = computeV3CorrelationStats(
            results = listOf(
                // One run BEFORE the cutoff (censored solved count)…
                run(SURVIVAL_UNLIMITED_CUTOFF_MS - 1),
                // …and one after.
                run(t0)
            ),
            games = emptyList(),
            audits = emptyList(),
            config = cfg
        )
        assertEquals(2, s.totalRunsSeen)
        assertEquals(1, s.eligibleRuns)
    }

    @Test
    fun `reflex-fail runs are excluded - they never solved puzzles`() {
        val s = computeV3CorrelationStats(
            results = listOf(run(t0, verdict = "FAIL_REFLEX"), run(t0 + day)),
            games = emptyList(),
            audits = emptyList(),
            config = cfg
        )
        assertEquals(1, s.eligibleRuns)
        assertEquals("PASS", s.points.single().run.verdict)
    }

    @Test
    fun `following session joins rated games and counted audits within window`() {
        val s = computeV3CorrelationStats(
            results = listOf(run(t0)),
            games = listOf(
                game(t0 + hour, won = true, ratingAfter = 1500),
                game(t0 + 2 * hour, won = false, ratingAfter = 1490),
                game(t0 + 10 * hour, won = true)                       // outside 6 h
            ),
            audits = listOf(
                audit(t0 + hour, accuracy = 80.0),
                audit(t0 + 2 * hour, accuracy = 60.0),
                audit(t0 + 10 * hour, accuracy = 99.0)
            ),
            config = cfg
        )
        assertEquals(1, s.matchedSessions)
        val sess = s.points.single().session!!
        assertEquals(2, sess.games)
        assertEquals(1, sess.wins)
        assertEquals(50.0, sess.winRate, 1e-9)
        assertEquals(70.0, sess.avgAccuracy!!, 1e-9)
        assertEquals(2, sess.accuracyGames)
        assertEquals(-10, sess.eloDelta)
    }

    @Test
    fun `bypassed accuracy audits do not feed the accuracy pairs`() {
        val s = computeV3CorrelationStats(
            results = listOf(run(t0)),
            games = listOf(game(t0 + hour, won = true)),
            audits = listOf(audit(t0 + hour, accuracy = 80.0, counted = false)),
            config = cfg
        )
        assertTrue(s.rtAccuracyPairs.isEmpty())
        assertTrue(s.solvedAccuracyPairs.isEmpty())
        assertNull(s.rtAccuracyR)
        assertNull(s.solvedAccuracyR)
    }

    @Test
    fun `rt vs solved pairs use the same run - no session needed`() {
        val s = computeV3CorrelationStats(
            results = listOf(
                run(t0, rt = 250.0, puzzles = 25),
                run(t0 + day, rt = 300.0, puzzles = 20),
                run(t0 + 2 * day, rt = 350.0, puzzles = 15),
                run(t0 + 3 * day, rt = 400.0, puzzles = 10)
            ),
            games = emptyList(),
            audits = emptyList(),
            config = cfg
        )
        assertEquals(4, s.rtSolvedPairs.size)
        assertEquals(250.0, s.rtSolvedPairs[0].x, 1e-9)
        assertEquals(25.0, s.rtSolvedPairs[0].y, 1e-9)
        // Perfect inverse relation → r = −1.
        assertEquals(-1.0, s.rtSolvedR!!, 1e-9)
    }

    @Test
    fun `fewer pairs than the minimum yields null coefficients`() {
        val s = computeV3CorrelationStats(
            results = listOf(run(t0), run(t0 + day), run(t0 + 2 * day)),
            games = emptyList(),
            audits = emptyList(),
            config = cfg
        )
        assertEquals(3, s.rtSolvedPairs.size)
        assertNull(s.rtSolvedR)
    }

    @Test
    fun `correlation is strongly negative when slow reflexes precede low accuracy`() {
        // 6 eligible runs, each followed by one audited rated game.
        // Reflex RT climbs 240→490, puzzles solved falls 25→20, and the
        // session accuracy falls 90→40 (both stage metrics anti-correlate
        // with the following-session outcome).
        val runs = (0 until 6).map {
            run(t0 + it * 2 * day, rt = 240.0 + it * 50, puzzles = 25 - it)
        }
        val games = runs.map { game(it.timestamp + hour, won = it.reflexMeanRtMs!! < 350.0) }
        val audits = runs.map {
            audit(it.timestamp + hour, accuracy = 90.0 - it.reflexMeanRtMs!! + 240.0)
        }
        val s = computeV3CorrelationStats(runs, games, audits, cfg)
        assertNotNull(s.rtAccuracyR)
        assertTrue("expected strong negative r, got ${s.rtAccuracyR}", s.rtAccuracyR!! < -0.9)
        assertNotNull(s.solvedAccuracyR)
    }

    @Test
    fun `acpl and unforced blunder pairs join from analyzed audits`() {
        // 6 eligible runs; RT climbs, and the following-session Stockfish
        // ACPL climbs while unforced blunders climb too.
        val runs = (0 until 6).map {
            run(t0 + it * 2 * day, rt = 240.0 + it * 50, puzzles = 25 - it)
        }
        val games = runs.map { game(it.timestamp + hour, won = false) }
        val audits = runs.mapIndexed { i, r ->
            audit(
                r.timestamp + hour,
                accuracy = 80.0,
                acpl = 20.0 + i * 10.0,      // 20 → 70
                blunders = i,               // 0 → 5
                unforced = maxOf(0, i - 1)  // 0,0,1,2,3,4
            )
        }
        val s = computeV3CorrelationStats(runs, games, audits, cfg)
        assertEquals(6, s.rtAcplPairs.size)
        assertEquals(70.0, s.rtAcplPairs.last().y, 1e-9)
        // RT ↑ with ACPL ↑ → perfect positive r.
        assertEquals(1.0, s.rtAcplR!!, 1e-9)
        assertEquals(6, s.solvedAcplPairs.size)
        // Puzzles solved ↓ while ACPL ↑ → perfect inverse r.
        assertEquals(-1.0, s.solvedAcplR!!, 1e-9)
        assertEquals(6, s.rtUnforcedPairs.size)
        assertEquals(4.0, s.rtUnforcedPairs.last().y, 1e-9)
        // Unforced blunders [0,0,1,2,3,4] vs linearly rising RT — strongly
        // positive but not perfect (the flat leading pair breaks linearity).
        assertTrue("expected strong positive r, got ${s.rtUnforcedR}", s.rtUnforcedR!! > 0.9)
        assertEquals(6, s.solvedBlundersPairs.size)
        assertEquals(5.0, s.solvedBlundersPairs.last().y, 1e-9)
        // Session outcomes without analysis (win rate/elo) still computed.
        assertEquals(6, s.rtWinRatePairs.size)
    }

    @Test
    fun `sessions without stockfish analysis produce no acpl or blunder pairs`() {
        val runs = (0 until 4).map { run(t0 + it * 2 * day) }
        val games = runs.map { game(it.timestamp + hour, won = true) }
        val audits = runs.map { audit(it.timestamp + hour, accuracy = 80.0) }
        val s = computeV3CorrelationStats(runs, games, audits, cfg)
        assertTrue(s.rtAcplPairs.isEmpty())
        assertTrue(s.solvedUnforcedPairs.isEmpty())
        assertNull(s.rtAcplR)
        assertNull(s.solvedBlundersR)
        // Accuracy still works.
        assertEquals(4, s.rtAccuracyPairs.size)
    }

    @Test
    fun `empty inputs yield zeroed stats`() {
        val s = computeV3CorrelationStats(
            results = emptyList(), games = emptyList(), audits = emptyList(), config = cfg
        )
        assertEquals(0, s.totalRunsSeen)
        assertEquals(0, s.eligibleRuns)
        assertEquals(0, s.matchedSessions)
        assertNull(s.rtAccuracyR)
        assertNull(s.rtSolvedR)
    }
}
