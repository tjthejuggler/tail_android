package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tail.data.ExperimentStatus
import com.example.tail.data.ExperimentStatusStore
import com.example.tail.data.HabitNotification
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.remoteBridgeConnectionFrom
import com.example.tail.data.tailcue.TailCue
import com.example.tail.data.tailcue.TailCueService
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background TailCue warning sync.
 *
 * TailCue (on the PC) pushes predictive warnings ("cues") to the Tail bridge
 * whenever something noticed today raises the chance of something upcoming —
 * a validated lead indicator firing, a metric going anomalous, a discovered
 * pattern going active — each with any known countermeasure.
 *
 * Every ~15 minutes this worker pulls pending cues and drops them into the
 * existing notification system (in-app center + system notification) as info
 * notices, then acks them on the bridge. 👍/👎/notes are sent from the
 * notification center rows (see [TailCueFeedback]).
 *
 * An unreachable bridge is not an error — the next periodic pass retries.
 */
class TailCueSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = try {
            SettingsRepository(applicationContext).settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings: ${e.message}")
            return Result.success()
        }
        if (!settings.bridgeEnabled) return Result.success()
        // Remote (always-on server) bridge first, laptop bridge as fallback —
        // TailCue pushes cues to the remote bridge, so warnings reach the
        // phone even when the laptop is off.
        val candidates = buildList {
            remoteBridgeConnectionFrom(settings.remoteHostUrl, settings.garminAppToken)
                ?.let { add(it) }
            bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
                ?.let { add(it) }
        }
        if (candidates.isEmpty()) return Result.success()

        val service = TailCueService()
        var conn: Pair<String, String>? = null
        var cues: List<TailCue>? = null
        for (c in candidates) {
            cues = try {
                service.fetchPending(c.first, c.second)
            } catch (e: Exception) {
                Log.w(TAG, "Cue fetch failed on ${c.first}: ${e.message}")
                null
            }
            if (cues != null) {
                conn = c
                break
            }
        }
        val bridge = conn ?: return Result.success() // unreachable — retry next period
        val ack: suspend (String) -> Unit = { id ->
            service.ack(bridge.first, bridge.second, id) // best-effort
        }
        val cueList = cues ?: return Result.success()

        if (cueList.isEmpty()) return Result.success()

        val store = NotificationStore(applicationContext)
        for (cue in cueList) {
            // Special family: the experiment-status mirror (no notification).
            // Store the active experiment so MainActivity can flash a reminder
            // on app open; a "clear" event wipes it. Ack so it doesn't requeue.
            if (cue.family == "experiment") {
                try {
                    val data = org.json.JSONObject(cue.body)
                    when (data.optString("event")) {
                        "started", "refresh" -> ExperimentStatusStore.save(
                            applicationContext,
                            ExperimentStatus(
                                expId = data.optInt("exp_id", 0),
                                title = data.optString("title", cue.title),
                                daysLeft = data.optInt("days_left", 0),
                                daysIn = data.optInt("days_in", 1),
                                endsAt = data.optString("ends_at", "")
                            )
                        )
                        "clear" -> ExperimentStatusStore.save(applicationContext, null)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Bad experiment status cue: ${e.message}")
                }
                ack(cue.id)
                continue
            }
            val ask = HabitNotification(
                id = TailCue.askId(cue.id),
                habitName = "TailCue",
                type = HabitNotification.TYPE_INFO,
                // High-severity cues (e.g. the nightly momentum streak warning)
                // get a red marker so they stand out in the shade.
                title = if (cue.severity == "high") "🔴 ${cue.title}" else cue.title,
                question = if (cue.advice.isBlank()) cue.body
                           else cue.body + "\n\n💡 " + cue.advice,
                createdAtMillis = System.currentTimeMillis(),
                payload = TailCue.payload(cue)
            )
            store.add(ask)
            HabitNotifier.postAsk(applicationContext, ask)
            ack(cue.id)
            Log.i(TAG, "Posted TailCue warning '${cue.title}' (${cue.severity})")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TailCueSync"
        private const val UNIQUE_WORK_NAME = "tailcue_cue_sync"

        /** Idempotently schedules the periodic sync (15 min, any network). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TailCueSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "TailCue warning sync scheduled (15 min period)")
        }
    }
}
