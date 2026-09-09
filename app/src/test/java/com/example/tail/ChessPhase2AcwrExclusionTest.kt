package com.example.tail

import com.example.tail.data.ReadinessGameRecord
import com.example.tail.widget.ChessPhase2V2Store
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The audited game is excluded from the ACWR acute count (2026-09-09 fix):
 * chronic overload is a PRE-EXISTING condition — the game that just ended
 * must never be the single game that tips a borderline ratio over the bar.
 */
class ChessPhase2AcwrExclusionTest {

    private fun game(endMs: Long, rated: Boolean = true) = ReadinessGameRecord(
        endTimeMs = endMs, type = "BLITZ", opponent = "x", won = true,
        minutes = 3.0, ccrsAtPlay = null, stateAtPlay = null,
        authorized = true, variant = "chess", rated = rated
    )

    @Test
    fun `excludeEndMs drops exactly that game from the ratio`() {
        val utc = ZoneOffset.UTC
        // 28 games → chronicWeekly 7.0; 7 acute games (6 yesterday-ish on
        // day 6 + the audited one now). With the audited game excluded the
        // ratio is 6/7 ≈ 0.86; without exclusion 7/7 = 1.0.
        val now = 1_789_000_000_000L
        val day = 24L * 60 * 60 * 1000
        val games = buildList {
            repeat(21) { back -> add(game(now - (back + 7) * day)) } // days 7–27
            repeat(6) { add(game(now - 6 * day)) }                   // day 6
            add(game(now))                                           // audited
        }
        val inclusive = ChessPhase2V2Store.acwrInput(games, now, utc)
        val exclusive = ChessPhase2V2Store.acwrInput(
            games, now, utc, excludeEndMs = now
        )
        assertEquals(inclusive.acuteGames - 1, exclusive.acuteGames)
        // Excluding the audited game removes it from BOTH windows, but the
        // acute count drops by a whole game while the weekly average drops
        // by only a quarter-game — so the ratio strictly falls.
        org.junit.Assert.assertTrue(exclusive.ratio < inclusive.ratio)
    }

    @Test
    fun `excludeEndMs is a no-op for other timestamps`() {
        val utc = ZoneOffset.UTC
        val now = 1_789_000_000_000L
        val games = listOf(game(now - 1000), game(now))
        val a = ChessPhase2V2Store.acwrInput(games, now, utc)
        val b = ChessPhase2V2Store.acwrInput(
            games, now, utc, excludeEndMs = now - 999_999
        )
        assertEquals(a.acuteGames, b.acuteGames)
        assertEquals(a.ratio, b.ratio, 0.0)
    }
}
