package com.example.tail

import com.example.tail.ipc.HabitTimesPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Protocol v5 `EXTRA_TIMES_JSON` parsing: dates map to sorted, zero-padded
 * "HH:mm:ss" lists; malformed pieces are dropped without killing the rest;
 * duplicates are kept (N units at one moment = N identical strings, matching
 * how HabitTimestampRepository stores multi-unit increments).
 */
class HabitTimesPayloadTest {

    @Test
    fun `parses dates with sorted times`() {
        val json = """
            {"2026-01-15": ["09:14:44", "09:13:02"], "2026-01-16": ["21:07:10"]}
        """.trimIndent()
        val parsed = HabitTimesPayload.parse(json)
        assertEquals(
            mapOf(
                LocalDate.of(2026, 1, 15) to listOf("09:13:02", "09:14:44"),
                LocalDate.of(2026, 1, 16) to listOf("21:07:10")
            ),
            parsed
        )
    }

    @Test
    fun `keeps duplicate times as separate units`() {
        val parsed = HabitTimesPayload.parse("""{"2026-01-15": ["10:00:00", "10:00:00"]}""")
        assertEquals(listOf("10:00:00", "10:00:00"), parsed.values.single())
    }

    @Test
    fun `normalises non-padded times`() {
        assertEquals("09:03:02", HabitTimesPayload.normaliseTime("9:3:2"))
    }

    @Test
    fun `drops invalid times but keeps the date clearable`() {
        val parsed = HabitTimesPayload.parse("""{"2026-01-15": ["25:00:00", "garbage", "08:30:00"]}""")
        assertEquals(mapOf(LocalDate.of(2026, 1, 15) to listOf("08:30:00")), parsed)
        // All-invalid → empty list means "clear this date's timestamps".
        val cleared = HabitTimesPayload.parse("""{"2026-01-15": ["nope", "25:61:00"]}""")
        assertEquals(mapOf(LocalDate.of(2026, 1, 15) to emptyList<String>()), cleared)
    }

    @Test
    fun `skips unparseable dates and non-array values`() {
        val parsed = HabitTimesPayload.parse("""{"not-a-date": ["08:00:00"], "2026-01-15": 3}""")
        assertEquals(emptyMap<LocalDate, List<String>>(), parsed)
    }

    @Test
    fun `malformed json yields empty map`() {
        assertEquals(emptyMap<LocalDate, List<String>>(), HabitTimesPayload.parse("{oops"))
    }

    @Test
    fun `normalise rejects out-of-range components`() {
        assertNull(HabitTimesPayload.normaliseTime("24:00:00"))
        assertNull(HabitTimesPayload.normaliseTime("12:60:00"))
        assertNull(HabitTimesPayload.normaliseTime("12:00:60"))
        assertNull(HabitTimesPayload.normaliseTime("12:00"))
        assertNull(HabitTimesPayload.normaliseTime(""))
    }
}
