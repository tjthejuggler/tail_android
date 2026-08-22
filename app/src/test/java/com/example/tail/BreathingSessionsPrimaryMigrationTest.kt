package com.example.tail

import com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS
import com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.swapToSessionsPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one-time breathing sessions-primary migration (Aug-22-2026):
 * Meditations, Resonance Breathing and Until Contraction move from the legacy
 * Wags layout (minutes = primary + sessions in `secondary_value:`) to
 * sessions-primary with the built-in `minutes:` slot — the same
 * [com.example.tail.data.swapToSessionsPrimary] swap the five apnea habits
 * got on Aug-21-2026, applied to [BREATHING_SESSIONS_PRIMARY_HABITS].
 */
class BreathingSessionsPrimaryMigrationTest {

    @Test
    fun `target set covers the three wags breathing habits`() {
        assertEquals(
            setOf("Meditations", "Resonance Breathing", "Until Contraction"),
            BREATHING_SESSIONS_PRIMARY_HABITS
        )
        // Disjoint from the apnea set — no habit is migrated twice.
        assertTrue(
            APNEA_SESSIONS_PRIMARY_HABITS.intersect(BREATHING_SESSIONS_PRIMARY_HABITS).isEmpty()
        )
    }

    @Test
    fun `meditation minutes move to minutes slot and sessions become primary`() {
        val db = mapOf(
            "Meditations" to mapOf("2026-08-20" to 9, "2026-08-21" to 25),
            secondaryValueKey("Meditations") to mapOf("2026-08-20" to 1, "2026-08-21" to 2)
        )
        val out = swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS)
        assertEquals(mapOf("2026-08-20" to 1, "2026-08-21" to 2), out["Meditations"])
        assertEquals(mapOf("2026-08-20" to 9, "2026-08-21" to 25), out[minutesKey("Meditations")])
        assertNull(out[secondaryValueKey("Meditations")])
    }

    @Test
    fun `meditation day with minutes but no session entry gets one session`() {
        // Years of pre-2026 Wags data recorded minutes only — a session must
        // have happened, so the day stays done for streak/points purposes.
        val db = mapOf(
            "Meditations" to mapOf("2020-02-19" to 20, "2026-08-22" to 6)
        )
        val out = swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS)
        assertEquals(mapOf("2020-02-19" to 1, "2026-08-22" to 1), out["Meditations"])
        assertEquals(mapOf("2020-02-19" to 20, "2026-08-22" to 6), out[minutesKey("Meditations")])
    }

    @Test
    fun `resonance sessions only keeps them as primary`() {
        val db = mapOf(
            secondaryValueKey("Resonance Breathing") to mapOf("2026-03-20" to 1, "2026-04-02" to 2)
        )
        val out = swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS)
        assertEquals(mapOf("2026-03-20" to 1, "2026-04-02" to 2), out["Resonance Breathing"])
        assertNull(out[minutesKey("Resonance Breathing")])
        assertNull(out[secondaryValueKey("Resonance Breathing")])
    }

    @Test
    fun `until contraction swaps like the other breathing habits`() {
        val db = mapOf(
            "Until Contraction" to mapOf("2026-08-17" to 1),
            secondaryValueKey("Until Contraction") to mapOf("2026-08-17" to 1)
        )
        val out = swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS)
        assertEquals(mapOf("2026-08-17" to 1), out["Until Contraction"])
        assertEquals(mapOf("2026-08-17" to 1), out[minutesKey("Until Contraction")])
        assertNull(out[secondaryValueKey("Until Contraction")])
    }

    @Test
    fun `apnea practiced is deliberately untouched`() {
        // User decision (Aug-22-2026): "Apnea practiced" keeps its historical
        // meaning — fed by the O2/CO2 Tables conditional links, never
        // incremented on its own — so its legacy layout must survive every
        // sessions-primary migration unchanged.
        val db = mapOf(
            "Apnea practiced" to mapOf("2021-10-06" to 1, "2026-07-17" to 1),
            secondaryValueKey("Apnea practiced") to mapOf("2026-03-01" to 2, "2026-07-17" to 1)
        )
        assertEquals(db, swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS))
        assertEquals(db, swapToSessionsPrimary(db))
        assertFalse("Apnea practiced" in BREATHING_SESSIONS_PRIMARY_HABITS)
    }

    @Test
    fun `already migrated habit is left alone on re-run`() {
        // Idempotent retry: sessions in primary + minutes in the minutes slot,
        // no legacy secondary data — nothing may change.
        val db = mapOf(
            "Meditations" to mapOf("2026-08-22" to 2),
            minutesKey("Meditations") to mapOf("2026-08-22" to 14)
        )
        assertEquals(db, swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS))
    }

    @Test
    fun `special multi-value habits pass through unchanged`() {
        // Chess.com (games in secondary_value:, result in secondary_value2:),
        // JugCoach (six slots) and the movie bridge (IMDb ratings) are NOT
        // part of this migration — their slots must survive untouched.
        val db = mapOf(
            "Blitz Chess" to mapOf("2026-08-20" to 49),
            secondaryValueKey("Blitz Chess") to mapOf("2026-08-20" to 13),
            "secondary_value2:Blitz Chess" to mapOf("2026-08-20" to 8),
            "Juggle run" to mapOf("2026-08-18" to 3),
            secondaryValueKey("Juggle run") to mapOf("2026-08-18" to 420),
            "Fiction Video Intake" to mapOf("2026-08-21" to 6),
            minutesKey("Fiction Video Intake") to mapOf("2026-08-21" to 235),
            secondaryValueKey("Fiction Video Intake") to mapOf("2026-08-21" to 81)
        )
        assertEquals(db, swapToSessionsPrimary(db, BREATHING_SESSIONS_PRIMARY_HABITS))
    }
}
