package com.example.tail

// Tests for the suggestion feedback loop: every flash impression resolves
// to ACCEPTED (✓ / ✎ / matching manual increment) or IGNORED (shown, not
// used), and the resulting Laplace-smoothed acceptance rate becomes a score
// multiplier that damps — but never erases — habitually ignored habits.

import com.example.tail.data.suggest.SuggestionFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionFeedbackTest {

    private val day0 = 20000L

    private fun rateAfter(accepted: Int, ignored: Int): Double {
        var json = ""
        repeat(accepted) { json = SuggestionFeedback.record(json, "H", true, day0) }
        repeat(ignored) { json = SuggestionFeedback.record(json, "H", false, day0) }
        return SuggestionFeedback.acceptRate(SuggestionFeedback.stats(json, day0)["H"])
    }

    @Test
    fun `empty store is neutral`() {
        assertEquals(0.5, SuggestionFeedback.acceptRate(null), 1e-9)
        assertEquals(1.0, SuggestionFeedback.multiplier(null), 1e-9)
        assertTrue(SuggestionFeedback.multipliers("", day0).isEmpty())
    }

    @Test
    fun `laplace smoothing keeps a single outcome from being extreme`() {
        // One accept → (1+1)/(1+0+2) = 0.667, not 1.0. One ignore → 0.333, not 0.
        assertEquals(2.0 / 3.0, rateAfter(1, 0), 1e-9)
        assertEquals(1.0 / 3.0, rateAfter(0, 1), 1e-9)
    }

    @Test
    fun `multiplier is centred on one with a dead zone`() {
        assertEquals(1.0, SuggestionFeedback.multiplierForRate(0.5), 1e-9)
        assertEquals(1.0, SuggestionFeedback.multiplierForRate(0.52), 1e-9)
        assertEquals(1.0, SuggestionFeedback.multiplierForRate(0.48), 1e-9)
        assertTrue(SuggestionFeedback.multiplierForRate(0.9) > 1.0)
        assertTrue(SuggestionFeedback.multiplierForRate(0.1) < 1.0)
    }

    @Test
    fun `multiplier never erases a habit — floor is respected`() {
        assertEquals(SuggestionFeedback.MIN_MULTIPLIER, SuggestionFeedback.multiplierForRate(0.0), 1e-9)
        assertEquals(SuggestionFeedback.MAX_MULTIPLIER, SuggestionFeedback.multiplierForRate(1.0), 1e-9)
        // A floor-damped overdue daily (raw ≈ 0.9) still clears a 0.30 cut-off …
        assertTrue(0.9 * SuggestionFeedback.MIN_MULTIPLIER >= 0.30)
        // … while a floor-damped marginal card (raw ≈ 0.4) drops out.
        assertTrue(0.4 * SuggestionFeedback.MIN_MULTIPLIER < 0.30)
    }

    @Test
    fun `habitually ignored habit sinks and habitually accepted one floats`() {
        val ignored = rateAfter(1, 10)      // (1+1)/(1+10+2) ≈ 0.154
        val accepted = rateAfter(10, 1)     // (10+1)/(10+1+2) ≈ 0.846
        assertTrue("ignored rate $ignored", ignored < 0.25)
        assertTrue("accepted rate $accepted", accepted > 0.75)
        val mIgnored = SuggestionFeedback.multiplierForRate(ignored)   // ≈ 0.57
        val mAccepted = SuggestionFeedback.multiplierForRate(accepted)  // ≈ 1.26
        assertTrue("ignored multiplier $mIgnored", mIgnored < 0.6)
        assertTrue("accepted multiplier $mAccepted", mAccepted > 1.2)
        // Damp, don't erase: a 10:1 ignore record must still let a strong
        // (overdue-daily-like, 0.7) card through …
        assertTrue("strong card survives damping", 0.7 * mIgnored >= 0.30)
        // … while a marginal one (0.4) is pushed under the 0.30 cut-off.
        assertTrue("marginal card drops out", 0.4 * mIgnored < 0.30)
    }

    @Test
    fun `same-count history stays neutral`() {
        assertEquals(0.5, rateAfter(5, 5), 1e-9)
        assertEquals(1.0, SuggestionFeedback.multiplierForRate(rateAfter(5, 5)), 1e-9)
    }

    @Test
    fun `counters decay with a 14-day half life`() {
        val json = SuggestionFeedback.record("", "H", false, day0)
        val fresh = SuggestionFeedback.stats(json, day0)["H"]!!
        assertEquals(1.0, fresh.ignored, 1e-9)
        // Read 14 days later without touching: the counter has halved …
        val later = SuggestionFeedback.stats(json, day0 + 14)["H"]!!
        assertEquals(0.5, later.ignored, 1e-9)
        // … and 28 days later it is a quarter.
        val muchLater = SuggestionFeedback.stats(json, day0 + 28)["H"]!!
        assertEquals(0.25, muchLater.ignored, 1e-9)
    }

    @Test
    fun `decay is baked in on write so old evidence fades naturally`() {
        // Ten ignores long ago should matter far less than one recent accept.
        var json = ""
        repeat(10) { json = SuggestionFeedback.record(json, "H", false, day0) }
        // One accept 70 days (5 half-lives) later: ign 10 → 0.3125, then +1 acc.
        json = SuggestionFeedback.record(json, "H", true, day0 + 70)
        val s = SuggestionFeedback.stats(json, day0 + 70)["H"]!!
        assertEquals(0.3125, s.ignored, 1e-9)
        assertEquals(1.0, s.accepted, 1e-9)
        assertTrue(SuggestionFeedback.acceptRate(s) > 0.6)
    }

    @Test
    fun `counters are capped so one binge cannot pin the rate`() {
        var json = ""
        repeat(500) { json = SuggestionFeedback.record(json, "H", false, day0) }
        val s = SuggestionFeedback.stats(json, day0)["H"]!!
        assertEquals(SuggestionFeedback.MAX_COUNT, s.ignored, 1e-9)
        // With the cap, a handful of accepts can still pull the rate back up.
        repeat(20) { json = SuggestionFeedback.record(json, "H", true, day0) }
        val after = SuggestionFeedback.stats(json, day0)["H"]!!
        assertTrue(SuggestionFeedback.acceptRate(after) > SuggestionFeedback.acceptRate(s))
    }

    @Test
    fun `habits are tracked independently`() {
        var json = SuggestionFeedback.record("", "A", true, day0)
        json = SuggestionFeedback.record(json, "B", false, day0)
        val m = SuggestionFeedback.multipliers(json, day0)
        assertTrue(m["A"]!! > 1.0)
        assertTrue(m["B"]!! < 1.0)
    }

    @Test
    fun `stale entries are pruned after the retention window`() {
        // "Old" last touched day0, "New" last touched day0+100.
        var json = SuggestionFeedback.record("", "Old", false, day0)
        json = SuggestionFeedback.record(json, "New", false, day0 + 100)
        val kept = SuggestionFeedback.stats(json, day0 + 100)
        assertTrue("both fresh enough at day0+100", "Old" in kept && "New" in kept)
        // At day0+180 "Old" is exactly RETENTION_DAYS stale (dropped) while
        // "New" (80 days old) is still inside the window (kept).
        val pruned = SuggestionFeedback.prune(json, day0 + SuggestionFeedback.RETENTION_DAYS)
        val after = SuggestionFeedback.stats(pruned, day0 + SuggestionFeedback.RETENTION_DAYS)
        assertTrue("Old dropped", "Old" !in after)
        assertTrue("New kept (touched later)", "New" in after)
        // At day0+100+180 "New" is stale too → everything gone.
        val allGone = SuggestionFeedback.prune(pruned, day0 + 100 + SuggestionFeedback.RETENTION_DAYS)
        assertTrue(SuggestionFeedback.stats(allGone, day0 + 100 + SuggestionFeedback.RETENTION_DAYS).isEmpty())
    }

    @Test
    fun `corrupt json never throws`() {
        assertTrue(SuggestionFeedback.stats("{not json", day0).isEmpty())
        val recovered = SuggestionFeedback.record("{not json", "H", true, day0)
        assertEquals(1.0, SuggestionFeedback.stats(recovered, day0)["H"]!!.accepted, 1e-9)
    }

    @Test
    fun `multipliers helper matches per-habit multiplier`() {
        var json = SuggestionFeedback.record("", "H", false, day0)
        json = SuggestionFeedback.record(json, "H", false, day0)
        val expected = SuggestionFeedback.multiplier(SuggestionFeedback.stats(json, day0)["H"])
        assertEquals(expected, SuggestionFeedback.multipliers(json, day0)["H"]!!, 1e-12)
    }
}
