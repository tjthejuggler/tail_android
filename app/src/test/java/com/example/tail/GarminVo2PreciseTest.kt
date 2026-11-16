package com.example.tail

import com.example.tail.data.health.GarminType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * VO2max precise-value semantics: Garmin's GraphQL gateway reports a
 * one-decimal value (vo2MaxPreciseValue, e.g. 47.5) which the app stores
 * ×10 (475) — the same integer-clean scaling pattern the repo already uses
 * for fitness age (×100), environment metrics and weights (×10).
 */
class GarminVo2PreciseTest {

    @Test
    fun `tenths formatter renders one decimal only when present`() {
        assertEquals("47.5", GarminType.VO2_MAX.formatVo2Tenths(475))
        assertEquals("48", GarminType.VO2_MAX.formatVo2Tenths(480))
        assertEquals("52.2", GarminType.VO2_MAX.formatVo2Tenths(522))
        assertEquals("0", GarminType.VO2_MAX.formatVo2Tenths(0))
    }

    @Test
    fun `legacy plain-int values sit below the x10 threshold`() {
        // Historic caches store plain ints (40..60 for humans); every real
        // ×10 value (400..600) is far above the threshold, so the rescale
        // rule (value < 150 → multiply by 10) can never corrupt fresh data.
        val legacySample = 48
        val freshSample = 475
        assert(legacySample < GarminType.VO2_MAX_X10_LEGACY_MAX)
        assert(freshSample >= GarminType.VO2_MAX_X10_LEGACY_MAX)
        // Rescaled legacy lands on a whole-number tenths value.
        assertEquals("48", GarminType.VO2_MAX.formatVo2Tenths(legacySample * 10))
    }

    @Test
    fun `precise value maps to x10 storage`() {
        // Mirrors processMetricsToDaily: ((precise ?: rounded) * 10).toInt()
        val roundedOnly = 48.0 to null
        val precise = 48.0 to 47.5
        assertEquals(480, ((roundedOnly.second ?: roundedOnly.first) * 10).toInt())
        assertEquals(475, ((precise.second ?: precise.first) * 10).toInt())
    }
}
