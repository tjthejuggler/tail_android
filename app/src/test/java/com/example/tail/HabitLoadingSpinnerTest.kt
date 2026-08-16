package com.example.tail

import com.example.tail.ui.LoadingMetrics
import com.example.tail.ui.habitPointsTier
import com.example.tail.ui.loadingTiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for "The Orrery" loading animation tier mapping — the pure
 * logic that resolves the monthly/weekly/daily metric triple into the
 * three independent colour tiers that drive the animation layers.
 */
class HabitLoadingSpinnerTest {

    // ── Tier boundaries (shared with accentColorForPoints in MapScreen) ────

    @Test
    fun `tier boundaries map to the seven app colour tiers`() {
        assertEquals(0, habitPointsTier(0))
        assertEquals(0, habitPointsTier(13))
        assertEquals(1, habitPointsTier(14))
        assertEquals(1, habitPointsTier(20))
        assertEquals(2, habitPointsTier(21))
        assertEquals(2, habitPointsTier(30))
        assertEquals(3, habitPointsTier(31))
        assertEquals(3, habitPointsTier(41))
        assertEquals(4, habitPointsTier(42))
        assertEquals(4, habitPointsTier(48))
        assertEquals(5, habitPointsTier(49))
        assertEquals(5, habitPointsTier(55))
        assertEquals(6, habitPointsTier(56))
        assertEquals(6, habitPointsTier(500))
    }

    // ── Metric triple resolution ───────────────────────────────────────────

    @Test
    fun `averages are rounded before tiering`() {
        // 13.6 → 14 (orange), 20.5 → 21 (green), 0 points → red
        val t = loadingTiers(LoadingMetrics(13.6, 20.5, 0))
        assertEquals(1, t.monthly)
        assertEquals(2, t.weekly)
        assertEquals(0, t.daily)
    }

    @Test
    fun `each metric tiers independently`() {
        val t = loadingTiers(LoadingMetrics(56.0, 31.0, 14))
        assertEquals(6, t.monthly)   // Supernova core
        assertEquals(3, t.weekly)    // tilted-ellipse halo
        assertEquals(1, t.daily)     // steady spark dot
    }

    // ── Resonance flourish ─────────────────────────────────────────────────

    @Test
    fun `resonance requires all three tiers aligned`() {
        assertTrue(loadingTiers(LoadingMetrics(21.0, 21.0, 21)).resonant)
        assertFalse(loadingTiers(LoadingMetrics(21.0, 21.0, 20)).resonant)
        // 20.4 rounds to 20, one tier below the others.
        assertFalse(loadingTiers(LoadingMetrics(21.0, 20.4, 21)).resonant)
    }

    @Test
    fun `resonance never triggers at tier zero`() {
        assertFalse(loadingTiers(LoadingMetrics(0.0, 0.0, 0)).resonant)
    }
}
