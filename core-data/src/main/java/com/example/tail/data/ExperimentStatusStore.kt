package com.example.tail.data

import android.content.Context
import org.json.JSONObject

/**
 * The experiment the user is currently running (mirrored from TailCue on the PC).
 *
 * TailCue pushes special family="experiment" cues to the bridge; TailCueSyncWorker
 * decodes them and stores the ACTIVE experiment here so the app can flash a
 * full-screen "you are in an experiment" reminder on every app open. A null/absent
 * state means no experiment is running.
 */
data class ExperimentStatus(
    val expId: Int,
    val title: String,
    val daysLeft: Int,
    val daysIn: Int,
    val endsAt: String,
)

object ExperimentStatusStore {
    private const val PREFS = "tailcue_experiment_status"
    private const val KEY = "status"

    fun load(context: Context): ExperimentStatus? = try {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return null
        val json = JSONObject(raw)
        ExperimentStatus(
            expId = json.optInt("exp_id", 0),
            title = json.optString("title", ""),
            daysLeft = json.optInt("days_left", 0),
            daysIn = json.optInt("days_in", 1),
            endsAt = json.optString("ends_at", "")
        ).takeIf { it.title.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    fun save(context: Context, status: ExperimentStatus?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (status == null) {
            prefs.edit().remove(KEY).apply()
            return
        }
        val json = JSONObject().apply {
            put("exp_id", status.expId)
            put("title", status.title)
            put("days_left", status.daysLeft)
            put("days_in", status.daysIn)
            put("ends_at", status.endsAt)
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }
}
