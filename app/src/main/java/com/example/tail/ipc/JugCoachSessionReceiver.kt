package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.ui.ACTION_HABIT_INCREMENTED
import com.example.tail.ui.EXTRA_HABIT_NAME
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.buildTaskerStatsContent
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.secondaryValueSlotKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private const val TAG = "JugCoachSessionReceiver"

/**
 * **Protocol v3 (JugCoach)** — BroadcastReceiver that records a completed
 * JugCoach juggling run on the mapped habit in ONE atomic write.
 *
 * JugCoach fires this once per completed run (instead of the legacy plain
 * `ACTION_INCREMENT_HABIT` "+1" ping) so Tail can store the run's full
 * breakdown alongside the binary "used" count:
 *
 * - Habit's own count (binary "+1", max-1 cap respected)
 * - `secondary_value:<habit>`   — total seconds spent juggling
 * - `secondary_value2:<habit>`  — total catches
 * - `secondary_value3:<habit>`  — seconds in runs that ended in a catch
 * - `secondary_value4:<habit>`  — seconds in runs that ended in a drop
 * - `secondary_value5:<habit>`  — catches in runs that ended in a catch
 * - `secondary_value6:<habit>`  — catches in runs that ended in a drop
 *
 * Action:  `com.example.tail.ACTION_JUGCOACH_SESSION`
 * Extras:
 *  - `EXTRA_HABIT_ID`          — habit name (String)
 *  - `EXTRA_SECONDS_TOTAL`     — run duration in seconds (Int, ≥ 0)
 *  - `EXTRA_SECONDS_CATCH`     — seconds if the run ended in a catch, else 0
 *  - `EXTRA_SECONDS_DROP`      — seconds if the run ended in a drop, else 0
 *  - `EXTRA_CATCHES_TOTAL`     — total catches in the run (Int, ≥ 0)
 *  - `EXTRA_CATCHES_CATCH`     — catches if the run ended in a catch, else 0
 *  - `EXTRA_CATCHES_DROP`      — catches if the run ended in a drop, else 0
 *
 * Security: declared in the manifest with `android:permission` pointing to the
 * `com.example.tail.permission.TAIL_INTEGRATION` signature permission, so only
 * apps signed with the same keystore can send this broadcast.
 *
 * **Concurrency:** a shared [Mutex] serialises processing so that rapid-fire
 * broadcasts (several runs saved in quick succession) each see the previous
 * broadcast's committed changes — the same protection [HabitValueSetReceiver]
 * uses for WAGS backfills. The multi-key write itself is a single atomic
 * read-modify-write via [HabitsRepository.incrementHabitSlots].
 */
class JugCoachSessionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_JUGCOACH_SESSION = "com.example.tail.ACTION_JUGCOACH_SESSION"
        const val ACTION_JUGCOACH_BACKFILL = "com.example.tail.ACTION_JUGCOACH_BACKFILL"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_SECONDS_TOTAL = "EXTRA_SECONDS_TOTAL"
        const val EXTRA_SECONDS_CATCH = "EXTRA_SECONDS_CATCH"
        const val EXTRA_SECONDS_DROP = "EXTRA_SECONDS_DROP"
        const val EXTRA_CATCHES_TOTAL = "EXTRA_CATCHES_TOTAL"
        const val EXTRA_CATCHES_CATCH = "EXTRA_CATCHES_CATCH"
        const val EXTRA_CATCHES_DROP = "EXTRA_CATCHES_DROP"
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"

        /**
         * Serialises concurrent broadcasts so each session write sees the
         * committed result of the previous one (prevents lost increments when
         * several runs are saved in quick succession).
         */
        private val fileMutex = Mutex()
    }

    // Use a SupervisorJob scope so one failed coroutine doesn't cancel the others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_JUGCOACH_SESSION -> handleSession(context, intent)
            ACTION_JUGCOACH_BACKFILL -> handleBackfill(context, intent)
        }
    }

    private fun handleSession(context: Context, intent: Intent) {
        val habitName = intent.getStringExtra(EXTRA_HABIT_ID)?.takeIf { it.isNotBlank() }
        if (habitName == null) {
            Log.w(TAG, "Received $ACTION_JUGCOACH_SESSION with no valid EXTRA_HABIT_ID — ignoring")
            return
        }

        val secondsTotal = intent.getIntExtra(EXTRA_SECONDS_TOTAL, 0).coerceAtLeast(0)
        val secondsCatch = intent.getIntExtra(EXTRA_SECONDS_CATCH, 0).coerceAtLeast(0)
        val secondsDrop = intent.getIntExtra(EXTRA_SECONDS_DROP, 0).coerceAtLeast(0)
        val catchesTotal = intent.getIntExtra(EXTRA_CATCHES_TOTAL, 0).coerceAtLeast(0)
        val catchesCatch = intent.getIntExtra(EXTRA_CATCHES_CATCH, 0).coerceAtLeast(0)
        val catchesDrop = intent.getIntExtra(EXTRA_CATCHES_DROP, 0).coerceAtLeast(0)

        // goAsync() lets us do I/O without the system killing the receiver after onReceive returns.
        val pendingResult = goAsync()

        // Always use applicationContext: it holds the persisted SAF URI permissions
        // that were granted when the user picked the file in the main app.
        val appContext = context.applicationContext

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot record JugCoach session for '$habitName'")
                    return@launch
                }

                val uri = Uri.parse(fileUriString)

                // Binary "+1" on the habit's own count, respecting the "max 1"
                // cap (a JugCoach-usage habit is typically capped at 1/day).
                // The metric slots below bypass the cap because they are
                // cumulative totals, not binary "did it" counts.
                var binaryIncrement = 1
                if (habitName in settings.maxOneHabits) {
                    val db = habitsRepo.loadDatabase(uri, appContext)
                    val todayStr = java.time.LocalDate.now().toString()
                    val currentCount = db[habitName]?.get(todayStr) ?: 0
                    if (currentCount >= 1) {
                        binaryIncrement = 0
                        Log.i(TAG, "Skipping binary increment for '$habitName' — already at max 1 for today")
                    }
                }

                // One atomic write: binary count + all six metric slots.
                // Slots with a zero amount are skipped (no empty date entries).
                val increments = buildMap {
                    if (binaryIncrement > 0) put(habitName, binaryIncrement)
                    if (secondsTotal > 0) put(secondaryValueKey(habitName), secondsTotal)
                    if (catchesTotal > 0) put(secondaryValueSlotKey(habitName, 2), catchesTotal)
                    if (secondsCatch > 0) put(secondaryValueSlotKey(habitName, 3), secondsCatch)
                    if (secondsDrop > 0) put(secondaryValueSlotKey(habitName, 4), secondsDrop)
                    if (catchesCatch > 0) put(secondaryValueSlotKey(habitName, 5), catchesCatch)
                    if (catchesDrop > 0) put(secondaryValueSlotKey(habitName, 6), catchesDrop)
                }
                if (increments.isEmpty()) {
                    Log.i(TAG, "JugCoach session for '$habitName' carried no writable values — nothing to do")
                    return@launch
                }

                fileMutex.withLock {
                    habitsRepo.incrementHabitSlots(uri, appContext, increments)
                }
                HabitIncrementBus.emit(habitName)
                Log.i(
                    TAG,
                    "Recorded JugCoach session for '$habitName': +$binaryIncrement use, " +
                        "${secondsTotal}s total (${secondsCatch}s catch / ${secondsDrop}s drop), " +
                        "$catchesTotal catches ($catchesCatch catch / $catchesDrop drop)"
                )

                // Record timestamp for the IPC-triggered usage (binary part only)
                if (binaryIncrement > 0) {
                    try {
                        HabitTimestampRepository(appContext).addTimestamp(habitName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                    }
                }

                // Update the Tasker stats file so external apps see the new total immediately
                val taskerUri = settings.taskerFileUri
                if (taskerUri.isNotEmpty()) {
                    writeTaskerFile(appContext, habitsRepo, uri, taskerUri, settings.habitDividers, settings.noPointsHabits)
                }

                // Broadcast a generic "habit incremented" event for same-keystore listeners
                try {
                    val broadcastIntent = Intent(ACTION_HABIT_INCREMENTED).apply {
                        putExtra(EXTRA_HABIT_NAME, habitName)
                    }
                    appContext.sendBroadcast(
                        broadcastIntent,
                        "com.example.tail.permission.TAIL_INTEGRATION"
                    )
                    Log.d(TAG, "Sent ACTION_HABIT_INCREMENTED broadcast for '$habitName'")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send habit-incremented broadcast: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record JugCoach session for '$habitName': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * **Backfill handler** — SET absolute per-day totals from a JSON payload
     * of `{"yyyy-MM-dd": {"runs":n,"s":n,"c":n,"sc":n,"sd":n,"cc":n,"cd":n}}`
     * produced by JugCoach from its full run history.
     *
     * Idempotent: SET semantics mean re-running the backfill simply overwrites
     * the same values. The habit's own count is set to the day's run count
     * (capped at 1 for max-1 habits); the six metric slots get the raw totals.
     */
    private fun handleBackfill(context: Context, intent: Intent) {
        val habitName = intent.getStringExtra(EXTRA_HABIT_ID)?.takeIf { it.isNotBlank() }
        val json = intent.getStringExtra(EXTRA_VALUES_JSON)
        if (habitName == null || json.isNullOrBlank()) {
            Log.w(TAG, "Received $ACTION_JUGCOACH_BACKFILL with missing extras — ignoring")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                val days = parseBackfillJson(json)
                if (days.isEmpty()) {
                    Log.w(TAG, "Backfill JSON for '$habitName' contained no valid days — ignoring")
                    return@launch
                }

                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot backfill JugCoach history for '$habitName'")
                    return@launch
                }
                val uri = Uri.parse(fileUriString)

                val maxOne = habitName in settings.maxOneHabits
                val slotValues = buildMap<String, Map<java.time.LocalDate, Int>> {
                    put(habitName, days.mapValues { (_, d) -> if (maxOne) minOf(d.runs, 1) else d.runs })
                    put(secondaryValueKey(habitName), days.mapValues { (_, d) -> d.seconds })
                    put(secondaryValueSlotKey(habitName, 2), days.mapValues { (_, d) -> d.catches })
                    put(secondaryValueSlotKey(habitName, 3), days.mapValues { (_, d) -> d.secondsCatch })
                    put(secondaryValueSlotKey(habitName, 4), days.mapValues { (_, d) -> d.secondsDrop })
                    put(secondaryValueSlotKey(habitName, 5), days.mapValues { (_, d) -> d.catchesCatch })
                    put(secondaryValueSlotKey(habitName, 6), days.mapValues { (_, d) -> d.catchesDrop })
                }

                fileMutex.withLock {
                    habitsRepo.setHabitSlotsForDates(uri, appContext, slotValues)
                }
                HabitIncrementBus.emit(habitName)
                Log.i(TAG, "JugCoach backfill: set ${days.size} days for '$habitName'")

                // Update the Tasker stats file if today was among the backfilled days
                if (java.time.LocalDate.now() in days.keys) {
                    val taskerUri = settings.taskerFileUri
                    if (taskerUri.isNotEmpty()) {
                        writeTaskerFile(appContext, habitsRepo, uri, taskerUri, settings.habitDividers, settings.noPointsHabits)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to backfill JugCoach history for '$habitName': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** One day's aggregated metrics parsed from the backfill JSON. */
    private data class BackfillDay(
        val runs: Int,
        val seconds: Int,
        val catches: Int,
        val secondsCatch: Int,
        val secondsDrop: Int,
        val catchesCatch: Int,
        val catchesDrop: Int
    )

    /**
     * Parses the backfill payload into `LocalDate → day totals`. Dates that
     * don't parse as ISO yyyy-MM-dd and days where nothing at all happened
     * (no runs, no seconds, no catches) are skipped.
     */
    private fun parseBackfillJson(json: String): Map<java.time.LocalDate, BackfillDay> {
        val result = mutableMapOf<java.time.LocalDate, BackfillDay>()
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val date = try {
                    java.time.LocalDate.parse(key)
                } catch (e: Exception) {
                    Log.w(TAG, "Backfill: skipping unparseable date '$key'")
                    continue
                }
                val day = obj.optJSONObject(key) ?: continue
                val entry = BackfillDay(
                    runs = day.optInt("runs", 0).coerceAtLeast(0),
                    seconds = day.optInt("s", 0).coerceAtLeast(0),
                    catches = day.optInt("c", 0).coerceAtLeast(0),
                    secondsCatch = day.optInt("sc", 0).coerceAtLeast(0),
                    secondsDrop = day.optInt("sd", 0).coerceAtLeast(0),
                    catchesCatch = day.optInt("cc", 0).coerceAtLeast(0),
                    catchesDrop = day.optInt("cd", 0).coerceAtLeast(0)
                )
                if (entry.runs > 0 || entry.seconds > 0 || entry.catches > 0) {
                    result[date] = entry
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse backfill JSON: ${e.message}", e)
        }
        return result
    }

    /**
     * Writes today's habit totals to the Tasker relay txt file.
     * Mirrors the logic in HabitIncrementReceiver so external apps
     * (e.g. Tasker) see an up-to-date count immediately after an IPC increment.
     */
    private suspend fun writeTaskerFile(
        context: Context,
        habitsRepo: HabitsRepository,
        habitsUri: Uri,
        taskerUriString: String,
        dividers: Map<String, Int>,
        noPointsHabits: Set<String>
    ) {
        try {
            val db = habitsRepo.loadDatabase(habitsUri, context)
            // Shared helper excludes "Don't affect points" habits (e.g. Garmin imports)
            val content = buildTaskerStatsContent(db, dividers, noPointsHabits)

            val taskerUri = Uri.parse(taskerUriString)
            context.contentResolver.openOutputStream(taskerUri, "wt")?.use { stream ->
                stream.bufferedWriter().use { it.write(content) }
            }
            Log.i(TAG, "Tasker file updated")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Tasker file: ${e.message}")
        }
    }
}
