package com.example.tail.widget

import android.content.Context
import android.util.Log
import com.example.tail.data.ChessComGameDetail
import com.example.tail.data.ChessComService

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Deferred game pipeline — classify & audit shared games whenever they
 *  become available, by the readiness state AT THE MOMENT THE GAME ENDED
 * ════════════════════════════════════════════════════════════════════════
 *
 * chess.com publishes a finished game to the two players' monthly archives
 * independently (and with lag), so a share can arrive before ANY archive
 * lists the game. Instead of failing, such shares are parked in
 * [ChessPendingGameStore] and reconciled later — on every chess.com poll
 * and whenever the share sheet opens.
 *
 * Classification is time-based, mirroring the live gate
 * [ChessPhase2Store.ratedPlayAuthorized] but evaluated at the game's END
 * time rather than "now":
 *
 *  - APPROVED      the latest readiness test at/before the game ended was
 *                  GREEN and still inside its 60-minute validity window,
 *                  and no Yellow/Red Phase 2 audit intervened → the full
 *                  Phase 2 audit runs, stamped at the game's end time so
 *                  sessions/strain/ΔE history stay chronologically true.
 *  - UNAPPROVED    anything else (no test, YELLOW/RED latest, window
 *                  expired, or revoked by a bad audit) → no Phase 2 audit;
 *                  the game is written to the Chess Readiness activity log
 *                  where the compliance stats count it as unauthorized
 *                  play (the DENIED/EXPIRED buckets).
 *
 * Games the user FORGOT to share need no queue at all: the regular
 * chess.com poll already sweeps every archive game into
 * [ChessReadinessLogStore.logGames] with the same time-based
 * authorized/unapproved annotation.
 */
object ChessDeferredGameReconciler {

    private const val TAG = "ChessDeferredRecon"

    /** Pending shares older than this are dropped (chess.com will have published long before). */
    private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

    /** Minimal Phase 2 audit fact needed for game-time authorization. */
    data class AuditStamp(val timestamp: Long, val outputState: String)

    /** Outcome of classifying + processing one fetched game. */
    sealed class GameOutcome {
        /** Audit ran (live or deferred) — verdict below. */
        data class Audited(val result: ChessPhase2Engine.AuditResult) : GameOutcome()

        /** The game was already audited on a previous share (re-share). */
        data class AlreadyAudited(val previous: ChessPhase2Store.Phase2Audit) : GameOutcome()

        /** Format not subject to audits (unrated / variant / daily). */
        data class NotAuditable(val reason: String) : GameOutcome()

        /**
         * Rated & auditable but played OUTSIDE any valid green-light
         * window → recorded as unapproved play in the compliance stats.
         */
        data class Unauthorized(
            /** Epoch millis when the game ended. */
            val playedAt: Long,
            /** State name of the latest readiness test at play time (null = none). */
            val stateAtPlay: String?
        ) : GameOutcome()
    }

    /** Queue-drain counters for logging/status. */
    data class Summary(
        val audited: Int = 0,
        val unauthorized: Int = 0,
        val resolved: Int = 0,
        val stillPending: Int = 0
    )

    /**
     * Was RATED play authorized at the moment the game ended? PURE —
     * mirrors [ChessPhase2Store.ratedPlayAuthorized] with "now" replaced by
     * [gameEndMs]:
     *  - the latest test at/before [gameEndMs] was GREEN_LIGHT, and
     *  - [gameEndMs] is inside that test's validity window, and
     *  - every Phase 2 audit filed between the test and [gameEndMs] left
     *    rated play alive (CONTINUE_RATED).
     */
    fun authorizedAtGameEnd(
        tests: List<ChessReadinessEngine.ReadinessTest>,
        audits: List<AuditStamp>,
        gameEndMs: Long
    ): Boolean {
        val last = tests
            .filter { it.timestamp <= gameEndMs }
            .maxByOrNull { it.timestamp } ?: return false
        if (last.state != ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name) return false
        if (gameEndMs - last.timestamp >= ChessReadinessEngine.SESSION_VALIDITY_MS) return false
        return audits.all {
            it.timestamp < last.timestamp || it.timestamp > gameEndMs ||
                it.outputState == ChessPhase2Engine.OutputState.CONTINUE_RATED.name
        }
    }

    /**
     * Classifies a FETCHED game and, when it was authorized at play time,
     * runs and persists the full Phase 2 audit — stamped at the game's end
     * time so session derivation, strain chains and the ΔE percentile
     * floors see the game exactly where it happened. Also writes the game
     * to the Chess Readiness activity log (deduped) so the compliance
     * stats see it immediately, even when the user's own archive still
     * lags behind the opponent's.
     */
    fun processGame(context: Context, username: String, game: ChessComGameDetail): GameOutcome {
        // Compliance stats first — cheap, deduped, and correct for every
        // outcome below (authorized games land in the APPROVED bucket).
        try {
            ChessReadinessLogStore.logGames(
                context, listOf(ChessGameAuditMapper.toLightGame(game)), username
            )
        } catch (e: Exception) {
            Log.w(TAG, "Readiness logging of game ${game.gameId} failed: ${e.message}")
        }

        ChessPhase2Store.findAuditByGameId(context, game.gameId)?.let {
            return GameOutcome.AlreadyAudited(it)
        }

        val gameEndMs = game.endTime * 1000L

        // Authorization is evaluated FIRST — the Chess Guard penalty for
        // unauthorized play is applied by the logGames call above (single
        // choke point shared with the archive poller), so this classifier
        // only decides the audit outcome below.
        val tests = ChessReadinessStore.loadHistory(context)
        val authorized = authorizedAtGameEnd(
            tests = tests,
            audits = ChessPhase2Store.loadAudits(context).map {
                AuditStamp(it.timestamp, it.outputState)
            },
            gameEndMs = gameEndMs
        )

        val mapping = ChessGameAuditMapper.buildInput(
            game = game,
            username = username,
            accuracyHistories = ChessPhase2Engine.TimeControl.entries.associateWith {
                ChessPhase2Store.accuracyHistory(context, it)
            },
            sessionMinutesBefore = ChessPhase2Store.sessionMinutesUsed(context, gameEndMs)
        )
        val ready = when (mapping) {
            is ChessGameAuditMapper.Mapping.NotAuditable ->
                return GameOutcome.NotAuditable(mapping.reason)
            is ChessGameAuditMapper.Mapping.Ready -> mapping
        }

        if (!authorized) {
            val stateAtPlay = tests
                .filter { it.timestamp <= gameEndMs }
                .maxByOrNull { it.timestamp }?.state
            return GameOutcome.Unauthorized(playedAt = gameEndMs, stateAtPlay = stateAtPlay)
        }

        val session = ChessPhase2Store.currentSessionAudits(context, gameEndMs).map {
            ChessPhase2Engine.SessionGame(
                timestamp = it.timestamp,
                timeControl = it.timeControl,
                outputState = it.outputState,
                deltaE = it.deltaE,
                strain = it.strain
            )
        }
        val result = ChessPhase2Engine.evaluate(
            input = ready.input,
            sessionHistory = session,
            now = gameEndMs,
            deltaEHistory = ChessPhase2Store.recentDeltaE(context, gameEndMs),
            readinessCcrs = ChessPhase2Store.authorizingReadinessCcrs(context, gameEndMs)
        )

        if (ready.accuracyKnown && !ready.input.shortGame) {
            ChessPhase2Store.appendAccuracy(
                context, ready.input.timeControl, ready.input.caps2Accuracy
            )
        }
        ChessPhase2Store.appendAudit(
            context,
            ChessPhase2Store.Phase2Audit(
                timestamp = gameEndMs,
                timeControl = ready.input.timeControl.name,
                outputState = result.outputState.name,
                deltaE = result.deltaE,
                caps2Accuracy = ready.input.caps2Accuracy,
                accuracyCounted = ready.accuracyKnown && !ready.input.shortGame,
                gameId = game.gameId.toString(),
                estimatedMinutes = ready.estimatedMinutes,
                strain = result.strain
            )
        )
        return GameOutcome.Audited(result)
    }

    /**
     * Drains the pending-share queue: re-fetches every parked game (the
     * configured username is searched first, then the players recorded
     * from the share text) and processes whatever has appeared. Games that
     * are still unpublished stay queued for the next poll; entries too old
     * to ever resolve are dropped. One game failing must not abort the
     * rest, so each is handled individually.
     */
    suspend fun reconcilePending(
        context: Context,
        username: String,
        service: ChessComService = ChessComService(),
        now: Long = System.currentTimeMillis(),
        excludeGameId: Long? = null
    ): Summary {
        val queue = ChessPendingGameStore.pending(context)
            .filterNot { it.gameId == excludeGameId }
        if (queue.isEmpty()) return Summary()

        var audited = 0
        var unauthorized = 0
        var resolved = 0
        var stillPending = 0

        for (p in queue) {
            if (now - p.sharedAt > MAX_AGE_MS) {
                ChessPendingGameStore.remove(context, p.gameId)
                continue
            }
            if (ChessPhase2Store.findAuditByGameId(context, p.gameId) != null) {
                ChessPendingGameStore.remove(context, p.gameId)
                resolved++
                continue
            }
            try {
                val players = (listOf(username) + p.usernames)
                    .mapNotNull { it.trim().lowercase().takeIf { it.isNotEmpty() } }
                    .distinct()
                val game = service.findGameById(players, p.gameId)
                if (game == null) {
                    stillPending++
                    continue
                }
                when (processGame(context, username, game)) {
                    is GameOutcome.Audited -> audited++
                    is GameOutcome.Unauthorized -> unauthorized++
                    else -> { /* already audited / not auditable — resolved either way */ }
                }
                resolved++
                ChessPendingGameStore.remove(context, p.gameId)
            } catch (e: Exception) {
                Log.w(TAG, "Reconcile of game ${p.gameId} failed: ${e.message}")
                stillPending++
            }
        }
        return Summary(
            audited = audited,
            unauthorized = unauthorized,
            resolved = resolved,
            stillPending = stillPending
        )
    }
}
