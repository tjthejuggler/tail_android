package com.example.tail.widget

import android.content.Context
import com.example.tail.data.ChessComGame
import com.example.tail.data.ReadinessBlockedRecord
import com.example.tail.data.ReadinessGameRecord
import com.example.tail.data.ReadinessTestRecord
import com.example.tail.data.gameDedupeKey
import com.example.tail.data.gameToRecord
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Readiness — detailed activity log (persistent telemetry)
 * ════════════════════════════════════════════════════════════════════════
 *
 * While [ChessReadinessStore] keeps only the capped 50-test summary needed
 * for rate limiting, THIS store is the permanent, detailed record of
 * everything the user does in the chess-readiness domain:
 *
 *  - every submitted Phase-1 test with its FULL telemetry (sub-scores,
 *    raw sleep/clarity inputs, puzzle times, rush score/strikes, session
 *    duration) — written by [ChessReadinessOverlay] at submission time
 *  - every chess.com game (polled via [com.example.tail.data.ChessComRepository])
 *    with the readiness context at the moment it ended (latest CCRS/state,
 *    and whether that game was played inside a valid GREEN authorization
 *    window) — deduped so re-fetching a month never double-logs
 *  - every blocked test attempt (rate limit / cool-down / rest lock)
 *
 * Storage: a single JSON file (`chess_readiness_log.json`) in the app's
 * internal storage, read-modify-written under a lock. The aggregation for
 * the stats screen lives in the pure
 * [com.example.tail.data.computeReadinessStats] calculator.
 */
object ChessReadinessLogStore {

    private const val FILE_NAME = "chess_readiness_log.json"
    private const val KEY_TESTS = "tests"
    private const val KEY_GAMES = "games"
    private const val KEY_BLOCKED = "blocked"
    private const val KEY_BACKFILL_USER = "backfillUser"
    private const val KEY_BACKFILL_AT = "backfillAt"

    /**
     * One-time 5→10 point survey migration marker: tests written before
     * v3.1 stored stress/focus/energy on the 1–5 scale; on first touch
     * after the upgrade every stored value is doubled (see
     * [ChessReadinessEngine.scaleSurveyTo10]) and this flag is set.
     */
    private const val KEY_SURVEY_SCALE10 = "surveyScale10"

    /**
     * Soft cap on stored events (oldest are trimmed first). Generous on
     * purpose: the one-time full-history backfill must be able to hold a
     * user's ENTIRE chess.com game history, not just recent months.
     */
    private const val MAX_EVENTS = 50000

    private val lock = Any()

    private fun file(context: Context): File =
        File(context.filesDir, FILE_NAME)

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Records a submitted readiness test with its full input telemetry.
     * Called right after [ChessReadinessEngine.evaluate] succeeds.
     */
    fun logTest(
        context: Context,
        result: ChessReadinessEngine.ReadinessResult,
        input: ChessReadinessEngine.ReadinessInput,
        sleepScore: Int,
        sleepFromGarmin: Boolean,
        stress: Int,
        focus: Int,
        energy: Int,
        sessionStartedAt: Long
    ) {
        val record = ReadinessTestRecord(
            timestamp = result.timestamp,
            ccrs = result.ccrs,
            state = result.state.name,
            sSleep = result.sSleep,
            sClarity = result.sClarity,
            pPuzzle = result.pPuzzle,
            pRush = result.pRush,
            sleepScore = sleepScore,
            sleepFromGarmin = sleepFromGarmin,
            stress = stress,
            focus = focus,
            energy = energy,
            puzzleTimesSec = input.puzzleTimesSec,
            rushScore = input.rushScore,
            rushStrikes = input.rushStrikes,
            rushAllTimeHigh = input.rushAllTimeHigh,
            sessionStartedAt = sessionStartedAt
        )
        synchronized(lock) {
            val root = readRoot(context)
            migrateSurveyScaleLocked(root)
            val arr = root.optJSONArray(KEY_TESTS) ?: JSONArray()
            arr.put(encodeTest(record))
            root.put(KEY_TESTS, arr)
            writeRoot(context, root)
        }
    }

    /**
     * Records chess.com games that are not already in the log (deduped by
     * [gameDedupeKey]), annotating each with the readiness context at the
     * moment it ended. Games the user didn't play, and unclassifiable
     * (daily/correspondence) games, are skipped.
     *
     * Every game that lands here as a NEW entry is also fed to
     * [ChessGuardPenalty.evaluateAndApply] — this is the single choke
     * point through which every game-detection path (share sheet,
     * deferred reconciler, monthly archive poll) flows, so an
     * unauthorized game can never slip past the 24-hour-penalty detector.
     */
    fun logGames(context: Context, games: List<ChessComGame>, username: String): Int {
        if (games.isEmpty() || username.isBlank()) return 0
        var added = 0
        val newEntries = ArrayList<Triple<String, Long, Boolean>>() // key, endMs, rated
        synchronized(lock) {
            val root = readRoot(context)
            val migrated = migrateSurveyScaleLocked(root)
            val seeded = seedLegacyTestsLocked(context, root)
            val gamesArr = root.optJSONArray(KEY_GAMES) ?: JSONArray()
            val keyIndex = HashMap<String, Int>(gamesArr.length())
            for (i in 0 until gamesArr.length()) {
                keyIndex[gamesArr.getJSONObject(i).optString("key")] = i
            }
            // Readiness context is resolved from the tests already in the log.
            val tests = loadTestsLocked(root)
            var upgraded = false
            for (game in games) {
                val isWhite = game.whiteUsername.equals(username, ignoreCase = true)
                val isBlack = game.blackUsername.equals(username, ignoreCase = true)
                if (!isWhite && !isBlack) continue
                val opponent = if (isWhite) game.blackUsername else game.whiteUsername
                val key = gameDedupeKey(game.endTime, opponent, game.timeControl)
                val record = gameToRecord(game, username, tests) ?: continue
                val existingIdx = keyIndex[key]
                if (existingIdx != null) {
                    // Upgrade entries logged before ratings/variant were
                    // captured, so rating stats cover the whole history.
                    val stored = gamesArr.getJSONObject(existingIdx)
                    if (!stored.has("ratingAfter") && record.ratingAfter != null) {
                        gamesArr.put(existingIdx, encodeGame(record, key))
                        upgraded = true
                    }
                    continue
                }
                gamesArr.put(encodeGame(record, key))
                keyIndex[key] = gamesArr.length() - 1
                added++
                newEntries.add(Triple(key, record.endTimeMs, record.rated))
            }
            root.put(KEY_GAMES, gamesArr)
            if (added > 0 || seeded || upgraded || migrated) writeRoot(context, root)
        }
        // Outside the file lock — the detector writes to its own prefs
        // store and must never break or block the logging path.
        for ((key, endMs, rated) in newEntries) {
            try {
                ChessGuardPenalty.evaluateAndApply(context, key, endMs, rated)
            } catch (_: Exception) {
                // Penalty detection is best-effort on top of logging.
            }
        }
        return added
    }

    /**
     * One-time import of the legacy capped test history kept by
     * [ChessReadinessStore] (it only persists what rate limiting needs)
     * into this detailed log. This gives historical games fetched from
     * chess.com an accurate readiness context and tells the compliance
     * chart when the system was adopted. Idempotent — tests already in
     * the log (matched by timestamp) are skipped.
     */
    fun ensureSeeded(context: Context) {
        synchronized(lock) {
            val root = readRoot(context)
            val migrated = migrateSurveyScaleLocked(root)
            val seeded = seedLegacyTestsLocked(context, root)
            if (seeded || migrated) writeRoot(context, root)
        }
    }

    /** Mutates [root] in place; true when any legacy test was imported. */
    private fun seedLegacyTestsLocked(context: Context, root: JSONObject): Boolean {
        val arr = root.optJSONArray(KEY_TESTS) ?: JSONArray()
        val known = HashSet<Long>(arr.length())
        for (i in 0 until arr.length()) {
            known.add(arr.getJSONObject(i).optLong("timestamp", -1))
        }
        var added = false
        for (t in ChessReadinessStore.loadHistory(context)) {
            if (t.timestamp in known) continue
            // Legacy records carry only timestamp/ccrs/state — raw input
            // telemetry wasn't persisted before this log existed.
            arr.put(encodeTest(
                ReadinessTestRecord(
                    timestamp = t.timestamp, ccrs = t.ccrs, state = t.state,
                    sSleep = 0, sClarity = 0, pPuzzle = 0, pRush = 0,
                    sleepScore = 0, sleepFromGarmin = false,
                    stress = 0, focus = 0, energy = 0,
                    puzzleTimesSec = emptyList(),
                    rushScore = 0, rushStrikes = 0, rushAllTimeHigh = 0,
                    sessionStartedAt = t.timestamp
                )
            ))
            known.add(t.timestamp)
            added = true
        }
        if (added) root.put(KEY_TESTS, arr)
        return added
    }

    /** Records a blocked test attempt (why the gate refused a re-test). */
    fun logBlockedAttempt(context: Context, reason: String) {
        val record = ReadinessBlockedRecord(
            timestamp = System.currentTimeMillis(),
            reason = reason.take(300)
        )
        synchronized(lock) {
            val root = readRoot(context)
            val arr = root.optJSONArray(KEY_BLOCKED) ?: JSONArray()
            arr.put(JSONObject().apply {
                put("timestamp", record.timestamp)
                put("reason", record.reason)
            })
            root.put(KEY_BLOCKED, arr)
            writeRoot(context, root)
        }
    }

    /** Deletes the entire readiness log. */
    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    /**
     * True when the user's ENTIRE chess.com history has already been
     * swept into the games log for [username] (case-insensitive —
     * chess.com usernames are). The marker lives in the log file itself,
     * so clearing the log also clears the backfill state.
     */
    fun isHistoryBackfilled(context: Context, username: String): Boolean =
        synchronized(lock) {
            val root = readRoot(context)
            root.optLong(KEY_BACKFILL_AT, 0L) > 0L &&
                root.optString(KEY_BACKFILL_USER).equals(username.trim(), ignoreCase = true)
        }

    /** Marks the full-history sweep complete for [username]. */
    fun markHistoryBackfilled(context: Context, username: String) {
        synchronized(lock) {
            val root = readRoot(context)
            root.put(KEY_BACKFILL_USER, username.trim())
            root.put(KEY_BACKFILL_AT, System.currentTimeMillis())
            writeRoot(context, root)
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    fun loadTests(context: Context): List<ReadinessTestRecord> =
        synchronized(lock) {
            val root = readRoot(context)
            if (migrateSurveyScaleLocked(root)) writeRoot(context, root)
            loadTestsLocked(root)
        }

    fun loadGames(context: Context): List<ReadinessGameRecord> =
        synchronized(lock) {
            val arr = readRoot(context).optJSONArray(KEY_GAMES) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                decodeGame(arr.getJSONObject(i))
            }
        }

    fun loadBlocked(context: Context): List<ReadinessBlockedRecord> =
        synchronized(lock) {
            val arr = readRoot(context).optJSONArray(KEY_BLOCKED) ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ReadinessBlockedRecord(
                    timestamp = o.getLong("timestamp"),
                    reason = o.optString("reason", "")
                )
            }
        }

    // ── JSON codec ──────────────────────────────────────────────────────────

    private fun readRoot(context: Context): JSONObject = try {
        val f = file(context)
        if (f.exists()) JSONObject(f.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject() // corrupt file → start fresh rather than crash logging
    }

    /**
     * Doubles every stored 1–5 survey value to the 10-point scale (idem-
     * potent via [KEY_SURVEY_SCALE10]). Values of 0 are the "no data"
     * sentinel of legacy seeded records and pass through untouched. True
     * when the root was mutated and needs persisting.
     */
    private fun migrateSurveyScaleLocked(root: JSONObject): Boolean {
        if (root.optBoolean(KEY_SURVEY_SCALE10, false)) return false
        val arr = root.optJSONArray(KEY_TESTS)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                for (key in listOf("stress", "focus", "energy")) {
                    o.put(key, ChessReadinessEngine.scaleSurveyTo10(o.optInt(key, 0)))
                }
            }
        }
        root.put(KEY_SURVEY_SCALE10, true)
        return true
    }

    /** Persists the root, trimming the oldest events past [MAX_EVENTS]. */
    private fun writeRoot(context: Context, root: JSONObject) {
        try {
            trimOldest(root, KEY_GAMES, MAX_EVENTS)
            trimOldest(root, KEY_TESTS, MAX_EVENTS)
            trimOldest(root, KEY_BLOCKED, MAX_EVENTS / 10)
            file(context).writeText(root.toString())
        } catch (_: Exception) {
            // Logging must never take down the readiness flow.
        }
    }

    private fun trimOldest(root: JSONObject, key: String, max: Int) {
        val arr = root.optJSONArray(key) ?: return
        if (arr.length() <= max) return
        val kept = JSONArray()
        for (i in arr.length() - max until arr.length()) kept.put(arr.get(i))
        root.put(key, kept)
    }

    private fun loadTestsLocked(root: JSONObject): List<ReadinessTestRecord> {
        val arr = root.optJSONArray(KEY_TESTS) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            decodeTest(arr.getJSONObject(i))
        }
    }

    private fun encodeTest(t: ReadinessTestRecord): JSONObject = JSONObject().apply {
        put("timestamp", t.timestamp)
        put("ccrs", t.ccrs)
        put("state", t.state)
        put("sSleep", t.sSleep)
        put("sClarity", t.sClarity)
        put("pPuzzle", t.pPuzzle)
        put("pRush", t.pRush)
        put("sleepScore", t.sleepScore)
        put("sleepFromGarmin", t.sleepFromGarmin)
        put("stress", t.stress)
        put("focus", t.focus)
        put("energy", t.energy)
        put("puzzleTimesSec", JSONArray(t.puzzleTimesSec))
        put("rushScore", t.rushScore)
        put("rushStrikes", t.rushStrikes)
        put("rushAllTimeHigh", t.rushAllTimeHigh)
        put("sessionStartedAt", t.sessionStartedAt)
    }

    private fun decodeTest(o: JSONObject): ReadinessTestRecord? = try {
        ReadinessTestRecord(
            timestamp = o.getLong("timestamp"),
            ccrs = o.getInt("ccrs"),
            state = o.optString("state", ""),
            sSleep = o.optInt("sSleep", 0),
            sClarity = o.optInt("sClarity", 0),
            pPuzzle = o.optInt("pPuzzle", 0),
            pRush = o.optInt("pRush", 0),
            sleepScore = o.optInt("sleepScore", 0),
            sleepFromGarmin = o.optBoolean("sleepFromGarmin", false),
            stress = o.optInt("stress", 6),
            focus = o.optInt("focus", 6),
            energy = o.optInt("energy", 6),
            puzzleTimesSec = o.optJSONArray("puzzleTimesSec")?.let { arr ->
                (0 until arr.length()).map { arr.getInt(it) }
            } ?: emptyList(),
            rushScore = o.optInt("rushScore", 0),
            rushStrikes = o.optInt("rushStrikes", 0),
            rushAllTimeHigh = o.optInt("rushAllTimeHigh", 0),
            sessionStartedAt = o.optLong("sessionStartedAt", o.getLong("timestamp"))
        )
    } catch (_: Exception) {
        null
    }

    private fun encodeGame(g: ReadinessGameRecord, key: String): JSONObject = JSONObject().apply {
        put("key", key)
        put("endTimeMs", g.endTimeMs)
        put("type", g.type)
        put("opponent", g.opponent)
        put("won", g.won)
        put("minutes", g.minutes)
        put("authorized", g.authorized)
        put("variant", g.variant)
        put("rated", g.rated)
        if (g.ratingAfter != null) put("ratingAfter", g.ratingAfter)
        if (g.ccrsAtPlay != null) put("ccrsAtPlay", g.ccrsAtPlay)
        if (g.stateAtPlay != null) put("stateAtPlay", g.stateAtPlay)
    }

    private fun decodeGame(o: JSONObject): ReadinessGameRecord? = try {
        ReadinessGameRecord(
            endTimeMs = o.getLong("endTimeMs"),
            type = o.optString("type", ""),
            opponent = o.optString("opponent", ""),
            won = o.optBoolean("won", false),
            minutes = o.optDouble("minutes", 0.0),
            ccrsAtPlay = if (o.has("ccrsAtPlay") && !o.isNull("ccrsAtPlay")) o.getInt("ccrsAtPlay") else null,
            stateAtPlay = if (o.has("stateAtPlay") && !o.isNull("stateAtPlay")) o.optString("stateAtPlay") else null,
            authorized = o.optBoolean("authorized", false),
            variant = o.optString("variant", "chess"),
            rated = o.optBoolean("rated", true),
            ratingAfter = if (o.has("ratingAfter") && !o.isNull("ratingAfter")) o.getInt("ratingAfter") else null
        )
    } catch (_: Exception) {
        null
    }
}
