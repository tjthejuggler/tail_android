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
    /** The username of the white player */
    val whiteUsername: String,
    /** The username of the black player */
    val blackUsername: String,
    /** Result string for the white player, e.g. "win", "checkmated", "agreed" */
    val whiteResult: String = "",
    /** Result string for the black player, e.g. "win", "checkmated", "agreed" */
    val blackResult: String = ""
)

/** chess.com result string for a won game (any other value is a loss or draw). */
const val CHESS_COM_RESULT_WIN = "win"

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

                if (timeClass.isNotEmpty() && endTime > 0) {
                    games.add(
                        ChessComGame(
                            timeClass = timeClass,
                            timeControl = timeControl,
                            endTime = endTime,
                            whiteUsername = whiteUsername,
                            blackUsername = blackUsername,
                            whiteResult = whiteResult,
                            blackResult = blackResult
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
     * Throws on non-2xx status codes.
     */
    private fun httpGet(urlStr: String): JSONObject {
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
