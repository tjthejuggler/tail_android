package com.example.tail.notify

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.environment.EnvironmentMetric
import com.example.tail.data.environment.EnvironmentRepository
import com.example.tail.data.location.LocationRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * One-time background environment backlog.
 *
 * Runs the SAME polite day-by-day capture as the in-app buttons, but as a
 * WorkManager job: it keeps going after the app is closed, retries on
 * transient failures with backoff, and pauses on connectivity loss.
 *
 * Battery story: this is NOT a periodic worker. Nothing is scheduled until
 * the user taps "Resume history (background)" / "Full redo (background)";
 * the job exists only while the backlog runs, then it is gone. Zero cost
 * during normal usage. The 2.5 s inter-batch sleep keeps the radio in its
 * normal duty cycle, so the whole run costs a few minutes of idle-ish
 * networking spread over however long the history is.
 *
 * The worker appends to the same snapshot store the in-app flow uses and
 * reuses the resume rule (skip days whose capture is complete for all
 * linked metrics), so app and worker never fight: whichever runs next
 * simply skips whatever the other already finished.
 */
class EnvironmentBacklogWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settingsRepo = SettingsRepository(applicationContext)
        val settings: AppSettings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "settings read failed: ${e.message}")
            return Result.retry()
        }
        if (!settings.environmentEnabled) return Result.success()

        val repair = inputData.getBoolean(KEY_REPAIR, false)
        val useFahrenheit = settings.environmentTemperatureUnit == "F"
        val locationRepo = LocationRepository(applicationContext)
        val envRepo = EnvironmentRepository(applicationContext)
        val habitsRepo = HabitsRepository()

        if (repair) envRepo.clearAllSnapshots()

        val allCoords = locationRepo.getAllStoredCoords()
        val labels = locationRepo.getAllStoredLabels()
        if (allCoords.isEmpty()) return Result.success()

        val today = LocalDate.now()
        val dates = allCoords.keys.mapNotNull {
            runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
        }.filter { !it.isAfter(today) }.sorted()

        val linkedMetrics = settings.environmentHabitMetrics.values
            .mapNotNull { EnvironmentMetric.fromKey(it) }
        val todo = if (repair) dates else dates.filter { d ->
            val snap = envRepo.getSnapshot(d)
            snap == null || snap.fetchedAt.isBlank() ||
                linkedMetrics.any { it.extract(snap) == null }
        }
        if (todo.isEmpty()) return Result.success()

        // Group remaining days by location: consecutive days at one place are
        // captured with RANGE requests (~35 days per network window) — a
        // 2 000-day backlog becomes ~150 light requests instead of ~6 000.
        data class Loc(val lat: Double, val lon: Double, val label: String)
        val byLocation = linkedMapOf<Loc, MutableList<LocalDate>>()
        for (d in todo) {
            val c = allCoords[d.toString()] ?: continue
            val l = labels[d.toString()] ?: ""
            byLocation.getOrPut(Loc(c.first, c.second, l)) { mutableListOf() }.add(d)
        }

        // Persisted progress: the settings UI reads this to show live state
        // even after process death (the WorkManager job itself survives).
        val prefs = applicationContext.getSharedPreferences("tail_env_backlog", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("running", true).putBoolean("repair", repair)
            .putInt("total", todo.size).putInt("done", 0)
            .putLong("startedAt", System.currentTimeMillis())
            .putString("lastMessage", "Background backlog starting — ${todo.size} day(s)")
            .apply()

        val totalWindows = byLocation.values.sumOf { days -> (days.size + 34) / 35 }
        var windowsDone = 0
        for ((loc, days) in byLocation) {
            if (isStopped) {
                prefs.edit().putBoolean("running", false)
                    .putString("lastMessage", "Interrupted — press Resume in background to continue")
                    .apply()
                return Result.retry()
            }
            val stored = envRepo.captureRange(days, Pair(loc.lat, loc.lon), loc.label) { wDone, wTotal ->
                windowsDone++
                prefs.edit()
                    .putInt("done", windowsDone).putInt("total", totalWindows)
                    .putString(
                        "lastMessage",
                        "Background backlog — ${loc.label.ifEmpty { "unknown" }}: " +
                            "window $wDone/$wTotal · $windowsDone/$totalWindows windows"
                    )
                    .apply()
            }
            setProgress(
                workDataOf(
                    "done" to windowsDone, "total" to totalWindows, "repair" to repair
                )
            )
            // Sync linked squares into the habits DB for this location's days.
            if (settings.fileUri.isNotEmpty() && settings.environmentHabitMetrics.isNotEmpty()) {
                val snaps = days.mapNotNull { envRepo.getSnapshot(it) }.associateBy { it.date }
                if (snaps.isNotEmpty()) {
                    val db = habitsRepo.loadDatabase(
                        android.net.Uri.parse(settings.fileUri), applicationContext
                    ).toMutableMap()
                    var changed = false
                    for ((_, snap) in snaps) {
                        for ((habit, metricKey) in settings.environmentHabitMetrics) {
                            val metric = EnvironmentMetric.fromKey(metricKey) ?: continue
                            val v = metric.scaledValue(snap, useFahrenheit) ?: continue
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
            }
            Log.i(TAG, "location '$loc.label' done: +$stored stored")
        }
        prefs.edit()
            .putBoolean("running", false)
            .putString("lastMessage", "Background backlog finished — $totalWindows window(s)")
            .apply()
        Log.i(TAG, "backlog finished: $totalWindows window(s)")
        return Result.success()
    }

    companion object {
        private const val TAG = "EnvBacklogWorker"
        private const val KEY_REPAIR = "repair"
        private const val WORK_NAME = "environment_backlog"

        /**
         * Schedules (or replaces) the one-time background backlog. Runs only
         * with network; retries with backoff on failure/app death.
         */
        fun schedule(context: Context, repair: Boolean) {
            val request = OneTimeWorkRequestBuilder<EnvironmentBacklogWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_REPAIR to repair))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
