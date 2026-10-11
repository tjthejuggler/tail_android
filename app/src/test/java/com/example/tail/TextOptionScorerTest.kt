package com.example.tail

import com.example.tail.data.TextOptionScorer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Unit tests for the most-likely-first ordering of text-habit option
 * lists ([TextOptionScorer.orderOptions]).
 */
class TextOptionScorerTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 11)

    private fun ts(daysAgo: Long, hh: Int, mm: Int): String =
        String.format(
            "%s %02d:%02d:00",
            today.minusDays(daysAgo), hh, mm
        )

    @Test
    fun `more frequent option ranks first regardless of time`() {
        val log = mapOf(
            ts(1, 8, 0) to "Common",
            ts(2, 8, 30) to "Common",
            ts(3, 8, 10) to "Common",
            ts(1, 20, 0) to "Rare",
            ts(5, 21, 0) to "Rare"
        )
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(8, 0), today)
        assertEquals(listOf("Common", "Rare"), order)
    }

    @Test
    fun `time of day boost lifts the now-appropriate option`() {
        // Morning option is the most frequent overall; evening option is
        // rarer but consistently taken around 21:00. At 21:00 the evening
        // option must outrank the morning one.
        val log = buildMap {
            repeat(10) { put(ts(it.toLong() + 1, 7, 30), "Morning pill") }
            repeat(3) { put(ts(it.toLong() + 1, 21, 0), "Night pill") }
        }
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(21, 5), today)
        assertEquals(listOf("Night pill", "Morning pill"), order)

        // ...and in the morning the frequency leader is back on top.
        val morning = TextOptionScorer.orderOptions(log, LocalTime.of(7, 25), today)
        assertEquals(listOf("Morning pill", "Night pill"), morning)
    }

    @Test
    fun `single lucky timestamp does not beat a consistent now-option`() {
        // "Once" has one entry near now; "Regular" has many entries spread
        // over the day including several near now. "Regular" must win.
        val log = buildMap {
            put(ts(1, 9, 0), "Once")
            put(ts(1, 9, 5), "Regular")
            put(ts(2, 9, 10), "Regular")
            put(ts(3, 9, 0), "Regular")
            put(ts(4, 15, 0), "Regular")
            put(ts(5, 22, 0), "Regular")
        }
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(9, 0), today)
        assertEquals("Regular", order.first())
    }

    @Test
    fun `circular distance across midnight is respected`() {
        // 23:50 option vs 01:30 option; "now" is 00:10. Both within the
        // window via the circular distance; the closer one (23:50, 20 min)
        // must outrank the farther one (01:30, 80 min) despite equal counts.
        val log = mapOf(
            ts(1, 23, 50) to "Late",
            ts(2, 23, 55) to "Late",
            ts(1, 1, 30) to "Early",
            ts(2, 1, 25) to "Early"
        )
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(0, 10), today)
        assertEquals(listOf("Late", "Early"), order)
    }

    @Test
    fun `ties break alphabetically and empty log yields empty list`() {
        assertEquals(emptyList<String>(), TextOptionScorer.orderOptions(emptyMap(), LocalTime.NOON, today))
        // Same recency weight (identical daysAgo), no time-of-day match for
        // either at noon → equal scores → alphabetical order.
        val log = mapOf(
            ts(1, 8, 0) to "Beta",
            ts(1, 9, 0) to "Alpha"
        )
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(12, 0), today)
        // No time match for either; equal share → alphabetical.
        assertEquals(listOf("Alpha", "Beta"), order)
    }

    @Test
    fun `malformed and future keys are ignored`() {
        val log = mapOf(
            "not a timestamp" to "Bad",
            "${today.plusDays(2)} 08:00:00" to "Future",
            ts(1, 8, 0) to "Good"
        )
        assertEquals(listOf("Good"), TextOptionScorer.orderOptions(log, LocalTime.of(8, 0), today))
    }

    @Test
    fun `multi-line grouping keeps its combined identity`() {
        val log = mapOf(
            ts(1, 8, 0) to "Iron\nB12",
            ts(2, 8, 0) to "Iron",
            ts(3, 9, 0) to "Iron\nB12"
        )
        val order = TextOptionScorer.orderOptions(log, LocalTime.of(8, 0), today)
        assertEquals(listOf("Iron\nB12", "Iron"), order)
    }
}
