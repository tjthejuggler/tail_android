package com.example.tail.widget

import com.example.tail.data.ChessComGameDetail
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Shared-Link Game Audit Mapper
 * ════════════════════════════════════════════════════════════════════════
 *
 * Bridges the chess.com share sheet into the Phase 2 audit engine. When a
 * game ends, chess.com's share option produces text like:
 *
 *   Check out this #chess game: jugglah vs darknessdecay -
 *   https://www.chess.com/live/game/173067813820
 *
 * The user shares that text to Tail; this mapper extracts the game ID and,
 * once the game has been fetched from the chess.com archive API
 * ([com.example.tail.data.ChessComService.findGameById]), converts the
 * API's [ChessComGameDetail] into a [ChessPhase2Engine.GameInput] so the
 * audit runs with ZERO manual data entry.
 *
 * Field sourcing:
 *  - time control  → derived from the API's `time_control` string (same
 *    thresholds the habit-linking feature uses: <3 min bullet, <10 blitz,
 *    else rapid; daily/correspondence is not audited)
 *  - ratings       → white/black `rating` of the game
 *  - result        → the user side's `result` string (win / draw set / loss)
 *  - accuracy      → the API's `accuracies` for the user's side when the
 *    game review exists; when absent the rolling-mean baseline is used so
 *    no accuracy violation can trigger, and the game is NOT counted in the
 *    rolling window
 *  - blunders      → not exposed by the public API → always 0 / not unforced
 *  - short game    → last move number of the PGN < 10 (early resignation)
 *  - session mins  → estimated base-clock minutes of every audited game in
 *    the current session (same approximation the habit links use), so the
 *    60-minute capacity ceiling accumulates automatically per share
 *
 * The mapper is PURE — no Android dependencies — so it is unit-testable.
 */
object ChessGameAuditMapper {

    /**
     * Matches every chess.com game-URL shape seen in the wild:
     *  - `chess.com/live/game/173067813820`   (share sheet)
     *  - `chess.com/game/live/173067813820`   (archive API `url` field)
     *  - `chess.com/game/173067813820`        (legacy)
     */
    private val GAME_URL =
        Regex("chess\\.com/(?:[a-z]+/)*game/(?:live/)?(\\d+)", RegexOption.IGNORE_CASE)

    /** The chess.com result strings that count as a draw (everything but "win" is a loss). */
    private val DRAW_RESULTS = setOf(
        "agreed", "repetition", "stalemate", "insufficient", "50move", "timevsinsufficient"
    )

    /**
     * Rules variants that the audit covers. Chess960 is the user's MAIN format
     * and is audit-identical to standard chess (rated, Game Review accuracies,
     * normal time controls, same result strings). True variants (crazyhouse,
     * three-check, king-of-the-hill, …) play by different rules and stay
     * excluded.
     */
    private val AUDITED_RULES = setOf("chess", "chess960")

    /** Extracts the game ID from arbitrary shared text, or null when no chess.com game link is present. */
    fun parseSharedGameId(text: String): Long? =
        GAME_URL.find(text)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Matches the player pair in chess.com share text:
     * "Check out this #chess game: jugglah vs Dinmuhamed_055 - https://…"
     * (chess.com usernames are letters/digits/underscores/hyphens).
     */
    private val SHARE_PLAYERS =
        Regex("([A-Za-z0-9_-]+)\\s+vs\\.?\\s+([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)

    /**
     * The usernames mentioned around " vs " in the shared text (white first,
     * black second — as chess.com prints them). Empty when the text carries
     * no player pair. Used to widen the archive search: chess.com publishes
     * a just-finished game to the OPPONENT's monthly archive long before the
     * owner's own archive updates, and the game JSON found under either
     * player is symmetric.
     */
    fun parseShareUsernames(text: String): List<String> {
        val m = SHARE_PLAYERS.find(text) ?: return emptyList()
        return listOf(m.groupValues[1], m.groupValues[2])
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    /** The trailing numeric game ID of an archive-API game URL, or null. */
    fun trailingGameId(url: String): Long? =
        url.trimEnd('/').substringAfterLast('/').toLongOrNull()

    /** Maps a chess.com per-side result string onto the Elo score scale. */
    fun resultFor(resultStr: String): ChessPhase2Engine.GameResult = when {
        resultStr == "win" -> ChessPhase2Engine.GameResult.WIN
        resultStr in DRAW_RESULTS -> ChessPhase2Engine.GameResult.DRAW
        else -> ChessPhase2Engine.GameResult.LOSS
    }

    /**
     * Time-control tier for an API `time_control` string ("600", "180+2", …).
     * Null for daily/correspondence ("1/86400") — those are not audited.
     */
    fun timeControlFor(timeControl: String): ChessPhase2Engine.TimeControl? {
        if (timeControl.contains("/")) return null
        val baseSeconds = timeControl.split("+").firstOrNull()?.toDoubleOrNull() ?: return null
        return when {
            baseSeconds < 180 -> ChessPhase2Engine.TimeControl.BULLET
            baseSeconds < 600 -> ChessPhase2Engine.TimeControl.BLITZ
            else -> ChessPhase2Engine.TimeControl.RAPID
        }
    }

    /** Estimated minutes a game took (base clock; increment ignored) — the established approximation. */
    fun estimateMinutes(timeControl: String): Double {
        if (timeControl.contains("/")) return 0.0
        val baseSeconds = timeControl.split("+").firstOrNull()?.toDoubleOrNull() ?: return 0.0
        return baseSeconds / 60.0
    }

    /** The last move number of a PGN ("1. e4 e5 2. ... {[%clk ...]} 17. Qxf7#"), or 0 when unreadable. */
    fun countPgnMoves(pgn: String): Int =
        Regex("(?:^|\\s)(\\d+)\\.")
            .findAll(pgn)
            .maxOfOrNull { it.groupValues[1].toIntOrNull() ?: 0 } ?: 0

    /** Outcome of mapping a fetched game onto the audit engine's input. */
    sealed class Mapping {
        /** The game is not subject to a Phase 2 audit (with a user-facing reason). */
        data class NotAuditable(val reason: String) : Mapping()

        /** The audit may run. */
        data class Ready(
            val input: ChessPhase2Engine.GameInput,
            /** True when the API supplied a real accuracy (false → baseline used, not counted). */
            val accuracyKnown: Boolean,
            /** Base-clock minutes this game adds to the session capacity tally. */
            val estimatedMinutes: Double
        ) : Mapping()
    }

    /**
     * Converts a fetched chess.com game into a [ChessPhase2Engine.GameInput].
     *
     * @param game                 the game fetched from the archive API
     * @param username             the user's chess.com username (settings)
     * @param accuracyHistories    rolling accuracy windows per time control
     * @param sessionMinutesBefore base-clock minutes already audited this session
     */
    fun buildInput(
        game: ChessComGameDetail,
        username: String,
        accuracyHistories: Map<ChessPhase2Engine.TimeControl, List<Double>> =
            emptyMap(),
        sessionMinutesBefore: Double = 0.0
    ): Mapping {
        if (!game.rated) return Mapping.NotAuditable(
            "Unrated game — casual play is not audited. Only rated games count."
        )
        if (game.rules.isNotBlank() && game.rules !in AUDITED_RULES) return Mapping.NotAuditable(
            "Variant game (${game.rules}) — not part of the readiness system."
        )
        val tc = timeControlFor(game.timeControl) ?: return Mapping.NotAuditable(
            "Daily / correspondence game — not audited."
        )

        val userLower = username.trim().lowercase()
        val isWhite = game.whiteUsername.lowercase() == userLower
        val isBlack = game.blackUsername.lowercase() == userLower
        if (!isWhite && !isBlack) return Mapping.NotAuditable(
            "You ($username) did not play in this game."
        )

        val userRating = if (isWhite) game.whiteRating else game.blackRating
        val opponentRating = if (isWhite) game.blackRating else game.whiteRating
        val resultStr = if (isWhite) game.whiteResult else game.blackResult

        // Early resignation (< 10 moves) bypasses the accuracy check; a PGN
        // we cannot read (0) must NOT bypass anything.
        val shortGame = countPgnMoves(game.pgn) in 1..9

        val history = accuracyHistories[tc].orEmpty()
        val accuracy = if (isWhite) game.whiteAccuracy else game.blackAccuracy
        val effectiveAccuracy = accuracy
            ?: ChessPhase2Engine.rollingMean(history, tc) // unknown → baseline → no violation

        val minutes = estimateMinutes(game.timeControl)

        return Mapping.Ready(
            input = ChessPhase2Engine.GameInput(
                timeControl = tc,
                userRating = userRating,
                opponentRating = opponentRating,
                gameResult = resultFor(resultStr),
                caps2Accuracy = effectiveAccuracy,
                blunderCount = 0,            // not exposed by the public API
                hasUnforcedBlunder = false,  // not exposed by the public API
                sessionElapsedMins = (sessionMinutesBefore + minutes).roundToInt(),
                shortGame = shortGame,
                accuracyHistory = history
            ),
            accuracyKnown = accuracy != null,
            estimatedMinutes = minutes
        )
    }

    /**
     * The light [com.example.tail.data.ChessComGame] projection of a fetched
     * [com.example.tail.data.ChessComGameDetail] — what the Chess Readiness
     * activity log ([ChessReadinessLogStore.logGames]) consumes. Lets a game
     * fetched via the OPPONENT's archive (while the user's own archive lags)
     * still reach the compliance stats immediately.
     */
    fun toLightGame(game: com.example.tail.data.ChessComGameDetail):
        com.example.tail.data.ChessComGame =
        com.example.tail.data.ChessComGame(
            timeClass = game.timeClass,
            timeControl = game.timeControl,
            endTime = game.endTime,
            whiteUsername = game.whiteUsername,
            blackUsername = game.blackUsername,
            whiteResult = game.whiteResult,
            blackResult = game.blackResult,
            rated = game.rated,
            rules = game.rules,
            whiteRating = game.whiteRating,
            blackRating = game.blackRating
        )
}
