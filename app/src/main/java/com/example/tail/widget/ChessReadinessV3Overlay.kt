package com.example.tail.widget

import com.example.tail.widget.ChessReadinessV3Engine.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ♟ Chess Readiness V3 — Step 1 (reflex) overlay + survival hand-off.
 *
 * Rendered as a floating overlay dialog by [FloatingBubbleService]. The
 * pipeline:
 *
 *  1. REFLEX — the mandatory 2-minute PVT-B (random ISI 2–10 s, 355 ms
 *     lapses, <100 ms false starts). BINARY: ≥3 lapses or ≥3 false starts
 *     fails the nervous-system check → TOTAL REST LOCKOUT (recorded here,
 *     run ends).
 *
 *  2. SURVIVAL — NOT run in this dialog. The intro step's Start button
 *     ARMS the survival gate: the session (target, reflex summary) is
 *     parked in [ChessReadinessV3Store.savePendingSurvival] and the
 *     floating bubble takes over with a compact panel UNDER the bubble —
 *     a ▶ START button first; once started (the user has navigated to the
 *     chess.com survival drill), ✓ PASS / ✕ FAIL buttons, the puzzle
 *     counter, a per-puzzle stopwatch and the 5-minute total timer. The
 *     chess app stays fully usable the whole time.
 *
 * Rate limiting reuses the v1 engine gate (daily cap / cool-down / rest),
 * so v1, v2 and v3 tests draw from the same allowance.
 */
class ChessReadinessV3Overlay(
    service: android.content.Context,
    /** Invoked when the survival gate is armed — the bubble shows its panel. */
    private val onSurvivalArmed: (() -> Unit)? = null
) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class Phase { LOADING, BLOCKED, REFLEX_INTRO, REFLEX_RUN, SURVIVAL_INTRO, RESULT }

    // ── Wizard state ────────────────────────────────────────────────────────

    private var phase = Phase.LOADING
    private var blockedMessage = ""
    private var blockedRetryAt = 0L
    private var sessionStartedAt = 0L

    private var reflex: ChessReadinessV3Engine.ReflexSummary? = null
    private var verdict: Verdict? = null
    private var target = 0

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
        dismiss()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        when (phase) {
            Phase.LOADING -> renderLoading()
            Phase.BLOCKED -> renderBlocked()
            Phase.REFLEX_INTRO -> renderReflexIntro()
            Phase.REFLEX_RUN -> renderReflexRun()
            Phase.SURVIVAL_INTRO -> renderSurvivalIntro()
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
                gravity = android.view.Gravity.CENTER
            }
            customView(countdown, 20)
            val pvt = ChessPvtView(context)
            pvt.durationMs = ChessReadinessV3Engine.REFLEX_DURATION_MS
            pvt.onTick = { remaining ->
                countdown.text = "%d:%02d".format(
                    (remaining / 1000).toInt().coerceAtLeast(0) / 60,
                    (remaining / 1000).toInt().coerceAtLeast(0) % 60
                )
            }
            pvt.onComplete = { samples ->
                val summary = ChessReadinessV3Engine.summarizeReflex(samples)
                reflex = summary
                // Feed the 2-min reflex run into the SHARED PVT log so the
                // existing reflex stats/charts on the Chess Stats screen
                // (and the personal speed baseline) include v3 runs too.
                val v2Summary = ChessReadinessV2Engine.summarizePvt(samples)
                ChessReadinessV2Store.appendPvt(
                    context,
                    ChessReadinessV2Store.PvtRecord(
                        timestamp = System.currentTimeMillis(),
                        validResponses = v2Summary.validResponses,
                        lapses = v2Summary.lapses,
                        falseStarts = v2Summary.falseStarts,
                        meanRrt = v2Summary.meanRrt,
                        meanRtMs = v2Summary.meanRtMs,
                        maxRtMs = v2Summary.maxRtMs
                    )
                )
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

    // ── Step 2 intro: arm the survival gate → bubble takes over ─────────────

    private fun renderSurvivalIntro() {
        val pb = ChessReadinessV3Store.survivalPb(context)
        dialog.setContent("♟ Chess Readiness V3", "Step 2 · Survival gate") {
            body("Reflex cleared — the nervous system is online.", color = 0xFF66BB6A.toInt())
            spacer(6)
            keyValue("Dynamic target", "$target puzzles")
            keyValue("Derived from", if (pb > 0) "PB $pb × 0.60" else "default PB ${ChessReadinessV3Engine.DEFAULT_PB} × 0.60")
            spacer(6)
            body("The survival run happens in the chess app, controlled from the bubble:")
            hint("• 1 — Tap START below, then open the Puzzle Rush Survival drill in chess.com.")
            hint("• 2 — Tap ▶ on the bubble panel when the drill begins.")
            hint("• 3 — Tap ✓ PASS per solved puzzle, ✕ FAIL on a miss. One strike fails the gate.")
            hint("• Global cap: 5 minutes. Target: $target consecutive passes.")
            primaryButton("Arm survival gate") {
                ChessReadinessV3Store.savePendingSurvival(
                    context,
                    ChessReadinessV3Store.PendingSurvival(
                        sessionStartedAt = sessionStartedAt,
                        target = target,
                        reflexLapses = reflex?.lapses ?: 0,
                        reflexFalseStarts = reflex?.falseStarts ?: 0,
                        reflexMeanRtMs = reflex?.meanRtMs
                    )
                )
                dismiss()
                onSurvivalArmed?.invoke()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    // ── Result recording (reflex failure path) ──────────────────────────────

    private fun finishRun(v: Verdict) {
        verdict = v
        ChessReadinessV3Recorder.record(
            context = context,
            sessionStartedAt = sessionStartedAt,
            verdict = v,
            target = target,
            puzzlesPassed = 0,
            survivalDurationMs = 0L,
            reflex = reflex
        )
        phase = Phase.RESULT
        render()
    }

    private fun renderResult() {
        val v = verdict ?: return dismiss()
        dialog.setContent("♟ Chess Readiness V3", "Verdict") {
            bigScore("FAIL", "#EF4444")
            stateLabel("REFLEX FAIL — TOTAL REST LOCKOUT", "#EF4444")
            spacer(6)
            body("Reflex (2-min PVT-B)", bold = true, size = 12)
            reflex?.let { r ->
                keyValue("Lapses / false starts", "${r.lapses} / ${r.falseStarts}")
                r.meanRtMs?.let { keyValue("Mean reaction time", String.format("%.0f ms", it)) }
            }
            spacer(8)
            body("Prohibited", bold = true, size = 12, color = 0xFFEF4444.toInt())
            bullet("− ALL chess — total rest (recover, then re-test)", 0xFFEF9A9A.toInt())
            spacer(4)
            hint("Re-test available after the shared rest period.")
            primaryButton("Close") { dismiss() }
        }
    }
}
