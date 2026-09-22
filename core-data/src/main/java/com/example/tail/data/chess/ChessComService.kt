package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ChessComService"
private const val BASE_URL = "https://api.chess.com/pub"
private const val USER_AGENT = "TailHabitTracker/1.0 (Android habit tracking app)"
private const val HTTP_RETRIES = 2
private const val HTTP_RETRY_BACKOFF_MS = 1500L

/**
 * Represents a single chess.com game with the data we need for habit tracking.
 */
data class ChessComGame(
    /** "bullet", "blitz", "rapid", "daily" */
    val timeClass: String,
    /** Time control string e.g. "600" or "180+2" */
    val timeControl: String,
    /** Unix timestamp when the game ended */
    val endTime: Long,
    /**
     * Unix timestamp when the game started (parsed from the archive PGN's
     * UTCDate/StartTime headers), or null when the PGN was absent.
     */
    val startTime: Long? = null,
    /** The username of the white player */
    val whiteUsername: String,
    /** The username of the black player */
    val blackUsername: String,
    /** Result string for the white player, e.g. "win", "checkmated", "agreed" */
    val whiteResult: String = "",
    /** Result string for the black player, e.g. "win", "checkmated", "agreed" */
    val blackResult: String = "",
    /** True for rating-affecting games. */
    val rated: Boolean = true,
    /** Rules variant, e.g. "chess", "chess960". */
    val rules: String = "chess",
    /** White's rating after the game (0 = unknown). */
    val whiteRating: Int = 0,
    /** Black's rating after the game (0 = unknown). */
    val blackRating: Int = 0
)

/** chess.com result string for a won game (any other value is a loss or draw). */
const val CHESS_COM_RESULT_WIN = "win"

private val PGN_UTC_DATE = Regex("""\[UTCDate "(\d{4})\.(\d{2})\.(\d{2})"]""")
private val PGN_START_TIME = Regex("""\[StartTime "(\d{2}):(\d{2}):(\d{2})"]""")

/**
 * Parses the game's start time (Unix seconds, UTC) from a chess.com PGN's
 * UTCDate + StartTime header pair — chess.com publishes both in UTC.
 * Returns null when either header is missing or malformed.
 */
fun pgnStartEpochSec(pgn: String): Long? {
    if (pgn.isBlank()) return null
    val date = PGN_UTC_DATE.find(pgn) ?: return null
    val time = PGN_START_TIME.find(pgn) ?: return null
    return try {
        java.time.LocalDateTime.of(
            date.groupValues[1].toInt(), date.groupValues[2].toInt(),
            date.groupValues[3].toInt(),
            time.groupValues[1].toInt(), time.groupValues[2].toInt(),
            time.groupValues[3].toInt()
        ).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() / 1000L
    } catch (_: Exception) {
        null
    }
}

/**
 * A single chess.com game with the FULL detail needed by the Phase 2
 * post-game audit — everything the monthly archive endpoint exposes for a
 * game: ratings, per-side results, Game Review accuracies (when computed),
 * the PGN and the rated/rules flags.
 */
data class ChessComGameDetail(
    /** Numeric game ID parsed from the game URL (e.g. 173067813820). */
    val gameId: Long,
    /** Canonical game URL, e.g. "https://www.chess.com/game/live/173067813820". */
    val url: String,
    /** True for rated games (only rated games are audited). */
    val rated: Boolean,
    /** Rules variant ("chess", "chess960", …) — "chess" and "chess960" are audited. */
    val rules: String,
    /** API time class: "bullet", "blitz", "rapid", "daily". */
    val timeClass: String,
    /** Raw time control, e.g. "600" or "180+2". */
    val timeControl: String,
    /** Unix timestamp (seconds) when the game ended. */
    val endTime: Long,
    val whiteUsername: String,
    val whiteRating: Int,
    /** Result string for the white player, e.g. "win", "checkmated", "agreed". */
    val whiteResult: String,
    val blackUsername: String,
    val blackRating: Int,
    /** Result string for the black player. */
    val blackResult: String,
    /** Game Review (CAPS2) accuracy for white, 0–100, or null when unavailable. */
    val whiteAccuracy: Double?,
    /** Game Review (CAPS2) accuracy for black, 0–100, or null when unavailable. */
    val blackAccuracy: Double?,
    /** Full PGN of the game ("" when absent). */
    val pgn: String
)

/**
 * Puzzle stats from chess.com /player/{username}/stats endpoint.
 */
data class ChessComPuzzleStats(
    /** Highest tactics rating ever achieved */
    val tacticsHighest: Int = 0,
    /** Current tactics rating */
    val tacticsCurrent: Int = 0,
    /** Total puzzle rush attempts */
    val puzzleRushTotalAttempts: Int = 0,
    /** Best puzzle rush score */
    val puzzleRushBestScore: Int = 0
)

/**
 * Low-level API client for chess.com public API.
 * All methods run on Dispatchers.IO and return parsed data or throw on error.
 */
class ChessComService {

    /**
     * Fetches the list of monthly archive URLs for a player.
     * Returns URLs like "https://api.chess.com/pub/player/hikaru/games/2020/01"
     */
    suspend fun getArchiveUrls(username: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/player/${username.lowercase()}/games/archives"
        val json = httpGet(url)
        val arr = json.optJSONArray("archives") ?: JSONArray()
        (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * Fetches all games for a specific monthly archive URL.
     * The URL should be one returned by [getArchiveUrls].
     */
    suspend fun getGamesForMonth(archiveUrl: String): List<ChessComGame> = withContext(Dispatchers.IO) {
        val json = httpGet(archiveUrl)
        val gamesArr = json.optJSONArray("games") ?: JSONArray()
        parseGames(gamesArr)
    }

    /**
     * Fetches games for a specific year/month for a player.
     * Month should be 1-12 (will be zero-padded).
     */
    suspend fun getGamesForMonth(username: String, year: Int, month: Int): List<ChessComGame> =
        withContext(Dispatchers.IO) {
            val monthStr = month.toString().padStart(2, '0')
            val url = "$BASE_URL/player/${username.lowercase()}/games/$year/$monthStr"
            val json = httpGet(url)
            val gamesArr = json.optJSONArray("games") ?: JSONArray()
            parseGames(gamesArr)
        }

    /**
     * Fetches the player's stats including puzzle data.
     */
    suspend fun getPlayerStats(username: String): ChessComPuzzleStats = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/player/${username.lowercase()}/stats"
        val json = httpGet(url)

        val tactics = json.optJSONObject("tactics")
        val tacticsHighest = tactics?.optJSONObject("highest")?.optInt("rating", 0) ?: 0
        val tacticsCurrent = tactics?.optJSONObject("lowest")?.optInt("rating", 0) ?: 0

        val puzzleRush = json.optJSONObject("puzzle_rush")
        val best = puzzleRush?.optJSONObject("best")
        val totalAttempts = best?.optInt("total_attempts", 0) ?: 0
        val bestScore = best?.optInt("score", 0) ?: 0

        ChessComPuzzleStats(
            tacticsHighest = tacticsHighest,
            tacticsCurrent = tacticsCurrent,
            puzzleRushTotalAttempts = totalAttempts,
            puzzleRushBestScore = bestScore
        )
    }

    /**
     * Current per-variant ratings from the /stats endpoint
     * (chess_bullet / chess_blitz / chess_rapid / chess960_daily `last`).
     * Missing variants come back as 0.
     */
    suspend fun getVariantRatings(username: String): Map<String, Int> =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/player/${username.lowercase()}/stats"
            val json = httpGet(url)
            fun last(key: String) =
                json.optJSONObject(key)?.optJSONObject("last")
                    ?.optInt("rating", 0) ?: 0
            val user = username.lowercase()
            mapOf(
                "bullet" to last("chess_bullet"),
                "blitz" to last("chess_blitz"),
                "rapid" to last("chess_rapid"),
                // chess.com's /stats does NOT expose the live chess960
                // rating (chess960_daily is a different, daily variant).
                // Derive it from the most recent chess960 game in the
                // monthly archives instead (newest first, last 3 months).
                "chess960" to runCatching { latestVariantRating(user, "chess960") }
                    .getOrDefault(0)
            )
        }

    /**
     * The player's CURRENT rating in [variant] ("chess960"), taken from
     * their most recent rated game of that variant across the last few
     * monthly archives (each game JSON carries both players'
     * post-game ratings).
     */
    private suspend fun latestVariantRating(username: String, variant: String): Int {
        val archives = getArchiveUrls(username).takeLast(3).reversed()
        for (url in archives) {
            val games = runCatching { getGamesForMonth(url) }.getOrDefault(emptyList())
            val hit = games
                .filter { it.rated && it.rules == variant }
                .filter { it.whiteUsername.lowercase() == username || it.blackUsername.lowercase() == username }
                .maxByOrNull { it.endTime } ?: continue
            return if (hit.whiteUsername.lowercase() == username) hit.whiteRating
            else hit.blackRating
        }
        return 0
    }

    /**
     * Finds a single game by its numeric ID (as extracted from a shared
     * chess.com game link) in the player's monthly archives. Searches the
     * current month first, then the previous month (to cover month
     * boundaries). Returns null when the game is not in either archive —
     * chess.com can take a minute or two to publish a just-finished game.
     */
    suspend fun findGameById(username: String, gameId: Long): ChessComGameDetail? =
        findGameById(listOf(username), gameId)

    /**
     * Finds a single game by its numeric ID, searching the monthly archives
     * of EVERY given player (current + previous month each).
     *
     * chess.com has no by-ID endpoint, and the per-player monthly archives
     * update INDEPENDENTLY — a just-finished game can be missing from the
     * owner's own archive for minutes-to-hours while already being published
     * under the opponent (verified empirically). The game JSON is symmetric
     * (it carries both players' ratings/results/accuracies/PGN), so a match
     * found under ANY participant is complete. Usernames typically come from
     * the share text ("jugglah vs Dinmuhamed_055"); the configured account
     * name should always be first so its archive is preferred.
     */
    suspend fun findGameById(usernames: List<String>, gameId: Long): ChessComGameDetail? =
        withContext(Dispatchers.IO) {
            val current = java.time.YearMonth.now()
            val months = listOf(current, current.minusMonths(1))
            val players = usernames.mapNotNull { it.trim().lowercase() }.distinct()
            for (player in players) {
                for (m in months) {
                    try {
                        val games = getGameDetailsForMonth(player, m.year, m.monthValue)
                        val match = games.firstOrNull { it.gameId == gameId }
                        if (match != null) return@withContext match
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to search $player ${m} for game $gameId: ${e.message}")
                    }
                }
            }
            null
        }

    /**
     * Fetches the full game details for a specific year/month for a player
     * (ratings, accuracies, PGN included).
     */
    suspend fun getGameDetailsForMonth(
        username: String,
        year: Int,
        month: Int
    ): List<ChessComGameDetail> = withContext(Dispatchers.IO) {
        val monthStr = month.toString().padStart(2, '0')
        val url = "$BASE_URL/player/${username.lowercase()}/games/$year/$monthStr"
        val json = httpGet(url)
        val gamesArr = json.optJSONArray("games") ?: JSONArray()
        parseGameDetails(gamesArr)
    }

    /**
     * Validates that a chess.com username exists by trying to fetch their profile.
     * Returns true if the user exists, false otherwise.
     */
    suspend fun validateUsername(username: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/player/${username.lowercase()}"
            httpGet(url)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Parses the archive endpoint's game objects into [ChessComGameDetail]s. */
    private fun parseGameDetails(gamesArr: JSONArray): List<ChessComGameDetail> {
        val games = mutableListOf<ChessComGameDetail>()
        for (i in 0 until gamesArr.length()) {
            try {
                val g = gamesArr.getJSONObject(i)
                val url = g.optString("url", "")
                val gameId = ChessGameAuditMapperIds.trailingGameId(url) ?: continue
                val white = g.optJSONObject("white")
                val black = g.optJSONObject("black")
                val acc = g.optJSONObject("accuracies")
                games.add(
                    ChessComGameDetail(
                        gameId = gameId,
                        url = url,
                        rated = g.optBoolean("rated", false),
                        rules = g.optString("rules", "chess"),
                        timeClass = g.optString("time_class", ""),
                        timeControl = g.optString("time_control", ""),
                        endTime = g.optLong("end_time", 0L),
                        whiteUsername = white?.optString("username", "") ?: "",
                        whiteRating = white?.optInt("rating", 0) ?: 0,
                        whiteResult = white?.optString("result", "") ?: "",
                        blackUsername = black?.optString("username", "") ?: "",
                        blackRating = black?.optInt("rating", 0) ?: 0,
                        blackResult = black?.optString("result", "") ?: "",
                        whiteAccuracy = acc?.let {
                            it.optDouble("white", Double.NaN).takeIf { v -> !v.isNaN() }
                        },
                        blackAccuracy = acc?.let {
                            it.optDouble("black", Double.NaN).takeIf { v -> !v.isNaN() }
                        },
                        pgn = g.optString("pgn", "")
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse game detail at index $i: ${e.message}")
            }
        }
        return games
    }

    private fun parseGames(gamesArr: JSONArray): List<ChessComGame> {
        val games = mutableListOf<ChessComGame>()
        for (i in 0 until gamesArr.length()) {
            try {
                val g = gamesArr.getJSONObject(i)
                val timeClass = g.optString("time_class", "")
                val timeControl = g.optString("time_control", "")
                val endTime = g.optLong("end_time", 0L)
                val white = g.optJSONObject("white")
                val black = g.optJSONObject("black")
                val whiteUsername = white?.optString("username", "") ?: ""
                val blackUsername = black?.optString("username", "") ?: ""
                val whiteResult = white?.optString("result", "") ?: ""
                val blackResult = black?.optString("result", "") ?: ""
                val rated = g.optBoolean("rated", true)
                val rules = g.optString("rules", "chess")
                val whiteRating = white?.optInt("rating", 0) ?: 0
                val blackRating = black?.optInt("rating", 0) ?: 0

                if (timeClass.isNotEmpty() && endTime > 0) {
                    games.add(
                        ChessComGame(
                            timeClass = timeClass,
                            timeControl = timeControl,
                            endTime = endTime,
                            startTime = pgnStartEpochSec(g.optString("pgn", "")),
                            whiteUsername = whiteUsername,
                            blackUsername = blackUsername,
                            whiteResult = whiteResult,
                            blackResult = blackResult,
                            rated = rated,
                            rules = rules,
                            whiteRating = whiteRating,
                            blackRating = blackRating
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse game at index $i: ${e.message}")
            }
        }
        return games
    }

    /**
     * Makes an HTTP GET request and returns the parsed JSON response.
     * Retries rate-limited (429), transient server (5xx) and network
     * failures with linear backoff — full-archive sweeps issue many
     * sequential requests and chess.com throttles bursts. Throws the last
     * error once retries are exhausted (or immediately for other 4xx,
     * which never resolve on retry).
     */
    private fun httpGet(urlStr: String): JSONObject {
        var lastError: Exception? = null
        for (attempt in 0..HTTP_RETRIES) {
            if (attempt > 0) Thread.sleep(HTTP_RETRY_BACKOFF_MS * attempt)
            try {
                return httpGetOnce(urlStr)
            } catch (e: ChessComApiException) {
                lastError = e
                val retryable = e.statusCode == 429 || e.statusCode in 500..599
                if (!retryable) throw e
                Log.w(TAG, "HTTP ${e.statusCode} (attempt ${attempt + 1}/${HTTP_RETRIES + 1}) for $urlStr")
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Request failed (attempt ${attempt + 1}/${HTTP_RETRIES + 1}) for $urlStr: ${e.message}")
            }
        }
        throw lastError!!
    }

    /** Single HTTP GET attempt — no retry logic. */
    private fun httpGetOnce(urlStr: String): JSONObject {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                throw ChessComApiException(code, "HTTP $code for $urlStr: $errorBody")
            }
            val body = conn.inputStream.bufferedReader().readText()
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}

class ChessComApiException(val statusCode: Int, message: String) : Exception(message)

/** Tiny pure helper: the trailing numeric game ID of a chess.com game URL. */
private object ChessGameAuditMapperIds {
    fun trailingGameId(url: String): Long? =
        url.trimEnd('/').substringAfterLast('/').toLongOrNull()
}
