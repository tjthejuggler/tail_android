package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import com.example.tail.data.ReadinessGameRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persistence for the Phase 2 v2 post-game audit — kept SEPARATE from
 * [ChessPhase2Store] so the v1 engine and its data are never touched:
 *
 *  - the PHASE 2 VERSION mirror ("v1"/"v2"), synced from DataStore by the
 *    settings view-model (same pattern as [ChessReadinessV2Store] for the
 *    pre-game engine) so the synchronous reconciler path can branch;
 *  - the per-time-control rolling personal baselines the tilt vector needs
 *    (accuracy and avg-seconds-per-move, last
 *    [ChessPhase2V2Engine.BASELINE_WINDOW] games each — report §7);
 *  - the recent rated-game ledger (result + verdict + minutes per game)
 *    feeding the loss-streak rule and the hysteresis rule.
 *
 * ACWR inputs are NOT stored here — they are computed on demand from the
 * canonical Chess Readiness activity log ([ChessReadinessLogStore]), which
 * already records every rated game with full fidelity.
 */
object ChessPhase2V2Store {

    private const val PREFS_NAME = "tail_chess_phase2_v2"
    private const val KEY_VERSION = "phase2_version"
    private const val KEY_VERSION_LOG = "version_log"
    private const val KEY_ACC_PREFIX = "acc_baseline_"
    private const val KEY_MOVE_PREFIX = "move_baseline_"
    private const val KEY_RECENT_GAMES = "recent_rated_games"

    /** Only the most recent ledger entries are kept. */
    private const val MAX_RECENT_GAMES = 300
    private const val MAX_VERSION_SWITCHES = 200

    /** Which post-game engine audits shared games. */
    const val VERSION_V1 = "v1"
    const val VERSION_V2 = "v2"
    const val VERSION_V3 = "v3"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Version mirror ─────────────────────────────────────────────────────

    /** "v1" (default — the original adaptive audit), "v2" or "v3" (hybrid). */
    fun phase2Version(context: Context): String =
        prefs(context).getString(KEY_VERSION, VERSION_V1) ?: VERSION_V1

    fun isV2(context: Context): Boolean = phase2Version(context) == VERSION_V2

    fun isV3(context: Context): Boolean = phase2Version(context) == VERSION_V3

    /**
     * Mirrored from DataStore by the settings view-model on every change.
     * Every ACTUAL version switch is appended to the version log so the
     * rating chart can mark which audit engine was active when.
     */
    fun savePhase2Version(context: Context, version: String) {
        val target = when (version) {
            VERSION_V2 -> VERSION_V2
            VERSION_V3 -> VERSION_V3
            else -> VERSION_V1
        }
        val previous = phase2Version(context)
        prefs(context).edit()
            .putString(KEY_VERSION, target)
            .apply()
        if (target != previous) {
            appendVersionSwitch(context, target)
        }
    }

    /** One recorded v1↔v2 engine switch (chart-marker telemetry). */
    data class VersionSwitchRecord(
        val timestampMs: Long,
        /** The version that became active ("v1"/"v2"). */
        val version: String
    )

    private fun appendVersionSwitch(context: Context, version: String) {
        val arr = prefs(context).getString(KEY_VERSION_LOG, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("version", version)
        })
        while (arr.length() > MAX_VERSION_SWITCHES) arr.remove(0)
        prefs(context).edit().putString(KEY_VERSION_LOG, arr.toString()).apply()
    }

    /** Engine switches, oldest first (rendered as markers on the rating chart). */
    fun loadVersionSwitches(context: Context): List<VersionSwitchRecord> {
        val raw = prefs(context).getString(KEY_VERSION_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                VersionSwitchRecord(
                    timestampMs = o.getLong("timestamp"),
                    version = o.optString("version", VERSION_V1)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Personal baselines (per time control) ──────────────────────────────

    /** One rated game as the ledger keeps it. */
    data class RatedGameRecord(
        /** Epoch millis at game end. */
        val timestamp: Long,
        /** [ChessPhase2Engine.GameResult] name. */
        val result: String,
        /** [ChessPhase2Engine.TimeControl] name. */
        val timeControl: String,
        /** [ChessPhase2Engine.OutputState] name of this game's audit. */
        val outputState: String,
        /** Base-clock minutes the game contributed to the session tally. */
        val estimatedMinutes: Double,
        /**
         * Elo expected score of that game (0–1) — drives v3's ΔE-weighted
         * loss streak. Null on rows recorded before v3 (weight falls back
         * to the normal 1.0 for losses).
         */
        val expectedScore: Double? = null,
        /** Strain this game contributed (v3 Rule 5; 0.0 on v1/v2 rows). */
        val strain: Double = 0.0
    )

    private fun loadWindow(context: Context, key: String): List<Double> {
        val raw = prefs(context).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getDouble(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveWindow(context: Context, key: String, window: List<Double>) {
        prefs(context).edit()
            .putString(key, JSONArray(window).toString())
            .apply()
    }

    /** Rolling accuracy history for [tc] (most recent last). */
    fun accuracyHistory(
        context: Context,
        tc: ChessPhase2Engine.TimeControl
    ): List<Double> = loadWindow(context, KEY_ACC_PREFIX + tc.name)

    /** Rolling avg-seconds-per-move history for [tc] (most recent last). */
    fun moveTimeHistory(
        context: Context,
        tc: ChessPhase2Engine.TimeControl
    ): List<Double> = loadWindow(context, KEY_MOVE_PREFIX + tc.name)

    /**
     * Appends one game's telemetry to the baselines. Only games with KNOWN
     * values count (no Game Review → no accuracy; no PGN clocks → no move
     * time), and short games skip the accuracy window (their accuracy is
     * noise — same convention as v1).
     */
    fun appendTelemetry(
        context: Context,
        tc: ChessPhase2Engine.TimeControl,
        accuracy: Double?,
        shortGame: Boolean,
        avgMoveSec: Double?
    ) {
        if (accuracy != null && !shortGame) {
            saveWindow(
                context, KEY_ACC_PREFIX + tc.name,
                (accuracyHistory(context, tc) + accuracy)
                    .takeLast(ChessPhase2V2Engine.BASELINE_WINDOW)
            )
        }
        if (avgMoveSec != null) {
            saveWindow(
                context, KEY_MOVE_PREFIX + tc.name,
                (moveTimeHistory(context, tc) + avgMoveSec)
                    .takeLast(ChessPhase2V2Engine.BASELINE_WINDOW)
            )
        }
    }

    // ── Recent rated-game ledger ───────────────────────────────────────────

    fun loadRecentGames(context: Context): List<RatedGameRecord> {
        val raw = prefs(context).getString(KEY_RECENT_GAMES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                RatedGameRecord(
                    timestamp = o.getLong("timestamp"),
                    result = o.optString("result", ""),
                    timeControl = o.optString("timeControl", ""),
                    outputState = o.optString("outputState", ""),
                    estimatedMinutes = o.optDouble("estimatedMinutes", 0.0),
                    expectedScore = if (o.has("expectedScore") && !o.isNull("expectedScore"))
                        o.optDouble("expectedScore") else null,
                    strain = o.optDouble("strain", 0.0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun appendRecentGame(context: Context, record: RatedGameRecord) {
        val history = (loadRecentGames(context) + record)
            .sortedBy { it.timestamp }
            .takeLast(MAX_RECENT_GAMES)
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("result", it.result)
                put("timeControl", it.timeControl)
                put("outputState", it.outputState)
                put("estimatedMinutes", it.estimatedMinutes)
                put("expectedScore", it.expectedScore ?: JSONObject.NULL)
                put("strain", it.strain)
            })
        }
        prefs(context).edit().putString(KEY_RECENT_GAMES, arr.toString()).apply()
    }

    /**
     * The ledger games belonging to the CURRENT session at [now]: everything
     * after the last TERMINATE verdict, chained by inter-game gaps no larger
     * than [ChessPhase2Store.SESSION_GAP_MS] (same derivation v1 uses).
     */
    fun currentSessionGames(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): List<RatedGameRecord> {
        val history = loadRecentGames(context)
        if (history.isEmpty()) return emptyList()
        val lastTerminateIdx = history.indexOfLast {
            it.outputState == ChessPhase2Engine.OutputState.TERMINATE_SESSION.name
        }
        val sinceTerminate = history.subList(
            (lastTerminateIdx + 1).coerceAtMost(history.size),
            history.size
        )
        val chain = mutableListOf<RatedGameRecord>()
        var prevTime = now
        for (game in sinceTerminate.asReversed()) {
            if (prevTime - game.timestamp <= ChessPhase2Store.SESSION_GAP_MS) {
                chain.add(game)
                prevTime = game.timestamp
            } else break
        }
        return chain.asReversed()
    }

    // ── ACWR inputs (from the canonical games log) ─────────────────────────

    /**
     * Rolling-Average ACWR inputs from the Chess Readiness activity log:
     * acute = rated games in the last [ChessPhase2V2Engine.ACWR_ACUTE_DAYS]
     * days ending at [now]; chronic = average weekly games over the last
     * [ChessPhase2V2Engine.ACWR_CHRONIC_DAYS] days. Only rated games in the
     * audited variants (standard chess / Chess960) count.
     */
    fun acwrInput(
        games: List<ReadinessGameRecord>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): ChessPhase2V2Engine.AcwrInput {
        val auditedVariants = setOf("chess", "chess960")
        val rated = games.filter { it.rated && it.variant.lowercase() in auditedVariants }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byDay = rated.groupBy {
            Instant.ofEpochMilli(it.endTimeMs).atZone(zone).toLocalDate()
        }
        val acute = (0 until ChessPhase2V2Engine.ACWR_ACUTE_DAYS)
            .sumOf { back -> byDay[today.minusDays(back.toLong())]?.size ?: 0 }
        val chronic = (0 until ChessPhase2V2Engine.ACWR_CHRONIC_DAYS)
            .sumOf { back -> byDay[today.minusDays(back.toLong())]?.size ?: 0 }
        return ChessPhase2V2Engine.AcwrInput(
            acuteGames = acute,
            chronicWeekly = chronic / (ChessPhase2V2Engine.ACWR_CHRONIC_DAYS / 7.0),
            distinctDays = byDay.size
        )
    }
}
