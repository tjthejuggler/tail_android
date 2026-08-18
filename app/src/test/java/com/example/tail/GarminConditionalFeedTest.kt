package com.example.tail

import com.example.tail.data.conditionalSyncFeedAmount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the conditional feed amounts applied by the Garmin sync path
 * (applyGarminData), which writes absolute values per day instead of manual
 * increments.
 *
 * Semantics: only POSITIVE day deltas feed linked habits — a new or raised
 * Garmin value counts, a downward correction never un-feeds (run the
 * conditional backfill to true-up after corrections). The "feed max1" cap
 * limits the feed to at most 1 point per day, first positive delta only,
 * mirroring the manual increment path.
 */
class GarminConditionalFeedTest {

    @Test
    fun `positive delta without cap feeds the full amount`() {
        assertEquals(1, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 1, feedMaxOne = false))
        assertEquals(2, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 2, feedMaxOne = false))
    }

    @Test
    fun `first positive delta of an empty day feeds one point when capped`() {
        assertEquals(1, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 1, feedMaxOne = true))
        assertEquals(1, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 3, feedMaxOne = true))
    }

    @Test
    fun `raised value on an already fed day feeds nothing when capped`() {
        // e.g. custom point ranges: tier goes 1 → 2 later in the day
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 1, delta = 1, feedMaxOne = true))
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 2, delta = 2, feedMaxOne = true))
    }

    @Test
    fun `raised value on an already fed day still feeds when uncapped`() {
        // Without the cap, a tier raise 1 → 2 feeds the additional point
        assertEquals(1, conditionalSyncFeedAmount(sourceStoredBefore = 1, delta = 1, feedMaxOne = false))
    }

    @Test
    fun `downward corrections never un-feed`() {
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 1, delta = -1, feedMaxOne = false))
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 1, delta = -1, feedMaxOne = true))
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 2, delta = -2, feedMaxOne = false))
    }

    @Test
    fun `zero delta feeds nothing`() {
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 0, feedMaxOne = false))
        assertEquals(0, conditionalSyncFeedAmount(sourceStoredBefore = 0, delta = 0, feedMaxOne = true))
    }
}
