package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val gson = Gson()
private val prettyGson = GsonBuilder().setPrettyPrinting().create()
private val textLogType = object : TypeToken<Map<String, String>>() {}.type
private val TEXT_LOG_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Handles reading and writing per-habit text-log JSON files.
 *
 * File format:
 * {
 *   "2023-07-07 10:00:17": "Cant Stop Me by David Goggins",
 *   "2023-07-08 10:00:17": "Advanced Bird Language by Jon Young"
 * }
 *
 * Keys are "YYYY-MM-DD HH:mm:ss" timestamps; values are the user's free-text entries.
 */
class TextInputRepository {

    /**
     * Loads the text log from the given SAF URI.
     * Returns an empty map if the file is missing, empty, or malformed.
     */
    suspend fun loadTextLog(uri: Uri, context: Context): Map<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                cr.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    if (text.isBlank()) return@withContext emptyMap()
                    gson.fromJson<Map<String, String>>(text, textLogType) ?: emptyMap()
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /**
     * Appends a new text entry to the log file at [uri], keyed by a timestamp.
     * Performs atomic read-modify-write.
     *
     * @param uri The SAF URI of the text log file
     * @param context Android context
     * @param text The text entry to save
     * @param date The date to use for the timestamp. If null, uses current date/time.
     *             When provided, uses noon (12:00:00) as the time.
     * Returns the updated log map.
     */
    suspend fun appendTextEntry(
        uri: Uri,
        context: Context,
        text: String,
        date: LocalDate? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        val timestamp = if (date != null) {
            // Use the provided date with noon time
            LocalDateTime.of(date, LocalTime.NOON).format(TEXT_LOG_DATE_FMT)
        } else {
            // Use current date/time
            LocalDateTime.now().format(TEXT_LOG_DATE_FMT)
        }
        existing[timestamp] = text
        saveTextLog(uri, context, existing)
        existing
    }

    /**
     * Writes the full text log map back to the SAF URI as formatted JSON.
     * Entries are sorted chronologically by timestamp key before saving.
     */
    private suspend fun saveTextLog(
        uri: Uri,
        context: Context,
        log: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        // Sort entries chronologically by timestamp (keys are "YYYY-MM-DD HH:mm:ss")
        val sortedLog = log.toSortedMap()
        val json = prettyGson.toJson(sortedLog)
        val cr = context.contentResolver
        cr.openOutputStream(uri, "wt")?.use { stream ->
            stream.bufferedWriter().use { it.write(json) }
        }
    }

    /**
     * Returns all unique text values ever entered for this habit (from the log file),
     * sorted alphabetically for display as options.
     */
    suspend fun loadUniqueOptions(uri: Uri, context: Context): List<String> =
        withContext(Dispatchers.IO) {
            loadTextLog(uri, context).values.toSortedSet().toList()
        }

    /**
     * Updates an existing text entry in the log file.
     * [oldTimestamp] is the exact key to match; [newText] replaces the old value.
     * If the key is not found, no change is made.
     * Returns the updated log map.
     */
    suspend fun updateTextEntry(
        uri: Uri,
        context: Context,
        oldTimestamp: String,
        newText: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        if (oldTimestamp in existing) {
            existing[oldTimestamp] = newText
            saveTextLog(uri, context, existing)
        }
        existing
    }

    /**
     * Deletes an existing text entry from the log file.
     * [timestamp] is the exact key to remove.
     * Returns the updated log map.
     */
    suspend fun deleteTextEntry(
        uri: Uri,
        context: Context,
        timestamp: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        existing.remove(timestamp)
        saveTextLog(uri, context, existing)
        existing
    }
}
