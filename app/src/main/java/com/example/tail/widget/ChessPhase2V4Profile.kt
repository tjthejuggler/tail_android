package com.example.tail.widget

import android.content.Context
import android.util.Log
import com.example.tail.data.BridgeClient
import org.json.JSONObject

/**
 * Phase 2 v4 — the PERSONAL READINESS PROFILE.
 *
 * A JSON document built on the desktop by chess-coach's
 * `build_v4_profile.py` from the user's 6,500+ analyzed games (recency
 * weighted, half-life 150 days) and served by the tail bridge at
 * `chess_analysis/v4_profile`. It replaces the v3 engine's hand-picked
 * constants with data-derived thresholds.
 *
 * FALLBACK CONTRACT (mirrors [ChessAnalysisFetcher]): when no profile can
 * be fetched or parsed, [fallback] returns a profile whose values equal
 * the v3 constants — v4 then behaves exactly like v3 and never blocks the
 * audit on the desktop being unreachable.
 */
object ChessPhase2V4Profile {

    private const val TAG = "ChessPhase2V4Profile"
    private const val PREFS = "chess_phase2_v4_profile"
    private const val KEY_JSON = "profile_json"

    /** Per-time-control personal baselines (weighted stats of history). */
    data class Baseline(
        val acplMean: Double,
        val acplSd: Double,
        val blunderMean: Double,
        val blunderSd: Double,
        /** Personal blunder allowance (weighted p75 of history); null = none. */
        val blunderCap: Int?,
        val mistakeMean: Double,
        val mistakeSd: Double,
        val inaccuracyMean: Double,
        val inaccuracySd: Double,
        val avgGameMinutes: Double
    )

    /** Fatigue bars for one time control; null minute = not derivable. */
    data class Fatigue(
        val yellowMin: Int?,
        val redMin: Int?,
        val fallbackYellow: Int,
        val fallbackRed: Int
    ) {
        val yellow: Int get() = yellowMin ?: fallbackYellow
        val red: Int get() = redMin ?: fallbackRed
    }

    data class CurvePoint(val expected: Double, val weight: Double)

    data class StreakThresholds(
        val yellowWeight: Double,
        val redWeight: Double,
        val derived: Boolean
    )

    data class Rest(val restMinutes: Int, val derived: Boolean)

    data class CircadianHour(val hour: Int, val offsetZ: Double)

    /** The full parsed profile. */
    data class Profile(
        val version: Int,
        val generatedAt: String,
        val gamesAnalyzed: Int,
        val sessionGapMin: Double,
        val pipelineLatencyMin: Int,
        val baselines: Map<String, Baseline>,
        val fatigue: Map<String, Fatigue>,
        val lossWeightCurve: List<CurvePoint>,
        val lossCurveDerived: Boolean,
        val streak: StreakThresholds,
        val rest: Rest,
        val circadian: List<CircadianHour>
    ) {
        /** True when this profile came from real historical data. */
        val isReal: Boolean get() = gamesAnalyzed > 0

        fun fatigueFor(tc: ChessPhase2Engine.TimeControl): Fatigue =
            fatigue[tc.name.lowercase()] ?: Fatigue(null, null, 90, 120)

        /** Interpolated loss weight for an expected score (v3 bands when
         *  the curve is the fallback). */
        fun lossWeight(expectedScore: Double): Double {
            if (lossWeightCurve.isEmpty()) {
                return ChessPhase2V3Engine.lossWeight(expectedScore)
            }
            val pts = lossWeightCurve.sortedBy { it.expected }
            if (expectedScore <= pts.first().expected) return pts.first().weight
            if (expectedScore >= pts.last().expected) return pts.last().weight
            for (i in 0 until pts.size - 1) {
                val a = pts[i]; val b = pts[i + 1]
                if (expectedScore in a.expected..b.expected) {
                    val t = (expectedScore - a.expected) /
                        (b.expected - a.expected).coerceAtLeast(1e-9)
                    return a.weight + t * (b.weight - a.weight)
                }
            }
            return 1.0
        }

        /** Personal circadian offset (in accuracy-Z units) at [hour];
         *  0.0 when the profile has no curve. Positive = you historically
         *  play WORSE at this hour. */
        fun circadianOffsetZ(hour: Int): Double =
            circadian.firstOrNull { it.hour == hour }?.offsetZ ?: 0.0
    }

    /** v3-identical profile used when the desktop profile is unavailable. */
    fun fallback(): Profile = Profile(
        version = 0,
        generatedAt = "fallback",
        gamesAnalyzed = 0,
        sessionGapMin = 45.0,
        pipelineLatencyMin = 5,
        baselines = emptyMap(),
        fatigue = mapOf(
            "bullet" to Fatigue(null, null, 90, 120),
            "blitz" to Fatigue(null, null, 90, 120),
            "rapid" to Fatigue(null, null, 90, 120)
        ),
        lossWeightCurve = emptyList(),
        lossCurveDerived = false,
        streak = StreakThresholds(
            ChessPhase2V3Engine.STREAK_YELLOW_WEIGHT,
            ChessPhase2V3Engine.STREAK_RED_WEIGHT,
            derived = false
        ),
        rest = Rest(30, derived = false),
        circadian = emptyList()
    )

    // ── Parsing ────────────────────────────────────────────────────────

    fun parse(json: JSONObject): Profile? = try {
        val baselines = mutableMapOf<String, Baseline>()
        json.optJSONObject("baselines")?.let { obj ->
            for (key in obj.keys()) {
                val b = obj.optJSONObject(key) ?: continue
                baselines[key] = Baseline(
                    acplMean = b.optDouble("acplMean", 0.0),
                    acplSd = b.optDouble("acplSd", 1.0).coerceAtLeast(1.0),
                    blunderMean = b.optDouble("blunderMean", 0.0),
                    blunderSd = b.optDouble("blunderSd", 0.2).coerceAtLeast(0.2),
                    blunderCap = if (b.isNull("blunderCap")) null
                        else b.optInt("blunderCap"),
                    mistakeMean = b.optDouble("mistakeMean", 0.0),
                    mistakeSd = b.optDouble("mistakeSd", 0.2).coerceAtLeast(0.2),
                    inaccuracyMean = b.optDouble("inaccuracyMean", 0.0),
                    inaccuracySd = b.optDouble("inaccuracySd", 0.2)
                        .coerceAtLeast(0.2),
                    avgGameMinutes = b.optDouble("avgGameMinutes", 5.0)
                )
            }
        }
        val fatigue = mutableMapOf<String, Fatigue>()
        json.optJSONObject("fatigue")?.let { obj ->
            for (key in obj.keys()) {
                val f = obj.optJSONObject(key) ?: continue
                fatigue[key] = Fatigue(
                    yellowMin = if (f.isNull("yellowMin")) null
                        else f.optInt("yellowMin"),
                    redMin = if (f.isNull("redMin")) null else f.optInt("redMin"),
                    fallbackYellow = f.optInt("fallbackYellow", 90),
                    fallbackRed = f.optInt("fallbackRed", 120)
                )
            }
        }
        val curveObj = json.optJSONObject("lossWeightCurve")
        val curve = mutableListOf<CurvePoint>()
        var curveDerived = false
        if (curveObj != null) {
            curveDerived = curveObj.optBoolean("derived", false)
            val arr = curveObj.optJSONArray("points") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                curve += CurvePoint(
                    p.optDouble("expected", 0.5),
                    p.optDouble("weight", 1.0)
                )
            }
        }
        val streakObj = json.optJSONObject("streakThresholds")
        val restObj = json.optJSONObject("rest")
        val circArr = json.optJSONArray("circadian") ?: org.json.JSONArray()
        val circadian = mutableListOf<CircadianHour>()
        for (i in 0 until circArr.length()) {
            val h = circArr.optJSONObject(i) ?: continue
            circadian += CircadianHour(
                h.optInt("hour", -1),
                h.optDouble("offsetZ", 0.0)
            )
        }
        Profile(
            version = json.optInt("version", 1),
            generatedAt = json.optString("generatedAt", "unknown"),
            gamesAnalyzed = json.optInt("gamesAnalyzed", 0),
            sessionGapMin = json.optDouble("sessionGapMin", 45.0),
            pipelineLatencyMin = json.optInt("pipelineLatencyMin", 5),
            baselines = baselines,
            fatigue = fatigue,
            lossWeightCurve = curve,
            lossCurveDerived = curveDerived,
            streak = StreakThresholds(
                streakObj?.optDouble("yellowWeight")
                    ?: ChessPhase2V3Engine.STREAK_YELLOW_WEIGHT,
                streakObj?.optDouble("redWeight")
                    ?: ChessPhase2V3Engine.STREAK_RED_WEIGHT,
                streakObj?.optBoolean("derived", false) ?: false
            ),
            rest = Rest(
                restObj?.optInt("restMinutes", 30) ?: 30,
                restObj?.optBoolean("derived", false) ?: false
            ),
            circadian = circadian
        )
    } catch (e: Exception) {
        Log.w(TAG, "Profile parse failed: ${e.message}")
        null
    }

    // ── Cache + fetch ──────────────────────────────────────────────────

    private val client = BridgeClient()

    /** Loads the cached profile, or the v3-identical fallback. */
    fun load(context: Context): Profile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null) ?: return fallback()
        return parse(JSONObject(raw)) ?: fallback()
    }

    /**
     * Fetches a fresh profile from the bridge and caches it. Returns the
     * new profile, or null on any failure (caller keeps the cached one).
     * Never throws.
     */
    suspend fun refresh(
        credentials: ChessAnalysisFetcher.BridgeCredentials?,
        context: Context? = null
    ): Profile? {
        if (credentials == null || credentials.url.isBlank() ||
            credentials.token.isBlank()
        ) return null
        return try {
            val resp = client.post(
                bridgeUrl = credentials.url,
                token = credentials.token,
                path = "chess_analysis/v4_profile",
                body = JSONObject(),
                readTimeoutMs = 10_000
            ) ?: run {
                Log.w(TAG, "Profile endpoint unreachable/HTTP error")
                return null
            }
            val parsed = parse(resp)
            // Cache the RAW json so load() round-trips every field.
            if (parsed != null && parsed.isReal && context != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_JSON, resp.toString()).apply()
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "Profile refresh failed: ${e.message}")
            null
        }
    }

}
