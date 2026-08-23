package com.example.tail.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.HabitIncrementBus
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

/**
 * ♟ Chess Readiness — Phase 1 Pre-Session Diagnostic, rendered as a floating
 * overlay dialog by [FloatingBubbleService] (no Activity is started, so the
 * chess app stays the focused/dominant app underneath).
 *
 * Step-by-step flow (progress persisted in [ChessReadinessStore] so the
 * overlay can close between steps and resume when the user taps the bubble):
 *  1. SLEEP — Garmin sleep score (or manual entry)
 *  2. CLARITY — three 1–10 sliders: stress / focus / energy
 *  3. PUZZLES — three rated puzzles (go solve → come back → report)
 *  4. RUSH — one 3-minute Puzzle Rush (go play → come back → report)
 *  5. RESULT — CCRS 0–100 with the Green/Yellow/Red authorization
 *
 * The copy is deliberately minimal: each step says only what to DO and what
 * to REPORT BACK.
 */
class ChessReadinessOverlay(service: Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private enum class Phase {
        LOADING, BLOCKED, SLEEP, CLARITY,
        PUZZLE_GO, PUZZLE_RESULT, RUSH_GO, RUSH_RESULT, RESULT
    }

    // ── Wizard state ────────────────────────────────────────────────────────
    private var phase = Phase.LOADING
    private var blockedMessage = ""
    private var blockedRetryAt = 0L

    private var garminSleepScore: Int? = null
    private var rushAth = 0

    private var sessionStartedAt = 0L
    private var stepStartedAt = 0L
    private var sleepScore: Int? = null
    private var sleepFromGarmin = false
    private var stress = 6
    private var focus = 6
    private var energy = 6
    private var puzzleIndex = 0
    private var puzzleTimes = emptyList<Int>()
    private var puzzleSolved: Boolean? = null
    private var puzzleTimeText = ""
    private var rushScoreText = ""
    private var rushStrikes = -1
    private var result: ChessReadinessEngine.ReadinessResult? = null

    // Handles to input views of the currently shown step
    private var sleepField: android.widget.EditText? = null
    private var puzzleTimeField: android.widget.EditText? = null
    private var rushScoreField: android.widget.EditText? = null

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

    // ── Initial load (resume / gate check) ──────────────────────────────────

    private fun loadInitial() {
        scope.launch {
            rushAth = ChessReadinessStore.lastRushAllTimeHigh(context)

            garminSleepScore = withContext(Dispatchers.IO) {
                try {
                    val today = LocalDate.now()
                    GarminRepository(context)
                        .loadFromCache(today.year, today.monthValue)
                        ?.get(GarminType.SLEEP_SCORE)
                        ?.get(today.toString())
                } catch (_: Exception) {
                    null
                }
            }

            val session = ChessReadinessStore.loadSession(context)
            if (session != null) {
                sessionStartedAt = session.startedAt
                stepStartedAt = session.stepStartedAt
                sleepScore = session.sleepScore
                sleepFromGarmin = session.sleepFromGarmin
                if (session.clarityScores.size == 3) {
                    stress = session.clarityScores[0]
                    focus = session.clarityScores[1]
                    energy = session.clarityScores[2]
                }
                puzzleIndex = session.puzzleIndex
                puzzleTimes = session.puzzleTimesSec
                when (session.step) {
                    SessionStep.SLEEP -> phase = Phase.SLEEP
                    SessionStep.CLARITY -> phase = Phase.CLARITY
                    SessionStep.PUZZLE_GO -> phase = Phase.PUZZLE_GO
                    SessionStep.PUZZLE_RESULT -> {
                        // Pre-fill the elapsed time from the timer anchor
                        val elapsed = if (session.stepStartedAt > 0)
                            ((System.currentTimeMillis() - session.stepStartedAt) / 1000L).toInt()
                        else 0
                        puzzleTimeText = elapsed.coerceAtLeast(0).toString()
                        phase = Phase.PUZZLE_RESULT
                    }
                    SessionStep.RUSH_GO -> phase = Phase.RUSH_GO
                    SessionStep.RUSH_RESULT -> phase = Phase.RUSH_RESULT
                }
            } else {
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
                    is ChessReadinessEngine.GateStatus.Allowed -> phase = Phase.SLEEP
                }
            }
            if (dialog.isShowing()) render()
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun persist(step: SessionStep, puzzleIdx: Int = puzzleIndex, timerAnchor: Long = stepStartedAt) {
        ChessReadinessStore.saveSession(
            context,
            ReadinessSession(
                startedAt = sessionStartedAt,
                updatedAt = System.currentTimeMillis(),
                step = step,
                puzzleIndex = puzzleIdx,
                sleepScore = sleepScore,
                sleepFromGarmin = sleepFromGarmin,
                clarityScores = listOf(stress, focus, energy),
                puzzleTimesSec = puzzleTimes,
                stepStartedAt = timerAnchor
            )
        )
    }

    private fun abandon() {
        ChessReadinessStore.clearSession(context)
        dismiss()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        puzzleTimeField = null
        rushScoreField = null
        sleepField = null
        when (phase) {
            Phase.LOADING -> renderLoading()
            Phase.BLOCKED -> renderBlocked()
            Phase.SLEEP -> renderSleep()
            Phase.CLARITY -> renderClarity()
            Phase.PUZZLE_GO -> renderPuzzleGo()
            Phase.PUZZLE_RESULT -> renderPuzzleResult()
            Phase.RUSH_GO -> renderRushGo()
            Phase.RUSH_RESULT -> renderRushResult()
            Phase.RESULT -> renderResult()
        }
    }

    private fun renderLoading() {
        dialog.setContent("♟ Chess Readiness", null) { body("Checking…", color = 0xFF999999.toInt()) }
    }

    private fun renderBlocked() {
        dialog.setContent("♟ Chess Readiness", "Test unavailable") {
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

    /** "14:37" — local clock time at which the rate-limit block lifts. */
    private fun formatRetryClock(retryAt: Long): String =
        Instant.ofEpochMilli(retryAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    /** "1 h 23 min" / "42 min" (rounded up) — wait left until [retryAt]. */
    private fun formatRetryRemaining(retryAt: Long): String {
        val totalMin = (((retryAt - System.currentTimeMillis()) + 59999L) / 60000L)
            .toInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    private fun renderSleep() {
        dialog.setContent("♟ Chess Readiness", "Step 1 · Sleep") {
            if (garminSleepScore != null) {
                body("✓ Garmin sleep score: $garminSleepScore", color = 0xFF66BB6A.toInt(), size = 15, bold = true)
                primaryButton("Next") { advanceFromSleep(garminSleepScore) }
            } else {
                sleepField = numberField("Sleep score (0–100)", "", 3)
                primaryButton("Next") {
                    advanceFromSleep(sleepField?.text?.toString()?.toIntOrNull())
                }
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun advanceFromSleep(score: Int?) {
        if (score == null) return
        sleepScore = score
        sleepFromGarmin = garminSleepScore != null
        persist(SessionStep.CLARITY)
        phase = Phase.CLARITY
        render()
    }

    private fun renderClarity() {
        dialog.setContent("♟ Chess Readiness", "Step 2 · Rate yourself 1–10") {
            slider("Stress", "stressed", "calm", stress, maxValue = 10) { stress = it }
            slider("Focus", "scattered", "sharp", focus, maxValue = 10) { focus = it }
            slider("Energy", "drained", "energized", energy, maxValue = 10) { energy = it }
            primaryButton("Next") {
                persist(SessionStep.PUZZLE_GO, puzzleIdx = 0)
                puzzleIndex = 0
                phase = Phase.PUZZLE_GO
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderPuzzleGo() {
        val n = puzzleIndex + 1
        dialog.setContent(
            "♟ Chess Readiness",
            "Step 3 · Rated Puzzle $n/${ChessReadinessEngine.RATED_PUZZLE_COUNT}"
        ) {
            body("Solve ONE Rated Puzzle — first try, no clues.")
            hint("Timer starts on Start. Tap the ♟ bubble when done.")
            primaryButton("Start") {
                stepStartedAt = System.currentTimeMillis()
                persist(SessionStep.PUZZLE_RESULT, timerAnchor = stepStartedAt)
                dismiss() // user goes to solve the puzzle in the chess app
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderPuzzleResult() {
        val n = puzzleIndex + 1
        dialog.setContent(
            "♟ Chess Readiness",
            "Step 3 · Puzzle $n/${ChessReadinessEngine.RATED_PUZZLE_COUNT} — result"
        ) {
            body("Solved on the first attempt?")
            chipRow(listOf("Solved", "Failed"), puzzleSolved.toChipIndex()) { i ->
                puzzleSolved = if (i == 0) true else false
                render()
            }
            var nextButton: android.widget.TextView? = null
            fun updateNext() {
                val btn = nextButton ?: return
                val ready = puzzleSolved != null &&
                    (puzzleSolved == false || puzzleTimeText.isNotBlank())
                btn.isEnabled = ready
                btn.alpha = if (ready) 1f else 0.5f
            }
            if (puzzleSolved == true) {
                spacer(8)
                puzzleTimeField = numberField("Seconds", puzzleTimeText, 3).also { field ->
                    field.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            puzzleTimeText = s?.toString().orEmpty()
                            updateNext()
                        }
                    })
                }
            }
            nextButton = primaryButton(
                if (puzzleIndex + 1 < ChessReadinessEngine.RATED_PUZZLE_COUNT)
                    "Next puzzle" else "Next: Puzzle Rush"
            ) { submitPuzzleResult() }
            updateNext()
            textButton("Not done yet — restart timer") {
                puzzleSolved = null
                puzzleTimeText = ""
                persist(SessionStep.PUZZLE_GO)
                phase = Phase.PUZZLE_GO
                render()
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun submitPuzzleResult() {
        val effective = if (puzzleSolved == false)
            ChessReadinessEngine.PUZZLE_FAIL_TIME_SEC
        else puzzleTimeField?.text?.toString()?.toIntOrNull() ?: 0
        puzzleTimes = puzzleTimes + effective
        puzzleTimeText = ""
        // Credit the linked habit with the minutes this puzzle took (rounded
        // to the nearest minute, minimum 1) — written to the habit's minutes
        // secondary value, NOT its session count.
        val puzzleMinutes = Math.round(effective / 60.0).toInt().coerceAtLeast(1)
        ChessHabitCredit.grant(
            context, ChessReadinessStore.linkedPuzzleHabit(context), puzzleMinutes
        )
        if (puzzleIndex + 1 < ChessReadinessEngine.RATED_PUZZLE_COUNT) {
            puzzleIndex += 1
            persist(SessionStep.PUZZLE_GO, puzzleIdx = puzzleIndex)
            phase = Phase.PUZZLE_GO
        } else {
            persist(SessionStep.RUSH_GO)
            phase = Phase.RUSH_GO
        }
        render()
    }

    private fun renderRushGo() {
        dialog.setContent("♟ Chess Readiness", "Step 4 · 3-Minute Puzzle Rush") {
            body("Play one 3-minute Puzzle Rush.")
            hint("Tap the ♟ bubble when done.")
            primaryButton("Start") {
                persist(SessionStep.RUSH_RESULT)
                dismiss() // user goes to play the rush in the chess app
            }
            textButton("Abandon test") { abandon() }
        }
    }

    private fun renderRushResult() {
        dialog.setContent("♟ Chess Readiness", "Step 4 · Puzzle Rush — result") {
            var computeButton: android.widget.TextView? = null
            fun updateCompute() {
                val btn = computeButton ?: return
                val ready = rushScoreText.isNotBlank() && rushStrikes >= 0
                btn.isEnabled = ready
                btn.alpha = if (ready) 1f else 0.5f
            }
            rushScoreField = numberField("Puzzles solved", rushScoreText, 3).also { field ->
                field.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        rushScoreText = s?.toString().orEmpty()
                        updateCompute()
                    }
                })
            }
            spacer(8)
            body("Strikes (failures)", bold = true)
            chipRow(listOf("0", "1", "2", "3"), rushStrikes) { rushStrikes = it; updateCompute() }
            computeButton = primaryButton("Compute readiness") { submitRushResult() }
            updateCompute()
            textButton("Abandon test") { abandon() }
        }
    }

    private fun submitRushResult() {
        val rushScore = rushScoreField?.text?.toString()?.toIntOrNull() ?: return
        val input = ChessReadinessEngine.ReadinessInput(
            sleepScore = sleepScore ?: 0,
            clarityAverage = ChessReadinessEngine.clarityAverageFromSliders(stress, focus, energy),
            puzzleTimesSec = puzzleTimes,
            rushScore = rushScore,
            rushAllTimeHigh = rushAth,
            rushStrikes = rushStrikes.coerceAtLeast(0)
        )
        val now = System.currentTimeMillis()

        // Re-verify the rate-limit gate at submission time
        val history = ChessReadinessStore.loadHistory(context)
        when (val gate = ChessReadinessEngine.checkGate(history, now)) {
            is ChessReadinessEngine.GateStatus.Blocked -> {
                blockedMessage = gate.error.message
                blockedRetryAt = gate.error.retryAt
                ChessReadinessLogStore.logBlockedAttempt(context, gate.error.message)
                ChessReadinessStore.clearSession(context)
                phase = Phase.BLOCKED
                render()
            }
            is ChessReadinessEngine.GateStatus.Allowed -> {
                // History drives the adaptive percentile pass bars; the new
                // test is appended only after evaluation, so it is not in it.
                val r = ChessReadinessEngine.evaluate(
                    input, now, history,
                    greenTargetFraction =
                        ChessReadinessStore.greenTargetPercent(context) / 100.0
                )
                ChessReadinessStore.appendTest(
                    context,
                    ChessReadinessEngine.ReadinessTest(
                        timestamp = r.timestamp,
                        ccrs = r.ccrs,
                        state = r.state.name,
                        // v3.1 telemetry — feeds the form-relative baselines
                        // and the survey calibration of FUTURE tests.
                        clarityAverage = input.clarityAverage,
                        puzzleAvgSec = if (input.puzzleTimesSec.isEmpty()) null
                        else Math.round(input.puzzleTimesSec.average()).toInt(),
                        rushScore = input.rushScore,
                        pPuzzle = r.pPuzzle,
                        pRush = r.pRush
                    )
                )
                // Permanent detailed telemetry log (stats screen source of truth):
                // full inputs + sub-scores + session duration.
                ChessReadinessLogStore.logTest(
                    context, r, input,
                    sleepScore = sleepScore ?: 0,
                    sleepFromGarmin = sleepFromGarmin,
                    stress = stress,
                    focus = focus,
                    energy = energy,
                    sessionStartedAt = sessionStartedAt
                )
                // The rush run itself lasts 3 minutes — credit those to the
                // linked habit's minutes value (plus the usual +1 session).
                ChessHabitCredit.grant(
                    context,
                    ChessReadinessStore.linkedRushHabit(context),
                    ChessReadinessEngine.RUSH_RUN_MINUTES
                )
                val newAth = ChessReadinessEngine.nextAllTimeHigh(rushAth, input.rushScore)
                if (newAth != rushAth) {
                    ChessReadinessStore.saveRushAllTimeHigh(context, newAth)
                }
                ChessReadinessStore.clearSession(context)
                result = r
                phase = Phase.RESULT
                render()
            }
        }
    }

    private fun renderResult() {
        val r = result ?: run { dismiss(); return }
        dialog.setContent("♟ Chess Readiness", null) {
            bigScore("${r.ccrs}", r.state.colorHex)
            stateLabel(r.state.name.replace("_", " "), r.state.colorHex)
            spacer(8)
            body(r.state.message)
            spacer(10)
            r.state.permitted.forEach { bullet("✓ $it", 0xFF66BB6A.toInt()) }
            r.state.prohibited.forEach { bullet("✗ $it", 0xFFEF4444.toInt()) }
            spacer(8)
            body(
                "Sleep ${r.sSleep} · Clarity ${r.sClarity} · " +
                    "Puzzles ${r.pPuzzle} · Rush ${r.pRush}",
                color = 0xFF999999.toInt(), size = 13
            )
            val basis = if (r.thresholdBasis ==
                ChessReadinessEngine.ThresholdBasis.PERCENTILE
            ) "your last ${r.thresholdSampleSize} tests" else "cold start"
            hint("Pass bar — Green ≥ ${r.greenThreshold} · Yellow ≥ ${r.yellowThreshold} ($basis)")
            // Survey-calibration transparency: the survey only counts as
            // much as it has historically deserved.
            val trustPct = Math.round(r.surveyWeight * 100).toInt()
            hint(
                if (r.surveyMae != null)
                    "Survey trust ${trustPct}% · avg gap ${"%.1f".format(r.surveyMae)} " +
                        "(last ${r.surveySampleSize} tests)"
                else
                    "Survey trust ${trustPct}% — calibrating " +
                        "(${r.surveySampleSize}/${ChessReadinessEngine.CALIBRATION_MIN_SAMPLES} tests)"
            )
            spacer(8)
            val validUntil = Instant.ofEpochMilli(r.validUntil)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            hint("Valid until $validUntil")
            if (r.state == ChessReadinessEngine.ReadinessState.RED_LIGHT) {
                primaryButton("Leave", danger = true) { leaveChess() }
            } else {
                primaryButton("Back to chess") {
                    // Landing in the chess app with rated play prohibited.
                    // The app is ALREADY the foreground app behind this
                    // overlay (no activity was started), so no new
                    // window-state event will fire — show the casual-only
                    // warning NOW instead of staying silent until the
                    // next leave-and-return.
                    if (r.state == ChessReadinessEngine.ReadinessState.YELLOW_LIGHT &&
                        ChessReadinessStore.enforcementEnabledAt(context) > 0L
                    ) {
                        ChessGuardReactions.markYellowWarned()
                        try {
                            ChessGuardWallOverlay.showWarning(context)
                        } catch (_: Exception) {
                            // Overlay grant missing — the guard re-warns on
                            // the next real entry into the chess app.
                        }
                    }
                    dismiss()
                }
            }
        }
    }

    /** Red result: exit to the home screen (closes the chess session). */
    private fun leaveChess() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (_: Exception) { /* best-effort */ }
        dismiss()
    }

    private fun Boolean?.toChipIndex(): Int = when (this) {
        true -> 0
        false -> 1
        null -> -1
    }
}

/**
 * Credits time spent on a readiness-test step to [habitName] (if linked in
 * Settings): adds [minutes] to the habit's first-class MINUTES slot
 * (`minutes:<habitName>` in habitsdb.txt) and +[sessions] to the
 * habit's own session-count slot, in one atomic read-modify-write — the
 * same write the bubble timer uses
 * ([HabitsRepository.incrementHabitWithMinutes]).
 *
 * Minute-based credits are cumulative durations, not binary "did it"
 * counts, so they bypass the max-1/day cap (mirroring the IPC v2
 * EXTRA_MINUTES rule); a pure session credit ([minutes] <= 0) still
 * respects it. Fire-and-forget on IO; emits the increment bus so open UIs
 * refresh, records a timestamp.
 */
object ChessHabitCredit {
    fun grant(context: Context, habitName: String, minutes: Int, sessions: Int = 1) {
        if (habitName.isBlank()) return
        if (minutes <= 0 && sessions <= 0) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val habitsRepo = HabitsRepository()
                val settings = SettingsRepository(appContext).settingsFlow.first()
                val uriStr = settings.fileUri
                if (uriStr.isEmpty()) return@launch
                val uri = Uri.parse(uriStr)

                // The max-1/day cap only makes sense for count-based credits;
                // minutes are cumulative durations (same rule as IPC v2).
                if (minutes <= 0 && habitName in settings.maxOneHabits) {
                    val db = habitsRepo.loadDatabase(uri, appContext)
                    val today = LocalDate.now().toString()
                    if ((db[habitName]?.get(today) ?: 0) >= 1) return@launch
                }

                habitsRepo.incrementHabitWithMinutes(
                    uri, appContext, habitName, minutes.coerceAtLeast(0), sessions
                )
                HabitIncrementBus.emit(habitName)
                try {
                    HabitTimestampRepository(appContext).addTimestamp(habitName)
                } catch (_: Exception) { /* timestamp optional */ }
            } catch (_: Exception) { /* credit is best-effort */ }
        }
    }
}
