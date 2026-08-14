package com.example.tail.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that monitors the foreground app and automatically
 * shows/hides the [FloatingBubbleService] based on per-habit "Use Widget"
 * trigger settings.
 *
 * The service polls [UsageStatsManager.queryEvents] every [POLL_INTERVAL_MS]
 * ms to detect which app is currently in the foreground (the package of the
 * most recent ACTIVITY_RESUMED event). When a watched package (one that a
 * habit has configured as its widget trigger app) comes to the foreground,
 * the floating bubble is started. When it leaves, the bubble is stopped.
 *
 * Requires the user to grant "Usage access" permission
 * ([Settings.ACTION_USAGE_ACCESS_SETTINGS]). Without the permission the
 * usage queries simply return empty results — the service runs harmlessly
 * and starts working as soon as the permission is granted.
 */
class WidgetTriggerService : Service() {

    companion object {
        private const val TAG = "WidgetTriggerService"
        private const val CHANNEL_ID = "tail_widget_trigger"
        private const val NOTIFICATION_ID = 9912

        /** How often (ms) to poll the foreground app. */
        private const val POLL_INTERVAL_MS = 2000L

        /**
         * How far back (ms) to look when querying usage events.
         * Must comfortably exceed the poll interval: apps sitting idle emit NO
         * new ACTIVITY_RESUMED events, so the window is how long we "remember"
         * the last foreground app. 60 s covers idle reading/lesson screens.
         */
        private const val EVENT_WINDOW_MS = 60_000L

        /** Action to tell the service to re-read its trigger-app configuration. */
        const val ACTION_REFRESH = "com.example.tail.widget.REFRESH_TRIGGERS"

        /**
         * Returns true if the user has granted "Usage access" permission.
         */
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            // unsafeCheckOpNoThrow requires API 29+; fall back to deprecated checkOpNoThrow
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED
        }

        /**
         * Convenience: starts the monitoring service if there are habits with
         * widget triggers configured, or stops it if there are none.
         *
         * The service is started regardless of usage-access state — without the
         * permission the usage queries return empty and the service simply does
         * nothing until the user grants it (no restart needed).
         */
        fun updateServiceState(context: Context, triggerAppCount: Int) {
            val intent = Intent(context, WidgetTriggerService::class.java)
            if (triggerAppCount > 0) {
                try {
                    context.startForegroundService(intent)
                    Log.d(TAG, "Monitor started ($triggerAppCount trigger app(s) configured)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start monitor service", e)
                }
            } else {
                context.stopService(intent)
                Log.d(TAG, "Monitor stopped (no trigger apps configured)")
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }

    /** Package names that should trigger the bubble. */
    private var watchedPackages: Set<String> = emptySet()

    /** Reverse of the trigger-app setting: package → habit name (for the timer menu). */
    private var habitByPackage: Map<String, String> = emptyMap()

    /** The package that is currently in the foreground (or null). */
    private var currentForegroundPackage: String? = null

    /** Whether the bubble is currently shown. */
    private var bubbleActive = false

    /** Whether the polling loop is currently scheduled. */
    private var isPolling = false

    // ──────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        loadWatchedPackages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_REFRESH -> loadWatchedPackages()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        stopPolling()
        // Ensure the bubble is removed when the service stops
        stopBubble()
        serviceScope.cancel()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Configuration loading
    // ──────────────────────────────────────────────────────────────────────

    private fun loadWatchedPackages() {
        serviceScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            val newByPackage = settings.widgetTriggerApps.entries
                .filter { it.value.isNotBlank() }
                .associate { (habit, pkg) -> pkg to habit }
            val newPackages = newByPackage.keys

            watchedPackages = newPackages
            habitByPackage = newByPackage
            Log.d(TAG, "Watched packages loaded: $newPackages")

            if (newPackages.isEmpty()) {
                // No trigger apps configured — stop everything
                stopBubble()
                stopPolling()
            } else {
                startPolling()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Foreground-app polling
    // ──────────────────────────────────────────────────────────────────────

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                checkForegroundApp()
            } catch (e: Exception) {
                Log.e(TAG, "Poll error", e)
            }
            if (isPolling) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        Log.d(TAG, "Polling started")
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        isPolling = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun checkForegroundApp() {
        if (watchedPackages.isEmpty()) return

        val foregroundPkg = getForegroundPackage()

        // No ACTIVITY_RESUMED events in the window — the foreground app is
        // UNCHANGED (an app sitting idle emits no events; the last resume is
        // simply older than the window). Keep the previous state; otherwise a
        // still-open trigger app would be "forgotten" and the bubble hidden.
        if (foregroundPkg == null) return

        // Ignore our own app coming to the foreground
        if (foregroundPkg == packageName) {
            if (bubbleActive) {
                Log.d(TAG, "Tail is foreground — hiding bubble")
                stopBubble()
            }
            currentForegroundPackage = null
            return
        }

        if (foregroundPkg != currentForegroundPackage) {
            Log.d(TAG, "Foreground changed: $currentForegroundPackage → $foregroundPkg")
            currentForegroundPackage = foregroundPkg

            if (foregroundPkg in watchedPackages) {
                if (!bubbleActive) {
                    Log.d(TAG, "Trigger app detected ($foregroundPkg) — showing bubble")
                    startBubble(habitByPackage[foregroundPkg])
                }
            } else {
                if (bubbleActive) {
                    Log.d(TAG, "Trigger app left — hiding bubble")
                    stopBubble()
                }
            }
        }
    }

    /**
     * Queries [UsageStatsManager] for the package name of the app currently
     * in the foreground, using the event stream (more reliable than
     * queryUsageStats for real-time detection, especially on Samsung One UI).
     *
     * Walks the events in chronological order; the package of the most recent
     * ACTIVITY_RESUMED (MOVE_TO_FOREGROUND) event is the foreground app.
     * Returns null if no events are available (e.g. permission not granted
     * yet, or no activity in the window).
     */
    private fun getForegroundPackage(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - EVENT_WINDOW_MS, now)

        val event = UsageEvents.Event()
        var lastForegroundPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastForegroundPackage = event.packageName
            }
        }
        return lastForegroundPackage
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Bubble start / stop
    // ──────────────────────────────────────────────────────────────────────

    private fun startBubble(triggerHabit: String?) {
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            triggerHabit?.let { putExtra(FloatingBubbleService.EXTRA_HABIT_NAME, it) }
        }
        startForegroundService(intent)
        bubbleActive = true
    }

    private fun stopBubble() {
        if (!bubbleActive) return
        val intent = Intent(this, FloatingBubbleService::class.java)
            .apply { action = FloatingBubbleService.ACTION_STOP_BUBBLE }
        startService(intent)
        bubbleActive = false
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Notification (required for foreground service)
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Widget Trigger Monitor",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Monitors for trigger apps to show/hide the floating bubble"
            setShowBadge(false)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tail Widget Monitor")
            .setContentText("Monitoring for trigger apps")
            .setSmallIcon(com.example.tail.R.drawable.ic_bubble_notification)
            .setOngoing(true)
            .build()
    }
}
