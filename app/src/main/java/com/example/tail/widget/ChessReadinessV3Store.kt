package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the Chess Readiness V3 (reflex + puzzle rush survival
 * gate) — its OWN SharedPreferences file, mirroring the v2 store pattern.
 *
 * Holds:
 *  - the survival all-time PB (manual override OR Chess.com API sync of
 *    `puzzle_rush.best.score`; the API cache can lag up to 12 h, so the
 *    settings view offers both paths)
 *  - the linked "Puzzle Rush Survival" habit (habit association, exactly
 *    like the v1 Rated Puzzles / Puzzle Rush habit links)
 *  - the v3 result log (one entry per completed run)
 *  - the per-puzzle survival telemetry log (puzzle_index, duration_ms,
 *    timestamp, final_verdict — capped)
 */
object ChessReadinessV3Store {

    private const val PREFS_NAME = "tail_chess_readiness_v3"

    private const val KEY_SURVIVAL_PB = "survival_all_time_pb"
    private const val KEY_SURVIVAL_PB_SOURCE = "survival_pb_source"
    private const val KEY_SURVIVAL_PB_SYNCED_AT = "survival_pb_synced_at"
    private const val KEY_SURVIVAL_HABIT = "linked_survival_habit"
    private const val KEY_RESULTS = "results"
    private const val KEY_EVENTS = "survival_events"

    private const val MAX_RESULTS = 200
    private const val MAX_EVENTS = 1000

    /** An untouched v3 session expires after this long (matches v1/v2). */
    const val STEP_TIMEOUT_MS = 10L * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Survival all-time PB ───────────────────────────────────────────────

    /** Where the stored PB came from. */
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_CHESSCOM = "chesscom"

    /** The stored survival PB (0 = not configured; engine falls back). */
    fun survivalPb(context: Context): Int =
        prefs(context).getInt(KEY_SURVIVAL_PB, 0)

    /** "manual" or "chesscom" — how the current PB was last set. */
    fun survivalPbSource(context: Context): String =
        prefs(context).getString(KEY_SURVIVAL_PB_SOURCE, SOURCE_MANUAL) ?: SOURCE_MANUAL

    /** Epoch ms of the last successful Chess.com sync (0 = never). */
    fun survivalPbSyncedAt(context: Context): Long =
        prefs(context).getLong(KEY_SURVIVAL_PB_SYNCED_AT, 0L)

    /** Manual override from the settings field. */
    fun saveSurvivalPbManual(context: Context, pb: Int) {
        prefs(context).edit()
            .putInt(KEY_SURVIVAL_PB, pb.coerceAtLeast(0))
            .putString(KEY_SURVIVAL_PB_SOURCE, SOURCE_MANUAL)
            .apply()
    }

    /**
     * Chess.com API sync result. The API only ever RAISES the bar when the
     * current value also came from the API (a manual override wins until
     * the user clears it) — matches the "manual override" semantics of the
     * spec.
     */
    fun saveSurvivalPbFromChessCom(context: Context, pb: Int) {
        val p = prefs(context)
        val current = p.getInt(KEY_SURVIVAL_PB, 0)
        val currentSource = p.getString(KEY_SURVIVAL_PB_SOURCE, SOURCE_MANUAL)
        if (currentSource == SOURCE_MANUAL && current > 0 && pb <= current) {
            // Manual override stands; just stamp the sync time.
            p.edit().putLong(KEY_SURVIVAL_PB_SYNCED_AT, System.currentTimeMillis()).apply()
            return
        }
        p.edit()
            .putInt(KEY_SURVIVAL_PB, pb.coerceAtLeast(0))
            .putString(KEY_SURVIVAL_PB_SOURCE, SOURCE_CHESSCOM)
            .putLong(KEY_SURVIVAL_PB_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }

    // ── Linked survival habit ──────────────────────────────────────────────

    /** The habit associated with Puzzle Rush Survival runs ("" = none). */
    fun linkedSurvivalHabit(context: Context): String =
        prefs(context).getString(KEY_SURVIVAL_HABIT, "") ?: ""

    fun saveLinkedSurvivalHabit(context: Context, name: String) {
        prefs(context).edit().putString(KEY_SURVIVAL_HABIT, name.trim()).apply()
    }

    // ── Result log ─────────────────────────────────────────────────────────

    /** One completed v3 run (telemetry for the Chess Stats screen). */
    data class V3ResultRecord(
        val timestamp: Long,
        val sessionStartedAt: Long,
        val verdict: String,
        val stateName: String,
        val ccrs: Int,
        val target: Int,
        val puzzlesPassed: Int,
        val survivalDurationMs: Long,
        val reflexLapses: Int,
        val reflexFalseStarts: Int,
        val reflexMeanRtMs: Double?
    )

    fun appendResult(context: Context, record: V3ResultRecord) {
        val arr = prefs(context).getString(KEY_RESULTS, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("timestamp", record.timestamp)
            put("sessionStartedAt", record.sessionStartedAt)
            put("verdict", record.verdict)
            put("state", record.stateName)
            put("ccrs", record.ccrs)
            put("target", record.target)
            put("passed", record.puzzlesPassed)
            put("durationMs", record.survivalDurationMs)
            put("lapses", record.reflexLapses)
            put("falseStarts", record.reflexFalseStarts)
            record.reflexMeanRtMs?.let { put("meanRtMs", it) }
        })
        while (arr.length() > MAX_RESULTS) arr.remove(0)
        prefs(context).edit().putString(KEY_RESULTS, arr.toString()).apply()
    }

    fun loadResults(context: Context): List<V3ResultRecord> {
        val raw = prefs(context).getString(KEY_RESULTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                V3ResultRecord(
                    timestamp = o.getLong("timestamp"),
                    sessionStartedAt = o.optLong("sessionStartedAt", o.getLong("timestamp")),
                    verdict = o.optString("verdict", ""),
                    stateName = o.optString("state", ""),
                    ccrs = o.optInt("ccrs", 0),
                    target = o.optInt("target", 0),
                    puzzlesPassed = o.optInt("passed", 0),
                    survivalDurationMs = o.optLong("durationMs", 0L),
                    reflexLapses = o.optInt("lapses", 0),
                    reflexFalseStarts = o.optInt("falseStarts", 0),
                    reflexMeanRtMs = if (o.has("meanRtMs") && !o.isNull("meanRtMs"))
                        o.optDouble("meanRtMs") else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Pending (armed) survival session ────────────────────────────────────

    /**
     * A survival gate that passed the reflex step and is ARMED: the overlay
     * parked it and the floating bubble shows the START panel. Persisted so
     * the panel survives the bubble service being killed.
     */
    data class PendingSurvival(
        val sessionStartedAt: Long,
        val target: Int,
        val reflexLapses: Int,
        val reflexFalseStarts: Int,
        val reflexMeanRtMs: Double?
    )

    /** An armed-but-unstarted survival session expires after this long. */
    const val PENDING_TIMEOUT_MS = 15L * 60 * 1000

    fun savePendingSurvival(context: Context, pending: PendingSurvival) {
        prefs(context).edit().putString("pending_survival", JSONObject().apply {
            put("sessionStartedAt", pending.sessionStartedAt)
            put("target", pending.target)
            put("lapses", pending.reflexLapses)
            put("falseStarts", pending.reflexFalseStarts)
            if (pending.reflexMeanRtMs != null) put("meanRtMs", pending.reflexMeanRtMs)
            put("armedAt", System.currentTimeMillis())
        }.toString()).apply()
    }

    fun loadPendingSurvival(context: Context): PendingSurvival? {
        val raw = prefs(context).getString("pending_survival", null) ?: return null
        return try {
            val o = JSONObject(raw)
            if (System.currentTimeMillis() - o.getLong("armedAt") > PENDING_TIMEOUT_MS) {
                clearPendingSurvival(context)
                return null
            }
            PendingSurvival(
                sessionStartedAt = o.getLong("sessionStartedAt"),
                target = o.optInt("target", 0),
                reflexLapses = o.optInt("lapses", 0),
                reflexFalseStarts = o.optInt("falseStarts", 0),
                reflexMeanRtMs = if (o.has("meanRtMs") && !o.isNull("meanRtMs"))
                    o.optDouble("meanRtMs") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPendingSurvival(context: Context) {
        prefs(context).edit().remove("pending_survival").apply()
    }

    // ── Per-puzzle survival telemetry ──────────────────────────────────────

    /**
     * One PASS/FAIL event inside a survival run:
     * puzzle_index, puzzle_duration_ms, timestamp, final_verdict.
     */
    data class SurvivalEventRecord(
        val sessionId: Long,
        val puzzleIndex: Int,
        val puzzleDurationMs: Long,
        val timestamp: Long,
        val verdict: String
    )

    fun appendEvent(context: Context, record: SurvivalEventRecord) {
        val arr = prefs(context).getString(KEY_EVENTS, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("session", record.sessionId)
            put("index", record.puzzleIndex)
            put("durationMs", record.puzzleDurationMs)
            put("timestamp", record.timestamp)
            put("verdict", record.verdict)
        })
        while (arr.length() > MAX_EVENTS) arr.remove(0)
        prefs(context).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    fun loadEvents(context: Context): List<SurvivalEventRecord> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                SurvivalEventRecord(
                    sessionId = o.getLong("session"),
                    puzzleIndex = o.optInt("index", 0),
                    puzzleDurationMs = o.optLong("durationMs", 0L),
                    timestamp = o.getLong("timestamp"),
                    verdict = o.optString("verdict", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Records a finished v3 run into every store the shared enforcement system
 * reads: the SHARED v1 history (GREEN/RED + synthetic ccrs → Chess Guard,
 * colors, Phase-2 audits, rest ladder), the v3 telemetry log, and the
 * linked survival-habit credit. Used by both the overlay (reflex fails)
 * and the floating-bubble survival panel (gate runs).
 */
object ChessReadinessV3Recorder {
    fun record(
        context: Context,
        sessionStartedAt: Long,
        verdict: ChessReadinessV3Engine.Verdict,
        target: Int,
        puzzlesPassed: Int,
        survivalDurationMs: Long,
        reflex: ChessReadinessV3Engine.ReflexSummary?
    ) {
        val now = System.currentTimeMillis()
        val stateName = ChessReadinessV3Engine.stateNameFor(verdict)
        val ccrs = ChessReadinessV3Engine.syntheticCcrs(verdict)
        ChessReadinessStore.appendTest(
            context,
            ChessReadinessEngine.ReadinessTest(timestamp = now, ccrs = ccrs, state = stateName)
        )
        ChessReadinessV3Store.appendResult(
            context,
            ChessReadinessV3Store.V3ResultRecord(
                timestamp = now,
                sessionStartedAt = sessionStartedAt,
                verdict = verdict.name,
                stateName = stateName,
                ccrs = ccrs,
                target = target,
                puzzlesPassed = puzzlesPassed,
                survivalDurationMs = survivalDurationMs,
                reflexLapses = reflex?.lapses ?: 0,
                reflexFalseStarts = reflex?.falseStarts ?: 0,
                reflexMeanRtMs = reflex?.meanRtMs
            )
        )
        if (survivalDurationMs > 0) {
            ChessHabitCredit.grant(
                context,
                ChessReadinessV3Store.linkedSurvivalHabit(context),
                kotlin.math.round(survivalDurationMs / 60000.0).toInt().coerceAtLeast(1),
                1
            )
        }
    }
}
