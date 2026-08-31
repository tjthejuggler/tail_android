package com.example.tail.widget

import android.content.Context
import android.util.Log
import com.example.tail.data.ChessComGameDetail
import com.example.tail.data.ChessComService

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Deferred game pipeline — classify & audit shared games whenever they
 *  become available, by the readiness state AT THE MOMENT THE GAME STARTED
 * ════════════════════════════════════════════════════════════════════════
 *
 * chess.com publishes a finished game to the two players' monthly archives
 * independently (and with lag), so a share can arrive before ANY archive
 * lists the game. Instead of failing, such shares are parked in
 * [ChessPendingGameStore] and reconciled later — on every chess.com poll
 * and whenever the share sheet opens.
 *
 * Classification is time-based, mirroring the live gate
 * [ChessPhase2Store.ratedPlayAuthorized] but evaluated at the game's START
 * time rather than "now" (user rule, 2026-08-25: a game that begins inside
 * a valid window stays authorized even if it ends after the window expired):
 *
 *  - APPROVED      the latest readiness test at/before the game started was
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

        /** v2 post-game engine audit ran — verdict below. */
        data class AuditedV2(val result: ChessPhase2V2Engine.AuditResultV2) : GameOutcome()

        /** v3 hybrid audit ran (optionally backed by desktop Stockfish). */
        data class AuditedV3(val result: ChessPhase2V3Engine.AuditResultV3) : GameOutcome()

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
     * Was RATED play authorized at the moment the game STARTED? PURE —
     * mirrors [ChessPhase2Store.ratedPlayAuthorized] with "now" replaced by
     * [gameStartMs]:
     *  - the latest test at/before [gameStartMs] was GREEN_LIGHT, and
     *  - [gameStartMs] is inside that test's validity window, and
     *  - every Phase 2 audit filed between the test and [gameStartMs] left
     *    rated play alive (CONTINUE_RATED).
     */
   fun authorizedAtPlay(
       tests: List<ChessReadinessEngine.ReadinessTest>,
       audits: List<AuditStamp>,
       gameStartMs: Long
   ): Boolean {
       val last = tests
           .filter { it.timestamp <= gameStartMs }
           .maxByOrNull { it.timestamp } ?: return false
       if (last.state != ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name) return false
       if (gameStartMs - last.timestamp >= ChessReadinessEngine.SESSION_VALIDITY_MS) return false
       return audits.all {
           it.timestamp < last.timestamp || it.timestamp > gameStartMs ||
               it.outputState == ChessPhase2Engine.OutputState.CONTINUE_RATED.name
       }
   }

   /**
    * Best-effort game START in epoch millis: the PGN's UTC StartTime when
    * chess.com published it, else end minus the time-control base clock.
    */
   fun gameStartMsOf(
       game: ChessComGameDetail,
       gameEndMs: Long
   ): Long =
       com.example.tail.data.pgnStartEpochSec(game.pgn)?.times(1000L)
           ?: (gameEndMs -
               (com.example.tail.data.estimateGameMinutes(game.timeControl) * 60_000).toLong())

    /**
     * Classifies a FETCHED game and, when it was authorized at play time,
     * runs and persists the full Phase 2 audit — stamped at the game's end
     * time so session derivation, strain chains and the ΔE percentile
     * floors see the game exactly where it happened. Also writes the game
     * to the Chess Readiness activity log (deduped) so the compliance
     * stats see it immediately, even when the user's own archive still
     * lags behind the opponent's.
     */
    suspend fun processGame(
        context: Context,
        username: String,
        game: ChessComGameDetail,
        bridge: ChessAnalysisFetcher.BridgeCredentials? = null
    ): GameOutcome {
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

        // Phase 2 engine branch: "v3" runs the hybrid audit (v2 rules +
        // ΔE-weighted streaks + the strain accumulator, with real unforced
        // blunders from desktop Stockfish when the bridge is reachable);
        // "v2" runs the research-report audit; the default "v1" path below
        // is untouched.
        if (ChessPhase2V2Store.isV3(context) || ChessPhase2V2Store.isV4(context)) {
            return processGameV3(context, username, game, gameEndMs, bridge)
        }
        if (ChessPhase2V2Store.isV2(context)) {
            return processGameV2(context, username, game, gameEndMs)
        }

        // Authorization is evaluated FIRST — the Chess Guard penalty for
        // unauthorized play is applied by the logGames call above (single
        // choke point shared with the archive poller), so this classifier
        // only decides the audit outcome below.
        val tests = ChessReadinessStore.loadHistory(context)
        val gameStartMs = gameStartMsOf(game, gameEndMs)
        val authorized = authorizedAtPlay(
            tests = tests,
            audits = ChessPhase2Store.loadAudits(context).map {
                AuditStamp(it.timestamp, it.outputState)
            },
            gameStartMs = gameStartMs
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
                .filter { it.timestamp <= gameStartMs }
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
     * The v2 post-game audit path (research-report engine): maps the game
     * with the shared eligibility rules, checks authorization exactly like
     * v1, assembles the personal baselines + session ledger + ACWR inputs,
     * evaluates [ChessPhase2V2Engine.evaluate], then persists to BOTH the
     * shared audit history (so Chess Guard enforcement, rated-play
     * authorization and session derivation work unchanged) and the v2
     * telemetry stores (baselines + ledger).
     */
    private fun processGameV2(
        context: Context,
        username: String,
        game: ChessComGameDetail,
        gameEndMs: Long
    ): GameOutcome {
        val localHour = java.time.Instant.ofEpochMilli(gameEndMs)
            .atZone(java.time.ZoneId.systemDefault()).hour

        val mapping = ChessPhase2V2Engine.inputFrom(
            game = game,
            username = username,
            sessionMinutesBefore = ChessPhase2Store.sessionMinutesUsed(context, gameEndMs),
            localHour = localHour
        )
        val ready = when (mapping) {
            is ChessPhase2V2Engine.MappingV2.NotAuditable ->
                return GameOutcome.NotAuditable(mapping.reason)
            is ChessPhase2V2Engine.MappingV2.Ready -> mapping
        }

        // Same authorization rule as v1 — a game outside a green window is
        // unapproved play, never an audit.
        val tests = ChessReadinessStore.loadHistory(context)
        val gameStartMs = gameStartMsOf(game, gameEndMs)
        val authorized = authorizedAtPlay(
            tests = tests,
            audits = ChessPhase2Store.loadAudits(context).map {
                AuditStamp(it.timestamp, it.outputState)
            },
            gameStartMs = gameStartMs
        )
        if (!authorized) {
            val stateAtPlay = tests
                .filter { it.timestamp <= gameStartMs }
                .maxByOrNull { it.timestamp }?.state
            return GameOutcome.Unauthorized(playedAt = gameEndMs, stateAtPlay = stateAtPlay)
        }

        val tc = ready.input.timeControl
        val accBaseline = ChessPhase2V2Engine.baselineOf(
            ChessPhase2V2Store.accuracyHistory(context, tc),
            ChessPhase2V2Engine.SD_FLOOR_ACCURACY
        )
        val moveBaseline = ChessPhase2V2Engine.baselineOf(
            ChessPhase2V2Store.moveTimeHistory(context, tc),
            ChessPhase2V2Engine.SD_FLOOR_MOVE_SEC
        )
        val sessionGames = ChessPhase2V2Store.currentSessionGames(context, gameEndMs)
            .mapNotNull { g ->
                ChessPhase2V2Engine.SessionGameV2(
                    timestamp = g.timestamp,
                    result = ChessPhase2Engine.GameResult.entries.firstOrNull {
                        it.name == g.result
                    } ?: return@mapNotNull null,
                    outputState = g.outputState
                )
            }
        val acwr = try {
            ChessPhase2V2Store.acwrInput(
                ChessReadinessLogStore.loadGames(context), gameEndMs
            )
        } catch (_: Exception) { null }

        val result = ChessPhase2V2Engine.evaluate(
            input = ready.input,
            sessionGames = sessionGames,
            accBaseline = accBaseline,
            moveBaseline = moveBaseline,
            acwr = acwr,
            now = gameEndMs
        )

        // Personal baselines grow from every game with KNOWN telemetry.
        ChessPhase2V2Store.appendTelemetry(
            context, tc,
            accuracy = ready.input.accuracy.takeIf { ready.accuracyKnown },
            shortGame = ready.input.shortGame,
            avgMoveSec = ready.input.avgMoveSec.takeIf { ready.moveTimeKnown }
        )
        ChessPhase2V2Store.appendRecentGame(
            context,
            ChessPhase2V2Store.RatedGameRecord(
                timestamp = gameEndMs,
                result = ready.input.result.name,
                timeControl = tc.name,
                outputState = result.outputState.name,
                estimatedMinutes = ready.estimatedMinutes
            )
        )
        // Shared audit history → Chess Guard, rated-play authorization and
        // session derivation all consume this. Strain is mapped onto the v1
        // scale (0 / 50 / 100) so switching back to v1 mid-history keeps a
        // meaningful session tally.
        ChessPhase2Store.appendAudit(
            context,
            ChessPhase2Store.Phase2Audit(
                timestamp = gameEndMs,
                timeControl = tc.name,
                outputState = result.outputState.name,
                deltaE = ready.deltaE,
                caps2Accuracy = ready.input.accuracy ?: 0.0,
                accuracyCounted = ready.accuracyKnown && !ready.input.shortGame,
                gameId = game.gameId.toString(),
                estimatedMinutes = ready.estimatedMinutes,
                strain = when (result.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        ChessPhase2Engine.STRAIN_TERMINATE_BASE
                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        ChessPhase2Engine.SEVERE_STRAIN
                    else -> 0.0
                }
            )
        )
        return GameOutcome.AuditedV2(result)
    }

    /**
     * v3 hybrid audit: the v2 rule skeleton with ΔE-weighted loss streaks,
     * the v1 strain accumulator (readiness-buffered, with one-dip
     * forgiveness) and — when [bridge] is configured and reachable — real
     * unforced-blunder counts from the desktop Stockfish analysis service.
     * A null analysis simply leaves the blunder term ungated (away-from-PC
     * fallback); every other rule still evaluates.
     */
    private suspend fun processGameV3(
        context: Context,
        username: String,
        game: ChessComGameDetail,
        gameEndMs: Long,
        bridge: ChessAnalysisFetcher.BridgeCredentials?
    ): GameOutcome {
        val localHour = java.time.Instant.ofEpochMilli(gameEndMs)
            .atZone(java.time.ZoneId.systemDefault()).hour

        val mapping = ChessPhase2V2Engine.inputFrom(
            game = game,
            username = username,
            sessionMinutesBefore = ChessPhase2Store.sessionMinutesUsed(context, gameEndMs),
            localHour = localHour
        )
        val ready = when (mapping) {
            is ChessPhase2V2Engine.MappingV2.NotAuditable ->
                return GameOutcome.NotAuditable(mapping.reason)
            is ChessPhase2V2Engine.MappingV2.Ready -> mapping
        }

        // Same authorization rule as v1/v2 — a game outside a green window
        // is unapproved play, never an audit.
        val tests = ChessReadinessStore.loadHistory(context)
        val gameStartMs = gameStartMsOf(game, gameEndMs)
        val authorized = authorizedAtPlay(
            tests = tests,
            audits = ChessPhase2Store.loadAudits(context).map {
                AuditStamp(it.timestamp, it.outputState)
            },
            gameStartMs = gameStartMs
        )
        if (!authorized) {
            val stateAtPlay = tests
                .filter { it.timestamp <= gameStartMs }
                .maxByOrNull { it.timestamp }?.state
            return GameOutcome.Unauthorized(playedAt = gameEndMs, stateAtPlay = stateAtPlay)
        }

        // Desktop Stockfish analysis through the bridge — null when away
        // from the PC / service down (fallback contract, see the fetcher).
        val isWhite = game.whiteUsername.trim().lowercase() ==
            username.trim().lowercase()
        val analysis = ChessAnalysisFetcher.fetch(
            credentials = bridge,
            gameId = game.gameId,
            pgn = game.pgn,
            username = username,
            isWhite = isWhite
        )
        // v4: refresh the personal profile from the bridge (best-effort;
        // a failure keeps the cached copy / v3-identical fallback).
        if (ChessPhase2V2Store.isV4(context)) {
            ChessPhase2V4Profile.refresh(bridge, context)
        }

        val tc = ready.input.timeControl
        val result = evaluateV3(context, username, game, gameEndMs, ready, analysis, bridge)
        val expectedScore = ready.input.result.score - ready.deltaE

        // Personal baselines grow from every game with KNOWN telemetry.
        ChessPhase2V2Store.appendTelemetry(
            context, tc,
            accuracy = ready.input.accuracy.takeIf { ready.accuracyKnown },
            shortGame = ready.input.shortGame,
            avgMoveSec = ready.input.avgMoveSec.takeIf { ready.moveTimeKnown }
        )
        ChessPhase2V2Store.appendRecentGame(
            context,
            ChessPhase2V2Store.RatedGameRecord(
                timestamp = gameEndMs,
                result = ready.input.result.name,
                timeControl = tc.name,
                outputState = result.outputState.name,
                estimatedMinutes = ready.estimatedMinutes,
                expectedScore = expectedScore,
                strain = result.strain
            )
        )
        // Shared audit history → Chess Guard, rated-play authorization and
        // session derivation all consume this (strain mapped onto the v1
        // 0/50/100 scale so switching engines mid-history keeps a
        // meaningful session tally).
        ChessPhase2Store.appendAudit(
            context,
            ChessPhase2Store.Phase2Audit(
                timestamp = gameEndMs,
                timeControl = tc.name,
                outputState = result.outputState.name,
                deltaE = ready.deltaE,
                caps2Accuracy = ready.input.accuracy ?: 0.0,
                accuracyCounted = ready.accuracyKnown && !ready.input.shortGame,
                gameId = game.gameId.toString(),
                estimatedMinutes = ready.estimatedMinutes,
                strain = when (result.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        ChessPhase2Engine.STRAIN_TERMINATE_BASE
                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        ChessPhase2Engine.SEVERE_STRAIN
                    else -> 0.0
                }
            )
        )
        return GameOutcome.AuditedV3(result)
    }

    /**
     * Pure v3 evaluation (no persistence) — shared by the first-run audit
     * and the re-share / engine-retry paths so both see identical logic.
     * History inputs are cut at STRICTLY before [gameEndMs] so a re-run
     * (where this game's own audit is already on file) matches the
     * first-run semantics exactly.
     */
    private suspend fun evaluateV3(
        context: Context,
        username: String,
        game: ChessComGameDetail,
        gameEndMs: Long,
        ready: ChessPhase2V2Engine.MappingV2.Ready,
        analysis: ChessAnalysisFetcher.Analysis?,
        bridge: ChessAnalysisFetcher.BridgeCredentials? = null
    ): ChessPhase2V3Engine.AuditResultV3 {
        val localHour = java.time.Instant.ofEpochMilli(gameEndMs)
            .atZone(java.time.ZoneId.systemDefault()).hour
        val tc = ready.input.timeControl
        val accBaseline = ChessPhase2V2Engine.baselineOf(
            ChessPhase2V2Store.accuracyHistory(context, tc),
            ChessPhase2V2Engine.SD_FLOOR_ACCURACY
        )
        val moveBaseline = ChessPhase2V2Engine.baselineOf(
            ChessPhase2V2Store.moveTimeHistory(context, tc),
            ChessPhase2V2Engine.SD_FLOOR_MOVE_SEC
        )
        val sessionGames = ChessPhase2V2Store.currentSessionGames(context, gameEndMs)
            .filter { it.timestamp < gameEndMs }
            .mapNotNull { g ->
                val res = ChessPhase2Engine.GameResult.entries
                    .firstOrNull { it.name == g.result } ?: return@mapNotNull null
                ChessPhase2V3Engine.SessionGameV3(
                    timestamp = g.timestamp,
                    result = res,
                    outputState = g.outputState,
                    expectedScore = g.expectedScore,
                    strain = g.strain
                )
            }
        val acwr = try {
            ChessPhase2V2Store.acwrInput(
                ChessReadinessLogStore.loadGames(context), gameEndMs
            )
        } catch (_: Exception) { null }

        // CCRS of the latest readiness test at/before game end — drives the
        // fatigue scaling, the strain buffer and one-dip forgiveness.
        val ccrs = ChessReadinessStore.loadHistory(context)
            .filter { it.timestamp <= gameEndMs }
            .maxByOrNull { it.timestamp }?.ccrs

        // ΔE history from the shared audit log (the same source v1's
        // personal percentile floors use).
        val deltaEHistory = ChessPhase2Store.loadAudits(context)
            .filter { it.timestamp < gameEndMs }
            .map { ChessPhase2Engine.DeltaERecord(it.timestamp, it.deltaE) }

        val expectedScore = ready.input.result.score - ready.deltaE

        val v4Input = ChessPhase2V3Engine.GameInputV3(
            timeControl = tc,
            result = ready.input.result,
            accuracy = ready.input.accuracy,
            avgMoveSec = ready.input.avgMoveSec,
            sessionElapsedMins = ready.input.sessionElapsedMins,
            localHour = localHour,
            shortGame = ready.input.shortGame,
            expectedScore = expectedScore,
            deltaE = ready.deltaE,
            unforcedBlunders = analysis?.userStats?.unforcedBlunders,
            blunderCount = analysis?.userStats?.blunders,
            mistakeCount = analysis?.userStats?.mistakes,
            inaccuracyCount = analysis?.userStats?.inaccuracies,
            analysisAcpl = analysis?.userStats?.acpl,
            analysisMoves = analysis?.userStats?.moves,
            accuracyHistory = ChessPhase2V2Store.accuracyHistory(context, tc),
            readinessCcrs = ccrs
        )
        val base = ChessPhase2V3Engine.evaluate(
            input = v4Input,
            sessionGames = sessionGames,
            accBaseline = accBaseline,
            moveBaseline = moveBaseline,
            acwr = acwr,
            deltaEHistory = deltaEHistory,
            now = gameEndMs
        )
        // v4 overlay: personal, data-derived thresholds refine the v3
        // verdict (bit-identical to v3 under the fallback profile), and
        // every recommendation is reported (with its exact decision
        // variables) to the bridge-side history log — fire-and-forget.
        if (!ChessPhase2V2Store.isV4(context)) return base
        val profile = ChessPhase2V4Profile.load(context)
        val refined = ChessPhase2V4Engine.refine(
            base = base,
            input = v4Input,
            sessionGames = sessionGames,
            profile = profile
        )
        try {
            ChessPhase2V4Report.send(
                credentials = bridge,
                gameId = game.gameId,
                username = username,
                result = refined,
                input = v4Input,
                profile = profile
            )
        } catch (e: Exception) {
            Log.w(TAG, "v4 report failed for game ${game.gameId}: ${e.message}")
        }
        return refined
    }

    /**
     * RE-SHARE / ENGINE-RETRY path: re-runs the v3 audit for an
     * ALREADY-recorded game — fetching fresh desktop Stockfish analysis
     * when the bridge is now reachable — and UPDATES the recorded verdict
     * and ledger row in place. Never appends duplicate history or
     * telemetry. Returns the (possibly engine-backed) result, or null when
     * the game is no longer auditable.
     */
    suspend fun reauditV3(
        context: Context,
        username: String,
        game: ChessComGameDetail,
        bridge: ChessAnalysisFetcher.BridgeCredentials? = null
    ): ChessPhase2V3Engine.AuditResultV3? {
        val gameEndMs = game.endTime * 1000L
        val localHour = java.time.Instant.ofEpochMilli(gameEndMs)
            .atZone(java.time.ZoneId.systemDefault()).hour
        val ready = when (
            val m = ChessPhase2V2Engine.inputFrom(
                game = game,
                username = username,
                sessionMinutesBefore = ChessPhase2Store.sessionMinutesUsed(context, gameEndMs),
                localHour = localHour
            )
        ) {
            is ChessPhase2V2Engine.MappingV2.NotAuditable -> return null
            is ChessPhase2V2Engine.MappingV2.Ready -> m
        }

        val isWhite = game.whiteUsername.trim().lowercase() ==
            username.trim().lowercase()
        val analysis = ChessAnalysisFetcher.fetch(
            credentials = bridge,
            gameId = game.gameId,
            pgn = game.pgn,
            username = username,
            isWhite = isWhite
        )
        if (ChessPhase2V2Store.isV4(context)) {
            ChessPhase2V4Profile.refresh(bridge, context)
        }
        val result = evaluateV3(context, username, game, gameEndMs, ready, analysis, bridge)

        // In-place verdict update — Chess Guard / authorization consumers
        // read these stores, so a revised verdict must replace, not append.
        ChessPhase2Store.updateAuditForGame(context, game.gameId) { old ->
            old.copy(
                outputState = result.outputState.name,
                strain = when (result.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        ChessPhase2Engine.STRAIN_TERMINATE_BASE
                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        ChessPhase2Engine.SEVERE_STRAIN
                    else -> 0.0
                }
            )
        }
        ChessPhase2V2Store.updateRecentGameAt(context, gameEndMs) {
            it.copy(outputState = result.outputState.name, strain = result.strain)
        }
        return result
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
        excludeGameId: Long? = null,
        bridge: ChessAnalysisFetcher.BridgeCredentials? = null
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
                when (processGame(context, username, game, bridge)) {
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
