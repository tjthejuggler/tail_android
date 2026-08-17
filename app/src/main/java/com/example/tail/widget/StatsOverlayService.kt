package com.example.tail.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.tail.MainActivity
import com.example.tail.R
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.computeTaskerStats
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that shows a small always-on-top stats bar with the same
 * daily / weekly / monthly point totals that the Tasker relay file receives
 * (today / avg7 / avg30 — computed by the shared [computeTaskerStats]).
 *
 * This is the in-app replacement for the Tasker overlay: once confirmed
 * working, the external total_habits.txt dependency can be retired.
 *
 * The bar can be:
 *  - Dragged anywhere on the screen (touch and move)
 *  - Resized by dragging the ◢ corner handle — width and font size scale
 *    together, so "wider" also means "bigger text"
 *  - Long-pressed to open the Tail app
 *  - Hidden from its notification action (or the Settings master switch)
 *
 * Position/width/opacity persist across reboots via [StatsOverlayStore].
 * The service is START_STICKY and additionally revived by
 * [WidgetWatchdogReceiver] and MainActivity so the bar "always stays showing".
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW]
 * ("draw over other apps").
 */
class StatsOverlayService : Service() {

    companion object {
        private const val TAG = "StatsOverlay"
        private const val CHANNEL_ID = "tail_stats_overlay"
        private const val NOTIFICATION_ID = 9912

        /** Periodic refresh cadence — catches Syncthing desktop edits + midnight rollover. */
        private const val REFRESH_INTERVAL_MS = 60_000L

        /** Debounce for HabitIncrementBus-driven refreshes. */
        private const val BUS_DEBOUNCE_MS = 600L

        /** Action to hide the overlay from anywhere (e.g. notification action). */
        const val ACTION_STOP_OVERLAY = "com.example.tail.widget.STOP_STATS_OVERLAY"

        /**
         * Action sent by the Settings screen after geometry/opacity changes so
         * the running overlay re-reads its store and applies them live.
         */
        const val ACTION_SETTINGS_CHANGED = "com.example.tail.widget.STATS_OVERLAY_SETTINGS_CHANGED"

        /** True while this service is alive (for the watchdog / revive logic). */
        @Volatile
        var isRunning = false
            private set

        /** dp → px helper */
        private fun Int.dp(resources: Resources): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                this.toFloat(),
                resources.displayMetrics
            ).toInt()

        /** Starts the overlay service (no-op safe if already running). */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            try {
                context.startForegroundService(
                    Intent(context, StatsOverlayService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
            }
        }

        /** Deliberately stops the overlay service and clears the revive flag. */
        fun stop(context: Context) {
            StatsOverlayStore.setShouldRun(context, false)
            if (!isRunning) return
            try {
                context.startService(
                    Intent(context, StatsOverlayService::class.java)
                        .setAction(ACTION_STOP_OVERLAY)
                )
            } catch (e: Exception) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
        }

        /** Revives the overlay if the user enabled it but it died (app start, watchdog). */
        fun ensureRunning(context: Context) {
            if (isRunning) return
            if (!StatsOverlayStore.shouldRun(context)) return
            if (!Settings.canDrawOverlays(context)) return
            start(context)
        }
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null
    private var statsText: TextView? = null
    private lateinit var overlayParams: WindowManager.LayoutParams

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val habitsRepo by lazy { HabitsRepository() }

    /** Current geometry (loaded from the store, kept in sync while dragging/resizing). */
    private var geo = StatsOverlayStore.Geometry()

    // ── Refresh runnables ─────────────────────────────────────────────────
    private val refreshRunnable = Runnable { refreshStats() }
    private val busDebouncedRefresh = Runnable {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 0)
    }
    private val periodicTick = object : Runnable {
        override fun run() {
            refreshStats()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    // ── Long-press detection ──────────────────────────────────────────────
    private var longPressConsumed = false
    private val longPressRunnable = Runnable { onOverlayLongPressed() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        geo = StatsOverlayStore.loadGeometry(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                StatsOverlayStore.setShouldRun(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SETTINGS_CHANGED -> {
                applyStoredGeometry()
                return START_STICKY
            }
        }

        // Mark that the overlay should exist — the watchdog / app start revive
        // it after crashes, process kills and reboots until deliberately stopped.
        StatsOverlayStore.setShouldRun(this, true)

        if (overlayView == null) {
            showOverlay()
        }

        // Instant refresh on (re)start…
        refreshStats()
        // …bus-driven refreshes (habit changed anywhere in the process)…
        serviceScope.launch {
            HabitIncrementBus.events.collect {
                handler.removeCallbacks(busDebouncedRefresh)
                handler.postDelayed(busDebouncedRefresh, BUS_DEBOUNCE_MS)
            }
        }
        // …and the periodic heartbeat.
        handler.removeCallbacks(periodicTick)
        handler.postDelayed(periodicTick, REFRESH_INTERVAL_MS)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        serviceScope.cancel()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Overlay setup
    // ──────────────────────────────────────────────────────────────────────

    private fun showOverlay() {
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels

        // First-run default: top-center, 220dp wide.
        if (geo.x < 0 || geo.y < 0) {
            geo = geo.copy(
                x = (screenWidth / 2 - geo.widthDp.dp(resources) / 2),
                y = 48.dp(resources)
            )
        }

        overlayParams = WindowManager.LayoutParams(
            geo.widthDp.dp(resources),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                // Absolute screen coordinates: y=0 is the very top of the
                // screen, so the bar can sit over the status-bar area — the
                // same spot the Tasker overlay used to occupy.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geo.x
            y = geo.y
        }

        val root = FrameLayout(this).apply {
            background = barBackground()
        }

        val text = TextView(this).apply {
            this.text = "– – –"
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            val hPad = 12.dp(resources)
            val vPad = 7.dp(resources)
            setPadding(hPad, vPad, hPad + 16.dp(resources), vPad + 4.dp(resources))
            textSize = fontSpForWidth(geo.widthDp)
        }
        statsText = text

        val handle = TextView(this).apply {
            this.text = "◢"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 13f
            setOnTouchListener(ResizeTouchListener())
        }
        val handleLP = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = 2.dp(resources)
            bottomMargin = 0
        }

        root.addView(text, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        root.addView(handle, handleLP)
        root.setOnTouchListener(BarTouchListener())
        overlayView = root

        try {
            windowManager.addView(root, overlayParams)
        } catch (e: Exception) {
            // Permission revoked or similar — stop cleanly and don't let the
            // watchdog keep retrying every heartbeat.
            Log.w(TAG, "addView failed: ${e.message}")
            StatsOverlayStore.setShouldRun(this, false)
            stopSelf()
            return
        }
    }

    /** Rounded dark bar background whose alpha follows the opacity setting. */
    private fun barBackground(): GradientDrawable {
        val alpha = (geo.opacity * 255).toInt().coerceIn(40, 255)
        return GradientDrawable().apply {
            setColor(Color.argb(alpha, 0x16, 0x16, 0x16))
            cornerRadius = 14f * resources.displayMetrics.density
            setStroke(1, Color.argb(160, 0x33, 0x44, 0x55))
        }
    }

    /** Font size scales with the bar width so resizing also scales the text. */
    private fun fontSpForWidth(widthDp: Int): Float =
        (widthDp / 11f).coerceIn(8f, 42f)

    /**
     * Re-reads geometry/opacity from the store and applies it to the live
     * overlay (position clamp, width, font size, background alpha). Sent by
     * the Settings screen after opacity changes / geometry resets.
     */
    private fun applyStoredGeometry() {
        geo = StatsOverlayStore.loadGeometry(this)
        overlayView?.let { root ->
            val maxX = Resources.getSystem().displayMetrics.widthPixels -
                geo.widthDp.dp(resources)
            val maxY = Resources.getSystem().displayMetrics.heightPixels - root.height
            overlayParams.x = geo.x.coerceIn(0, maxX.coerceAtLeast(0))
            overlayParams.y = geo.y.coerceIn(0, maxY.coerceAtLeast(0))
            overlayParams.width = geo.widthDp.dp(resources)
            root.background = barBackground()
            statsText?.textSize = fontSpForWidth(geo.widthDp)
            updateLayout()
        }
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        overlayView = null
        statsText = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Touch handling — drag (bar) + resize (corner handle)
    // ──────────────────────────────────────────────────────────────────────

    private inner class BarTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false
        private val touchSlop = 10.dp(resources)
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = overlayParams.x
                    initialY = overlayParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    longPressConsumed = false
                    handler.postDelayed(longPressRunnable, longPressTimeout.toLong())
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        val maxX = Resources.getSystem().displayMetrics.widthPixels -
                            overlayParams.width
                        val maxY = Resources.getSystem().displayMetrics.heightPixels -
                            (overlayView?.height ?: 0)
                        overlayParams.x = (initialX + dx.toInt()).coerceIn(0, maxX.coerceAtLeast(0))
                        overlayParams.y = (initialY + dy.toInt()).coerceIn(0, maxY.coerceAtLeast(0))
                        updateLayout()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (longPressConsumed) {
                        longPressConsumed = false
                        return true
                    }
                    if (isDragging) {
                        geo = geo.copy(x = overlayParams.x, y = overlayParams.y)
                        StatsOverlayStore.saveGeometry(this@StatsOverlayService, geo)
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    longPressConsumed = false
                    if (isDragging) {
                        geo = geo.copy(x = overlayParams.x, y = overlayParams.y)
                        StatsOverlayStore.saveGeometry(this@StatsOverlayService, geo)
                    }
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startWidthDp = 0
        private var initialTouchX = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidthDp = geo.widthDp
                    initialTouchX = event.rawX
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val density = resources.displayMetrics.density
                    val dxDp = (event.rawX - initialTouchX) / density
                    val screenWdDp =
                        Resources.getSystem().displayMetrics.widthPixels / density
                    val newWidth = (startWidthDp + dxDp.toInt())
                        .coerceIn(120, screenWdDp.toInt())
                    if (newWidth != geo.widthDp) {
                        geo = geo.copy(widthDp = newWidth)
                        overlayParams.width = newWidth.dp(resources)
                        statsText?.textSize = fontSpForWidth(newWidth)
                        updateLayout()
                    }
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    StatsOverlayStore.saveGeometry(this@StatsOverlayService, geo)
                    return true
                }
            }
            return false
        }
    }

    private fun updateLayout() {
        try {
            windowManager.updateViewLayout(overlayView, overlayParams)
        } catch (e: Exception) { /* view removed */ }
    }

    private fun onOverlayLongPressed() {
        longPressConsumed = true
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) { /* activity unavailable */ }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Stats computation — SAME source as the Tasker relay file
    // ──────────────────────────────────────────────────────────────────────

    private fun refreshStats() {
        serviceScope.launch {
            try {
                val settings = settingsRepo.settingsFlow.first()
                if (settings.fileUri.isEmpty()) {
                    postStatsText("– – –")
                    return@launch
                }
                val db = habitsRepo.loadDatabase(
                    Uri.parse(settings.fileUri), this@StatsOverlayService
                )
                val stats = computeTaskerStats(
                    db = db,
                    dividers = settings.habitDividers,
                    noPointsHabits = settings.noPointsHabits,
                    secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits,
                    timerMinutesPrimaryHabits = settings.widgetTimerMinutesPrimary,
                    invertedBinaryHabits = settings.invertedBinaryHabits
                )
                postStatsText(
                    "${stats.today} ${formatNum(stats.avg7)} ${formatNum(stats.avg30)}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "refreshStats failed: ${e.message}")
            }
        }
    }

    /** Applies new stats text on the main thread (safe from any coroutine). */
    private fun postStatsText(text: String) {
        handler.post { statsText?.text = text }
    }

    /** Bare-number format: rounded to the nearest whole number ("44.4"→"44", "44.5"→"45"). */
    private fun formatNum(v: Double): String = Math.round(v).toString()

    // ──────────────────────────────────────────────────────────────────────
    //  Notification (required for foreground service)
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stats Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the Tail stats overlay bar is active"
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, StatsOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 1, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tail Stats Overlay")
            .setContentText("Drag to move · ◢ corner to resize · long-press to open Tail")
            .setSmallIcon(R.drawable.ic_bubble_notification)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Hide Overlay",
                    stopPendingIntent
                ).build()
            )
            .build()
    }
}

/**
 * Persists the stats overlay's geometry (position / width / opacity) and the
 * "should exist" flag in SharedPreferences — deliberately NOT DataStore, so
 * the service and the watchdog can read/write it synchronously (same pattern
 * as BubbleStateStore / the widget watchdog's monitor flag).
 */
object StatsOverlayStore {
    private const val PREFS = "tail_stats_overlay"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_WIDTH_DP = "width_dp"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_SHOULD_RUN = "should_run"

    const val DEFAULT_WIDTH_DP = 220
    const val DEFAULT_OPACITY = 0.80f

    data class Geometry(
        val x: Int = -1,          // -1 → first-run sentinel (auto top-center)
        val y: Int = -1,
        val widthDp: Int = DEFAULT_WIDTH_DP,
        val opacity: Float = DEFAULT_OPACITY
    )

    fun loadGeometry(context: Context): Geometry {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Geometry(
            x = p.getInt(KEY_X, -1),
            y = p.getInt(KEY_Y, -1),
            widthDp = p.getInt(KEY_WIDTH_DP, DEFAULT_WIDTH_DP).coerceIn(120, 2000),
            opacity = p.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0.15f, 1f)
        )
    }

    fun saveGeometry(context: Context, geo: Geometry) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, geo.x)
            .putInt(KEY_Y, geo.y)
            .putInt(KEY_WIDTH_DP, geo.widthDp)
            .putFloat(KEY_OPACITY, geo.opacity)
            .apply()
    }

    /** Saves only the opacity (Settings slider) without touching position/size. */
    fun saveOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_OPACITY, opacity.coerceIn(0.15f, 1f))
            .apply()
    }

    /** Resets position/size to the first-run defaults (Settings button). */
    fun resetGeometry(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_X)
            .remove(KEY_Y)
            .remove(KEY_WIDTH_DP)
            .apply()
    }

    /**
     * True while the overlay is supposed to exist. Set by the service on every
     * start; cleared only on a deliberate stop (notification action / Settings
     * toggle) — never on crashes/kills, so the watchdog keeps reviving it.
     */
    fun shouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SHOULD_RUN, value)
            .apply()
    }
}
