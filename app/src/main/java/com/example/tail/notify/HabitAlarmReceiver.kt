package com.example.tail.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.data.HabitSchedule
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "HabitAlarmReceiver"

/**
 * Daily alarm for the scheduled habit asks ("ask me about floss every night
 * at 22:00").
 *
 * When the alarm fires, the ask is created via [HabitAsks.fireScheduledAsk]
 * (store record + system notification) and the next day's alarm is scheduled.
 * BOOT_COMPLETED re-registers all alarms from the saved schedule settings —
 * exact alarms do not survive a reboot on their own.
 */
class HabitAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.tail.NOTIF_FIRE"
        const val EXTRA_HABIT = "habit"
        const val EXTRA_TIME = "time"

        private fun firePendingIntent(context: Context, habit: String): PendingIntent {
            val intent = Intent(context, HabitAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_HABIT, habit)
            }
            return PendingIntent.getBroadcast(
                context,
                ("habitask:$habit").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Schedules the next occurrence of [habit]'s daily ask at "HH:mm"
         * [time]. Replaces any previous alarm for the habit. Malformed times
         * are ignored.
         */
        fun schedule(context: Context, habit: String, time: String) {
            val next = HabitSchedule.nextOccurrenceMillis(time, System.currentTimeMillis()) ?: run {
                Log.w(TAG, "Ignoring malformed schedule time '$time' for '$habit'")
                return
            }
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = firePendingIntent(context, habit)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            } catch (e: SecurityException) {
                // No exact-alarm grant — an inexact alarm still fires, just
                // possibly batched by a few minutes.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            }
        }

        /** Cancels [habit]'s scheduled ask alarm (when the schedule is removed). */
        fun cancel(context: Context, habit: String) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(firePendingIntent(context, habit))
        }

        /** Re-registers alarms for every habit that has a schedule time. */
        fun rescheduleAll(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val settings = SettingsRepository(appContext).settingsFlow.first()
                    settings.habitScheduleTimes.forEach { (habit, time) ->
                        schedule(appContext, habit, time)
                    }
                    if (settings.habitScheduleTimes.isNotEmpty()) {
                        Log.i(TAG, "Rescheduled ${settings.habitScheduleTimes.size} habit ask alarm(s)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reschedule habit alarms: ${e.message}", e)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> rescheduleAll(appContext)
            ACTION_FIRE -> {
                val habit = intent.getStringExtra(EXTRA_HABIT) ?: return
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        HabitAsks.fireScheduledAsk(appContext, habit)
                        // Always schedule the next day's occurrence.
                        val time = SettingsRepository(appContext).settingsFlow.first()
                            .habitScheduleTimes[habit]
                        if (time != null) {
                            schedule(appContext, habit, time)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to fire scheduled ask for '$habit': ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
