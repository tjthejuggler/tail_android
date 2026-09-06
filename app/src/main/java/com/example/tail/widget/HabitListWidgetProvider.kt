package com.example.tail.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import com.example.tail.R
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "HabitListWidgetProvider"

/**
 * Lock-screen friendly habit list widget.
 *
 * STATES (per appWidgetId):
 *  - collapsed (default)            — transparent background; a small fingertip-sized
 *                                     outline button in the upper-right corner.
 *  - armed     (after 1st tap)      — button fills orange. A 1.5 s alarm auto-disarms
 *                                     back to collapsed if the user does nothing.
 *  - expanded  (after 2nd tap)      — shows a scrollable list of habits (≈ 8 visible).
 *                                     Auto-collapses when:
 *                                       • screen turns off
 *                                       • phone is unlocked (USER_PRESENT)
 *                                       • 60 s safety timer (user walked away)
 *                                     Each habit tap resets the 60 s safety timer.
 */
class HabitListWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_ARM             = "com.example.tail.widget.ARM"
        const val ACTION_DISARM          = "com.example.tail.widget.DISARM"
        const val ACTION_EXPAND          = "com.example.tail.widget.EXPAND"
        const val ACTION_COLLAPSE        = "com.example.tail.widget.COLLAPSE"
        const val ACTION_INCREMENT       = "com.example.tail.widget.INCREMENT"
        const val ACTION_REFRESH         = "com.example.tail.widget.REFRESH"

        const val EXTRA_HABIT_NAME       = "habit_name"

        /** How long an "armed" state stays before reverting on its own (ms). */
        const val ARM_TIMEOUT_MS         = 1500L

        /** Safety auto-collapse timeout — only fires if the user truly walked away.
         *  Long enough that scrolling is never interrupted. */
        const val EXPAND_SAFETY_TIMEOUT_MS = 60_000L

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, HabitListWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val intent = Intent(context, HabitListWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Receiver for screen-off and user-unlock. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_USER_PRESENT -> collapseAll(context)
            }
        }
    }

    private fun collapseAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, HabitListWidgetProvider::class.java)
        )
        for (id in ids) {
            scope.launch {
                val wasExpanded = WidgetPreferences.isExpanded(context, id)
                if (wasExpanded) {
                    WidgetPreferences.setExpanded(context, id, false)
                    cancelSafetyTimer(context, id)
                    renderWidget(context, mgr, id, armed = false)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // AppWidgetProvider lifecycle
    // ────────────────────────────────────────────────────────────────────────

    @Volatile
    private var receiverRegistered = false

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        registerScreenReceiver(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        unregisterScreenReceiver(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        registerScreenReceiver(context)
        // Delayed re-pushes: during the nav-mode rebind storm AIO applies
        // early updates to host views it then discards (slots stay stuck).
        // A repaint after the storm settles self-heals the slot.
        val appCtx = context.applicationContext
        val mgr = appWidgetManager
        for (delayMs in longArrayOf(3_000L, 8_000L, 16_000L)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    for (id in appWidgetIds) {
                        renderWidget(appCtx, mgr, id, armed = false)
                    }
                } catch (_: Exception) { /* best-effort */ }
            }, delayMs)
        }
        // SYNCHRONOUS static frame first: on a host restart (launcher
        // recovered after a configuration change — e.g. a Modes & Routines
        // nav-bar switch — or reboot) the host re-asks every provider and
        // paints whatever arrives inside the rebind window. renderWidget's
        // coroutine + DB read used to leave a window with no answer; a
        // launcher that drops unanswered slots (e.g. AIO Launcher) showed
        // the widget as vanished. The collapsed layout is pure static
        // construction (no DB), so it can be painted inside the broadcast;
        // the async render then upgrades it with the real state.
        for (id in appWidgetIds) {
            try {
                appWidgetManager.updateAppWidget(id, buildCollapsedViews(context, id, armed = false))
            } catch (_: Exception) { /* best-effort placeholder */ }
        }
        for (id in appWidgetIds) {
            renderWidget(context, appWidgetManager, id, armed = false)
        }
    }

    /**
     * Host restarts (launcher recovers, configuration change, reboot)
     * re-ask every provider for content; hosts paint whatever arrives
     * within the rebind window and drop what doesn't. Both Tail renders
     * hop through a coroutine + DB read, so on a restart there was NO
     * synchronous answer at all — the widget slot stayed empty (observed
     * as "the widget vanishes when leaving the chess app", whose real
     * trigger was a Modes & Routines nav-bar switch that restarts the
     * launcher). Answering resize/rebind callbacks synchronously with the
     * static collapsed layout guarantees a valid RemoteViews inside the
     * broadcast; the async render still upgrades it with live data.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        renderWidget(context, appWidgetManager, appWidgetId, armed = false)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            scope.launch { WidgetPreferences.clear(context, id) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        val mgr = AppWidgetManager.getInstance(context)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        when (intent.action) {
            ACTION_ARM -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                renderWidget(context, mgr, widgetId, armed = true)
                scheduleAutoDisarm(context, widgetId)
            }

            ACTION_DISARM -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                renderWidget(context, mgr, widgetId, armed = false)
            }

            ACTION_EXPAND -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                cancelAutoDisarm(context, widgetId)
                scope.launch {
                    WidgetPreferences.setExpanded(context, widgetId, true)
                    renderWidget(context, mgr, widgetId, armed = false)
                    scheduleSafetyTimer(context, widgetId)
                }
            }

            ACTION_COLLAPSE -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                scope.launch {
                    WidgetPreferences.setExpanded(context, widgetId, false)
                    cancelSafetyTimer(context, widgetId)
                    renderWidget(context, mgr, widgetId, armed = false)
                }
            }

            ACTION_INCREMENT -> {
                val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: return
                handleIncrementTap(context, widgetId, habitName)
            }

            ACTION_REFRESH -> {
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: intArrayOf(widgetId).filter { it != AppWidgetManager.INVALID_APPWIDGET_ID }.toIntArray()
                for (id in ids) {
                    renderWidget(context, mgr, id, armed = false)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Screen receiver registration
    // ────────────────────────────────────────────────────────────────────────

    private fun registerScreenReceiver(context: Context) {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(screenReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen receiver: ${e.message}")
        }
    }

    private fun unregisterScreenReceiver(context: Context) {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) { }
        receiverRegistered = false
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rendering
    // ────────────────────────────────────────────────────────────────────────

    private fun renderWidget(
        context: Context,
        mgr: AppWidgetManager,
        widgetId: Int,
        armed: Boolean
    ) {
        scope.launch {
            try {
                val expanded = WidgetPreferences.isExpanded(context, widgetId)
                val rv = if (expanded) buildExpandedViews(context, widgetId)
                         else           buildCollapsedViews(context, widgetId, armed)
                mgr.updateAppWidget(widgetId, rv)
                if (expanded) {
                    mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_habit_list)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to render widget $widgetId: ${e.message}", e)
            }
        }
    }

    private fun buildCollapsedViews(
        context: Context,
        widgetId: Int,
        armed: Boolean
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_collapsed)

        if (armed) {
            rv.setInt(
                R.id.widget_collapsed_button,
                "setBackgroundResource",
                R.drawable.widget_button_armed_bg
            )
            rv.setOnClickPendingIntent(
                R.id.widget_collapsed_button,
                pendingBroadcast(context, ACTION_EXPAND, widgetId)
            )
        } else {
            rv.setInt(
                R.id.widget_collapsed_button,
                "setBackgroundResource",
                R.drawable.widget_button_idle_bg
            )
            rv.setOnClickPendingIntent(
                R.id.widget_collapsed_button,
                pendingBroadcast(context, ACTION_ARM, widgetId)
            )
        }
        return rv
    }

    private fun buildExpandedViews(context: Context, widgetId: Int): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_expanded)

        // Sticky "Add Note" bar — opens the note composer over the lock
        // screen; on confirm it prepends to the configured voice-note markdown.
        rv.setOnClickPendingIntent(
            R.id.widget_add_note_button,
            notePendingIntent(context, widgetId)
        )

        val intent = Intent(context, HabitListRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        rv.setRemoteAdapter(R.id.widget_habit_list, intent)
        rv.setEmptyView(R.id.widget_habit_list, R.id.widget_empty_label)

        val templateIntent = Intent(context, HabitListWidgetProvider::class.java).apply {
            action = ACTION_INCREMENT
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val templatePending = PendingIntent.getBroadcast(
            context,
            widgetId,
            templateIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        rv.setPendingIntentTemplate(R.id.widget_habit_list, templatePending)

        return rv
    }

    // ────────────────────────────────────────────────────────────────────────
    // Tap handling
    // ────────────────────────────────────────────────────────────────────────

    private fun handleIncrementTap(context: Context, widgetId: Int, habitName: String) {
        val appCtx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(context)

        scope.launch {
            try {
                val settings = SettingsRepository(appCtx).settingsFlow.first()
                val isTextInput = habitName in settings.textInputHabits
                val isMaxOne    = habitName in settings.maxOneHabits

                if (isTextInput) {
                    val launch = Intent(appCtx, WidgetInputActivity::class.java).apply {
                        action = WidgetInputActivity.ACTION_SHOW
                        putExtra(WidgetInputActivity.EXTRA_HABIT_NAME, habitName)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appCtx.startActivity(launch)
                    // Reset safety timer since user is actively interacting
                    scheduleSafetyTimer(appCtx, widgetId)
                    return@launch
                }

                val fileUri = settings.fileUri
                if (fileUri.isNotEmpty()) {
                    val habitsRepo = HabitsRepository()
                    val uri = Uri.parse(fileUri)

                    var didIncrement = false
                    if (isMaxOne) {
                        val db = habitsRepo.loadDatabase(uri, appCtx)
                        val todayStr = java.time.LocalDate.now().toString()
                        val cur = db[habitName]?.get(todayStr) ?: 0
                        if (cur < 1) {
                            habitsRepo.incrementHabit(uri, appCtx, habitName, 1)
                            HabitIncrementBus.emit(habitName)
                            didIncrement = true
                        }
                    } else {
                        habitsRepo.incrementHabit(uri, appCtx, habitName, 1)
                        HabitIncrementBus.emit(habitName)
                        didIncrement = true
                    }

                    if (didIncrement) {
                        com.example.tail.ui.HabitHaptics.confirmIncrement(appCtx)
                        try {
                            HabitTimestampRepository(appCtx).addTimestamp(habitName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                        }
                        // Announce the increment to same-keystore listeners (e.g. VILD
                        // reverse-syncs widget taps into its own day log).
                        com.example.tail.ipc.HabitIncrementAnnouncer.announce(appCtx, habitName, 1)
                    }
                } else {
                    Log.w(TAG, "No habits file URI configured — widget tap ignored for '$habitName'")
                }

                // Update widget-local ordering
                if (isMaxOne) {
                    WidgetPreferences.recordMax1Tap(appCtx, widgetId, habitName)
                } else {
                    WidgetPreferences.recordTap(appCtx, widgetId, habitName)
                }

                // Refresh the list and reset safety timer
                mgr.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_habit_list)
                scheduleSafetyTimer(appCtx, widgetId)
            } catch (e: Exception) {
                Log.e(TAG, "handleIncrementTap('$habitName') failed: ${e.message}", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Auto-disarm timer (1.5 s) — for the collapsed → armed → collapsed flow
    // ────────────────────────────────────────────────────────────────────────

    private fun scheduleAutoDisarm(context: Context, widgetId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = autoDisarmPendingIntent(context, widgetId)
        val triggerAt = SystemClock.elapsedRealtime() + ARM_TIMEOUT_MS
        try {
            am.setExact(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        }
    }

    private fun cancelAutoDisarm(context: Context, widgetId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(autoDisarmPendingIntent(context, widgetId))
    }

    private fun autoDisarmPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, HabitListWidgetProvider::class.java).apply {
            action = ACTION_DISARM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId * 10 + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Safety auto-collapse timer (60 s) — only fires if user walked away.
    // Each habit tap resets this timer. Scrolling does NOT reset it because
    // RemoteViews has no scroll listener; the 60 s window is long enough
    // that scrolling is never interrupted.
    // ────────────────────────────────────────────────────────────────────────

    private fun scheduleSafetyTimer(context: Context, widgetId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = safetyTimerPendingIntent(context, widgetId)
        val triggerAt = SystemClock.elapsedRealtime() + EXPAND_SAFETY_TIMEOUT_MS
        try {
            am.setExact(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.ELAPSED_REALTIME, triggerAt, pi)
        }
    }

    private fun cancelSafetyTimer(context: Context, widgetId: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(safetyTimerPendingIntent(context, widgetId))
    }

    private fun safetyTimerPendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, HabitListWidgetProvider::class.java).apply {
            action = ACTION_COLLAPSE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId * 10 + 2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun pendingBroadcast(
        context: Context,
        action: String,
        widgetId: Int
    ): PendingIntent {
        val intent = Intent(context, HabitListWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("tail-widget://$action/$widgetId")
        }
        return PendingIntent.getBroadcast(
            context,
            widgetId * 10 + action.hashCode().and(0x7),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Launches [WidgetNoteActivity] (note composer) from the expanded widget. */
    private fun notePendingIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WidgetNoteActivity::class.java).apply {
            action = WidgetNoteActivity.ACTION_SHOW
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse("tail-widget://note/$widgetId")
        }
        return PendingIntent.getActivity(
            context,
            widgetId * 10 + 3,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
