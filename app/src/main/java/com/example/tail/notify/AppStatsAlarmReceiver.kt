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
 * Daily alarm for the app-stats record notifications
 * ([AppStatsRecordNotifier.checkAndPost]) — the "close to a new record" /
 * "new all-time record" notices. Fires every evening (20:30) so the day's
 * habits are mostly logged, and again after each reboot (exact alarms do
 * not survive one). The app-open catch-up path in HabitViewModel covers
 * missed alarms.
 */
class AppStatsAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.tail.APPSTATS_FIRE"
        private const val FIRE_HOUR = 20
        private const val FIRE_MINUTE = 30

        private fun firePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, AppStatsAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
            }
            return PendingIntent.getBroadcast(
                context,
                "appstats_records".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Schedules the next daily 20:30 check (replaces any previous one). */
        fun schedule(context: Context) {
            val zone = java.time.ZoneId.systemDefault()
            val now = java.time.ZonedDateTime.now(zone)
            var next = now.toLocalDate().atTime(FIRE_HOUR, FIRE_MINUTE).atZone(zone)
            if (!next.toInstant().isAfter(now.toInstant())) next = next.plusDays(1)
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), firePendingIntent(context))
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), firePendingIntent(context))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> schedule(appContext)
            ACTION_FIRE -> {
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
