package com.example.tail.widget

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.example.tail.data.chess.ChessReadinessEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ♟ Chess Readiness V4 — the GREEN-LIGHT overlay (phone port of TailCue's
 * Readiness panel; spec: mission #1, 2026-10-21).
 *
 * Rendered as a floating overlay dialog by [FloatingBubbleService]:
 *
 *  1. ASSESS — signals are collected from the phone's own sources (sleep
 *     wake-log awake minutes, Garmin HRV cache, coffee habit, yesterday's
 *     rated Blitz from the chess log, wall-clock hour). Missing data NEVER
 *     adds concern.
 *  2. GREEN (concern ≤ 1, the usual case) — INSTANT light, no test. One
 *     tap accepts and authorizes rated play.
 *  3. YELLOW (concern 2) — reflex gate: 5 reaction taps, pass at median
 *     ≤ the personalised threshold (default 320 ms). Pass → green.
 *  4. RED-lean (concern ≥ 3) — strong advisory listing exactly which
 *     signals drove it (with evidence), then either a sized survival
 *     battery (5 puzzles at concern 3, 10 at 4+ — armed into the same
 *     bubble panel the v3 gate uses) or "Play anyway" (override, logged).
 *
 * Every outcome — assessment, gate result, override — is appended to the
 * v4 calibration event log ([ChessReadinessV4Store]) and a granted
 * authorization lands in the SHARED v1 history so Chess Guard, the colour
 * system and the Phase-2 audit pipeline work unchanged.
 */
class ChessReadinessV4Overlay(
    service: android.content.Context,
    /** Invoked when the survival gate is armed — the bubble shows its panel. */
    private val onSurvivalArmed: (() -> Unit)? = null
) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class Phase {
        LOADING, BLOCKED, ASSESS, REFLEX_RUN, RESULT
    }

    private var phase = Phase.LOADING
    private var blockedMessage = ""
    private var blockedRetryAt = 0L

    private var assessment: ChessReadinessV4Engine.Assessment? = null
    private var watch: ChessReadinessV4Engine.SessionWatch? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun show() {
        dialog.show()
        render()
        loadInitial()
    }

    fun dismiss() {
        scope.cancel()
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    private fun loadInitial() {
        scope.launch {
            when (val gate = ChessReadinessEngine.checkGate(
                ChessReadinessStore.loadHistory(context), System.currentTimeMillis()
            )) {
                is ChessReadinessEngine.GateStatus.Blocked -> {
                    blockedMessage = gate.error.message
                    blockedRetryAt = gate.error.retryAt
                    ChessReadinessLogStore.logBlockedAttempt(context, gate.error.message)
                    phase = Phase.BLOCKED
                }
                is ChessReadinessEngine.GateStatus.Allowed -> {
                    val inputs = ChessReadinessV4Signals.collect(context)
                    assessment = ChessReadinessV4Engine.assess(
                        inputs, System.currentTimeMillis()
                    )
                    watch = ChessReadinessV4Engine.sessionWatch(
                        ChessReadinessV4Signals.todayPoints(context)
                    )
                    phase = Phase.ASSESS
                }
            }
            if (dialog.isShowing()) render()
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        when (phase) {
            Phase.LOADING -> dialog.setContent("♟ Green Light", null) {
                body("Assessing state signals…", color = 0xFF999999.toInt())
            }
            Phase.BLOCKED -> renderBlocked()
            Phase.ASSESS -> renderAssessment()
            Phase.REFLEX_RUN -> renderReflexRun()
            Phase.RESULT -> renderResult()
        }
    }

    private fun renderBlocked() {
        dialog.setContent("♟ Green Light", "Assessment unavailable") {
            body(blockedMessage)
            if (blockedRetryAt > 0L) {
                spacer(8)
                body(
                    "Next check: ${formatRetryClock(blockedRetryAt)} " +
                        "(in ${formatRetryRemaining(blockedRetryAt)})",
                    color = 0xFF66CCFF.toInt(), size = 15, bold = true
                )
            }
            primaryButton("Close") { dismiss() }
        }
    }

    /** Shared transparency block: the signals that drove the decision. */
    private fun ChessOverlayDialog.OverlayScope.renderSignals(a: ChessReadinessV4Engine.Assessment) {
        body("Signals (concern ${a.concern})", bold = true, size = 12)
        if (a.signals.isEmpty()) {
            hint("None — no negative state signals. No signal = no concern.")
        } else {
            a.signals.forEach { s ->
                bullet(
                    "+${s.weight} ${s.label}" + if (s.tiebreaker) " (tiebreaker)" else "",
                    0xFFF5B040.toInt()
                )
                hint("· ${s.evidence}")
            }
        }
        spacer(8)
    }

    private fun ChessOverlayDialog.OverlayScope.renderSessionWatch() {
        val w = watch ?: return
        keyValue("Games today", "${w.gamesToday} · score %.0f%%".format(w.scorePct))
        keyValue("Loss streak", "${w.lossStreak}")
        w.warnings.forEach { warn ->
            bullet("⚠ ${warn.msg}", 0xFFEF9A9A.toInt())
        }
        spacer(8)
    }

    private fun renderAssessment() {
        val a = assessment ?: return dismiss()
        when (a.level) {
            ChessReadinessV4Engine.Level.GREEN -> dialog.setContent(
                "♟ Green Light", "Level · GREEN"
            ) {
                stateLabel("GREEN — PLAY", "#22C55E")
                spacer(6)
                body(a.level.headline, color = 0xFF66BB6A.toInt(), bold = true)
                spacer(8)
                renderSignals(a)
                renderSessionWatch()
                hint("Missing data never adds concern — this is the default outcome.")
                primaryButton("✓ Accept — play") {
                    ChessReadinessV4Recorder.record(
                        context, a, gateType = "", gatePassed = null,
                        reflexMs = null, overridden = false
                    )
                    dismiss()
                }
                textButton("Close without accepting") { dismiss() }
            }

            ChessReadinessV4Engine.Level.YELLOW -> dialog.setContent(
                "♟ Green Light", "Level · YELLOW (concern ${a.concern})"
            ) {
                stateLabel("QUICK CHECK, THEN PLAY", "#EAB308")
                spacer(6)
                body(a.level.headline)
                spacer(8)
                renderSignals(a)
                renderSessionWatch()
                val passMs = ChessReadinessV4Store.reflexPassMs(context)
                body("Reflex gate — ${ChessReadinessV4Engine.REFLEX_TAPS} taps (~15 s):", bold = true, size = 12)
                hint("• Wait for the GREEN square, then tap as fast as you can.")
                hint("• Pass at median ≤ $passMs ms — then play.")
                hint("• Tapping early restarts the round.")
                primaryButton("Start tap test") {
                    phase = Phase.REFLEX_RUN
                    render()
                }
                textButton("Not now") { dismiss() }
            }

            ChessReadinessV4Engine.Level.RED -> dialog.setContent(
                "♟ Green Light", "Level · RED-lean (concern ${a.concern})"
            ) {
                stateLabel("STRONG CONSIDER RESTING", "#EF4444")
                spacer(6)
                body(a.level.headline, color = 0xFFFFAAAA.toInt())
                spacer(8)
                renderSignals(a)
                renderSessionWatch()
                val gate = a.gate
                if (gate != null) {
                    body("Survival gate — ${gate.puzzles} puzzles", bold = true, size = 12)
                    hint("• ${gate.why}")
                    hint("• Run it in the chess app: ✓ PASS per solve, ✕ FAIL on a miss.")
                    primaryButton("Arm survival gate (${gate.puzzles})") {
                        armSurvival(a)
                    }
                }
                // RED never hard-blocks: the override is always available and
                // logged for calibration.
                textButton("Play anyway (override, logged)") {
                    ChessReadinessV4Recorder.record(
                        context, a, gateType = a.gate?.type ?: "",
                        gatePassed = null, reflexMs = null, overridden = true
                    )
                    dismiss()
                }
            }
        }
    }

    /** Parks the v4 context + arms the shared bubble survival panel (v3 path). */
    private fun armSurvival(a: ChessReadinessV4Engine.Assessment) {
        val gate = a.gate ?: return dismiss()
        org.json.JSONArray().apply { a.signals.forEach { put(it.id) } }.let { ids ->
            ChessReadinessV4Store.savePending(
                context,
                ChessReadinessV4Store.PendingV4(
                    concern = a.concern,
                    signalsJson = ids.toString(),
                    gatePuzzles = gate.puzzles,
                    armedAtMs = System.currentTimeMillis()
                )
            )
        }
        ChessReadinessV3Store.savePendingSurvival(
            context,
            ChessReadinessV3Store.PendingSurvival(
                sessionStartedAt = System.currentTimeMillis(),
                target = gate.puzzles,
                reflexLapses = 0,
                reflexFalseStarts = 0,
                reflexMeanRtMs = null,
                variant = null,
                ratingBasis = 0
            )
        )
        dismiss()
        onSurvivalArmed?.invoke()
    }

    // ── The 5-tap reflex gate ───────────────────────────────────────────────

    private fun renderReflexRun() {
        val a = assessment ?: return dismiss()
        val passMs = ChessReadinessV4Store.reflexPassMs(context)
        dialog.setContent(
            "♟ Green Light", "Reflex gate — ${ChessReadinessV4Engine.REFLEX_TAPS} taps · pass ≤ $passMs ms"
        ) {
            val tap = ChessV4TapTestView(context)
            tap.taps = ChessReadinessV4Engine.REFLEX_TAPS
            tap.onComplete = { samples ->
                val median = ChessReadinessV4Engine.medianTapMs(samples)
                val passed = ChessReadinessV4Engine.reflexPassed(samples, passMs)
                ChessReadinessV4Recorder.record(
                    context, a, gateType = "reflex",
                    gatePassed = passed, reflexMs = median, overridden = false
                )
                phase = Phase.RESULT
                render()
            }
            customView(tap, 320)
            tap.start()
            textButton("Abandon") { dismiss() }
        }
    }

    private fun renderResult() {
        val a = assessment ?: return dismiss()
        val last = ChessReadinessV4Store.loadEvents(context).lastOrNull()
        val passed = last?.gatePassed == true
        dialog.setContent("♟ Green Light", "Reflex gate — verdict") {
            if (passed) {
                stateLabel("PASSED — GREEN", "#22C55E")
                spacer(6)
                body("Rated play unlocked. ${a.level.headline}", color = 0xFF66BB6A.toInt())
                last?.reflexMs?.let {
                    keyValue("Median reaction", "%.0f ms".format(it))
                }
            } else {
                stateLabel("FAILED — RED (rest first)", "#EF4444")
                spacer(6)
                body("Re-test after the rest period — or override (logged).")
                last?.reflexMs?.let {
                    keyValue("Median reaction", "%.0f ms".format(it))
                }
                spacer(6)
                renderSignals(a)
                textButton("Play anyway (override, logged)") {
                    ChessReadinessV4Recorder.record(
                        context, a, gateType = "reflex",
                        gatePassed = null, reflexMs = last?.reflexMs, overridden = true
                    )
                    dismiss()
                }
            }
            primaryButton("Close") { dismiss() }
        }
    }

    private fun formatRetryClock(retryAt: Long): String =
        Instant.ofEpochMilli(retryAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun formatRetryRemaining(retryAt: Long): String {
        val totalMin = (((retryAt - System.currentTimeMillis()) + 59999L) / 60000L)
            .toInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }
}

/**
 * The v4 reflex gate view: [taps] rounds of wait-for-green → tap. Taps
 * during the wait restart the round (false start). Reports the completed
 * tap latencies (ms) via [onComplete]. Plain framework views — no Compose,
 * same policy as the other chess overlay widgets.
 */
class ChessV4TapTestView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val label = TextView(context).apply {
        text = "Get ready…"
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    /** Number of scored taps (spec default 5). */
    var taps: Int = 5

    var onComplete: ((List<Long>) -> Unit)? = null

    private var round = 0
    private val samples = mutableListOf<Long>()
    private var goAt = 0L
    private var waiting = false
    private var running = false

    init {
        setBackgroundColor(0xFF101018.toInt())
        addView(
            label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        setOnClickListener { onTap() }
    }

    fun start() {
        round = 0
        samples.clear()
        running = true
        nextRound()
    }

    private fun nextRound() {
        if (!running) return
        waiting = true
        label.text = "Wait for GREEN…\n(${samples.size}/$taps)"
        setBackgroundColor(0xFF20202A.toInt())
        handler.postDelayed({
            if (!running || !waiting) return@postDelayed
            waiting = false
            goAt = android.os.SystemClock.elapsedRealtime()
            label.text = "TAP!"
            setBackgroundColor(0xFF22C55E.toInt())
        }, 1200L + Random.nextLong(2300L))
    }

    private fun onTap() {
        if (!running) return
        if (waiting) {
            // False start — restart this round's delay.
            handler.removeCallbacksAndMessages(null)
            label.text = "Too early — wait for GREEN…"
            setBackgroundColor(0xFF20202A.toInt())
            handler.postDelayed({ if (running) nextRound() }, 900L)
            return
        }
        samples.add(android.os.SystemClock.elapsedRealtime() - goAt)
        if (samples.size >= taps) {
            running = false
            handler.removeCallbacksAndMessages(null)
            onComplete?.invoke(samples.toList())
        } else {
            nextRound()
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
