package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val gson = Gson()
private val prettyGson = GsonBuilder().setPrettyPrinting().create()
private val dbType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

/**
 * Handles all read/write operations for habitsdb_phone.txt via SAF URI.
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
     * Iterates ALL habits present in the DB (not just HABIT_ORDER) so that habits
     * added outside the canonical list (e.g. "hrv readiness", "See Sun", "Dream Recorded")
     * also get their missing days filled in.
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

        // Use ALL habits present in the DB so that habits not in HABIT_ORDER
        // (e.g. "hrv readiness", "See Sun", "Dream Recorded") are also caught.
        val allHabitNames = db.keys.toSet() + HABIT_ORDER
        for (name in allHabitNames) {
            val entries = db[name]?.toMutableMap() ?: mutableMapOf()

            // Find the latest date already in this habit's entries
            val latestExisting = entries.keys.maxOrNull()

            if (latestExisting == null) {
                // No entries at all — just add today with 0
                entries[todayStr] = 0
                anyAdded = true
            } else if (latestExisting < todayStr) {
                // Fill in every missing day from day-after-latest up to today
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

        // Keep entries sorted by date
        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Increments today's count for a habit by [amount], then saves.
     * Performs atomic read-modify-write.
     */
    suspend fun incrementHabit(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int
    ): HabitsDatabase = incrementHabitForDate(uri, context, habitName, amount, LocalDate.now())

    /**
     * Reads and parses the historical habits JSON file (read-only, no write needed).
     * Returns an empty map if the file is missing or malformed.
     */
    suspend fun loadHistoricalDatabase(uri: Uri, context: Context): HabitsDatabase =
        loadDatabase(uri, context)

    /**
     * Reads and parses habitsdb_without_phone_totals.txt — a pre-computed stats file.
     * Format: { "habit_name": { "days_since_not_zero": N, "days_since_zero": N, "longest_streak": N } }
     * Returns an empty map if the file is missing or malformed.
     */
    suspend fun loadHistoricalTotals(uri: Uri, context: Context): HistoricalTotals =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                cr.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    val jsonObj = gson.fromJson(text, JsonObject::class.java)
                        ?: return@withContext emptyMap()
                    val result = mutableMapOf<String, HabitHistoricalStats>()
                    for ((habitName, statsElem) in jsonObj.entrySet()) {
                        val statsObj = statsElem.asJsonObject
                        result[habitName] = HabitHistoricalStats(
                            daysSinceNotZero = statsObj.get("days_since_not_zero")?.asInt ?: 0,
                            daysSinceZero = statsObj.get("days_since_zero")?.asInt ?: 0,
                            longestStreak = statsObj.get("longest_streak")?.asInt ?: 0
                        )
                    }
                    result
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /**
     * Adds a new habit to one or more JSON database files.
     * For each provided URI, reads the file, adds the habit with today's date = 0 if not already present,
     * then saves. This ensures the new habit appears in all configured habit files.
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
     * Merges two databases by combining their date entries per habit.
     * Phone DB entries take precedence on date conflicts (phone is the source of truth for recent data).
     */
    fun mergeDatabases(phoneDb: HabitsDatabase, historicalDb: HabitsDatabase): HabitsDatabase {
        val allKeys = phoneDb.keys + historicalDb.keys
        return allKeys.associateWith { name ->
            val historical = historicalDb[name] ?: emptyMap()
            val phone = phoneDb[name] ?: emptyMap()
            // Merge: historical provides the base, phone entries override/add
            (historical + phone).toSortedMap()
        }
    }

    /**
     * Builds the display [Habit] list from raw database + settings for a specific [targetDate].
     * If a historical database is provided, merges it with the phone database first.
     * If historicalTotals is provided, uses pre-computed streak baselines to extend phone-only streaks.
     */
    fun buildHabitList(
        db: HabitsDatabase,
        settings: AppSettings,
        historicalDb: HabitsDatabase = emptyMap(),
        historicalTotals: HistoricalTotals = emptyMap(),
        targetDate: LocalDate = LocalDate.now()
    ): List<Habit> {
        val merged = if (historicalDb.isEmpty()) db else mergeDatabases(db, historicalDb)
        // Use custom order if set, otherwise fall back to default HABIT_ORDER
        val order = if (settings.habitOrder.isNotEmpty()) settings.habitOrder else HABIT_ORDER
        return order.map { name ->
            val entries = merged[name] ?: emptyMap()
            buildHabit(
                name = name,
                entries = entries,
                useCustomInput = name in settings.customInputHabits,
                historicalStats = historicalTotals[name],
                targetDate = targetDate
            )
        }
    }
}
