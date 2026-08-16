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
 * trigger settings. It ALSO polls media sessions every tick to drive
 * automatic media listening-time tracking ([MediaPlaybackTracker]) for
 * habits with the "Media" type enabled (podcasts, Spotify, any audio app).
 *
 * The service polls [UsageStatsManager.queryEvents] every [POLL_INTERVAL_MS]
 * ms to detect which app is currently in the foreground (the package of the
 * most recent ACTIVITY_RESUMED event). When a watched package (one that a
 * habit has configured as its widget trigger app) comes to the foreground,
 * the floating bubble is started. When it leaves, the bubble is stopped and
 * any still-running habit timer is stopped and recorded.
 *
 * Media tracking is independent of the bubble: it runs whenever any habit
 * has a media app configured, and requires notification-listener access
 * (see [MediaPlaybackTracker]) rather than usage access.
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

        /**
         * Look-back used for the FIRST poll after the service starts: if the
         * monitor was just revived (watchdog / boot) while the user is deep
         * inside a watched app, the last ACTIVITY_RESUMED may be several
         * minutes old — 15 min ensures the bubble comes back without the
         * user having to leave and re-enter the app.
         */
        private const val FIRST_EVENT_WINDOW_MS = 15L * 60_000

        /**
         * True while this service is alive — checked by
         * [WidgetWatchdogReceiver] so a killed monitor is revived.
         */
        @Volatile
        var isRunning = false
            private set

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
         *
         * The start intent ALWAYS carries ACTION_REFRESH: for an ALREADY-RUNNING
         * service a plain start intent is a no-op, so without the refresh the
         * monitor would keep watching the stale package list from when it first
         * started and never notice newly added (or removed) trigger apps.
         */
        fun updateServiceState(context: Context, triggerAppCount: Int) {
            val intent = Intent(context, WidgetTriggerService::class.java).apply {
                action = ACTION_REFRESH
            }
            if (triggerAppCount > 0) {
                WidgetWatchdogReceiver.setMonitorShouldRun(context, true)
                WidgetWatchdogReceiver.schedule(context)
                try {
                    context.startForegroundService(intent)
                    Log.d(TAG, "Monitor started ($triggerAppCount trigger app(s) configured)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start monitor service", e)
                }
            } else {
                // Deliberate stop — stand the watchdog down too.
                WidgetWatchdogReceiver.setMonitorShouldRun(context, false)
                WidgetWatchdogReceiver.cancel(context)
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

    /** Reverse of the trigger-app setting: package → habit names
     *  (several habits can share one trigger app). */
    private var habitsByPackage: Map<String, List<String>> = emptyMap()

    /** Package of the Chess Readiness app (null when feature is off/unset). */
    private var chessReadinessPackage: String? = null

    /**
     * Reverse of the media-app setting: package → media habit names.
     * Habits here get AUTOMATIC listening-time tracking via
     * [MediaPlaybackTracker] (media-session playback detection), which is
     * polled alongside the foreground-app check. Independent of the bubble:
     * the bubble only appears over an app when it is a widget TRIGGER app,
     * but media minute tracking runs whenever a media app is configured.
     */
    @Volatile
    private var mediaHabitsByPackage: Map<String, List<String>> = emptyMap()

    /** The package that is currently in the foreground (or null). */
    private var currentForegroundPackage: String? = null

    /** Whether the bubble is currently shown. */
    private var bubbleActive = false

    /** Whether the polling loop is currently scheduled. */
    private var isPolling = false

    /** True until the first poll runs — the first query uses a wider event
     *  window so a freshly (re)started monitor picks up the app that is
     *  ALREADY in the foreground without waiting for a new resume event. */
    private var firstPoll = true

    // ──────────────────────────────────────────────────────────────────────
    //  Service lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service created")
        // Arm the watchdog so that if THIS service is ever killed without a
        // matching stop (process death, install-time kill, crash), the alarm
        // receiver brings it back within ~2 minutes.
        WidgetWatchdogReceiver.setMonitorShouldRun(this, true)
        WidgetWatchdogReceiver.schedule(this)
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
        isRunning = false
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
            // groupBy (not associate!) so habits sharing one trigger app are
            // ALL kept — associate would silently drop all but the last one.
            val newByPackage = settings.widgetTriggerApps.entries
                .filter { it.value.isNotBlank() }
                .groupBy({ it.value }, { it.key })
            val newChessPkg = if (settings.chessReadinessEnabled &&
                settings.chessReadinessApp.isNotBlank()
            ) settings.chessReadinessApp else null
            val newPackages = newByPackage.keys + listOfNotNull(newChessPkg)

            // Media habits: package → habits for automatic listening-time
            // tracking. Only entries whose habit still has the media type
            // enabled are honored (stale app entries are ignored).
            val newMediaByPackage = settings.mediaApps.entries
                .filter { it.value.isNotBlank() && it.key in settings.mediaHabits }
                .groupBy({ it.value }, { it.key })

            watchedPackages = newPackages
            habitsByPackage = newByPackage
            chessReadinessPackage = newChessPkg
            mediaHabitsByPackage = newMediaByPackage
            Log.d(
                TAG,
                "Watched packages loaded: $newPackages (chess readiness: $newChessPkg, " +
                    "media apps: ${newMediaByPackage.keys})"
            )

            if (newPackages.isEmpty() && newMediaByPackage.isEmpty()) {
                // No trigger apps and no media apps configured — stop everything
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
            // Automatic media listening-time tracking runs on the same tick
            // as the foreground check (off the main thread — it does file I/O
            // when a listening block finishes).
            val mediaMap = mediaHabitsByPackage
            if (mediaMap.isNotEmpty()) {
                serviceScope.launch {
                    try {
                        MediaPlaybackTracker.update(applicationContext, mediaMap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Media playback tracking error", e)
                    }
                }
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

        // ── Self-heal: revive a bubble that died unexpectedly ──────────
        // If we believe the bubble is up but its service is gone (process
        // kill, crash, overlay failure), bring it straight back — the
        // widget must never stay gone while a watched app is in use.
        if (bubbleActive && !FloatingBubbleService.isRunning) {
            if (FloatingBubbleService.stoppedByUser) {
                // The user (or this monitor) dismissed it deliberately.
                bubbleActive = false
            } else if (currentForegroundPackage in watchedPackages) {
                Log.d(TAG, "Bubble died unexpectedly — reviving")
                startBubble(
                    habitsByPackage[currentForegroundPackage].orEmpty(),
                    chessReadiness = currentForegroundPackage == chessReadinessPackage
                )
            } else {
                bubbleActive = false
            }
        }

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
                stopBubble(stopRunningTimer = true)
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
                    startBubble(
                        habitsByPackage[foregroundPkg].orEmpty(),
                        chessReadiness = foregroundPkg == chessReadinessPackage
                    )
                }
            } else {
                if (bubbleActive) {
                    Log.d(TAG, "Trigger app left — hiding bubble, stopping timer if running")
                    stopBubble(stopRunningTimer = true)
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
        // First poll after (re)start looks further back so an in-progress
        // chess session is detected immediately instead of being missed
        // because its resume event predates the normal short window.
        val windowMs = if (firstPoll) FIRST_EVENT_WINDOW_MS else EVENT_WINDOW_MS
        firstPoll = false
        val events = usageStatsManager.queryEvents(now - windowMs, now)

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

    private fun startBubble(triggerHabits: List<String>, chessReadiness: Boolean = false) {
        val intent = Intent(this, FloatingBubbleService::class.java).apply {
            if (triggerHabits.isNotEmpty()) {
                putStringArrayListExtra(FloatingBubbleService.EXTRA_HABIT_NAMES, ArrayList(triggerHabits))
            }
            putExtra(FloatingBubbleService.EXTRA_CHESS_READINESS, chessReadiness)
        }
        try {
            startForegroundService(intent)
            bubbleActive = true
        } catch (e: Exception) {
            // Blocked (e.g. background FGS restriction). Clear the cached
            // foreground package so the next poll re-evaluates and retries
            // instead of wedging.
            Log.e(TAG, "Failed to start bubble — will retry", e)
            bubbleActive = false
            currentForegroundPackage = null
        }
    }

    /**
     * Tells the bubble service to hide itself. When [stopRunningTimer] is
     * true (the trigger app left the foreground) a still-running habit timer
     * is stopped and recorded first; false (monitor shutdown / trigger apps
     * deconfigured) leaves any timer running so it can resume if the bubble
     * comes back.
     */
    private fun stopBubble(stopRunningTimer: Boolean = false) {
        if (!bubbleActive) return
        val stopAction = if (stopRunningTimer) {
            FloatingBubbleService.ACTION_TRIGGER_APP_LEFT
        } else {
            FloatingBubbleService.ACTION_STOP_BUBBLE
        }
        val intent = Intent(this, FloatingBubbleService::class.java)
            .apply { action = stopAction }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop bubble service", e)
        }
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
