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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tail.MainActivity
import com.example.tail.R
import com.example.tail.data.BridgeClient
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.HabitsRepository
import com.example.tail.data.PcEventQueueProcessor
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.dateString
import com.example.tail.data.secondaryValueKey
import com.example.tail.ui.HabitIncrementBus
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Foreground service that displays a draggable floating bubble over other apps.
 *
 * The bubble shows the Tail app icon and can be:
 *  - Dragged anywhere on the screen
 *  - Dismissed by dragging to the X zone at the bottom center
 *  - Tapped to start/stop the trigger habit's timer — while it runs, the live
 *    elapsed time is shown in a small pill right above the bubble
 *  - Long-pressed to stop a running timer (recording it) and open the Tail app
 *  - Stopped automatically when the trigger app closes/leaves the foreground —
 *    a still-running timer is stopped and recorded at that moment
 *    (see [ACTION_TRIGGER_APP_LEFT])
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW] ("draw over other apps").
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "tail_floating_bubble"
        private const val NOTIFICATION_ID = 9911

        /**
         * How long to stay alive after the trigger app left while a timer was
         * running: the increment flash shows for ~3 s and [onDestroy] would
         * tear it down, so the actual stopSelf() waits out the flash.
         */
        private const val LINGER_STOP_DELAY_MS = 3500L

        /** dp → px helper */
        private fun Int.dp(resources: Resources): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                this.toFloat(),
                resources.displayMetrics
            ).toInt()

        /** Action to stop the bubble from anywhere (e.g. notification action). */
        const val ACTION_STOP_BUBBLE = "com.example.tail.widget.STOP_BUBBLE"

        /**
         * Action sent by [WidgetTriggerService] when the trigger app left the
         * foreground (closed or switched away from): any still-running habit
         * timer is stopped and recorded before the bubble hides itself.
         */
        const val ACTION_TRIGGER_APP_LEFT = "com.example.tail.widget.TRIGGER_APP_LEFT"

        /** Intent extra: name of the habit whose trigger app opened the bubble. */
        const val EXTRA_HABIT_NAME = "habit_name"

        /** Intent extra: names of ALL habits sharing the trigger app (picker menu). */
        const val EXTRA_HABIT_NAMES = "habit_names"

        /** Intent extra: true when the trigger app is the Chess Readiness app. */
        const val EXTRA_CHESS_READINESS = "chess_readiness"

        /**
         * True while this service is alive. Lets [WidgetTriggerService] detect
         * when the bubble died unexpectedly (process kill / crash) and revive
         * it — the widget must never stay gone.
         */
        @Volatile
        var isRunning = false
            private set

        /**
         * True when the last stop was deliberate (dismiss drag or
         * ACTION_STOP_BUBBLE) rather than an unexpected death. The trigger
         * service only auto-revives the bubble when this is false.
         */
        @Volatile
        var stoppedByUser = false
            private set
    }

    /** Marks the current/next stop as deliberate (user or monitor initiated). */
    private fun noteDeliberateStop() {
        stoppedByUser = true
    }

    /** Clears the deliberate-stop flag — the bubble was (re)started on purpose. */
    private fun noteRestarted() {
        stoppedByUser = false
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleRingView: View? = null
    private var dismissZoneView: View? = null

    /** Habit whose trigger app opened the bubble (drives the tap timer). */
    private var triggerHabitName: String? = null

    /** Every habit sharing the trigger app that opened the bubble. */
    private var triggerHabitNames: List<String> = emptyList()

    /** True when the bubble was opened over the Chess Readiness app. */
    private var chessReadinessActive = false

    // ── Timer chip overlay (live elapsed time above the bubble) ───────────
    private var timerChipView: TextView? = null

    // ── V3 survival gate panel (side banner next to the bubble) ──────────
    private var survivalPanelView: LinearLayout? = null
    private var survivalArmed = false        // armed = waiting for ▶ START
    private var survivalRunning = false
    private var survivalSessionStartedAt = 0L
    private var survivalTarget = 0
    private var survivalVariant: String? = null
    private var survivalPassed = 0
    private var survivalRunStartMs = 0L      // elapsedRealtime when ▶ was tapped
    private var survivalPuzzleStartMs = 0L
    private var survivalReflex: ChessReadinessV3Engine.ReflexSummary? = null

    /**
     * Free-play mode: after the official v3 gate ends, a linked survival
     * habit keeps the banner alive as a plain increasing timer so the user
     * can keep drilling and earn habit minutes for the extra time.
     */
    private var survivalFreePlay = false
    private var survivalFreePlayStartMs = 0L
    private var survivalCounterText: TextView? = null
    private var survivalStopwatchText: TextView? = null
    private var survivalTotalText: TextView? = null
    private var survivalPctText: TextView? = null

    /** 70th-percentile personal target (null until 8 past runs exist). */
    private var survivalPctTarget: Int? = null

    /**
     * The pass bar actually enforced: the rating-derived guaranteed target,
     * relaxed to the user's own 70th-percentile history when that is lower
     * (but never below the hard floor). 0 until the run starts.
     */
    private var survivalPassAt = 0

    /** Percentile win already secured this run (run continues regardless). */
    private var survivalPctWon = false

    /** Slot INSIDE the bubble's window where the survival banner is attached. */
    private var survivalSlot: FrameLayout? = null

    /**
     * Cumulative survival time (every past run, including the one that just
     * ended) — the free-play timer STARTS from this, so the displayed time is
     * the user's TOTAL time in survival mode, not just the free-play portion.
     */
    private var survivalFreePlayBaseMs = 0L

    /** Verdict popup shown when a survival run ends (pass / fail). */
    private var survivalResultPopup: ChessOverlayDialog? = null

    // ── Habit picker menu (several habits share one trigger app) ──────────
    private var habitMenuView: LinearLayout? = null

    // ── Increment flash message (shown after a session is recorded) ──────
    private var flashView: LinearLayout? = null
    private val flashDismissRunnable = Runnable { hideIncrementFlash() }
    private val handler = Handler(Looper.getMainLooper())

    // ── Delayed self-stop after the trigger app left ─────────────────────
    // When the trigger app closes mid-timer the session is recorded and a
    // confirmation flash shown; the service must outlive that flash because
    // onDestroy removes it. The generation counter cancels the pending stop
    // if the bubble is re-started (user re-entered the app) meanwhile.
    private var startGeneration = 0
    private var lingerStopGeneration = -1
    private val delayedStopRunnable: Runnable = object : Runnable {
        override fun run() {
            if (lingerStopGeneration == startGeneration) {
                // A Puzzle Rush report still due? The overlay window dies
                // with the service, so hold off until it is answered or
                // skipped — the pending report itself is persisted, so an
                // expired prompt simply never re-opens.
                if (chessPuzzleRushOverlay?.isShowing() == true) {
                    handler.postDelayed(this, LINGER_STOP_DELAY_MS)
                } else {
                    stopSelf()
                }
            }
        }
    }

    // ── Long-press detection ──────────────────────────────────────────────
    private var longPressConsumed = false
    private val longPressRunnable = Runnable { onBubbleLongPressed() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // NOT cancelled in onDestroy: stopping the timer and immediately leaving
    // the trigger app (or any other stopSelf()) used to cancel the in-flight
    // minutes write — serviceScope is torn down in onDestroy — killing the
    // save with "Job was cancelled" and losing the recorded minutes.
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val habitsRepo by lazy { HabitsRepository() }

    // ── PC widget event queue poll ────────────────────────────────────────
    // The PC bubble widget appends habit events to the Tail Bridge queue.
    // While this service runs we drain that queue into the phone DB
    // (acking what we applied), so PC sessions are recorded even when the
    // Tail app itself is not open. The drain uses the bridge's long-poll
    // endpoint (pc_widget/events/wait), so a PC-side timer stop is applied
    // ~instantly; bridges without that endpoint (404) fall back to the
    // fixed interval. No-op when no Tail Bridge connection is configured.
    private val pcEventPollIntervalMs = 45_000L
    private var pcEventLongPollSupported = true
    private val pcEventPollRunnable = Runnable { pollPcEventQueue() }

    private fun pollPcEventQueue() {
        serviceScope.launch(Dispatchers.IO) {
            var nextDelayMs = pcEventPollIntervalMs
            try {
                val settings = settingsRepo.settingsFlow.first()
                if (settings.garminProxyUrl.isNotEmpty()) {
                    PcEventQueueProcessor(applicationContext).processOnce()
                    val bridge = bridgeConnectionFrom(
                        settings.garminProxyUrl, settings.garminAppToken)
                    if (bridge != null && pcEventLongPollSupported) {
                        // Hold the connection open until the bridge has
                        // something (or the timeout passes) — instant pickup.
                        val waitSec = pcEventPollIntervalMs / 1000
                        val result = BridgeClient().fetchWithStatus(
                            bridge.first, bridge.second,
                            "pc_widget/events/wait?timeout=$waitSec",
                            readTimeoutMs = (pcEventPollIntervalMs + 10_000L).toInt())
                        when (result?.first) {
                            200 -> nextDelayMs = 1_000L
                            404 -> pcEventLongPollSupported = false // old bridge
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("FloatingBubbleService", "PC event poll failed: ${e.message}")
            } finally {
                handler.postDelayed(pcEventPollRunnable, nextDelayMs)
            }
        }
    }

    // Bubble layout params (positioned on screen)
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Bubble size in px
    private val bubbleSize by lazy { 56.dp(resources) }
    private val dismissZoneSize by lazy { 72.dp(resources) }
    private val timerChipHeight by lazy { 30.dp(resources) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        stoppedByUser = false
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        // Start the PC widget event queue poll (short first delay so a
        // freshly-started bubble picks up queued events quickly).
        handler.postDelayed(pcEventPollRunnable, 5_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_BUBBLE -> {
                noteDeliberateStop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TRIGGER_APP_LEFT -> {
                // The trigger app closed/left the foreground — stop & record
                // any running timer, then hide the bubble.
                noteDeliberateStop()
                handleTriggerAppLeft()
                return START_NOT_STICKY
            }
        }

        // A (re)start cancels any pending linger-stop scheduled when the
        // trigger app was last left (the user re-entered it during the flash
        // linger) — the bubble is back in business.
        handler.removeCallbacks(delayedStopRunnable)
        startGeneration++
        noteRestarted()

        // Remember which habits' trigger app opened the bubble (for the timer).
        // Several habits can share one trigger app; if so, the first tap shows
        // a picker menu instead of starting a timer right away.
        val habits = intent?.getStringArrayListExtra(EXTRA_HABIT_NAMES)
            ?: intent?.getStringExtra(EXTRA_HABIT_NAME)?.let { arrayListOf(it) }
        if (habits != null) {
            triggerHabitNames = habits
            // Auto-select the single habit, or the one already being timed
            // (bubble re-shown mid-session); otherwise wait for the user to pick.
            // With Chess Readiness active the menu always offers a choice, so
            // no auto-selection happens (the user must pick explicitly).
            triggerHabitName = if (intent?.getBooleanExtra(EXTRA_CHESS_READINESS, false) == true) {
                habits.firstOrNull { WidgetTimerStore.isTimerRunning(this, it) }
            } else {
                habits.firstOrNull { WidgetTimerStore.isTimerRunning(this, it) }
                    ?: habits.singleOrNull()
            }
        }
        chessReadinessActive = intent?.getBooleanExtra(EXTRA_CHESS_READINESS, false) == true

        if (intent != null) {
            // Remember the configuration so a sticky restart after a process
            // kill can restore it (the trigger service normally re-sends it,
            // but it may itself be dead at that moment).
            try {
                BubbleStateStore.save(this, triggerHabitNames, chessReadinessActive)
            } catch (_: Exception) { /* prefs are best-effort */ }
        } else if (triggerHabitNames.isEmpty()) {
            // Sticky restart with a null intent — restore the last config.
            val saved = BubbleStateStore.load(this)
            triggerHabitNames = saved.habitNames
            triggerHabitName =
                saved.habitNames.firstOrNull { WidgetTimerStore.isTimerRunning(this, it) }
                    ?: if (saved.chessReadiness) null else saved.habitNames.singleOrNull()
            chessReadinessActive = saved.chessReadiness
        }

        if (bubbleView == null) {
            showBubble()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        dismissChessOverlays()
        removeBubble()
        removeDismissZone()
        hideTimerChip()
        survivalResultPopup?.dismiss()
        survivalResultPopup = null
        hideSurvivalPanel()
        hideHabitPickerMenu()
        hideIncrementFlash()
        handler.removeCallbacks(pcEventPollRunnable)
        serviceScope.cancel()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Bubble setup
    // ──────────────────────────────────────────────────────────────────────

    private fun showBubble() {
        val layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Start at the right edge, vertically centered
            x = Resources.getSystem().displayMetrics.widthPixels - bubbleSize - 16.dp(resources)
            y = Resources.getSystem().displayMetrics.heightPixels / 3
        }

        // Border ring around the bubble (turns green while the timer runs)
        val ring = View(this).apply {
            background = createRingDrawable(running = false)
        }
        bubbleRingView = ring

        val container = FrameLayout(this).apply {
            // Render the app icon as a circular bitmap inside an ImageView
            val imageView = ImageView(this@FloatingBubbleService).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                // Load the adaptive launcher icon
                val iconRes = resources.getIdentifier(
                    "ic_launcher_foreground_custom", "drawable", packageName
                )
                if (iconRes != 0) {
                    val drawable = resources.getDrawable(iconRes, theme)
                    setImageDrawable(drawable)
                }
                // Circular background behind the icon
                background = createCircularBackground()
            }

            addView(ring, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(imageView, FrameLayout.LayoutParams(
                bubbleSize - 8.dp(resources),
                bubbleSize - 8.dp(resources),
                Gravity.CENTER
            ))
        }

        // Apply rounded clipping via outline
        container.clipToOutline = true
        container.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        container.setOnTouchListener(BubbleTouchListener())

        // The bubble window is a horizontal COMPOSITE: the round bubble plus
        // an (initially hidden) slot to its right where the V3 survival
        // banner (gate controls / free-play timer) attaches. One window →
        // banner and bubble always move, snap and clamp together.
        val slot = FrameLayout(this).apply { visibility = View.GONE }
        survivalSlot = slot
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(container, LinearLayout.LayoutParams(bubbleSize, bubbleSize))
            addView(
                slot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        bubbleView = root

        try {
            windowManager.addView(root, bubbleParams)
        } catch (e: Exception) {
            // Permission not granted or other error — stop the service
            stopSelf()
            return
        }

        // If the habit's timer is already running (e.g. the bubble was hidden
        // while the trigger app was left), resume the live timer display.
        val habit = triggerHabitName
        if (habit != null && WidgetTimerStore.isTimerRunning(this, habit)) {
            setBubbleRunningVisuals(running = true)
            showTimerChip()
        }

        // Restore an armed (or running) V3 survival panel after the bubble
        // service was recreated while the gate was in progress.
        maybeRestoreSurvivalPanel()
    }

    private fun createCircularBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    }

    private fun createRingDrawable(running: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(
                3.dp(resources),
                if (running) 0xFF4CAF50.toInt() else Color.argb(80, 0, 0, 0)
            )
            setColor(Color.TRANSPARENT)
        }
    }

    /** Toggles the bubble's "timer running" look (green ring while running). */
    private fun setBubbleRunningVisuals(running: Boolean) {
        bubbleRingView?.background = createRingDrawable(running)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Dismiss zone (the X at the bottom center)
    // ──────────────────────────────────────────────────────────────────────

    private fun showDismissZone() {
        if (dismissZoneView != null) return

        val params = WindowManager.LayoutParams(
            dismissZoneSize,
            dismissZoneSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 48.dp(resources)
        }

        val container = FrameLayout(this).apply {
            background = createDismissZoneBackground()
            val xLabel = TextView(this@FloatingBubbleService).apply {
                text = "✕"
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
            }
            addView(xLabel, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ))
        }

        container.alpha = 0f
        container.animate().alpha(1f).setDuration(200).start()

        dismissZoneView = container
        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun hideDismissZone() {
        dismissZoneView?.let { zone ->
            zone.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    try {
                        windowManager.removeView(zone)
                    } catch (e: Exception) { /* already removed */ }
                }
                .start()
        }
        dismissZoneView = null
    }

    private fun createDismissZoneBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.argb(180, 244, 67, 54)) // semi-transparent red
            setStroke(4.dp(resources), Color.argb(200, 255, 255, 255))
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Touch handling — drag + tap + dismiss
    // ──────────────────────────────────────────────────────────────────────

    private inner class BubbleTouchListener : View.OnTouchListener {

        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var isDragging = false
        private val touchSlop = 10.dp(resources) // px threshold to distinguish tap from drag

        /** System long-press timeout, used to detect long-presses on the bubble. */
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout()

        // For dismiss-zone proximity detection
        private val dismissProximityRadius by lazy { dismissZoneSize.toFloat() * 0.8f }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
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

                    if (!isDragging && hypot(dx, dy) > touchSlop) {
                        isDragging = true
                        cancelLongPress()
                        hideHabitPickerMenu()
                        showDismissZone()
                    }

                    if (isDragging) {
                        bubbleParams.x = initialX + dx.toInt()
                        bubbleParams.y = initialY + dy.toInt()

                        // Clamp to screen bounds (window = bubble + any
                        // attached survival banner)
                        val maxX = Resources.getSystem().displayMetrics.widthPixels -
                            bubbleWindowWidth()
                        val maxY = Resources.getSystem().displayMetrics.heightPixels - bubbleSize
                        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
                        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)

                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        } catch (e: Exception) { /* view removed */ }

                        // Keep the timer chip glued above the bubble
                        positionTimerChip()
                        positionSurvivalPanel()

                        // Highlight dismiss zone when bubble is near it
                        updateDismissZoneHighlight()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    cancelLongPress()

                    // The long-press already acted — don't treat the release as a tap
                    if (longPressConsumed) {
                        longPressConsumed = false
                        return true
                    }

                    if (isDragging) {
                        hideDismissZone()

                        // Check if dropped on dismiss zone
                        if (isOverDismissZone(event.rawX, event.rawY)) {
                            noteDeliberateStop()
                            stopSelf()
                            return true
                        }

                        // Snap to nearest horizontal edge (left or right)
                        snapToEdge()
                    } else {
                        // It was a tap — toggle the habit timer
                        onBubbleTapped()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                    longPressConsumed = false
                    if (isDragging) {
                        hideDismissZone()
                        snapToEdge()
                    }
                    return true
                }
            }
            return false
        }

        /** Check if the touch position is over the dismiss zone area. */
        private fun isOverDismissZone(rawX: Float, rawY: Float): Boolean {
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Dismiss zone center: bottom center, 48dp from bottom
            val zoneCenterX = screenWidth / 2f
            val zoneCenterY = screenHeight - 48.dp(resources) - dismissZoneSize / 2f

            val dist = hypot(rawX - zoneCenterX, rawY - zoneCenterY)
            return dist < dismissProximityRadius
        }

        /** Scale up the dismiss zone when the bubble is close to it. */
        private fun updateDismissZoneHighlight() {
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            val zoneCenterX = screenWidth / 2f
            val zoneCenterY = screenHeight - 48.dp(resources) - dismissZoneSize / 2f

            // Bubble center
            val bubbleCenterX = bubbleParams.x + bubbleSize / 2f
            val bubbleCenterY = bubbleParams.y + bubbleSize / 2f

            val dist = hypot(bubbleCenterX - zoneCenterX, bubbleCenterY - zoneCenterY)
            val nearZone = dist < dismissProximityRadius * 1.5f

            dismissZoneView?.let { zone ->
                val targetScale = if (nearZone) 1.25f else 1.0f
                zone.animate().scaleX(targetScale).scaleY(targetScale).setDuration(100).start()
            }
        }

        /** Animate the bubble to the nearest screen edge (left or right). */
        private fun snapToEdge() {
            val displayMetrics = Resources.getSystem().displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val margin = 8.dp(resources)

            val targetX = if (bubbleParams.x + bubbleSize / 2 < screenWidth / 2) {
                margin
            } else {
                screenWidth - bubbleWindowWidth() - margin
            }

            val startX = bubbleParams.x
            val startY = bubbleParams.y
            val targetY = bubbleParams.y

            // Simple animation by progressively updating the view position
            val duration = 200L
            val startTime = System.currentTimeMillis()

            val updateRunnable = object : Runnable {
                override fun run() {
                    val elapsed = System.currentTimeMillis() - startTime
                    val progress = (elapsed.toFloat() / duration).coerceAtMost(1f)
                    // Ease-out interpolation
                    val interpolated = 1f - (1f - progress) * (1f - progress)

                    bubbleParams.x = (startX + (targetX - startX) * interpolated).toInt()
                    bubbleParams.y = targetY

                    try {
                        windowManager.updateViewLayout(bubbleView, bubbleParams)
                        positionTimerChip()
                        positionSurvivalPanel()
                    } catch (e: Exception) { return }

                    if (progress < 1f) {
                        bubbleView?.postOnAnimation(this)
                    }
                }
            }
            bubbleView?.postOnAnimation(updateRunnable)
        }
    }

    /**
     * Called when the bubble is tapped (not dragged). Toggles the trigger
     * habit's timer: first tap starts it (a live elapsed-time pill appears
     * above the bubble), second tap stops it and records the minutes.
     */
    private fun onBubbleTapped() {
        try {
            onBubbleTappedInner()
        } catch (e: Exception) {
            // A menu/overlay failure must NEVER take the bubble (and with it
            // the whole widget) down.
        }
    }

    private fun onBubbleTappedInner() {
        // Brief scale animation to give visual feedback
        bubbleView?.let { bubble ->
            bubble.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(80)
                .withEndAction {
                    bubble.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(80)
                        .start()
                }
                .start()
        }

        // A Chess Readiness test in progress owns the bubble: resume the
        // wizard straight at its current step (puzzle/rush result entry) —
        // no picker menu, no other options. loadSession() self-clears
        // expired sessions, so a null here simply falls through to the
        // normal menu/timer behaviour.
        if (chessReadinessActive &&
            ChessReadinessStore.loadSession(this) != null
        ) {
            hideHabitPickerMenu()
            openChessReadiness()
            return
        }

        // A due Puzzle Rush report equally owns the bubble: the rush timer
        // ended and its result was never entered. loadPending() self-clears
        // expired reports, so a null here falls through to normal behaviour.
        if (ChessPuzzleRushStore.loadPending(this) != null) {
            hideHabitPickerMenu()
            openPuzzleRushReport()
            return
        }

        val habit = triggerHabitName
        if (habit == null) {
            if (chessReadinessActive || triggerHabitNames.size > 1) {
                // Chess Readiness option and/or several habits share this
                // trigger app — show the picker menu
                showHabitPickerMenu()
            } else {
                // Bubble started manually (no trigger habit) — tapping just opens Tail
                openTailApp()
            }
            return
        }

        if (WidgetTimerStore.isTimerRunning(this, habit)) {
            stopTimerAndRecord(habit)
        } else if (chessReadinessActive || triggerHabitNames.size > 1) {
            // Chess Readiness option and/or multiple habits available and
            // nothing running — pick from the menu
            showHabitPickerMenu()
        } else {
            startTimerForHabit(habit)
        }
    }

    /**
     * Called when the bubble is long-pressed. Stops a running timer (recording
     * the elapsed minutes) and opens the Tail app either way.
     */
    private fun onBubbleLongPressed() {
        longPressConsumed = true
        hideHabitPickerMenu()

        // Haptic confirmation
        try {
            getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) { /* no vibrator */ }

        val habit = triggerHabitName
        if (habit != null && WidgetTimerStore.isTimerRunning(this, habit)) {
            // Record first, then open Tail once the write has landed —
            // opening immediately would race the app's startup DB load
            // against our write and could clobber it (lost increments).
            stopTimerAndRecord(habit) { openTailApp() }
        } else {
            openTailApp()
        }
    }

    /** Cancels a pending long-press detection (drag started / finger lifted). */
    private fun cancelLongPress() {
        handler.removeCallbacks(longPressRunnable)
    }

    /** Launches the Tail app's main activity from the overlay. */
    private fun openTailApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) { /* activity unavailable */ }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Timer chip (live elapsed time above the bubble)
    // ──────────────────────────────────────────────────────────────────────

    /** Live-update runnable — refreshes the elapsed-time pill while visible. */
    private val timerTickRunnable = object : Runnable {
        override fun run() {
            val habit = triggerHabitName ?: return
            val chip = timerChipView ?: return
            if (WidgetTimerStore.isTimerRunning(this@FloatingBubbleService, habit)) {
                val elapsed = WidgetTimerStore.formatElapsed(
                    WidgetTimerStore.elapsedMillis(this@FloatingBubbleService, habit)
                )
                chip.text = elapsed
                // Shrink slightly once the string gets long (e.g. h:mm:ss)
                chip.textSize = if (elapsed.length > 5) 12f else 14f
                handler.postDelayed(this, 500L)
            } else {
                hideTimerChip()
            }
        }
    }

    /**
     * Shows the live elapsed-time pill anchored above the bubble (or directly
     * below it when the bubble sits at the very top of the screen).
     */
    private fun showTimerChip() {
        if (timerChipView != null) return
        val habit = triggerHabitName ?: return

        val density = resources.displayMetrics.density
        val chip = TextView(this).apply {
            text = WidgetTimerStore.formatElapsed(
                WidgetTimerStore.elapsedMillis(this@FloatingBubbleService, habit)
            )
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(0xEE161616.toInt())
                cornerRadius = 14f * density
                setStroke(1, 0xFF334455.toInt())
            }
        }
        timerChipView = chip

        val params = WindowManager.LayoutParams(
            bubbleSize, // same width as the bubble → stays centered on it
            timerChipHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager.addView(chip, params)
            positionTimerChip()
            handler.removeCallbacks(timerTickRunnable)
            handler.postDelayed(timerTickRunnable, 500L)
        } catch (e: Exception) {
            timerChipView = null
        }
    }

    /** Removes the timer chip overlay and stops the live tick updates. */
    private fun hideTimerChip() {
        handler.removeCallbacks(timerTickRunnable)
        timerChipView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        timerChipView = null
    }

    /**
     * Repositions the timer chip so it floats just above the bubble (flipping
     * below it if the bubble is at the top edge). Called whenever the bubble
     * moves — drag, snap animation, or chip creation.
     */
    private fun positionTimerChip() {
        val chip = timerChipView ?: return
        val params = chip.layoutParams as? WindowManager.LayoutParams ?: return
        val gap = 8.dp(resources)
        params.x = bubbleParams.x
        params.y = if (bubbleParams.y - timerChipHeight - gap >= 0) {
            bubbleParams.y - timerChipHeight - gap
        } else {
            bubbleParams.y + bubbleSize + gap
        }
        try {
            windowManager.updateViewLayout(chip, params)
        } catch (e: Exception) { /* view removed */ }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  V3 survival gate panel (Puzzle Rush Survival control under the bubble)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Restores the survival panel after a service restart: an ARMED session
     * re-shows the ▶ START panel (the pending record survives in the v3
     * store). A RUNNING run cannot be restored mid-flight (the run timing
     * is monotonic and the run must be contiguous) — it is treated as
     * armed-again from zero.
     */
    private fun maybeRestoreSurvivalPanel() {
        val pending = ChessReadinessV3Store.loadPendingSurvival(this) ?: return
        survivalSessionStartedAt = pending.sessionStartedAt
        survivalTarget = pending.target
        survivalVariant = pending.variant
        survivalReflex = ChessReadinessV3Engine.ReflexSummary(
            lapses = pending.reflexLapses,
            falseStarts = pending.reflexFalseStarts,
            meanRtMs = pending.reflexMeanRtMs,
            passed = true
        )
        showSurvivalPanel(armed = true)
    }

    /** Called by the v3 overlay's hand-off: shows the ▶ START panel. */
    fun armSurvivalPanel() {
        val pending = ChessReadinessV3Store.loadPendingSurvival(this) ?: return
        survivalSessionStartedAt = pending.sessionStartedAt
        survivalTarget = pending.target
        survivalVariant = pending.variant
        survivalReflex = ChessReadinessV3Engine.ReflexSummary(
            lapses = pending.reflexLapses,
            falseStarts = pending.reflexFalseStarts,
            meanRtMs = pending.reflexMeanRtMs,
            passed = true
        )
        showSurvivalPanel(armed = true)
    }

    /** Live tick: per-puzzle stopwatch + total timer + 5-minute cap check. */
    private val survivalTickRunnable = object : Runnable {
        override fun run() {
            if (survivalFreePlay) {
                val now = android.os.SystemClock.elapsedRealtime()
                // Free-play timer = gate run duration (base) + this free-play
                // session, i.e. the user's TOTAL time in survival mode.
                survivalStopwatchText?.text = formatSurvivalStopwatch(
                    survivalFreePlayBaseMs + (now - survivalFreePlayStartMs)
                )
                handler.postDelayed(this, 100)
                return
            }
            if (!survivalRunning) return
            val now = android.os.SystemClock.elapsedRealtime()
            val total = now - survivalRunStartMs
            survivalStopwatchText?.text = formatSurvivalStopwatch(now - survivalPuzzleStartMs)
            survivalTotalText?.text = formatSurvivalClock(total) + " / 5:00"
            if (ChessReadinessV3Engine.timedOut(total)) {
                // Timeout ends the run, but a score already at/above the
                // personal P70 bar still passes (never below the floor).
                finishSurvivalRun(
                    if (survivalPassAt > 0 && survivalPassed >= survivalPassAt)
                        ChessReadinessV3Engine.Verdict.PASS
                    else ChessReadinessV3Engine.Verdict.FAIL_TIMEOUT
                )
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    private fun formatSurvivalClock(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatSurvivalStopwatch(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val s = clamped / 1000
        val tenth = (clamped % 1000) / 100
        return "%02d:%02d.%d".format(s / 60, s % 60, tenth)
    }

    /**
     * Shows the survival panel next to the bubble.
     *  - armed: small vertical card with "SURVIVAL GATE — N puzzles" + one
     *    ▶ START button (the user navigates to the chess.com survival drill
     *    first, then taps ▶);
     *  - running: a LONG HORIZONTAL BANNER to the right of the bubble —
     *    counter, percentile indicator, stopwatch, total timer and the
     *    ✓ PASS / ✕ FAIL buttons all in one row, so it can be dragged
     *    somewhere that does not cover the board.
     */
    private fun showSurvivalPanel(armed: Boolean) {
        hideSurvivalPanel()
        survivalArmed = armed
        survivalRunning = !armed

        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val panel = LinearLayout(this).apply {
            // VERTICAL in BOTH modes — a narrow tall card next to the bubble
            // can never be clipped off the screen edge the way a wide
            // horizontal banner was.
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE161616.toInt())
                cornerRadius = 12f * density
                setStroke(1, 0xFF334455.toInt())
            }
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
        }

        fun panelButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) =
            TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(fg)
                setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
                background = GradientDrawable().apply {
                    setColor(bg)
                    cornerRadius = 10f * density
                }
                setOnClickListener { onClick() }
            }

        if (armed) {
            panel.addView(TextView(this).apply {
                text = "♟ SURVIVAL GATE"
                setTextColor(0xFF66CCFF.toInt())
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            panel.addView(TextView(this).apply {
                text = "Target: $survivalTarget puzzles" +
                    (survivalVariant?.let { " ($it)" } ?: "") +
                    " · 0 strikes · 5:00 cap"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, 2.dp(), 0, 4.dp())
            })
            panel.addView(
                panelButton("▶  START SURVIVAL", 0xFF1E5631.toInt(), 0xFF88FF88.toInt()) {
                    startSurvivalRun()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            panel.addView(TextView(this).apply {
                text = "Open the chess.com survival drill, then tap START"
                setTextColor(0xFF999999.toInt())
                textSize = 10f
                gravity = Gravity.CENTER
                setPadding(0, 4.dp(), 0, 0)
            })
        } else {
            fun vMargin(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = top.dp(); bottomMargin = bottom.dp() }

            // TOTAL timer (small, on top) counting up to the 5:00 cap — no
            // per-puzzle stopwatch (no per-puzzle limit exists; per-puzzle
            // latency is still recorded silently as telemetry on each PASS).
            survivalTotalText = TextView(this).apply {
                text = "0:00 / 5:00"
                setTextColor(0xFF999999.toInt())
                textSize = 11f
                gravity = Gravity.CENTER
            }
            panel.addView(survivalTotalText, vMargin())

            // Puzzle counter BELOW the timer.
            survivalCounterText = TextView(this).apply {
                text = "%02d / %d".format(survivalPassed + 1, survivalTarget)
                setTextColor(0xFF66CCFF.toInt())
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            panel.addView(survivalCounterText, vMargin(top = 2))

            survivalPctText = TextView(this).apply {
                text = pctBannerLabel()
                setTextColor(if (survivalPctWon) 0xFF88FF88.toInt() else 0xFFAAAAAA.toInt())
                textSize = 9f
                gravity = Gravity.CENTER
            }
            panel.addView(survivalPctText, vMargin())

            // Caption ABOVE the buttons; the buttons carry only the ✓ / ✕
            // glyph but are LARGE (52×48 dp) so they are easy to hit mid-drill.
            val caption = TextView(this).apply {
                text = "✓ pass · ✕ fail"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 9f
                gravity = Gravity.CENTER
            }
            panel.addView(caption, vMargin(top = 4, bottom = 2))

            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            fun bigButton(label: String, bg: Int, fg: Int, onClick: () -> Unit) =
                panelButton(label, bg, fg, onClick).apply {
                    textSize = 20f
                    minWidth = 52.dp()
                    minHeight = 48.dp()
                }
            buttonRow.addView(
                bigButton("✓", 0xFF1E5631.toInt(), 0xFF88FF88.toInt()) {
                    onSurvivalPass()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp() }
            )
            buttonRow.addView(
                bigButton("✕", 0xFF7F1D1D.toInt(), 0xFFEF9A9A.toInt()) {
                    onSurvivalFail()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            panel.addView(
                buttonRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        survivalPanelView = panel
        // ATTACHED to the bubble's own window (right of it) — not a separate
        // floating window, so it can never drift apart from the bubble.
        attachSurvivalBanner(panel)
        if (survivalRunning) {
            handler.removeCallbacks(survivalTickRunnable)
            handler.postDelayed(survivalTickRunnable, 100)
        }
    }

    /** Percentile indicator label for the running banner. */
    private fun pctBannerLabel(): String {
        val t = survivalPctTarget
        return when {
            survivalPctWon -> "P70 ✓ secured"
            t != null -> "P70 win at $t"
            else -> "P70 needs history"
        }
    }

    /** ▶ tapped: the drill begins — timers start, PASS/FAIL go live. */
    private fun startSurvivalRun() {
        survivalRunStartMs = android.os.SystemClock.elapsedRealtime()
        survivalPuzzleStartMs = survivalRunStartMs
        survivalPassed = 0
        survivalPctWon = false
        val past = ChessReadinessV3Store.loadResults(this).map { it.puzzlesPassed }
        survivalPctTarget = ChessReadinessV3Engine.percentileTarget(past)
        survivalPassAt = ChessReadinessV3Engine.effectivePassTarget(survivalTarget, past)
        showSurvivalPanel(armed = false)
    }

    /**
     * Immediate tactile + visual confirmation of a PASS/FAIL tap: a short
     * vibration (a longer, sharper double-buzz for FAIL) and a brief
     * green/red flash of the survival banner background, so the press is
     * unmistakably registered even mid-drill.
     */
    private fun feedbackSurvivalPress(pass: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        try {
            val effect = if (pass) {
                VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 60, 70, 120), -1)
            }
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
        }
        val panel = survivalPanelView ?: return
        val bg = panel.background?.mutate() as? GradientDrawable ?: return
        bg.setColor(if (pass) 0xCC1E5631.toInt() else 0xCC7F1D1D.toInt())
        panel.invalidate()
        handler.postDelayed({
            bg.setColor(0xEE161616.toInt())
            panel.invalidate()
        }, 180)
    }

    private fun onSurvivalPass() {
        if (!survivalRunning) return
        feedbackSurvivalPress(pass = true)
        val now = android.os.SystemClock.elapsedRealtime()
        val duration = (now - survivalPuzzleStartMs).coerceAtLeast(0L)
        ChessReadinessV3Store.appendEvent(
            this,
            ChessReadinessV3Store.SurvivalEventRecord(
                sessionId = survivalSessionStartedAt,
                puzzleIndex = survivalPassed + 1,
                puzzleDurationMs = duration,
                timestamp = System.currentTimeMillis(),
                verdict = ChessReadinessV3Engine.Verdict.PASS.name
            )
        )
        val passed = survivalPassed
        survivalPassed = passed + 1
        // The run ALWAYS continues to the guaranteed target — the P70 bar
        // never ends it early, so the logged score reflects the true max.
        if (ChessReadinessV3Engine.onPass(passed, survivalTarget)) {
            finishSurvivalRun(ChessReadinessV3Engine.Verdict.PASS)
        } else {
            // Percentile win: secured but NOT terminal — the run continues
            // up to the absolute guaranteed target.
            if (!survivalPctWon &&
                ChessReadinessV3Engine.percentileReached(survivalPassed, survivalPctTarget)
            ) {
                survivalPctWon = true
                survivalPctText?.apply {
                    text = pctBannerLabel()
                    setTextColor(0xFF88FF88.toInt())
                }
                Toast.makeText(
                    this,
                    "♟ 70th-percentile win secured — continue to the absolute target ($survivalTarget)",
                    Toast.LENGTH_SHORT
                ).show()
            }
            survivalCounterText?.text = "%02d / %d".format(survivalPassed + 1, survivalTarget)
            survivalPuzzleStartMs = now
        }
    }

    private fun onSurvivalFail() {
        if (!survivalRunning) return
        feedbackSurvivalPress(pass = false)
        val now = android.os.SystemClock.elapsedRealtime()
        ChessReadinessV3Store.appendEvent(
            this,
            ChessReadinessV3Store.SurvivalEventRecord(
                sessionId = survivalSessionStartedAt,
                puzzleIndex = survivalPassed + 1,
                puzzleDurationMs = (now - survivalPuzzleStartMs).coerceAtLeast(0L),
                timestamp = System.currentTimeMillis(),
                verdict = ChessReadinessV3Engine.Verdict.FAIL_STRIKE.name
            )
        )
        // A strike ends the run, but a score already at/above the personal
        // P70 bar (which is never below the hard floor) still passes —
        // e.g. failing puzzle 20 of 20 with 19 solved and P70 = 19.
        finishSurvivalRun(
            if (survivalPassAt > 0 && survivalPassed >= survivalPassAt)
                ChessReadinessV3Engine.Verdict.PASS
            else ChessReadinessV3Engine.Verdict.FAIL_STRIKE
        )
    }

    private fun finishSurvivalRun(verdict: ChessReadinessV3Engine.Verdict) {
        handler.removeCallbacks(survivalTickRunnable)
        val elapsed = if (survivalRunStartMs > 0)
            android.os.SystemClock.elapsedRealtime() - survivalRunStartMs else 0L
        // Seed the free-play timer with the gate run's duration so the banner
        // shows the TOTAL survival time (gate + free play) — matching what the
        // linked habit is credited (gate minutes here, free-play minutes on
        // STOP & SAVE).
        survivalFreePlayBaseMs = elapsed
        survivalRunning = false
        survivalArmed = false
        ChessReadinessV3Store.clearPendingSurvival(this)
        ChessReadinessV3Recorder.record(
            context = this,
            sessionStartedAt = survivalSessionStartedAt,
            verdict = verdict,
            target = survivalTarget,
            puzzlesPassed = survivalPassed,
            survivalDurationMs = elapsed,
            reflex = survivalReflex,
            variant = survivalVariant
        )
        // Verdict POPUP: tells the user the outcome and — unless the run put
        // them in RED (kicked out of chess entirely) — offers continuing the
        // survival drill as free play from the attached banner.
        val red = verdict == ChessReadinessV3Engine.Verdict.FAIL_TIMEOUT ||
            verdict == ChessReadinessV3Engine.Verdict.FAIL_REFLEX
        hideSurvivalPanel()
        // The verdict popup is the ONLY route to the free-play timer, so a
        // silent window-add failure would end the run with no feedback at
        // all (the pass is already recorded at this point). runCatching +
        // the isShowing() check detect that; the fallback below still tells
        // the user the outcome and starts the free-play banner directly.
        val popupShown = runCatching {
        val popup = ChessOverlayDialog(this)
        survivalResultPopup = popup
        popup.show()
        // Full run summary (mirrors the v2 result screen): passive Garmin
        // metrics, the reflex test result and the survival gate outcome.
        val todayKey = LocalDate.now().toString()
        val cached = try {
            GarminRepository(this).loadAllCachedData()
        } catch (_: Exception) { emptyMap<GarminType, Map<String, Int>>() }
        // Cached Garmin values are Ints — a "%f" conversion on an Int throws
        // IllegalFormatConversionException, which used to abort the content
        // build half-way and orphan the popup window on screen.
        fun garmin(type: GarminType): String =
            cached[type]?.get(todayKey)?.toString() ?: "—"
        val reflex = survivalReflex
        popup.setContent("♟ Survival Gate", "Run complete — summary") {
            when (verdict) {
                ChessReadinessV3Engine.Verdict.PASS ->
                    stateLabel("GATE PASSED", "#66BB6A")
                ChessReadinessV3Engine.Verdict.FAIL_STRIKE ->
                    stateLabel("GATE FAILED — STRIKE", "#F5B040")
                else ->
                    stateLabel("GATE FAILED — RED", "#EF4444")
            }
            spacer(8)

            // ── Passive (overnight Garmin) ───────────────────────────────
            body("Passive — overnight (Garmin)", color = 0xFF66CCFF.toInt(), size = 13, bold = true)
            keyValue("Sleep score", garmin(GarminType.SLEEP_SCORE))
            keyValue("HRV (RMSSD, last night)", garmin(GarminType.HRV_LAST_NIGHT).let { if (it == "—") it else "$it ms" })
            keyValue("Resting HR", garmin(GarminType.RESTING_HR).let { if (it == "—") it else "$it bpm" })
            keyValue("Stress level", garmin(GarminType.STRESS_LEVEL))
            spacer(6)

            // ── Step 1: reflex test ─────────────────────────────────────
            body("Step 1 · Reflex test (PVT-B)", color = 0xFF66CCFF.toInt(), size = 13, bold = true)
            if (reflex != null) {
                keyValue("Verdict", if (reflex.passed) "PASS ✓" else "FAIL ✕")
                keyValue("Lapses (≥355 ms)", "${reflex.lapses}")
                keyValue("False starts (<100 ms)", "${reflex.falseStarts}")
                reflex.meanRtMs?.let { rt ->
                    keyValue("Average response", "%.0f ms".format(rt))
                    keyValue("Speed score (1000/RT)", "%.2f".format(1000.0 / rt))
                }
            } else {
                keyValue("Verdict", "—")
            }
            spacer(6)

            // ── Step 2: survival gate ───────────────────────────────────
            body("Step 2 · Survival gate", color = 0xFF66CCFF.toInt(), size = 13, bold = true)
            keyValue(
                "Puzzles passed",
                if (survivalPassAt in 1 until survivalTarget)
                    "$survivalPassed — pass bar $survivalPassAt (your P70; guaranteed $survivalTarget)"
                else
                    "$survivalPassed / $survivalTarget"
            )
            keyValue("Run time", formatSurvivalClock(elapsed) + " / 5:00 cap")
            if (survivalPctWon) keyValue("Percentile win", "P70 ✓ secured")
            spacer(6)
            when {
                verdict == ChessReadinessV3Engine.Verdict.PASS ->
                    body("Rated play unlocked. You can also keep drilling survival puzzles as free play — the banner timer counts this free-play session's time.")
                !red ->
                    body("Rated play locked (yellow). You can still continue the survival drill as free play — the banner timer counts this free-play session's time.")
                else ->
                    body("Red mode — chess is locked entirely. Leave chess and rest; the gate can be re-tested after the rest period.", color = 0xFFFFAAAA.toInt())
            }
            if (!red) {
                primaryButton("▶  Continue survival (free play)") {
                    survivalResultPopup = null
                    popup.dismiss()
                    showSurvivalFreePlayPanel()
                }
                textButton("Done — close banner") {
                    survivalResultPopup = null
                    popup.dismiss()
                    hideSurvivalPanel()
                }
            } else {
                primaryButton("Close", danger = true) {
                    survivalResultPopup = null
                    popup.dismiss()
                    hideSurvivalPanel()
                }
            }
        }
        }.isSuccess && survivalResultPopup?.isShowing() == true
        if (!popupShown) {
            // The window may already be on screen with half-built content
            // (setContent threw mid-build) — dismiss it BEFORE dropping the
            // reference, otherwise an unclosable overlay stays stuck on top.
            runCatching { survivalResultPopup?.dismiss() }
            survivalResultPopup = null
            val label = when (verdict) {
                ChessReadinessV3Engine.Verdict.PASS ->
                    "GATE PASSED ✓ — rated play unlocked"
                ChessReadinessV3Engine.Verdict.FAIL_STRIKE ->
                    "GATE FAILED — STRIKE (yellow)"
                else -> "GATE FAILED — RED (rest required)"
            }
            Toast.makeText(this, "♟ Survival Gate: $label", Toast.LENGTH_LONG).show()
            if (!red) showSurvivalFreePlayPanel()
        }
    }

    /**
     * Post-gate free play: the same draggable side banner, reduced to a
     * label + an ever-increasing timer + a STOP & SAVE button. No targets,
     * no strikes, no cap — pure habit-credit drilling time.
     */
    private fun showSurvivalFreePlayPanel() {
        hideSurvivalPanel()
        survivalFreePlay = true
        survivalFreePlayStartMs = android.os.SystemClock.elapsedRealtime()
        // The free-play timer starts from the gate run's duration (seeded in
        // finishSurvivalRun), so it displays the TOTAL survival time. STOP &
        // SAVE still credits ONLY the free-play portion — the gate minutes
        // were already credited when the run was recorded.

        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()
        fun bannerMargin(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dp(); marginEnd = 8.dp() }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE161616.toInt())
                cornerRadius = 12f * density
                setStroke(1, 0xFF334455.toInt())
            }
            setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
        }

        val label = TextView(this).apply {
            text = "♟ SURVIVAL\nfree play · session"
            setTextColor(0xFF66CCFF.toInt())
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        panel.addView(label, bannerMargin())

        survivalStopwatchText = TextView(this).apply {
            text = formatSurvivalStopwatch(survivalFreePlayBaseMs)
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        panel.addView(survivalStopwatchText, bannerMargin())

        panel.addView(
            TextView(this).apply {
                text = "■ STOP & SAVE"
                gravity = Gravity.CENTER
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFFEF9A9A.toInt())
                setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
                background = GradientDrawable().apply {
                    setColor(0xFF7F1D1D.toInt())
                    cornerRadius = 10f * density
                }
                setOnClickListener { stopSurvivalFreePlay() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        survivalPanelView = panel
        // ATTACHED to the bubble's own window (right of it) — same composite
        // as the gate banner, never a separate floating window.
        attachSurvivalBanner(panel)
        handler.removeCallbacks(survivalTickRunnable)
        handler.postDelayed(survivalTickRunnable, 100)
    }

    /** STOP & SAVE: credits the free-play minutes to the linked habit. */
    private fun stopSurvivalFreePlay() {
        if (!survivalFreePlay) return
        val elapsed = android.os.SystemClock.elapsedRealtime() - survivalFreePlayStartMs
        val habit = ChessReadinessV3Store.linkedSurvivalHabit(this)
        survivalFreePlay = false
        hideSurvivalPanel()
        if (habit != null && elapsed > 0) {
            val minutes = kotlin.math.round(elapsed / 60000.0).toInt().coerceAtLeast(1)
            ChessHabitCredit.grant(this, habit, minutes, 1)
            Toast.makeText(this, "♟ +$minutes min → $habit", Toast.LENGTH_SHORT).show()
        }
    }

    /** Removes the survival panel (and stops its tick loop). */
    private fun hideSurvivalPanel() {
        handler.removeCallbacks(survivalTickRunnable)
        survivalRunning = false
        survivalArmed = false
        survivalFreePlay = false
        survivalPanelView = null
        // The banner lives INSIDE the bubble window — detaching = clearing
        // the slot, then re-clamping the (now narrow) window on screen.
        survivalSlot?.apply {
            removeAllViews()
            visibility = View.GONE
        }
        clampBubbleOnScreen()
        survivalCounterText = null
        survivalStopwatchText = null
        survivalTotalText = null
        survivalPctText = null
    }

    /** Current on-screen width of the bubble window (bubble + banner). */
    private fun bubbleWindowWidth(): Int =
        bubbleView?.takeIf { it.measuredWidth > 0 }?.measuredWidth ?: bubbleSize

    /**
     * Attaches the survival banner INSIDE the bubble's window, in the slot to
     * the right of the round bubble — one composite window, so the banner is
     * glued to the bubble (drag / snap / clamp all move them together).
     */
    private fun attachSurvivalBanner(view: View) {
        val slot = survivalSlot ?: return
        slot.removeAllViews()
        slot.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        slot.visibility = View.VISIBLE
        clampBubbleOnScreen()
    }

    /**
     * Keeps the whole composite window (bubble + attached banner) on screen —
     * called whenever the banner is attached/detached so a wide banner never
     * pushes content off the right edge.
     */
    private fun clampBubbleOnScreen() {
        val root = bubbleView ?: return
        root.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = root.measuredWidth.coerceAtLeast(bubbleSize)
        val height = root.measuredHeight.coerceAtLeast(bubbleSize)
        val maxX = (Resources.getSystem().displayMetrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (Resources.getSystem().displayMetrics.heightPixels - height).coerceAtLeast(0)
        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)
        try {
            windowManager.updateViewLayout(root, bubbleParams)
        } catch (e: Exception) { /* view removed */ }
    }

    /**
     * Legacy anchor call from the drag / snap paths — the banner is now part
     * of the bubble window, so "positioning" it just means re-clamping the
     * composite window after the bubble moved.
     */
    private fun positionSurvivalPanel() {
        clampBubbleOnScreen()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Habit picker menu (several habits share one trigger app)
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Shows a small menu anchored next to the bubble listing every habit that
     * shares the current trigger app. Tapping an item starts that habit's
     * timer immediately; tapping anywhere outside dismisses the menu.
     */
    private fun showHabitPickerMenu() {
        if (habitMenuView != null) return
        val habits = triggerHabitNames
        // Show the menu when several habits share the trigger app OR when the
        // Chess Readiness option is available (even with 0 or 1 habits).
        if (habits.size < 2 && !chessReadinessActive) return

        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xEE161616.toInt())
                cornerRadius = 12f * density
                setStroke(1, 0xFF334455.toInt())
            }
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
        }

        // Chess entries (shown when the bubble is over the Chess Readiness
        // app). Listed FIRST so they stay prominent. Exactly ONE of the two
        // is ever offered:
        //  - rated play authorized (Phase 1 green light inside its 60-minute
        //    window, no Yellow/Red audit since) → "Chess Status" popup
        //    (games are audited by sharing them to Tail, not via a form).
        //  - otherwise → "Chess Readiness" (the Phase 1 test), shown again
        //    once the window expired or rated play was revoked.
        if (chessReadinessActive) {
            if (ChessPhase2Store.ratedPlayAuthorized(this)) {
                val statusItem = TextView(this).apply {
                    text = "♟ Chess Status"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                    background = GradientDrawable().apply {
                        setColor(0xFF1A2A3A.toInt())
                        cornerRadius = 8f * density
                        setStroke(1, 0xFF5588AA.toInt())
                    }
                    setOnClickListener {
                        hideHabitPickerMenu()
                        openChessStatus()
                    }
                }
                menu.addView(statusItem, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 6.dp()
                })
            } else {
                // Show a "resume" hint when a step-by-step test is in progress
                val resuming = ChessReadinessStore.loadSession(this) != null
                val chessItem = TextView(this).apply {
                    text = if (resuming) "♟ Chess Readiness ▸ resume" else "♟ Chess Readiness"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                    background = GradientDrawable().apply {
                        setColor(0xFF2A1A3A.toInt())
                        cornerRadius = 8f * density
                        setStroke(1, 0xFF8866CC.toInt())
                    }
                    setOnClickListener {
                        hideHabitPickerMenu()
                        openChessReadiness()
                    }
                }
                menu.addView(chessItem, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 6.dp()
                })
            }

        }

        habits.forEachIndexed { index, habit ->
            val item = TextView(this).apply {
                text = habit
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
                background = GradientDrawable().apply {
                    setColor(0xFF1A2A3A.toInt())
                    cornerRadius = 8f * density
                }
                setOnClickListener {
                    hideHabitPickerMenu()
                    startTimerForHabit(habit)
                }
            }
            menu.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = if (index == habits.lastIndex) 0 else 6.dp()
            })
        }

        // Dismiss when the user taps anywhere outside the menu
        menu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideHabitPickerMenu()
                true
            } else false
        }

        // Measure so the menu can be anchored beside the bubble
        menu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val menuWidth = menu.measuredWidth
        val menuHeight = menu.measuredHeight

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val gap = 8.dp()
            val screenW = Resources.getSystem().displayMetrics.widthPixels
            val screenH = Resources.getSystem().displayMetrics.heightPixels
            // Open on the side of the bubble with more room
            val bubbleCenterX = bubbleParams.x + bubbleSize / 2f
            x = if (bubbleCenterX > screenW / 2f) {
                (bubbleParams.x - menuWidth - gap).coerceAtLeast(gap)
            } else {
                (bubbleParams.x + bubbleSize + gap).coerceAtMost(screenW - menuWidth - gap)
            }
            y = (bubbleParams.y + bubbleSize / 2f - menuHeight / 2f).toInt()
                .coerceIn(gap, (screenH - menuHeight - gap).coerceAtLeast(gap))
        }

        try {
            windowManager.addView(menu, params)
            habitMenuView = menu
        } catch (e: Exception) { /* overlay failed */ }
    }

    /** Removes the habit picker menu overlay. */
    private fun hideHabitPickerMenu() {
        habitMenuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        habitMenuView = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Chess Readiness overlay dialogs
    // ──────────────────────────────────────────────────────────────────────

    // Shown directly through the WindowManager ON TOP of the chess app — no
    // activity is started, so the chess app stays the focused, dominant app;
    // closing a dialog hands focus straight back to it.
    private var chessReadinessOverlay: ChessReadinessOverlay? = null
    private var chessReadinessV2Overlay: ChessReadinessV2Overlay? = null
    private var chessReadinessV3Overlay: ChessReadinessV3Overlay? = null
    private var chessStatusOverlay: ChessStatusOverlay? = null
    private var chessPuzzleRushOverlay: ChessPuzzleRushOverlay? = null

    /**
     * Shows the Phase 1 readiness wizard as a floating overlay dialog.
     * Branches on the settings toggle: v1 keeps the original diagnostic,
     * v2 opens the neurobiological gate wizard (HRV/RHR Z-scores, PVT-B,
     * ACWR). Both record into the same shared history.
     */
    private fun openChessReadiness() {
        try {
            chessStatusOverlay?.dismiss()
            chessStatusOverlay = null
            chessReadinessOverlay?.dismiss()
            chessReadinessOverlay = null
            chessReadinessV2Overlay?.dismiss()
            chessReadinessV2Overlay = null
            chessReadinessV3Overlay?.dismiss()
            chessReadinessV3Overlay = null
            when {
                ChessReadinessV2Store.isV3(this) ->
                    chessReadinessV3Overlay = ChessReadinessV3Overlay(this) {
                        armSurvivalPanel()
                    }.also { it.show() }
                ChessReadinessV2Store.isV2(this) ->
                    chessReadinessV2Overlay = ChessReadinessV2Overlay(this).also { it.show() }
                else ->
                    chessReadinessOverlay = ChessReadinessOverlay(this).also { it.show() }
            }
        } catch (e: Exception) { /* never crash the bubble */ }
    }

    /** Shows the chess status popup as a floating overlay dialog. */
    private fun openChessStatus() {
        try {
            chessReadinessOverlay?.dismiss()
            chessReadinessOverlay = null
            chessReadinessV2Overlay?.dismiss()
            chessReadinessV2Overlay = null
            chessReadinessV3Overlay?.dismiss()
            chessReadinessV3Overlay = null
            chessStatusOverlay?.dismiss()
            chessStatusOverlay = ChessStatusOverlay(this).also { it.show() }
        } catch (e: Exception) { /* never crash the bubble */ }
    }

    /** Shows the Puzzle Rush end-of-session report overlay dialog. */
    private fun openPuzzleRushReport() {
        try {
            chessReadinessOverlay?.dismiss()
            chessReadinessOverlay = null
            chessReadinessV2Overlay?.dismiss()
            chessReadinessV2Overlay = null
            chessReadinessV3Overlay?.dismiss()
            chessReadinessV3Overlay = null
            chessStatusOverlay?.dismiss()
            chessStatusOverlay = null
            chessPuzzleRushOverlay?.dismiss()
            chessPuzzleRushOverlay = ChessPuzzleRushOverlay(this).also { it.show() }
        } catch (e: Exception) { /* never crash the bubble */ }
    }

    /** Removes any open chess overlay dialog (e.g. when the service dies). */
    private fun dismissChessOverlays() {
        try { chessReadinessOverlay?.dismiss() } catch (_: Exception) {}
        try { chessReadinessV2Overlay?.dismiss() } catch (_: Exception) {}
        try { chessReadinessV3Overlay?.dismiss() } catch (_: Exception) {}
        try { chessStatusOverlay?.dismiss() } catch (_: Exception) {}
        try { chessPuzzleRushOverlay?.dismiss() } catch (_: Exception) {}
        chessReadinessOverlay = null
        chessReadinessV2Overlay = null
        chessReadinessV3Overlay = null
        chessStatusOverlay = null
        chessPuzzleRushOverlay = null
    }

    /** Starts the timer for [habit] and updates the bubble visuals. */
    private fun startTimerForHabit(habit: String) {
        triggerHabitName = habit
        WidgetTimerStore.startTimer(this, habit)
        setBubbleRunningVisuals(running = true)
        showTimerChip()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Increment flash message
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Shows a brief flash banner at the top of the screen summarising what
     * the finished timer session recorded in Tail: habit name, minutes added
     * and the day's new minute total, and the session added / new total.
     */
    private fun showIncrementFlash(
        habit: String,
        addedMinutes: Int,
        totalMinutes: Int,
        addedSessions: Int,
        totalSessions: Int
    ) {
        hideIncrementFlash()

        val density = resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val title = TextView(this).apply {
            text = "✓ $habit"
            textSize = 15f
            setTextColor(0xFF88FF88.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val minutesLine = TextView(this).apply {
            text = "⏱ +$addedMinutes min → $totalMinutes min today"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val sessionsLine = TextView(this).apply {
            text = "● +$addedSessions session → $totalSessions today"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val flash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dp(), 12.dp(), 18.dp(), 14.dp())
            background = GradientDrawable().apply {
                setColor(0xEE161616.toInt())
                cornerRadius = 16f * density
                setStroke(1, 0xFF334455.toInt())
            }
            addView(title, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(minutesLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(sessionsLine, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        flash.alpha = 0f

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 64.dp() // below the status bar
        }

        flashView = flash
        try {
            windowManager.addView(flash, params)
            flash.animate().alpha(1f).setDuration(150).start()
            handler.postDelayed(flashDismissRunnable, 3000L)
        } catch (e: Exception) {
            flashView = null
        }
    }

    /** Fades out and removes the increment flash banner, if showing. */
    private fun hideIncrementFlash() {
        handler.removeCallbacks(flashDismissRunnable)
        flashView?.let { v ->
            v.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    try {
                        windowManager.removeView(v)
                    } catch (e: Exception) { /* already removed */ }
                }
                .start()
        }
        flashView = null
    }

    /**
     * Stops the habit's timer, hides the live display and — if at least a
     * (rounded) minute elapsed — writes the minutes to the habit's "minutes"
     * secondary value (`secondary_value:<habit>` in habitsdb.txt).
     *
     * When the habit is the linked Puzzle Rush habit, the timer is an
     * official Puzzle Rush run: the session's times are parked as a due
     * report and the result prompt opens on top of the chess app.
     */
    private fun stopTimerAndRecord(habit: String, onFinished: (() -> Unit)? = null) {
        // Captured BEFORE the stop clears the persisted start timestamp.
        val rushStartMillis = WidgetTimerStore.timerStartMillis(this, habit)
        val minutes = WidgetTimerStore.stopTimerAndComputeMinutes(this, habit)
        hideTimerChip()
        setBubbleRunningVisuals(running = false)
        if (minutes > 0) {
            maybePromptPuzzleRush(habit, rushStartMillis)
            writeMinutesToHabit(habit, minutes, onFinished)
        } else {
            Toast.makeText(
                this, "Timer stopped — under a minute, nothing recorded", Toast.LENGTH_SHORT
            ).show()
            onFinished?.invoke()
        }
    }

    /**
     * Parks a due Puzzle Rush report and opens the result prompt when the
     * timer that just stopped belonged to the linked Puzzle Rush habit.
     * Best-effort: any failure here must never break the minutes write.
     */
    private fun maybePromptPuzzleRush(habit: String, startedAt: Long) {
        try {
            if (startedAt <= 0L) return
            val rushHabit = ChessReadinessStore.linkedRushHabit(this).trim()
            if (rushHabit.isEmpty() || habit != rushHabit) return
            ChessPuzzleRushStore.savePending(this, startedAt, System.currentTimeMillis())
            openPuzzleRushReport()
        } catch (_: Exception) { /* never crash the timer stop */ }
    }

    /**
     * The trigger app left the foreground (closed or switched away from).
     * Any still-running timer of the bubble's trigger habits is stopped and
     * recorded — exactly as if the user had tapped the bubble — and then the
     * bubble hides itself. The self-stop is DELAYED past the increment flash:
     * [onDestroy] tears the flash down, and stopping immediately would
     * swallow the "session recorded" confirmation.
     */
    private fun handleTriggerAppLeft() {
        val runningHabit = triggerHabitNames.firstOrNull {
            WidgetTimerStore.isTimerRunning(this, it)
        }
        if (runningHabit == null) {
            stopSelf()
            return
        }
        // Record first, then stop the service once the write has landed —
        // stopSelf() cancels serviceScope in onDestroy, killing an in-flight
        // write (and with it the recorded minutes).
        stopTimerAndRecord(runningHabit) { scheduleLingerStop() }
    }

    /**
     * Stops the service once the increment flash has finished showing
     * (skipped if the bubble was re-started in the meantime).
     */
    private fun scheduleLingerStop() {
        lingerStopGeneration = startGeneration
        handler.removeCallbacks(delayedStopRunnable)
        handler.postDelayed(delayedStopRunnable, LINGER_STOP_DELAY_MS)
    }

    /**
     * Records a finished timer session ATOMICALLY: adds [minutes] to the
     * habit's minutes secondary value AND +1 session to the habit's own slot
     * in a single read-modify-write, then refreshes UI surfaces and shows
     * the increment flash. (Two separate writes allowed a concurrent
     * reader/writer — e.g. the app starting up — to interleave between them
     * and lose the session increment.)
     */
    private fun writeMinutesToHabit(habit: String, minutes: Int, onFinished: (() -> Unit)? = null) {
        // persistenceScope (NOT serviceScope): the write must survive a
        // stopSelf() that lands while it is still in flight.
        persistenceScope.launch {
            try {
                val settings = settingsRepo.settingsFlow.first()
                val uriStr = settings.fileUri
                if (uriStr.isEmpty()) {
                    Toast.makeText(
                        this@FloatingBubbleService,
                        "No habits file configured — minutes not saved",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                val db = habitsRepo.incrementHabitWithMinutes(
                    Uri.parse(uriStr), applicationContext, habit, minutes, 1
                )

                // The save HAS landed here. Everything below is UI refresh:
                // if the service is being torn down concurrently (bubble
                // dismissed, trigger app left), these may fail — and that
                // must NOT surface as "Failed to save minutes".
                try {
                    HabitIncrementBus.emit(habit)
                    HabitListWidgetProvider.refreshAll(applicationContext)

                    // Day totals straight from the just-saved state
                    val today = dateString(LocalDate.now())
                    val totalMinutes = db[secondaryValueKey(habit)]?.get(today) ?: minutes
                    val totalSessions = db[habit]?.get(today) ?: 1
                    showIncrementFlash(habit, minutes, totalMinutes, 1, totalSessions)
                } catch (e: Exception) { /* UI-only — save already succeeded */ }
            } catch (e: Exception) {
                Toast.makeText(this@FloatingBubbleService, "Failed to save minutes: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                onFinished?.invoke()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Cleanup
    // ──────────────────────────────────────────────────────────────────────

    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        bubbleView = null
        bubbleRingView = null
        // Closing the bubble also removes the timer chip
        hideTimerChip()
    }

    private fun removeDismissZone() {
        dismissZoneView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { /* already removed */ }
        }
        dismissZoneView = null
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Notification (required for foreground service)
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Floating Bubble",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while the Tail floating bubble is active"
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_BUBBLE
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tail Bubble")
            .setContentText("Tap: start/stop timer · Long-press: open Tail · Drag to ✕ to dismiss")
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Remove Bubble",
                    stopPendingIntent
                ).build()
            )
            .build()
    }
}

/**
 * Persists the bubble's last start configuration (trigger habits + chess
 * readiness flag) so a START_STICKY restart after a process kill restores
 * the bubble exactly as it was — the widget must never stay gone.
 */
private object BubbleStateStore {
    private const val PREFS = "tail_floating_bubble"
    private const val KEY_HABITS = "last_habits"
    private const val KEY_CHESS = "last_chess_readiness"

    /** Separator that cannot appear inside a habit name. */
    private const val SEP = "\u0000"

    data class State(val habitNames: List<String>, val chessReadiness: Boolean)

    fun save(context: Context, habits: List<String>, chess: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HABITS, habits.joinToString(SEP))
            .putBoolean(KEY_CHESS, chess)
            .apply()
    }

    fun load(context: Context): State {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val habits = prefs.getString(KEY_HABITS, null)
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return State(habits, prefs.getBoolean(KEY_CHESS, false))
    }
}
