package com.example.tail.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * External refresh hook for automation apps (MacroDroid / Tasker).
 *
 * AIO Launcher shows a per-slot refresh button on OTHER widgets' cards but
 * not on Tail's (it's an AIO-side affordance we cannot attach from the
 * provider side). This receiver gives automation the equivalent super-
 * power: a single explicit broadcast that repaints BOTH Tail widgets
 * (habit list + tier bar) with fully-formed frames — no UI clicking, and
 * it works while the slot is still showing AIO's "Loading..." placeholder.
 *
 * MacroDroid setup:
 *   Trigger: whatever you like (e.g. "Application Launched"/"Activity
 *            Started" → com.chess, or Manual)
 *   Action:  Intent Broadcast
 *            · Package:  com.example.tail
 *            · Receiver: com.example.tail.widget.WidgetRefreshReceiver
 *            · Action:   com.example.tail.widget.EXTERNAL_REFRESH
 *   (or equivalently `adb shell am broadcast -a
 *    com.example.tail.widget.EXTERNAL_REFRESH -n
 *    com.example.tail/.widget.WidgetRefreshReceiver`)
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EXTERNAL_REFRESH = "com.example.tail.widget.EXTERNAL_REFRESH"
        private const val TAG = "WidgetRefreshReceiver"

        /** Min gap between accepted external refreshes (debounce). */
        private const val MIN_INTERVAL_MS = 1_500L

        @Volatile
        private var lastAcceptedAt = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXTERNAL_REFRESH) return
        val now = System.currentTimeMillis()
        // goAsync: the paints do settings/DB IO on the tier-bar path.
        val pending = goAsync()
        try {
            synchronized(this) {
                if (now - lastAcceptedAt < MIN_INTERVAL_MS) {
                    Log.d(TAG, "External refresh debounced")
                    pending.finish()
                    return
                }
                lastAcceptedAt = now
            }
            val appContext = context.applicationContext
            Log.i(TAG, "External widget refresh requested")
            HabitListWidgetProvider.refreshAll(appContext)
            val mgr = context.getSystemService(AppWidgetManager::class.java) ?: return
            val tierIds = mgr.getAppWidgetIds(
                android.content.ComponentName(appContext, TierBarWidgetProvider::class.java)
            )
            if (tierIds.isNotEmpty()) {
                appContext.sendBroadcast(
                    Intent(appContext, TierBarWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, tierIds)
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "External refresh failed: ${e.message}")
        } finally {
            pending.finish()
        }
    }
}
