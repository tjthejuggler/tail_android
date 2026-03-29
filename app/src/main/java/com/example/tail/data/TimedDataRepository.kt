package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Reads/writes per-habit timed session JSON files via SAF URI.
 *
 * File format (sorted by timestamp key):
 * ```json
 * {
 *   "2026-01-18 11:45:06": { "subtype": "chinups", "count": 5 },
 *   "2026-01-18 12:49:42": { "subtype": "pullups", "count": 7 },
 *   "2026-03-15 14:30:00": { "subtype": null, "count": 3 }
 * }
 * ```
 *
 * Each key is "YYYY-MM-DD HH:MM:SS". The value object has:
 * - `subtype`: the subtype name (String) or null for non-subtyped timed habits
 * - `count`: the session count (Int)
 */
class TimedDataRepository {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType = object : TypeToken<Map<String, Map<String, Any>>>() {}.type

    companion object {
        private val TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Returns a timestamp string for "right now". */
        fun nowTimestamp(): String = LocalDateTime.now().format(TIMESTAMP_FMT)
    }

    /** Loads the full timed data file. Returns empty map on error. */
    suspend fun loadTimedData(uri: Uri, context: Context): Map<String, TimedEntry> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    parseTimedJson(text)
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** Saves the full timed data file (sorted by timestamp). */
    suspend fun saveTimedData(uri: Uri, context: Context, data: Map<String, TimedEntry>) =
        withContext(Dispatchers.IO) {
            try {
                val sorted = data.toSortedMap()
                val serializable = sorted.mapValues { (_, entry) ->
                    mapOf("subtype" to entry.subtype, "count" to entry.count)
                }
                val json = prettyGson.toJson(serializable)
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { it.write(json) }
                }
            } catch (e: Exception) {
                // Best-effort
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
    suspend fun appendEntries(
        uri: Uri,
        context: Context,
        increments: Map<String?, Int>
    ) {
        val data = loadTimedData(uri, context).toMutableMap()
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

        saveTimedData(uri, context, data)
    }

    private fun parseTimedJson(text: String): Map<String, TimedEntry> {
        if (text.isBlank()) return emptyMap()
        return try {
            val raw: Map<String, Map<String, Any>> = gson.fromJson(text, mapType) ?: return emptyMap()
            raw.mapValues { (_, obj) ->
                val subtype = obj["subtype"]?.toString()?.takeIf { it != "null" }
                val count = (obj["count"] as? Number)?.toInt() ?: 0
                TimedEntry(subtype = subtype, count = count)
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
}

/** A single timed session entry. */
data class TimedEntry(
    val subtype: String?,  // null for non-subtyped timed habits
    val count: Int
)
