package com.example.tail.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import android.widget.TextView
import com.example.tail.R

/**
 * Lightweight activity that shows a habit increment confirmation message
 * over the lock screen, then auto-dismisses after 3 seconds.
 *
 * Uses showWhenLocked + turnScreenOn to appear on top of the lock screen
 * even when the screen is off — the same mechanism alarm clocks use.
 */
class HabitIncrementConfirmActivity : Activity() {

    companion object {
        const val EXTRA_CONFIRM_MSG = "confirm_msg"
        private const val AUTO_DISMISS_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over lock screen and wake the screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // Acquire a screen bright wake lock to ensure the screen turns on
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "tail:HabitIncrementConfirm"
        ).apply {
            acquire(5000L)
        }

        // Add window flags for lock screen visibility
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContentView(R.layout.activity_habit_increment_confirm)

        val msg = intent.getStringExtra(EXTRA_CONFIRM_MSG) ?: ""
        val textView = findViewById<TextView>(R.id.confirm_text)
        textView.text = "✓ $msg"

        // Auto-dismiss after 3 seconds
        handler.postDelayed({ finish() }, AUTO_DISMISS_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }
}
