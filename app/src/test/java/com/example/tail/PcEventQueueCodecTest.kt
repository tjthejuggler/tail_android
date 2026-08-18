package com.example.tail

import com.example.tail.data.PcEventQueueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Tests for the PC-widget event queue wire format (pc_habit_events.json /
 * pc_habit_acks.json). The codec is the trust boundary between the PC
 * Python widget and the phone: malformed or hostile entries must be
 * dropped individually without losing the rest of the queue, and acks
 * must round-trip losslessly so events are never double-applied.
 */
class PcEventQueueCodecTest {

    private fun eventsJson(vararg entries: String): String =
        """{"version": 1, "events": [${entries.joinToString(",")}]}"""

    private val sessionEntry =
        """{"id": "evt-1", "habit": "Coding", "kind": "session", "date": "2026-08-17",
            "start": "14:30:00", "end": "15:15:00", "minutes": 45}"""

    @Test
    fun `valid session event parses with all fields`() {
        val events = PcEventQueueCodec.parseEvents(eventsJson(sessionEntry))
        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("evt-1", e.id)
        assertEquals("Coding", e.habit)
        assertTrue(e.isSession)
        assertEquals(LocalDate.of(2026, 8, 17), e.date)
        assertEquals("14:30:00", e.startTime)
        assertEquals(45, e.minutes)
    }

    @Test
    fun `tap event parses as non-session with zero minutes`() {
        val tap = """{"id": "evt-2", "habit": "Water", "kind": "tap",
            "date": "2026-08-17", "start": "09:00:00"}"""
        val events = PcEventQueueCodec.parseEvents(eventsJson(tap))
        assertEquals(1, events.size)
        assertFalse(events[0].isSession)
        assertEquals(0, events[0].minutes)
    }

    @Test
    fun `missing kind is treated as a tap`() {
        val tap = """{"id": "evt-3", "habit": "Water", "date": "2026-08-17"}"""
        val events = PcEventQueueCodec.parseEvents(eventsJson(tap))
        assertEquals(1, events.size)
        assertFalse(events[0].isSession)
    }

    @Test
    fun `entries missing id or habit are dropped but neighbours survive`() {
        val noId = """{"habit": "Coding", "kind": "session", "date": "2026-08-17", "minutes": 5}"""
        val noHabit = """{"id": "evt-x", "kind": "session", "date": "2026-08-17", "minutes": 5}"""
        val events = PcEventQueueCodec.parseEvents(
            eventsJson(noId, sessionEntry, noHabit)
        )
        assertEquals(1, events.size)
        assertEquals("evt-1", events[0].id)
    }

    @Test
    fun `invalid or missing date drops the entry`() {
        val badDate = """{"id": "evt-b", "habit": "Coding", "date": "17-08-2026", "minutes": 5}"""
        val noDate = """{"id": "evt-c", "habit": "Coding", "minutes": 5}"""
        assertTrue(PcEventQueueCodec.parseEvents(eventsJson(badDate)).isEmpty())
        assertTrue(PcEventQueueCodec.parseEvents(eventsJson(noDate)).isEmpty())
    }

    @Test
    fun `negative minutes are clamped to zero`() {
        val evil = """{"id": "evt-n", "habit": "Coding", "kind": "session",
            "date": "2026-08-17", "minutes": -999}"""
        val events = PcEventQueueCodec.parseEvents(eventsJson(evil))
        assertEquals(1, events.size)
        assertEquals(0, events[0].minutes)
    }

    @Test
    fun `minutes as string still parses and garbage string becomes zero`() {
        val asString = """{"id": "evt-s", "habit": "Coding", "kind": "session",
            "date": "2026-08-17", "minutes": "30"}"""
        val garbage = """{"id": "evt-g", "habit": "Coding", "kind": "session",
            "date": "2026-08-17", "minutes": "abc"}"""
        val events = PcEventQueueCodec.parseEvents(eventsJson(asString, garbage))
        assertEquals(30, events[0].minutes)
        assertEquals(0, events[1].minutes)
    }

    @Test
    fun `malformed start time is nulled but the event survives`() {
        val noSeconds = """{"id": "evt-t", "habit": "Coding", "kind": "session",
            "date": "2026-08-17", "start": "14:30", "minutes": 10}"""
        val events = PcEventQueueCodec.parseEvents(eventsJson(noSeconds))
        assertEquals(1, events.size)
        assertNull(events[0].startTime)
    }

    @Test
    fun `totally malformed json yields an empty queue`() {
        assertTrue(PcEventQueueCodec.parseEvents("not json at all {{{").isEmpty())
        assertTrue(PcEventQueueCodec.parseEvents("""{"no_events_key": true}""").isEmpty())
        assertTrue(PcEventQueueCodec.parseEvents("""{"events": []}""").isEmpty())
    }

    @Test
    fun `non-object entries inside the events array are dropped`() {
        val events = PcEventQueueCodec.parseEvents(
            """{"events": ["junk", 42, null, $sessionEntry]}"""
        )
        assertEquals(1, events.size)
        assertEquals("evt-1", events[0].id)
    }

    // ── Acks ──────────────────────────────────────────────────────────────

    @Test
    fun `acks parse into a set of processed ids`() {
        val json = """{"version": 1, "processed": ["a", "b", "a"], "updated_at": "x"}"""
        assertEquals(setOf("a", "b"), PcEventQueueCodec.parseAcks(json))
    }

    @Test
    fun `blank and non-string ack entries are filtered`() {
        val json = """{"processed": ["a", "", "  ", 7]}"""
        assertEquals(setOf("a"), PcEventQueueCodec.parseAcks(json))
    }

    @Test
    fun `malformed acks file yields an empty set`() {
        assertTrue(PcEventQueueCodec.parseAcks("garbage").isEmpty())
        assertTrue(PcEventQueueCodec.parseAcks("""{}""").isEmpty())
    }

    @Test
    fun `buildAcksBody round-trips through parseAcks`() {
        val ids = (1..600).map { "evt-$it" }
        val json = PcEventQueueCodec.buildAcksBody(ids)
        assertEquals(ids.toSet(), PcEventQueueCodec.parseAcks(json))
    }

    // ── Format validators ─────────────────────────────────────────────────

    @Test
    fun `date validator accepts only yyyy-MM-dd`() {
        assertTrue(PcEventQueueCodec.isValidDate("2026-08-17"))
        assertFalse(PcEventQueueCodec.isValidDate("2026-8-17"))
        assertFalse(PcEventQueueCodec.isValidDate("17/08/2026"))
        assertFalse(PcEventQueueCodec.isValidDate(null))
        assertFalse(PcEventQueueCodec.isValidDate(""))
    }

    @Test
    fun `time validator accepts only HH-mm-ss`() {
        assertTrue(PcEventQueueCodec.isValidTime("23:59:59"))
        assertTrue(PcEventQueueCodec.isValidTime("00:00:00"))
        assertFalse(PcEventQueueCodec.isValidTime("9:30:00"))
        assertFalse(PcEventQueueCodec.isValidTime("14:30"))
        assertFalse(PcEventQueueCodec.isValidTime("25:00:00"))
        assertFalse(PcEventQueueCodec.isValidTime(null))
    }
}
