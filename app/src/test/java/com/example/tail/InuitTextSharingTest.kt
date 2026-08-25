package com.example.tail

import com.example.tail.ipc.InuitTextSharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Pure rules of the Inuit text-habit sharing endpoint: recency window,
 * per-habit cap, newest-first ordering, truncation and limit clamping.
 */
class InuitTextSharingTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 8, 25, 12, 0, 0)

    @Test
    fun `limit clamps to default and ceiling`() {
        assertEquals(InuitTextSharing.DEFAULT_LIMIT, InuitTextSharing.clampLimit(null))
        assertEquals(InuitTextSharing.DEFAULT_LIMIT, InuitTextSharing.clampLimit(0))
        assertEquals(InuitTextSharing.DEFAULT_LIMIT, InuitTextSharing.clampLimit(-3))
        assertEquals(1, InuitTextSharing.clampLimit(1))
        assertEquals(InuitTextSharing.MAX_LIMIT, InuitTextSharing.clampLimit(99))
    }

    @Test
    fun `returns newest entries first within the recency window`() {
        val log = mapOf(
            "2026-08-01 10:00:00" to "too old (24 days)",        // outside 14-day window
            "2026-08-20 09:00:00" to "older-but-recent",
            "2026-08-24 18:30:00" to "yesterday",
            "2026-08-25 08:15:00" to "this morning"
        )
        val out = InuitTextSharing.recentEntries(log, limit = 3, now = now)
        assertEquals(listOf("this morning", "yesterday", "older-but-recent"), out.map { it.second })
        assertEquals("2026-08-25 08:15:00", out.first().first)
    }

    @Test
    fun `caps entries per habit at the limit`() {
        val log = (1..6).associate {
            "2026-08-2${it} 10:00:00" to "entry $it"
        }
        val out = InuitTextSharing.recentEntries(log, limit = 2, now = now)
        assertEquals(2, out.size)
        // newest two win: Aug 26 key doesn't exist; keys are Aug 21..26 → but Aug 26 is
        // in the future relative to `now` and must be dropped by the horizon check.
        assertTrue(out.all { it.second != "entry 6" })
        assertEquals("entry 5", out[0].second)
        assertEquals("entry 4", out[1].second)
    }

    @Test
    fun `future-dated entries are dropped`() {
        val log = mapOf(
            "2026-08-25 12:00:30" to "half a minute in the future (skew ok)",
            "2026-08-26 00:00:00" to "clearly tomorrow (dropped)",
            "2026-08-25 11:00:00" to "an hour ago"
        )
        val out = InuitTextSharing.recentEntries(log, limit = 5, now = now)
        // horizon = now + 1 min → only the skew entry and the past entry survive
        assertEquals(listOf("half a minute in the future (skew ok)", "an hour ago"), out.map { it.second })
    }

    @Test
    fun `long entries are truncated with ellipsis`() {
        val longText = "x".repeat(500)
        val out = InuitTextSharing.recentEntries(
            mapOf("2026-08-25 10:00:00" to longText), limit = 3, now = now
        )
        assertEquals(InuitTextSharing.MAX_ENTRY_CHARS + 1, out.single().second.length)
        assertTrue(out.single().second.endsWith("…"))
    }

    @Test
    fun `unparseable keys are skipped`() {
        val log = mapOf(
            "not-a-timestamp" to "junk",
            "2026-08-25 10:00:00" to "good"
        )
        val out = InuitTextSharing.recentEntries(log, limit = 3, now = now)
        assertEquals(listOf("good"), out.map { it.second })
    }

    @Test
    fun `empty log yields empty list`() {
        assertTrue(InuitTextSharing.recentEntries(emptyMap(), limit = 3, now = now).isEmpty())
    }
}
