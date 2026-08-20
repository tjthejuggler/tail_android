package com.example.tail.data

import com.example.tail.widget.ChessReadinessEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness — pure computation layer for the Readiness Stats screen
 * ════════════════════════════════════════════════════════════════════════
 *
 * Consumes the detailed event log persisted by
 * [com.example.tail.widget.ChessReadinessLogStore] (readiness tests with
 * full telemetry, chess.com games with their readiness context, blocked
 * test attempts) and aggregates it into the numbers shown on the special
 * Chess Readiness stats screen:
 *
 *  - readiness ratings over time (per-day average CCRS, chartable)
 *  - readiness by time of day (6 × 4-hour buckets)
 *  - games played inside valid GREEN authorization windows vs. games
 *    played without authorization (protocol compliance + win rates)
 *
 * Everything here is PURE — no Android dependencies — so it is
 * unit-testable on the JVM, mirroring [computeDailyChessStats].
 */

/** One logged Phase-1 readiness test with its full input telemetry. */
data class ReadinessTestRecord(
    /** Epoch millis at submission. */
    val timestamp: Long,
    /** Composite Cognitive Readiness Score 0–100. */
    val ccrs: Int,
    /** Authorization state name (GREEN_LIGHT / YELLOW_LIGHT / RED_LIGHT). */
    val state: String,
    // Sub-component points
    val sSleep: Int,
    val sClarity: Int,
    val pPuzzle: Int,
    val pRush: Int,
    // Raw inputs
    /** Sleep score 0–100 (Garmin or manual). */
    val sleepScore: Int,
    val sleepFromGarmin: Boolean,
    /** Clarity sliders, raw 1–5 (stress / focus / energy). */
    val stress: Int,
    val focus: Int,
    val energy: Int,
    /** Effective solve times (seconds) of the rated puzzles, in order. */
    val puzzleTimesSec: List<Int>,
    /** Puzzles solved in the 3-minute Puzzle Rush run. */
    val rushScore: Int,
    val rushStrikes: Int,
    /** All-time-high rush baseline in effect at test time. */
    val rushAllTimeHigh: Int,
    /** Epoch millis when the step-by-step test session was started. */
    val sessionStartedAt: Long
)

/** One logged chess.com game with the readiness context at play time. */
data class ReadinessGameRecord(
    /** Epoch millis when the game ended. */
    val endTimeMs: Long,
    /** Classified game type name (BULLET / BLITZ / RAPID). */
    val type: String,
    /** Opponent username (the non-user side of the game). */
    val opponent: String,
    val won: Boolean,
    /** Estimated minutes played (base clock time). */
    val minutes: Double,
    /** CCRS of the latest test at/before the game ended (null = no test yet). */
    val ccrsAtPlay: Int?,
    /** State name of that latest test (null = no test yet). */
    val stateAtPlay: String?,
    /**
     * True when the game was played inside a valid GREEN authorization
     * window (rated play permitted at the moment the game ended).
     */
    val authorized: Boolean,
    /** Rules variant name ("chess", "chess960", …). */
    val variant: String = "chess",
    /** Whether the game affected ratings. */
    val rated: Boolean = true,
    /**
     * The user's rating AFTER this game in its (variant × speed) pool;
     * null for unrated games or when the API didn't report one.
     */
    val ratingAfter: Int? = null
)

/** A blocked readiness test attempt (rate limit / cool-down / rest lock). */
data class ReadinessBlockedRecord(
    val timestamp: Long,
    val reason: String
)

/** Readiness + activity aggregates for one 4-hour time-of-day bucket. */
data class ReadinessTimeBucket(
    /** Display label, e.g. "08–12". */
    val label: String,
    /** Inclusive start hour of the bucket (0, 4, 8, 12, 16, 20). */
    val rangeStartHour: Int,
    val testCount: Int,
    val avgCcrs: Double,
    val greenCount: Int,
    val gamesPlayed: Int,
    val gamesWon: Int
)

/** Full aggregate consumed by the Chess Readiness stats screen. */
data class ReadinessStats(
    // Tests
    val totalTests: Int,
    val avgCcrs: Double,
    val bestCcrs: Int,
    val bestTestAt: Long?,
    val worstCcrs: Int,
    val worstTestAt: Long?,
    val greenCount: Int,
    val yellowCount: Int,
    val redCount: Int,
    // Average sub-component contributions (0–25 each)
    val avgSleepPts: Double,
    val avgClarityPts: Double,
    val avgPuzzlePts: Double,
    val avgRushPts: Double,
    /** Average wall-clock minutes from session start to submission. */
    val avgTestDurationMin: Double,
    val firstTestAt: Long?,
    val lastTestAt: Long?,
    // Over time
    /** Chronological date → average CCRS (one entry per day with tests). */
    val dailyAvgCcrs: List<Pair<String, Int>>,
    // Time of day
    val timeBuckets: List<ReadinessTimeBucket>,
    val bestBucketLabel: String?,
    val worstBucketLabel: String?,
    // Games vs readiness
    val totalGames: Int,
    /** Games played inside a valid GREEN authorization window. */
    val gamesAuthorized: Int,
    /**
     * Games played when a test existed but did NOT authorize play
     * (Red/Yellow state, or the Green window had already expired).
     */
    val gamesUnauthorized: Int,
    /** Games played before any test had ever been logged. */
    val gamesNoTest: Int,
    val winsAuthorized: Int,
    val winsUnauthorized: Int,
    val winRateAuthorized: Double,
    val winRateUnauthorized: Double,
    /** Distinct GREEN tests whose window covered at least one game. */
    val greenSessions: Int,
    val avgGamesPerGreenSession: Double,
    val maxGamesInOneGreenSession: Int,
    /** authorized / (authorized + unauthorized) × 100; 100 when no games. */
    val complianceRate: Double,
    // Gate blocks
    val blockedAttempts: Int
)

// ── Context / mapping helpers ─────────────────────────────────────────────

/**
 * Stable dedupe key for a logged game. chess.com monthly archives don't
 * expose a game id in the light [ChessComGame] model, so the key combines
 * the end timestamp (unique to the second in practice), the opponent and
 * the time control — re-fetching the same month can never double-log.
 */
fun gameDedupeKey(endTimeSec: Long, opponent: String, timeControl: String): String =
    "$endTimeSec|${opponent.trim().lowercase()}|$timeControl"

/**
 * Finds the readiness context at a point in time: the latest test submitted
 * at/before [timeMs], plus whether that test authorized RATED play at that
 * moment (GREEN state and still inside its validity window).
 *
 * Returns (null, false) when no test precedes the moment.
 */
fun readinessContextAt(
    tests: List<ReadinessTestRecord>,
    timeMs: Long
): Pair<ReadinessTestRecord?, Boolean> {
    val latest = tests
        .filter { it.timestamp <= timeMs }
        .maxByOrNull { it.timestamp }
        if (latest == null) return null to false
    val authorized = latest.state == ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name &&
        timeMs - latest.timestamp <= ChessReadinessEngine.SESSION_VALIDITY_MS
    return latest to authorized
}

/**
 * Converts one raw chess.com archive game into a [ReadinessGameRecord],
 * resolving the readiness context from [tests]. Returns null when the user
 * did not participate in the game or it has no classifiable time control
 * (daily/correspondence games are not tracked).
 */
fun gameToRecord(
    game: ChessComGame,
    username: String,
    tests: List<ReadinessTestRecord>
): ReadinessGameRecord? {
    val userLower = username.lowercase()
    val isWhite = game.whiteUsername.lowercase() == userLower
    val isBlack = game.blackUsername.lowercase() == userLower
    if (!isWhite && !isBlack) return null

    val type = classifyByTimeControl(game.timeControl) ?: return null
    val endTimeMs = game.endTime * 1000L
    val (context, authorized) = readinessContextAt(tests, endTimeMs)
    val won = if (isWhite) {
        game.whiteResult == CHESS_COM_RESULT_WIN
    } else {
        game.blackResult == CHESS_COM_RESULT_WIN
    }
    val myRating = if (isWhite) game.whiteRating else game.blackRating

    return ReadinessGameRecord(
        endTimeMs = endTimeMs,
        type = type.name,
        opponent = (if (isWhite) game.blackUsername else game.whiteUsername),
        won = won,
        minutes = estimateGameMinutes(game.timeControl),
        ccrsAtPlay = context?.ccrs,
        stateAtPlay = context?.state,
        authorized = authorized,
        variant = game.rules,
        rated = game.rated,
        ratingAfter = if (game.rated && myRating > 0) myRating else null
    )
}

// ── Master aggregation ─────────────────────────────────────────────────────

/** The six 4-hour time-of-day bucket labels, in order. */
private val BUCKET_LABELS = listOf("00–04", "04–08", "08–12", "12–16", "16–20", "20–24")

/** Bucket index (0–5) of an epoch-millis timestamp in [zone]. */
fun timeBucketIndex(timeMs: Long, zone: ZoneId): Int {
    val hour = Instant.ofEpochMilli(timeMs).atZone(zone).hour
    return (hour / 4).coerceIn(0, 5)
}

/**
 * Aggregates the full readiness log into [ReadinessStats]. Pure function;
 * [zone] controls date/hour bucketing (pass the device zone from the UI).
 */
fun computeReadinessStats(
    tests: List<ReadinessTestRecord>,
    games: List<ReadinessGameRecord>,
    blocked: List<ReadinessBlockedRecord>,
    zone: ZoneId = ZoneId.systemDefault()
): ReadinessStats {
    val sortedTests = tests.sortedBy { it.timestamp }
    val green = ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name
    val yellow = ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name

    // ── Test aggregates ───────────────────────────────────────────────────
    val totalTests = sortedTests.size
    val avgCcrs = if (totalTests > 0) sortedTests.sumOf { it.ccrs }.toDouble() / totalTests else 0.0
    val best = sortedTests.maxByOrNull { it.ccrs }
    val worst = sortedTests.minByOrNull { it.ccrs }
    val avgSub = { sel: (ReadinessTestRecord) -> Int ->
        if (totalTests > 0) sortedTests.sumOf(sel).toDouble() / totalTests else 0.0
    }
    val avgDuration = if (totalTests > 0) {
        sortedTests.sumOf { (it.timestamp - it.sessionStartedAt).coerceAtLeast(0) } /
            totalTests / 60000.0
    } else 0.0

    // ── Per-day average CCRS (chart series) ───────────────────────────────
    val byDay = LinkedHashMap<String, MutableList<Int>>()
    for (t in sortedTests) {
        val d = Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalDate()
        byDay.getOrPut(dateString(d)) { mutableListOf() }.add(t.ccrs)
    }
    val dailyAvgCcrs = byDay.map { (day, scores) ->
        day to Math.round(scores.sum().toDouble() / scores.size).toInt()
    }

    // ── Time-of-day buckets ───────────────────────────────────────────────
    data class BucketAcc(
        var tests: Int = 0, var ccrsSum: Int = 0, var green: Int = 0,
        var games: Int = 0, var wins: Int = 0
    )
    val acc = List(6) { BucketAcc() }
    for (t in sortedTests) {
        val b = acc[timeBucketIndex(t.timestamp, zone)]
        b.tests++; b.ccrsSum += t.ccrs
        if (t.state == green) b.green++
    }
    for (g in games) {
        val b = acc[timeBucketIndex(g.endTimeMs, zone)]
        b.games++
        if (g.won) b.wins++
    }
    val timeBuckets = acc.mapIndexed { i, b ->
        ReadinessTimeBucket(
            label = BUCKET_LABELS[i],
            rangeStartHour = i * 4,
            testCount = b.tests,
            avgCcrs = if (b.tests > 0) b.ccrsSum.toDouble() / b.tests else 0.0,
            greenCount = b.green,
            gamesPlayed = b.games,
            gamesWon = b.wins
        )
    }
    val ratedBuckets = timeBuckets.filter { it.testCount > 0 }
    val bestBucket = ratedBuckets.maxByOrNull { it.avgCcrs }
    val worstBucket = ratedBuckets.minByOrNull { it.avgCcrs }

    // ── Games vs readiness ────────────────────────────────────────────────
    val gamesAuthorized = games.count { it.authorized }
    val gamesNoTest = games.count { it.stateAtPlay == null }
    val gamesUnauthorized = games.size - gamesAuthorized - gamesNoTest
    val winsAuthorized = games.count { it.authorized && it.won }
    val winsUnauthorized = games.count { !it.authorized && it.stateAtPlay != null && it.won }
    val winRate = { w: Int, n: Int -> if (n > 0) w * 100.0 / n else 0.0 }

    // Authorized sessions: group authorized games by the test that
    // authorized them (identified by ccrsAtPlay + the test timestamp is
    // implicit — a GREEN test's 60-min window is the "session").
    val sessionCounts = HashMap<Long, Int>()
    for (g in games) {
        if (!g.authorized) continue
        // Re-resolve the authorizing test to key the session by its timestamp.
        val (ctx, _) = readinessContextAt(sortedTests, g.endTimeMs)
        if (ctx != null) sessionCounts[ctx.timestamp] = (sessionCounts[ctx.timestamp] ?: 0) + 1
    }
    val greenSessions = sessionCounts.size
    val maxInSession = sessionCounts.values.maxOrNull() ?: 0
    val avgPerSession = if (greenSessions > 0)
        gamesAuthorized.toDouble() / greenSessions else 0.0
    val compliance = if (gamesAuthorized + gamesUnauthorized > 0)
        gamesAuthorized * 100.0 / (gamesAuthorized + gamesUnauthorized) else 100.0

    return ReadinessStats(
        totalTests = totalTests,
        avgCcrs = avgCcrs,
        bestCcrs = best?.ccrs ?: 0,
        bestTestAt = best?.timestamp,
        worstCcrs = worst?.ccrs ?: 0,
        worstTestAt = worst?.timestamp,
        greenCount = sortedTests.count { it.state == green },
        yellowCount = sortedTests.count { it.state == yellow },
        redCount = sortedTests.count { it.state !in listOf(green, yellow) },
        avgSleepPts = avgSub { it.sSleep },
        avgClarityPts = avgSub { it.sClarity },
        avgPuzzlePts = avgSub { it.pPuzzle },
        avgRushPts = avgSub { it.pRush },
        avgTestDurationMin = avgDuration,
        firstTestAt = sortedTests.firstOrNull()?.timestamp,
        lastTestAt = sortedTests.lastOrNull()?.timestamp,
        dailyAvgCcrs = dailyAvgCcrs,
        timeBuckets = timeBuckets,
        bestBucketLabel = bestBucket?.label,
        worstBucketLabel = worstBucket?.label,
        totalGames = games.size,
        gamesAuthorized = gamesAuthorized,
        gamesUnauthorized = gamesUnauthorized,
        gamesNoTest = gamesNoTest,
        winsAuthorized = winsAuthorized,
        winsUnauthorized = winsUnauthorized,
        winRateAuthorized = winRate(winsAuthorized, gamesAuthorized),
        winRateUnauthorized = winRate(winsUnauthorized, gamesUnauthorized),
        greenSessions = greenSessions,
        avgGamesPerGreenSession = avgPerSession,
        maxGamesInOneGreenSession = maxInSession,
        complianceRate = compliance,
        blockedAttempts = blocked.size
    )
}

// ── Compliance over time ────────────────────────────────────────────────────

/** One day of the post-adoption compliance series (one chart bar). */
data class ComplianceDay(
    /** Calendar day (in the aggregation zone) the games ended. */
    val date: LocalDate,
    /** Games played inside a valid GREEN authorization window. */
    val authorized: Int,
    /** Games played while a FRESH test explicitly denied play (Yellow/Red inside its validity window). */
    val violationDenied: Int,
    /** Games played with no fresh test covering them (the latest one had expired) — system bypassed. */
    val violationNoTest: Int
) {
    val total: Int get() = authorized + violationDenied + violationNoTest

    /** Share of the day's games that were authorized (0–100). */
    val compliantPct: Double get() = if (total == 0) 100.0 else authorized * 100.0 / total
}

/**
 * Daily compliance series for the "Compliance Over Time" chart.
 *
 * Only games that ended at/after [systemStartMs] — the timestamp of the
 * first readiness test ever logged, i.e. when the system was adopted —
 * are included; earlier games existed before the system and would only
 * clutter the chart.
 *
 * Each post-adoption game falls into exactly one bucket:
 *  - [ComplianceDay.authorized] — played inside a valid GREEN window ✅
 *  - [ComplianceDay.violationDenied] — a fresh test (still inside its
 *    validity window) said Yellow/Red and play happened anyway 🚫
 *  - [ComplianceDay.violationNoTest] — no fresh test covered the game
 *    (the latest one had already expired) — the system was bypassed ⏭
 */
fun computeComplianceSeries(
    games: List<ReadinessGameRecord>,
    tests: List<ReadinessTestRecord>,
    systemStartMs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): List<ComplianceDay> {
    if (systemStartMs <= 0) return emptyList()
    val acc = LinkedHashMap<LocalDate, IntArray>()
    for (g in games.sortedBy { it.endTimeMs }) {
        if (g.endTimeMs < systemStartMs) continue // pre-adoption → excluded
        val (ctx, authorized) = readinessContextAt(tests, g.endTimeMs)
        val day = Instant.ofEpochMilli(g.endTimeMs).atZone(zone).toLocalDate()
        val a = acc.getOrPut(day) { IntArray(3) }
        when {
            authorized -> a[0]++
            ctx != null && g.endTimeMs - ctx.timestamp <= ChessReadinessEngine.SESSION_VALIDITY_MS -> a[1]++
            else -> a[2]++
        }
    }
    return acc.map { (day, a) -> ComplianceDay(day, a[0], a[1], a[2]) }
}

// ── Rating impact (compliant vs non-compliant) ──────────────────────────────

/** Rating-delta aggregates for one compliance category in one rating pool. */
data class RatingCategoryStats(
    /** Categorized games whose rating delta was measured. */
    val games: Int,
    /** Sum of per-game rating deltas (points). */
    val totalDelta: Int,
    /** Average rating delta per game (points). */
    val avgDelta: Double,
    val wins: Int
) {
    val winRate: Double get() = if (games > 0) wins * 100.0 / games else 0.0
}

/**
 * Rating stats for one pool. A pool is a distinct chess.com rating:
 * variant × speed (e.g. Chess960 Blitz is separate from Standard Blitz).
 */
data class RatingPoolStats(
    /** Display label, e.g. "Chess960 · Blitz". */
    val label: String,
    /** Pool key: "variant|TYPE". */
    val key: String,
    /** Rated games logged in this pool (delta measurable for all but the first). */
    val ratedGames: Int,
    /** Rating after the most recent rated game in the pool (null = unknown). */
    val currentRating: Int?,
    val authorized: RatingCategoryStats,
    /** Violations = played despite a blocking test OR without a fresh test. */
    val violations: RatingCategoryStats
)

private fun prettyVariant(variant: String): String = when (variant.lowercase()) {
    "chess" -> "Standard"
    "chess960" -> "Chess960"
    "kingofthehill" -> "King of the Hill"
    "threecheck" -> "Three-check"
    else -> variant.replaceFirstChar { it.uppercase() }
}

private fun prettyType(type: String): String =
    type.lowercase().replaceFirstChar { it.uppercase() }

/**
 * Rating pool key. Standard chess has a separate chess.com rating per
 * speed (bullet/blitz/rapid), but every other variant (chess960,
 * kingofthehill, …) carries a SINGLE chess.com rating regardless of game
 * speed — so all of a variant's games, across all speeds, merge into one
 * pool with one continuous rating chain.
 */
internal fun ratingPoolKey(variant: String, type: String): String =
    if (variant.equals("chess", ignoreCase = true)) "$variant|$type" else variant.lowercase()

/** Display label for a [ratingPoolKey]: "Standard · Blitz" or just "Chess960". */
internal fun ratingPoolLabel(key: String): String {
    val idx = key.indexOf('|')
    return if (idx < 0) prettyVariant(key)
    else "${prettyVariant(key.substring(0, idx))} · ${prettyType(key.substring(idx + 1))}"
}

/**
 * Computes per-game rating deltas and splits them by compliance category,
 * per rating pool (see [ratingPoolKey]: standard chess per speed; each
 * variant — e.g. Chess960 — is ONE pool because chess.com gives variants
 * a single rating).
 *
 * Delta chain: within a pool, a game's delta is its `ratingAfter` minus the
 * `ratingAfter` of the previous RATED game in that pool (unrated games never
 * change ratings, so they are skipped; the pool's first game has no
 * measurable delta and only serves as the baseline). Pre-adoption games
 * keep the chain intact but are not attributed to either category.
 */
fun computeRatingStats(
    games: List<ReadinessGameRecord>,
    tests: List<ReadinessTestRecord>,
    systemStartMs: Long
): List<RatingPoolStats> {
    val pools = games
        .filter { it.rated && it.ratingAfter != null }
        .groupBy { ratingPoolKey(it.variant, it.type) }

    val result = pools.map { (key, poolGames) ->
        val sorted = poolGames.sortedBy { it.endTimeMs }
        var prevRating: Int? = null
        var authGames = 0; var authDelta = 0; var authWins = 0
        var violGames = 0; var violDelta = 0; var violWins = 0

        for (g in sorted) {
            val rating = g.ratingAfter!!
            val delta = prevRating?.let { rating - it }
            prevRating = rating
            if (delta == null) continue // first game of the pool → baseline only
            if (systemStartMs > 0 && g.endTimeMs < systemStartMs) continue // pre-adoption
            // Post-adoption, a game is either authorized or a violation
            // (fresh GREEN always authorizes, so no third case exists).
            val (_, authorized) = readinessContextAt(tests, g.endTimeMs)
            if (authorized) {
                authGames++; authDelta += delta; if (g.won) authWins++
            } else {
                violGames++; violDelta += delta; if (g.won) violWins++
            }
        }

        RatingPoolStats(
            label = ratingPoolLabel(key),
            key = key,
            ratedGames = sorted.size,
            currentRating = sorted.lastOrNull()?.ratingAfter,
            authorized = RatingCategoryStats(
                authGames, authDelta,
                if (authGames > 0) authDelta.toDouble() / authGames else 0.0,
                authWins
            ),
            violations = RatingCategoryStats(
                violGames, violDelta,
                if (violGames > 0) violDelta.toDouble() / violGames else 0.0,
                violWins
            )
        )
    }
    return result.sortedByDescending { it.ratedGames }
}

// ── Rating history (entire chess.com history) ───────────────────────────────

/** One point of a pool's rating-over-time series. */
data class RatingHistoryPoint(
    val endTimeMs: Long,
    val rating: Int,
    /**
     * Whether the game ending at this point was played inside a valid GREEN
     * authorization window. Drives the per-segment coloring of the
     * "rating since readiness" chart (green = authorized, red = violation).
     */
    val authorized: Boolean = true
)

/** A pool's full rating timeline across the user's entire history. */
data class RatingHistorySeries(
    val label: String,
    val key: String,
    /** Chronological (game end time, rating after game) points — rated games only. */
    val points: List<RatingHistoryPoint>,
    val startRating: Int,
    val endRating: Int,
    val peakRating: Int,
    val lowRating: Int
)

/**
 * Full rating history per pool (see [ratingPoolKey]: standard chess per
 * speed; each variant — e.g. Chess960 — is ONE pool because chess.com
 * gives variants a single rating) from every rated game in the log —
 * deliberately independent of readiness adoption, so the chart can show
 * the user's ENTIRE chess.com history with the adoption point (and later,
 * significant system-change points) marked on top.
 */
fun computeRatingHistory(games: List<ReadinessGameRecord>): List<RatingHistorySeries> =
    games
        .filter { it.rated && it.ratingAfter != null }
        .groupBy { ratingPoolKey(it.variant, it.type) }
        .map { (key, poolGames) ->
            val pts = poolGames
                .sortedBy { it.endTimeMs }
                .map { RatingHistoryPoint(it.endTimeMs, it.ratingAfter!!, it.authorized) }
            RatingHistorySeries(
                label = ratingPoolLabel(key),
                key = key,
                points = pts,
                startRating = pts.first().rating,
                endRating = pts.last().rating,
                peakRating = pts.maxOf { it.rating },
                lowRating = pts.minOf { it.rating }
            )
        }
        .sortedByDescending { it.points.size }
