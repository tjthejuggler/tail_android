package com.example.tail.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.example.tail.R

/**
 * Lightweight activity that shows a habit increment or note confirmation message
 * over the lock screen, then fades out and auto-dismisses.
 *
 * Uses showWhenLocked + turnScreenOn to appear on top of the lock screen
 * even when the screen is off — the same mechanism alarm clocks use.
 *
 * @property EXTRA_CONFIRM_MSG The primary message (habit names or "Note saved")
 * @property EXTRA_NOTE_BODY Optional note body text (shown below the title for notes)
 * @property EXTRA_IS_NOTE If true, uses longer display duration
 */
class HabitIncrementConfirmActivity : Activity() {

    companion object {
        const val EXTRA_CONFIRM_MSG = "confirm_msg"
        const val EXTRA_NOTE_BODY = "note_body"
        const val EXTRA_IS_NOTE = "is_note"

        /** Display duration before fade-out begins */
        private const val HABIT_VISIBLE_MS = 1200L
        private const val NOTE_VISIBLE_MS = 2500L

        /** Fade-out animation duration */
        private const val FADE_OUT_MS = 600L
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
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_habit_increment_confirm)

        val msg = intent.getStringExtra(EXTRA_CONFIRM_MSG) ?: ""
        val noteBody = intent.getStringExtra(EXTRA_NOTE_BODY)
        val isNote = intent.getBooleanExtra(EXTRA_IS_NOTE, false)

        val titleView = findViewById<TextView>(R.id.confirm_text)
        val noteBodyView = findViewById<TextView>(R.id.note_body_text)

        titleView.text = if (isNote) "📝 $msg" else "✓ $msg"

        if (!noteBody.isNullOrEmpty()) {
            noteBodyView.text = noteBody
            noteBodyView.visibility = View.VISIBLE
        }

        // Schedule fade-out after visible duration
        val visibleMs = if (isNote) NOTE_VISIBLE_MS else HABIT_VISIBLE_MS
        handler.postDelayed({ fadeOutAndFinish() }, visibleMs)
    }

    /** Animate the root view to alpha=0, then finish the activity. */
    private fun fadeOutAndFinish() {
        val root = findViewById<FrameLayout>(R.id.confirm_root)
        root.animate()
            .alpha(0f)
            .setDuration(FADE_OUT_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction { finish() }
            .start()
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
