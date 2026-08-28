package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Persistence for the Chess Readiness V2 (neurobiological gate) — kept in
 * its OWN SharedPreferences file so the v1 store ([ChessReadinessStore])
 * remains byte-for-byte untouched.
 *
 * Holds:
 *  - the v1/v2 engine version MIRROR (the DataStore setting is async; the
 *    bubble service and overlay need a synchronous read on the window-manager
 *    path — same pattern as the chess-package mirror in v1)
 *  - the in-progress v2 wizard session (survives the overlay closing;
 *    the PVT itself always restarts — a vigilance test must be contiguous)
 *  - the v2 result log (tier, module metrics per completed evaluation)
 *  - the v2 PVT log (one entry per completed 3-minute PVT-B)
 *  - extra cognitive session loads (the v2 test itself feeds the ACWR)
 */
object ChessReadinessV2Store {

    private const val PREFS_NAME = "tail_chess_readiness_v2"

    private const val KEY_VERSION = "readiness_version"
    private const val KEY_VERSION_LOG = "version_log"
    private const val KEY_SESSION = "session_json"
    private const val KEY_RESULTS = "results"
    private const val KEY_PVT_LOG = "pvt_log"
    private const val KEY_SESSION_LOADS = "session_loads"

    /** Capped logs (results/PVT are telemetry, not enforcement state). */
    private const val MAX_RESULTS = 200
    private const val MAX_PVT = 200
    private const val MAX_LOAD_DAYS = 90
    private const val MAX_VERSION_SWITCHES = 200

    /** An untouched v2 session expires after this long (matches v1). */
    const val STEP_TIMEOUT_MS = 10L * 60 * 1000

    /** Which readiness engine the chess flow uses. */
    const val VERSION_V1 = "v1"
    const val VERSION_V2 = "v2"
    const val VERSION_V3 = "v3"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Version mirror ─────────────────────────────────────────────────────

    /** "v1" (default — the original system), "v2" or "v3". */
    fun readinessVersion(context: Context): String =
        prefs(context).getString(KEY_VERSION, VERSION_V1) ?: VERSION_V1

    fun isV2(context: Context): Boolean = readinessVersion(context) == VERSION_V2

    fun isV3(context: Context): Boolean = readinessVersion(context) == VERSION_V3

    /**
     * Mirrored from DataStore by the settings view-model on every change.
     * Every ACTUAL v1↔v2 switch is appended to the version log so the
     * rating chart can mark which engine was active when.
     */
    fun saveReadinessVersion(context: Context, version: String) {
        val target = when (version) {
            VERSION_V2 -> VERSION_V2
            VERSION_V3 -> VERSION_V3
            else -> VERSION_V1
        }
        val previous = readinessVersion(context)
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

    // ── Wizard session ─────────────────────────────────────────────────────

    /** Where the v2 wizard currently is. */
    enum class V2Step {
        /** Autonomic metrics computed, awaiting the user's continue. */
        OVERVIEW,
        /** Autonomic cleared — PVT-B due (not started or restarted). */
        PVT_PENDING,
        /** Finished (result recorded). */
        DONE
    }

    /**
     * In-progress v2 test. The PVT samples are NOT persisted — resuming a
     * half-finished vigilance test would invalidate it, so a PVT always
     * restarts from zero when the overlay reopens at [V2Step.PVT_PENDING].
     * (Sessions persisted by pre-priming-removal builds may carry a PRIMING
     * step or primingIndex — both are ignored on load.)
     */
    data class V2Session(
        val startedAt: Long,
        val updatedAt: Long,
        val step: V2Step,
        /** Serialized autonomic evaluation (JSON), kept between steps. */
        val autonomicJson: String?,
        /** Serialized gating result computed after the PVT (JSON). */
        val gatingJson: String?
    )

    fun loadSession(context: Context): V2Session? {
        val raw = prefs(context).getString(KEY_SESSION, null) ?: return null
        val session = try {
            val o = JSONObject(raw)
            V2Session(
                startedAt = o.getLong("startedAt"),
                updatedAt = o.getLong("updatedAt"),
                step = runCatching { V2Step.valueOf(o.getString("step")) }
                    .getOrDefault(V2Step.OVERVIEW),
                autonomicJson = o.optString("autonomic").takeIf { it.isNotBlank() },
                gatingJson = o.optString("gating").takeIf { it.isNotBlank() }
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

    fun saveSession(context: Context, session: V2Session) {
        val o = JSONObject().apply {
            put("startedAt", session.startedAt)
            put("updatedAt", System.currentTimeMillis())
            put("step", session.step.name)
            put("autonomic", session.autonomicJson ?: "")
            put("gating", session.gatingJson ?: "")
        }
        prefs(context).edit().putString(KEY_SESSION, o.toString()).apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).apply()
    }

    // ── Result log ─────────────────────────────────────────────────────────

    /** One completed v2 evaluation (telemetry for future stats screens). */
    data class V2ResultRecord(
        val timestamp: Long,
        val tier: String,
        val stateName: String,
        val ccrs: Int,
        val zLnRmssd: Double?,
        val zRhr: Double?,
        val lapses: Int,
        val falseStarts: Int,
        val meanRrt: Double?,
        val acwr: Double?,
        val pvtSkipped: Boolean,
        val sessionStartedAt: Long
    )

    fun appendResult(context: Context, record: V2ResultRecord) {
        val arr = prefs(context).getString(KEY_RESULTS, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("timestamp", record.timestamp)
            put("tier", record.tier)
            put("state", record.stateName)
            put("ccrs", record.ccrs)
            record.zLnRmssd?.let { v -> put("zLnRmssd", v) }
            record.zRhr?.let { v -> put("zRhr", v) }
            put("lapses", record.lapses)
            put("falseStarts", record.falseStarts)
            record.meanRrt?.let { v -> put("meanRrt", v) }
            record.acwr?.let { v -> put("acwr", v) }
            put("pvtSkipped", record.pvtSkipped)
            put("sessionStartedAt", record.sessionStartedAt)
        })
        while (arr.length() > MAX_RESULTS) arr.remove(0)
        prefs(context).edit().putString(KEY_RESULTS, arr.toString()).apply()
    }

    fun loadResults(context: Context): List<V2ResultRecord> {
        val raw = prefs(context).getString(KEY_RESULTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                V2ResultRecord(
                    timestamp = o.getLong("timestamp"),
                    tier = o.optString("tier", ""),
                    stateName = o.optString("state", ""),
                    ccrs = o.optInt("ccrs", 0),
                    zLnRmssd = o.optNullableDouble("zLnRmssd"),
                    zRhr = o.optNullableDouble("zRhr"),
                    lapses = o.optInt("lapses", 0),
                    falseStarts = o.optInt("falseStarts", 0),
                    meanRrt = o.optNullableDouble("meanRrt"),
                    acwr = o.optNullableDouble("acwr"),
                    pvtSkipped = o.optBoolean("pvtSkipped", false),
                    sessionStartedAt = o.optLong("sessionStartedAt", o.getLong("timestamp"))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── PVT log ────────────────────────────────────────────────────────────

    /** One completed 3-minute PVT-B run. */
    data class PvtRecord(
        val timestamp: Long,
        val validResponses: Int,
        val lapses: Int,
        val falseStarts: Int,
        val meanRrt: Double?,
        val meanRtMs: Double?,
        val maxRtMs: Int?
    )

    fun appendPvt(context: Context, record: PvtRecord) {
        val arr = prefs(context).getString(KEY_PVT_LOG, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("timestamp", record.timestamp)
            put("valid", record.validResponses)
            put("lapses", record.lapses)
            put("falseStarts", record.falseStarts)
            record.meanRrt?.let { v -> put("meanRrt", v) }
            record.meanRtMs?.let { v -> put("meanRtMs", v) }
            record.maxRtMs?.let { v -> put("maxRtMs", v) }
        })
        while (arr.length() > MAX_PVT) arr.remove(0)
        prefs(context).edit().putString(KEY_PVT_LOG, arr.toString()).apply()
    }

    /** The completed PVT-B runs, oldest first (telemetry for the stats screen). */
    fun loadPvt(context: Context): List<PvtRecord> {
        val raw = prefs(context).getString(KEY_PVT_LOG, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PvtRecord(
                    timestamp = o.getLong("timestamp"),
                    validResponses = o.optInt("valid", 0),
                    lapses = o.optInt("lapses", 0),
                    falseStarts = o.optInt("falseStarts", 0),
                    meanRrt = o.optNullableDouble("meanRrt"),
                    meanRtMs = o.optNullableDouble("meanRtMs"),
                    maxRtMs = if (o.has("maxRtMs") && !o.isNull("maxRtMs"))
                        o.optInt("maxRtMs") else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Extra cognitive session loads (feed the ACWR) ─────────────────────

    /**
     * Adds a cognitive load entry (date → cTRIMP units) for sessions the
     * games log can't see — each completed v2 test contributes
     * [ChessReadinessV2Engine.TEST_SESSION_MINUTES] ×
     * [ChessReadinessV2Engine.TEST_SESSION_INTENSITY].
     */
    fun addSessionLoad(context: Context, date: LocalDate, load: Double) {
        val root = prefs(context).getString(KEY_SESSION_LOADS, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: JSONObject()
        val existing = root.optDouble(date.toString(), 0.0)
        root.put(date.toString(), existing + load)
        // Trim oldest days beyond the cap.
        val days = root.keys().asSequence().toList().sorted()
        if (days.size > MAX_LOAD_DAYS) {
            for (d in days.take(days.size - MAX_LOAD_DAYS)) root.remove(d)
        }
        prefs(context).edit().putString(KEY_SESSION_LOADS, root.toString()).apply()
    }

    /** Date → accumulated extra load (v2 test sessions). */
    fun loadSessionLoads(context: Context): Map<LocalDate, Double> {
        val raw = prefs(context).getString(KEY_SESSION_LOADS, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val out = LinkedHashMap<LocalDate, Double>()
            for (key in root.keys()) {
                val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                val load = root.optDouble(key, 0.0)
                if (load > 0.0) out[day] = load
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** Nullable JSON double reader (absent keys → null). */
private fun JSONObject.optNullableDouble(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
