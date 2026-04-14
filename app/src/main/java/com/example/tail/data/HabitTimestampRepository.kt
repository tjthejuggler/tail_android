package com.example.tail.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Stores per-habit increment timestamps in the app's internal storage.
 *
 * File: `files/habit_timestamps.json`
 * Format:
 * ```json
 * {
 *   "Habit Name": {
 *     "2026-04-13": ["17:30:45", "18:15:22"],
 *     "2026-04-12": ["09:00:00"]
 *   }
 * }
 * ```
 *
 * This is purely supplemental data — it does NOT affect the main habit counts
 * stored in habitsdb.txt. Timestamps are "extra metadata" for future analysis.
 */
class HabitTimestampRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType = object : TypeToken<MutableMap<String, MutableMap<String, MutableList<String>>>>() {}.type

    private val file: File get() = File(context.filesDir, "habit_timestamps.json")

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

        /** Returns a time string for "right now" (HH:mm:ss). */
        fun nowTime(): String = LocalTime.now().format(TIME_FMT)
    }

    /** Load the full timestamp database from disk. */
    suspend fun loadAll(): Map<String, Map<String, List<String>>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext emptyMap()
                val text = file.readText()
                if (text.isBlank()) return@withContext emptyMap()
                val parsed: Map<String, Map<String, List<String>>>? = gson.fromJson(text, mapType)
                parsed ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** Save the full timestamp database to disk. */
    private suspend fun saveAll(data: Map<String, Map<String, List<String>>>) =
        withContext(Dispatchers.IO) {
            try {
                val json = prettyGson.toJson(data)
                file.writeText(json)
            } catch (_: Exception) {
                // Best-effort
            }
        }

    /**
     * Record a timestamp for [habitName] on [date] at [time].
     * Defaults to today and now.
     */
    suspend fun addTimestamp(
        habitName: String,
        date: LocalDate = LocalDate.now(),
        time: String = nowTime()
    ) {
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data.getOrPut(habitName) { mutableMapOf() }
        val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
        dayList.add(time)
        dayList.sort()
        saveAll(data)
    }

    /**
     * Record multiple timestamps at once (e.g. for batch increments).
     * Each call adds [count] timestamps all at the current time.
     */
    suspend fun addTimestamps(
        habitName: String,
        count: Int,
        date: LocalDate = LocalDate.now(),
        time: String = nowTime()
    ) {
        if (count <= 0) return
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data.getOrPut(habitName) { mutableMapOf() }
        val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
        repeat(count) { dayList.add(time) }
        dayList.sort()
        saveAll(data)
    }

    /**
     * Get all timestamps for [habitName] on [date].
     * Returns a sorted list of "HH:mm:ss" strings.
     */
    suspend fun getTimestampsForDay(
        habitName: String,
        date: LocalDate = LocalDate.now()
    ): List<String> {
        val data = loadAll()
        val dateStr = dateString(date)
        return data[habitName]?.get(dateStr)?.sorted() ?: emptyList()
    }

    /**
     * Replace all timestamps for [habitName] on [date] with [timestamps].
     * Used by the timestamp editor dialog.
     */
    suspend fun setTimestampsForDay(
        habitName: String,
        date: LocalDate,
        timestamps: List<String>
    ) {
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data.getOrPut(habitName) { mutableMapOf() }
        if (timestamps.isEmpty()) {
            habitMap.remove(dateStr)
        } else {
            habitMap[dateStr] = timestamps.sorted().toMutableList()
        }
        // Clean up empty habit entries
        if (habitMap.isEmpty()) {
            data.remove(habitName)
        }
        saveAll(data)
    }

    /** Delete a single timestamp at [index] for [habitName] on [date]. */
    suspend fun deleteTimestamp(
        habitName: String,
        date: LocalDate,
        index: Int
    ): List<String> {
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data[habitName] ?: return emptyList()
        val dayList = habitMap[dateStr] ?: return emptyList()
        if (index in dayList.indices) {
            dayList.removeAt(index)
        }
        if (dayList.isEmpty()) {
            habitMap.remove(dateStr)
        }
        if (habitMap.isEmpty()) {
            data.remove(habitName)
        }
        saveAll(data)
        return dayList.sorted()
    }

    /** Update a single timestamp at [index] for [habitName] on [date]. */
    suspend fun updateTimestamp(
        habitName: String,
        date: LocalDate,
        index: Int,
        newTime: String
    ): List<String> {
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data[habitName] ?: return emptyList()
        val dayList = habitMap[dateStr] ?: return emptyList()
        if (index in dayList.indices) {
            dayList[index] = newTime
        }
        dayList.sort()
        saveAll(data)
        return dayList.toList()
    }

    /**
     * Update the last (most recent) timestamp for [habitName] on [date] to [newTime].
     * Returns the updated list of timestamps, or empty list if none exist.
     */
    suspend fun updateLastTimestamp(
        habitName: String,
        date: LocalDate,
        newTime: String
    ): List<String> {
        val data = loadMutable()
        val dateStr = dateString(date)
        val habitMap = data[habitName] ?: return emptyList()
        val dayList = habitMap[dateStr] ?: return emptyList()
        if (dayList.isEmpty()) return emptyList()
        // The last timestamp is the one most recently added (last in sorted order)
        dayList[dayList.lastIndex] = newTime
        dayList.sort()
        saveAll(data)
        return dayList.toList()
    }

    private suspend fun loadMutable(): MutableMap<String, MutableMap<String, MutableList<String>>> =
        withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext mutableMapOf()
                val text = file.readText()
                if (text.isBlank()) return@withContext mutableMapOf()
                gson.fromJson(text, mapType) ?: mutableMapOf()
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
}
