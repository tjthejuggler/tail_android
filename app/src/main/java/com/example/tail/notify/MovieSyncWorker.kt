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
import com.example.tail.data.MovieBridgeService
import com.example.tail.data.MovieCacheStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background movie-bridge sync.
 *
 * The desktop watcher has a movie in its cache within ~a minute of playback
 * starting, but the phone previously only pulled it when the app was open —
 * a single fetch that, if it failed (Wi-Fi still reconnecting after unlock,
 * PC briefly asleep), was never retried. This worker closes that gap:
 *
 *  • every ~15 minutes (WorkManager's minimum period) it pulls the bridge's
 *    recent watch history into the phone-local [MovieCacheStore], so the
 *    data is on the phone long before the movie ends;
 *  • it then runs the shared movie-ask check ([HabitAsks.checkAndPostMovieAsk]),
 *    so the "Watched this?" notification is usually already waiting when the
 *    user picks up the phone — no app open needed.
 *
 * A bridge that is unreachable (away from home, PC off) is not an error:
 * the worker finishes quietly and the next periodic pass retries.
 */
class MovieSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = try {
            SettingsRepository(applicationContext).settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings: ${e.message}")
            return Result.success()
        }
        if (!settings.bridgeEnabled) return Result.success()
        val conn = bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
            ?: return Result.success()

        val fresh = try {
            MovieBridgeService().fetchRecent(conn.first, conn.second, MovieCacheStore.CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Movie sync fetch failed: ${e.message}")
            null
        }
        if (fresh == null) return Result.success() // unreachable — retry next period

        if (fresh.isNotEmpty()) {
            MovieCacheStore.save(applicationContext, fresh)
            Log.i(TAG, "Movie cache synced: ${fresh.size} entries, newest='${fresh.firstOrNull()?.title}'")
        }

        val habitName = settings.bridgeMovieHabits.firstOrNull {
            it in settings.textInputHabits
        } ?: return Result.success()
        HabitAsks.checkAndPostMovieAsk(applicationContext, habitName, fresh)
        return Result.success()
    }

    companion object {
        private const val TAG = "MovieSyncWorker"
        private const val UNIQUE_WORK_NAME = "movie_bridge_sync"

        /**
         * Idempotently schedules the periodic sync (15 min, any connected
         * network). Safe to call on every app open — KEEP leaves an existing
         * schedule untouched.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MovieSyncWorker>(15, TimeUnit.MINUTES)
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
            Log.i(TAG, "Movie sync worker scheduled (15 min period)")
        }
    }
}
