package com.example.tail.data.suggest

import org.json.JSONObject
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════
 * SUGGESTION FEEDBACK — learns from the suggestions the user does NOT use
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The history scorer ([HabitScorer]) answers "is this habit likely right
 * now?" from timestamps alone — it has NO idea whether the suggestion was
 * actually useful. A habit the user never logs through the flash kept
 * reappearing at the same rank forever.
 *
 * This store closes that loop. Every flash session records an impression
 * per shown card; when the session ends each card resolves to
 *  · ACCEPTED — ✓ tapped on the card, ✎ opened its dialog, OR the habit
 *    was incremented by hand while the flash was open (the suggestion was
 *    RIGHT, the user just tapped the tile instead);
 *  · IGNORED  — shown, not used.
 *
 * Per habit we keep Laplace-smoothed decayed counters `acc` / `ign`:
 *
 *   p = (acc + 1) / (acc + ign + 2)        ∈ (0, 1), neutral 0.5 when new
 *
 * Both counters decay exponentially on every write according to the days
 * since the entry's last touch (half-life [HALF_LIFE_DAYS]) so the model
 * follows changing routines. [multiplierForRate] turns `p` into a score
 * multiplier centred on 1.0:
 *
 *   p = 0.5  → ×1.00   (no evidence either way)
 *   p = 0.9  → ×1.48   (habitually accepted — floats up)
 *   p = 0.1  → ×0.48   (habitually ignored — sinks below the threshold)
 *
 * The floor [MIN_MULTIPLIER] guarantees a habit is DAMPED, never erased:
 * a genuinely overdue daily (score ≈ 0.9) still clears a 0.30 cut-off at
 * the floor, while a marginal one (score ≈ 0.4) drops out. That is the
 * desired behaviour — "stop suggesting the things I don't use" — without
 * the user having to manage a 🚫 list by hand.
 *
 * Persistence: one small JSON blob in SharedPreferences, same shape and
 * hygiene as [com.example.tail.data.SmartOpenStore]:
 * `{ "<habit>": {"a": acc, "i": ign, "d": epochDay} }`.
 * Entries not touched for [RETENTION_DAYS] are pruned so a habit that was
 * ignored for a while but later becomes relevant starts fresh.
 */
object SuggestionFeedback {

    const val PREFS = "suggestion_feedback"
    const val KEY_STATS = "stats"

    /** Counters halve every this many days (applied per write, from the entry's last touch). */
    const val HALF_LIFE_DAYS = 14.0

    /** A habit not touched for this long is forgotten entirely. */
    const val RETENTION_DAYS = 180L

    /** Per-counter cap — one busy month can't pin the rate at 0 or 1. */
    const val MAX_COUNT = 60.0

    /** Score multiplier floor / ceiling (see class doc). */
    const val MIN_MULTIPLIER = 0.35
    const val MAX_MULTIPLIER = 1.6

    /** Neutral band: rates within ±[DEAD_ZONE] of 0.5 change nothing. */
    const val DEAD_ZONE = 0.05

    // ────────────────────────────────────────────────────────────────────
    // Pure logic — unit-testable, no Android dependencies
    // ────────────────────────────────────────────────────────────────────

    /** Decayed counters for one habit. */
    data class Stats(val accepted: Double, val ignored: Double, val lastEpochDay: Long)

    /** Laplace-smoothed acceptance rate in (0, 1); 0.5 when nothing is known. */
    fun acceptRate(s: Stats?): Double {
        if (s == null) return 0.5
        return (s.accepted + 1.0) / (s.accepted + s.ignored + 2.0)
    }

    /**
     * Score multiplier for an acceptance rate. Linear from [MIN_MULTIPLIER]
     * at p = 0 to [MAX_MULTIPLIER] at p = 1, with a flat 1.0 dead-zone
     * around the neutral 0.5 so a single stray outcome doesn't nudge a card.
     */
    fun multiplierForRate(p: Double): Double {
        val hi = 0.5 + DEAD_ZONE
        val lo = 0.5 - DEAD_ZONE
        return when {
            p > hi -> {
                val t = (p - hi) / (1.0 - hi)
                1.0 + t * (MAX_MULTIPLIER - 1.0)
            }
            p < lo -> {
                val t = (lo - p) / lo
                1.0 - t * (1.0 - MIN_MULTIPLIER)
            }
            else -> 1.0
        }
    }

    fun multiplier(s: Stats?): Double = multiplierForRate(acceptRate(s))

    private fun decayed(s: Stats, nowEpochDay: Long): Stats {
        val days = (nowEpochDay - s.lastEpochDay).coerceAtLeast(0L)
        val f = Math.pow(0.5, days / HALF_LIFE_DAYS)
        return Stats(s.accepted * f, s.ignored * f, nowEpochDay)
    }

    private fun parseEntry(obj: JSONObject?, nowEpochDay: Long): Stats? {
        if (obj == null) return null
        val s = Stats(
            accepted = obj.optDouble("a", 0.0),
            ignored = obj.optDouble("i", 0.0),
            lastEpochDay = obj.optLong("d", nowEpochDay)
        )
        if (s.accepted <= 0.0 && s.ignored <= 0.0) return null
        return decayed(s, nowEpochDay)
    }

    private fun parseRoot(json: String): JSONObject =
        if (json.isBlank()) JSONObject() else try { JSONObject(json) } catch (_: Exception) { JSONObject() }

    /** Parses the whole store into habit → decayed stats (stale entries dropped). */
    fun stats(json: String, nowEpochDay: Long): Map<String, Stats> {
        if (json.isBlank()) return emptyMap()
        val root = parseRoot(json)
        val out = HashMap<String, Stats>()
        for (key in root.keys().asSequence().toList()) {
            val s = parseEntry(root.optJSONObject(key), nowEpochDay) ?: continue
            if (nowEpochDay - s.lastEpochDay >= RETENTION_DAYS) continue
            out[key] = s
        }
        return out
    }

    /** The multiplier each habit currently deserves (convenience for scoring). */
    fun multipliers(json: String, nowEpochDay: Long): Map<String, Double> =
        stats(json, nowEpochDay).mapValues { (_, s) -> multiplier(s) }

    /**
     * Records one outcome for [habit]: +1 to the matching counter after
     * applying time decay since the entry's last touch, capped at
     * [MAX_COUNT]. Returns the updated (pruned) JSON.
     */
    fun record(json: String, habit: String, accepted: Boolean, nowEpochDay: Long): String {
        val root = parseRoot(json)
        val base = parseEntry(root.optJSONObject(habit), nowEpochDay) ?: Stats(0.0, 0.0, nowEpochDay)
        val updated = if (accepted) {
            Stats(min(base.accepted + 1.0, MAX_COUNT), base.ignored, nowEpochDay)
        } else {
            Stats(base.accepted, min(base.ignored + 1.0, MAX_COUNT), nowEpochDay)
        }
        root.put(habit, JSONObject().put("a", updated.accepted).put("i", updated.ignored).put("d", nowEpochDay))
        return pruneObj(root, nowEpochDay).toString()
    }

    /** Drops entries untouched for [RETENTION_DAYS]. */
    fun prune(json: String, nowEpochDay: Long): String = pruneObj(parseRoot(json), nowEpochDay).toString()

    private fun pruneObj(root: JSONObject, nowEpochDay: Long): JSONObject {
        for (key in root.keys().asSequence().toList()) {
            val e = root.optJSONObject(key) ?: continue
            if (nowEpochDay - e.optLong("d", nowEpochDay) >= RETENTION_DAYS) root.remove(key)
        }
        return root
    }

    // ────────────────────────────────────────────────────────────────────
    // Android glue
    // ────────────────────────────────────────────────────────────────────

    private val ioLock = Any()

    private fun prefs(context: android.content.Context) =
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    /** Current per-habit score multipliers. Cheap: one prefs read + tiny JSON parse. */
    fun multipliers(context: android.content.Context): Map<String, Double> {
        return try {
            val json = prefs(context).getString(KEY_STATS, "").orEmpty()
            multipliers(json, java.time.LocalDate.now().toEpochDay())
        } catch (t: Throwable) {
            android.util.Log.w("SuggestFeedback", "read failed: ${t.message}")
            emptyMap()
        }
    }

    /**
     * Fire-and-forget write of one outcome. Call from any thread; the
     * read-modify-write is serialised on [ioLock] and persisted with
     * `apply()` so the caller never waits on disk.
     */
    fun recordAsync(context: android.content.Context, habit: String, accepted: Boolean) {
        try {
            synchronized(ioLock) {
                val p = prefs(context)
                val day = java.time.LocalDate.now().toEpochDay()
                val updated = record(p.getString(KEY_STATS, "").orEmpty(), habit, accepted, day)
                p.edit().putString(KEY_STATS, updated).apply()
            }
        } catch (t: Throwable) {
            android.util.Log.w("SuggestFeedback", "record failed: ${t.message}")
        }
    }

    /** Wipes all learned feedback (Settings → "Reset learning"). */
    fun clear(context: android.content.Context) {
        try {
            synchronized(ioLock) { prefs(context).edit().remove(KEY_STATS).apply() }
        } catch (t: Throwable) {
            android.util.Log.w("SuggestFeedback", "clear failed: ${t.message}")
        }
    }
}

/**
 * ONE flash session's worth of impressions, resolved when the flash closes.
 *
 * Created on the main thread when the strip is shown; cards register with
 * [track], the UI callbacks resolve them with [markAccepted] (✓ / ✎), the
 * ViewModel's increment path resolves them with [onManualIncrement] (tile
 * tap on a suggested habit), and [settle] turns everything still unresolved
 * into IGNORED. Instances are single-use: once settled they ignore calls.
 *
 * Thread-safety: every method is synchronised on the instance so a settle
 * racing a late increment can never double-count — a resolved impression is
 * resolved exactly once no matter which path gets there first.
 */
class SuggestionSession(private val context: android.content.Context) {

    private class Impression {
        @Volatile var resolved = false
    }

    private val impressions = LinkedHashMap<String, Impression>()

    /** True once [settle] has run — a settled session must not be reused. */
    @Volatile
    var settled: Boolean = false
        private set

    /** Registers that [habit]'s card is on screen right now. */
    fun track(habit: String) {
        synchronized(impressions) {
            if (settled) return
            impressions.getOrPut(habit) { Impression() }
        }
    }

    /**
     * Resolves [habit]'s impression as ACCEPTED (✓ tap, ✎ edit, or a
     * matching manual increment). Later calls are no-ops once resolved, so
     * one card can never count as more than one accept. Returns true when
     * this call was the one that resolved it.
     */
    fun markAccepted(habit: String): Boolean {
        synchronized(impressions) {
            if (settled) return false
            val imp = impressions[habit] ?: return false
            if (imp.resolved) return false
            imp.resolved = true
        }
        SuggestionFeedback.recordAsync(context, habit, accepted = true)
        return true
    }

    /**
     * Hook for the ViewModel's manual-increment path. When [habit] has an
     * UNRESOLVED impression from the current flash the suggestion was right
     * after all (the user tapped the tile instead of the card) → accept.
     * No elapsed-time check on purpose: a flash can legitimately stay open
     * for minutes while the user works through cards, and a tile tap on a
     * suggested habit while it is open is a correct prediction regardless.
     */
    fun onManualIncrement(habit: String): Boolean = markAccepted(habit)

    /** Unresolved impressions at close become IGNORED. Idempotent. */
    fun settle() {
        val pending: List<String>
        synchronized(impressions) {
            if (settled) return
            settled = true
            pending = impressions.filterValues { !it.resolved }.keys.toList()
        }
        for (habit in pending) SuggestionFeedback.recordAsync(context, habit, accepted = false)
    }
}
