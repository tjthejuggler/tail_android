package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A single watching session of a movie/series (start time, end time, duration).
 */
data class MovieSession(
    val start: String,           // "2026-02-11 17:07:51"
    val end: String,             // "2026-02-11 18:30:00" (empty if still playing)
    val startUnix: Long,         // raw unix timestamp
    val endUnix: Long?,          // raw unix timestamp, null if ongoing
    val durationMin: Int?        // minutes, null if unknown
)

/**
 * A movie/series entry from the bridge, potentially with multiple sessions.
 */
data class BridgeMovie(
    val title: String,           // "All Her Fault S01E02" or "The Invisible Guest (2016)"
    val season: Int?,            // 1 for TV, null for movies
    val episode: Int?,           // 2 for TV, null for movies
    val date: String,            // "2026-02-11"
    val lastWatched: String,     // datetime of the most recent session start
    val sessions: List<MovieSession>,
    val totalWatchMin: Int?      // total watch time across all sessions
) {
    /** True if this is a TV series episode (has season + episode numbers). */
    val isSeries: Boolean get() = season != null && episode != null

    /** Human-readable "x min" or null. */
    val durationLabel: String? get() = totalWatchMin?.let { if (it > 0) "${it} min" else null }
}

/**
 * High-level service for fetching movie data from the Tail Bridge.
 *
 * Uses [BridgeClient] under the hood — this class adds movie-specific parsing
 * on top of the generic JSON fetch.
 *
 * ## Usage
 * ```kotlin
 * val service = MovieBridgeService()
 * val movie = service.fetchLatestSuggestion(url, token, exclude = listOf("Already logged"))
 * if (movie != null) {
 *     // Show confirm dialog with movie.title
 * }
 * ```
 */
class MovieBridgeService {

    companion object {
        private const val TAG = "MovieBridge"
    }

    private val client = BridgeClient()

    /**
     * Fetches the most recent movie, optionally excluding titles the user
     * has already logged. This is the primary call for the "movie suggestion"
     * flow — when the user taps the movie habit, this returns what to pre-fill.
     *
     * @param bridgeUrl  Bridge server base URL
     * @param token      X-App-Auth token
     * @param exclude    Titles to skip (e.g. already-confirmed entries for today)
     * @return The suggested movie, or null if the bridge is unreachable / no data
     */
    suspend fun fetchLatestSuggestion(
        bridgeUrl: String,
        token: String,
        exclude: List<String> = emptyList()
    ): BridgeMovie? = withContext(Dispatchers.IO) {
        val excludeParam = if (exclude.isNotEmpty()) {
            "?exclude=" + exclude.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") }
        } else ""
        val json = client.fetch(bridgeUrl, token, "movies/suggest$excludeParam")
        json?.let { parseMovie(it) }
    }

    /**
     * Fetches the latest movie unconditionally (no exclusion).
     */
    suspend fun fetchLatest(bridgeUrl: String, token: String): BridgeMovie? =
        withContext(Dispatchers.IO) {
            val json = client.fetch(bridgeUrl, token, "movies/latest")
            json?.let { parseMovie(it) }
        }

    /**
     * Fetches the N most recent movies.
     */
    suspend fun fetchRecent(
        bridgeUrl: String,
        token: String,
        limit: Int = 10
    ): List<BridgeMovie> = withContext(Dispatchers.IO) {
        val json = client.fetch(bridgeUrl, token, "movies/recent?limit=$limit")
        if (json == null) return@withContext emptyList()
        try {
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).mapNotNull { i ->
                parseMovie(items.getJSONObject(i))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recent movies: ${e.message}")
            emptyList()
        }
    }

    /**
     * Checks if the bridge is reachable and the token is valid.
     */
    suspend fun testConnection(bridgeUrl: String, token: String): Boolean =
        client.checkHealth(bridgeUrl, token)

    // ── Parsing ─────────────────────────────────────────────────────────────

    private fun parseMovie(json: JSONObject): BridgeMovie? {
        return try {
            val title = json.optString("title", "")
            if (title.isBlank()) return null

            val sessions = mutableListOf<MovieSession>()
            val sessionsArray = json.optJSONArray("sessions")
            if (sessionsArray != null) {
                for (i in 0 until sessionsArray.length()) {
                    val s = sessionsArray.getJSONObject(i)
                    sessions.add(MovieSession(
                        start = s.optString("start", ""),
                        end = s.optString("end", ""),
                        startUnix = s.optLong("start_unix", 0),
                        endUnix = s.optLong("end_unix", 0).takeIf { it > 0 },
                        durationMin = s.optInt("duration_min", -1).takeIf { it >= 0 }
                    ))
                }
            }

            BridgeMovie(
                title = title,
                season = json.optInt("season", -1).takeIf { it >= 0 },
                episode = json.optInt("episode", -1).takeIf { it >= 0 },
                date = json.optString("date", ""),
                lastWatched = json.optString("last_watched", json.optString("datetime", "")),
                sessions = sessions,
                totalWatchMin = json.optInt("total_watch_min", -1).takeIf { it >= 0 }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse movie JSON: ${e.message}")
            null
        }
    }
}
