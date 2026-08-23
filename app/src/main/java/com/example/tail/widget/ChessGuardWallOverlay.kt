package com.example.tail.widget

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Guard — full-screen lock wall rendered as a SYSTEM_ALERT_WINDOW
 *  overlay
 * ════════════════════════════════════════════════════════════════════════
 *
 *  Why an overlay and not (only) the [ChessGuardLockActivity]: Android
 *  10+ background-activity-launch (BAL) restrictions can silently refuse
 *  `startActivity` calls made from a service context — observed in the
 *  wild on One UI even with the overlay grant held — which left the user
 *  with a chess app that "opens and immediately closes" and NO
 *  explanation of when the next test becomes possible. Overlay windows
 *  added through the WindowManager are not subject to BAL, and Tail
 *  already holds the "Display over other apps" grant for the floating
 *  bubble, so this path is reliable. The activity stays as the fallback
 *  for the (unlikely) case where the overlay grant is missing.
 *
 *  Same jobs as the activity wall:
 *   - say WHY the app is blocked ([ChessEnforcementPolicy.Decision.Block.message]);
 *   - count down live to the moment the next test becomes possible
 *     ("Can re-test in 43 min (12:53)" / "Penalty ends in …");
 *   - dissolve the instant the policy turns into an Allow (re-checked
 *     every second — e.g. the rest period just ended and the trust
 *     window opened).
 *
 *  [showWarning] is the NON-blocking sibling for YELLOW sessions: the app
 *  stays usable for casual play (unrated / puzzles), but a full-screen
 *  yellow notice spells out exactly what a rated game would cost — the
 *  automatic 24-hour lockout.
 */
object ChessGuardWallOverlay {

    private val handler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var appContext: Context? = null
    private var containerView: LinearLayout? = null
    private var titleView: TextView? = null
    private var messageView: TextView? = null
    private var countdownView: TextView? = null

    private var warningWm: WindowManager? = null
    private var warningView: LinearLayout? = null

    private val tick = object : Runnable {
        override fun run() {
            val context = appContext
            if (context == null || containerView == null) return
            val decision = try {
                ChessEnforcementPolicy.evaluateNow(context)
            } catch (_: Exception) {
                null
            }
            when (decision) {
                null -> dismiss()
                is ChessEnforcementPolicy.Decision.Allow -> dismiss()
                is ChessEnforcementPolicy.Decision.Block -> {
                    render(decision)
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    /**
     * Shows (or refreshes) the wall. Returns false when the overlay
     * grant is missing or the window cannot be added — callers should
     * fall back to [ChessGuardLockActivity] in that case.
     */
    fun show(context: Context, block: ChessEnforcementPolicy.Decision.Block): Boolean {
        if (!Settings.canDrawOverlays(context)) return false

        // Already up — just refresh with the freshest verdict.
        if (containerView != null) {
            render(block)
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 1000L)
            return true
        }

        val density = context.resources.displayMetrics.density
        val pad = (density * 24).toInt()

        val title = TextView(context).apply {
            text = "♟ CHESS LOCKED"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
        }
        val message = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#E5E7EB"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        val countdown = TextView(context).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        val close = Button(context).apply {
            text = "Close"
            setOnClickListener { dismiss() }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F1111111"))
            setPadding(pad, pad, pad, pad)
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                countdown,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                close,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad; gravity = Gravity.CENTER_HORIZONTAL }
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            windowManager.addView(root, params)
            wm = windowManager
            appContext = context.applicationContext
            containerView = root
            titleView = title
            messageView = message
            countdownView = countdown
            render(block)
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 1000L)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Full-screen YELLOW entry warning — NOT a block. Shown when the chess
     * app opens during a YELLOW session: casual play is allowed, but the
     * message spells out that one rated game triggers the automatic
     * 24-hour lockout. Dismissable ("Got it" button); no live tick — the
     * underlying state is an Allow, so there is nothing to count down to.
     */
    fun showWarning(context: Context): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (warningView != null) return true // already up

        val density = context.resources.displayMetrics.density
        val pad = (density * 24).toInt()

        val title = TextView(context).apply {
            text = "⚠️ YELLOW — CASUAL PLAY ONLY"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EAB308"))
            gravity = Gravity.CENTER
        }
        val message = TextView(context).apply {
            text =
                "Your last readiness test was YELLOW, so the chess app is open " +
                    "for UNRATED games, bots and puzzles only.\n\n" +
                    "♟ RATED GAMES ARE LOCKED.\n\n" +
                    "If a rated game is detected in this state, Chess Guard " +
                    "automatically locks the ENTIRE app — the next readiness " +
                    "test included — for 24 HOURS.\n\n" +
                    "Take a new readiness test to re-earn GREEN, or keep it " +
                    "casual."
            textSize = 16f
            setTextColor(Color.parseColor("#E5E7EB"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        val gotIt = Button(context).apply {
            text = "Got it — casual only"
            setOnClickListener { dismissWarning() }
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F1140F06"))
            setPadding(pad, pad, pad, pad)
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                message,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                gotIt,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad; gravity = Gravity.CENTER_HORIZONTAL }
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return try {
            windowManager.addView(root, params)
            warningWm = windowManager
            warningView = root
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Removes the YELLOW warning (Got it button or service gone). */
    fun dismissWarning() {
        val view = warningView
        warningView = null
        if (view != null) {
            try {
                warningWm?.removeView(view)
            } catch (_: Exception) {
                // Window already gone — nothing to clean up.
            }
        }
        warningWm = null
    }

    /** Removes the wall (Close button, policy turned Allow, or service gone). */
    fun dismiss() {
        handler.removeCallbacks(tick)
        val view = containerView
        containerView = null
        titleView = null
        messageView = null
        countdownView = null
        appContext = null
        if (view != null) {
            try {
                wm?.removeView(view)
            } catch (_: Exception) {
                // Window already gone — nothing to clean up.
            }
        }
        wm = null
    }

    private fun render(block: ChessEnforcementPolicy.Decision.Block) {
        val title = titleView ?: return
        val message = messageView ?: return
        val countdown = countdownView ?: return

        message.text = block.message
        val yellow = block.reason == ChessEnforcementPolicy.Reason.YELLOW_SESSION
        title.setTextColor(Color.parseColor(if (yellow) "#EAB308" else "#EF4444"))

        if (block.retryAt > 0L) {
            val remaining = block.retryAt - System.currentTimeMillis()
            val label = if (block.reason == ChessEnforcementPolicy.Reason.PENALTY) {
                "Penalty ends"
            } else {
                "Can re-test"
            }
            countdown.text = if (remaining > 0) {
                val totalMin = ((remaining + 59999L) / 60000L).toInt().coerceAtLeast(1)
                val h = totalMin / 60
                val m = totalMin % 60
                val wait = if (h > 0) "$h h $m min" else "$m min"
                val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(block.retryAt))
                "$label in $wait ($at)"
            } else {
                "Unlocks any moment now…"
            }
            countdown.visibility = View.VISIBLE
        } else {
            countdown.visibility = View.GONE
        }
    }
}
