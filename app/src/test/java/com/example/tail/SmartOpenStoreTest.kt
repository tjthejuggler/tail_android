package com.example.tail

import com.example.tail.data.SmartOpenStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smart Open — pure-logic tests for the learned context→screen model:
 * recording, weighting, dominance threshold, recency decay, and the
 * two-week pruning that keeps the store from filling up with stale
 * locations/apps/times.
 */
class SmartOpenStoreTest {

    private val day0 = 20_000L // arbitrary "today" epoch day

    private fun recordTimes(json: String, screen: Int, keys: List<String>, times: Int, day: Long = day0): String {
        var out = json
        repeat(times) { out = SmartOpenStore.record(out, screen, keys, day) }
        return out
    }

    @Test
    fun `hour buckets are two-hour wide`() {
        assertEquals(0, SmartOpenStore.hourBucket(0))
        assertEquals(0, SmartOpenStore.hourBucket(1))
        assertEquals(3, SmartOpenStore.hourBucket(7))
        assertEquals(11, SmartOpenStore.hourBucket(23))
    }

    @Test
    fun `location signal beats hour-only signal`() {
        // Screen 1: 10 gym increments (hour + location + app)
        // Screen 2: 10 generic increments at a DIFFERENT hour (hour only)
        val gymCtx = SmartOpenStore.signalKeys(18, "com.gym.app", Pair(45.1234, 9.2345))
        val genericCtx = SmartOpenStore.signalKeys(12, null, null)
        var json = "{}"
        json = recordTimes(json, 1, gymCtx, 10)
        json = recordTimes(json, 2, genericCtx, 10)

        // At the gym: screen 1 wins decisively.
        assertEquals(1, SmartOpenStore.resolve(json, gymCtx, day0))
        // Elsewhere, generic hour still points at screen 2.
        assertEquals(2, SmartOpenStore.resolve(json, genericCtx, day0))
    }

    @Test
    fun `app association wins when app context matches`() {
        // "Just left the juggling app" → juggle screen, learned via the
        // recent-foreground-app signal.
        val jugglingCtx = SmartOpenStore.signalKeys(15, "com.juggle.app", null)
        var json = recordTimes("{}", 3, jugglingCtx, 4)
        // A DIFFERENT hour without the app context must not confidently switch.
        val otherHour = SmartOpenStore.signalKeys(21, null, null)
        assertEquals(3, SmartOpenStore.resolve(json, jugglingCtx, day0))
        assertEquals(-1, SmartOpenStore.resolve(json, otherHour, day0))
    }

    @Test
    fun `ambiguous context yields no guess`() {
        // Two screens reinforced identically for the same hour — no
        // dominance, so no switch (stay on the saved screen).
        val ctx = SmartOpenStore.signalKeys(9, null, null)
        var json = "{}"
        json = recordTimes(json, 0, ctx, 5)
        json = recordTimes(json, 5, ctx, 5)
        assertEquals(-1, SmartOpenStore.resolve(json, ctx, day0))
    }

    @Test
    fun `empty store yields no guess`() {
        assertEquals(-1, SmartOpenStore.resolve("{}", SmartOpenStore.signalKeys(9, null, null), day0))
        assertEquals(-1, SmartOpenStore.resolve("", SmartOpenStore.signalKeys(9, null, null), day0))
    }

    @Test
    fun `stale signals decay then vanish`() {
        val gymCtx = SmartOpenStore.signalKeys(18, null, Pair(45.0, 9.0))
        var json = recordTimes("{}", 1, gymCtx, 10)
        // Half the retention window later: half weight — 10 * 3.0 * 0.5 = 15
        // still clears the bar alone.
        assertEquals(1, SmartOpenStore.resolve(json, gymCtx, day0 + SmartOpenStore.RETENTION_DAYS / 2))
        // At the retention edge: pruned away entirely, no guess.
        val pruned = SmartOpenStore.prune(json, day0 + SmartOpenStore.RETENTION_DAYS)
        assertEquals(-1, SmartOpenStore.resolve(pruned, gymCtx, day0 + SmartOpenStore.RETENTION_DAYS))
    }

    @Test
    fun `prune removes only stale entries and emptied screens`() {
        val fresh = SmartOpenStore.signalKeys(8, null, null)
        val old = SmartOpenStore.signalKeys(20, null, null)
        var json = "{}"
        json = recordTimes(json, 0, fresh, 3)
        json = recordTimes(json, 0, old, 3, day0 - SmartOpenStore.RETENTION_DAYS)
        json = recordTimes(json, 7, old, 3, day0 - SmartOpenStore.RETENTION_DAYS)
        val pruned = SmartOpenStore.prune(json, day0)
        val root = JSONObject(pruned)
        // Screen 7 had ONLY stale entries → gone. Screen 0 keeps the fresh hour.
        assertTrue(!root.has("7"))
        assertTrue(root.getJSONObject("0").has("h=4"))
        assertTrue(!root.getJSONObject("0").has("h=10"))
    }

    @Test
    fun `record caps counts so one binge cannot dominate`() {
        val ctx = SmartOpenStore.signalKeys(12, null, null)
        val json = recordTimes("{}", 2, ctx, 100)
        val entry = JSONObject(json).getJSONObject("2").getJSONObject(ctx[0])
        assertTrue(entry.getInt("c") <= SmartOpenStore.MAX_COUNT)
    }

    @Test
    fun `recent reinforcement beats older equal-weight history`() {
        val ctx = SmartOpenStore.signalKeys(10, null, null)
        var json = "{}"
        // Screen A: strong but OLD history.
        json = recordTimes(json, 4, ctx, 10, day0 - 10)
        // Screen B: weaker but RECENT.
        json = recordTimes(json, 6, ctx, 4)
        // B: 4*1*1.0=4 vs A: 10*1*(4/14)≈2.86 → B wins, but dominance (4<1.5*2.86)
        // is not met → no guess rather than a coin flip.
        assertEquals(-1, SmartOpenStore.resolve(json, ctx, day0))
        // With one more recent increment B dominates.
        val json2 = recordTimes(json, 6, ctx, 1)
        assertNotEquals(4, SmartOpenStore.resolve(json2, ctx, day0))
    }
}
