package com.example.tail

import com.example.tail.data.conditionalTapFeedAmount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the shared tap-path conditional feed amount helper.
 *
 * Semantics: a conditional increment of `amount` units feeds its linked
 * habit the source's POINTS delta when "feed points" is enabled and the
 * source has a divider > 1 (applyDivider(before+amount) minus
 * applyDivider(before)), otherwise the raw increment amount. This is what
 * keeps aggregate habits (e.g. "Chess" = sum of its sources' points)
 * consistent across every increment path (taps, IPC, voice, PC widget).
 */
class ConditionalTapFeedAmountTest {

    @Test
    fun `raw amount feeds through when feed points is off`() {
        assertEquals(5, conditionalTapFeedAmount(10, 5, feedPoints = false, sourceDivider = 30))
    }

    @Test
    fun `raw amount feeds through when divider is one`() {
        assertEquals(3, conditionalTapFeedAmount(4, 3, feedPoints = true, sourceDivider = 1))
    }

    @Test
    fun `feed points sends the divider-applied points delta`() {
        // 0 to 45 minutes at divider 30: points 0 to 2 (round(45/30)=2, min 1)
        assertEquals(2, conditionalTapFeedAmount(0, 45, feedPoints = true, sourceDivider = 30))
        // 45 to 60 minutes at divider 30: points 2 to 2, no feed
        assertEquals(0, conditionalTapFeedAmount(45, 15, feedPoints = true, sourceDivider = 30))
        // 60 to 75 minutes at divider 30: points 2 to 3
        assertEquals(1, conditionalTapFeedAmount(60, 15, feedPoints = true, sourceDivider = 30))
    }

    @Test
    fun `first unit of a day always feeds at least one point`() {
        // applyDivider floors any positive raw to at least 1 point
        assertEquals(1, conditionalTapFeedAmount(0, 4, feedPoints = true, sourceDivider = 30))
        assertEquals(1, conditionalTapFeedAmount(0, 1, feedPoints = true, sourceDivider = 15))
    }

    @Test
    fun `points accumulate exactly like the displayed points`() {
        // 30 then 1 minutes at divider 2 feeds 15 then 1 - rounding per delta
        assertEquals(15, conditionalTapFeedAmount(0, 30, feedPoints = true, sourceDivider = 2))
        assertEquals(1, conditionalTapFeedAmount(30, 1, feedPoints = true, sourceDivider = 2))
    }

    @Test
    fun `ipc flat plus one is now amount aware`() {
        // A 5-unit voice increment on an undivided source feeds 5, not 1
        assertEquals(5, conditionalTapFeedAmount(2, 5, feedPoints = false, sourceDivider = 1))
        // A 5-unit increment on a divider-15 feed-points source: 20 to 25 raw = 1 to 2 points
        assertEquals(1, conditionalTapFeedAmount(20, 5, feedPoints = true, sourceDivider = 15))
    }
}
