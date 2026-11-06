package com.example.tail.data

import android.content.Context
import java.io.File
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ONE logged set of a weights habit, exactly as entered in the popup form.
 *
 * The habits DB keeps only the day-aggregated slots (heaviest weight of the
 * day + the day's total reps); this sidecar preserves the individual sets so
 * the UI can show/edit each set separately.
 */
data class WeightsSet(
    /** True = machine slot, false = free-weights slot. */
    val machine: Boolean,
    /** Exercise / machine name as typed ("" = unnamed). */
    val exercise: String = "",
    /** Weight in grams (canonical storage unit; 0 = none entered). */
    val weightGrams: Int = 0,
    /** Reps of this set (0 = none entered). */
    val reps: Int = 0,
    /** Time-of-day of the increment this set belongs to ("HH:mm:ss"). */
    val time: String = ""
)

/**
 * Per-set log for weights habits, stored in the app's internal storage.
 *
 * File: `files/weights_sets.json`
 * Format:
 * ```json
 * {
 *   "Gym": {
 *     "2026-11-05": [
 *       { "machine": true, "exercise": "Bench Press", "weightGrams": 60000, "reps": 8, "time": "18:32:11" },
 *       { "machine": true, "exercise": "Bench Press", "weightGrams": 60000, "reps": 6, "time": "18:38:40" }
 *     ]
 *   }
 * }
 * ```
 *
 * Every popup-form submission (saveWeightsEntry) appends ONE set here, in
 * addition to the day-aggregated DB slots (which stay the source for the
 * graphs, PB joins and the companion API). Days logged before this sidecar
 * existed simply have no set list — the UI falls back to the aggregated
 * day editor for those.
 */
class WeightsSetsRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    private val file get() = File(context.filesDir, FILE_NAME)

    companion object {
        const val FILE_NAME = "weights_sets.json"

        /**
         * Process-wide mutex serialising ALL read-modify-write operations on
         * the file — every call site creates its own repository instance, so
         * the lock must be shared (same convention as HabitTimestampRepository).
         */
        private val fileMutex = Mutex()
    }

    private val mapType =
        object : TypeToken<MutableMap<String, MutableMap<String, MutableList<WeightsSet>>>>() {}.type

    private suspend fun loadMutable():
        MutableMap<String, MutableMap<String, MutableList<WeightsSet>>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) mutableMapOf()
                else gson.fromJson(file.reader(), mapType) ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }

    private suspend fun saveAll(
        data: MutableMap<String, MutableMap<String, MutableList<WeightsSet>>>
    ) = withContext(Dispatchers.IO) {
        try {
            file.writer().use { w -> prettyGson.toJson(data, w) }
        } catch (_: Exception) {
            // Best-effort
        }
    }

    /** Full set log (habit → date → sets, chronological). Callers must not mutate. */
    suspend fun loadAll(): Map<String, Map<String, List<WeightsSet>>> = loadMutable()

    /** The day's sets, or an empty list when none are recorded for [date]. */
    suspend fun getSetsForDate(habitName: String, date: LocalDate): List<WeightsSet> =
        loadMutable()[habitName]?.get(dateString(date)) ?: emptyList()

    /** Appends one set to [habitName]'s log for [date]. */
    suspend fun addSet(habitName: String, date: LocalDate, set: WeightsSet) {
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            data.getOrPut(habitName) { mutableMapOf() }
                .getOrPut(dateStr) { mutableListOf() }
                .add(set)
            saveAll(data)
        }
    }

    /** Replaces the set stamped [time] with [newSet]. No-op when not found. */
    suspend fun updateSet(habitName: String, date: LocalDate, time: String, newSet: WeightsSet) {
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val list = data[habitName]?.get(dateStr) ?: return@withLock
            val idx = list.indexOfFirst { it.time == time }
            if (idx >= 0) {
                list[idx] = newSet
                saveAll(data)
            }
        }
    }

    /** Removes the set stamped [time]. Returns true when one was removed. */
    suspend fun removeSet(habitName: String, date: LocalDate, time: String): Boolean {
        var removed = false
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val list = data[habitName]?.get(dateStr) ?: return@withLock
            removed = list.removeAll { it.time == time }
            if (removed) {
                if (list.isEmpty()) data[habitName]?.remove(dateStr)
                if (data[habitName]?.isEmpty() == true) data.remove(habitName)
                saveAll(data)
            }
        }
        return removed
    }

    /** Removes ALL sets recorded for [habitName] on [date]. */
    suspend fun removeDay(habitName: String, date: LocalDate) {
        fileMutex.withLock {
            val data = loadMutable()
            val habitMap = data[habitName]?.toMutableMap() ?: return@withLock
            habitMap.remove(dateString(date))
            if (habitMap.isEmpty()) data.remove(habitName) else data[habitName] = habitMap
            saveAll(data)
        }
    }

    /** Moves every recorded set from [oldName] to [newName] (habit rename). */
    suspend fun renameHabit(oldName: String, newName: String) {
        if (oldName == newName) return
        fileMutex.withLock {
            val data = loadMutable()
            val moved = data[oldName]?.toMutableMap() ?: return@withLock
            data.remove(oldName)
            // Merge: keep existing entries under the new name, old entries fill gaps
            val existing = data[newName]?.toMutableMap() ?: mutableMapOf()
            for ((date, sets) in moved) {
                existing.merge(date, sets) { keep, add ->
                    (keep + add).toMutableList()
                }
            }
            data[newName] = existing
            saveAll(data)
        }
    }
}
