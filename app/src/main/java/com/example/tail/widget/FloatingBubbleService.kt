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
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.tail.R
import kotlin.math.hypot

/**
 * Foreground service that displays a draggable floating bubble over other apps.
 *
 * The bubble shows the Tail app icon and can be:
 *  - Dragged anywhere on the screen
 *  - Dismissed by dragging to the X zone at the bottom center
 *  - Tapped (placeholder for future habit-tracking features)
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
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var dismissZoneView: View? = null

    // Bubble layout params (positioned on screen)
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // Bubble size in px
    private val bubbleSize by lazy { 56.dp(resources) }
    private val dismissZoneSize by lazy { 72.dp(resources) }

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

        if (bubbleView == null) {
            showBubble()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        removeBubble()
        removeDismissZone()
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

            // Add a subtle white border ring around the bubble
            val ring = View(this@FloatingBubbleService).apply {
                background = createRingDrawable()
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
        }
    }

    private fun createCircularBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
    }

    private fun createRingDrawable(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(3.dp(resources), Color.argb(80, 0, 0, 0))
            setColor(Color.TRANSPARENT)
        }
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
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    if (!isDragging && hypot(dx, dy) > touchSlop) {
                        isDragging = true
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

                        // Highlight dismiss zone when bubble is near it
                        updateDismissZoneHighlight()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
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
                        // It was a tap — placeholder for future features
                        onBubbleTapped()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
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
     * Called when the bubble is tapped (not dragged).
     * Placeholder for future habit-tracking features.
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
            .setContentText("Floating bubble is active. Drag to ✕ to dismiss.")
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
