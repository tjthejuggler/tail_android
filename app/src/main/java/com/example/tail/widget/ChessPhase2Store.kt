package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for the Phase 2 Post-Game Performance Audit.
 *
 * Stores, in plain [SharedPreferences] (not DataStore) so the bubble service
 * and the share activity can read/write synchronously:
 *  - the per-time-control rolling accuracy windows (last
 *    [ChessPhase2Engine.ROLLING_WINDOW] games each) used as the baseline for
 *    the calibrated accuracy-drop check.
 *  - the full audit history, from which the CURRENT session is derived:
 *    a session ends at a TERMINATE_SESSION output, or when the gap between
 *    consecutive audits exceeds [SESSION_GAP_MS] (the user clearly left the
 *    board and came back later).
 *
 * Audits are filed automatically when the user shares a finished chess.com
 * game link to Tail (see [com.example.tail.ChessGameShareActivity]); each
 * audit records the game's chess.com ID (so re-shares are detected) and its
 * estimated base-clock minutes (so the 60-minute capacity ceiling
 * accumulates automatically).
 */
object ChessPhase2Store {

    private const val PREFS_NAME = "tail_chess_phase2"
    private const val KEY_AUDITS = "audit_history"
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
        /** CAPS2 accuracy of the audited game (as reported by chess.com). */
        val caps2Accuracy: Double,
        /** False when the accuracy was bypassed (short game or no Game
         *  Review available) — such games are excluded from the rolling mean. */
        val accuracyCounted: Boolean,
        /** chess.com game ID ("" for audits filed before share ingestion). */
        val gameId: String = "",
        /** Estimated base-clock minutes the game contributed to the session. */
        val estimatedMinutes: Double = 0.0,
        /** Strain (0–100) this game contributed to the session (v2.0). */
        val strain: Double = 0.0
    )

    /**
     * Strain assigned to a PRE-v2.0 audit that has no stored strain:
     * derived from its verdict so old history still counts as evidence.
     */
    private fun legacyStrain(outputState: String): Double = when (outputState) {
        ChessPhase2Engine.OutputState.TERMINATE_SESSION.name ->
            ChessPhase2Engine.STRAIN_TERMINATE_BASE
        ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name ->
            ChessPhase2Engine.SEVERE_STRAIN
        else -> 0.0
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
                val state = o.optString("outputState", "")
                Phase2Audit(
                    timestamp = o.getLong("timestamp"),
                    timeControl = o.optString("timeControl", ""),
                    outputState = state,
                    deltaE = o.optDouble("deltaE", 0.0),
                    caps2Accuracy = o.optDouble("caps2Accuracy", 0.0),
                    accuracyCounted = o.optBoolean("accuracyCounted", true),
                    gameId = o.optString("gameId", ""),
                    estimatedMinutes = o.optDouble("estimatedMinutes", 0.0),
                    strain = if (o.has("strain")) o.getDouble("strain")
                    else legacyStrain(state)
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
                put("gameId", it.gameId)
                put("estimatedMinutes", it.estimatedMinutes)
                put("strain", it.strain)
            })
        }
        prefs(context).edit().putString(KEY_AUDITS, arr.toString()).apply()
        // The newest audit can flip the enforcement decision (a PIVOT or
        // TERMINATE verdict after the last test limits the session) —
        // keep the guard and external automation in sync.
        ChessGuardNotifier.notifyStateChange(context)
    }

    /** The most recent audit of a specific chess.com game, or null (re-share detection). */
    fun findAuditByGameId(context: Context, gameId: Long): Phase2Audit? =
        loadAudits(context).lastOrNull { it.gameId == gameId.toString() }

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

    // ── ΔE history (personal percentile floors) ────────────────────────────

    /**
     * The user's audited ΔE records (most recent last) — the input to
     * [ChessPhase2Engine.computeDeltaFloors].
     */
    fun recentDeltaE(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): List<ChessPhase2Engine.DeltaERecord> =
        loadAudits(context)
            .filter { it.timestamp <= now }
            .map { ChessPhase2Engine.DeltaERecord(it.timestamp, it.deltaE) }

    // ── Rated-play authorization (Phase 1 gate) ────────────────────────────

    /**
     * The CCRS of the Phase 1 test currently authorizing rated play, or null
     * when no GREEN authorization is inside its validity window. Feeds the
     * readiness buffer in [ChessPhase2Engine.evaluate].
     */
    fun authorizingReadinessCcrs(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Int? {
        val last = ChessReadinessStore.lastTest(context) ?: return null
        if (last.state != ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name) return null
        if (now - last.timestamp >= ChessReadinessEngine.SESSION_VALIDITY_MS) return null
        return last.ccrs
    }

    /**
     * True when the user may currently play/report RATED games, i.e. all of:
     *  - the last Phase 1 test resulted in GREEN_LIGHT (rated authorized),
     *  - that authorization is still inside its 60-minute validity window
     *    ([ChessReadinessEngine.SESSION_VALIDITY_MS]),
     *  - every Phase 2 audit filed since the authorization is CONTINUE_RATED
     *    (a Yellow/Red audit revokes rated play for the rest of the window).
     *
     * The floating bubble uses this to decide which SINGLE chess entry its
     * popup menu shows: authorized → "Chess Status"; otherwise →
     * "Chess Readiness". The share activity gates on it too.
     */
    fun ratedPlayAuthorized(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val authTimestamp = ChessReadinessStore.lastTest(context)
            ?.takeIf {
                it.state == ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name &&
                    now - it.timestamp < ChessReadinessEngine.SESSION_VALIDITY_MS
            }
            ?.timestamp ?: return false
        val auditsSinceAuth = loadAudits(context).filter { it.timestamp >= authTimestamp }
        return auditsSinceAuth.all {
            it.outputState == ChessPhase2Engine.OutputState.CONTINUE_RATED.name
        }
    }

    /**
     * Estimated base-clock minutes already audited in the CURRENT session —
     * the running tally the 60-minute capacity ceiling is checked against.
     */
    fun sessionMinutesUsed(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): Double =
        currentSessionAudits(context, now).sumOf { it.estimatedMinutes }
}
