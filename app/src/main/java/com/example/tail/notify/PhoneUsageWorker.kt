package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.phoneusage.PhoneUsageMetric
import com.example.tail.data.phoneusage.PhoneUsageRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Periodic phone-usage aggregation worker (~4 h).
 *
 * UsageStats keeps only a limited event history (~7 days on most devices),
 * so aggregation must run frequently enough not to lose days: each run
 *  1. re-aggregates TODAY (partial — the day is still accumulating),
 *  2. re-aggregates YESTERDAY if it still needs capture (04:00 cutoff day),
 *  3. on the first run after enabling, retro-backfills whatever remaining
 *     history the device retains (flagged retro=1),
 * then syncs the linked habits' squares into the habits DB, mirroring the
 * EnvironmentBacklogWorker write path so app and worker never fight.
 *
 * No-ops silently when the feature is disabled or Usage access is missing.
 */
class PhoneUsageWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val settings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "settings read failed: ${e.message}")
            return Result.retry()
        }
        if (!settings.phoneUsageEnabled) return Result.success()

        val repo = PhoneUsageRepository(applicationContext)
        if (!repo.hasUsageAccess()) {
            Log.i(TAG, "usage access missing — skipping")
            return Result.success()
        }

        val captured = mutableMapOf<String, com.example.tail.data.phoneusage.PhoneUsageSnapshot>()

        // First run after enable: retro-backfill the retained history (~7 d).
        if (settings.phoneUsageHabitMetrics.isNotEmpty() &&
            repo.getAllSnapshots().values.none { !it.retro }
        ) {
            captured.putAll(repo.backfillRecent())
        }

        val today = LocalDate.now()
        // Yesterday (only until its snapshot has data — the day is closed at 04:00).
        repo.captureForDate(today.minusDays(1), retro = false)?.let {
            captured[it.date] = it
        }
        // Today (partial, refreshed every run).
        repo.captureForDate(today, retro = false)?.let {
            captured[it.date] = it
        }

        if (captured.isEmpty()) return Result.success()
        Log.i(TAG, "aggregated ${captured.size} day(s)")

        // Sync linked squares into the habits DB (EnvironmentBacklogWorker path).
        val links = settings.phoneUsageHabitMetrics
        if (settings.fileUri.isNotEmpty() && links.isNotEmpty()) {
            val habitsRepo = HabitsRepository()
            val db = habitsRepo.loadDatabase(
                android.net.Uri.parse(settings.fileUri), applicationContext
            ).toMutableMap()
            var changed = false
            for ((_, snap) in captured) {
                for ((habit, metricKey) in links) {
                    val metric = PhoneUsageMetric.fromKey(metricKey) ?: continue
                    if (habit !in db) continue // never resurrect deleted habits
                    val v = metric.scaledValue(snap) ?: continue
                    val entries = (db[habit] ?: mutableMapOf()).toMutableMap()
                    if (entries[snap.date] != v) {
                        entries[snap.date] = v
                        db[habit] = entries
                        changed = true
                    }
                }
            }
            if (changed) habitsRepo.persistDatabase(
                android.net.Uri.parse(settings.fileUri), applicationContext, db
            )
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "PhoneUsageWorker"
        private const val WORK_NAME = "tail_phone_usage_periodic"

        /** Schedules the ~4 h periodic aggregation (idempotent, KEEP policy). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneUsageWorker>(4, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
