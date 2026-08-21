package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate

private const val TAG = "HabitValueSetReceiver"

/**
 * **Protocol v2** — BroadcastReceiver that handles the retroactive backfill
 * of past session minutes from WAGS.
 *
 * Unlike [HabitIncrementReceiver] which *adds* to today's count, this receiver
 * **SETS** (replaces) the stored value for each date provided in the JSON
 * payload. This makes the operation idempotent: running the backfill multiple
 * times produces the same result.
 *
 * Action:  `com.example.tail.ACTION_SET_HABIT_VALUES`
 * Extras:
 *  - `EXTRA_HABIT_ID`    — habit name (String)
 *  - `EXTRA_VALUES_JSON` — JSON object: `{"yyyy-MM-dd": <minutes:Int>, ...}`
 *
 * Security: declared in the manifest with `android:permission` pointing to the
 * `com.example.tail.permission.TAIL_INTEGRATION` signature permission, so only
 * apps signed with the same keystore can send this broadcast.
 *
 * **Concurrency:** WAGS sends separate broadcasts for each habit slot (e.g.
 * resonance breathing, then meditation) in rapid succession. A shared [Mutex]
 * serialises the processing so that the second broadcast's read-modify-write
 * cycle always sees the first broadcast's committed changes. Without this,
 * the two concurrent coroutines would race on the same JSON file and the last
 * writer would silently overwrite the other's changes.
 */
class HabitValueSetReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"

        /**
         * Serialises concurrent broadcasts so that each SET operation sees the
         * committed result of the previous one. Without this, two broadcasts
         * arriving simultaneously (e.g. resonance + meditation backfill) would
         * race on the same file and one would silently lose its changes.
         */
        private val fileMutex = Mutex()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_HABIT_VALUES) return

        val habitName = intent.getStringExtra(EXTRA_HABIT_ID)
        val json = intent.getStringExtra(EXTRA_VALUES_JSON)

        if (habitName.isNullOrBlank() || json.isNullOrBlank()) {
            Log.w(TAG, "Received $ACTION_SET_HABIT_VALUES with missing EXTRA_HABIT_ID or EXTRA_VALUES_JSON — ignoring")
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot set values for '$habitName'")
                    return@launch
                }

                val uri = Uri.parse(fileUriString)

                // Parse the JSON: {"2026-01-15": 10, "2026-01-16": 5, ...}
                val rawDateValues = parseDateMinuteJson(json)
                if (rawDateValues.isEmpty()) {
                    Log.w(TAG, "Parsed JSON contained no valid date→minutes entries — ignoring")
                    return@launch
                }

                // Convert date strings to LocalDate, skipping unparseable ones
                val dateValues = mutableMapOf<LocalDate, Int>()
                for ((dateStr, minutes) in rawDateValues) {
                    val date = try {
                        LocalDate.parse(dateStr)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping unparseable date '$dateStr': ${e.message}")
                        continue
                    }
                    dateValues[date] = minutes
                }

                if (dateValues.isEmpty()) {
                    Log.w(TAG, "No valid dates after parsing — ignoring")
                    return@launch
                }

                // SET all dates in a single read-modify-write, serialised by
                // the mutex so concurrent broadcasts don't overwrite each other.
                fileMutex.withLock {
                    habitsRepo.setHabitValuesForDates(uri, appContext, habitName, dateValues)
                }

                // Notify the UI so it reloads
                HabitIncrementBus.emit(habitName)
                Log.i(TAG, "Set ${dateValues.size} dates for habit '$habitName'")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to set habit values for '$habitName': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Parses a JSON object of `{"yyyy-MM-dd": <Int>}` entries into a map.
     * A value of **0** is valid and means "clear this date's entry" (Skin
     * Tracker sends 0 for days whose photos were all deleted). Negative
     * values are rejected.
     */
    private fun parseDateMinuteJson(json: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) {
                val minutes = obj.optInt(key, 0)
                if (minutes >= 0) {
                    result[key] = minutes
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
        }
        return result
    }

}
