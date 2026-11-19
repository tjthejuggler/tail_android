package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.tail.data.ExperimentStatus
import com.example.tail.data.ExperimentStatusStore
import com.example.tail.data.HabitNotification
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.movie.MovieBridgeService
import com.example.tail.data.movie.MovieCacheStore
import com.example.tail.data.remoteBridgeConnectionFrom
import com.example.tail.data.tailcue.TailCue
import com.example.tail.data.tailcue.TailCueService
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background bridge sync: TailCue warnings + movie cache in ONE pass.
 *
 * TailCue (on the PC) pushes predictive warnings ("cues") to the Tail bridge
 * whenever something noticed today raises the chance of something upcoming —
 * a validated lead indicator firing, a metric going anomalous, a discovered
 * pattern going active — each with any known countermeasure.
 *
 * The movie half (merged from the former separate MovieSyncWorker,
 * 2026-11-19): the desktop watcher has a movie in its cache within ~a minute
 * of playback starting; this worker pulls the bridge's recent watch history
 * into the phone-local [MovieCacheStore] and runs the shared movie-ask check
 * ([HabitAsks.checkAndPostMovieAsk]), so the "Watched this?" notification is
 * usually already waiting when the user picks up the phone.
 *
 * ## Battery design (2026-11-19)
 *  - **Once an hour**, one wakeup for everything (was: two separate 15-min
 *    pollers ≈ 192 network wakeups/day; now ≈ 24).
 *  - **No connectivity = no work at all.** The CONNECTED constraint means
 *    WorkManager never wakes the process in airplane mode / without data;
 *    the run is simply deferred until a network appears.
 *  - **Foreground suppression.** A periodic pass that finds a pass hit any
 *    bridge within the last [SKIP_IF_SYNCED_WITHIN_MS] skips the network
 *    entirely — opening the app (which triggers [syncNow]) effectively
 *    pushes the next background check an hour out.
 *  - **Battery gate.** The periodic schedule requires battery-not-low, so a
 *    nearly-dead phone spends its remaining charge on anything but syncing.
 *
 * An unreachable bridge is not an error — the next periodic pass retries.
 * 👍/👎/notes on cues are sent from the notification center rows (see
 * [TailCueFeedback]).
 */
class TailCueSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Foreground suppression: a periodic pass stands down when any pass
        // (foreground one-shot or periodic) already hit a bridge recently.
        if (inputData.getBoolean(KEY_PERIODIC, false)) {
            val last = prefs.getLong(KEY_LAST_BRIDGE_SYNC, 0L)
            val since = System.currentTimeMillis() - last
            if (last > 0L && since < SKIP_IF_SYNCED_WITHIN_MS) {
                Log.i(TAG, "Skipping periodic bridge sync — a pass hit a bridge ${since / 60_000} min ago")
                return Result.success()
            }
        }

        val settings = try {
            SettingsRepository(applicationContext).settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings: ${e.message}")
            return Result.success()
        }
        if (!settings.bridgeEnabled) return Result.success()

        var bridgeReached = false

        // ── TailCue cues ─────────────────────────────────────────────────
        // Remote (always-on server) bridge first, laptop bridge as fallback —
        // TailCue pushes cues to the remote bridge, so warnings reach the
        // phone even when the laptop is off.
        val candidates = buildList {
            remoteBridgeConnectionFrom(settings.remoteHostUrl, settings.garminAppToken)
                ?.let { add(it) }
            bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
                ?.let { add(it) }
        }
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
        val bridge = conn
        if (bridge != null) {
            bridgeReached = true
            val store = NotificationStore(applicationContext)
            val ack: suspend (String) -> Unit = { id ->
                service.ack(bridge.first, bridge.second, id) // best-effort
            }
            for (cue in cues.orEmpty()) {
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
        }

        // ── Movie cache (merged from MovieSyncWorker, 2026-11-19) ────────
        // The movie watcher runs on the laptop, so unlike cues this stays on
        // the laptop bridge. Unreachable (PC off / away from home) is fine.
        val movieConn = bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
        if (movieConn != null) {
            val fresh = try {
                MovieBridgeService().fetchRecent(movieConn.first, movieConn.second, MovieCacheStore.CAPACITY)
            } catch (e: Exception) {
                Log.w(TAG, "Movie sync fetch failed: ${e.message}")
                null
            }
            if (fresh != null) {
                bridgeReached = true
                if (fresh.isNotEmpty()) {
                    MovieCacheStore.save(applicationContext, fresh)
                    Log.i(TAG, "Movie cache synced: ${fresh.size} entries, newest='${fresh.firstOrNull()?.title}'")
                }
                val habitName = settings.bridgeMovieHabits.firstOrNull {
                    it in settings.textInputHabits
                }
                if (habitName != null) {
                    HabitAsks.checkAndPostMovieAsk(applicationContext, habitName, fresh)
                }
            }
        }

        if (bridgeReached) {
            prefs.edit().putLong(KEY_LAST_BRIDGE_SYNC, System.currentTimeMillis()).apply()
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "TailCueSync"
        private const val UNIQUE_WORK_NAME = "tailcue_cue_sync"
        private const val SYNC_NOW_WORK_NAME = "tailcue_cue_sync_now"

        /**
         * Legacy unique names of the separate movie poller that this worker
         * absorbed (2026-11-19). Cancelled on every schedule() so upgraded
         * installs — which still carry the old periodic spec in WorkManager's
         * database — stop waking a second time each hour (and stop trying to
         * instantiate the deleted MovieSyncWorker class).
         */
        private const val LEGACY_MOVIE_WORK_NAME = "movie_bridge_sync"
        private const val LEGACY_MOVIE_NOW_WORK_NAME = "movie_bridge_sync_now"

        private const val PREFS = "tailcue_sync_state"
        private const val KEY_LAST_BRIDGE_SYNC = "last_bridge_sync_at"
        private const val KEY_PERIODIC = "periodic"

        /** A periodic pass skips itself when any pass hit a bridge this recently. */
        private const val SKIP_IF_SYNCED_WITHIN_MS = 50 * 60 * 1000L

        /**
         * Idempotently schedules the periodic sync (1 h, any network,
         * battery not low).
         *
         * UPDATE (not KEEP) so an already-installed schedule from an older
         * build (15 min period — the cause of constant background traffic
         * and battery drain, 2026-11-19) is actually replaced on upgrade;
         * with an unchanged spec UPDATE behaves like KEEP (no reset).
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(LEGACY_MOVIE_WORK_NAME)
            wm.cancelUniqueWork(LEGACY_MOVIE_NOW_WORK_NAME)
            val request = PeriodicWorkRequestBuilder<TailCueSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInputData(workDataOf(KEY_PERIODIC to true))
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "TailCue + movie bridge sync scheduled (1 h period)")
        }

        /**
         * Immediate one-shot pass for when the app is actually open
         * (activity resume) — keeps the hourly background cadence cheap
         * while still feeling instant in the foreground. KEEP policy means
         * rapid re-entries never stack duplicate runs; a failed pass simply
         * waits for the next hourly period. No battery gate — the user just
         * deliberately opened the app.
         */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<TailCueSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_NOW_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
