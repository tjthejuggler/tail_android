package com.example.tail

import com.example.tail.data.conditionalSyncFeedAmount
import com.example.tail.data.positiveSyncDayDeltas
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the GitHub conditional feed (2026-08-18 bug: "JugCoach
 * Repo" was incremented by GitHub sync but its conditional habit "Juggling
 * Tech Sessions" was not). A GitHub-linked habit with conditional links must
 * feed its linked habits when a sync raises a day's stored value — and never
 * re-feed or un-feed on backlog re-fetches and downward corrections.
 *
 * These tests exercise the pure decision layer shared by applyGithubData:
 * [positiveSyncDayDeltas] (which days rose, from which stored value) combined
 * with [conditionalSyncFeedAmount] (how much of that rise feeds a Points target).
 */
class GithubConditionalFeedTest {

    /** Feed amount a Points-target link receives for [date] on this sync write. */
    private fun feedForDay(
        before: Map<String, Int>,
        after: Map<String, Int>,
        date: String,
        feedMaxOne: Boolean = false
    ): Int {
        val day = positiveSyncDayDeltas(before, after).firstOrNull { it.first == date }
            ?: return 0
        return conditionalSyncFeedAmount(day.second, day.third, feedMaxOne)
    }

    @Test
    fun `new commits on an already-tracked day feed the linked habit`() {
        // Poll cycle sees today's stored value rise 120 → 145.
        val before = mapOf("2026-08-18" to 120)
        val after = mapOf("2026-08-18" to 145)
        assertEquals(25, feedForDay(before, after, "2026-08-18"))
    }

    @Test
    fun `first ever sync of a day feeds the full value`() {
        val after = mapOf("2026-08-18" to 40)
        assertEquals(40, feedForDay(emptyMap(), after, "2026-08-18"))
    }

    @Test
    fun `backlog re-fetch with unchanged history feeds nothing`() {
        // fetchGithubBacklog resets the habit to 0 before applying; the fix
        // passes the PRE-reset snapshot as `before`, so unchanged days have
        // delta 0 and nothing re-feeds into the linked habit.
        val before = mapOf("2026-08-17" to 90, "2026-08-18" to 120)
        val after = mapOf("2026-08-17" to 90, "2026-08-18" to 120)
        assertEquals(0, feedForDay(before, after, "2026-08-17"))
        assertEquals(0, feedForDay(before, after, "2026-08-18"))
    }

    @Test
    fun `downward correction never un-feeds`() {
        // GitHub rewrote history (e.g. metric switch or force-push): the day's
        // value fell. The linked habit keeps what it was fed.
        val before = mapOf("2026-08-18" to 145)
        val after = mapOf("2026-08-18" to 140)
        assertEquals(0, feedForDay(before, after, "2026-08-18"))
    }

    @Test
    fun `feed max one caps the day's first activity at one point`() {
        val after = mapOf("2026-08-18" to 25)
        assertEquals(1, feedForDay(emptyMap(), after, "2026-08-18", feedMaxOne = true))
    }

    @Test
    fun `feed max one stays silent once the day already had activity`() {
        // First poll of the day already fed 1 point; a later poll that adds
        // more commits must not feed again.
        val before = mapOf("2026-08-18" to 30)
        val after = mapOf("2026-08-18" to 60)
        assertEquals(0, feedForDay(before, after, "2026-08-18", feedMaxOne = true))
    }

    @Test
    fun `positive deltas carry the pre-change stored value`() {
        val before = mapOf("2026-08-16" to 10, "2026-08-17" to 0)
        val after = mapOf("2026-08-16" to 10, "2026-08-17" to 7, "2026-08-18" to 3)
        val deltas = positiveSyncDayDeltas(before, after)
        // Only the two days that rose; 2026-08-16 is unchanged → absent.
        assertEquals(
            listOf(
                Triple("2026-08-17", 0, 7),
                Triple("2026-08-18", 0, 3)
            ),
            deltas
        )
    }
}
