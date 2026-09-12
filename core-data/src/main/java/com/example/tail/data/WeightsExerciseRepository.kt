package com.example.tail.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Per-day exercise/machine names for weights habits, stored in the app's
 * internal storage.
 *
 * File: `files/weights_exercise_names.json`
 * Format:
 * ```json
 * {
 *   "Gym": {
 *     "2026-09-11": { "machine": "Bench Press", "free": "Dumbbell Curl" },
 *     "2026-09-12": { "free": "Squat" }
 *   }
 * }
 * ```
 *
 * The habits DB only keeps aggregated day slots (machine/free weight + reps),
 * so the exercise name typed alongside a log entry would otherwise be lost.
 * This sidecar records WHICH exercise each day's machine/free slot belongs
 * to. It powers:
 *  - the weight popup's PB / last-set display (per exercise name),
 *  - the graph's exercise-type filter and the tooltip's exercise line,
 *  - the edit screen showing the exact name logged for a given day.
 *
 * Purely supplemental metadata — it never affects habit counts or slots.
 */
class WeightsExerciseRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    private val file: File get() = File(context.filesDir, FILE_NAME)

    companion object {
        const val FILE_NAME = "weights_exercise_names.json"
        /** Day-map key for the machine-slot exercise name. */
        const val KEY_MACHINE = "machine"
        /** Day-map key for the free-slot exercise name. */
        const val KEY_FREE = "free"

        /**
         * Process-wide mutex serialising ALL read-modify-write operations on
         * the file — every call site creates its own repository instance, so
         * the lock must be shared (same convention as HabitTimestampRepository).
         */
        private val fileMutex = Mutex()
    }

    private val mapType =
        object : TypeToken<MutableMap<String, MutableMap<String, MutableMap<String, String>>>>() {}.type

    private suspend fun loadMutable(): MutableMap<String, MutableMap<String, MutableMap<String, String>>> =
        withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) mutableMapOf()
            else gson.fromJson(file.reader(), mapType) ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private suspend fun saveAll(data: MutableMap<String, MutableMap<String, MutableMap<String, String>>>) =
        withContext(Dispatchers.IO) {
        try {
            file.writer().use { w -> prettyGson.toJson(data, w) }
        } catch (_: Exception) {
            // Best-effort
        }
    }

    /** Full name database (habit → date → type → name). Callers must not mutate. */
    suspend fun loadAll(): Map<String, Map<String, Map<String, String>>> = loadMutable()

    /**
     * Sets (or, when [name] is blank, clears) the exercise name for one
     * slot (machine/free) of [habitName] on [date].
     */
    suspend fun setExerciseName(
        habitName: String,
        date: LocalDate,
        machine: Boolean,
        name: String
    ) {
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val key = if (machine) KEY_MACHINE else KEY_FREE
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val dayMap = habitMap.getOrPut(dateStr) { mutableMapOf() }
            if (name.isBlank()) dayMap.remove(key) else dayMap[key] = name.trim()
            // Prune empty containers so the file stays minimal
            if (dayMap.isEmpty()) habitMap.remove(dateStr)
            if (habitMap.isEmpty()) data.remove(habitName)
            saveAll(data)
        }
    }

    /** Removes ALL exercise names recorded for [habitName] on [date]. */
    suspend fun removeDay(habitName: String, date: LocalDate) {
        fileMutex.withLock {
            val data = loadMutable()
            val habitMap = data[habitName]?.toMutableMap() ?: return@withLock
            habitMap.remove(dateString(date))
            if (habitMap.isEmpty()) data.remove(habitName) else data[habitName] = habitMap
            saveAll(data)
        }
    }

    /** Moves every recorded name from [oldName] to [newName] (habit rename). */
    suspend fun renameHabit(oldName: String, newName: String) {
        if (oldName == newName) return
        fileMutex.withLock {
            val data = loadMutable()
            val moved = data[oldName]?.toMutableMap() ?: return@withLock
            data.remove(oldName)
            // Merge: keep existing entries under the new name, old entries fill gaps
            val existing = data[newName]?.toMutableMap() ?: mutableMapOf()
            for ((date, dayMap) in moved) {
                existing.merge(date, dayMap) { keep, _ -> keep }
            }
            data[newName] = existing
            saveAll(data)
        }
    }
}
