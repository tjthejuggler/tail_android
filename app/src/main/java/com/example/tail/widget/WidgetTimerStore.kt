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
        val elapsed = elapsedMillis(context, habitName)
        prefs(context).edit().remove(key(habitName)).apply()
        if (elapsed <= 0L) return 0
        return Math.round(elapsed / 60000.0).toInt().coerceAtLeast(0)
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
            val banked = bankMillis(context, from) + (System.currentTimeMillis() - fromStart)
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
