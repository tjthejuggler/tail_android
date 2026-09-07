package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "ChessComRepo"

/** Pause between sequential archive requests so full sweeps stay within chess.com rate limits. */
private const val ARCHIVE_FETCH_PACE_MS = 250L

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
 * Returns the wall-clock end times ("HH:mm:ss", oldest first) of the [count]
 * NEWEST games of [type] played by [username] that ended on [dateStr].
 *
 * Habit timestamps are recorded per NEW game, and games accumulate
 * chronologically — so the newest [count] games ending that day are exactly
 * the not-yet-stamped ones. Stamping at the actual end time (instead of sync
 * time) keeps the schedule view truthful: N games no longer collapse into a
 * single ×N event stacked at the moment the poll happened to run.
 *
 * Returns fewer (or no) entries when the raw game list is missing or short;
 * callers fill any shortfall with sync-time stamps.
 */
internal fun newGameEndTimes(
    games: List<ChessComGame>,
    username: String,
    type: ChessComType,
    dateStr: String,
    count: Int,
    zone: ZoneId = ZoneId.systemDefault()
): List<String> {
    if (count <= 0) return emptyList()
    val userLower = username.lowercase()
    val matching = games
        .filter { game ->
            val isPlayer = game.whiteUsername.lowercase() == userLower ||
                game.blackUsername.lowercase() == userLower
            isPlayer &&
                classifyByTimeControl(game.timeControl) == type &&
                dateString(Instant.ofEpochSecond(game.endTime).atZone(zone).toLocalDate()) == dateStr
        }
        .sortedBy { it.endTime }
    return matching.takeLast(count).map { game ->
        Instant.ofEpochSecond(game.endTime).atZone(zone).toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
}

/** Result of a full-archive sweep over the user's entire chess.com history. */
data class ArchiveFetchResult(
    /** Per-day stats for each game type, merged across ALL months. */
    val daily: Map<ChessComType, DailyStatsMap>,
    /** Months that failed to fetch even after retries (0 = complete sweep). */
    val failedMonths: Int,
    /** Total monthly archives discovered on the account. */
    val totalMonths: Int
)

class ChessComRepository(private val context: Context) {

    private val service = ChessComService()

    /**
     * Fetches and processes games for the current month only (for regular polling).
     * Returns per-day stats for each game type for the current month.
     *
     * @param onGames Invoked with the RAW game list before aggregation, so
     *        callers can feed the Chess Readiness activity log without an
     *        extra HTTP round-trip.
     */
    suspend fun fetchCurrentMonthData(
        username: String,
        onGames: (List<ChessComGame>) -> Unit = {}
    ): Map<ChessComType, DailyStatsMap> = withContext(Dispatchers.IO) {
        try {
            val now = LocalDate.now()
            val games = service.getGamesForMonth(username, now.year, now.monthValue)
            onGames(games)

            // Month-rollover safety net: games played in the final minutes of
            // the previous month (after the last poll before midnight) live in
            // the previous month's archive, which polling never revisits. For
            // the first days of each month, re-fetch it fresh so the Chess
            // Readiness activity log also sees them. The log store dedupes,
            // and the returned stats stay current-month-only (the previous
            // month's habit increments were already applied when it was
            // current — re-applying would double-count).
            if (now.dayOfMonth <= 3) {
                try {
                    val prev = now.minusMonths(1)
                    val prevGames = service.getGamesForMonth(username, prev.year, prev.monthValue)
                    if (prevGames.isNotEmpty()) onGames(prevGames)
                } catch (e: Exception) {
                    Log.w(TAG, "Prev-month rollover fetch failed: ${e.message}")
                }
            }

            computeDailyChessStats(games, username)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch current month data: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetches the RAW game list of EVERY monthly archive on the account —
     * all the way back to when the chess.com account was created — and
     * hands every month's games to [onGames], so the Chess Readiness
     * activity log sees the ENTIRE history (the old cache-backed path
     * skipped cached months, leaving pre-feature months unlogged).
     *
     * Requests are paced and retried (see [ChessComService]) so long sweeps
     * survive chess.com rate limiting. A month that still fails is skipped
     * and counted in [ArchiveFetchResult.failedMonths] — callers decide
     * whether a partial sweep is acceptable.
     *
     * @param onProgress Called with (completedMonths, totalMonths) for UI progress.
     * @param onGames Invoked with EVERY month's RAW game list for the
     *        Chess Readiness activity log (deduping happens inside the store).
     */
    suspend fun fetchAllArchiveGames(
        username: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onGames: (List<ChessComGame>) -> Unit = {}
    ): ArchiveFetchResult = withContext(Dispatchers.IO) {
        val archiveUrls = service.getArchiveUrls(username)
        val totalMonths = archiveUrls.size
        val allDaily = mutableMapOf<ChessComType, MutableMap<String, ChessComDailyStats>>()
        var failed = 0

        archiveUrls.forEachIndexed { index, archiveUrl ->
            try {
                val games = service.getGamesForMonth(archiveUrl)
                onGames(games)
                mergeInto(allDaily, computeDailyChessStats(games, username))
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to fetch archive $archiveUrl: ${e.message}")
            }
            onProgress(index + 1, totalMonths)
            if (index < archiveUrls.lastIndex) delay(ARCHIVE_FETCH_PACE_MS)
        }

        ArchiveFetchResult(
            daily = allDaily.mapValues { it.value.toMap() },
            failedMonths = failed,
            totalMonths = totalMonths
        )
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
