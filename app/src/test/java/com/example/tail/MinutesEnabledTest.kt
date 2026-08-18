package com.example.tail

import com.example.tail.data.effectiveMinutesEnabled
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for the per-habit minutes-enabled invariants:
 *  - explicit toggle membership enables minutes;
 *  - max-1 habits NEVER have minutes (the cap wins over everything);
 *  - timer-widget connections (PC widget, phone bubble, media tracker,
 *    movie bridge) force minutes ON;
 *  - minutes-primary implies minutes ON;
 *  - everything else defaults to OFF.
 */
class MinutesEnabledTest {

    private fun effective(
        habit: String,
        minutesEnabled: Set<String> = emptySet(),
        pcWidget: Set<String> = emptySet(),
        widgetTrigger: Set<String> = emptySet(),
        media: Set<String> = emptySet(),
        movieBridge: Set<String> = emptySet(),
        minutesPrimary: Set<String> = emptySet(),
        maxOne: Set<String> = emptySet()
    ): Boolean = effectiveMinutesEnabled(
        habit, minutesEnabled, pcWidget, widgetTrigger, media, movieBridge, minutesPrimary, maxOne
    )

    @Test
    fun `minutes off by default`() {
        assertFalse(effective("Reading"))
    }

    @Test
    fun `explicit toggle enables minutes`() {
        assertTrue(effective("Reading", minutesEnabled = setOf("Reading")))
    }

    @Test
    fun `max one forces minutes off even when explicitly enabled`() {
        assertFalse(
            effective("Flossed", minutesEnabled = setOf("Flossed"), maxOne = setOf("Flossed"))
        )
    }

    @Test
    fun `pc widget connection forces minutes on`() {
        assertTrue(effective("Chess", pcWidget = setOf("Chess")))
    }

    @Test
    fun `phone bubble trigger forces minutes on`() {
        assertTrue(effective("Chess", widgetTrigger = setOf("Chess")))
    }

    @Test
    fun `media tracker forces minutes on`() {
        assertTrue(effective("Podcasts", media = setOf("Podcasts")))
    }

    @Test
    fun `movie bridge forces minutes on`() {
        assertTrue(effective("Movies", movieBridge = setOf("Movies")))
    }

    @Test
    fun `minutes primary implies minutes on`() {
        assertTrue(effective("Meditations", minutesPrimary = setOf("Meditations")))
    }

    @Test
    fun `max one wins over widget connections`() {
        // A max-1 habit connected to a widget still has no minutes — the
        // toggleMaxOne path strips the flags, and the effective computation
        // guarantees the invariant even if stale flags survive a restore.
        assertFalse(
            effective(
                "Flossed",
                pcWidget = setOf("Flossed"),
                widgetTrigger = setOf("Flossed"),
                media = setOf("Flossed"),
                movieBridge = setOf("Flossed"),
                minutesPrimary = setOf("Flossed"),
                minutesEnabled = setOf("Flossed"),
                maxOne = setOf("Flossed")
            )
        )
    }

    @Test
    fun `other habits flags do not leak`() {
        // Membership of a DIFFERENT habit must not enable minutes for this one.
        assertFalse(
            effective(
                "Reading",
                minutesEnabled = setOf("Sweat"),
                pcWidget = setOf("Sweat"),
                widgetTrigger = setOf("Sweat"),
                media = setOf("Sweat"),
                movieBridge = setOf("Sweat"),
                minutesPrimary = setOf("Sweat"),
                maxOne = setOf("Sweat")
            )
        )
    }
}
