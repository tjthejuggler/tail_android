package com.example.tail.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One day's record in a sleep-suite habit's data file.
 *
 * A **sleep-time** habit only ever writes the SLEEP half ([bed], [temp], [conditions]);
 * a **wake-time** habit only ever writes the WAKE half ([wake], [awakenings], [awakeMin],
 * [quality]). Every field is nullable so the two halves stay independent — Gson parses
 * absent keys as null and [merge] keeps the other half intact on overwrite.
 *
 * Minutes are "minutes since midnight of the entry's date" (0–1439), so a bedtime of
 * 23:30 on date D is 1410 and a wake-up of 07:15 on the SAME date key is 435. Sessions
 * spanning midnight are reconstructed by the consumer (duration = wake − bed, +1440
 * when non-positive).
 *
 * Temperature is stored as tenths of a degree (°C × 10) to stay integral.
 *
 * Quality is the perceived overall sleep quality on a 1–5 scale (5 = best).
 */
data class SleepRecord(
    /** Bed time — minutes since midnight of the entry date. Null = not set. */
    val bed: Int? = null,
    /** Room temperature in tenths of °C (e.g. 192 = 19.2 °C). Null = not set. */
    val temp: Int? = null,
    /** Free-text sleep conditions (blanket/fan/noise/…); powers the past-inputs suggestions. */
    val conditions: String? = null,
    /** Wake time — minutes since midnight of the entry date. Null = not set. */
    val wake: Int? = null,
    /** Number of awakenings during the night. Null = not set. */
    val awakenings: Int? = null,
    /** Total minutes spent awake during the night. Null = not set. */
    val awakeMin: Int? = null,
    /** Perceived overall sleep quality, 1–5. Null = not set. */
    val quality: Int? = null
) {
    /** Returns this record with every non-null field of [other] overriding it. */
    fun merge(other: SleepRecord): SleepRecord = SleepRecord(
        bed = other.bed ?: bed,
        temp = other.temp ?: temp,
        conditions = other.conditions ?: conditions,
        wake = other.wake ?: wake,
        awakenings = other.awakenings ?: awakenings,
        awakeMin = other.awakeMin ?: awakeMin,
        quality = other.quality ?: quality
    )
}

/**
 * Reads/writes per-habit sleep-suite records in the app's INTERNAL storage.
 *
 * File: `files/sleep_data.json`
 * Format:
 * ```json
 * {
 *   "Sleep":  { "2026-09-17": { "bed": 1410, "temp": 192, "conditions": "fan on" } },
 *   "Wake":   { "2026-09-18": { "wake": 435, "awakenings": 2, "awakeMin": 25, "quality": 4 } }
 * }
 * ```
 *
 * Same storage conventions as [SubtypeDataRepository]: internal-only (no SAF), pretty
 * printed, and every read-modify-write cycle serialised by a PROCESS-WIDE mutex because
 * callers construct their own repository instances.
 */
class SleepDataRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType =
        object : TypeToken<Map<String, Map<String, SleepRecord>>>() {}.type

    private val file: File get() = File(context.filesDir, "sleep_data.json")

    companion object {
        private const val TAG = "SleepDataRepo"

        /** Process-wide mutex serialising all read-modify-write cycles. */
        private val fileMutex = Mutex()
    }

    // ── Read operations ──────────────────────────────────────────────────────

    /** Loads the full internal store: habit → date → record. */
    suspend fun loadAll(): Map<String, Map<String, SleepRecord>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext emptyMap()
                val text = file.readText()
                if (text.isBlank()) return@withContext emptyMap()
                gson.fromJson(text, mapType) ?: emptyMap()
            } catch (e: Exception) {
                Log.w(TAG, "loadAll failed: ${e.message}")
                emptyMap()
            }
        }

    /** Loads the records for one habit: date → record. */
    suspend fun loadHabitData(habitName: String): Map<String, SleepRecord> =
        loadAll()[habitName] ?: emptyMap()

    /** Gets the record for a single date (empty record when absent). */
    suspend fun getRecord(habitName: String, dateStr: String): SleepRecord =
        loadHabitData(habitName)[dateStr] ?: SleepRecord()

    /**
     * All distinct non-blank conditions strings ever recorded for [habitName],
     * MOST RECENT FIRST — powers the "options from past inputs" suggestion list.
     */
    suspend fun loadConditionsHistory(habitName: String): List<String> {
        val seen = LinkedHashSet<String>()
        loadHabitData(habitName).toSortedMap(reverseOrder()).values.forEach { rec ->
            rec.conditions?.trim()?.takeIf { it.isNotEmpty() }?.let { seen.add(it) }
        }
        return seen.toList()
    }

    // ── Write operations ─────────────────────────────────────────────────────

    /**
     * Atomically merges [patch] into the record for [habitName]/[dateStr]:
     * fields set in [patch] win, unset (null) fields keep their stored value.
     * Pass an empty patch to keep the record unchanged.
     */
    suspend fun mergeRecord(habitName: String, dateStr: String, patch: SleepRecord) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val habitData = all[habitName]?.toMutableMap() ?: mutableMapOf()
            val existing = habitData[dateStr] ?: SleepRecord()
            habitData[dateStr] = existing.merge(patch)
            all[habitName] = habitData
            saveAll(all)
        }
    }

    /** Replaces the stored record for [habitName]/[dateStr]. */
    suspend fun saveRecord(habitName: String, dateStr: String, record: SleepRecord) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val habitData = all[habitName]?.toMutableMap() ?: mutableMapOf()
            habitData[dateStr] = record
            all[habitName] = habitData
            saveAll(all)
        }
    }

    /**
     * Moves all sleep data from [oldName] to [newName] so it survives a habit
     * rename. No-op if [oldName] has no stored data.
     */
    suspend fun renameHabit(oldName: String, newName: String) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val data = all.remove(oldName) ?: return@withLock
            all[newName] = data
            saveAll(all)
        }
    }

    /** Removes all sleep data for [habitName] (habit deletion cleanup). */
    suspend fun deleteHabit(habitName: String) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            if (all.remove(habitName) != null) saveAll(all)
        }
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    /** Writes the full store to disk (pretty-printed, sorted). Caller holds [fileMutex]. */
    private suspend fun saveAll(data: Map<String, Map<String, SleepRecord>>) =
        withContext(Dispatchers.IO) {
            try {
                val sorted = data.toSortedMap().mapValues { (_, dates) ->
                    dates.toSortedMap()
                }
                file.writeText(prettyGson.toJson(sorted))
            } catch (e: Exception) {
                Log.w(TAG, "saveAll failed: ${e.message}")
            }
        }
}
