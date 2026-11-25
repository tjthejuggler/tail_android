package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.WorkManager

/**
 * Single source of truth for Tail's background-work policy in the
 * launcher era (2026-11-25).
 *
 * Tail is the HOME app: it is brought to the front dozens of times a day,
 * so every "keep the cache warm while the app is closed" schedule was pure
 * battery waste — the same refresh already runs on ON_START / ON_RESUME
 * (see [com.example.tail.MainActivity]'s lifecycle observer), which for a
 * launcher is a far more frequent and better-timed trigger than any period.
 *
 * What is deliberately allowed to run while Tail is hidden:
 *  - [PhoneUsageWorker] (4 h, LOCAL only — no radio). UsageStats retains
 *    ~7 days of events; without a periodic run, any day Tail isn't opened
 *    in time is lost for good.
 *  - [HabitAlarmReceiver] / [AppStatsAlarmReceiver] — the user-scheduled
 *    habit asks and record notices. Notifications ARE the feature.
 *  - WidgetTriggerService + WidgetWatchdogReceiver — must run while OTHER
 *    apps are in the foreground; cannot be gated on Tail's own foreground.
 *
 * What was retired and is cancelled here on every launch so upgraded
 * installs drop the stale schedules that older builds persisted in
 * WorkManager's database (same technique TailCueSyncWorker already uses
 * for its own legacy movie poller):
 *  - TailCue + movie hourly pass   → now onResume (TailCueSyncWorker.syncNow)
 *  - Garmin 2 h cache pass         → now onAppForegrounded (syncGarminCurrentMonth)
 *  - Environment daily self-heal   → now onAppForegrounded (env catch-up sweep)
 */
object BackgroundWorkPolicy {

    private const val TAG = "BgWorkPolicy"

    /** Legacy periodic names — must match the constants in the workers. */
    private const val TAILCUE_PERIODIC = "tailcue_cue_sync"
    private const val GARMIN_PERIODIC = "garmin_metrics_sync"
    private const val ENV_PERIODIC = "environment_backlog_daily"

    /**
     * Cancels every periodic schedule this policy retired. Idempotent and
     * cheap (a WorkManager table delete); call once per Activity create.
     *
     * NOTE: the one-shot names (tailcue_cue_sync_now, environment_backlog)
     * are intentionally untouched — those are user/foreground-initiated.
     */
    fun retirePeriodicSync(context: Context) {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(TAILCUE_PERIODIC)
            wm.cancelUniqueWork(GARMIN_PERIODIC)
            wm.cancelUniqueWork(ENV_PERIODIC)
            Log.i(TAG, "Periodic sync schedules retired (foreground-only policy)")
        } catch (e: Exception) {
            // WorkManager not initialised (shouldn't happen) — nothing to do.
            Log.w(TAG, "Could not retire periodic work: ${e.message}")
        }
    }
}
