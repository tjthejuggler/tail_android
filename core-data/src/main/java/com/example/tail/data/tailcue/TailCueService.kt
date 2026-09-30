package com.example.tail.data.tailcue

import android.util.Log
import com.example.tail.data.BridgeClient
import org.json.JSONObject

/**
 * One predictive warning ("cue") produced by TailCue on the PC.
 *
 * TailCue watches lead indicators in the self-tracking data (e.g. "RHR above
 * baseline → canker sore a day later") and pushes a cue whenever something
 * noticed today raises the chance of something upcoming. Cues carry any known
 * countermeasure as [advice].
 */
data class TailCue(
    val id: String,
    val createdAt: String,
    val family: String,
    val severity: String,
    val title: String,
    val body: String,
    val advice: String
) {
    companion object {
        private const val TAG = "TailCue"

        /** Ask-id prefix used by the notification system for cues. */
        const val ASK_PREFIX = "tailcue:"

        fun askId(cueId: String): String = "$ASK_PREFIX$cueId"

        fun cueIdFromAskId(askId: String): String? =
            askId.takeIf { it.startsWith(ASK_PREFIX) }?.removePrefix(ASK_PREFIX)

        fun parseCue(json: JSONObject): TailCue? {
            val id = json.optString("id", "")
            if (id.isBlank()) return null
            return TailCue(
                id = id,
                createdAt = json.optString("created_at", ""),
                family = json.optString("family", ""),
                severity = json.optString("severity", "info"),
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                advice = json.optString("advice", "")
            )
        }

        /** Payload codec for the cue's HabitNotification (advice survives restarts). */
        fun payload(cue: TailCue): String = JSONObject().apply {
            put("cid", cue.id)
            put("advice", cue.advice)
            put("severity", cue.severity)
            put("family", cue.family)
        }.toString()

        fun parseAdvice(payload: String): String = try {
            JSONObject(payload).optString("advice", "")
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Bridge client layer for TailCue cues: fetch pending, acknowledge, and send
 * 👍/👎/note feedback that TailCue uses to improve future warnings.
 */
class TailCueService(private val client: BridgeClient = BridgeClient()) {

    /** Pending (unacked) cues, or null when the bridge is unreachable. */
    suspend fun fetchPending(bridgeUrl: String, token: String, limit: Int = 20): List<TailCue>? {
        val json = client.fetch(bridgeUrl, token, "tailcue/cues/pending?limit=$limit") ?: return null
        val arr = json.optJSONArray("cues") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parseSafe(arr.getJSONObject(it)) }
    }

    private fun parseSafe(o: JSONObject): TailCue? = try {
        TailCue.parseCue(o)
    } catch (e: Exception) {
        Log.w("TailCue", "Bad cue JSON: ${e.message}")
        null
    }

    /** Tells TailCue the phone displayed the cue (best-effort). */
    suspend fun ack(bridgeUrl: String, token: String, cueId: String): Boolean =
        client.post(bridgeUrl, token, "tailcue/cues/${cueId}/ack", JSONObject()) != null

    /**
     * Sends 👍 ([rating] = 1) / 👎 (-1) with an optional explanatory note back
     * to TailCue, so future warnings and research directions can be steered.
     */
    suspend fun sendFeedback(
        bridgeUrl: String,
        token: String,
        cueId: String,
        rating: Int,
        comment: String?
    ): Boolean {
        val body = JSONObject().apply {
            put("rating", rating)
            put("comment", comment ?: "")
        }
        return client.post(bridgeUrl, token, "tailcue/cues/${cueId}/feedback", body) != null
    }
}
