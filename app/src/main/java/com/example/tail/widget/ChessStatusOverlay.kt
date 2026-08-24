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
 *    (session strain vs the terminate bar — which includes the readiness
 *    buffer — plus the hard cutoffs and the 60-minute capacity ceiling)
 *  - the personal ΔE bars the audits are judged against
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

            // v2 post-game engine: show the research-system session gauges
            // instead of the v1 strain/ΔE view.
            if (ChessPhase2V2Store.isV2(context)) {
                renderV2(now, lastTest)
                return@setContent
            }

            stateLabel("RATED PLAY AUTHORIZED", "#22C55E")
            spacer(10)

            val msLeft = (lastTest?.timestamp ?: now) +
                ChessReadinessEngine.SESSION_VALIDITY_MS - now
            val ccrs = ChessPhase2Store.authorizingReadinessCcrs(context, now)
            val buffer = ChessPhase2Engine.readinessBuffer(ccrs)
            val terminateAt = ChessPhase2Engine.STRAIN_TERMINATE_BASE + buffer
            val strain = session.sumOf { it.strain }
            val floors = ChessPhase2Engine.computeDeltaFloors(
                ChessPhase2Store.recentDeltaE(context, now), now
            )
            keyValue("Time left", "${(msLeft / 60000L).coerceAtLeast(0)} min")
            keyValue("Games audited this session", "${session.size}")
            keyValue(
                "Session strain",
                "${strain.roundToInt()} / ${terminateAt.roundToInt()}" +
                    if (buffer > 0) "  (+$buffer readiness)" else ""
            )
            keyValue(
                "Your ΔE bars",
                "pause < ${"%.2f".format(floors.pivot)} · stop < ${"%.2f".format(floors.terminate)}" +
                    if (floors.basis == ChessPhase2Engine.FloorBasis.PERCENTILE)
                        "  (last ${floors.sampleSize} games)" else "  (cold start)"
            )
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
                "Catastrophic loss — ΔE ≤ ${ChessPhase2Engine.CATASTROPHIC_DELTA_E} (hard cutoff)",
                0xFFEF4444.toInt()
            )
            bullet(
                "One game with everything wrong (result + accuracy + blunders)",
                0xFFEF4444.toInt()
            )
            bullet(
                "Session strain reaching ${terminateAt.roundToInt()} — accumulated bad games",
                0xFFEF4444.toInt()
            )
            bullet(
                "${ChessPhase2Engine.SESSION_CAP_MINUTES} minutes of rated play (capacity ceiling)",
                0xFFEF4444.toInt()
            )
            bullet(
                "One bad game alone only pauses rated play (yellow) — never ends the session",
                0xFF22C55E.toInt()
            )

            spacer(12)
            body("After every rated game:", bold = true)
            body("Share → ♟ Chess Audit (Tail)", color = 0xFF66CCFF.toInt())
            hint("The share button sits on the game-over screen — Tail audits the game automatically.")

            primaryButton("Close") { dismiss() }
        }
    }

    /** v2 gauges: fatigue budget, loss streak, baselines, ACWR, rules. */
    private fun renderV2(now: Long, lastTest: ChessReadinessEngine.ReadinessTest?) {
        val session = ChessPhase2V2Store.currentSessionGames(context, now)
        val minutesUsed = session.sumOf { it.estimatedMinutes }
        val lastGame = session.lastOrNull()
        val consecutiveLosses = session
            .asReversed()
            .takeWhile {
                it.result == ChessPhase2Engine.GameResult.LOSS.name
            }.size
        val acwr = try {
            ChessPhase2V2Store.acwrInput(
                ChessReadinessLogStore.loadGames(context), now
            )
        } catch (_: Exception) { null }

        dialog.setContent("♟ Chess Status", null) {
            stateLabel("RATED PLAY AUTHORIZED · AUDIT v2", "#22C55E")
            spacer(10)

            val msLeft = (lastTest?.timestamp ?: now) +
                ChessReadinessEngine.SESSION_VALIDITY_MS - now
            keyValue("Time left", "${(msLeft / 60000L).coerceAtLeast(0)} min")
            keyValue("Games audited this session", "${session.size}")
            keyValue(
                "Fatigue budget",
                "${minutesUsed.roundToInt()} / " +
                    "${ChessPhase2V2Engine.SESSION_RED_MINUTES} min " +
                    "(yellow at ${ChessPhase2V2Engine.SESSION_YELLOW_MINUTES})"
            )
            keyValue(
                "Loss streak",
                "$consecutiveLosses consecutive " +
                    "(yellow at ${ChessPhase2V2Engine.STREAK_YELLOW}, " +
                    "red at ${ChessPhase2V2Engine.STREAK_RED})"
            )
            if (acwr != null && acwr.ready) {
                keyValue(
                    "Workload ratio (7d : 28d)",
                    if (acwr.ratio.isInfinite()) "∞"
                    else "%.2f".format(acwr.ratio) +
                        "  (yellow ≥ ${ChessPhase2V2Engine.ACWR_YELLOW}, " +
                        "red ≥ ${ChessPhase2V2Engine.ACWR_RED})"
                )
            }

            if (lastGame != null) {
                spacer(8)
                body("Last audited game", bold = true)
                keyValue(
                    "Result",
                    lastGame.result.replace('_', ' ').lowercase()
                )
                keyValue(
                    "Verdict",
                    lastGame.outputState.replace('_', ' ').lowercase()
                )
            }

            spacer(10)
            body("How you get kicked out", bold = true)
            bullet(
                "More than ${ChessPhase2V2Engine.SESSION_RED_MINUTES} min of " +
                    "continuous play (fatigue ceiling)",
                0xFFEF4444.toInt()
            )
            bullet(
                "${ChessPhase2V2Engine.STREAK_RED} consecutive losses (loss-chasing)",
                0xFFEF4444.toInt()
            )
            bullet(
                "Tilt vector: far faster AND far less accurate than your norm " +
                    "in a lost game",
                0xFFEF4444.toInt()
            )
            bullet(
                "Workload ratio ≥ ${ChessPhase2V2Engine.ACWR_RED} (chronic overload)",
                0xFFEF4444.toInt()
            )
            bullet(
                "A single loss never stops you — performance rises after one " +
                    "loss (bounce-back effect)",
                0xFF22C55E.toInt()
            )
            bullet(
                "Yellow persists until a win/draw at your normal accuracy after " +
                    "15+ min (hysteresis)",
                0xFFEAB308.toInt()
            )

            spacer(12)
            body("After every rated game:", bold = true)
            body("Share → ♟ Chess Audit (Tail)", color = 0xFF66CCFF.toInt())
            hint("The share button sits on the game-over screen — Tail audits the game automatically.")

            primaryButton("Close") { dismiss() }
        }
    }
}
