package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val gson = Gson()
private val prettyGson = GsonBuilder().setPrettyPrinting().create()
private val dbType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

/**
 * Handles all read/write operations for habitsdb.txt via SAF URI.
 * This is the single unified database shared between phone and desktop via Syncthing.
 */
class HabitsRepository {

    /**
     * Reads and parses the habits JSON file from the given SAF URI.
     * Returns an empty map if the file is missing or malformed.
     */
    suspend fun loadDatabase(uri: Uri, context: Context): HabitsDatabase =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                cr.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    gson.fromJson(text, dbType) ?: emptyMap()
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /**
     * Writes the habits database back to the SAF URI as formatted JSON.
     * Validates the data before writing to prevent corruption.
     */
    suspend fun saveDatabase(uri: Uri, context: Context, db: HabitsDatabase) =
        withContext(Dispatchers.IO) {
            val json = prettyGson.toJson(db)
            // Validate round-trip before writing
            val validated: HabitsDatabase? = try {
                gson.fromJson(json, dbType)
            } catch (e: Exception) {
                null
            }
            if (validated == null) return@withContext

            val cr = context.contentResolver
            cr.openOutputStream(uri, "wt")?.use { stream ->
                stream.bufferedWriter().use { it.write(json) }
            }
        }

    /**
     * Ensures every habit has an entry for every date from the latest recorded date
     * up to and including [today]. Missing dates are added with value 0.
     * Iterates ALL habits present in the DB so that habits added outside the canonical
     * list also get their missing days filled in.
     * Returns the updated database (and saves it if any dates were added).
     */
    suspend fun ensureDaysExist(
        uri: Uri,
        context: Context,
        today: LocalDate = LocalDate.now()
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val db = loadDatabase(uri, context).toMutableMap()
        val todayStr = dateString(today)
        var anyAdded = false

        val allHabitNames = db.keys.toSet() + HABIT_ORDER
        for (name in allHabitNames) {
            val entries = db[name]?.toMutableMap() ?: mutableMapOf()

            val latestExisting = entries.keys.maxOrNull()

            if (latestExisting == null) {
                entries[todayStr] = 0
                anyAdded = true
            } else if (latestExisting < todayStr) {
                var cursor = parseDate(latestExisting)?.plusDays(1) ?: today
                while (!cursor.isAfter(today)) {
                    val ds = dateString(cursor)
                    if (!entries.containsKey(ds)) {
                        entries[ds] = 0
                        anyAdded = true
                    }
                    cursor = cursor.plusDays(1)
                }
            }

            db[name] = entries.toSortedMap()
        }

        if (anyAdded) {
            saveDatabase(uri, context, db)
        }
        db
    }

    /**
     * Applies an increment to [db] in memory only — no disk I/O.
     * Returns the updated database. Safe to call on the main thread.
     */
    fun applyIncrementToDb(
        db: HabitsDatabase,
        habitName: String,
        amount: Int,
        date: LocalDate
    ): HabitsDatabase {
        val dateStr = dateString(date)
        val mutable = db.toMutableMap()
        val habitEntries = mutable[habitName]?.toMutableMap() ?: mutableMapOf()
        habitEntries[dateStr] = (habitEntries[dateStr] ?: 0) + amount
        mutable[habitName] = habitEntries.toSortedMap()
        return mutable
    }

    /**
     * Writes [db] to disk at [uri]. Call this after an optimistic in-memory update.
     */
    suspend fun persistDatabase(uri: Uri, context: Context, db: HabitsDatabase) =
        saveDatabase(uri, context, db)

    /**
     * Increments the count for a habit on [date] by [amount], then saves.
     * Performs atomic read-modify-write (reads from disk first).
     * Prefer [applyIncrementToDb] + [persistDatabase] for optimistic UI updates.
     */
    suspend fun incrementHabitForDate(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val db = loadDatabase(uri, context).toMutableMap()
        val dateStr = dateString(date)

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        val current = habitEntries[dateStr] ?: 0
        habitEntries[dateStr] = current + amount

        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Increments today's count for a habit by [amount], then saves.
     */
    suspend fun incrementHabit(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int
    ): HabitsDatabase = incrementHabitForDate(uri, context, habitName, amount, LocalDate.now())

    /**
     * Adds a new habit to the JSON database file.
     * Reads the file, adds the habit with today's date = 0 if not already present, then saves.
     */
    suspend fun addHabitToFiles(
        uris: List<Uri>,
        context: Context,
        habitName: String,
        today: LocalDate = LocalDate.now()
    ) = withContext(Dispatchers.IO) {
        val todayStr = dateString(today)
        for (uri in uris) {
            try {
                val db = loadDatabase(uri, context).toMutableMap()
                if (!db.containsKey(habitName)) {
                    db[habitName] = sortedMapOf(todayStr to 0)
                    saveDatabase(uri, context, db)
                }
            } catch (e: Exception) {
                // Best-effort: skip files that can't be written
            }
        }
    }

    /**
     * Builds the display [Habit] list from raw database + settings for a specific [targetDate].
     * Uses the full unified habitsdb.txt — no merging with separate historical DB needed.
     */
    fun buildHabitList(
        db: HabitsDatabase,
        settings: AppSettings,
        targetDate: LocalDate = LocalDate.now()
    ): List<Habit> {
        val order = if (settings.habitOrder.isNotEmpty()) settings.habitOrder else HABIT_ORDER
        return order.map { name ->
            val entries = db[name] ?: emptyMap()
            buildHabit(
                name = name,
                entries = entries,
                useCustomInput = name in settings.customInputHabits,
                divider = settings.habitDividers[name] ?: 1,
                targetDate = targetDate
            )
        }
    }
}
