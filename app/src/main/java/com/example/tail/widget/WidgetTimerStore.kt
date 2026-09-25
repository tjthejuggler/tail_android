package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-habit timer state for the floating-bubble "Start/Stop Timer" feature.
 *
 * The timer is intentionally just a persisted START TIMESTAMP per habit — no
 * ticking process is needed. Elapsed time is computed on demand
 * ([elapsedMillis]), so the timer survives the bubble being dismissed
 * manually and the Tail app being killed. When the trigger app leaves the
 * foreground, FloatingBubbleService stops the timer and records the session.
 * Device reboots are the only case where an in-flight timer is lost
 * (timestamps are wall-clock based).
 *
 * Stored in plain [SharedPreferences] (not DataStore) because the bubble
 * service reads/writes it synchronously from the UI thread when rendering
 * the timer menu.
 */
object WidgetTimerStore {

    private const val PREFS_NAME = "tail_widget_timers"
    private const val KEY_PREFIX = "timer_start_"
    private const val BANK_PREFIX = "timer_bank_"
    private const val MULTI_PREFIX = "multi_member_"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun key(habitName: String) = "$KEY_PREFIX$habitName"

    /** Epoch millis at which the habit's timer was started, or 0 if not running. */
    fun timerStartMillis(context: Context, habitName: String): Long =
        prefs(context).getLong(key(habitName), 0L)

    /** True if the habit's timer is currently running. */
    fun isTimerRunning(context: Context, habitName: String): Boolean =
        timerStartMillis(context, habitName) > 0L

    /** Starts (or restarts) the habit's timer at the current time. */
    fun startTimer(context: Context, habitName: String) {
        prefs(context).edit().putLong(key(habitName), System.currentTimeMillis()).apply()
    }

    /**
     * Elapsed time in millis for a running timer (0 if not running).
     * Does NOT stop the timer.
     */
    fun elapsedMillis(context: Context, habitName: String): Long {
        val start = timerStartMillis(context, habitName)
        if (start <= 0L) return 0L
        return System.currentTimeMillis() - start
    }

    /**
     * Stops the habit's timer and returns the elapsed time in whole minutes
     * (rounded to nearest). Returns 0 and clears nothing if the timer was
     * not running. Sub-minute elapsed time rounds down to 0 (< 30 s) or up
     * to 1 (>= 30 s).
     */
    fun stopTimerAndComputeMinutes(context: Context, habitName: String): Int {
        val start = timerStartMillis(context, habitName)
        val elapsed = elapsedMillis(context, habitName)
        prefs(context).edit().remove(key(habitName)).apply()
        journalDrillSpan(context, habitName, start, start + elapsed)
        if (elapsed <= 0L) return 0
        return Math.round(elapsed / 60000.0).toInt().coerceAtLeast(0)
    }

    // ── Drill-span journal (chess session keep-alive, 2026-09-25) ─────────
    //
    // Every START of a session-preserving habit's timer (puzzles, unrated
    // games, …) is (start → end) activity evidence for the rolling GREEN
    // window: the 15-minute idle clock re-anchors on it like on a played
    // game. Completed spans are journaled here on every stop/switch path;
    // RUNNING spans are derived on demand (start → now) by [drillSpans].

    private const val DRILL_JOURNAL = "drill_journal"
    private const val MAX_DRILL_SPANS = 120

    /** One completed drill span: habit name + (startMs, endMs). */
    private data class JournalSpan(val habit: String, val start: Long, val end: Long)

    /** Completed drill spans with their habit, oldest first (bounded). */
    private fun drillJournal(context: Context): List<JournalSpan> {
        val raw = prefs(context).getString(DRILL_JOURNAL, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val s = o.optLong("s", 0L)
                val e = o.optLong("e", 0L)
                val h = o.optString("h", "")
                if (s > 0L && e > s && h.isNotEmpty()) JournalSpan(h, s, e) else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Appends one completed span (bounded; runs must not crash the timer). */
    private fun journalDrillSpan(context: Context, habit: String, start: Long, end: Long) {
        if (habit.isBlank() || start <= 0L || end <= start) return
        try {
            val arr = org.json.JSONArray()
            val spans = (drillJournal(context) + JournalSpan(habit, start, end))
                .takeLast(MAX_DRILL_SPANS)
            spans.forEach { sp ->
                arr.put(org.json.JSONObject().put("h", sp.habit).put("s", sp.start).put("e", sp.end))
            }
            prefs(context).edit().putString(DRILL_JOURNAL, arr.toString()).apply()
        } catch (_: Exception) {
            // The journal is advisory evidence — never break the timer stop.
        }
    }

    /**
     * (startMs, endMs) spans of the given [habits]' drill activity finished
     * at/before [beforeMs], oldest first — journaled completed spans PLUS
     * any currently-running timer of those habits as a live (start → now)
     * span that began at/before [beforeMs]. Callers pass their own
     * evaluation instant; the rolling-window chain filters window
     * membership itself. Multi-group switch/stop paths journal their banks
     * separately — same evidence discipline as the games chain.
     */
    fun drillSpans(
        context: Context,
        habits: Set<String>,
        beforeMs: Long,
        nowMs: Long
    ): List<Pair<Long, Long>> {
        if (habits.isEmpty()) return emptyList()
        val spans = ArrayList<Pair<Long, Long>>()
        drillJournal(context)
            .filter { it.habit in habits && it.end <= beforeMs }
            .forEach { spans.add(it.start to it.end) }
        habits.forEach { habit ->
            val start = timerStartMillis(context, habit)
            if (start <= 0L || start > beforeMs) return@forEach
            val end = minOf(nowMs, System.currentTimeMillis())
            if (end > start) spans.add(start to end)
        }
        return spans.sortedBy { it.first }
    }

    /** Drops journal entries ending before [cutoffMs] (housekeeping on stop). */
    fun pruneDrillJournal(context: Context, cutoffMs: Long) {
        try {
            val kept = drillJournal(context).filter { it.end >= cutoffMs }
            val arr = org.json.JSONArray()
            kept.forEach { sp ->
                arr.put(org.json.JSONObject().put("h", sp.habit).put("s", sp.start).put("e", sp.end))
            }
            prefs(context).edit().putString(DRILL_JOURNAL, arr.toString()).apply()
        } catch (_: Exception) {
            // Advisory evidence — pruning must never crash.
        }
    }

    // ── Multi-timer group support ─────────────────────────────────────────
    //
    // A multi-timer session links every habit sharing a trigger app: starting
    // one arms the whole group, but exactly ONE member's clock runs at any
    // time. Switching members banks the outgoing member's elapsed time
    // (BANK_PREFIX) and restarts the incoming member's clock on top of its
    // own bank. The dedicated stop control records EVERY member's banked +
    // running time at once.

    /** Banked (switched-away) millis for [habit] — 0 outside a multi session. */
    fun bankMillis(context: Context, habitName: String): Long =
        prefs(context).getLong(BANK_PREFIX + habitName, 0L)

    /** Bank + current-elapsed millis (what the member has accumulated so far). */
    fun totalMillis(context: Context, habitName: String): Long =
        bankMillis(context, habitName) + elapsedMillis(context, habitName)

    /** True while [habit] is a member of a live multi-timer group. */
    fun isMultiMember(context: Context, habitName: String): Boolean =
        prefs(context).getBoolean(MULTI_PREFIX + habitName, false)

    /** All habits currently marked as members of a live multi-timer group. */
    fun multiMembers(context: Context): List<String> =
        prefs(context).all.keys
            .filter { it.startsWith(MULTI_PREFIX) }
            .map { it.removePrefix(MULTI_PREFIX) }

    /**
     * Arms a fresh multi-timer group for [habits] with [active]'s clock
     * running. Any stale state (leftover banks / running clocks) of the
     * members is cleared first so the group starts from zero.
     */
    fun startMultiGroup(context: Context, habits: List<String>, active: String) {
        val editor = prefs(context).edit()
        habits.forEach { habit ->
            editor.remove(key(habit)).remove(BANK_PREFIX + habit)
                .putBoolean(MULTI_PREFIX + habit, true)
        }
        editor.putLong(key(active), System.currentTimeMillis()).apply()
    }

    /**
     * Switches the running clock from [from] to [to] inside a multi group:
     * banks [from]'s elapsed time and starts [to]'s clock on top of its bank.
     */
    fun switchMultiActive(context: Context, from: String, to: String) {
        if (from == to) return
        val editor = prefs(context).edit()
        val fromStart = timerStartMillis(context, from)
        if (fromStart > 0L) {
            val now = System.currentTimeMillis()
            // The banked stretch is real drill activity: journal it so a
            // chess session stays alive across multi-group member switches.
            journalDrillSpan(context, from, fromStart, now)
            val banked = bankMillis(context, from) + (now - fromStart)
            editor.putLong(BANK_PREFIX + from, banked).remove(key(from))
        }
        editor.putLong(key(to), System.currentTimeMillis())
        editor.apply()
    }

    /**
     * Ends the multi-timer group: returns whole (rounded) minutes per habit
     * — banked time plus running time — and clears every member's state.
     * Habits under a (rounded) minute are included with 0 and are simply not
     * recorded by the caller.
     */
    fun stopMultiGroupAndComputeMinutes(context: Context): Map<String, Int> {
        val members = multiMembers(context)
        val result = mutableMapOf<String, Int>()
        val editor = prefs(context).edit()
        members.forEach { habit ->
            result[habit] = roundMillisToMinutes(totalMillis(context, habit))
            // Journal the member's final running stretch (banked stretches
            // were already journaled at switch time).
            val start = timerStartMillis(context, habit)
            if (start > 0L) journalDrillSpan(context, habit, start, System.currentTimeMillis())
            editor.remove(key(habit)).remove(BANK_PREFIX + habit).remove(MULTI_PREFIX + habit)
        }
        editor.apply()
        return result
    }

    /** Rounds a millis duration to whole minutes (>= 30 s rounds up). */
    fun roundMillisToMinutes(millis: Long): Int =
        Math.round(millis / 60000.0).toInt().coerceAtLeast(0)

    /** Formats an elapsed-millis value as h:mm:ss / m:ss for the live timer display. */
    fun formatElapsed(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
