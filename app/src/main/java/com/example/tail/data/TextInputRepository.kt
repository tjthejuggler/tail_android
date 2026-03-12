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
     * Appends a new text entry to the log file at [uri], keyed by the current timestamp.
     * Performs atomic read-modify-write.
     * Returns the updated log map.
     */
    suspend fun appendTextEntry(
        uri: Uri,
        context: Context,
        text: String
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        val timestamp = LocalDateTime.now().format(TEXT_LOG_DATE_FMT)
        existing[timestamp] = text
        saveTextLog(uri, context, existing)
        existing
    }

    /**
     * Writes the full text log map back to the SAF URI as formatted JSON.
     */
    private suspend fun saveTextLog(
        uri: Uri,
        context: Context,
        log: Map<String, String>
    ) = withContext(Dispatchers.IO) {
        val json = prettyGson.toJson(log)
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
}
