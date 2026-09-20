package com.example.tail.widget

import com.example.tail.data.ChessReadinessEngine
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the Chess Readiness feature.
 *
 * Stores, in plain [SharedPreferences] (not DataStore) so the bubble service
 * and the readiness activity can read/write synchronously:
 *  - the Phase 1 test history (for the 24 h cap + cool-down/rest rules)
 *  - the Puzzle Rush all-time high (readiness baseline, editable in Settings)
 *  - the IN-PROGRESS test session, so the user can solve each puzzle in the
 *    chess app and tap the widget again to resume exactly where they left off
 *    (with the puzzle timer anchored to when the step was shown).
 */

/** Where the user currently is inside the step-by-step readiness flow. */
enum class SessionStep { SLEEP, CLARITY, PUZZLE_GO, PUZZLE_RESULT, RUSH_GO, RUSH_RESULT }

/**
 * A partially-completed readiness test. Persisted as JSON after every step
 * so the flow survives the activity being dismissed between puzzles.
 */
data class ReadinessSession(
    /** Epoch millis when the whole test was started. */
    val startedAt: Long,
    /** Epoch millis of the last interaction (used for expiry). */
    val updatedAt: Long,
    val step: SessionStep,
    /** 0-based index of the rated puzzle the user is currently on. */
    val puzzleIndex: Int,
    /** Sleep score 0–100 from Garmin, or entered manually (null = not yet). */
    val sleepScore: Int?,
    val sleepFromGarmin: Boolean,
    /** Clarity slider answers, raw 1–10 (stress / focus / energy; empty = not yet answered). */
    val clarityScores: List<Int>,
    /** Effective solve times of the puzzles completed so far. */
    val puzzleTimesSec: List<Int>,
    /** Timer anchor: when the current "go do it" step was shown (0 = none). */
    val stepStartedAt: Long
)

object ChessReadinessStore {

    private const val PREFS_NAME = "tail_chess_readiness"
    private const val KEY_HISTORY = "test_history"
    private const val KEY_LAST_RUSH_ATH = "last_rush_all_time_high"
    private const val KEY_SESSION = "session_json"
    private const val KEY_PUZZLE_HABIT = "linked_puzzle_habit"
    private const val KEY_RUSH_HABIT = "linked_rush_habit"

    // Per-mode Puzzle Rush all-time highs (3- and 5-minute runs are
    // separate disciplines — a record in one says nothing about the
    // other). The legacy single [KEY_LAST_RUSH_ATH] value seeds the
    // 3-minute bucket on first read.
    private const val KEY_RUSH_ATH_3 = "rush_all_time_high_3min"
    private const val KEY_RUSH_ATH_5 = "rush_all_time_high_5min"

    // ── Chess Guard (hard enforcement) keys ──────────────────────────────
    // Enforcement state deliberately lives HERE (synchronous prefs), not in
    // DataStore: the ChessGuardService accessibility callback must never
    // touch DataStore flows. The chess package is mirrored from DataStore
    // by the settings view-model / trigger service whenever it changes.
    private const val KEY_ENFORCEMENT_ENABLED_AT = "enforcement_enabled_at"
    private const val KEY_PENALTIES = "violation_penalties"
    private const val KEY_CHESS_PACKAGE = "chess_package_mirror"

    /** Only the most recent tests are kept — enough for any 24 h check. */
    private const val MAX_HISTORY = 50

    /** Only the most recent penalties are kept (dedup window). */
    private const val MAX_PENALTIES = 50

    /** Prefs key for the "how many times the guard kicked you out" counter. */
    private const val KEY_GUARD_BLOCK_COUNT = "guard_block_count"

    /** An untouched session step expires after this long (ms). */
    const val STEP_TIMEOUT_MS = 10L * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Test history ───────────────────────────────────────────────────────

    fun loadHistory(context: Context): List<ChessReadinessEngine.ReadinessTest> {
        val raw = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ChessReadinessEngine.ReadinessTest(
                    timestamp = o.getLong("timestamp"),
                    ccrs = o.getInt("ccrs"),
                    state = o.optString("state", ""),
                    // Optional v3.1 telemetry — absent on legacy records.
                    clarityAverage = o.optNullableDouble("clarityAverage"),
                    puzzleAvgSec = o.optNullableInt("puzzleAvgSec"),
                    rushScore = o.optNullableInt("rushScore"),
                    pPuzzle = o.optNullableInt("pPuzzle"),
                    pRush = o.optNullableInt("pRush"),
                    // SPECIAL GREEN provenance — absent on legacy records.
                    specialGreen = o.optBoolean("specialGreen", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendTest(context: Context, test: ChessReadinessEngine.ReadinessTest) {
        val history = (loadHistory(context) + test)
            .sortedBy { it.timestamp }
            .takeLast(MAX_HISTORY)
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("ccrs", it.ccrs)
                put("state", it.state)
                it.clarityAverage?.let { v -> put("clarityAverage", v) }
                it.puzzleAvgSec?.let { v -> put("puzzleAvgSec", v) }
                it.rushScore?.let { v -> put("rushScore", v) }
                it.pPuzzle?.let { v -> put("pPuzzle", v) }
                it.pRush?.let { v -> put("pRush", v) }
                if (it.specialGreen) put("specialGreen", true)
            })
        }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
        // A new test can flip the enforcement verdict (new session, new
        // cool-down) — tell Chess Guard listeners.
        ChessGuardNotifier.notifyStateChange(context)
    }

    /** The most recent test, or null if none was ever recorded. */
    fun lastTest(context: Context): ChessReadinessEngine.ReadinessTest? =
        loadHistory(context).maxByOrNull { it.timestamp }

    // ── Puzzle Rush all-time high (readiness baseline) ─────────────────────

    fun lastRushAllTimeHigh(context: Context): Int =
        prefs(context).getInt(KEY_LAST_RUSH_ATH, 0)

    fun saveRushAllTimeHigh(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LAST_RUSH_ATH, value).apply()
    }

    /**
     * The 3- and 5-minute Puzzle Rush all-time highs, tracked SEPARATELY —
     * a record in one mode says nothing about the other. The legacy single
     * value (v1-test baseline, always a 3-minute run) seeds the 3-minute
     * bucket on first read so historical achievements carry over.
     */
    fun rushAllTimeHigh(context: Context, minutesMode: Int): Int {
        val p = prefs(context)
        return when (minutesMode) {
            5 -> p.getInt(KEY_RUSH_ATH_5, 0)
            else -> p.getInt(KEY_RUSH_ATH_3, p.getInt(KEY_LAST_RUSH_ATH, 0))
        }
    }

    fun saveRushAllTimeHigh(context: Context, minutesMode: Int, value: Int) {
        prefs(context).edit()
            .putInt(if (minutesMode == 5) KEY_RUSH_ATH_5 else KEY_RUSH_ATH_3, value)
            .apply()
    }

    /**
     * SPECIAL GREEN grant: a new all-time Puzzle Rush record (3- or
     * 5-minute mode) unlocks rated play exactly like a passed pre-game
     * readiness test. Implemented as a marked GREEN_LIGHT entry in the v1
     * test history — every gate consumer (Chess Guard evaluate(),
     * [ChessPhase2Store.ratedPlayExpiresAt], the deferred-game reconciler)
     * already keys off the latest GREEN_LIGHT entry, so the whole
     * 60-minute session / rolling-window machinery applies unchanged. The
     * entry carries the new record as its score and the [specialGreen]
     * provenance flag for the UI.
     *
     * @return the appended authorization entry.
     */
    fun grantSpecialGreen(
        context: Context,
        rushScore: Int,
        minutesMode: Int
    ): ChessReadinessEngine.ReadinessTest {
        val test = ChessReadinessEngine.ReadinessTest(
            timestamp = System.currentTimeMillis(),
            ccrs = rushScore.coerceIn(0, 100),
            state = ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name,
            rushScore = rushScore,
            specialGreen = true
        )
        appendTest(context, test)
        return test
    }

    // ── Linked habits (puzzle / rush credit) ───────────────────────────────

    /**
     * Habit credited the minutes each rated puzzle takes (plus +1 session)
     * during the Phase 1 test ("" = no habit linked). Chosen in Settings
     * from the full habit list.
     */
    fun linkedPuzzleHabit(context: Context): String =
        prefs(context).getString(KEY_PUZZLE_HABIT, "") ?: ""

    fun saveLinkedPuzzleHabit(context: Context, name: String) {
        prefs(context).edit().putString(KEY_PUZZLE_HABIT, name.trim()).apply()
    }

    /**
     * Habit credited the 3 rush minutes (plus +1 session) when the Puzzle
     * Rush run is reported during the Phase 1 test ("" = no habit linked).
     */
    fun linkedRushHabit(context: Context): String =
        prefs(context).getString(KEY_RUSH_HABIT, "") ?: ""

    fun saveLinkedRushHabit(context: Context, name: String) {
        prefs(context).edit().putString(KEY_RUSH_HABIT, name.trim()).apply()
    }

    // ── In-progress session (widget resume support) ────────────────────────

    /**
     * Loads the in-progress session, or null when there is none / it expired
     * ([STEP_TIMEOUT_MS] since the last interaction). Expired sessions are
     * discarded automatically.
     */
    fun loadSession(context: Context): ReadinessSession? {
        val raw = prefs(context).getString(KEY_SESSION, null) ?: return null
        val session = try {
            val o = JSONObject(raw)
            ReadinessSession(
                startedAt = o.getLong("startedAt"),
                updatedAt = o.getLong("updatedAt"),
                step = runCatching { SessionStep.valueOf(o.getString("step")) }
                    .getOrDefault(SessionStep.SLEEP),
                puzzleIndex = o.optInt("puzzleIndex", 0),
                sleepScore = if (o.has("sleepScore") && !o.isNull("sleepScore"))
                    o.getInt("sleepScore") else null,
                sleepFromGarmin = o.optBoolean("sleepFromGarmin", false),
                clarityScores = normalizeClarity(
                    o.optJSONArray("clarityScores")?.let { arr ->
                        (0 until arr.length()).map { arr.getInt(it) }
                    } ?: emptyList(),
                    // Sessions saved before the anchor flip stored stress
                    // inverted (1 = calm); the marker below is only written
                    // by the new convention.
                    legacyStressInverted = !o.optBoolean("clarityV2", false),
                    // Sessions saved before the 10-point survey stored 1–5
                    // sliders; the marker below is only written by the new
                    // convention.
                    legacyFivePoint = !o.optBoolean("clarityV3", false)
                ),
                puzzleTimesSec = o.optJSONArray("puzzleTimesSec")?.let { arr ->
                    (0 until arr.length()).map { arr.getInt(it) }
                } ?: emptyList(),
                stepStartedAt = o.optLong("stepStartedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
        if (session == null) return null
        return if (System.currentTimeMillis() - session.updatedAt > STEP_TIMEOUT_MS) {
            clearSession(context)
            null
        } else session
    }

    fun saveSession(context: Context, session: ReadinessSession) {
        val o = JSONObject().apply {
            put("startedAt", session.startedAt)
            put("updatedAt", System.currentTimeMillis())
            put("step", session.step.name)
            put("puzzleIndex", session.puzzleIndex)
            put("sleepScore", session.sleepScore ?: JSONObject.NULL)
            put("sleepFromGarmin", session.sleepFromGarmin)
            put("clarityScores", JSONArray(session.clarityScores))
            put("clarityV2", true)
            put("clarityV3", true)
            put("puzzleTimesSec", JSONArray(session.puzzleTimesSec))
            put("stepStartedAt", session.stepStartedAt)
        }
        prefs(context).edit().putString(KEY_SESSION, o.toString()).apply()
        // Step transitions change whether the chess app is allowed (the
        // puzzle/rush steps ARE the anti-deadlock pass).
        ChessGuardNotifier.notifyStateChange(context)
    }

    fun clearSession(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).apply()
        ChessGuardNotifier.notifyStateChange(context)
    }

    // ── Chess Guard: enforcement toggle & package mirror ─────────────────

    /**
     * Epoch millis when hard enforcement was switched ON, 0 = off. Games
     * that ended BEFORE this moment are never penalized (no retroactive
     * punishment for the pre-enforcement era).
     */
    fun enforcementEnabledAt(context: Context): Long =
        prefs(context).getLong(KEY_ENFORCEMENT_ENABLED_AT, 0L)

    /** Enables/disables hard enforcement (records the switch-on moment). */
    fun setEnforcementEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putLong(KEY_ENFORCEMENT_ENABLED_AT, if (enabled) System.currentTimeMillis() else 0L)
            .apply()
        ChessGuardNotifier.notifyStateChange(context)
    }

    // ── Green pass-rate target (user setting) ─────────────────────────────

    private const val KEY_GREEN_TARGET = "greenTargetPercent"
    private const val KEY_GREEN_TARGET_AT = "greenTargetChangedAt"

    /**
     * The user's target share of GREEN readiness outcomes, in whole
     * percent (5–95). Drives the adaptive Green bar via
     * [ChessReadinessEngine.greenPercentileFor]. Default 30%.
     */
    fun greenTargetPercent(context: Context): Int =
        prefs(context).getInt(KEY_GREEN_TARGET, (ChessReadinessEngine.DEFAULT_GREEN_TARGET * 100).toInt())

    /** Epoch ms the target was last changed (0 = never changed). */
    fun greenTargetChangedAt(context: Context): Long =
        prefs(context).getLong(KEY_GREEN_TARGET_AT, 0L)

    /**
     * Saves a new Green pass-rate target (whole percent, clamped to
     * 5–95) and stamps the change moment — the settings UI warns
     * against changing it too often, using this stamp plus the count
     * of tests and games logged since.
     */
    fun saveGreenTargetPercent(context: Context, percent: Int) {
        val p = percent.coerceIn(
            (ChessReadinessEngine.MIN_GREEN_TARGET * 100).toInt(),
            (ChessReadinessEngine.MAX_GREEN_TARGET * 100).toInt()
        )
        prefs(context).edit()
            .putInt(KEY_GREEN_TARGET, p)
            .putLong(KEY_GREEN_TARGET_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * The chess app package (mirror of the DataStore `chessReadinessApp`
     * setting, kept here for synchronous reads by the guard service).
     * Blank = no chess app configured.
     */
    fun chessPackage(context: Context): String =
        prefs(context).getString(KEY_CHESS_PACKAGE, "") ?: ""

    /** Updates the chess package mirror (idempotent, cheap). */
    fun saveChessPackage(context: Context, packageName: String) {
        prefs(context).edit().putString(KEY_CHESS_PACKAGE, packageName.trim()).apply()
    }

    // ── Chess Guard: violation penalties ─────────────────────────────────

    /** All persisted penalties, oldest first. */
    fun loadPenalties(context: Context): List<ChessEnforcementPolicy.Penalty> {
        val raw = prefs(context).getString(KEY_PENALTIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                ChessEnforcementPolicy.Penalty(
                    timestamp = o.getLong("timestamp"),
                    gameId = o.optString("gameId", ""),
                    expiresAt = o.getLong("expiresAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** True when a penalty was already recorded for this chess.com game. */
    fun hasPenaltyForGame(context: Context, gameId: String): Boolean =
        gameId.isNotBlank() &&
            loadPenalties(context).any { it.gameId == gameId }

    // ── Chess Guard: blocked-attempt counter ─────────────────────────────

    /** How many times the guard kicked the user out of the chess app. */
    fun guardBlockCount(context: Context): Int =
        prefs(context).getInt(KEY_GUARD_BLOCK_COUNT, 0)

    /** Increments the blocked-attempt counter (called by the guard service). */
    fun noteGuardBlock(context: Context) {
        prefs(context).edit()
            .putInt(KEY_GUARD_BLOCK_COUNT, guardBlockCount(context) + 1)
            .apply()
    }

    /** Appends a penalty (kept newest-last, capped at [MAX_PENALTIES]). */
    fun appendPenalty(context: Context, penalty: ChessEnforcementPolicy.Penalty) {
        val penalties = (loadPenalties(context) + penalty)
            .sortedBy { it.timestamp }
            .takeLast(MAX_PENALTIES)
        val arr = JSONArray()
        penalties.forEach {
            arr.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("gameId", it.gameId)
                put("expiresAt", it.expiresAt)
            })
        }
        prefs(context).edit().putString(KEY_PENALTIES, arr.toString()).apply()
        ChessGuardNotifier.notifyStateChange(context)
    }

    /**
     * Normalizes persisted clarity answers to the current 3-slider format
     * (stress / focus / energy, each 1–10, positive end = 10). Legacy
     * sessions stored four 0–10 values (focus / calm / energy / alertness)
     * — those are converted; 3-slider sessions written before the anchor
     * flip stored stress inverted (1 = calm) and are re-inverted via
     * [legacyStressInverted]; sessions written before the 10-point survey
     * (v3.1) stored 1–5 sliders and are doubled via
     * [ChessReadinessEngine.scaleSurveyTo10] when [legacyFivePoint];
     * anything else is dropped (the user simply re-answers the step).
     */
    private fun normalizeClarity(
        raw: List<Int>,
        legacyStressInverted: Boolean = false,
        legacyFivePoint: Boolean = false
    ): List<Int> {
        val base = when {
            raw.size == 3 && raw.all { it in 1..10 } ->
                // The pre-anchor-flip inversion only ever produced 1–5
                // values — never touch an already-10-point session.
                if (legacyStressInverted && raw.all { it in 1..5 })
                    listOf(6 - raw[0], raw[1], raw[2])
                else raw
            raw.size == 4 -> listOf(
                Math.round(raw[1] / 2.0).toInt().coerceIn(1, 5), // calm 0–10 → stress 1–5 (5 = calm)
                Math.round(raw[0] / 2.0).toInt().coerceIn(1, 5), // focus
                Math.round(raw[2] / 2.0).toInt().coerceIn(1, 5)  // energy
            )
            else -> emptyList()
        }
        if (base.isEmpty()) return base
        val scaled = if (legacyFivePoint)
            base.map { ChessReadinessEngine.scaleSurveyTo10(it) }
        else base
        return if (scaled.all { it in 1..10 }) scaled else emptyList()
    }
}

/** Nullable JSON readers for the optional v3.1 history telemetry. */
private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

private fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
