package com.example.tail.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * All-time personal-best records per exercise for weights habits, stored in
 * the app's internal storage.
 *
 * File: `files/weights_records.json`
 * Format:
 * ```json
 * {
 *   "Gym": {
 *     "machine\u0001Bench Press": {
 *       "bestWeightGrams": 82500,
 *       "bestWeightReps": 5,
 *       "bestWeightDate": "2026-09-11",
 *       "bestWeightLocation": "Gym Bellinzona",
 *       "bestReps": 12,
 *       "bestRepsGrams": 60000,
 *       "bestRepsDate": "2026-08-30",
 *       "bestRepsLocation": "Gym Bellinzona"
 *     }
 *   }
 * }
 * ```
 *
 * Why a sidecar: the habits DB keeps only AGGREGATED day slots — the weight
 * slot max-merges (a day's value is its heaviest set), while the reps slot
 * ACCUMULATES across the day. A single set's reps are therefore not
 * recoverable from the DB, and even the day's max single-set weight loses
 * the pairing weight×reps of that set. This sidecar keeps the all-time best
 * single-set weight AND the all-time best single-set reps per exercise,
 * each with the date (and location label when known) it was set — exactly
 * what the PR flash needs to tell the story of the old record.
 *
 * Records are keyed by habit name + type (machine/free) + exercise name
 * (case-insensitive on lookup). The habits-DB-derived PB (heaviest day
 * weight) is honored at read time via [RecordsFor.habitPbWeightGrams] so
 * logging done before this sidecar existed still counts as a beatable
 * standing record: a new set must beat BOTH the sidecar record and the
 * historical DB-derived PB to count as a new all-time best weight.
 */
class WeightsRecordsRepository(private val context: Context) {

    /** All-time best single-set weight for one exercise. */
    data class Record(
        /** Heaviest single-set weight ever logged, in grams (0 = none). */
        @SerializedName("bestWeightGrams") val bestWeightGrams: Int = 0,
        /** Reps done on that heaviest set. */
        @SerializedName("bestWeightReps") val bestWeightReps: Int = 0,
        /** Date the weight record was set, or null when none. */
        @SerializedName("bestWeightDate") val bestWeightDate: String? = null,
        /** Location label stored for that date, when known. */
        @SerializedName("bestWeightLocation") val bestWeightLocation: String? = null,
        /** Best single-set rep count ever logged (0 = none). */
        @SerializedName("bestReps") val bestReps: Int = 0,
        /** Weight used on that best-reps set, in grams (0 = none). */
        @SerializedName("bestRepsGrams") val bestRepsGrams: Int = 0,
        /** Date the reps record was set, or null when none. */
        @SerializedName("bestRepsDate") val bestRepsDate: String? = null,
        /** Location label stored for that date, when known. */
        @SerializedName("bestRepsLocation") val bestRepsLocation: String? = null
    )

    /**
     * Result of evaluating a set against the standing records: which
     * records (if any) the set beats, plus the full story of the old
     * records for the flash (date + location of the previous best).
     */
    data class PrCheck(
        /** True when the set beats the standing all-time WEIGHT record. */
        val weightPr: Boolean = false,
        /** True when the set beats the standing all-time REPS record. */
        val repsPr: Boolean = false,
        /** The standing (pre-set) record snapshot, for the flash's "old" line. */
        val old: Record = Record(),
        /** The set being evaluated (grams, reps). */
        val grams: Int = 0,
        val reps: Int = 0
    ) {
        val isPr: Boolean get() = weightPr || repsPr
    }

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    private val file: File get() = File(context.filesDir, FILE_NAME)

    companion object {
        const val FILE_NAME = "weights_records.json"

        /**
         * Separator joining the type key (machine/free) and the exercise
         * name inside the record key — an unprintable char that can never
         * appear in a typed exercise name.
         */
        internal const val KEY_SEP = '\u0001'

        /**
         * Process-wide mutex serialising ALL read-modify-write operations on
         * the file — every call site creates its own repository instance, so
         * the lock must be shared (same convention as WeightsExerciseRepository).
         */
        private val fileMutex = Mutex()

        /** Case-insensitive record key for one (habit, type, exercise). */
        fun recordKey(habitName: String, machine: Boolean, exerciseName: String): String =
            (if (machine) "machine" else "free") + KEY_SEP + exerciseName.trim().lowercase()
    }

    private val mapType =
        object : TypeToken<MutableMap<String, MutableMap<String, Record>>>() {}.type

    private suspend fun loadMutable(): MutableMap<String, MutableMap<String, Record>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) mutableMapOf()
                else gson.fromJson(file.reader(), mapType) ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }

    private suspend fun saveAll(data: MutableMap<String, MutableMap<String, Record>>) =
        withContext(Dispatchers.IO) {
            try {
                file.writer().use { w -> prettyGson.toJson(data, w) }
            } catch (_: Exception) {
                // Best-effort
            }
        }

    /**
     * Evaluates a set against the standing all-time records and, when it
     * beats one or both, updates the sidecar and returns the PR details.
     * The record update happens atomically under the same lock as the
     * evaluation so concurrent logs can never double-count a beat.
     *
     * [logDate] is the date the set was logged (today, or the selected
     * back-fill day) — it becomes the new record's date.
     *
     * [habitPbWeightGrams] is the habits-DB-derived heaviest DAY weight for
     * this exercise (from [getWeightsExerciseStats]-style joins); a new set
     * must also beat it to claim a weight PR — this keeps logging that
     * predates the sidecar beatable-but-honest.
     */
    suspend fun evaluateAndRecord(
        habitName: String,
        machine: Boolean,
        exerciseName: String,
        grams: Int,
        reps: Int,
        logDate: LocalDate,
        locationLabel: String?,
        habitPbWeightGrams: Int
    ): PrCheck {
        if (exerciseName.isBlank() || (grams <= 0 && reps <= 0)) return PrCheck()
        val key = recordKey(habitName, machine, exerciseName)
        fileMutex.withLock {
            val data = loadMutable()
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val old = habitMap[key] ?: Record()
            // Standing weight floor: the higher of the sidecar record and
            // the DB-derived historical day-max (pre-sidecar data).
            val standingWeight = maxOf(old.bestWeightGrams, habitPbWeightGrams)
            val weightPr = grams > 0 && grams > standingWeight
            val repsPr = reps > 0 && reps > old.bestReps

            val updated = if (weightPr || repsPr) {
                old.copy(
                    bestWeightGrams = if (weightPr) grams else old.bestWeightGrams,
                    bestWeightReps = if (weightPr) reps else old.bestWeightReps,
                    bestWeightDate = if (weightPr) dateString(logDate) else old.bestWeightDate,
                    bestWeightLocation = if (weightPr) locationLabel?.takeIf { it.isNotBlank() } else old.bestWeightLocation,
                    bestReps = if (repsPr) reps else old.bestReps,
                    bestRepsGrams = if (repsPr) grams else old.bestRepsGrams,
                    bestRepsDate = if (repsPr) dateString(logDate) else old.bestRepsDate,
                    bestRepsLocation = if (repsPr) locationLabel?.takeIf { it.isNotBlank() } else old.bestRepsLocation
                )
            } else old

            if (weightPr || repsPr) {
                // Case-insensitive key means "Bench press" and "Bench Press"
                // share one record — replace any same-text-different-case key.
                habitMap.keys.filter { it.equals(key, ignoreCase = true) }
                    .forEach { habitMap.remove(it) }
                habitMap[key] = updated
                saveAll(data)
            }
            return PrCheck(weightPr, repsPr, old, grams, reps)
        }
    }

    /** Loads ALL records (habit → record-key → record). Callers must not mutate. */
    suspend fun loadAll(): Map<String, Map<String, Record>> = loadMutable()

    /** Moves every record from [oldName] to [newName] (habit rename). */
    suspend fun renameHabit(oldName: String, newName: String) {
        if (oldName == newName) return
        fileMutex.withLock {
            val data = loadMutable()
            val moved = data[oldName]?.toMutableMap() ?: return@withLock
            data.remove(oldName)
            val existing = data[newName]?.toMutableMap() ?: mutableMapOf()
            for ((key, record) in moved) {
                // Merge: keep existing records under the new name; old ones fill gaps
                if (!existing.containsKey(key)) existing[key] = record
            }
            data[newName] = existing
            saveAll(data)
        }
    }
}

