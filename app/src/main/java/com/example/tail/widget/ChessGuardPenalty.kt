package com.example.tail.widget

import android.content.Context
import android.util.Log

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Guard — automatic violation detection (the 24-hour penalty)
 * ════════════════════════════════════════════════════════════════════════
 *
 * Every chess.com game the app learns about — via the post-game share
 * sheet, the deferred reconciler, or the monthly archive poll — passes
 * through [ChessReadinessLogStore.logGames]. That single choke point calls
 * [evaluateAndApply] for each NEWLY logged game, so no path can smuggle a
 * violation past the detector.
 *
 * The rules (user, 2026-08-23):
 *  - RATED game that ended outside a valid GREEN authorization window
 *    (a YELLOW session does NOT authorize rated play) → penalty.
 *  - UNRATED / casual game is allowed only while the app was actually
 *    OPEN for casual play — i.e. the policy at the game's end moment was
 *    Allow(GREEN_SESSION) or Allow(YELLOW_SESSION). Casual games that
 *    ended during a lockout (RED rest, cool-down, daily cap, an active
 *    penalty) or during the bare re-test trust window (app open ONLY to
 *    take the test, not to play) → penalty.
 *  - A penalty locks the whole app — test entry included — for
 *    [ChessEnforcementPolicy.PENALTY_DURATION_MS] (24 h), overrides even
 *    a fresh GREEN pass, and is deduped by the game's log key so
 *    re-polls / re-shares never stack.
 *  - Games that ended before enforcement was switched on are exempt (no
 *    retroactive punishment of the pre-enforcement era).
 */
object ChessGuardPenalty {

    private const val TAG = "ChessGuardPenalty"

    /**
     * Checks one newly-logged game against the rules above and appends a
     * penalty when it was played unauthorized. Returns true when a new
     * penalty was applied. Never throws — detection must not break the
     * logging path that feeds it.
     */
    fun evaluateAndApply(
        context: Context,
        gameId: String,
        gameEndMs: Long,
        rated: Boolean
    ): Boolean {
        try {
            val enabledAt = ChessReadinessStore.enforcementEnabledAt(context)
            if (enabledAt <= 0L || gameEndMs < enabledAt) return false
            if (ChessReadinessStore.hasPenaltyForGame(context, gameId)) return false

            val tests = ChessReadinessStore.loadHistory(context)

            // Rule 1 — rated play requires a valid GREEN window (YELLOW
            // permits casual play only).
            if (rated) {
                val authorized = ChessDeferredGameReconciler.authorizedAtGameEnd(
                    tests = tests,
                    audits = ChessPhase2Store.loadAudits(context).map {
                        ChessDeferredGameReconciler.AuditStamp(it.timestamp, it.outputState)
                    },
                    gameEndMs = gameEndMs
                )
                if (!authorized) return append(context, gameId, rated = true)
                return false
            }

            // Rule 2 — casual (unrated) play is allowed only when the app
            // was open for casual play at the moment the game ended. The
            // policy is re-evaluated AT the game's end time (session
            // unknown for the past → null, which only ever *removes* the
            // in-progress-test allowance, never adds one). Penalties that
            // were applied AFTER the game must not count as "active then".
            val priorPenalties = ChessReadinessStore.loadPenalties(context)
                .filter { it.timestamp <= gameEndMs }
            val decisionAtEnd = ChessEnforcementPolicy.evaluate(
                enforcementEnabledAt = enabledAt,
                history = tests,
                session = null,
                penalties = priorPenalties,
                now = gameEndMs
            )
            val casualAllowed = decisionAtEnd is ChessEnforcementPolicy.Decision.Allow &&
                (
                    decisionAtEnd.reason == ChessEnforcementPolicy.Reason.GREEN_SESSION ||
                        decisionAtEnd.reason == ChessEnforcementPolicy.Reason.YELLOW_SESSION
                    )
            if (!casualAllowed) return append(context, gameId, rated = false)
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Penalty evaluation for game $gameId failed: ${e.message}")
            return false
        }
    }

    private fun append(context: Context, gameId: String, rated: Boolean): Boolean {
        val now = System.currentTimeMillis()
        ChessReadinessStore.appendPenalty(
            context,
            ChessEnforcementPolicy.Penalty(
                timestamp = now,
                gameId = gameId,
                expiresAt = now + ChessEnforcementPolicy.PENALTY_DURATION_MS
            )
        )
        Log.w(
            TAG,
            "Chess Guard: unauthorized ${if (rated) "RATED" else "casual"} game $gameId → " +
                "${ChessEnforcementPolicy.PENALTY_DURATION_MS / 3_600_000} h penalty"
        )
        return true
    }
}
