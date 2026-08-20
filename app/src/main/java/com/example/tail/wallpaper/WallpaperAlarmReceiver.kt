package com.example.tail.wallpaper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "WallpaperAlarmReceiver"

/**
 * Daily alarm for the points-driven wallpaper.
 *
 * Fires shortly after midnight (00:05) so the wallpaper resets to the new
 * day's points, then re-schedules itself for the next day. BOOT_COMPLETED
 * re-registers the alarm — inexact allow-while-idle alarms do not survive
 * a reboot on their own.
 *
 * Intraday point changes are handled separately by the post-save hook in
 * [WallpaperRefresher.onDatabaseSaved]; this alarm only guarantees the
 * day-rollover refresh even when the app stays closed.
 */
class WallpaperAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.example.tail.WALLPAPER_FIRE"

        /** Daily refresh time — just past midnight so TODAY starts at 0. */
        private val FIRE_TIME = LocalTime.of(0, 5)

        private fun firePendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
            }
            return PendingIntent.getBroadcast(
                context,
                "wallpaper-daily".hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Schedules the next daily refresh — but only when the wallpaper
         * feature is enabled. Safe to call repeatedly; replaces any
         * previous alarm.
         */
        fun scheduleNext(context: Context) {
            val appContext = context.applicationContext
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val settings = SettingsRepository(appContext).settingsFlow.first()
                    if (!settings.wallpaperEnabled) return@launch
                    val now = java.time.LocalDateTime.now()
                    var next = LocalDate.now().atTime(FIRE_TIME)
                    if (!next.isAfter(now)) next = next.plusDays(1)
                    val am = appContext.getSystemService(AlarmManager::class.java) ?: return@launch
                    // Inexact is fine for a wallpaper refresh — allow-while-idle
                    // still fires it in Doze, a few minutes' drift is harmless.
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), firePendingIntent(appContext))
                    Log.i(TAG, "Wallpaper refresh scheduled for $next")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to schedule wallpaper alarm: ${e.message}", e)
                }
            }
        }

        /** Cancels the daily wallpaper alarm (feature disabled). */
        fun cancel(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(firePendingIntent(context))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> scheduleNext(appContext)
            ACTION_FIRE -> {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        val status = WallpaperRefresher.refresh(appContext, force = true)
                        Log.i(TAG, "Daily wallpaper refresh: ${status.ifEmpty { "no change needed" }}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Daily wallpaper refresh failed: ${e.message}", e)
                    } finally {
                        // Always keep the daily cadence going (no-ops when disabled).
                        scheduleNext(appContext)
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
