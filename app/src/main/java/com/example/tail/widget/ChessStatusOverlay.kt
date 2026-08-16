package com.example.tail.widget

import kotlin.math.roundToInt

/**
 * ♟ Chess Status — a floating overlay dialog rendered by
 * [FloatingBubbleService] over the chess app.
 *
 * Shown from the bubble menu INSTEAD of the "Chess Readiness" entry while
 * rated play is authorized (Phase 1 green light inside its 60-minute window
 * and no Yellow/Red audit since). Answers, at a glance:
 *  - how well the session is going (last ΔE + its classification)
 *  - how close the user is to being kicked out of rated play
 *    (ΔE margin to the −0.35 severe floor, flagged-audit rule, and the
 *    60-minute capacity ceiling)
 *  - how much authorization time is left
 *  - how many games have been audited (shared) this session
 *  - the standing instruction: share every finished game to Tail
 */
class ChessStatusOverlay(service: android.content.Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)

    fun show() {
        dialog.show()
        render()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    private fun render() {
        val now = System.currentTimeMillis()
        val authorized = ChessPhase2Store.ratedPlayAuthorized(context, now)
        val lastTest = ChessReadinessStore.lastTest(context)
        val session = ChessPhase2Store.currentSessionAudits(context, now)
        val minutesUsed = session.sumOf { it.estimatedMinutes }
        val lastAudit = session.lastOrNull()

        dialog.setContent("♟ Chess Status", null) {
            if (!authorized) {
                // Defensive: the menu only offers Status while authorized,
                // but the window may have expired while the menu was open.
                stateLabel("READINESS REQUIRED", "#EF4444")
                spacer(8)
                body(
                    "Rated play is not currently authorized. Run the " +
                        "♟ Chess Readiness test from the Tail bubble."
                )
                primaryButton("Close") { dismiss() }
                return@setContent
            }

            stateLabel("RATED PLAY AUTHORIZED", "#22C55E")
            spacer(10)

            val msLeft = (lastTest?.timestamp ?: now) +
                ChessReadinessEngine.SESSION_VALIDITY_MS - now
            keyValue("Time left", "${(msLeft / 60000L).coerceAtLeast(0)} min")
            keyValue("Games audited this session", "${session.size}")
            keyValue(
                "Capacity used",
                "${minutesUsed.roundToInt()} / ${ChessPhase2Engine.SESSION_CAP_MINUTES} min"
            )

            if (lastAudit != null) {
                spacer(8)
                body("Last audited game", bold = true)
                keyValue("ΔE", "%+.3f".format(lastAudit.deltaE))
                keyValue(
                    "Verdict",
                    lastAudit.outputState.replace('_', ' ').lowercase()
                )
                if (lastAudit.accuracyCounted) {
                    keyValue("Accuracy", "${lastAudit.caps2Accuracy.roundToInt()}%")
                }
            }

            spacer(10)
            body("How you get kicked out", bold = true)
            bullet(
                "ΔE below −0.35 on any audited game",
                0xFFEF4444.toInt()
            )
            bullet(
                "One flagged audit (yellow) — rated play ends for the session",
                0xFFEF4444.toInt()
            )
            bullet(
                "${ChessPhase2Engine.SESSION_CAP_MINUTES} minutes of rated play (capacity ceiling)",
                0xFFEF4444.toInt()
            )

            spacer(12)
            body("After every rated game:", bold = true)
            body("Share → ♟ Chess Audit (Tail)", color = 0xFF66CCFF.toInt())
            hint("The share button sits on the game-over screen — Tail audits the game automatically.")

            primaryButton("Close") { dismiss() }
        }
    }
}
