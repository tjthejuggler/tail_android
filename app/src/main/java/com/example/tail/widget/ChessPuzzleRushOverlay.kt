package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import com.example.tail.data.PuzzleRushSessionRecord

/**
 * Persistence for the OFFICIAL Puzzle Rush timer (the habit linked as
 * "Puzzle Rush habit" in Settings, timed via the floating bubble).
 *
 * Keeps the report that is still DUE: when the rush habit's timer stops,
 * the session's start/end times are parked here and a
 * [ChessPuzzleRushOverlay] asks the user how the run went. Persisting the
 * pending report (plain [SharedPreferences], like the readiness session)
 * means the prompt survives the bubble service being killed — the next
 * tap on the bubble re-opens it until it is answered or expires.
 */
object ChessPuzzleRushStore {

    private const val PREFS_NAME = "tail_chess_puzzle_rush"
    private const val KEY_STARTED_AT = "pending_started_at"
    private const val KEY_ENDED_AT = "pending_ended_at"

    /** An unanswered rush report expires after this long (ms). */
    const val PENDING_TIMEOUT_MS = 30L * 60 * 1000

    /** A finished timer session whose result is still due. */
    data class PendingSession(
        /** Epoch millis when the timer was started. */
        val startedAt: Long,
        /** Epoch millis when the timer was stopped. */
        val endedAt: Long
    ) {
        /** Wall-clock length of the session in seconds (never negative). */
        val durationSec: Long
            get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0L)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Parks a due rush report for the timer session that just ended. */
    fun savePending(context: Context, startedAt: Long, endedAt: Long) {
        prefs(context).edit()
            .putLong(KEY_STARTED_AT, startedAt)
            .putLong(KEY_ENDED_AT, endedAt)
            .apply()
    }

    /**
     * The due rush report, or null when there is none / it expired
     * ([PENDING_TIMEOUT_MS] since the timer stopped). Expired reports are
     * discarded automatically.
     */
    fun loadPending(context: Context): PendingSession? {
        val p = prefs(context)
        val startedAt = p.getLong(KEY_STARTED_AT, 0L)
        val endedAt = p.getLong(KEY_ENDED_AT, 0L)
        if (startedAt <= 0L || endedAt <= startedAt) return null
        if (System.currentTimeMillis() - endedAt > PENDING_TIMEOUT_MS) {
            clearPending(context)
            return null
        }
        return PendingSession(startedAt = startedAt, endedAt = endedAt)
    }

    /** Clears the due report (answered or skipped). */
    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_STARTED_AT).remove(KEY_ENDED_AT).apply()
    }
}

/**
 * ♟ Puzzle Rush — end-of-session report, rendered as a floating overlay
 * dialog by [FloatingBubbleService] (same mechanism as the readiness
 * wizard, so the chess app stays the focused app underneath).
 *
 * Asks exactly what the v1 readiness test asked for its rush step —
 * puzzles solved + strikes — plus one follow-up, only when the run had
 * strikes: whether the user reviewed the puzzles they got wrong. The
 * answers (with the session's start/end times) are logged to
 * [ChessReadinessLogStore] and feed the Puzzle Rush section of the
 * Chess Stats screen.
 */
class ChessPuzzleRushOverlay(service: Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)

    // ── Report state (kept when the dialog re-renders) ─────────────────────
    private var rushScoreText = ""
    private var rushStrikes = -1
    private var reviewedWrong: Boolean? = null

    // Handles to input views of the currently shown step
    private var rushScoreField: EditText? = null

    // ── Lifecycle ───────────────────────────────────────────────────────────

    fun show() {
        dialog.show()
        render()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    // ── Step rendering ──────────────────────────────────────────────────────

    private fun render() {
        val pending = ChessPuzzleRushStore.loadPending(context)
        dialog.setContent("♟ Puzzle Rush", "Timer session — result") {
            if (pending != null) {
                hint(
                    "Session length " +
                        WidgetTimerStore.formatElapsed(pending.durationSec * 1000)
                )
            }
            spacer(8)
            var saveButton: TextView? = null
            fun updateSave() {
                val btn = saveButton ?: return
                // The review question only exists for runs with strikes.
                val ready = rushScoreText.isNotBlank() &&
                    rushStrikes >= 0 &&
                    (rushStrikes == 0 || reviewedWrong != null)
                btn.isEnabled = ready
                btn.alpha = if (ready) 1f else 0.5f
            }
            rushScoreField = numberField("Puzzles solved", rushScoreText, 3).also { field ->
                field.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        rushScoreText = s?.toString().orEmpty()
                        updateSave()
                    }
                })
            }
            spacer(8)
            body("Strikes (failures)", bold = true)
            chipRow(listOf("0", "1", "2", "3"), rushStrikes) { rushStrikes = it; updateSave() }
            if (rushStrikes > 0) {
                spacer(8)
                body("Reviewed the puzzles you got wrong?", bold = true)
                chipRow(
                    listOf("No", "Yes"),
                    when (reviewedWrong) {
                        true -> 1
                        false -> 0
                        null -> -1
                    }
                ) { reviewedWrong = it == 1; updateSave() }
            }
            saveButton = primaryButton("Save result") { submit() }
            updateSave()
            textButton("Skip — don't record") {
                ChessPuzzleRushStore.clearPending(context)
                dismiss()
            }
        }
    }

    // ── Submission ──────────────────────────────────────────────────────────

    private fun submit() {
        val pending = ChessPuzzleRushStore.loadPending(context)
            ?: run { dismiss(); return }
        val rushScore = rushScoreField?.text?.toString()?.toIntOrNull() ?: return
        val ath = ChessReadinessStore.lastRushAllTimeHigh(context)

        // Permanent detailed telemetry log (Chess Stats screen source of
        // truth): score, strikes, review answer and the session's times.
        ChessReadinessLogStore.logRushSession(
            context,
            PuzzleRushSessionRecord(
                timestamp = System.currentTimeMillis(),
                startedAt = pending.startedAt,
                durationSec = pending.durationSec,
                score = rushScore,
                strikes = rushStrikes.coerceAtLeast(0),
                // Null for strike-free runs — the question was never asked.
                reviewedWrong = if (rushStrikes > 0) reviewedWrong else null,
                allTimeHigh = ath
            )
        )
        // The rush all-time high feeds the record line of the stats chart
        // (and the v1 readiness baseline, should v1 ever be re-enabled).
        val newAth = ChessReadinessEngine.nextAllTimeHigh(ath, rushScore)
        if (newAth != ath) {
            ChessReadinessStore.saveRushAllTimeHigh(context, newAth)
        }
        ChessPuzzleRushStore.clearPending(context)
        dismiss()
    }
}
