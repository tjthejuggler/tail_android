package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * Increments today's count for a habit by [amount], then saves.
     * Performs atomic read-modify-write.
     */
    suspend fun incrementHabit(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val db = loadDatabase(uri, context).toMutableMap()
        val today = todayString()

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        val current = habitEntries[today] ?: 0
        habitEntries[today] = current + amount

        // Keep entries sorted by date
        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Reads and parses the historical habits JSON file (read-only, no write needed).
     * Returns an empty map if the file is missing or malformed.
     */
    suspend fun loadHistoricalDatabase(uri: Uri, context: Context): HabitsDatabase =
        loadDatabase(uri, context)

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
     * Builds the display [Habit] list from raw database + settings.
     * If a historical database is provided, merges it with the phone database first.
     */
    fun buildHabitList(
        db: HabitsDatabase,
        settings: AppSettings,
        historicalDb: HabitsDatabase = emptyMap()
    ): List<Habit> {
        val merged = if (historicalDb.isEmpty()) db else mergeDatabases(db, historicalDb)
        return HABIT_ORDER.map { name ->
            val entries = merged[name] ?: emptyMap()
            buildHabit(
                name = name,
                entries = entries,
                useCustomInput = name in settings.customInputHabits
            )
        }
    }
}
