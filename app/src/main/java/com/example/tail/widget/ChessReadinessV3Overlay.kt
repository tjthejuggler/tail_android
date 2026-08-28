package com.example.tail.widget

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tail.widget.ChessReadinessV3Engine.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * ♟ Chess Readiness V3 — the Reflex + Puzzle Rush Survival gate, rendered
 * as a floating overlay dialog by [FloatingBubbleService] (same mechanism
 * as the v1/v2 wizards; the chess app stays focused underneath, which the
 * survival step needs — the puzzles are solved IN the app).
 *
 * Pipeline:
 *  1. REFLEX  — the mandatory 2-minute PVT-B (random ISI 2–10 s, 355 ms
 *     lapses, <100 ms false starts). BINARY: ≥3 lapses or ≥3 false starts
 *     fails the nervous-system check → TOTAL REST LOCKOUT (run ends).
 *  2. SURVIVAL — the tactical gate overlay: the user solves real puzzles
 *     in the chess app and taps ✓ PASS per solve / ✕ STRIKE on a failure.
 *     Dynamic target = round(survival PB × 0.60). Zero-tolerance strikes;
 *     a 5-minute global cap auto-fails. Per-puzzle latency telemetry is
 *     logged on every event.
 *  3. RESULT — the verdict is recorded into the SHARED v1 history
 *     (GREEN/RED) so Chess Guard enforcement, the color system and the
 *     Phase-2 audit pipeline work unchanged.
 *
 * Rate limiting reuses the v1 engine gate (daily cap / cool-down / rest),
 * so v1, v2 and v3 tests draw from the same allowance.
 */
class ChessReadinessV3Overlay(service: android.content.Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private enum class Phase { LOADING, BLOCKED, REFLEX_INTRO, REFLEX_RUN, SURVIVAL_INTRO, SURVIVAL_RUN, RESULT }

    // ── Wizard state ────────────────────────────────────────────────────────

    private var phase = Phase.LOADING
    private var blockedMessage = ""
    private var blockedRetryAt = 0L
    private var sessionStartedAt = 0L

    private var reflex: ChessReadinessV3Engine.ReflexSummary? = null
    private var verdict: Verdict? = null

    // Survival run state
    private var target = 0
    private var puzzlesPassed = 0
    private var survivalStartMs = 0L        // elapsedRealtime when the run began
    private var puzzleStartMs = 0L          // elapsedRealtime when the current puzzle began
    private var survivalElapsedMs = 0L      // final total (captured on finish)

    // Live views of the survival panel
    private var survivalCounter: TextView? = null
    private var survivalStopwatch: TextView? = null
    private var survivalTotal: TextView? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (phase != Phase.SURVIVAL_RUN) return
            val now = SystemClock.elapsedRealtime()
            val total = now - survivalStartMs
            survivalStopwatch?.text = formatStopwatch(now - puzzleStartMs)
            survivalTotal?.text = formatClock(total)
            if (ChessReadinessV3Engine.timedOut(total)) {
                finishSurvival(Verdict.FAIL_TIMEOUT)
            } else {
                handler.postDelayed(this, 100)
            }
        }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun show() {
        dialog.show()
        render()
        loadInitial()
    }

    fun dismiss() {
        handler.removeCallbacks(tickRunnable)
        scope.cancel()
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    // ── Initial load (shared rate-limit gate) ───────────────────────────────

    private fun loadInitial() {
        scope.launch {
            sessionStartedAt = System.currentTimeMillis()
            val history = ChessReadinessStore.loadHistory(context)
            when (val gate =
                ChessReadinessEngine.checkGate(history, System.currentTimeMillis())) {
                is ChessReadinessEngine.GateStatus.Blocked -> {
                    blockedMessage = gate.error.message
                    blockedRetryAt = gate.error.retryAt
                    ChessReadinessLogStore.logBlockedAttempt(context, gate.error.message)
                    phase = Phase.BLOCKED
                }
                is ChessReadinessEngine.GateStatus.Allowed -> {
                    target = ChessReadinessV3Engine.targetScore(
                        ChessReadinessV3Store.survivalPb(context)
                    )
                    phase = Phase.REFLEX_INTRO
                }
            }
            if (dialog.isShowing()) render()
        }
    }

    private fun abandon() {
        handler.removeCallbacks(tickRunnable)
        dismiss()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        survivalCounter = null
        survivalStopwatch = null
        survivalTotal = null
        when (phase) {
            Phase.LOADING -> renderLoading()
            Phase.BLOCKED -> renderBlocked()
            Phase.REFLEX_INTRO -> renderReflexIntro()
            Phase.REFLEX_RUN -> renderReflexRun()
            Phase.SURVIVAL_INTRO -> renderSurvivalIntro()
            Phase.SURVIVAL_RUN -> renderSurvivalRun()
            Phase.RESULT -> renderResult()
        }
    }

    private fun renderLoading() {
        dialog.setContent("♟ Chess Readiness V3", null) {
            body("Preparing gate…", color = 0xFF999999.toInt())
        }
    }

    private fun renderBlocked() {
        dialog.setContent("♟ Chess Readiness V3", "Test unavailable") {
            body(blockedMessage)
            if (blockedRetryAt > 0L) {
                spacer(8)
                body(
                    "Next test: ${formatRetryClock(blockedRetryAt)} " +
                        "(in ${formatRetryRemaining(blockedRetryAt)})",
                    color = 0xFF66CCFF.toInt(), size = 15, bold = true
                )
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

    // ── Step 1: the 2-minute reflex test ────────────────────────────────────

    private fun renderReflexIntro() {
        dialog.setContent("♟ Chess Readiness V3", "Step 1 · Reflex test (2 min)") {
            body("A blank screen. At random moments a millisecond counter appears — TAP anywhere as fast as you can.")
            hint("• This is the physiological filter: CNS fatigue or sleep debt ends the run here.")
            hint("• Tapping before the counter = false start (impulsivity).")
            hint("• Responses ≥ 355 ms count as lapses.")
            hint("• 3+ lapses or 3+ false starts → total rest lockout.")
            primaryButton("Start reflex run") {
                phase = Phase.REFLEX_RUN
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderReflexRun() {
        dialog.setContent("♟ Chess Readiness V3", "Step 1 · Reflex — running") {
            val countdown = android.widget.TextView(context).apply {
                text = "2:00"
                setTextColor(0xFF66CCFF.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
            }
            customView(countdown, 20)
            val pvt = ChessPvtView(context)
            pvt.durationMs = ChessReadinessV3Engine.REFLEX_DURATION_MS
            pvt.onTick = { remaining -> countdown.text = formatClock(remaining) }
            pvt.onComplete = { samples ->
                val summary = ChessReadinessV3Engine.summarizeReflex(samples)
                reflex = summary
                if (summary.passed) {
                    phase = Phase.SURVIVAL_INTRO
                    render()
                } else {
                    finishRun(Verdict.FAIL_REFLEX)
                }
            }
            customView(pvt, 300)
            pvt.startRun()
            textButton("Abandon test") { abandon() }
        }
    }

    // ── Step 2: the puzzle rush survival gate ───────────────────────────────

    private fun renderSurvivalIntro() {
        val pb = ChessReadinessV3Store.survivalPb(context)
        dialog.setContent("♟ Chess Readiness V3", "Step 2 · Survival gate") {
            body("Reflex cleared — the nervous system is online.", color = 0xFF66BB6A.toInt())
            spacer(6)
            keyValue("Dynamic target", "$target puzzles")
            keyValue("Derived from", if (pb > 0) "PB $pb × 0.60" else "default PB ${ChessReadinessV3Engine.DEFAULT_PB} × 0.60")
            spacer(6)
            body("Solve REAL puzzles in the chess app underneath. After each solve tap ✓ PASS. One failure (✕ STRIKE) ends the run. Global cap: 5 minutes.")
            hint("• Zero-tolerance: a single strike fails the gate.")
            hint("• Solve consecutively — no skips, no takebacks.")
            hint("• Per-puzzle solving latency is logged as telemetry.")
            primaryButton("Start survival gate") {
                phase = Phase.SURVIVAL_RUN
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderSurvivalRun() {
        dialog.setContent("♟ Chess Readiness V3", "Step 2 · Survival gate — running") {
            val density = context.resources.displayMetrics.density
            fun Int.dp(): Int = (this * density).toInt()

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dp(), 10.dp(), 12.dp(), 12.dp())
            }

            survivalCounter = TextView(context).apply {
                text = "Puzzle: %02d / %d".format(puzzlesPassed + 1, target)
                setTextColor(0xFF66CCFF.toInt())
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            survivalStopwatch = TextView(context).apply {
                text = "00:00.0"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 26f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            survivalTotal = TextView(context).apply {
                text = "Total: 00:00"
                setTextColor(0xFF999999.toInt())
                textSize = 13f
                gravity = Gravity.CENTER
            }
            panel.addView(survivalCounter)
            panel.addView(survivalStopwatch)
            panel.addView(survivalTotal)

            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 10.dp(), 0, 0)
            }
            fun gateButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit) =
                TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textColor)
                    setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(bgColor)
                        cornerRadius = 10f * density
                    }
                    setOnClickListener { onClick() }
                }
            val passBtn = gateButton("✓ PASS", 0xFF1E5631.toInt(), 0xFF88FF88.toInt()) {
                onPassClicked()
            }
            val strikeBtn = gateButton("✕ STRIKE", 0xFF7F1D1D.toInt(), 0xFFEF9A9A.toInt()) {
                onStrikeClicked()
            }
            buttons.addView(
                passBtn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = 5.dp() }
            )
            buttons.addView(
                strikeBtn,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 5.dp() }
            )
            panel.addView(buttons)

            customView(panel, 20)
            survivalStartMs = SystemClock.elapsedRealtime()
            puzzleStartMs = survivalStartMs
            handler.removeCallbacks(tickRunnable)
            handler.postDelayed(tickRunnable, 100)
        }
    }

    private fun onPassClicked() {
        if (phase != Phase.SURVIVAL_RUN) return
        val now = SystemClock.elapsedRealtime()
        val duration = (now - puzzleStartMs).coerceAtLeast(0L)
        val passed = puzzlesPassed
        ChessReadinessV3Store.appendEvent(
            context,
            ChessReadinessV3Store.SurvivalEventRecord(
                sessionId = sessionStartedAt,
                puzzleIndex = passed + 1,
                puzzleDurationMs = duration,
                timestamp = System.currentTimeMillis(),
                verdict = Verdict.PASS.name
            )
        )
        puzzlesPassed = passed + 1
        survivalCounter?.text = "Puzzle: %02d / %d".format(puzzlesPassed + 1, target)
        if (ChessReadinessV3Engine.onPass(passed, target)) {
            finishSurvival(Verdict.PASS)
        } else {
            puzzleStartMs = now
        }
    }

    private fun onStrikeClicked() {
        if (phase != Phase.SURVIVAL_RUN) return
        val now = SystemClock.elapsedRealtime()
        ChessReadinessV3Store.appendEvent(
            context,
            ChessReadinessV3Store.SurvivalEventRecord(
                sessionId = sessionStartedAt,
                puzzleIndex = puzzlesPassed + 1,
                puzzleDurationMs = (now - puzzleStartMs).coerceAtLeast(0L),
                timestamp = System.currentTimeMillis(),
                verdict = Verdict.FAIL_STRIKE.name
            )
        )
        finishSurvival(Verdict.FAIL_STRIKE)
    }

    private fun finishSurvival(v: Verdict) {
        handler.removeCallbacks(tickRunnable)
        survivalElapsedMs = SystemClock.elapsedRealtime() - survivalStartMs
        finishRun(v)
    }

    // ── Result recording (shared v1 history → Chess Guard / colors) ────────

    private fun finishRun(v: Verdict) {
        handler.removeCallbacks(tickRunnable)
        verdict = v
        val now = System.currentTimeMillis()
        val stateName = ChessReadinessV3Engine.stateNameFor(v)
        val ccrs = ChessReadinessV3Engine.syntheticCcrs(v)

        // SHARED history — what Chess Guard enforcement and Phase 2 read.
        ChessReadinessStore.appendTest(
            context,
            ChessReadinessEngine.ReadinessTest(
                timestamp = now,
                ccrs = ccrs,
                state = stateName
            )
        )
        ChessReadinessV3Store.appendResult(
            context,
            ChessReadinessV3Store.V3ResultRecord(
                timestamp = now,
                sessionStartedAt = sessionStartedAt,
                verdict = v.name,
                stateName = stateName,
                ccrs = ccrs,
                target = target,
                puzzlesPassed = puzzlesPassed,
                survivalDurationMs = survivalElapsedMs,
                reflexLapses = reflex?.lapses ?: 0,
                reflexFalseStarts = reflex?.falseStarts ?: 0,
                reflexMeanRtMs = reflex?.meanRtMs
            )
        )
        // Habit credit for the survival portion (minutes solved + 1 session),
        // exactly like the v1 puzzle/rush step credits.
        if (survivalElapsedMs > 0) {
            ChessHabitCredit.grant(
                context,
                ChessReadinessV3Store.linkedSurvivalHabit(context),
                (survivalElapsedMs / 60000.0).roundToInt().coerceAtLeast(1),
                1
            )
        }
        phase = Phase.RESULT
        render()
    }

    private fun renderResult() {
        val v = verdict ?: return dismiss()
        val (colorHex, headline) = when (v) {
            Verdict.PASS -> "#22C55E" to "GATE PASSED — RATED PLAY UNLOCKED"
            Verdict.FAIL_STRIKE -> "#EF4444" to "GATE FAILED — STRIKE (rated play locked)"
            Verdict.FAIL_TIMEOUT -> "#EF4444" to "GATE FAILED — 5-MINUTE CAP (rated play locked)"
            Verdict.FAIL_REFLEX -> "#EF4444" to "REFLEX FAIL — TOTAL REST LOCKOUT"
        }
        dialog.setContent("♟ Chess Readiness V3", "Verdict") {
            bigScore(
                when (v) {
                    Verdict.PASS -> "PASS"
                    else -> "FAIL"
                },
                colorHex
            )
            stateLabel(headline, colorHex)
            spacer(6)
            body("Reflex (2-min PVT-B)", bold = true, size = 12)
            reflex?.let { r ->
                keyValue("Lapses / false starts", "${r.lapses} / ${r.falseStarts}")
                r.meanRtMs?.let { keyValue("Mean reaction time", String.format("%.0f ms", it)) }
            } ?: bullet("· skipped (reflex failure)", 0xFF999999.toInt())
            if (v != Verdict.FAIL_REFLEX) {
                body("Survival gate", bold = true, size = 12)
                keyValue("Puzzles passed", "$puzzlesPassed / $target")
                if (survivalElapsedMs > 0) {
                    keyValue("Session time", formatClock(survivalElapsedMs))
                }
            }
            spacer(8)
            if (v == Verdict.PASS) {
                body("Permitted", bold = true, size = 12, color = 0xFF66BB6A.toInt())
                bullet("+ Rated Blitz / Rapid / Classical", 0xFF66BB6A.toInt())
                bullet("+ Complex puzzle rushes", 0xFF66BB6A.toInt())
            } else {
                body("Prohibited", bold = true, size = 12, color = 0xFFEF4444.toInt())
                if (v == Verdict.FAIL_REFLEX) {
                    bullet("− ALL chess — total rest (recover, then re-test)", 0xFFEF9A9A.toInt())
                } else {
                    bullet("− ALL RATED PLAY (casual/study only)", 0xFFEF9A9A.toInt())
                }
            }
            spacer(4)
            hint("Authorization valid until ${formatRetryClock(System.currentTimeMillis() + ChessReadinessEngine.SESSION_VALIDITY_MS)} (shared 60-minute window).")
            primaryButton("Close") { dismiss() }
        }
    }

    // ── Formatting helpers ──────────────────────────────────────────────────

    private fun formatClock(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun formatStopwatch(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val s = clamped / 1000
        val tenth = (clamped % 1000) / 100
        return "%02d:%02d.%d".format(s / 60, s % 60, tenth)
    }
}
