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
import android.os.VibrationEffect
import android.os.Vibrator
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
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
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
 *
 * Requires [android.Manifest.permission.SYSTEM_ALERT_WINDOW] ("draw over other apps").
 */
class FloatingBubbleService : Service() {

    companion object {
        private const val CHANNEL_ID = "tail_floating_bubble"
        private const val NOTIFICATION_ID = 9911

        /** dp → px helper */
        private fun Int.dp(resources: Resources): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                this.toFloat(),
                resources.displayMetrics
            ).toInt()

        /** Action to stop the bubble from anywhere (e.g. notification action). */
        const val ACTION_STOP_BUBBLE = "com.example.tail.widget.STOP_BUBBLE"

        /** Intent extra: name of the habit whose trigger app opened the bubble. */
        const val EXTRA_HABIT_NAME = "habit_name"

        /** Intent extra: names of ALL habits sharing the trigger app (picker menu). */
        const val EXTRA_HABIT_NAMES = "habit_names"

        /** Intent extra: true when the trigger app is the Chess Readiness app. */
        const val EXTRA_CHESS_READINESS = "chess_readiness"
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

    // ── Habit picker menu (several habits share one trigger app) ──────────
    private var habitMenuView: LinearLayout? = null

    // ── Increment flash message (shown after a session is recorded) ──────
    private var flashView: LinearLayout? = null
    private val flashDismissRunnable = Runnable { hideIncrementFlash() }
    private val handler = Handler(Looper.getMainLooper())

    // ── Long-press detection ──────────────────────────────────────────────
    private var longPressConsumed = false
    private val longPressRunnable = Runnable { onBubbleLongPressed() }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val habitsRepo by lazy { HabitsRepository() }

    // Bubble layout params (positioned on screen)
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Bubble size in px
    private val bubbleSize by lazy { 56.dp(resources) }
    private val dismissZoneSize by lazy { 72.dp(resources) }
    private val timerChipHeight by lazy { 30.dp(resources) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_BUBBLE -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

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

        if (bubbleView == null) {
            showBubble()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        removeDismissZone()
        hideTimerChip()
        hideHabitPickerMenu()
        hideIncrementFlash()
        serviceScope.cancel()
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Bubble setup
    // ──────────────────────────────────────────────────────────────────────

    private fun showBubble() {
        val layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
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
        bubbleView = container

        try {
            windowManager.addView(container, bubbleParams)
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

                        // Clamp to screen bounds
                        val maxX = Resources.getSystem().displayMetrics.widthPixels - bubbleSize
                        val maxY = Resources.getSystem().displayMetrics.heightPixels - bubbleSize
                        bubbleParams.x = bubbleParams.x.coerceIn(0, maxX)
                        bubbleParams.y = bubbleParams.y.coerceIn(0, maxY)

                        try {
                            windowManager.updateViewLayout(bubbleView, bubbleParams)
                        } catch (e: Exception) { /* view removed */ }

                        // Keep the timer chip glued above the bubble
                        positionTimerChip()

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
                screenWidth - bubbleSize - margin
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

        // Chess Readiness entry (shown when the bubble is over the
        // Chess Readiness app). Listed FIRST so it stays prominent.
        if (chessReadinessActive) {
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

    /** Launches the Chess Readiness diagnostic activity from the overlay. */
    private fun openChessReadiness() {
        val intent = Intent(this, ChessReadinessActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) { /* activity unavailable */ }
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
     */
    private fun stopTimerAndRecord(habit: String, onFinished: (() -> Unit)? = null) {
        val minutes = WidgetTimerStore.stopTimerAndComputeMinutes(this, habit)
        hideTimerChip()
        setBubbleRunningVisuals(running = false)
        if (minutes > 0) {
            writeMinutesToHabit(habit, minutes, onFinished)
        } else {
            Toast.makeText(
                this, "Timer stopped — under a minute, nothing recorded", Toast.LENGTH_SHORT
            ).show()
            onFinished?.invoke()
        }
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
        serviceScope.launch {
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

                HabitIncrementBus.emit(habit)
                HabitListWidgetProvider.refreshAll(applicationContext)

                // Day totals straight from the just-saved state
                val today = dateString(LocalDate.now())
                val totalMinutes = db[secondaryValueKey(habit)]?.get(today) ?: minutes
                val totalSessions = db[habit]?.get(today) ?: 1
                showIncrementFlash(habit, minutes, totalMinutes, 1, totalSessions)
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
            .setSmallIcon(R.drawable.ic_bubble_notification)
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
