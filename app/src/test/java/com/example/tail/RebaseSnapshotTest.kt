package com.example.tail

import com.example.tail.data.PendingIncrement
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.IncrementJournal
import com.example.tail.data.rebaseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 3-way rebase used when a background sync persists a whole-file snapshot
 * built from the ViewModel's cache. It must keep every increment that landed
 * on disk while the sync was building its snapshot, while still applying the
 * sync's own edits — the `Blitz Chess 29 → 14` regression (chess.com sync
 * overwriting a widget-tap increment for the same habit on another date).
 */
class RebaseSnapshotTest {

    private fun db(vararg rows: Pair<String, Map<String, Int>>): HabitsDatabase = rows.toMap()

    @Test
    fun `untouched habit keeps the newer disk value`() {
        val base = db("Language studied" to mapOf("2026-11-25" to 1))
        val desired = db(
            "Language studied" to mapOf("2026-11-25" to 1),
            "Blitz Chess" to mapOf("2026-11-25" to 5)
        )
        // A widget tap landed on disk meanwhile:
        val disk = db("Language studied" to mapOf("2026-11-25" to 2))
        val out = rebaseSnapshot(base, desired, disk)
        assertEquals(2, out["Language studied"]!!["2026-11-25"])
        assertEquals(5, out["Blitz Chess"]!!["2026-11-25"])
    }

    @Test
    fun `date the caller changed wins, dates it did not change keep disk`() {
        val base = db("Blitz Chess" to mapOf("2026-11-17" to 14, "2026-11-25" to 3))
        val desired = db("Blitz Chess" to mapOf("2026-11-17" to 14, "2026-11-25" to 29))
        val disk = db("Blitz Chess" to mapOf("2026-11-17" to 20, "2026-11-25" to 3))
        val out = rebaseSnapshot(base, desired, disk)
        assertEquals(29, out["Blitz Chess"]!!["2026-11-25"])  // sync's edit applied
        assertEquals(20, out["Blitz Chess"]!!["2026-11-17"])  // tap kept
    }

    @Test
    fun `dates added on disk behind the caller's back survive`() {
        val base = db("H" to mapOf("2026-11-25" to 1))
        val desired = db("H" to mapOf("2026-11-25" to 4))
        val disk = db("H" to mapOf("2026-11-25" to 1, "2026-11-26" to 1))
        val out = rebaseSnapshot(base, desired, disk)
        assertEquals(4, out["H"]!!["2026-11-25"])
        assertEquals(1, out["H"]!!["2026-11-26"])
    }

    @Test
    fun `new habit that exists only on disk is kept`() {
        val base = db("A" to mapOf("2026-11-25" to 1))
        val desired = db("A" to mapOf("2026-11-25" to 2))
        val disk = db("A" to mapOf("2026-11-25" to 1), "minutes:B" to mapOf("2026-11-25" to 7))
        val out = rebaseSnapshot(base, desired, disk)
        assertEquals(7, out["minutes:B"]!!["2026-11-25"])
    }

    @Test
    fun `forced habit takes the caller's values wholesale`() {
        val base = db("Sleep" to mapOf("2026-11-25" to 8))
        val desired = db("Sleep" to mapOf("2026-11-25" to 0)) // reset-to-zero
        val disk = db("Sleep" to mapOf("2026-11-25" to 9))
        val out = rebaseSnapshot(base, desired, disk, forceHabits = setOf("Sleep"))
        assertEquals(0, out["Sleep"]!!["2026-11-25"])
    }

    @Test
    fun `explicit deletion by the caller is honoured when disk is unchanged`() {
        val base = db("H" to mapOf("2026-11-24" to 1, "2026-11-25" to 1))
        val desired = db("H" to mapOf("2026-11-25" to 1))
        val disk = base
        val out = rebaseSnapshot(base, desired, disk)
        assertFalse(out["H"]!!.containsKey("2026-11-24"))
    }

    @Test
    fun `satisfied uses the target so a landed-but-unconfirmed write is not reapplied`() {
        val inc = PendingIncrement(key = "H", amount = 1, date = "2026-11-25", eventAt = 0, target = 2)
        // disk already at 2 → nothing to do
        assertTrue(IncrementJournal.satisfied(inc, db("H" to mapOf("2026-11-25" to 2))))
        // disk at 1 (write truly lost) → must replay
        assertFalse(IncrementJournal.satisfied(inc, db("H" to mapOf("2026-11-25" to 1))))
    }

    @Test
    fun `satisfied without target falls back to the amount itself`() {
        val inc = PendingIncrement(key = "H", amount = 3, date = "2026-11-25", eventAt = 0)
        assertTrue(IncrementJournal.satisfied(inc, db("H" to mapOf("2026-11-25" to 3))))
        assertFalse(IncrementJournal.satisfied(inc, db("H" to emptyMap())))
    }
}
