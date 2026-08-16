package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private const val TAG = "ChessComRepo"

/**
 * The 3 chess.com activity types that can be linked to habits.
 * Note: Puzzle types (PUZZLE_SLOW, PUZZLE_RUSH) are not supported by the chess.com API
 * for per-day history, so they are not available as linkable habit types.
 */
enum class ChessComType(val label: String) {
    BULLET("Bullet"),
    BLITZ("Blitz"),
    RAPID("Rapid");

    companion object {
        fun fromKey(key: String): ChessComType? = entries.find { it.name == key }
    }
}

/**
 * Raw daily chess.com stats for one game type on one day.
 * All three values are stored directly into the linked habit:
 *  - [games]  → the habit's primary count
 *  - [minutes] → the `secondary_value:` slot
 *  - [wins]   → the `secondary_value2:` slot (outcome: 1 per win, 0 otherwise)
 */
data class ChessComDailyStats(
    /** Estimated minutes played (base clock time per game, summed). */
    val minutes: Double,
    /** Number of games played. */
    val games: Int,
    /** Number of games won (outcome 1 = win, 0 = loss/draw, summed). */
    val wins: Int
) {
    operator fun plus(other: ChessComDailyStats) = ChessComDailyStats(
        minutes = minutes + other.minutes,
        games = games + other.games,
        wins = wins + other.wins
    )

    companion object {
        val ZERO = ChessComDailyStats(0.0, 0, 0)
    }
}

/** Per-day stats for each game type, keyed by date string "YYYY-MM-DD". */
typealias DailyStatsMap = Map<String, ChessComDailyStats>

/**
 * Computes the duration in minutes for a single game based on its time_control string.
 * Time control formats:
 *   - "600" → 10 minutes (flat time)
 *   - "180+2" → 3 minutes base + increment (we use base time as approximation)
 *   - "1/86400" → daily game (1 move per day) — we ignore these
 *
 * For games with increment, we estimate total game time as base_time since
 * we don't have move count without PGN parsing. This is a reasonable approximation.
 */
internal fun estimateGameMinutes(timeControl: String): Double {
    if (timeControl.contains("/")) return 0.0 // daily/correspondence game
    val parts = timeControl.split("+")
    val baseSeconds = parts.firstOrNull()?.toDoubleOrNull() ?: return 0.0
    return baseSeconds / 60.0
}

/**
 * Classifies a game based on its time_control value, matching chess.com's app behavior.
 * The API's time_class field is sometimes inconsistent (e.g. 10-min games as "blitz"),
 * so we classify based on the actual base time:
 *   - Bullet: base time < 180 seconds (< 3 min)
 *   - Blitz: base time >= 180 and < 600 seconds (3 to <10 min)
 *   - Rapid: base time >= 600 seconds (10+ min)
 */
internal fun classifyByTimeControl(timeControl: String): ChessComType? {
    if (timeControl.contains("/")) return null // daily/correspondence
    val baseSeconds = timeControl.split("+").firstOrNull()?.toDoubleOrNull() ?: return null
    return when {
        baseSeconds < 180 -> ChessComType.BULLET
        baseSeconds < 600 -> ChessComType.BLITZ
        else -> ChessComType.RAPID
    }
}

/**
 * Processes a list of games into per-day stats (minutes, games, wins) grouped by
 * game type. Only counts games where the user participated (matches username).
 * Pure function — unit-testable without Android dependencies.
 */
internal fun computeDailyChessStats(
    games: List<ChessComGame>,
    username: String,
    zone: ZoneId = ZoneId.systemDefault()
): Map<ChessComType, DailyStatsMap> {
    val result = mutableMapOf<ChessComType, MutableMap<String, ChessComDailyStats>>()
    val userLower = username.lowercase()

    for (game in games) {
        // Verify the user actually played this game
        val isWhite = game.whiteUsername.lowercase() == userLower
        val isBlack = game.blackUsername.lowercase() == userLower
        if (!isWhite && !isBlack) continue

        // Classify by actual time control, not the API's time_class
        // (chess.com API sometimes misclassifies, e.g. 10-min as "blitz")
        val type = classifyByTimeControl(game.timeControl) ?: continue

        val minutes = estimateGameMinutes(game.timeControl)
        if (minutes <= 0) continue

        val won = if (isWhite) {
            game.whiteResult == CHESS_COM_RESULT_WIN
        } else {
            game.blackResult == CHESS_COM_RESULT_WIN
        }

        val date = Instant.ofEpochSecond(game.endTime).atZone(zone).toLocalDate()
        val dateStr = dateString(date)

        val dayMap = result.getOrPut(type) { mutableMapOf() }
        val existing = dayMap[dateStr] ?: ChessComDailyStats.ZERO
        dayMap[dateStr] = existing + ChessComDailyStats(
            minutes = minutes,
            games = 1,
            wins = if (won) 1 else 0
        )
    }

    return result.mapValues { it.value.toMap() }
}

/**
 * Processes chess.com API data into per-day stats (minutes, games, wins) for each
 * game type, and caches monthly archive data to avoid re-fetching historical months.
 *
 * Cache is stored as JSON files in the app's internal storage under `chess_com_cache/`.
 * Each file is named `{username}_{YYYY}_{MM}.json` and contains the processed
 * per-day stats for that month in the format:
 * `{ "BLITZ": { "2026-08-15": { "minutes": 24.0, "games": 8, "wins": 5 } } }`
 */
class ChessComRepository(private val context: Context) {

    private val service = ChessComService()
    private val cacheDir: File
        get() = File(context.filesDir, "chess_com_cache").also { it.mkdirs() }

    /**
     * Fetches and processes games for the current month only (for regular polling).
     * Returns per-day stats for each game type for the current month.
     * Uses cache for completed months, fetches fresh data for current month.
     */
    suspend fun fetchCurrentMonthData(
        username: String
    ): Map<ChessComType, DailyStatsMap> = withContext(Dispatchers.IO) {
        try {
            val now = LocalDate.now()
            val games = service.getGamesForMonth(username, now.year, now.monthValue)
            computeDailyChessStats(games, username)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch current month data: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetches the entire game history for a user (all monthly archives).
     * Caches each completed month so subsequent calls skip already-fetched months.
     * Returns per-day stats for ALL game types across ALL months.
     *
     * @param onProgress Called with (completedMonths, totalMonths) for UI progress.
     */
    suspend fun fetchEntireBacklog(
        username: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<ChessComType, DailyStatsMap> = withContext(Dispatchers.IO) {
        val archiveUrls = service.getArchiveUrls(username)
        val totalMonths = archiveUrls.size
        val allDaily = mutableMapOf<ChessComType, MutableMap<String, ChessComDailyStats>>()

        archiveUrls.forEachIndexed { index, archiveUrl ->
            try {
                // Parse year/month from URL: .../games/YYYY/MM
                val parts = archiveUrl.trimEnd('/').split("/")
                val year = parts[parts.size - 2].toInt()
                val month = parts[parts.size - 1].toInt()

                val monthData = getCachedOrFetch(username, year, month, archiveUrl)
                mergeInto(allDaily, monthData)

                onProgress(index + 1, totalMonths)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch archive $archiveUrl: ${e.message}")
                onProgress(index + 1, totalMonths)
            }
        }

        allDaily.mapValues { it.value.toMap() }
    }

    /**
     * Gets puzzle stats (for puzzle_slow and puzzle_rush tracking).
     * Note: Chess.com API doesn't provide per-day puzzle history,
     * so we can only track current totals and compute deltas.
     */
    suspend fun fetchPuzzleStats(username: String): ChessComPuzzleStats {
        return service.getPlayerStats(username)
    }

    /** Validates that a chess.com username exists. */
    suspend fun validateUsername(username: String): Boolean {
        return service.validateUsername(username)
    }

    /**
     * Returns cached data for a month if available, otherwise fetches from API and caches.
     * Current month is never cached (always fetched fresh).
     */
    private suspend fun getCachedOrFetch(
        username: String,
        year: Int,
        month: Int,
        archiveUrl: String
    ): Map<ChessComType, DailyStatsMap> {
        val now = YearMonth.now()
        val isCurrentMonth = year == now.year && month == now.monthValue

        // Try cache first (but not for current month — it's still in progress)
        if (!isCurrentMonth) {
            val cached = loadFromCache(username, year, month)
            if (cached != null) return cached
        }

        // Fetch from API
        val games = service.getGamesForMonth(archiveUrl)
        val daily = computeDailyChessStats(games, username)

        // Cache completed months
        if (!isCurrentMonth) {
            saveToCache(username, year, month, daily)
        }

        return daily
    }

    // ── Cache I/O ────────────────────────────────────────────────────────────

    private fun cacheFile(username: String, year: Int, month: Int): File {
        val monthStr = month.toString().padStart(2, '0')
        return File(cacheDir, "${username.lowercase()}_${year}_$monthStr.json")
    }

    private fun saveToCache(
        username: String,
        year: Int,
        month: Int,
        data: Map<ChessComType, DailyStatsMap>
    ) {
        try {
            val json = JSONObject()
            for ((type, dayMap) in data) {
                val typeJson = JSONObject()
                for ((date, stats) in dayMap) {
                    val statsJson = JSONObject()
                    statsJson.put("minutes", stats.minutes)
                    statsJson.put("games", stats.games)
                    statsJson.put("wins", stats.wins)
                    typeJson.put(date, statsJson)
                }
                json.put(type.name, typeJson)
            }
            cacheFile(username, year, month).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache: ${e.message}")
        }
    }

    private fun loadFromCache(
        username: String,
        year: Int,
        month: Int
    ): Map<ChessComType, DailyStatsMap>? {
        val file = cacheFile(username, year, month)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<ChessComType, DailyStatsMap>()
            for (typeName in json.keys()) {
                val type = ChessComType.fromKey(typeName) ?: continue
                val typeJson = json.getJSONObject(typeName)
                val dayMap = mutableMapOf<String, ChessComDailyStats>()
                for (date in typeJson.keys()) {
                    val statsJson = typeJson.getJSONObject(date)
                    dayMap[date] = ChessComDailyStats(
                        minutes = statsJson.optDouble("minutes", 0.0),
                        games = statsJson.optInt("games", 0),
                        wins = statsJson.optInt("wins", 0)
                    )
                }
                result[type] = dayMap
            }
            result
        } catch (e: Exception) {
            // Legacy cache format (plain per-day minutes) or corrupt file —
            // treat as a cache miss so the month is re-fetched fresh.
            Log.w(TAG, "Failed to read cache (will re-fetch): ${e.message}")
            null
        }
    }

    /** Clears all cached chess.com data. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mergeInto(
        target: MutableMap<ChessComType, MutableMap<String, ChessComDailyStats>>,
        source: Map<ChessComType, DailyStatsMap>
    ) {
        for ((type, dayMap) in source) {
            val targetDay = target.getOrPut(type) { mutableMapOf() }
            for ((date, stats) in dayMap) {
                val existing = targetDay[date] ?: ChessComDailyStats.ZERO
                targetDay[date] = existing + stats
            }
        }
    }
}
