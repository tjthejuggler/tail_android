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
import com.example.tail.data.SettingsRepository
import com.example.tail.data.health.GarminRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background Garmin-metrics sync.
 *
 * Root cause this closes (2026-09-26): Garmin data only ever synced when the
 * main app UI was open — `onAppForegrounded` and a 24h polling loop that lived
 * in the HabitViewModel's viewModelScope (and was only started when settings
 * were saved). When the app was swiped away or the process died, Garmin sync
 * silently stopped while everything else (movies, PC-widget events) kept
 * flowing through their WorkManager workers — so "everything synced except
 * Garmin" with nothing obviously broken on either side.
 *
 * This worker mirrors [MovieSyncWorker]: every ~2 hours (network-constrained,
 * no charging requirement) it pulls the last 7 days of metrics from the proxy
 * and MERGES them into the phone-local Garmin cache. The habit-DB application
 * of that cached data already happens on every app foreground (idempotent
 * `applyGarminData`), and the bubble overlay / widgets read the repository
 * cache directly — so they go fresh immediately after this worker runs.
 *
 * An unreachable proxy (away from home, PC asleep) is not an error: the worker
 * finishes quietly and the next periodic pass retries.
 *
 * Configuration gaps (Garmin disabled, no habit links, no DB file) are logged
 * as INFO, not errors — they are intentional states, but they are logged so a
 * misconfiguration shows up in `adb logcat` instead of failing silently.
 */
class GarminSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = try {
            SettingsRepository(applicationContext).settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings: ${e.message}")
            return Result.success()
        }

        if (!settings.garminEnabled || settings.garminProxyUrl.isEmpty() ||
            settings.garminAppToken.isEmpty()
        ) {
            Log.i(TAG, "Garmin sync skipped: disabled or unconfigured")
            return Result.success()
        }
        if (settings.garminHabitLinks.isEmpty()) {
            Log.i(TAG, "Garmin sync skipped: no habits linked to metrics")
            return Result.success()
        }
        if (settings.fileUri.isEmpty()) {
            Log.i(TAG, "Garmin sync skipped: no habit database file set")
            return Result.success()
        }

        val repo = GarminRepository(applicationContext)
        val fresh = try {
            repo.fetchCurrentMonthData(
                settings.garminProxyUrl,
                settings.garminAppToken,
                settings.garminDateOfBirth
            )
        } catch (e: Exception) {
            Log.w(TAG, "Garmin sync fetch failed (will retry next period): ${e.message}")
            null
        }
        if (fresh == null) return Result.success() // unreachable — retry next period

        if (fresh.isEmpty()) {
            Log.w(TAG, "Garmin sync fetched zero days — proxy reachable but no data served")
            return Result.success()
        }

        val today = java.time.LocalDate.now().toString()
        val days = fresh.values.firstOrNull()?.keys?.size ?: 0
        val hasToday = fresh.values.any { it.containsKey(today) }
        repo.mergeAndCacheDailyData(fresh)
        Log.i(
            TAG, "Garmin cache synced: ${fresh.size} metric types, ~$days days, " +
                "todayPresent=$hasToday"
        )
        if (!hasToday) {
            // The PC fetcher only refreshes every 30 min and Garmin itself
            // publishes daily metrics with delay — a missing "today" is normal
            // early in the morning, but worth a log line for diagnosis.
            Log.i(TAG, "Today's Garmin data not published yet (normal early in the day)")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "GarminSyncWorker"
        private const val UNIQUE_WORK_NAME = "garmin_metrics_sync"

        /**
         * Idempotently schedules the periodic sync (2 h, any connected
         * network). Safe to call on every app open — KEEP leaves an existing
         * schedule untouched.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GarminSyncWorker>(2, TimeUnit.HOURS)
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
            Log.i(TAG, "Garmin sync worker scheduled (2 h period)")
        }
    }
}
