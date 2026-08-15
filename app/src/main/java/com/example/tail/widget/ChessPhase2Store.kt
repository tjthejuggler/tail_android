package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the Phase 2 Post-Game Performance Audit.
 *
 * Stores, in plain [SharedPreferences] (not DataStore) so the bubble service
 * and the audit activity can read/write synchronously:
 *  - `last_selected_time_control` — UI memory requirement (spec §2): the
 *    Phase 2 modal's time control selector defaults to the last used tier.
 *  - the per-time-control rolling accuracy windows (last
 *    [ChessPhase2Engine.ROLLING_WINDOW] games each) used as the baseline for
 *    the calibrated accuracy-drop check.
 *  - the full audit history, from which the CURRENT session is derived:
 *    a session ends at a TERMINATE_SESSION output, or when the gap between
 *    consecutive audits exceeds [SESSION_GAP_MS] (the user clearly left the
 *    board and came back later).
 *  - the last entered "total elapsed playing time this session" so the form
 *    can be pre-filled and simply bumped up between games.
 */
object ChessPhase2Store {

    private const val PREFS_NAME = "tail_chess_phase2"
    private const val KEY_LAST_TC = "last_selected_time_control"
    private const val KEY_AUDITS = "audit_history"
    private const val KEY_LAST_SESSION_MINS = "last_session_elapsed_mins"
    private const val KEY_ACC_PREFIX = "acc_history_"

    /** Only the most recent audits are kept — plenty for session derivation. */
    private const val MAX_AUDITS = 200

    /**
     * Gap between consecutive audits beyond which a new session is considered
     * to have started (2 h — generous vs. the 60-min authorization window).
     */
    const val SESSION_GAP_MS = 2L * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** A recorded Phase 2 audit (persisted for session/streak evaluation). */
    data class Phase2Audit(
        /** Epoch millis at submission. */
        val timestamp: Long,
        /** [ChessPhase2Engine.TimeControl] name. */
        val timeControl: String,
        /** [ChessPhase2Engine.OutputState] name. */
        val outputState: String,
        /** Elo delta of the audited game. */
        val deltaE: Double,
        /** CAPS2 accuracy of the audited game (as entered). */
        val caps2Accuracy: Double,
        /** False when the accuracy was bypassed (short game) — such games
         *  are excluded from the rolling mean. */
        val accuracyCounted: Boolean
    )

    // ── Time control memory (spec §2 UI persistence) ───────────────────────

    fun lastTimeControl(context: Context): ChessPhase2Engine.TimeControl? =
        prefs(context).getString(KEY_LAST_TC, null)
            ?.let { ChessPhase2Engine.TimeControl.fromNameOrBlitz(it) }
            ?.takeIf { it.name == prefs(context).getString(KEY_LAST_TC, null) }

    fun saveTimeControl(context: Context, tc: ChessPhase2Engine.TimeControl) {
        prefs(context).edit().putString(KEY_LAST_TC, tc.name).apply()
    }

    // ── Rolling accuracy windows (per time control) ────────────────────────

    fun accuracyHistory(
        context: Context,
        tc: ChessPhase2Engine.TimeControl
    ): List<Double> {
        val raw = prefs(context).getString(KEY_ACC_PREFIX + tc.name, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getDouble(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Appends [accuracy] to the rolling window of [tc], keeping only the
     * last [ChessPhase2Engine.ROLLING_WINDOW] values. Short games (early
     * resignation) should NOT be appended — their accuracy is noise.
     */
    fun appendAccuracy(
        context: Context,
        tc: ChessPhase2Engine.TimeControl,
        accuracy: Double
    ) {
        val window = (accuracyHistory(context, tc) + accuracy)
            .takeLast(ChessPhase2Engine.ROLLING_WINDOW)
        prefs(context).edit()
            .putString(KEY_ACC_PREFIX + tc.name, JSONArray(window).toString())
            .apply()
    }

    // ── Audit history ──────────────────────────────────────────────────────

    fun loadAudits(context: Context): List<Phase2Audit> {
        val raw = prefs(context).getString(KEY_AUDITS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                Phase2Audit(
                    timestamp = o.getLong("timestamp"),
                    timeControl = o.optString("timeControl", ""),
                    outputState = o.optString("outputState", ""),
                    deltaE = o.optDouble("deltaE", 0.0),
                    caps2Accuracy = o.optDouble("caps2Accuracy", 0.0),
                    accuracyCounted = o.optBoolean("accuracyCounted", true)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun appendAudit(context: Context, audit: Phase2Audit) {
        val history = (loadAudits(context) + audit)
            .sortedBy { it.timestamp }
            .takeLast(MAX_AUDITS)
        val arr = JSONArray()
        history.forEach {
            arr.put(JSONObject().apply {
                put("timestamp", it.timestamp)
                put("timeControl", it.timeControl)
                put("outputState", it.outputState)
                put("deltaE", it.deltaE)
                put("caps2Accuracy", it.caps2Accuracy)
                put("accuracyCounted", it.accuracyCounted)
            })
        }
        prefs(context).edit().putString(KEY_AUDITS, arr.toString()).apply()
    }

    // ── Session derivation ─────────────────────────────────────────────────

    /**
     * The audits belonging to the CURRENT session: everything after the last
     * TERMINATE_SESSION output, trimmed to the contiguous chain of audits
     * whose inter-audit gaps do not exceed [SESSION_GAP_MS]. Returns an empty
     * list when the last audit is older than the gap (fresh session).
     */
    fun currentSessionAudits(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): List<Phase2Audit> {
        val history = loadAudits(context)
        if (history.isEmpty()) return emptyList()

        val lastTerminateIdx = history.indexOfLast {
            it.outputState == ChessPhase2Engine.OutputState.TERMINATE_SESSION.name
        }
        val sinceTerminate = history.subList(
            (lastTerminateIdx + 1).coerceAtMost(history.size),
            history.size
        )

        // Walk backwards from "now", keeping audits chained within the gap.
        val chain = mutableListOf<Phase2Audit>()
        var prevTime = now
        for (audit in sinceTerminate.asReversed()) {
            if (prevTime - audit.timestamp <= SESSION_GAP_MS) {
                chain.add(audit)
                prevTime = audit.timestamp
            } else break
        }
        return chain.asReversed()
    }

    // ── Rated-play authorization (Phase 1 gate) ────────────────────────────

    /**
     * True when the user may currently play/report RATED games, i.e. all of:
     *  - the last Phase 1 test resulted in GREEN_LIGHT (rated authorized),
     *  - that authorization is still inside its 60-minute validity window
     *    ([ChessReadinessEngine.SESSION_VALIDITY_MS]),
     *  - every Phase 2 audit filed since the authorization is CONTINUE_RATED
     *    (a Yellow/Red audit revokes rated play for the rest of the window).
     *
     * The floating bubble uses this to decide whether its popup menu shows
     * the "Report rated game" (Phase 2) entry.
     */
    fun ratedPlayAuthorized(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val last = ChessReadinessStore.lastTest(context) ?: return false
        if (last.state != ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name) return false
        if (now - last.timestamp >= ChessReadinessEngine.SESSION_VALIDITY_MS) return false
        val auditsSinceAuth = loadAudits(context).filter { it.timestamp >= last.timestamp }
        return auditsSinceAuth.all {
            it.outputState == ChessPhase2Engine.OutputState.CONTINUE_RATED.name
        }
    }

    // ── Session minutes pre-fill ───────────────────────────────────────────

    fun lastSessionMins(context: Context): Int =
        prefs(context).getInt(KEY_LAST_SESSION_MINS, 0)

    fun saveLastSessionMins(context: Context, mins: Int) {
        prefs(context).edit().putInt(KEY_LAST_SESSION_MINS, mins.coerceAtLeast(0)).apply()
    }
}
