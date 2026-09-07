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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reads/writes per-habit timed session data in the app's INTERNAL storage.
 *
 * File: `files/timed_data.json`
 * Format (sorted by timestamp key):
 * ```json
 * {
 *   "Meditation": {
 *     "2026-01-18 11:45:06": { "subtype": "chinups", "count": 5 },
 *     "2026-03-15 14:30:00": { "subtype": null, "count": 3 }
 *   }
 * }
 * ```
 *
 * Each timestamp key maps to a [TimedEntry] with:
 * - `subtype`: the subtype name (String) or null for non-subtyped timed habits
 * - `count`: the session count (Int)
 *
 * Historically this data lived in per-habit EXTERNAL SAF JSON files. Since
 * 2026-08-15 it is stored internally (a single JSON keyed by habit name) — the
 * app was the only reader/writer of those files, and no UI even existed to
 * pick new ones. A one-time migration ([SubtypeTimedMigrator]) imported any
 * existing external files; the originals were left untouched on disk.
 *
 * Concurrency: every read-modify-write cycle is serialised by a PROCESS-WIDE
 * mutex in the companion object, because callers (ViewModel, voice services,
 * receivers) each construct their own repository instance — the same pattern
 * as [HabitTimestampRepository].
 */
class TimedDataRepository(private val context: Context) {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType = object : TypeToken<MutableMap<String, MutableMap<String, Map<String, Any>>>>() {}.type

    private val file: File get() = File(context.filesDir, "timed_data.json")

    companion object {
        private const val TAG = "TimedDataRepo"

        /** Process-wide mutex serialising all read-modify-write cycles. */
        private val fileMutex = Mutex()

        private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Returns a timestamp string for "right now". */
        fun nowTimestamp(): String = LocalDateTime.now().format(TIMESTAMP_FMT)
    }

    // ── Internal store operations ────────────────────────────────────────────

    /** Loads the full internal store: habit → timestamp → entry. */
    suspend fun loadAll(): Map<String, Map<String, TimedEntry>> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext emptyMap()
            val text = file.readText()
            if (text.isBlank()) return@withContext emptyMap()
            val raw: Map<String, Map<String, Map<String, Any>>> =
                gson.fromJson(text, mapType) ?: return@withContext emptyMap()
            raw.mapValues { (_, entries) -> parseEntryMap(entries) }
        } catch (e: Exception) {
            Log.w(TAG, "loadAll failed: ${e.message}")
            emptyMap()
        }
    }

    /** Loads the timed data for one habit: timestamp → entry. */
    suspend fun loadTimedData(habitName: String): Map<String, TimedEntry> =
        loadAll()[habitName] ?: emptyMap()

    /** Writes the full internal store to disk (pretty-printed, sorted). */
    private suspend fun saveAll(data: Map<String, Map<String, TimedEntry>>) =
        withContext(Dispatchers.IO) {
            try {
                val serializable = data.toSortedMap().mapValues { (_, entries) ->
                    entries.toSortedMap().mapValues { (_, entry) ->
                        mapOf("subtype" to entry.subtype, "count" to entry.count)
                    }
                }
                file.writeText(prettyGson.toJson(serializable))
            } catch (e: Exception) {
                Log.w(TAG, "saveAll failed: ${e.message}")
            }
        }

    /** Replaces the stored data for [habitName]. Pass empty map to clear the habit. */
    suspend fun saveTimedData(habitName: String, data: Map<String, TimedEntry>) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            if (data.isEmpty()) all.remove(habitName) else all[habitName] = data
            saveAll(all)
        }
    }

    /**
     * Appends one or more timed entries for the current moment.
     * [increments] maps subtype name → count. For non-subtyped habits, use a single
     * entry with key = "" (empty string) which will be stored as subtype=null.
     *
     * Each subtype gets its own timestamp entry (same second is fine — they'll have
     * unique keys because we append a suffix if needed).
     */
    suspend fun appendEntries(habitName: String, increments: Map<String?, Int>) {
        fileMutex.withLock {
            val all = loadAll().toMutableMap()
            val data = all[habitName]?.toMutableMap() ?: mutableMapOf()
            val baseTimestamp = nowTimestamp()

            var suffix = 0
            for ((subtype, count) in increments) {
                if (count <= 0) continue
                // Ensure unique key — append suffix if timestamp already exists
                var key = if (suffix == 0) baseTimestamp else "$baseTimestamp.$suffix"
                while (data.containsKey(key)) {
                    suffix++
                    key = "$baseTimestamp.$suffix"
                }
                data[key] = TimedEntry(subtype = subtype, count = count)
                suffix++
            }

            all[habitName] = data
            saveAll(all)
        }
    }

    /**
     * Moves all timed data from [oldName] to [newName] so it survives a
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
     * Reads a legacy external timed-session JSON via its SAF URI. Used only by
     * [SubtypeTimedMigrator] to import pre-internalization data.
     */
    suspend fun loadTimedDataFromUri(uri: Uri): Map<String, TimedEntry> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    if (text.isBlank()) return@withContext emptyMap()
                    val flatType = object : TypeToken<Map<String, Map<String, Any>>>() {}.type
                    val raw: Map<String, Map<String, Any>> =
                        gson.fromJson(text, flatType) ?: return@withContext emptyMap()
                    parseEntryMap(raw)
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** Converts a raw timestamp → {subtype, count} map into typed entries. */
    private fun parseEntryMap(raw: Map<String, Map<String, Any>>): Map<String, TimedEntry> {
        return raw.mapValues { (_, obj) ->
            val subtype = obj["subtype"]?.toString()?.takeIf { it != "null" }
            val count = (obj["count"] as? Number)?.toInt() ?: 0
            TimedEntry(subtype = subtype, count = count)
        }
    }
}

/** A single timed session entry. */
data class TimedEntry(
    val subtype: String?,  // null for non-subtyped timed habits
    val count: Int
)
