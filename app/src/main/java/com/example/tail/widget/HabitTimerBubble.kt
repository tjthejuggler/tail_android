package com.example.tail.widget

import android.content.Context
import android.content.Intent

/**
 * Starts the per-habit "Habit Timer" bubble for [habitName] (edit-mode
 * toggle "Habit Timer"). The bubble shows the habit's own icon, starts the
 * habit's timer immediately, stays visible over every app, and a tap on it
 * stops & records the timer and then opens the habit's input window (or the
 * increment is recorded as part of the stop for plain habits).
 */
fun startHabitTimerBubble(context: Context, habitName: String) {
    val intent = Intent(context, FloatingBubbleService::class.java)
        .setAction(FloatingBubbleService.ACTION_HABIT_TIMER)
        .putExtra(FloatingBubbleService.EXTRA_HABIT_NAME, habitName)
    context.startForegroundService(intent)
}
