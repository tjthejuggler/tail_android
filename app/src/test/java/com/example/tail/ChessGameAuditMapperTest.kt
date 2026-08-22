package com.example.tail

import com.example.tail.data.ChessComGameDetail
import com.example.tail.widget.ChessGameAuditMapper
import com.example.tail.widget.ChessPhase2Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared-link game audit mapper — the bridge between the
 * chess.com share sheet ("Check out this #chess game: … chess.com/live/game/…")
 * and the Phase 2 audit engine.
 */
class ChessGameAuditMapperTest {

    private val M = ChessGameAuditMapper

    private fun detail(
        rated: Boolean = true,
        rules: String = "chess",
        timeControl: String = "180+2",
        whiteUsername: String = "opponent",
        whiteRating: Int = 1450,
        whiteResult: String = "checkmated",
        blackUsername: String = "jugglah",
        blackRating: Int = 1500,
        blackResult: String = "win",
        whiteAccuracy: Double? = 68.4,
        blackAccuracy: Double? = 81.2,
        pgn: String = FULL_PGN
    ) = ChessComGameDetail(
        gameId = 173067813820L,
        url = "https://www.chess.com/game/live/173067813820",
        rated = rated,
        rules = rules,
        timeClass = "blitz",
        timeControl = timeControl,
        endTime = 1_755_300_000L,
        whiteUsername = whiteUsername,
        whiteRating = whiteRating,
        whiteResult = whiteResult,
        blackUsername = blackUsername,
        blackRating = blackRating,
        blackResult = blackResult,
        whiteAccuracy = whiteAccuracy,
        blackAccuracy = blackAccuracy,
        pgn = pgn
    )

    // ── Shared-link parsing ────────────────────────────────────────────────

    @Test
    fun `parses game id from the real share-sheet text`() {
        val shared = "Check out this #chess game: jugglah vs darknessdecay - " +
            "https://www.chess.com/live/game/173067813820"
        assertEquals(173067813820L, M.parseSharedGameId(shared))
    }

    @Test
    fun `parses game id from the archive api url format`() {
        assertEquals(
            173067813820L,
            M.parseSharedGameId("https://www.chess.com/game/live/173067813820")
        )
    }

    @Test
    fun `parses game id from the legacy url format`() {
        assertEquals(42L, M.parseSharedGameId("https://www.chess.com/game/42"))
    }

    @Test
    fun `returns null for text without a chess game link`() {
        assertNull(M.parseSharedGameId("Check out this #chess game, no link"))
        assertNull(M.parseSharedGameId("https://lichess.org/abcdefgh"))
    }

    @Test
    fun `parses both player names from the real share-sheet text`() {
        val shared = "Check out this #chess game: jugglah vs Dinmuhamed_055 - " +
            "https://www.chess.com/live/game/173349316168"
        assertEquals(listOf("jugglah", "Dinmuhamed_055"), M.parseShareUsernames(shared))
    }

    @Test
    fun `returns no player names for text without a vs pair`() {
        assertTrue(M.parseShareUsernames("https://www.chess.com/game/live/42").isEmpty())
        assertTrue(M.parseShareUsernames("no players here at all").isEmpty())
    }

    @Test
    fun `toLightGame projects every field the readiness log needs`() {
        val light = M.toLightGame(detail())
        assertEquals("blitz", light.timeClass)
        assertEquals("180+2", light.timeControl)
        assertEquals(1_755_300_000L, light.endTime)
        assertEquals("opponent", light.whiteUsername)
        assertEquals("jugglah", light.blackUsername)
        assertEquals("checkmated", light.whiteResult)
        assertEquals("win", light.blackResult)
        assertTrue(light.rated)
        assertEquals("chess", light.rules)
        assertEquals(1450, light.whiteRating)
        assertEquals(1500, light.blackRating)
    }

    @Test
    fun `trailing game id handles trailing slash and rejects non-numeric`() {
        assertEquals(999L, M.trailingGameId("https://www.chess.com/game/live/999/"))
        assertNull(M.trailingGameId("https://www.chess.com/game/live/abc"))
    }

    // ── Result / time-control mapping ──────────────────────────────────────

    @Test
    fun `win maps to win`() {
        assertEquals(ChessPhase2Engine.GameResult.WIN, M.resultFor("win"))
    }

    @Test
    fun `all draw result strings map to draw`() {
        listOf("agreed", "repetition", "stalemate", "insufficient", "50move", "timevsinsufficient")
            .forEach { assertEquals(ChessPhase2Engine.GameResult.DRAW, M.resultFor(it)) }
    }

    @Test
    fun `loss result strings map to loss`() {
        listOf("checkmated", "resigned", "timeout", "abandoned")
            .forEach { assertEquals(ChessPhase2Engine.GameResult.LOSS, M.resultFor(it)) }
    }

    @Test
    fun `time control classification matches the habit-link thresholds`() {
        assertEquals(ChessPhase2Engine.TimeControl.BULLET, M.timeControlFor("60"))
        assertEquals(ChessPhase2Engine.TimeControl.BULLET, M.timeControlFor("120+1"))
        assertEquals(ChessPhase2Engine.TimeControl.BLITZ, M.timeControlFor("180"))
        assertEquals(ChessPhase2Engine.TimeControl.BLITZ, M.timeControlFor("300+2"))
        assertEquals(ChessPhase2Engine.TimeControl.RAPID, M.timeControlFor("600"))
        assertEquals(ChessPhase2Engine.TimeControl.RAPID, M.timeControlFor("1800+20"))
        assertNull(M.timeControlFor("1/86400"))
        assertNull(M.timeControlFor(""))
    }

    @Test
    fun `estimated minutes use the base clock`() {
        assertEquals(10.0, M.estimateMinutes("600"), 1e-9)
        assertEquals(3.0, M.estimateMinutes("180+2"), 1e-9)
        assertEquals(0.0, M.estimateMinutes("1/86400"), 1e-9)
    }

    // ── PGN move counting ──────────────────────────────────────────────────

    @Test
    fun `counts the last move number of a pgn with clock annotations`() {
        assertEquals(17, M.countPgnMoves(FULL_PGN))
    }

    @Test
    fun `pgn without moves yields zero`() {
        assertEquals(0, M.countPgnMoves(""))
        assertEquals(0, M.countPgnMoves("[Event \"Live Chess\"]\n[Result \"1-0\"]"))
    }

    // ── buildInput ─────────────────────────────────────────────────────────

    @Test
    fun `maps a full rated game with the user as black`() {
        val mapping = M.buildInput(detail(), "Jugglah") as ChessGameAuditMapper.Mapping.Ready

        assertEquals(ChessPhase2Engine.TimeControl.BLITZ, mapping.input.timeControl)
        assertEquals(1500, mapping.input.userRating)
        assertEquals(1450, mapping.input.opponentRating)
        assertEquals(ChessPhase2Engine.GameResult.WIN, mapping.input.gameResult)
        assertEquals(81.2, mapping.input.caps2Accuracy, 1e-9)
        assertTrue(mapping.accuracyKnown)
        assertFalse(mapping.input.shortGame)
        assertEquals(0, mapping.input.blunderCount)
        // 3-minute blitz game, fresh session
        assertEquals(3, mapping.input.sessionElapsedMins)
        assertEquals(3.0, mapping.estimatedMinutes, 1e-9)
    }

    @Test
    fun `maps the white side result when the user played white`() {
        val mapping = M.buildInput(
            detail(
                whiteUsername = "jugglah", whiteResult = "agreed",
                blackUsername = "opponent", blackResult = "agreed"
            ),
            "jugglah"
        ) as ChessGameAuditMapper.Mapping.Ready

        assertEquals(ChessPhase2Engine.GameResult.DRAW, mapping.input.gameResult)
        assertEquals(68.4, mapping.input.caps2Accuracy, 1e-9)
    }

    @Test
    fun `missing accuracy falls back to the rolling mean and is not counted`() {
        val history = mapOf(ChessPhase2Engine.TimeControl.BLITZ to listOf(80.0, 70.0))
        val mapping = M.buildInput(
            detail(whiteAccuracy = null, blackAccuracy = null),
            "jugglah",
            accuracyHistories = history
        ) as ChessGameAuditMapper.Mapping.Ready

        assertFalse(mapping.accuracyKnown)
        assertEquals(75.0, mapping.input.caps2Accuracy, 1e-9)
    }

    @Test
    fun `missing accuracy with no history falls back to the tier default`() {
        val mapping = M.buildInput(
            detail(whiteAccuracy = null, blackAccuracy = null),
            "jugglah"
        ) as ChessGameAuditMapper.Mapping.Ready

        assertEquals(75.0, mapping.input.caps2Accuracy, 1e-9) // BLITZ default
    }

    @Test
    fun `short game is detected from a sub-10-move pgn`() {
        val mapping = M.buildInput(
            detail(pgn = SHORT_PGN),
            "jugglah"
        ) as ChessGameAuditMapper.Mapping.Ready

        assertTrue(mapping.input.shortGame)
    }

    @Test
    fun `session minutes accumulate across games`() {
        val mapping = M.buildInput(
            detail(timeControl = "600"), // 10-minute rapid game
            "jugglah",
            sessionMinutesBefore = 12.5
        ) as ChessGameAuditMapper.Mapping.Ready

        // 12.5 + 10 = 22.5 → rounds to 23
        assertEquals(23, mapping.input.sessionElapsedMins)
    }

    @Test
    fun `unrated games are not auditable`() {
        val mapping = M.buildInput(detail(rated = false), "jugglah")
        assertTrue(mapping is ChessGameAuditMapper.Mapping.NotAuditable)
    }

    @Test
    fun `chess960 games are auditable`() {
        val mapping = M.buildInput(detail(rules = "chess960"), "jugglah")
            as ChessGameAuditMapper.Mapping.Ready

        // Chess960 is the user's main format: rated, reviewed, and audited
        // exactly like standard chess.
        assertEquals(ChessPhase2Engine.TimeControl.BLITZ, mapping.input.timeControl)
        assertEquals(ChessPhase2Engine.GameResult.WIN, mapping.input.gameResult)
        assertEquals(81.2, mapping.input.caps2Accuracy, 1e-9)
        assertTrue(mapping.accuracyKnown)
    }

    @Test
    fun `variant games are not auditable`() {
        listOf("crazyhouse", "king-of-the-hill", "three-check", "antichess").forEach { rules ->
            val mapping = M.buildInput(detail(rules = rules), "jugglah")
            assertTrue(mapping is ChessGameAuditMapper.Mapping.NotAuditable)
        }
    }

    @Test
    fun `daily games are not auditable`() {
        val mapping = M.buildInput(detail(timeControl = "1/86400"), "jugglah")
        assertTrue(mapping is ChessGameAuditMapper.Mapping.NotAuditable)
    }

    @Test
    fun `games without the user are not auditable`() {
        val mapping = M.buildInput(detail(), "someoneelse")
        assertTrue(mapping is ChessGameAuditMapper.Mapping.NotAuditable)
    }

    companion object {
        private val FULL_PGN = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Result "0-1"]

            1. e4 {[%clk 0:02:59.5]} 1... c5 {[%clk 0:02:58.7]} 2. Nf3 {[%clk 0:02:51.3]} 2... Nc6 {[%clk 0:02:50.1]} 3. d4 {[%clk 0:02:41.2]} 3... cxd4 {[%clk 0:02:40.5]} 4. Nxd4 {[%clk 0:02:32.9]} 4... g6 {[%clk 0:02:31.4]} 5. Be3 {[%clk 0:02:20.8]} 5... Nf6 {[%clk 0:02:19.9]} 6. c4 {[%clk 0:02:11.7]} 6... Qb6 {[%clk 0:02:10.2]} 7. Nb3 {[%clk 0:02:02.6]} 7... d6 {[%clk 0:01:59.5]} 8. Be2 {[%clk 0:01:50.4]} 8... Bg7 {[%clk 0:01:48.8]} 9. Nc3 {[%clk 0:01:40.1]} 9... O-O {[%clk 0:01:41.2]} 10. O-O {[%clk 0:01:31.5]} 10... Ng4 {[%clk 0:01:30.7]} 11. Qd2 {[%clk 0:01:21.3]} 11... Nxe3 {[%clk 0:01:22.4]} 12. Qxe3 {[%clk 0:01:13.9]} 12... Be6 {[%clk 0:01:14.8]} 13. Rad1 {[%clk 0:01:04.2]} 13... Qc7 {[%clk 0:01:05.1]} 14. f3 {[%clk 0:00:55.6]} 14... Rfd8 {[%clk 0:00:56.3]} 15. Qf2 {[%clk 0:00:46.8]} 15... d5 {[%clk 0:00:47.5]} 16. cxd5 {[%clk 0:00:38.1]} 16... Nxd5 {[%clk 0:00:39.0]} 17. Nxd5 {[%clk 0:00:29.4]} 17... Bxd5 0-1
        """.trimIndent()

        private val SHORT_PGN = """
            [Event "Live Chess"]
            [Result "1-0"]

            1. e4 {[%clk 0:02:59.5]} 1... e5 {[%clk 0:02:58.7]} 2. Qh5 {[%clk 0:02:57.1]} 2... Nc6 {[%clk 0:02:56.3]} 3. Bc4 {[%clk 0:02:50.9]} 3... Nf6 {[%clk 0:02:51.8]} 4. Qxf7# {[%clk 0:02:44.2]} 1-0
        """.trimIndent()
    }
}
