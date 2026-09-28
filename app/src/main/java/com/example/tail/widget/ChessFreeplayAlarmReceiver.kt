package com.example.tail.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private const val TAG = "ChessFreeplayAlarm"

/**
 * Weekly alarm that makes the chess freeplay ticket EAGER instead of lazy.
 *
 * The ledger in [ChessFreeplayStore] accrues its weekly ticket lazily —
 * only when something opens the store — which meant a fresh Monday could
 * pass unnoticed until the user next opened the chess menu. This receiver
 * fires just after midnight at the START of each week (Monday 00:05, so
 * the date has definitively rolled over) and runs the exact same
 * settle + accrue sweep, so the ticket is banked the moment the week
 * begins and is already there whenever the user looks.
 *
 * Same discipline as [com.example.tail.wallpaper.WallpaperAlarmReceiver]:
 * inexact allow-while-idle (fires in Doze, a few minutes' drift is
 * harmless — no SCHEDULE_EXACT_ALARM permission needed), re-schedules
 * itself every fire, and BOOT_COMPLETED re-registers the alarm because
 * alarms do not survive a reboot.
 */
class ChessFreeplayAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.tail.CHESS_FREEPLAY_FIRE"

        /** Just past midnight so the new week's date has fully rolled over. */
        private val FIRE_TIME = LocalTime.of(0, 5)

        private fun firePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ChessFreeplayAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
            }
            return PendingIntent.getBroadcast(
                context,
                "chess-freeplay-weekly".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Schedules the next weekly accrual for the START of the next ISO
         * week (Monday 00:05 local). Safe to call repeatedly; replaces any
         * previous alarm. Never cancels itself — the freeplay feature has
         * no settings toggle, the ledger gates itself via MAX_STOCK.
         */
        fun scheduleNext(context: Context) {
            try {
                val now = LocalDateTime.now()
                var next = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(FIRE_TIME)
                if (!next.isAfter(now)) {
                    // It is already Monday past 00:05 (or later in the week) —
                    // this week's grant is handled by the fire path / lazy
                    // readers, aim at next Monday.
                    next = next.plusWeeks(1)
                }
                val am = context.getSystemService(AlarmManager::class.java) ?: return
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    firePendingIntent(context)
                )
                Log.i(TAG, "Weekly freeplay accrual scheduled for $next")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule freeplay alarm: ${e.message}", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> scheduleNext(appContext)
            ACTION_FIRE -> {
                try {
                    val balance = ChessFreeplayStore.accrueWeekly(appContext)
                    Log.i(TAG, "Weekly freeplay accrual ran — balance now $balance")
                } catch (e: Exception) {
                    Log.e(TAG, "Weekly freeplay accrual failed: ${e.message}", e)
                } finally {
                    scheduleNext(appContext)
                }
            }
        }
    }
}
