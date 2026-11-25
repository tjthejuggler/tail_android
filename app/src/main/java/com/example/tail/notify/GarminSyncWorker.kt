package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tail.data.SettingsRepository
import com.example.tail.data.remoteProxyUrlFrom
import com.example.tail.data.health.GarminRepository
import com.example.tail.data.health.DailyValueMap
import com.example.tail.data.health.GarminType
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Garmin-metrics cache sync — retained for on-demand use only.
 *
 * HISTORY (2026-09-26): this worker existed as a 2-hour periodic so Garmin
 * data kept flowing while the app was closed. In the launcher era
 * (2026-11-25) that periodic schedule was retired by [BackgroundWorkPolicy]:
 * Tail is the HOME app and `onAppForegrounded` already calls
 * `syncGarminCurrentMonth()` on every ON_RESUME, which is a far more frequent
 * and better-timed trigger than any period. The 2 h periodic was pure
 * duplication + ~12 network wakeups/day for nothing.
 *
 * The class is kept (not deleted) because its merge logic is the canonical
 * "pull from every candidate proxy, merge into the local cache" routine and
 * remains a valid one-shot if an explicit in-app "Sync now" is ever wanted.
 * It is NOT scheduled anywhere.
 *
 * An unreachable proxy (away from home, PC asleep) is not an error.
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

        // A remote host alone is enough — the laptop proxy URL is optional
        // once the always-on server is configured.
        val hasAnyProxy = settings.garminProxyUrl.isNotEmpty() ||
            settings.remoteHostUrl.isNotEmpty()
        if (!settings.garminEnabled || !hasAnyProxy ||
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
        // Remote (always-on server) proxy first, laptop proxy as fallback —
        // Garmin data keeps flowing when the laptop is off. Both serve the
        // same cache shape.
        val proxyUrls = buildList {
            remoteProxyUrlFrom(settings.remoteHostUrl).takeIf { it.isNotBlank() }?.let { add(it) }
            add(settings.garminProxyUrl)
        }
        var fresh: Map<GarminType, DailyValueMap>? = null
        for (proxyUrl in proxyUrls) {
            val data = try {
                repo.fetchCurrentMonthData(
                    proxyUrl,
                    settings.garminAppToken,
                    settings.garminDateOfBirth
                )
            } catch (e: Exception) {
                Log.w(TAG, "Garmin sync fetch failed on $proxyUrl " +
                    "(will try next candidate/period): ${e.message}")
                null
            }
            if (data != null && data.isNotEmpty()) {
                fresh = data
                break
            }
            // null or empty: try the next candidate (an unreachable proxy can
            // surface as either); only the LAST empty result is treated as
            // "reachable but nothing published yet".
            if (data != null && proxyUrl == proxyUrls.last()) fresh = data
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

        // No schedule(): the 2 h periodic was retired 2026-11-25
        // (BackgroundWorkPolicy). This worker is on-demand only.
    }
}
