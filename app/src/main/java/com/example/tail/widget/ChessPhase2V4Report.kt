package com.example.tail.widget

import android.util.Log
import com.example.tail.data.BridgeClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fire-and-forget reporter: after every v4 audit the phone pushes the
 * FULL decision record (every variable that went into the recommendation)
 * to the bridge's v4_report endpoint, where chess-coach's tray "V4
 * Recommendation History" viewer picks it up. Never throws, never blocks
 * the audit — a lost report is acceptable telemetry loss.
 */
object ChessPhase2V4Report {

    private const val TAG = "ChessPhase2V4Report"
    private val client = BridgeClient()

    suspend fun send(
        credentials: ChessAnalysisFetcher.BridgeCredentials?,
        gameId: Long?,
        username: String,
        result: ChessPhase2V3Engine.AuditResultV3,
        input: ChessPhase2V3Engine.GameInputV3,
        profile: ChessPhase2V4Profile.Profile
    ) {
        if (credentials == null || credentials.url.isBlank()) return
        val body = JSONObject().apply {
            put("reportedAt", java.time.Instant.now().toString())
            put("gameId", gameId?.toString() ?: "unknown")
            put("username", username)
            put("verdict", result.outputState.name)
            put("reason", result.reason)
            put("message", result.message)
            put("redRules", JSONArray(result.redRules))
            put("yellowRules", JSONArray(result.yellowRules))
            put("profile", JSONObject().apply {
                put("generatedAt", profile.generatedAt)
                put("gamesAnalyzed", profile.gamesAnalyzed)
                put("isReal", profile.isReal)
            })
            // Exact variables behind the decision:
            put("variables", JSONObject().apply {
                put("timeControl", input.timeControl.name)
                put("result", input.result.name)
                put("expectedScore", input.expectedScore)
                put("deltaE", input.deltaE)
                put("sessionElapsedMins", input.sessionElapsedMins)
                put("fatigueYellowAt", result.fatigueYellowAt)
                put("fatigueRedAt", result.fatigueRedAt)
                put("localHour", input.localHour)
                put("weightedStreak", result.weightedStreak)
                put("streakYellowWeight", profile.streak.yellowWeight)
                put("streakRedWeight", profile.streak.redWeight)
                put("zMoveTime", result.zMoveTime ?: JSONObject.NULL)
                put("zDeficit", result.zDeficit ?: JSONObject.NULL)
                put("circadianOffsetZ", profile.circadianOffsetZ(input.localHour))
                put("acwr", result.acwr ?: JSONObject.NULL)
                put("strain", result.strain)
                put("sessionStrain", result.sessionStrain)
                put("strainTerminateAt", result.strainTerminateAt)
                put("catastrophic", result.catastrophic)
                put("strainForgiven", result.strainForgiven)
                put("accViolation", result.accViolation)
                put("blunderViolation", result.blunderViolation)
                put("engineBacked", result.engineBacked)
                put("unforcedBlunders", input.unforcedBlunders ?: JSONObject.NULL)
                put("blunders", input.blunderCount ?: JSONObject.NULL)
                put("mistakes", input.mistakeCount ?: JSONObject.NULL)
                put("analysisAcpl", input.analysisAcpl ?: JSONObject.NULL)
                put("readinessCcrs", input.readinessCcrs ?: JSONObject.NULL)
                put("hysteresisHeld", result.hysteresisHeld)
                put("restPrescriptionMin", profile.rest.restMinutes)
            })
        }
        try {
            client.post(
                bridgeUrl = credentials.url,
                token = credentials.token,
                path = "chess_analysis/v4_report",
                body = body,
                readTimeoutMs = 5_000
            )
        } catch (e: Exception) {
            Log.w(TAG, "v4 report failed (acceptable): ${e.message}")
        }
    }
}
