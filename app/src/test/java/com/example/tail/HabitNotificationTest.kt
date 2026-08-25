package com.example.tail

import com.example.tail.data.HabitNotification
import com.example.tail.data.HabitNotificationCodec
import com.example.tail.data.HabitSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HabitNotificationTest {

    private fun sampleAsk(
        id: String = "movie:Dune@2026-08-17",
        title: String = "Dune: Part Two",
        flashShown: Boolean = false,
        payload: String = "21:30:00"
    ) = HabitNotification(
        id = id,
        habitName = "Movies",
        type = HabitNotification.TYPE_MOVIE,
        title = title,
        question = "Watched this?",
        createdAtMillis = 1234567890L,
        flashShown = flashShown,
        payload = payload
    )

    // ── Movie payload ───────────────────────────────────────────────────────

    @Test
    fun `movie payload round-trips time and minutes`() {
        val payload = HabitNotification.moviePayload("21:30:00", 115)
        assertEquals("21:30:00|115", payload)
        val (time, minutes) = HabitNotification.parseMoviePayload(payload)
        assertEquals("21:30:00", time)
        assertEquals(115, minutes)
    }

    @Test
    fun `movie payload without minutes parses to zero minutes`() {
        val (time, minutes) = HabitNotification.parseMoviePayload("21:30:00")
        assertEquals("21:30:00", time)
        assertEquals(0, minutes)
    }

    @Test
    fun `movie payload builder omits the pipe when minutes are unknown`() {
        assertEquals("21:30:00", HabitNotification.moviePayload("21:30:00", 0))
    }

    @Test
    fun `parseMoviePayload tolerates garbage`() {
        val (time, minutes) = HabitNotification.parseMoviePayload("junk|notanumber")
        assertEquals("junk", time)
        assertEquals(0, minutes)
    }

    // ── Codec ───────────────────────────────────────────────────────────────

    @Test
    fun `encode then decode round-trips a single ask`() {
        val ask = sampleAsk()
        val decoded = HabitNotificationCodec.decode(HabitNotificationCodec.encode(listOf(ask)))
        assertEquals(listOf(ask), decoded)
    }

    @Test
    fun `encode then decode round-trips multiple asks preserving order`() {
        val asks = listOf(
            sampleAsk(),
            HabitNotification(
                id = HabitNotification.scheduleId("Floss", "2026-08-18"),
                habitName = "Floss",
                type = HabitNotification.TYPE_SCHEDULE,
                title = "Floss",
                question = "Did you floss?",
                createdAtMillis = 999L,
                flashShown = true
            )
        )
        val decoded = HabitNotificationCodec.decode(HabitNotificationCodec.encode(asks))
        assertEquals(asks, decoded)
    }

    @Test
    fun `decode of null and blank returns empty list`() {
        assertTrue(HabitNotificationCodec.decode(null).isEmpty())
        assertTrue(HabitNotificationCodec.decode("").isEmpty())
    }

    @Test
    fun `codec escapes field and record separators inside values`() {
        val ask = sampleAsk(
            title = "Movie\u001Fwith\u001Eweird\u001Dchars"
        )
        val decoded = HabitNotificationCodec.decode(HabitNotificationCodec.encode(listOf(ask)))
        assertEquals(1, decoded.size)
        assertEquals("Movie\u001Fwith\u001Eweird\u001Dchars", decoded[0].title)
        assertEquals(ask, decoded[0])
    }

    @Test
    fun `encode then decode round-trips an info notice`() {
        val notice = HabitNotification(
            id = "qc-fail:1784444444444",
            habitName = "Quick capture",
            type = HabitNotification.TYPE_INFO,
            title = "📸 Quick capture failed",
            question = "Camera error, no photo was taken: disk full",
            createdAtMillis = 1784444444444L
        )
        val decoded = HabitNotificationCodec.decode(HabitNotificationCodec.encode(listOf(notice)))
        assertEquals(listOf(notice), decoded)
    }

    @Test
    fun `decode skips malformed records`() {
        val good = sampleAsk()
        val encoded = HabitNotificationCodec.encode(listOf(good))
        val corrupted = "only three fields\u001E" + encoded
        val decoded = HabitNotificationCodec.decode(corrupted)
        assertEquals(listOf(good), decoded)
    }

    // ── Schedule ids ────────────────────────────────────────────────────────

    @Test
    fun `scheduleId formats habit and date`() {
        assertEquals("schedule:Floss:2026-08-18", HabitNotification.scheduleId("Floss", "2026-08-18"))
    }

    // ── Schedule time math ──────────────────────────────────────────────────

    @Test
    fun `parseTime accepts valid HH-mm and HH-mm-ss`() {
        assertEquals(22 to 5, HabitSchedule.parseTime("22:05"))
        assertEquals(0 to 0, HabitSchedule.parseTime("00:00"))
        assertEquals(9 to 41, HabitSchedule.parseTime("09:41:30"))
    }

    @Test
    fun `parseTime rejects malformed and out-of-range times`() {
        assertNull(HabitSchedule.parseTime("24:00"))
        assertNull(HabitSchedule.parseTime("12:60"))
        assertNull(HabitSchedule.parseTime("abc"))
        assertNull(HabitSchedule.parseTime(""))
        assertNull(HabitSchedule.parseTime("7"))
    }

    @Test
    fun `nextOccurrenceMillis returns today slot when still ahead`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 18, 10, 0, 0, 0, zone)
        val expected = ZonedDateTime.of(2026, 8, 18, 22, 0, 0, 0, zone)
        assertEquals(
            expected.toInstant().toEpochMilli(),
            HabitSchedule.nextOccurrenceMillis("22:00", now.toInstant().toEpochMilli())
        )
    }

    @Test
    fun `nextOccurrenceMillis rolls to tomorrow when slot passed`() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.of(2026, 8, 18, 23, 0, 0, 0, zone)
        val expected = ZonedDateTime.of(2026, 8, 19, 22, 0, 0, 0, zone)
        assertEquals(
            expected.toInstant().toEpochMilli(),
            HabitSchedule.nextOccurrenceMillis("22:00", now.toInstant().toEpochMilli())
        )
    }

    @Test
    fun `nextOccurrenceMillis returns null for malformed time`() {
        assertNull(HabitSchedule.nextOccurrenceMillis("nope", 0L))
    }

    @Test
    fun `passedToday is false before the slot and true after it`() {
        val zone = ZoneId.systemDefault()
        val before = ZonedDateTime.of(2026, 8, 18, 10, 0, 0, 0, zone)
        val after = ZonedDateTime.of(2026, 8, 18, 23, 0, 0, 0, zone)
        assertFalse(HabitSchedule.passedToday("22:00", before.toInstant().toEpochMilli()))
        assertTrue(HabitSchedule.passedToday("22:00", after.toInstant().toEpochMilli()))
    }

    @Test
    fun `passedToday at exact slot time counts as passed`() {
        val zone = ZoneId.systemDefault()
        val atSlot = ZonedDateTime.of(2026, 8, 18, 22, 0, 0, 0, zone)
        assertTrue(HabitSchedule.passedToday("22:00", atSlot.toInstant().toEpochMilli()))
    }
}
