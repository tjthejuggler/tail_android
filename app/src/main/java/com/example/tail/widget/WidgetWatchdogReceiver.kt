package com.example.tail.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.data.PcEventQueueProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
  * It also drains the PC-widget event queue while the heartbeat is still in
  * its warmup (fast) phase, so habit events queued by the PC bubble widget
  * land on the phone quickly right after the process (re)starts — the
  * moment when queued events are most likely waiting. In steady state the
  * drain is skipped: the bubble service's long-poll (~1 s pickup) and the
  * app-foreground drain cover freshness, and a 2-minute network GET cadence
  * was measured as the app's #1 battery consumer (alarm wakelock firing
  * every ~3 min, 2026-11-19 battery report).
 *
 * The trigger service's poll then re-shows the bubble over the watched app
 * (its first poll looks back 15 minutes, so a chess session already in
 * progress is picked up without the user having to leave and re-enter the
 * app).
 */
class WidgetWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        if (!processBeatDone) {
            // First heartbeat in this process: the alarm fired while the
            // process was dead (kill, install, OEM cleanup) — the exact
            // scenario the watchdog exists for. Restart the warmup phase
            // so recovery beats run fast; reviveServices() below acts
            // immediately on THIS beat regardless of the counter.
            processBeatDone = true
            resetHeartbeatCount(appContext)
        }
        bumpHeartbeatCount(appContext)
        val beats = heartbeatCount(appContext)
        val warmup = beats <= WARMUP_BEATS
        drainPcEventQueue(appContext, warmup)
        val shouldRun = monitorShouldRun(appContext)
        val overlayShouldRun = StatsOverlayStore.shouldRun(appContext)
        if (!shouldRun && !overlayShouldRun) {
            // Both features deliberately off — let the heartbeat die out.
        } else {
            reviveServices(appContext, shouldRun, overlayShouldRun)
            schedule(appContext)
        }
    }

    /**
     * PC-event queue drain — warmup heartbeats only. The bubble service's
     * long-poll picks events up ~1 s after a PC timer stops while it runs,
     * and the app drains the queue on every foregrounding; the heartbeat
     * drain only matters in the window right after the process (re)starts
     * (process killed, bubble dismissed, app closed), so it runs while the
     * heartbeat is in its fast warmup phase and stops once the heartbeat
     * settles into the slow steady-state cadence. processOnce() returns
     * early when no bridge is configured. goAsync() keeps the process
     * alive for the IO; finish() releases it.
     */
    private fun drainPcEventQueue(appContext: Context, warmup: Boolean) {
        if (!warmup) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                PcEventQueueProcessor(appContext).processOnce()
            } catch (e: Exception) {
                Log.d(TAG, "PC event drain failed: ${e.message}")
            } finally {
                pending.finish()
            }
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

        /** True once this process has handled a heartbeat (process-death detector). */
        @Volatile
        private var processBeatDone = false

        /** Persistent count of heartbeats since the watchdog was (re)armed. */
        private const val KEY_HEARTBEATS = "heartbeats_since_arm"

        /**
         * Warmup heartbeat interval (ms). The first beats after (re)arming
         * run at this fast cadence: a kill is most likely to be
         * user-visible right after the service starts (install, crash,
         * OEM cleanup), so recovery stays quick there.
         */
        private const val WARMUP_INTERVAL_MS = 2L * 60 * 1000

        /**
         * Steady-state heartbeat interval (ms). After [WARMUP_BEATS]
         * warmup beats the heartbeat slows to this — the watchdog's only
         * job now is noticing a dead service "eventually"; the previous
         * 2-minute-forever cadence (~720 alarm dispatches/day, each a
         * process spin-up + settings read + network GET) made Tail the
         * #1 battery consumer (2026-11-19 battery report). 15 min cuts
         * dispatches ~7-fold (~96/day) and removes the per-beat network
         * GET entirely. Trade-off: PC events queued while the app is
         * fully closed land within ~15 min instead of ~2 — they are
         * durably queued on the bridge and picked up instantly on the
         * next app open / trigger-app foregrounding.
         */
        private const val STEADY_INTERVAL_MS = 15L * 60 * 1000

        /**
         * How many warmup beats before settling into the slow cadence.
         * The counter is PERSISTED so a long-lived process stays slow even
         * across many beats; it is reset only when a heartbeat fires in a
         * freshly spawned process (see onReceive) — i.e. right after the
         * process actually died, the one moment fast beats matter. Worst
         * case (process dying before every beat) this degrades to the old
         * 2-min cadence, never worse.
         */
        private const val WARMUP_BEATS = 3

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

        private fun heartbeatCount(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_HEARTBEATS, 0)

        private fun bumpHeartbeatCount(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HEARTBEATS, heartbeatCount(context) + 1)
                .apply()
        }

        private fun resetHeartbeatCount(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_HEARTBEATS).apply()
        }

        /**
         * (Re-)arms the heartbeat alarm. Beats [WARMUP_BEATS] fire at the
         * warmup cadence, then the alarm settles at the steady cadence.
         */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val beats = heartbeatCount(context)
            val interval = if (beats < WARMUP_BEATS) WARMUP_INTERVAL_MS else STEADY_INTERVAL_MS
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC,
                    System.currentTimeMillis() + interval,
                    pendingIntent(context)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule watchdog alarm", e)
            }
        }

        /** Cancels the heartbeat (used when the feature is turned off). */
        fun cancel(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_HEARTBEATS).apply()
            try {
                am.cancel(pendingIntent(context))
            } catch (e: Exception) { /* nothing scheduled */ }
        }
    }
}
