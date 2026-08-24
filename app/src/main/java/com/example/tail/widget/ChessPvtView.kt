package com.example.tail.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Random
import kotlin.math.min

/**
 * The 3-minute Brief Psychomotor Vigilance Task (PVT-B) surface — a custom
 * view rendered inside the v2 readiness overlay dialog.
 *
 * Protocol (per the research framework):
 *  - blank "wait" state; at random inter-stimulus intervals of strictly
 *    2,000–10,000 ms a high-contrast digital millisecond counter appears
 *    and starts counting up;
 *  - the user taps anywhere on the surface as fast as possible;
 *  - a tap BEFORE the stimulus (or within 100 ms of it) is a FALSE START —
 *    recorded with no valid RT;
 *  - a response ≥ 355 ms is a LAPSE (classified by the engine);
 *  - the run lasts exactly [ChessReadinessV2Engine.PVT_DURATION_MS].
 *
 * Timing uses [SystemClock.elapsedRealtime] (monotonic, sleep-inclusive)
 * for millisecond-accurate reaction times.
 */
class ChessPvtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Callback for every raw response (null RT = false start). */
    var onSample: ((ChessReadinessV2Engine.PvtSample) -> Unit)? = null

    /** Callback when the 3-minute run completes. */
    var onComplete: ((List<ChessReadinessV2Engine.PvtSample>) -> Unit)? = null

    /** Per-second progress callback (remaining ms) — drives the countdown UI. */
    var onTick: ((remainingMs: Long) -> Unit)? = null

    private enum class State { IDLE, WAIT, STIM, FEEDBACK, DONE }

    private val handler = Handler(Looper.getMainLooper())
    private val random = Random()

    private var state = State.IDLE
    private var runStartMs = 0L      // elapsedRealtime when the run started
    private var stimStartMs = 0L     // elapsedRealtime when the counter appeared
    private var stimScheduledAtMs = 0L // wall time the stimulus is due (for false starts)
    private val samples = ArrayList<ChessReadinessV2Engine.PvtSample>()

    // ── Child views ────────────────────────────────────────────────────────

    private val statusText: TextView
    private val counterText: TextView
    private val feedbackText: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            setColor(0xFF0A0A0A.toInt())
            cornerRadius = 14f * resources.displayMetrics.density
            setStroke(1 * resources.displayMetrics.density.toInt(), 0xFF334455.toInt())
        }

        statusText = TextView(context).apply {
            text = "Press START when ready"
            setTextColor(0xFF999999.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        counterText = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 56f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = (70 * resources.displayMetrics.density).toInt()
        }
        feedbackText = TextView(context).apply {
            text = ""
            setTextColor(0xFF66CCFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = (22 * resources.displayMetrics.density).toInt()
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(counterText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(feedbackText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // The whole surface is the response button once the run is live.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                handleTap()
                true
            } else false
        }
    }

    // ── Run control ────────────────────────────────────────────────────────

    /** Starts the 3-minute run (only from IDLE/DONE — restarts reset everything). */
    fun startRun() {
        handler.removeCallbacksAndMessages(null)
        samples.clear()
        state = State.WAIT
        runStartMs = SystemClock.elapsedRealtime()
        counterText.text = ""
        feedbackText.text = ""
        statusText.text = "Wait for the counter — then TAP"
        scheduleStimulus()
        handler.postDelayed(::tickSecond, 1000)
    }

    /** Aborts the run (overlay dismissed) — no result is reported. */
    fun abortRun() {
        handler.removeCallbacksAndMessages(null)
        state = State.IDLE
    }

    private fun remainingMs(): Long =
        ChessReadinessV2Engine.PVT_DURATION_MS - (SystemClock.elapsedRealtime() - runStartMs)

    /** Per-second progress: finishes the run when the 3 minutes elapse. */
    private fun tickSecond() {
        val remaining = remainingMs()
        onTick?.invoke(remaining)
        if (remaining <= 0) {
            finishRun()
        } else {
            // If we're mid-wait and the clock runs out mid-ISI, end now.
            handler.postDelayed(::tickSecond, min(1000L, remaining + 1))
        }
    }

    private fun scheduleStimulus() {
        val isi = ChessReadinessV2Engine.ISI_MIN_MS +
            random.nextInt((ChessReadinessV2Engine.ISI_MAX_MS - ChessReadinessV2Engine.ISI_MIN_MS + 1).toInt())
        stimScheduledAtMs = SystemClock.elapsedRealtime() + isi
        handler.postDelayed(::showStimulus, isi)
    }

    private fun showStimulus() {
        if (state != State.WAIT) return
        state = State.STIM
        stimStartMs = SystemClock.elapsedRealtime()
        statusText.text = "TAP!"
        counterText.text = "0"
        handler.post(::updateCounter)
    }

    /** Animates the millisecond counter while the stimulus is visible. */
    private fun updateCounter() {
        if (state != State.STIM) return
        counterText.text = ((SystemClock.elapsedRealtime() - stimStartMs).toInt() / 10 * 10).toString()
        handler.post(this::updateCounter)
    }

    private fun handleTap() {
        when (state) {
            State.WAIT -> {
                // Anticipatory response before the counter appeared.
                handler.removeCallbacks(::showStimulus)
                state = State.FEEDBACK
                recordSample(ChessReadinessV2Engine.PvtSample(rtMs = null))
                feedbackText.text = "FALSE START — too early"
                feedbackText.setTextColor(0xFFEF9A9A.toInt())
                afterFeedback()
            }
            State.STIM -> {
                handler.removeCallbacks(::updateCounter)
                state = State.FEEDBACK
                val rt = (SystemClock.elapsedRealtime() - stimStartMs).toInt()
                recordSample(ChessReadinessV2Engine.PvtSample(rtMs = rt))
                val cls = ChessReadinessV2Engine.classifyPvtResponse(rt)
                when (cls) {
                    ChessReadinessV2Engine.PvtClassification.FalseStart -> {
                        feedbackText.text = "$rt ms — FALSE START"
                        feedbackText.setTextColor(0xFFEF9A9A.toInt())
                    }
                    ChessReadinessV2Engine.PvtClassification.Lapse -> {
                        feedbackText.text = "$rt ms — LAPSE"
                        feedbackText.setTextColor(0xFFEAB308.toInt())
                    }
                    else -> {
                        feedbackText.text = "$rt ms"
                        feedbackText.setTextColor(0xFF66BB6A.toInt())
                    }
                }
                counterText.text = ""
                afterFeedback()
            }
            else -> { /* taps between trials / after the end are ignored */ }
        }
    }

    private fun recordSample(sample: ChessReadinessV2Engine.PvtSample) {
        samples.add(sample)
        onSample?.invoke(sample)
    }

    /** Brief feedback pause, then the next random ISI (if time remains). */
    private fun afterFeedback() {
        statusText.text = "Wait for the counter…"
        counterText.text = ""
        handler.postDelayed({
            if (remainingMs() <= 0) {
                finishRun()
            } else {
                state = State.WAIT
                scheduleStimulus()
            }
        }, 900)
    }

    private fun finishRun() {
        handler.removeCallbacksAndMessages(null)
        state = State.DONE
        statusText.text = "Run complete"
        counterText.text = ""
        feedbackText.text = ""
        onComplete?.invoke(samples.toList())
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

}
