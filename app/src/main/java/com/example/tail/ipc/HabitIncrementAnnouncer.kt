package com.example.tail.ipc

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.ui.ACTION_HABIT_INCREMENTED
import com.example.tail.ui.EXTRA_AMOUNT
import com.example.tail.ui.EXTRA_HABIT_NAME
import com.example.tail.ui.EXTRA_SOURCE

/**
 * Single place that announces "a habit was incremented" to same-keystore
 * listener apps (VILD, WAGS, …) via the [ACTION_HABIT_INCREMENTED] broadcast,
 * protected by the TAIL_INTEGRATION signature permission.
 *
 * Every increment path in Tail (main UI, home-screen widget, widget text
 * input, habit-ask notification answers, voice service, JugCoach sessions and
 * the external IPC receiver itself) goes through here so listeners see a
 * consistent event no matter where the increment originated.
 *
 * Extras carried:
 *  - [EXTRA_HABIT_NAME] — the habit that was incremented
 *  - [EXTRA_AMOUNT]     — the count delta actually applied (0 = no-op such as
 *                         a max-1 cap or a minutes-only adjustment; count-based
 *                         listeners should ignore those)
 *  - [EXTRA_SOURCE]     — the originating app's package name, present only
 *                         when the increment arrived via an external IPC
 *                         broadcast. Propagated so the originator can recognise
 *                         and ignore its own echo (prevents increment loops in
 *                         bidirectional integrations like VILD's).
 */
object HabitIncrementAnnouncer {

    private const val TAG = "HabitIncrementAnnouncer"
    private const val PERMISSION_TAIL_INTEGRATION = "com.example.tail.permission.TAIL_INTEGRATION"

    /**
     * Fire-and-forget announcement. Silently dropped when no receiver is
     * registered; never crashes the caller.
     */
    fun announce(context: Context, habitName: String, amount: Int = 1, source: String? = null) {
        try {
            val intent = Intent(ACTION_HABIT_INCREMENTED).apply {
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_AMOUNT, amount)
                if (!source.isNullOrBlank()) putExtra(EXTRA_SOURCE, source)
            }
            context.sendBroadcast(intent, PERMISSION_TAIL_INTEGRATION)
            Log.d(TAG, "Announced increment of '$habitName' by $amount (source=${source ?: "local"})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send habit-incremented broadcast: ${e.message}")
        }
    }
}
