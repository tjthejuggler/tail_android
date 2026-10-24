package com.example.tail.widget

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for Chess Readiness V4 (Green Light) — its OWN
 * SharedPreferences file, mirroring the v2/v3 store pattern.
 *
 * Holds:
 *  - the CALIBRATION EVENT LOG: every assessment, gate outcome and override
 *    appended as JSON events so the gates themselves can be validated later
 *    (spec §3 feedback loop; the phone twin of TailCue's `readiness_events`)
 *  - the reflex pass threshold (starts at the spec default 320 ms; exposed
 *    for later personalisation from recorded outcomes)
 *  - the pending assessment context (the signals behind the CURRENT armed
 *    survival gate, so the gate's outcome event can cite them)
 */
object ChessReadinessV4Store {

    private const val PREFS_NAME = "tail_chess_readiness_v4"
    private const val KEY_EVENTS = "events"
    private const val KEY_REFLEX_PASS_MS = "reflex_pass_ms"
    private const val KEY_PENDING_ASSESSMENT = "pending_assessment"

    /** Event-log cap (telemetry, same scale as TailCue's 2000). */
    private const val MAX_EVENTS = 2000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Reflex threshold ───────────────────────────────────────────────────

    /** Current reflex-gate pass threshold (ms). Default: spec's 320. */
    fun reflexPassMs(context: Context): Int =
        prefs(context).getInt(KEY_REFLEX_PASS_MS, ChessReadinessV4Engine.DEFAULT_REFLEX_PASS_MS)

    fun saveReflexPassMs(context: Context, ms: Int) {
        prefs(context).edit()
            .putInt(KEY_REFLEX_PASS_MS, ms.coerceIn(150, 800))
            .apply()
    }

    // ── Pending assessment context (armed survival gate) ───────────────────

    /** Signals/concern behind the currently-armed v4 survival gate (if any). */
    data class PendingV4(
        val concern: Int,
        val signalsJson: String,
        val gatePuzzles: Int,
        val armedAtMs: Long
    )

    fun savePending(context: Context, pending: PendingV4) {
        prefs(context).edit()
            .putString(
                KEY_PENDING_ASSESSMENT,
                JSONObject().apply {
                    put("concern", pending.concern)
                    put("signals", pending.signalsJson)
                    put("puzzles", pending.gatePuzzles)
                    put("armedAt", pending.armedAtMs)
                }.toString()
            )
            .apply()
    }

    fun loadPending(context: Context): PendingV4? {
        val raw = prefs(context).getString(KEY_PENDING_ASSESSMENT, null) ?: return null
        return try {
            val o = JSONObject(raw)
            PendingV4(
                concern = o.optInt("concern", 0),
                signalsJson = o.optString("signals", "[]"),
                gatePuzzles = o.optInt("puzzles", 0),
                armedAtMs = o.optLong("armedAt", 0L)
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_ASSESSMENT).apply()
    }

    // ── Calibration event log ──────────────────────────────────────────────

    /**
     * One feedback-loop event (assessment / gate outcome / override), the
     * phone twin of TailCue's POST /api/readiness/event body.
     */
    data class V4Event(
        val timestampMs: Long,
        /** "GREEN" | "YELLOW" | "RED" — the assessed level. */
        val level: String,
        val concern: Int,
        /** "" for instant green; "reflex" | "survival" when a gate ran. */
        val gateType: String,
        /** null when no gate ran / it was abandoned. */
        val gatePassed: Boolean?,
        /** Median tap ms of a completed reflex gate (null otherwise). */
        val reflexMs: Double?,
        /** True when the person overrode a RED advisory ("play anyway"). */
        val overridden: Boolean,
        /** True when a GREEN authorization was actually granted. */
        val greenGranted: Boolean,
        /** JSON array of the signal ids that drove the assessment. */
        val signalsJson: String,
        val category: String
    )

    fun appendEvent(context: Context, event: V4Event) {
        val arr = prefs(context).getString(KEY_EVENTS, null)?.let {
            runCatching { JSONArray(it) }.getOrNull()
        } ?: JSONArray()
        arr.put(JSONObject().apply {
            put("ts", event.timestampMs)
            put("level", event.level)
            put("concern", event.concern)
            put("gateType", event.gateType)
            if (event.gatePassed != null) put("gatePassed", event.gatePassed)
            if (event.reflexMs != null) put("reflexMs", event.reflexMs)
            put("overridden", event.overridden)
            put("greenGranted", event.greenGranted)
            put("signals", event.signalsJson)
            put("category", event.category)
        })
        while (arr.length() > MAX_EVENTS) arr.remove(0)
        prefs(context).edit().putString(KEY_EVENTS, arr.toString()).apply()
    }

    /** All events, oldest first (drives the stats screen + calibration). */
    fun loadEvents(context: Context): List<V4Event> {
        val raw = prefs(context).getString(KEY_EVENTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                V4Event(
                    timestampMs = o.getLong("ts"),
                    level = o.optString("level", ""),
                    concern = o.optInt("concern", 0),
                    gateType = o.optString("gateType", ""),
                    gatePassed = if (o.has("gatePassed") && !o.isNull("gatePassed"))
                        o.optBoolean("gatePassed") else null,
                    reflexMs = if (o.has("reflexMs") && !o.isNull("reflexMs"))
                        o.optDouble("reflexMs") else null,
                    overridden = o.optBoolean("overridden", false),
                    greenGranted = o.optBoolean("greenGranted", false),
                    signalsJson = o.optString("signals", "[]"),
                    category = o.optString("category", "Blitz")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Records finished v4 outcomes into every store the shared enforcement
 * system reads — the SHARED v1 history (GREEN/RED entry → Chess Guard,
 * colours, Phase-2 audits), the v4 calibration event log — mirroring
 * [ChessReadinessV3Recorder].
 */
object ChessReadinessV4Recorder {

    /**
     * Records a terminal v4 outcome.
     *
     * @param assessment the assessment this outcome belongs to
     * @param gateType   "" (instant green), "reflex" or "survival"
     * @param gatePassed null when no gate ran; otherwise its verdict
     * @param reflexMs   median tap ms of a completed reflex gate
     * @param overridden true when a RED advisory was overridden
     */
    fun record(
        context: android.content.Context,
        assessment: ChessReadinessV4Engine.Assessment,
        gateType: String,
        gatePassed: Boolean?,
        reflexMs: Double?,
        overridden: Boolean
    ) {
        val now = System.currentTimeMillis()
        // A granted authorization: instant green, a passed gate, or an
        // override (RED never hard-blocks — the override is logged and play
        // is authorized like any GREEN).
        val green = gateType.isEmpty() || gatePassed == true || overridden
        if (green) {
            ChessReadinessStore.appendTest(
                context,
                com.example.tail.data.chess.ChessReadinessEngine.ReadinessTest(
                    timestamp = now,
                    ccrs = ChessReadinessV4Engine.syntheticCcrs(passed = true),
                    state = ChessReadinessV4Engine.stateNameFor(passed = true),
                    // Performance-only stop model (user rule 2026-10-24):
                    // this authorization lasts 12 h and idle never closes
                    // it — only a bad post-game audit revokes.
                    v4 = true
                )
            )
        } else if (gatePassed == false) {
            // A failed gate is a real RED entry (60-min rest before re-test).
            ChessReadinessStore.appendTest(
                context,
                com.example.tail.data.chess.ChessReadinessEngine.ReadinessTest(
                    timestamp = now,
                    ccrs = ChessReadinessV4Engine.syntheticCcrs(passed = false),
                    state = ChessReadinessV4Engine.stateNameFor(passed = false),
                    v4 = true
                )
            )
        }
        val signalsJson = JSONArray().apply {
            assessment.signals.forEach { put(it.id) }
        }.toString()
        ChessReadinessV4Store.appendEvent(
            context,
            ChessReadinessV4Store.V4Event(
                timestampMs = now,
                level = assessment.level.name,
                concern = assessment.concern,
                gateType = gateType,
                gatePassed = gatePassed,
                reflexMs = reflexMs,
                overridden = overridden,
                greenGranted = green,
                signalsJson = signalsJson,
                category = assessment.category
            )
        )
    }

    /**
     * Survival-gate completion, reported by the floating bubble's gate panel
     * when the armed pending run was a v4 gate. The shared v1 history entry
     * was ALREADY written by [ChessReadinessV3Recorder.record] — this only
     * files the v4 calibration event.
     */
    fun recordSurvivalOutcome(
        context: android.content.Context,
        concern: Int,
        signalsJson: String,
        category: String,
        passed: Boolean
    ) {
        ChessReadinessV4Store.appendEvent(
            context,
            ChessReadinessV4Store.V4Event(
                timestampMs = System.currentTimeMillis(),
                level = if (passed) "GREEN" else "RED",
                concern = concern,
                gateType = "survival",
                gatePassed = passed,
                reflexMs = null,
                overridden = false,
                greenGranted = passed,
                signalsJson = signalsJson,
                category = category
            )
        )
    }
}
