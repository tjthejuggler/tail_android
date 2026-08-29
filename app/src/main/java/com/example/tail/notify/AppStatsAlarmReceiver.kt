package com.example.tail.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AppStatsAlarm"

/**
 * Daily alarms for the app-stats record notifications
 * ([AppStatsRecordNotifier.checkAndPost]).
 *
 * Morning (07:00): beginning-of-day check — posts the once-a-day
 * "records held (as of yesterday)" summary (guarded by a persisted day
 * marker, so missed alarms simply catch up on the next run).
 * Evening (20:30): the day's habits are mostly logged — near-record /
 * new-record notices.
 *
 * Both alarms re-arm after each fire and are rescheduled after each reboot
 * (exact alarms do not survive one). The app-open catch-up path in
 * HabitViewModel covers missed alarms.
 */
class AppStatsAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.tail.APPSTATS_FIRE"
        const val ACTION_MORNING_FIRE = "com.example.tail.APPSTATS_MORNING_FIRE"
        private const val FIRE_HOUR = 20
        private const val FIRE_MINUTE = 30
        private const val MORNING_HOUR = 7
        private const val MORNING_MINUTE = 0

        private fun firePendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, AppStatsAlarmReceiver::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Schedules the next daily alarm at [hour]:[minute] (replaces any previous one). */
        private fun scheduleDaily(context: Context, action: String, hour: Int, minute: Int) {
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(hour, minute).atZone(zone)
            if (!next.toInstant().isAfter(now.toInstant())) next = next.plusDays(1)
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), firePendingIntent(context, action))
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), firePendingIntent(context, action))
            }
        }

        /** Schedules both daily checks (morning summary + evening records). */
        fun schedule(context: Context) {
            scheduleDaily(context, ACTION_MORNING_FIRE, MORNING_HOUR, MORNING_MINUTE)
            scheduleDaily(context, ACTION_FIRE, FIRE_HOUR, FIRE_MINUTE)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> schedule(appContext)
            ACTION_FIRE, ACTION_MORNING_FIRE -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        AppStatsRecordNotifier.checkAndPost(appContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "App-stats record check failed: ${e.message}", e)
                    } finally {
                        schedule(appContext)
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
