package com.example.tail.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Tiny haptic confirmation for habit increments.
 *
 * Plays a distinctive "tick-tock" double pulse — a soft short tick, a brief
 * gap, then a slightly firmer tap — so it feels different from the generic
 * single long buzz most apps use. Total motor-on time is ~44 ms, which is
 * a few millijoules per increment and has no measurable battery impact.
 */
object HabitHaptics {

    private const val TAG = "HabitHaptics"

    // (delay, on, gap, on) in ms — tiny tick, short pause, firmer tap
    private val TIMINGS = longArrayOf(0, 16, 60, 28)
    private val AMPLITUDES = intArrayOf(0, 48, 0, 110)

    /** Fire-and-forget haptic; never throws, silently no-ops without a vibrator. */
    fun confirmIncrement(context: Context) {
        try {
            val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator ?: return
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            }
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(VibrationEffect.createWaveform(TIMINGS, AMPLITUDES, -1))
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback skipped: ${e.message}")
        }
    }
}
