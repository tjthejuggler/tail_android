package com.example.tail.data

import android.content.Context
import android.net.Uri
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
 * Reads/writes per-habit subtype breakdown data in the app's INTERNAL storage.
 *
 * File: `files/subtype_data.json`
 * Format:
 * ```json
 * {
 *   "Pullups": { "2026-01-15": { "chinups": 5, "wide": 3 } },
 *   ...
 * }
 * ```
 *
 * Historically this data lived in per-habit EXTERNAL SAF JSON files picked in
 * edit mode. Since 2026-08-15 it is stored internally (a single JSON keyed by
 * habit name) — the app was the only reader/writer of those files, and the
 * external copies added a SAF round-trip to every increment plus a cross-file
 * consistency risk with the habits DB (the "subtype sum must equal the
 * habitsdb total" invariant spanned two files and two write paths). A one-time
 * migration ([SubtypeTimedMigrator]) imported any existing external files; the
 * original files were left untouched on disk for manual cleanup.
 *
 * Concurrency: every read-modify-write cycle is serialised by a PROCESS-WIDE
 * mutex in the companion object, because callers (ViewModel, voice services,
 * receivers) each construct their own repository instance — the same pattern
 * as [HabitTimestampRepository].
 */
class SubtypeDataRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType = object : TypeToken<MutableMap<String, MutableMap<String, MutableMap<String, Int>>>>() {}.type

    private val file: File get() = File(context.filesDir, "subtype_data.json")

    companion object {
        private const val TAG = "SubtypeDataRepo"

        /** Process-wide mutex serialising all read-modify-write cycles. */
        private val fileMutex = Mutex()
    }

    // ── Internal store operations ────────────────────────────────────────────

    /** Loads the full internal store: habit → date → subtype → count. */
    suspend fun loadAll(): Map<String, Map<String, Map<String, Int>>> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyMap()
            val text = file.readText()
            if (text.isBlank()) return@withContext emptyMap()
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<Map<String, Map<String, Map<String, Int>>>>(text, mapType) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed: ${e.message}")
            emptyMap()
        }
    }

    /** Loads the subtype data for one habit: date → subtype → count. */
    suspend fun loadSubtypeData(habitName: String): Map<String, Map<String, Int>> =
        loadAll()[habitName] ?: emptyMap()

    /** Writes the full internal store to disk (pretty-printed, sorted). */
    private suspend fun saveAll(data: Map<String, Map<String, Map<String, Int>>>) =
        withContext(Dispatchers.IO) {
            try {
                val sorted = data.toSortedMap().mapValues { (_, dates) ->
                    dates.toSortedMap().mapValues { (_, subs) -> subs.toSortedMap() }
                }
                file.writeText(prettyGson.toJson(sorted))
            } catch (e: Exception) {
                Log.w(TAG, "saveAll failed: ${e.message}")
            }
        }

    /** Replaces the stored data for [habitName]. Pass empty map to clear the habit. */
    suspend fun saveSubtypeData(habitName: String, data: Map<String, Map<String, Int>>) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            if (data.isEmpty()) all.remove(habitName) else all[habitName] = data
            saveAll(all)
        }
    }

    /** Gets the breakdown for a single date. */
    suspend fun getBreakdownForDate(habitName: String, dateStr: String): Map<String, Int> {
        return loadSubtypeData(habitName)[dateStr] ?: emptyMap()
    }

    /** Adds increments to a single date's breakdown and saves. */
    suspend fun addToDate(habitName: String, dateStr: String, increments: Map<String, Int>) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val habitData = all[habitName]?.toMutableMap() ?: mutableMapOf()
            val existing = habitData[dateStr]?.toMutableMap() ?: mutableMapOf()
            for ((subtype, amount) in increments) {
                if (amount > 0) {
                    existing[subtype] = (existing[subtype] ?: 0) + amount
                }
            }
            habitData[dateStr] = existing
            all[habitName] = habitData
            saveAll(all)
        }
    }

    /**
     * Moves all subtype data from [oldName] to [newName] so it survives a
     * habit rename. No-op if [oldName] has no stored data.
     */
    suspend fun renameHabit(oldName: String, newName: String) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val data = all.remove(oldName) ?: return@withLock
            all[newName] = data
            saveAll(all)
        }
    }

    // ── Legacy external-file access (one-time migration only) ────────────────

    /**
     * Reads a legacy external subtype JSON via its SAF URI. Used only by
     * [SubtypeTimedMigrator] to import pre-internalization data.
     */
    suspend fun loadSubtypeDataFromUri(uri: Uri): Map<String, Map<String, Int>> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    if (text.isBlank()) return@withContext emptyMap()
                    val type = object : TypeToken<Map<String, Map<String, Int>>>() {}.type
                    gson.fromJson<Map<String, Map<String, Int>>>(text, type) ?: emptyMap()
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }
}
