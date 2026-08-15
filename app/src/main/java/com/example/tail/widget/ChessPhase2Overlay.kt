package com.example.tail.widget

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView

/**
 * ♟ Chess Readiness — Phase 2 Post-Game Performance Audit, rendered as a
 * floating overlay dialog by [FloatingBubbleService] (no Activity is
 * started, so the chess app stays the focused/dominant app underneath).
 *
 * The user transcribes the Game Review telemetry of the rated game they
 * just finished; the engine computes the Elo-Adjusted Expected Score Delta
 * (ΔE) and issues CONTINUE_RATED / PIVOT_TO_DRILLS / TERMINATE_SESSION.
 *
 * Copy is minimal: the fields ARE the instructions.
 */
class ChessPhase2Overlay(service: Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)

    private enum class Phase { FORM, RESULT }

    private var phase = Phase.FORM

    // ── Form state (kept across re-renders) ─────────────────────────────────
    private var timeControl = ChessPhase2Engine.TimeControl.BLITZ
    private var userRatingText = ""
    private var opponentRatingText = ""
    private var gameResult: ChessPhase2Engine.GameResult? = null
    private var accuracyText = ""
    private var blunderText = ""
    private var unforced = false
    private var shortGame = false
    private var sessionMinsText = ""

    private var accHistories: Map<ChessPhase2Engine.TimeControl, List<Double>> = emptyMap()
    private var sessionHasPriorGames = false
    private var auditResult: ChessPhase2Engine.AuditResult? = null

    // Handles to the currently shown step's views
    private var userRatingField: EditText? = null
    private var opponentRatingField: EditText? = null
    private var accuracyField: EditText? = null
    private var blunderField: EditText? = null
    private var sessionMinsField: EditText? = null
    private var submitButton: TextView? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun show() {
        dialog.show()

        // Pre-set from local storage: last time control, session minutes
        timeControl = ChessPhase2Store.lastTimeControl(context)
            ?: ChessPhase2Engine.TimeControl.BLITZ
        sessionHasPriorGames = ChessPhase2Store.currentSessionAudits(context).isNotEmpty()
        if (sessionHasPriorGames) {
            val last = ChessPhase2Store.lastSessionMins(context)
            if (last > 0) sessionMinsText = last.toString()
        }
        accHistories = ChessPhase2Engine.TimeControl.entries.associateWith {
            ChessPhase2Store.accuracyHistory(context, it)
        }

        render()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render() {
        userRatingField = null
        opponentRatingField = null
        accuracyField = null
        blunderField = null
        sessionMinsField = null
        submitButton = null
        if (phase == Phase.FORM) renderForm() else renderResult()
    }

    private fun renderForm() {
        dialog.setContent("♟ Post-Game Audit", "From the Game Review screen:") {
            chipRow(
                ChessPhase2Engine.TimeControl.entries.map { it.label.substringBefore(" /") },
                ChessPhase2Engine.TimeControl.entries.indexOf(timeControl)
            ) {
                timeControl = ChessPhase2Engine.TimeControl.entries[it]
                render()
            }

            userRatingField = numberField("Your rating", userRatingText, 4).watchState()
            opponentRatingField = numberField("Opponent rating", opponentRatingText, 4).watchState()

            body("Result", bold = true)
            chipRow(
                ChessPhase2Engine.GameResult.entries.map { it.label.uppercase() },
                ChessPhase2Engine.GameResult.entries.indexOf(gameResult).let {
                    if (gameResult == null) -1 else it
                }
            ) { gameResult = ChessPhase2Engine.GameResult.entries[it]; render() }

            accuracyField = numberField(
                "Accuracy %", accuracyText, 5, decimal = true
            ).watchState()
            blunderField = numberField("Blunders", blunderText, 2).watchState()

            checkRow("Unforced blunder (before time scramble)", unforced) {
                unforced = it
            }
            checkRow("Short game (< 10 moves)", shortGame) { shortGame = it }

            sessionMinsField = numberField(
                "Session minutes", sessionMinsText, 3
            ).watchState()

            submitButton = primaryButton("Run audit", enabled = formValid()) { submit() }
            textButton("Cancel") { dismiss() }
        }
    }

    /** Keeps the controller's text state in sync + re-validates the button. */
    private fun EditText.watchState(): EditText {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                when (this@watchState) {
                    userRatingField -> userRatingText = s?.toString().orEmpty()
                    opponentRatingField -> opponentRatingText = s?.toString().orEmpty()
                    accuracyField -> accuracyText = s?.toString().orEmpty()
                    blunderField -> blunderText = s?.toString().orEmpty()
                    sessionMinsField -> sessionMinsText = s?.toString().orEmpty()
                }
                submitButton?.let { btn ->
                    val valid = formValid()
                    btn.isEnabled = valid
                    btn.alpha = if (valid) 1f else 0.5f
                }
            }
        })
        return this
    }

    private fun formValid(): Boolean {
        val user = userRatingText.toIntOrNull()
        val opp = opponentRatingText.toIntOrNull()
        val acc = accuracyText.toDoubleOrNull()
        val blunders = blunderText.toIntOrNull()
        val mins = sessionMinsText.toIntOrNull()
        return user != null && opp != null && gameResult != null &&
            (shortGame || (acc != null && acc in 0.0..100.0)) &&
            blunders != null && blunders >= 0 &&
            mins != null && mins >= 0
    }

    private fun submit() {
        if (!formValid()) return
        val now = System.currentTimeMillis()
        val input = ChessPhase2Engine.GameInput(
            timeControl = timeControl,
            userRating = userRatingText.toIntOrNull() ?: 0,
            opponentRating = opponentRatingText.toIntOrNull() ?: 0,
            gameResult = gameResult ?: ChessPhase2Engine.GameResult.LOSS,
            caps2Accuracy = accuracyText.toDoubleOrNull() ?: 0.0,
            blunderCount = blunderText.toIntOrNull() ?: 0,
            hasUnforcedBlunder = unforced,
            sessionElapsedMins = sessionMinsText.toIntOrNull() ?: 0,
            shortGame = shortGame,
            accuracyHistory = accHistories[timeControl].orEmpty()
        )
        val session = ChessPhase2Store.currentSessionAudits(context, now).map {
            ChessPhase2Engine.SessionGame(it.timestamp, it.timeControl, it.outputState)
        }
        val r = ChessPhase2Engine.evaluate(input, session, now)

        ChessPhase2Store.saveTimeControl(context, timeControl)
        ChessPhase2Store.saveLastSessionMins(context, input.sessionElapsedMins)
        if (!shortGame && input.caps2Accuracy > 0) {
            ChessPhase2Store.appendAccuracy(context, timeControl, input.caps2Accuracy)
        }
        ChessPhase2Store.appendAudit(
            context,
            ChessPhase2Store.Phase2Audit(
                timestamp = now,
                timeControl = timeControl.name,
                outputState = r.outputState.name,
                deltaE = r.deltaE,
                caps2Accuracy = input.caps2Accuracy,
                accuracyCounted = !shortGame && input.caps2Accuracy > 0
            )
        )
        auditResult = r
        phase = Phase.RESULT
        render()
    }

    private fun renderResult() {
        val r = auditResult ?: run { dismiss(); return }
        dialog.setContent("♟ Post-Game Audit", null) {
            stateLabel(r.outputState.name.replace("_", " "), r.outputState.colorHex)
            body(r.outputState.title, color = 0xFF999999.toInt(), size = 12)
            spacer(6)
            bigScore("%+.3f".format(r.deltaE), r.outputState.colorHex)
            stateLabel(ChessPhase2Engine.deltaEClassification(r.deltaE), "#999999")
            spacer(8)
            body(r.message)
            spacer(8)
            r.outputState.permitted.forEach { bullet("✓ $it", 0xFF66BB6A.toInt()) }
            r.outputState.prohibited.forEach { bullet("✗ $it", 0xFFEF4444.toInt()) }
            when (r.outputState) {
                ChessPhase2Engine.OutputState.TERMINATE_SESSION -> {
                    primaryButton("Leave chess — recover", danger = true) { leaveChess() }
                    textButton("Close") { dismiss() }
                }
                ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                    primaryButton("Back to chess (unrated / bots only)") { dismiss() }
                ChessPhase2Engine.OutputState.CONTINUE_RATED ->
                    primaryButton("Next rated game") { dismiss() }
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
}
