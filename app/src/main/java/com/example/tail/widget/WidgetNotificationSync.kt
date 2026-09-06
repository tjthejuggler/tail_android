package com.example.tail.widget

import android.content.Context
import android.util.Log
import com.example.tail.data.NotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Process-wide bridge between the pending-notification store and the
 * home-screen widgets.
 *
 * [NotificationStore] is the single source of truth for habit asks, and the
 * tier-bar widget paints its badge count straight from that store at paint
 * time ([TierBarWidgetProvider.buildRenderViews]) — so the only thing needed
 * for a live badge is a repaint whenever the list changes. This watcher
 * collects the store's flow for the whole process lifetime and repaints BOTH
 * widgets on every change, in either direction:
 *
 *  · an ask is CREATED (scheduled alarm fires, movie-watch detection,
 *    quick-capture failure notice, …) → badge count goes UP;
 *  · an ask is ANSWERED/READ anywhere — in-app notification center,
 *    system-notification Yes/No action, the one-time bottom flash — which
 *    removes the record from the store → badge count goes DOWN.
 *
 * Every path mutates the same DataStore, so watching the store's flow covers
 * all of them without touching any call site.
 *
 * The initial emission is treated as a change too: it self-heals a badge left
 * stale while the process was dead (e.g. asks answered from the PC side).
 * Updates are debounced so a burst of adds/removes in one tick paints once.
 */
object WidgetNotificationSync {

    private const val TAG = "WidgetNotifSync"

    /** Coalescing window so bursts of changes repaint once, not per item. */
    private const val DEBOUNCE_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    /** Idempotent; call once from [com.example.tail.TailApplication.onCreate]. */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                NotificationStore(appContext).notificationsFlow
                    .distinctUntilChanged()
                    .collectLatest {
                        delay(DEBOUNCE_MS)
                        repaintWidgets(appContext)
                    }
            } catch (e: Exception) {
                Log.w(TAG, "notification watch ended: ${e.message}")
            }
        }
    }

    /** Repaints the habit-list widget and the tier-bar badge now. */
    private fun repaintWidgets(appContext: Context) {
        try {
            HabitListWidgetProvider.refreshAll(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "habit-list repaint failed: ${e.message}")
        }
        scope.launch {
            try {
                // Suspend: recomputes tiers from the DB, then pushes the
                // badge-bearing RemoteViews. No-op without placed widgets.
                TierBarWidgetProvider.refreshAll(appContext)
            } catch (e: Exception) {
                Log.w(TAG, "tier-bar repaint failed: ${e.message}")
            }
        }
    }
}
