package com.example.tail

import com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.swapToSessionsPrimary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the one-time apnea sessions-primary migration
 * ([com.example.tail.data.swapToSessionsPrimary]).
 *
 * Context: the five Wags-fed apnea habits stored MINUTES in the primary key
 * and SESSIONS in the legacy `secondary_value:` slot (the Wags IPC protocol
 * layout). The migration makes sessions the primary value (and points
 * source) and moves the minutes into the first-class `minutes:` slot.
 */
class ApneaSessionsPrimaryMigrationTest {

    // ── Data swap ─────────────────────────────────────────────────────────────

    @Test
    fun `minutes move to minutes slot and sessions become primary`() {
        val db = mapOf(
            "Apnea apb" to mapOf("2026-08-17" to 12, "2026-08-19" to 2),
            secondaryValueKey("Apnea apb") to mapOf("2026-08-17" to 2, "2026-08-19" to 1)
        )
        val out = swapToSessionsPrimary(db)
        assertEquals(mapOf("2026-08-17" to 2, "2026-08-19" to 1), out["Apnea apb"])
        assertEquals(mapOf("2026-08-17" to 12, "2026-08-19" to 2), out[minutesKey("Apnea apb")])
        assertNull(out[secondaryValueKey("Apnea apb")])
    }

    @Test
    fun `day with minutes but no session entry gets one session`() {
        // Historical Wags data: minutes were recorded before the sessions
        // backfill existed. A session must have happened — keep the day done.
        val db = mapOf(
            "Progressive O2" to mapOf("2026-04-10" to 25),
            secondaryValueKey("Progressive O2") to mapOf("2026-08-17" to 1)
        )
        val out = swapToSessionsPrimary(db)
        assertEquals(mapOf("2026-04-10" to 1, "2026-08-17" to 1), out["Progressive O2"])
        assertEquals(mapOf("2026-04-10" to 25), out[minutesKey("Progressive O2")])
    }

    @Test
    fun `zero entries are dropped and legacy slot removed`() {
        // Pre-March-2026 dates were zeroed in primary by the earlier apnea
        // migration; the swap must not resurrect them as minutes.
        val db = mapOf(
            "Apnea apb" to mapOf("2021-10-04" to 0, "2026-08-21" to 2),
            secondaryValueKey("Apnea apb") to mapOf("2021-10-04" to 1, "2026-08-21" to 0)
        )
        val out = swapToSessionsPrimary(db)
        assertEquals(mapOf("2021-10-04" to 1, "2026-08-21" to 1), out["Apnea apb"])
        assertEquals(mapOf("2026-08-21" to 2), out[minutesKey("Apnea apb")])
        assertNull(out[secondaryValueKey("Apnea apb")])
    }

    @Test
    fun `existing minutes slot data is max-merged with primary minutes`() {
        // Stray hand-entered minutes: values (e.g. the Aug-19 Min Breath case)
        // must never be lost — take the larger of the two per date.
        val db = mapOf(
            "Apnea Min Breath" to mapOf("2026-08-19" to 6),
            minutesKey("Apnea Min Breath") to mapOf("2026-08-19" to 4, "2026-08-20" to 3),
            secondaryValueKey("Apnea Min Breath") to mapOf("2026-08-19" to 2)
        )
        val out = swapToSessionsPrimary(db)
        assertEquals(mapOf("2026-08-19" to 6, "2026-08-20" to 3), out[minutesKey("Apnea Min Breath")])
        assertEquals(mapOf("2026-08-19" to 2), out["Apnea Min Breath"])
    }

    @Test
    fun `habit with sessions only keeps them as primary`() {
        // Free-hold days recorded before Wags sent minutes: sessions already
        // in the legacy slot, no minutes anywhere.
        val db = mapOf(
            secondaryValueKey("O2 Tables") to mapOf("2026-04-02" to 1, "2026-04-09" to 1)
        )
        val out = swapToSessionsPrimary(db)
        assertEquals(mapOf("2026-04-02" to 1, "2026-04-09" to 1), out["O2 Tables"])
        assertNull(out[minutesKey("O2 Tables")])
        assertNull(out[secondaryValueKey("O2 Tables")])
    }

    @Test
    fun `habit with no data at all is untouched`() {
        val db = mapOf("CO2 Tables" to emptyMap<String, Int>())
        val out = swapToSessionsPrimary(db)
        // The empty primary key is preserved as-is — nothing to swap.
        assertEquals(db, out)
        assertFalse(out.containsKey(minutesKey("CO2 Tables")))
    }

    @Test
    fun `non-target habits pass through unchanged`() {
        val db = mapOf(
            "Until Contraction" to mapOf("2026-08-17" to 8),
            secondaryValueKey("Until Contraction") to mapOf("2026-08-17" to 1),
            "Meditations" to mapOf("2026-08-19" to 15),
            secondaryValueKey("Meditations") to mapOf("2026-08-19" to 1)
        )
        assertEquals(db, swapToSessionsPrimary(db))
    }

    @Test
    fun `target set is exactly the five wags apnea habits`() {
        assertEquals(
            setOf("Apnea apb", "Progressive O2", "Apnea Min Breath", "O2 Tables", "CO2 Tables"),
            APNEA_SESSIONS_PRIMARY_HABITS
        )
    }

    @Test
    fun `swap is idempotent`() {
        val db = mapOf(
            "Apnea apb" to mapOf("2026-08-17" to 12),
            secondaryValueKey("Apnea apb") to mapOf("2026-08-17" to 2)
        )
        val once = swapToSessionsPrimary(db)
        assertEquals(once, swapToSessionsPrimary(once))
    }

    @Test
    fun `device replica - all five habits swap correctly`() {
        // Replica of the live phone DB layout for the five habits.
        val db = mapOf(
            "Apnea apb" to mapOf("2026-08-16" to 2, "2026-08-19" to 2, "2026-08-21" to 2),
            secondaryValueKey("Apnea apb") to mapOf("2026-08-16" to 1, "2026-08-19" to 1, "2026-08-21" to 1),
            "O2 Tables" to mapOf("2026-04-02" to 6, "2026-04-09" to 6),
            secondaryValueKey("O2 Tables") to mapOf("2026-04-02" to 1),
            "CO2 Tables" to mapOf("2026-04-02" to 8, "2026-07-17" to 16),
            secondaryValueKey("CO2 Tables") to mapOf("2026-04-02" to 2, "2026-07-17" to 1),
            "Progressive O2" to mapOf("2026-08-14" to 3, "2026-08-17" to 8, "2026-08-18" to 6),
            secondaryValueKey("Progressive O2") to mapOf("2026-08-14" to 1, "2026-08-17" to 1, "2026-08-18" to 1),
            "Apnea Min Breath" to mapOf("2026-08-19" to 6, "2026-08-20" to 3, "2026-08-21" to 13),
            secondaryValueKey("Apnea Min Breath") to mapOf("2026-08-19" to 2, "2026-08-20" to 1, "2026-08-21" to 6)
        )
        val out = swapToSessionsPrimary(db)
        // Sessions are primary everywhere; minutes preserved in the slot.
        assertEquals(mapOf("2026-04-02" to 2, "2026-07-17" to 1), out["CO2 Tables"])
        assertEquals(mapOf("2026-04-02" to 8, "2026-07-17" to 16), out[minutesKey("CO2 Tables")])
        assertEquals(mapOf("2026-08-19" to 2, "2026-08-20" to 1, "2026-08-21" to 6), out["Apnea Min Breath"])
        assertEquals(mapOf("2026-08-19" to 6, "2026-08-20" to 3, "2026-08-21" to 13), out[minutesKey("Apnea Min Breath")])
        // O2 Tables gained one inferred session for the minutes-only day.
        assertEquals(mapOf("2026-04-02" to 1, "2026-04-09" to 1), out["O2 Tables"])
        for (habit in APNEA_SESSIONS_PRIMARY_HABITS) {
            assertNull(out[secondaryValueKey(habit)])
            assertTrue(out[minutesKey(habit)].orEmpty().isNotEmpty())
        }
    }
}
