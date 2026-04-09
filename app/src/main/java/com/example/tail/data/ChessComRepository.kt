package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

private const val TAG = "ChessComRepo"

/**
 * The 5 chess.com activity types that can be linked to habits.
 */
enum class ChessComType(val label: String) {
    BULLET("Bullet"),
    BLITZ("Blitz"),
    RAPID("Rapid"),
    PUZZLE_SLOW("Puzzles (Slow)"),
    PUZZLE_RUSH("Puzzles (Rush)");

    companion object {
        fun fromKey(key: String): ChessComType? = entries.find { it.name == key }
    }
}

/**
 * Per-day minutes played for each game type, keyed by date string "YYYY-MM-DD".
 * Value is total minutes (as Double for precision before rounding to increments).
 */
typealias DailyMinutesMap = Map<String, Double>

/**
 * Processes chess.com API data into per-day minutes for each game type,
 * and caches monthly archive data to avoid re-fetching historical months.
 *
 * Cache is stored as JSON files in the app's internal storage under `chess_com_cache/`.
 * Each file is named `{username}_{YYYY}_{MM}.json` and contains the processed
 * per-day minutes for that month.
 */
class ChessComRepository(private val context: Context) {

    private val service = ChessComService()
    private val cacheDir: File
        get() = File(context.filesDir, "chess_com_cache").also { it.mkdirs() }

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
    fun estimateGameMinutes(timeControl: String): Double {
        if (timeControl.contains("/")) return 0.0 // daily/correspondence game
        val parts = timeControl.split("+")
        val baseSeconds = parts.firstOrNull()?.toDoubleOrNull() ?: return 0.0
        return baseSeconds / 60.0
    }

    /**
     * Fetches and processes games for the current month only (for regular polling).
     * Returns per-day minutes for each game type for the current month.
     * Uses cache for completed months, fetches fresh data for current month.
     */
    suspend fun fetchCurrentMonthData(
        username: String
    ): Map<ChessComType, DailyMinutesMap> = withContext(Dispatchers.IO) {
        try {
            val now = LocalDate.now()
            val games = service.getGamesForMonth(username, now.year, now.monthValue)
            processGamesToDaily(games, username)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch current month data: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetches the entire game history for a user (all monthly archives).
     * Caches each completed month so subsequent calls skip already-fetched months.
     * Returns per-day minutes for ALL game types across ALL months.
     *
     * @param onProgress Called with (completedMonths, totalMonths) for UI progress.
     */
    suspend fun fetchEntireBacklog(
        username: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<ChessComType, DailyMinutesMap> = withContext(Dispatchers.IO) {
        val archiveUrls = service.getArchiveUrls(username)
        val totalMonths = archiveUrls.size
        val allDaily = mutableMapOf<ChessComType, MutableMap<String, Double>>()

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
    ): Map<ChessComType, DailyMinutesMap> {
        val now = YearMonth.now()
        val isCurrentMonth = year == now.year && month == now.monthValue

        // Try cache first (but not for current month — it's still in progress)
        if (!isCurrentMonth) {
            val cached = loadFromCache(username, year, month)
            if (cached != null) return cached
        }

        // Fetch from API
        val games = service.getGamesForMonth(archiveUrl)
        val daily = processGamesToDaily(games, username)

        // Cache completed months
        if (!isCurrentMonth) {
            saveToCache(username, year, month, daily)
        }

        return daily
    }

    /**
     * Processes a list of games into per-day minutes grouped by game type.
     * Only counts games where the user participated (matches username).
     */
    /**
     * Classifies a game based on its time_control value, matching chess.com's app behavior.
     * The API's time_class field is sometimes inconsistent (e.g. 10-min games as "blitz"),
     * so we classify based on the actual base time:
     *   - Bullet: base time < 180 seconds (< 3 min)
     *   - Blitz: base time >= 180 and < 600 seconds (3 to <10 min)
     *   - Rapid: base time >= 600 seconds (10+ min)
     */
    private fun classifyByTimeControl(timeControl: String): ChessComType? {
        if (timeControl.contains("/")) return null // daily/correspondence
        val baseSeconds = timeControl.split("+").firstOrNull()?.toDoubleOrNull() ?: return null
        return when {
            baseSeconds < 180 -> ChessComType.BULLET
            baseSeconds < 600 -> ChessComType.BLITZ
            else -> ChessComType.RAPID
        }
    }

    private fun processGamesToDaily(
        games: List<ChessComGame>,
        username: String
    ): Map<ChessComType, DailyMinutesMap> {
        val result = mutableMapOf<ChessComType, MutableMap<String, Double>>()
        val userLower = username.lowercase()

        // Count games by our classification for logging
        val typeCounts = mutableMapOf<String, Int>()

        for (game in games) {
            // Verify the user actually played this game
            if (game.whiteUsername.lowercase() != userLower &&
                game.blackUsername.lowercase() != userLower) continue

            // Classify by actual time control, not the API's time_class
            // (chess.com API sometimes misclassifies, e.g. 10-min as "blitz")
            val type = classifyByTimeControl(game.timeControl) ?: continue

            typeCounts[type.name] = (typeCounts[type.name] ?: 0) + 1

            val minutes = estimateGameMinutes(game.timeControl)
            if (minutes <= 0) continue

            val date = Instant.ofEpochSecond(game.endTime)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val dateStr = dateString(date)

            val dayMap = result.getOrPut(type) { mutableMapOf() }
            dayMap[dateStr] = (dayMap[dateStr] ?: 0.0) + minutes
        }

        Log.d(TAG, "Processed ${games.size} games for $username — types: $typeCounts")
        for ((type, dayMap) in result) {
            val todayStr = dateString(LocalDate.now())
            val todayMin = dayMap[todayStr]
            if (todayMin != null) {
                Log.d(TAG, "  Today ($todayStr) ${type.name}: ${String.format("%.1f", todayMin)} min")
            }
        }

        return result.mapValues { it.value.toMap() }
    }

    /**
     * Computes habit increments from daily minutes using the configured minutes-per-increment.
     * Any activity > 0 minutes always gives at least 1 point. Additional points use
     * standard rounding: max(1, round(minutes / minutesPerIncrement)).
     *
     * Example with 30 min per increment:
     *   1 min → 1, 15 min → 1, 45 min → 2, 60 min → 2, 90 min → 3
     *
     * Returns a map of date → increment count.
     */
    fun computeIncrements(
        dailyMinutes: DailyMinutesMap,
        minutesPerIncrement: Int
    ): Map<String, Int> {
        if (minutesPerIncrement <= 0) return emptyMap()
        return dailyMinutes.mapValues { (_, minutes) ->
            if (minutes <= 0) 0
            else maxOf(1, Math.round(minutes / minutesPerIncrement).toInt())
        }.filter { it.value > 0 }
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
        data: Map<ChessComType, DailyMinutesMap>
    ) {
        try {
            val json = JSONObject()
            for ((type, dayMap) in data) {
                val typeJson = JSONObject()
                for ((date, minutes) in dayMap) {
                    typeJson.put(date, minutes)
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
    ): Map<ChessComType, DailyMinutesMap>? {
        val file = cacheFile(username, year, month)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<ChessComType, DailyMinutesMap>()
            for (typeName in json.keys()) {
                val type = ChessComType.fromKey(typeName) ?: continue
                val typeJson = json.getJSONObject(typeName)
                val dayMap = mutableMapOf<String, Double>()
                for (date in typeJson.keys()) {
                    dayMap[date] = typeJson.getDouble(date)
                }
                result[type] = dayMap
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache: ${e.message}")
            null
        }
    }

    /** Clears all cached chess.com data. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mergeInto(
        target: MutableMap<ChessComType, MutableMap<String, Double>>,
        source: Map<ChessComType, DailyMinutesMap>
    ) {
        for ((type, dayMap) in source) {
            val targetDay = target.getOrPut(type) { mutableMapOf() }
            for ((date, minutes) in dayMap) {
                targetDay[date] = (targetDay[date] ?: 0.0) + minutes
            }
        }
    }
}
