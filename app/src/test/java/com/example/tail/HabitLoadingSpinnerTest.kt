package com.example.tail

import com.example.tail.ui.GrandeurThresholds
import com.example.tail.ui.LoadingMetrics
import com.example.tail.ui.habitPointsTier
import com.example.tail.ui.loadingTiers
import com.example.tail.ui.patronageFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for "The Orrery II" loading animation — the pure logic that
 * resolves the monthly/weekly/daily metric triple into the three
 * independent colour tiers, the per-layer PATRONAGE ranks (how strongly
 * the other two metrics upgrade each layer) and the global GRANDEUR
 * budget that drives size and the cross-cutting flourishes.
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

    // ── Patronage — the other two metrics upgrade each layer ──────────────

    @Test
    fun `patronage thresholds split the support sum into three ranks`() {
        assertEquals(0, patronageFrom(0))
        assertEquals(0, patronageFrom(3))
        assertEquals(1, patronageFrom(4))
        assertEquals(1, patronageFrom(7))
        assertEquals(2, patronageFrom(8))
        assertEquals(2, patronageFrom(12))
    }

    @Test
    fun `a mighty month and day champion an orange week`() {
        // monthly 52 → tier 5 (yellow), weekly 15 → tier 1 (orange),
        // daily 50 → tier 5 (yellow). The orange halo is PATRON-boosted.
        val t = loadingTiers(LoadingMetrics(52.0, 15.0, 50))
        assertEquals(5, t.monthly)
        assertEquals(1, t.weekly)
        assertEquals(5, t.daily)
        assertEquals(2, t.weekPatronage)   // 5 + 5 = 10 → PATRON
        assertEquals(1, t.monthPatronage)  // 1 + 5 = 6  → ALLY
        assertEquals(1, t.dayPatronage)    // 5 + 1 = 6  → ALLY
    }

    @Test
    fun `all-low metrics leave every layer a stranger`() {
        val t = loadingTiers(LoadingMetrics(3.0, 5.0, 2))
        assertEquals(0, t.monthly)
        assertEquals(0, t.weekly)
        assertEquals(0, t.daily)
        assertEquals(0, t.monthPatronage)
        assertEquals(0, t.weekPatronage)
        assertEquals(0, t.dayPatronage)
    }

    @Test
    fun `a strong supporter upgrades a weak layer to ally`() {
        // monthly 31 → tier 3, weekly 2 → tier 0, daily 0 → tier 0.
        // A tier-3 month alone is not enough (3 + 0 = 3 → stranger)…
        val t = loadingTiers(LoadingMetrics(31.0, 2.0, 0))
        assertEquals(0, t.weekPatronage)
        assertEquals(0, t.monthPatronage)  // 0 + 0 → stranger
        // …but a tier-4 month alone lifts the halo to ALLY (4 ≥ 4):
        val t2 = loadingTiers(LoadingMetrics(42.0, 2.0, 0))
        assertEquals(1, t2.weekPatronage)  // 4 + 0 = 4 → ALLY
    }

    // ── Grandeur — the global spectacle budget ─────────────────────────────

    @Test
    fun `grandeur is the sum of all three tiers`() {
        assertEquals(0, loadingTiers(LoadingMetrics(0.0, 0.0, 0)).grandeur)
        assertEquals(18, loadingTiers(LoadingMetrics(56.0, 56.0, 56)).grandeur)
        assertEquals(11, loadingTiers(LoadingMetrics(49.0, 31.0, 31)).grandeur) // 5+3+3
    }

    @Test
    fun `grandeur thresholds ascend and cap at totality`() {
        val th = GrandeurThresholds
        assertTrue(th.NEBULA < th.STARFIELD)
        assertTrue(th.STARFIELD < th.CORONA)
        assertTrue(th.CORONA < th.SHOOTING_STARS)
        assertTrue(th.SHOOTING_STARS < th.SPECTRUM_CROWN)
        assertTrue(th.SPECTRUM_CROWN < th.TOTALITY)
        assertEquals(18, th.TOTALITY)
    }

    @Test
    fun `the perfect trinity reaches totality and resonance`() {
        val t = loadingTiers(LoadingMetrics(56.0, 56.0, 56))
        assertEquals(18, t.grandeur)
        assertTrue(t.resonant)
        assertEquals(2, t.monthPatronage)
        assertEquals(2, t.weekPatronage)
        assertEquals(2, t.dayPatronage)
    }
}
