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
    private val minutesMapType = object : TypeToken<MutableMap<String, MutableMap<String, MutableMap<String, Int>>>>() {}.type

    private val file: File get() = File(context.filesDir, "habit_timestamps.json")
    private val minutesFile: File get() = File(context.filesDir, "habit_timestamp_minutes.json")

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

        /**
         * Process-wide mutex serialising ALL read-modify-write operations on the
         * timestamp file.  Because every call-site (ViewModel, voice services,
         * widget provider, IPC receiver) creates its own repository instance,
         * the mutex MUST live in the companion object so that concurrent
         * instances still serialise correctly.  Without this, two overlapping
         * addTimestamp() calls can each load the same snapshot, add their own
         * entry, and save — the second save silently overwrites the first,
         * losing a timestamp.
         */
        private val fileMutex = Mutex()

        /**
         * Process-wide parse cache for the timestamp file. [loadAll] is called
         * in hot loops — once per date during movie-timestamp reconciliation
         * (hundreds of full JSON parses on app open) and once per day switch
         * on the schedule screen — so re-reading and re-parsing the whole file
         * each time dominated load times. The snapshot is keyed on the file's
         * (lastModified, length): two stat() calls, effectively free. Any
         * writer — this class or anything else touching the file — changes
         * those stamps and the cache invalidates itself automatically.
         */
        private class Snapshot(
            val lastModified: Long,
            val length: Long,
            val data: Map<String, Map<String, List<String>>>
        )

        @Volatile
        private var cachedSnapshot: Snapshot? = null

        /** Returns a time string for "right now" (HH:mm:ss). */
        fun nowTime(): String = LocalTime.now().format(TIME_FMT)
    }

    /** Current (lastModified, length) stamps of the file — two stat() calls. */
    private fun fileStamps(): Pair<Long, Long> =
        if (file.exists()) file.lastModified() to file.length() else 0L to 0L

    /**
     * Returns the parsed timestamp database, from the in-memory snapshot
     * cache when the file is unchanged since the last parse/read, else fresh
     * from disk (refreshing the cache). The returned map is shared with the
     * cache and MUST be treated as read-only; all mutating paths go through
     * [loadMutable], which deep-copies.
     */
    private suspend fun readSnapshot(): Map<String, Map<String, List<String>>> =
        withContext(Dispatchers.IO) {
            val (mtime, len) = fileStamps()
            cachedSnapshot?.let { snap ->
                if (snap.lastModified == mtime && snap.length == len) {
                    return@withContext snap.data
                }
            }
            val parsed: Map<String, Map<String, List<String>>> = try {
                if (!file.exists()) emptyMap()
                else gson.fromJson(file.readText(), mapType) ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            cachedSnapshot = Snapshot(mtime, len, parsed)
            parsed
        }

    /** Load the full timestamp database (cached — see [readSnapshot]). */
    suspend fun loadAll(): Map<String, Map<String, List<String>>> = readSnapshot()

    /**
     * Save the full timestamp database to disk. Write-through: on success the
     * snapshot cache is refreshed with a deep copy of [data] under the file's
     * post-write stamps, so the next read skips the disk parse. The copy is
     * deliberate — some callers keep mutating their map after saving.
     */
    private suspend fun saveAll(data: Map<String, Map<String, List<String>>>) =
        withContext(Dispatchers.IO) {
            var saved = false
            try {
                val json = prettyGson.toJson(data)
                file.writeText(json)
                saved = true
            } catch (_: Exception) {
                // Best-effort
            }
            if (saved) {
                val (mtime, len) = fileStamps()
                cachedSnapshot = Snapshot(mtime, len, deepCopy(data))
            }
        }

    /** Deep copy so cached/shared structures are never aliased by a mutation. */
    private fun deepCopy(
        data: Map<String, Map<String, List<String>>>
    ): MutableMap<String, MutableMap<String, MutableList<String>>> {
        val copy = mutableMapOf<String, MutableMap<String, MutableList<String>>>()
        for ((habit, days) in data) {
            val daysCopy = mutableMapOf<String, MutableList<String>>()
            for ((date, times) in days) daysCopy[date] = times.toMutableList()
            copy[habit] = daysCopy
        }
        return copy
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
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
            dayList.add(time)
            dayList.sort()
            saveAll(data)
        }
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
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
            repeat(count) { dayList.add(time) }
            dayList.sort()
            saveAll(data)
        }
    }

    /**
     * Record multiple timestamps at once, each with its OWN time string
     * (e.g. one stamp per chess game at that game's actual end time).
     * Single mutex/file cycle, unlike looping [addTimestamp].
     */
    suspend fun addTimestampsAt(
        habitName: String,
        date: LocalDate,
        times: List<String>
    ) {
        if (times.isEmpty()) return
        fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
            dayList.addAll(times)
            dayList.sort()
            saveAll(data)
        }
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

    // ─────────────────────────────────────────────────────────────────────
    // Per-timestamp minutes (minutes-primary habits)
    //
    // The timestamp file stores ONE "HH:mm:ss" string per increment unit
    // (a session for timer-fed habits), so a timer session's minutes live
    // only in the day total of the habits DB. This sidecar file records the
    // minutes contributed AT each timestamp:
    // { "Habit": { "2026-04-13": { "17:30:45": 25 } } }
    // It is read by the timestamp editor to show/edit per-timestamp minutes
    // for minutes-primary habits; absent entries fall back to the group's
    // unit count (manual +N increments store one unit per minute).
    // ─────────────────────────────────────────────────────────────────────

    /** Minutes recorded at each timestamp for [habitName] on [date] (`time -> minutes`). */
    suspend fun getMinutesForDay(
        habitName: String,
        date: LocalDate = LocalDate.now()
    ): Map<String, Int> {
        val data = loadMinutesMutable()
        return data[habitName]?.get(dateString(date))?.toMap() ?: emptyMap()
    }

    /**
     * Records that [minutes] were contributed at [time] for [habitName] on
     * [date], ADDING to any minutes already recorded at that time (two timer
     * sessions ending within the same second). A result ≤ 0 removes the entry.
     */
    suspend fun addMinutesAtTime(
        habitName: String,
        date: LocalDate,
        time: String,
        minutes: Int
    ) {
        if (minutes == 0) return
        fileMutex.withLock {
            val data = loadMinutesMutable()
            val day = data.getOrPut(habitName) { mutableMapOf() }
                .getOrPut(dateString(date)) { mutableMapOf() }
            val new = (day[time] ?: 0) + minutes
            if (new > 0) day[time] = new else day.remove(time)
            pruneEmptyMinutesEntries(data)
            saveMinutes(data)
        }
    }

    /**
     * SETS the minutes contributed at [time] for [habitName] on [date] (the
     * timestamp editor's per-timestamp minutes editor). A value ≤ 0 removes
     * the entry, restoring the unit-count fallback. Returns the updated
     * `time -> minutes` map for the day.
     */
    suspend fun setMinutesAtTime(
        habitName: String,
        date: LocalDate,
        time: String,
        minutes: Int
    ): Map<String, Int> {
        fileMutex.withLock {
            val data = loadMinutesMutable()
            val day = data.getOrPut(habitName) { mutableMapOf() }
                .getOrPut(dateString(date)) { mutableMapOf() }
            if (minutes > 0) day[time] = minutes else day.remove(time)
            pruneEmptyMinutesEntries(data)
            saveMinutes(data)
            return data[habitName]?.get(dateString(date))?.toMap() ?: emptyMap()
        }
    }

    private fun pruneEmptyMinutesEntries(data: MutableMap<String, MutableMap<String, MutableMap<String, Int>>>) {
        for (habit in data.keys.toList()) {
            val days = data[habit] ?: continue
            days.entries.removeAll { it.value.isEmpty() }
            if (days.isEmpty()) data.remove(habit)
        }
    }

    private suspend fun loadMinutesMutable():
        MutableMap<String, MutableMap<String, MutableMap<String, Int>>> =
        withContext(Dispatchers.IO) {
            // Always called under fileMutex. The minutes sidecar is tiny and
            // rarely read, so a plain parse (no snapshot cache) suffices.
            try {
                if (!minutesFile.exists()) mutableMapOf()
                else gson.fromJson(minutesFile.readText(), minutesMapType) ?: mutableMapOf()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }

    private suspend fun saveMinutes(data: Map<String, Map<String, Map<String, Int>>>) {
        withContext(Dispatchers.IO) {
            try {
                if (data.isEmpty()) minutesFile.delete() else prettyGson.toJson(data).let(minutesFile::writeText)
            } catch (_: Exception) {
                // Best-effort
            }
        }
    }

    /**
     * Synchronously returns a map of `dateStr -> timestamp count` for [habitName].
     *
     * This bypasses the coroutine-based [loadAll] so it can be called from a
     * Compose `remember` block for instant previews (e.g. showing how many past
     * days would be restored when disabling "1 max"). The file is local internal
     * storage so the synchronous read is fast.
     */
    fun getTimestampCountsForHabitSync(habitName: String): Map<String, Int> {
        return try {
            if (!file.exists()) return emptyMap()
            val text = file.readText()
            if (text.isBlank()) return emptyMap()
            val parsed: Map<String, Map<String, List<String>>>? = gson.fromJson(text, mapType)
            val habitTs = parsed?.get(habitName) ?: return emptyMap()
            habitTs.mapValues { it.value.size }
        } catch (e: Exception) {
            emptyMap()
        }
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
        fileMutex.withLock {
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
            // SET semantics: drop per-timestamp minutes recorded at times
            // that no longer exist (Inuit backfill, chess trim, movie sync).
            val minutesData = loadMinutesMutable()
            val dayMinutes = minutesData[habitName]?.get(dateStr)
            if (dayMinutes != null) {
                val keep = timestamps.toSet()
                dayMinutes.entries.removeAll { it.key !in keep }
                if (dayMinutes.isEmpty()) {
                    minutesData[habitName]?.remove(dateStr)
                }
                pruneEmptyMinutesEntries(minutesData)
                saveMinutes(minutesData)
            }
        }
    }

    /** Delete a single timestamp at [index] for [habitName] on [date]. */
    suspend fun deleteTimestamp(
        habitName: String,
        date: LocalDate,
        index: Int
    ): List<String> {
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
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
            dayList.sorted()
        }
    }

    /** Update a single timestamp at [index] for [habitName] on [date]. */
    suspend fun updateTimestamp(
        habitName: String,
        date: LocalDate,
        index: Int,
        newTime: String
    ): List<String> {
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
            if (index in dayList.indices) {
                dayList[index] = newTime
            }
            dayList.sort()
            saveAll(data)
            dayList.toList()
        }
    }

    /**
     * Updates ALL timestamps equal to [oldTime] for [habitName] on [date] to
     * [newTime] (multi-increments at one moment are stored as duplicate time
     * strings — they move as a group). Returns the updated day list.
     */
    suspend fun updateTimestampsAtTime(
        habitName: String,
        date: LocalDate,
        oldTime: String,
        newTime: String
    ): List<String> {
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
            if (oldTime in dayList) {
                habitMap[dateStr] = dayList.map { if (it == oldTime) newTime else it }.toMutableList()
                habitMap[dateStr]!!.sort()
                saveAll(data)
                // The group's per-timestamp minutes move with it (merged with
                // any minutes already recorded at the destination time).
                val minutesData = loadMinutesMutable()
                val dayMinutes = minutesData.getOrPut(habitName) { mutableMapOf() }
                    .getOrPut(dateStr) { mutableMapOf() }
                dayMinutes.remove(oldTime)?.let { moving ->
                    dayMinutes[newTime] = (dayMinutes[newTime] ?: 0) + moving
                }
                pruneEmptyMinutesEntries(minutesData)
                saveMinutes(minutesData)
                habitMap[dateStr]!!.toList()
            } else {
                dayList.toList()
            }
        }
    }

    /**
     * Deletes ALL timestamps equal to [time] for [habitName] on [date].
     * Returns the updated day list.
     */
    suspend fun deleteTimestampsAtTime(
        habitName: String,
        date: LocalDate,
        time: String
    ): List<String> {
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
            habitMap[dateStr] = dayList.filter { it != time }.toMutableList()
            if (habitMap[dateStr]!!.isEmpty()) {
                habitMap.remove(dateStr)
            }
            if (habitMap.isEmpty()) {
                data.remove(habitName)
            }
            saveAll(data)
            // The group's per-timestamp minutes go with it.
            val minutesData = loadMinutesMutable()
            minutesData[habitName]?.get(dateStr)?.remove(time)
            pruneEmptyMinutesEntries(minutesData)
            saveMinutes(minutesData)
            habitMap[dateStr]?.sorted() ?: emptyList()
        }
    }

    /**
     * Ensures exactly [count] timestamps equal to [time] exist for [habitName]
     * on [date] — i.e. re-sizes a same-moment increment group. Adding units
     * appends duplicates of [time]; removing units drops duplicates. Returns
     * the updated day list.
     */
    suspend fun setTimestampCountAtTime(
        habitName: String,
        date: LocalDate,
        time: String,
        count: Int
    ): List<String> {
        if (count < 0) return getTimestampsForDay(habitName, date)
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data.getOrPut(habitName) { mutableMapOf() }
            val dayList = habitMap.getOrPut(dateStr) { mutableListOf() }
            val others = dayList.filter { it != time }
            val group = List(count) { time }
            val merged = (others + group).toMutableList()
            merged.sort()
            if (merged.isEmpty()) {
                habitMap.remove(dateStr)
            } else {
                habitMap[dateStr] = merged
            }
            if (habitMap.isEmpty()) {
                data.remove(habitName)
            }
            saveAll(data)
            habitMap[dateStr]?.sorted() ?: emptyList()
        }
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
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
            if (dayList.isEmpty()) return@withLock emptyList()
            // The last timestamp is the one most recently added (last in sorted order)
            dayList[dayList.lastIndex] = newTime
            dayList.sort()
            saveAll(data)
            dayList.toList()
        }
    }

    /**
     * Delete the last (most recent) timestamp for [habitName] on [date].
     * Used by the "Timeless" button on the increment toast to remove a just-recorded timestamp.
     * Returns the updated list of timestamps, or empty list if none exist.
     */
    suspend fun deleteLastTimestamp(
        habitName: String,
        date: LocalDate
    ): List<String> {
        return fileMutex.withLock {
            val data = loadMutable()
            val dateStr = dateString(date)
            val habitMap = data[habitName] ?: return@withLock emptyList()
            val dayList = habitMap[dateStr] ?: return@withLock emptyList()
            if (dayList.isEmpty()) return@withLock emptyList()
            dayList.removeAt(dayList.lastIndex)
            if (dayList.isEmpty()) {
                habitMap.remove(dateStr)
            }
            if (habitMap.isEmpty()) {
                data.remove(habitName)
            }
            saveAll(data)
            dayList.toList()
        }
    }

    private suspend fun loadMutable(): MutableMap<String, MutableMap<String, MutableList<String>>> =
        withContext(Dispatchers.IO) {
            // Always called under fileMutex. Serves from the snapshot cache
            // when fresh, deep-copying so callers can mutate freely without
            // ever touching the shared cached structure.
            try {
                deepCopy(readSnapshot())
            } catch (e: Exception) {
                mutableMapOf()
            }
        }

    /**
     * Renames a habit key in the timestamp file.
     * Moves all timestamp data from [oldName] to [newName] so that
     * historical timestamps survive a habit rename.
     */
    suspend fun renameHabit(oldName: String, newName: String) {
        fileMutex.withLock {
            val data = loadMutable()
            if (data.containsKey(oldName)) {
                data[newName] = data.remove(oldName)!!
                saveAll(data)
            }
            // Per-timestamp minutes survive the rename too.
            val minutesData = loadMinutesMutable()
            if (minutesData.containsKey(oldName)) {
                minutesData[newName] = minutesData.remove(oldName)!!
                saveMinutes(minutesData)
            }
        }
    }
}
