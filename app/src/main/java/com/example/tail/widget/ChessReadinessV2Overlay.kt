package com.example.tail.widget

import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ♟ Chess Readiness V2 — the Cognitive Readiness Gating wizard, rendered as
 * a floating overlay dialog by [FloatingBubbleService] (same mechanism as
 * the v1 [ChessReadinessOverlay]; the chess app stays focused underneath).
 *
 * Pipeline (per the research framework):
 *  1. OVERVIEW  — overnight autonomic Z-scores (lnRMSSD + RHR vs the rolling
 *                 30-day baseline) and the cognitive ACWR are computed from
 *                 Garmin data + the games log and shown to the user.
 *  2. PVT-B     — the mandatory 3-minute Brief Psychomotor Vigilance Task
 *                 (random ISI 2–10 s, ms counter, 355 ms lapse threshold,
 *                 <100 ms false starts).
 *  3. GATING    — the three modules combine through the immutable gating
 *                 matrix (worst module wins).
 *  4. PRIMING   — Tier 1 only: 5 mastered mate-in-one patterns with an
 *                 enforced 3-second blunder-check latency per puzzle.
 *  5. RESULT    — Tier 1/2/3 verdict, recorded into the SHARED v1 history
 *                 (GREEN/YELLOW/RED) so Chess Guard enforcement and the
 *                 Phase-2 audit pipeline work unchanged.
 *
 * Rate limiting reuses the v1 engine gate (daily cap / cool-down / rest),
 * so v1 and v2 tests draw from the same allowance.
 */
class ChessReadinessV2Overlay(service: android.content.Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class Phase { LOADING, BLOCKED, OVERVIEW, PVT_INTRO, PVT_RUN, PRIMING, RESULT }

    // ── Wizard state ────────────────────────────────────────────────────────

    private var phase = Phase.LOADING
    private var blockedMessage = ""
    private var blockedRetryAt = 0L
    private var sessionStartedAt = 0L

    private var autonomic: ChessReadinessV2Engine.AutonomicEvaluation? = null
    private var acwr: ChessReadinessV2Engine.AcwrEvaluation? = null
    private var pvtSummary: ChessReadinessV2Engine.PvtSummary? = null
    private var gating: ChessReadinessV2Engine.V2GatingResult? = null

    private var primingPuzzles: List<ChessPrimingBank.PrimingPuzzle> = emptyList()
    private var primingIndex = 0
    private var primingFeedback = ""

    // Handles to live views of the currently shown step
    private var pvtCountdown: android.widget.TextView? = null
    private var primingHeader: android.widget.TextView? = null
    private var primingHold: android.widget.TextView? = null
    private var primingFeedbackView: android.widget.TextView? = null
    private var primingBoard: ChessPrimingView? = null

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

    // ── Initial load (gate check + metrics) ─────────────────────────────────

    private fun loadInitial() {
        scope.launch {
            val session = ChessReadinessV2Store.loadSession(context)
            sessionStartedAt = session?.startedAt ?: System.currentTimeMillis()
            primingIndex = session?.primingIndex ?: 0

            // Shared v1 rate-limit gate (daily cap / cool-down / rest).
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
                    computeModules()
                    phase = when (session?.step) {
                        ChessReadinessV2Store.V2Step.PRIMING ->
                            if (gating?.tier == ChessReadinessV2Engine.V2Tier.TIER1_PEAK)
                                Phase.PRIMING else Phase.OVERVIEW
                        else -> Phase.OVERVIEW
                    }
                }
            }
            if (dialog.isShowing()) render()
        }
    }

    /**
     * Recomputes the autonomic Z-scores and the cognitive ACWR from current
     * data. Deterministic — safe to re-run on every overlay open (a resumed
     * session restarts at the overview; the PVT must be contiguous anyway).
     */
    private suspend fun computeModules() {
        val today = LocalDate.now()

        // ── Autonomic: 31 days of overnight RMSSD + RHR ──
        val biometrics = withContext(Dispatchers.IO) { loadBiometrics(today) }
        autonomic = ChessReadinessV2Engine.evaluateAutonomic(biometrics, today)

        // ── Workload: games log + v2 test-session loads ──
        val acwrEval = withContext(Dispatchers.IO) {
            val games = ChessReadinessLogStore.loadGames(context).map {
                ChessReadinessV2Engine.GameLoad(
                    endTimeMs = it.endTimeMs,
                    minutes = it.minutes,
                    type = it.type,
                    rated = it.rated
                )
            }
            val extra = ChessReadinessV2Store.loadSessionLoads(context)
            val daily = ChessReadinessV2Engine.dailyCognitiveLoads(games, extra)
            ChessReadinessV2Engine.evaluateAcwr(daily, today)
        }
        acwr = acwrEval
    }

    /**
     * Reads the Garmin cache for the last 31 days; when today's HRV is
     * missing and the proxy is configured, performs one bounded 7-day
     * refresh (≤ 20 s) and merges it into the cache first.
     */
    private suspend fun loadBiometrics(today: LocalDate): List<ChessReadinessV2Engine.DailyBiometric> {
        val repo = GarminRepository(context)
        var cached = repo.loadAllCachedData()
        val todayHrv = cached[GarminType.HRV_LAST_NIGHT]?.get(today.toString())

        if (todayHrv == null) {
            try {
                val settings = SettingsRepository(context).settingsFlow.first()
                if (settings.garminEnabled && settings.garminProxyUrl.isNotBlank()) {
                    val fresh = withTimeoutOrNull(20_000) {
                        repo.fetchCurrentMonthData(
                            settings.garminProxyUrl, settings.garminAppToken, settings.garminDateOfBirth
                        )
                    }
                    if (fresh != null && fresh.isNotEmpty()) {
                        repo.mergeAndCacheDailyData(fresh)
                        cached = repo.loadAllCachedData()
                    }
                }
            } catch (_: Exception) {
                // Cache-only fallback — the module reports NO_DATA if empty.
            }
        }

        val hrv = cached[GarminType.HRV_LAST_NIGHT].orEmpty()
        val rhr = cached[GarminType.RESTING_HR].orEmpty()
        val earliest = today.minusDays(30)
        val result = ArrayList<ChessReadinessV2Engine.DailyBiometric>()
        var d = earliest
        while (!d.isAfter(today)) {
            val key = d.toString()
            if (hrv.containsKey(key) || rhr.containsKey(key)) {
                result.add(
                    ChessReadinessV2Engine.DailyBiometric(
                        date = d,
                        rmssdMs = hrv[key],
                        restingHr = rhr[key]
                    )
                )
            }
            d = d.plusDays(1)
        }
        return result
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun persist(step: ChessReadinessV2Store.V2Step, primingIdx: Int = primingIndex) {
        ChessReadinessV2Store.saveSession(
            context,
            ChessReadinessV2Store.V2Session(
                startedAt = sessionStartedAt,
                updatedAt = System.currentTimeMillis(),
                step = step,
                primingIndex = primingIdx,
                autonomicJson = null,
                gatingJson = null
            )
        )
    }

    private fun abandon() {
        ChessReadinessV2Store.clearSession(context)
        dismiss()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        pvtCountdown = null
        primingHeader = null
        primingHold = null
        primingFeedbackView = null
        primingBoard = null
        when (phase) {
            Phase.LOADING -> renderLoading()
            Phase.BLOCKED -> renderBlocked()
            Phase.OVERVIEW -> renderOverview()
            Phase.PVT_INTRO -> renderPvtIntro()
            Phase.PVT_RUN -> renderPvtRun()
            Phase.PRIMING -> renderPriming()
            Phase.RESULT -> renderResult()
        }
    }

    private fun renderLoading() {
        dialog.setContent("♟ Chess Readiness V2", null) {
            body("Evaluating overnight metrics…", color = 0xFF999999.toInt())
        }
    }

    private fun renderBlocked() {
        dialog.setContent("♟ Chess Readiness V2", "Test unavailable") {
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

    // ── Step 1: overnight autonomic + workload overview ─────────────────────

    private fun tierLabel(t: ChessReadinessV2Engine.V2Tier): String = when (t) {
        ChessReadinessV2Engine.V2Tier.TIER1_PEAK -> "TIER 1"
        ChessReadinessV2Engine.V2Tier.TIER2_RESTRICTED -> "TIER 2"
        ChessReadinessV2Engine.V2Tier.TIER3_LOCKOUT -> "TIER 3"
        ChessReadinessV2Engine.V2Tier.NO_DATA -> "—"
    }

    private fun tierColor(t: ChessReadinessV2Engine.V2Tier): Int = when (t) {
        ChessReadinessV2Engine.V2Tier.TIER1_PEAK -> 0xFF66BB6A.toInt()
        ChessReadinessV2Engine.V2Tier.TIER2_RESTRICTED -> 0xFFEAB308.toInt()
        ChessReadinessV2Engine.V2Tier.TIER3_LOCKOUT -> 0xFFEF4444.toInt()
        ChessReadinessV2Engine.V2Tier.NO_DATA -> 0xFF999999.toInt()
    }

    private fun renderOverview() {
        dialog.setContent("♟ Chess Readiness V2", "Step 1 · Overnight metrics") {
            val auto = autonomic
            val load = acwr

            body("Autonomic (lnRMSSD · RHR)", bold = true, size = 13)
            if (auto == null || (auto.zLnRmssd == null && auto.zRhr == null)) {
                bullet("No Garmin baseline yet — module neutral", 0xFF999999.toInt())
            } else {
                auto.zLnRmssd?.let {
                    keyValue("lnRMSSD Z (7d EWMA vs 30d)", String.format("%+.2f", it))
                } ?: bullet("lnRMSSD: no data", 0xFF999999.toInt())
                auto.zRhr?.let {
                    keyValue("Resting HR Z", String.format("%+.2f", it))
                } ?: bullet("RHR: no data", 0xFF999999.toInt())
            }
            auto?.let {
                bullet("Autonomic: ${tierLabel(it.tier)}", tierColor(it.tier))
            }
            spacer(8)

            body("Cognitive workload (ACWR)", bold = true, size = 13)
            load?.let {
                if (it.ratio != null) {
                    keyValue("EWMA acute:chronical", String.format("%.2f", it.ratio))
                } else {
                    bullet("Baseline building (${it.historyDays}/14 load days)", 0xFF999999.toInt())
                }
                bullet("Workload: ${tierLabel(it.tier)}", tierColor(it.tier))
            }

            spacer(10)
            // The PVT-B ALWAYS runs — it is the core of the readiness test.
            // When a passive module already restricts, the matrix still takes
            // the worst module, but the user always gets (and is measured by)
            // an actual test.
            val autoTier = auto?.tier ?: ChessReadinessV2Engine.V2Tier.NO_DATA
            val loadTier = load?.tier ?: ChessReadinessV2Engine.V2Tier.NO_DATA
            val preRestricted =
                autoTier == ChessReadinessV2Engine.V2Tier.TIER2_RESTRICTED ||
                    autoTier == ChessReadinessV2Engine.V2Tier.TIER3_LOCKOUT ||
                    loadTier == ChessReadinessV2Engine.V2Tier.TIER2_RESTRICTED ||
                    loadTier == ChessReadinessV2Engine.V2Tier.TIER3_LOCKOUT
            if (preRestricted) {
                body(
                    "⚠ A passive module already restricts today — the vigilance " +
                        "test still runs for the complete picture (worst module wins).",
                    color = 0xFFEAB308.toInt()
                )
            } else {
                body("Autonomically clear — proceed to the vigilance test.", color = 0xFF66BB6A.toInt())
            }
            primaryButton("Begin 3-minute PVT-B") {
                persist(ChessReadinessV2Store.V2Step.PVT_PENDING)
                phase = Phase.PVT_INTRO
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    // ── Step 2: the 3-minute PVT-B ──────────────────────────────────────────

    private fun renderPvtIntro() {
        dialog.setContent("♟ Chess Readiness V2", "Step 2 · PVT-B (3 min)") {
            body("A blank screen. At random moments a millisecond counter appears — TAP anywhere as fast as you can.")
            hint("• Respond as fast as possible, but never guess.")
            hint("• Tapping before the counter = false start (impulsivity).")
            hint("• Responses ≥ 355 ms count as lapses.")
            hint("• Keep the phone steady; the run is exactly 3 minutes.")
            primaryButton("Start run") {
                phase = Phase.PVT_RUN
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderPvtRun() {
        dialog.setContent("♟ Chess Readiness V2", "Step 2 · PVT-B — running") {
            pvtCountdown = android.widget.TextView(context).apply {
                text = "3:00"
                setTextColor(0xFF66CCFF.toInt())
                textSize = 14f
                gravity = android.view.Gravity.CENTER
            }
            customView(pvtCountdown!!, 20)
            val pvt = ChessPvtView(context)
            pvt.onTick = { remaining ->
                pvtCountdown?.text = formatClock(remaining)
            }
            pvt.onComplete = { samples ->
                val summary = ChessReadinessV2Engine.summarizePvt(samples)
                ChessReadinessV2Store.appendPvt(
                    context,
                    ChessReadinessV2Store.PvtRecord(
                        timestamp = System.currentTimeMillis(),
                        validResponses = summary.validResponses,
                        lapses = summary.lapses,
                        falseStarts = summary.falseStarts,
                        meanRrt = summary.meanRrt,
                        meanRtMs = summary.meanRtMs,
                        maxRtMs = summary.maxRtMs
                    )
                )
                finalizeGating(pvt = summary)
            }
            customView(pvt, 300)
            pvt.startRun()
            textButton("Abandon test") { abandon() }
        }
    }

    private fun formatClock(remainingMs: Long): String {
        val s = (remainingMs / 1000).toInt().coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ── Step 3: gating → priming (Tier 1) or result ─────────────────────────

    /**
     * Runs the gating matrix. Tier 1 continues to the priming module; any
     * other tier records the result immediately (no priming when locked).
     */
    private fun finalizeGating(pvt: ChessReadinessV2Engine.PvtSummary?) {
        pvtSummary = pvt
        val result = ChessReadinessV2Engine.gate(
            ChessReadinessV2Engine.V2GatingInput(
                autonomic = autonomic,
                pvt = pvt,
                acwr = acwr
            )
        )
        gating = result
        if (result.tier == ChessReadinessV2Engine.V2Tier.TIER1_PEAK) {
            primingPuzzles = ChessPrimingBank.selectForDay(LocalDate.now().toEpochDay())
            primingIndex = 0
            primingFeedback = ""
            persist(ChessReadinessV2Store.V2Step.PRIMING, primingIdx = 0)
            phase = Phase.PRIMING
            render()
        } else {
            recordResult(result)
        }
    }

    private fun renderPriming() {
        val puzzles = primingPuzzles.ifEmpty {
            ChessPrimingBank.selectForDay(LocalDate.now().toEpochDay())
        }
        if (primingPuzzles.isEmpty()) primingPuzzles = puzzles
        val p = puzzles[primingIndex.coerceIn(0, puzzles.lastIndex)]

        dialog.setContent(
            "♟ Chess Readiness V2",
            "Step 3 · Priming ${primingIndex + 1}/${puzzles.size} — ${p.title}"
        ) {
            body("Find the mate in one. ${p.motif}.")
            hint("Tap the piece, then its square. Moves are LOCKED for the first 3 seconds — use the pause to check every line.")

            primingHold = android.widget.TextView(context).apply {
                text = "Hold — blunder check: 3 s"
                setTextColor(0xFFEAB308.toInt())
                textSize = 13f
                gravity = android.view.Gravity.CENTER
            }
            customView(primingHold!!, 18)

            val board = ChessPrimingView(context)
            board.onHoldCountdown = { seconds ->
                primingHold?.text = if (seconds > 0) "Hold — blunder check: ${seconds}s"
                else "Move unlocked"
                primingHold?.setTextColor(
                    if (seconds > 0) 0xFFEAB308.toInt() else 0xFF66BB6A.toInt()
                )
            }
            board.onMoveAttempt = { result ->
                when (result) {
                    ChessPrimingView.MoveResult.TooSoon ->
                        setPrimingFeedback("Too fast — hold the 3-second blunder check.", 0xFFEF9A9A.toInt())
                    is ChessPrimingView.MoveResult.Wrong ->
                        setPrimingFeedback("Not the mate — scan king escapes and checks again.", 0xFFEAB308.toInt())
                    ChessPrimingView.MoveResult.Correct -> {
                        if (primingIndex + 1 < puzzles.size) {
                            primingIndex += 1
                            primingFeedback = ""
                            persist(ChessReadinessV2Store.V2Step.PRIMING, primingIdx = primingIndex)
                            render()
                        } else {
                            val g = gating
                            if (g != null) recordResult(g) else abandon()
                        }
                    }
                }
            }
            primingBoard = board
            customView(board, 300)
            board.showPuzzle(p)

            primingFeedbackView = android.widget.TextView(context).apply {
                text = primingFeedback
                setTextColor(0xFFEAB308.toInt())
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                minHeight = 30
            }
            customView(primingFeedbackView!!, 30)

            textButton("Abandon test") { abandon() }
        }
    }

    private fun setPrimingFeedback(text: String, color: Int) {
        primingFeedback = text
        primingFeedbackView?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    // ── Step 4: result ──────────────────────────────────────────────────────

    /**
     * Records the verdict into the SHARED v1 history (GREEN/YELLOW/RED —
     * what Chess Guard and Phase 2 read), the v2 telemetry logs, and the
     * ACWR session load for today's test effort.
     */
    private fun recordResult(result: ChessReadinessV2Engine.V2GatingResult) {
        val now = System.currentTimeMillis()
        ChessReadinessStore.appendTest(
            context,
            ChessReadinessEngine.ReadinessTest(
                timestamp = now,
                ccrs = result.ccrs,
                state = result.stateName
            )
        )
        ChessReadinessV2Store.appendResult(
            context,
            ChessReadinessV2Store.V2ResultRecord(
                timestamp = now,
                tier = result.tier.name,
                stateName = result.stateName,
                ccrs = result.ccrs,
                zLnRmssd = autonomic?.zLnRmssd,
                zRhr = autonomic?.zRhr,
                lapses = pvtSummary?.lapses ?: 0,
                falseStarts = pvtSummary?.falseStarts ?: 0,
                meanRrt = pvtSummary?.meanRrt,
                acwr = acwr?.ratio,
                pvtSkipped = result.pvtSkipped,
                sessionStartedAt = sessionStartedAt
            )
        )
        ChessReadinessV2Store.addSessionLoad(
            context, LocalDate.now(),
            ChessReadinessV2Engine.TEST_SESSION_MINUTES * ChessReadinessV2Engine.TEST_SESSION_INTENSITY
        )
        ChessReadinessV2Store.clearSession(context)
        phase = Phase.RESULT
        render()
    }

    private fun renderResult() {
        val g = gating ?: return dismiss()
        val (colorHex, headline, permitted, prohibited) = when (g.tier) {
            ChessReadinessV2Engine.V2Tier.TIER1_PEAK -> Quad(
                "#22C55E", "TIER 1 · PEAK — RATED PLAY UNLOCKED",
                listOf("Rated Blitz / Rapid / Classical", "Complex puzzle rushes", "Deep calculation suites"),
                listOf("Nothing — you are primed")
            )
            ChessReadinessV2Engine.V2Tier.TIER2_RESTRICTED -> Quad(
                "#EAB308", "TIER 2 · RESTRICTED — RATED PLAY LOCKED",
                listOf("Unrated casual games", "Spaced-repetition review", "Pattern recognition drills", "Opening theory review"),
                listOf("ALL RATED PLAY", "Deep tactical calculation")
            )
            else -> Quad(
                "#EF4444", "TIER 3 · LOCKOUT — ALL CHESS LOCKED",
                listOf("Passive game review", "Recovery: rest, hydration, walk"),
                listOf("ALL chess play, drilling, and study")
            )
        }

        dialog.setContent("♟ Chess Readiness V2", "Verdict") {
            bigScore(tierLabel(g.tier), colorHex)
            stateLabel(headline, colorHex)
            spacer(6)
            g.autonomic?.let {
                body("Autonomic", bold = true, size = 12)
                it.reasons.forEach { r -> bullet("· $r", 0xFFCCCCCC.toInt()) }
            }
            g.pvt?.let {
                body("Vigilance (PVT-B)", bold = true, size = 12)
                it.reasons.forEach { r -> bullet("· $r", 0xFFCCCCCC.toInt()) }
            }
            if (g.pvtSkipped) {
                bullet("· PVT skipped — autonomic/workload already restricted", 0xFF999999.toInt())
            }
            g.workload?.let {
                body("Workload (ACWR)", bold = true, size = 12)
                it.reasons.forEach { r -> bullet("· $r", 0xFFCCCCCC.toInt()) }
            }
            spacer(8)
            body("Permitted", bold = true, size = 12, color = 0xFF66BB6A.toInt())
            permitted.forEach { bullet("+ $it", 0xFF66BB6A.toInt()) }
            body("Prohibited", bold = true, size = 12, color = 0xFFEF4444.toInt())
            prohibited.forEach { bullet("− $it", 0xFFEF9A9A.toInt()) }
            spacer(4)
            hint("Authorization valid until ${formatRetryClock(System.currentTimeMillis() + ChessReadinessEngine.SESSION_VALIDITY_MS)} (shared 60-minute window).")
            primaryButton("Close") { dismiss() }
        }
    }

    /** Tiny 4-tuple for the verdict screen copy. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
