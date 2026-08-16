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
