package com.example.tail

import com.example.tail.data.conditionalCappedFeedAmount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the conditional-habit "feed max1 point/day" sub-setting helper.
 *
 * Semantics: a feed-max-one conditional habit feeds at most 1 point per day
 * to each linked habit's Points (primary count). The first positive increment
 * of the day feeds at most 1 point; further positive increments the same day
 * feed nothing. Decrements pass through so undoes still unwind the feed.
 */
class ConditionalFeedMaxOneTest {

    @Test
    fun `first increment of an empty day feeds one point`() {
        assertEquals(1, conditionalCappedFeedAmount(sourceStoredToday = 0, amount = 1))
    }

    @Test
    fun `large first increment is still capped at one point`() {
        assertEquals(1, conditionalCappedFeedAmount(sourceStoredToday = 0, amount = 5))
    }

    @Test
    fun `repeat increments on an already-fed day feed nothing`() {
        assertEquals(0, conditionalCappedFeedAmount(sourceStoredToday = 1, amount = 1))
        assertEquals(0, conditionalCappedFeedAmount(sourceStoredToday = 3, amount = 2))
    }

    @Test
    fun `decrements pass through so undoes unwind the linked habit`() {
        assertEquals(-1, conditionalCappedFeedAmount(sourceStoredToday = 2, amount = -1))
        assertEquals(-1, conditionalCappedFeedAmount(sourceStoredToday = 0, amount = -1))
    }

    @Test
    fun `zero amount feeds nothing`() {
        assertEquals(0, conditionalCappedFeedAmount(sourceStoredToday = 0, amount = 0))
    }
}
