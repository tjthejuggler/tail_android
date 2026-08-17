package com.example.tail.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Self-healing watchdog for the widget stack.
 *
 * Observed failure (2026-08-15): the tail process was killed mid-chess-
 * session ("stop com.example.tail due to installPackageLI" — an APK
 * install/update, and the same happens on crashes or aggressive OEM
 * cleanup). Install-time kills do NOT restart START_STICKY services, so
 * [WidgetTriggerService] and [FloatingBubbleService] stayed dead and the
 * bubble never returned until the user manually opened the app.
 *
 * This receiver closes that hole from two directions:
 *  - a recurring allow-while-idle heartbeat alarm (re-armed on every fire)
 *    that restarts [WidgetTriggerService] whenever it should be running
 *    but isn't;
 *  - a BOOT_COMPLETED hook so the monitor also returns after a reboot.
 *
 * The trigger service's poll then re-shows the bubble over the watched app
 * (its first poll looks back 15 minutes, so a chess session already in
 * progress is picked up without the user having to leave and re-enter the
 * app).
 */
class WidgetWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val shouldRun = monitorShouldRun(appContext)
        val overlayShouldRun = StatsOverlayStore.shouldRun(appContext)
        if (!shouldRun && !overlayShouldRun) {
            // Both features deliberately off — let the heartbeat die out.
        } else {
            reviveServices(appContext, shouldRun, overlayShouldRun)
            schedule(appContext)
        }
    }

    /** Best-effort revival of the monitor and/or the stats overlay. */
    private fun reviveServices(appContext: Context, monitor: Boolean, overlay: Boolean) {
        if (monitor) {
            try {
                if (!WidgetTriggerService.isRunning) {
                    Log.d(TAG, "Watchdog: monitor dead — restarting it")
                    appContext.startForegroundService(
                        Intent(appContext, WidgetTriggerService::class.java)
                    )
                }
            } catch (e: Exception) {
                // FGS start can be blocked while the app is cached; the next
                // heartbeat retries (and any user interaction lifts the block).
                Log.d(TAG, "Watchdog: monitor restart deferred — ${e.message}")
            }
        }

        if (overlay) {
            try {
                if (!StatsOverlayService.isRunning) {
                    Log.d(TAG, "Watchdog: stats overlay dead — restarting it")
                    appContext.startForegroundService(
                        Intent(appContext, StatsOverlayService::class.java)
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Watchdog: stats overlay restart deferred — ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "WidgetWatchdog"
        private const val PREFS = "tail_widget_watchdog"
        private const val KEY_SHOULD_RUN = "monitor_should_run"

        /** Heartbeat interval (ms). */
        private const val INTERVAL_MS = 2L * 60 * 1000

        /**
         * True while the monitor is supposed to exist. Set by the monitor
         * itself whenever it runs; cleared only on a deliberate stop (no
         * trigger apps configured) — never on crashes/kills, so the
         * watchdog keeps reviving it.
         */
        fun monitorShouldRun(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOULD_RUN, false)

        fun setMonitorShouldRun(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHOULD_RUN, value)
                .apply()
        }

        private fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, WidgetWatchdogReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /** (Re-)arms the heartbeat alarm. */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + INTERVAL_MS,
                    pendingIntent(context)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog alarm", e)
            }
        }

        /** Cancels the heartbeat (used when the feature is turned off). */
        fun cancel(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            try {
                am.cancel(pendingIntent(context))
            } catch (e: Exception) { /* nothing scheduled */ }
        }
    }
}
