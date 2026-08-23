package com.example.tail.widget

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The full-screen wall [ChessGuardService] throws up when the chess app is
 * opened while blocked. Deliberately plain (programmatic views, no Compose):
 * it is launched from an accessibility callback, must inflate instantly,
 * and carries exactly four jobs:
 *
 *  - say WHY the app is blocked ([ChessEnforcementPolicy.Decision.Block.message]);
 *  - show WHEN the next test becomes possible (live countdown to
 *    `retryAt`, re-rendered every second);
 *  - dismiss itself the moment the policy turns into an Allow — e.g. the
 *    rest period just ended and the trust window opened, or an in-progress
 *    test produced a GREEN result. The user lands on the home screen and
 *    simply re-opens the chess app to take the test.
 */
class ChessGuardLockActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var titleView: TextView
    private lateinit var messageView: TextView
    private lateinit var countdownView: TextView

    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val decision = try {
                ChessEnforcementPolicy.evaluateNow(applicationContext)
            } catch (_: Exception) {
                null
            }
            when (decision) {
                null -> finish()
                is ChessEnforcementPolicy.Decision.Allow -> finish()
                is ChessEnforcementPolicy.Decision.Block -> {
                    render(decision)
                    handler.postDelayed(this, 1000L)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        handler.post(tick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Another guard kick while already showing — just refresh now.
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 24).toInt()

        titleView = TextView(this).apply {
            text = "♟ CHESS LOCKED"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER
        }
        messageView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#E5E7EB"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        countdownView = TextView(this).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111111"))
            setPadding(pad, pad, pad, pad)
            addView(
                titleView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                messageView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                countdownView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                closeButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = pad / 4; gravity = Gravity.CENTER_HORIZONTAL }
            )
        }
        setContentView(root)
    }

    private fun render(block: ChessEnforcementPolicy.Decision.Block) {
        messageView.text = block.message
        val yellow = block.reason == ChessEnforcementPolicy.Reason.YELLOW_SESSION
        titleView.setTextColor(Color.parseColor(if (yellow) "#EAB308" else "#EF4444"))

        if (block.retryAt > 0L) {
            val remaining = block.retryAt - System.currentTimeMillis()
            val label = if (block.reason == ChessEnforcementPolicy.Reason.PENALTY) {
                "Penalty ends"
            } else {
                "Can re-test"
            }
            countdownView.text = if (remaining > 0) {
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
            countdownView.visibility = android.view.View.VISIBLE
        } else {
            countdownView.visibility = android.view.View.GONE
        }
    }
}
