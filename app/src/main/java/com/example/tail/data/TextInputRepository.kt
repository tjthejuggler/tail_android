package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val gson = Gson()
private val prettyGson = GsonBuilder().setPrettyPrinting().create()
private val textLogType = object : TypeToken<Map<String, String>>() {}.type
private val TEXT_LOG_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private const val TAG = "TextInputRepo"
private const val BACKUP_DIR = "text_input_backups"

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
 *
 * ## Internal backup
 * Every write method accepts an optional [habitName]. When provided, the updated
 * log is also mirrored to `filesDir/text_input_backups/<habitName>.json` so that
 * the data survives even if the external SAF file is deleted or corrupted.
 * Use [loadInternalBackup] / [restoreFromInternalBackup] to recover.
 */
class TextInputRepository {

    // ───────────────────────────────────────────────────────────────────────
    //  External SAF file operations
    // ───────────────────────────────────────────────────────────────────────

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
     * @param date The date to use for the timestamp. If null, uses current date.
     * @param time The time-of-day to use for the timestamp. If null, uses current time
     *             (when [date] is also null) or noon (when [date] is provided but [time] is null).
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun appendTextEntry(
        uri: Uri,
        context: Context,
        text: String,
        date: LocalDate? = null,
        time: LocalTime? = null,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        val timestamp = when {
            date != null && time != null -> LocalDateTime.of(date, time)
            date != null -> LocalDateTime.of(date, LocalTime.NOON)
            time != null -> LocalDateTime.of(LocalDate.now(), time)
            else -> LocalDateTime.now()
        }.format(TEXT_LOG_DATE_FMT)
        existing[timestamp] = text
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    /**
     * Appends multiple text entries to the log file at [uri], each keyed by a unique
     * timestamp. Entries are offset by 1 second to avoid key collisions.
     *
     * @param uri The SAF URI of the text log file
     * @param context Android context
     * @param texts The list of text entries to save
     * @param date The date to use for timestamps. If null, uses current date.
     * @param time The base time-of-day. If null, uses current time (when [date] is also null)
     *             or noon (when [date] is provided but [time] is null).
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun appendMultipleTextEntries(
        uri: Uri,
        context: Context,
        texts: List<String>,
        date: LocalDate? = null,
        time: LocalTime? = null,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext loadTextLog(uri, context)
        val existing = loadTextLog(uri, context).toMutableMap()
        val baseDateTime = when {
            date != null && time != null -> LocalDateTime.of(date, time)
            date != null -> LocalDateTime.of(date, LocalTime.NOON)
            time != null -> LocalDateTime.of(LocalDate.now(), time)
            else -> LocalDateTime.now()
        }
        texts.forEachIndexed { index, text ->
            // Offset each entry by 1 second to guarantee unique keys
            val ts = baseDateTime.plusSeconds(index.toLong()).format(TEXT_LOG_DATE_FMT)
            existing[ts] = text
        }
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
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
     * If the key is not found, it will be added (for adding text to increments without text).
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun updateTextEntry(
        uri: Uri,
        context: Context,
        oldTimestamp: String,
        newText: String,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        existing[oldTimestamp] = newText
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    /**
     * Updates multiple existing text entries in one atomic read-modify-write.
     * Keys in [updates] that don't exist yet are added (same semantics as
     * [updateTextEntry]). Used by the movie-minutes backlog, which rewrites
     * many entries at once and must not perform one file write per entry.
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun updateTextEntries(
        uri: Uri,
        context: Context,
        updates: Map<String, String>,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext loadTextLog(uri, context)
        val existing = loadTextLog(uri, context).toMutableMap()
        existing.putAll(updates)
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    /**
     * Deletes an existing text entry from the log file.
     * [timestamp] is the exact key to remove.
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun deleteTextEntry(
        uri: Uri,
        context: Context,
        timestamp: String,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        existing.remove(timestamp)
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    /**
     * Deletes MULTIPLE text entries from the log file in one atomic
     * read-modify-write (used by the media per-show removal, which clears
     * every logged play of one show for a day). Keys not present are
     * ignored. @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun deleteTextEntries(
        uri: Uri,
        context: Context,
        timestamps: Collection<String>,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        if (timestamps.isEmpty()) return@withContext loadTextLog(uri, context)
        val existing = loadTextLog(uri, context).toMutableMap()
        timestamps.forEach { existing.remove(it) }
        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    /**
     * Rolls forward a text entry to multiple dates.
     * Copies the text from [sourceTimestamp] to all dates in the range [startDate] to [endDate] (inclusive).
     * For each date in the range, uses noon (12:00:00) as the time.
     * @param habitName If provided, also mirrors the updated log to internal storage.
     * Returns the updated log map.
     */
    suspend fun rollForwardTextEntry(
        uri: Uri,
        context: Context,
        sourceTimestamp: String,
        startDate: LocalDate,
        endDate: LocalDate,
        habitName: String? = null
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val existing = loadTextLog(uri, context).toMutableMap()
        val sourceText = existing[sourceTimestamp] ?: return@withContext existing

        var currentDate = startDate
        while (!currentDate.isAfter(endDate)) {
            val targetTimestamp = LocalDateTime.of(currentDate, LocalTime.NOON).format(TEXT_LOG_DATE_FMT)
            existing[targetTimestamp] = sourceText
            currentDate = currentDate.plusDays(1)
        }

        saveTextLog(uri, context, existing)
        if (habitName != null) saveInternalBackup(context, habitName, existing)
        existing
    }

    // ───────────────────────────────────────────────────────────────────────
    //  Internal backup (crash / external-file-loss recovery)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Saves a copy of the text-log map to internal storage so it survives
     * even if the external SAF file is deleted or corrupted.
     *
     * Stored at `filesDir/text_input_backups/<safeHabitName>.json`.
     */
    private fun saveInternalBackup(
        context: Context,
        habitName: String,
        log: Map<String, String>
    ) {
        try {
            val dir = File(context.filesDir, BACKUP_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, sanitizeFileName(habitName) + ".json")
            val sortedLog = log.toSortedMap()
            file.writeText(prettyGson.toJson(sortedLog))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save internal backup for '$habitName': ${e.message}")
        }
    }

    /**
     * Loads the internal backup copy of the text-log for [habitName].
     * Returns null if no internal backup exists.
     */
    fun loadInternalBackup(context: Context, habitName: String): Map<String, String>? {
        val file = File(context.filesDir, "$BACKUP_DIR/${sanitizeFileName(habitName)}.json")
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) null
            else gson.fromJson<Map<String, String>>(text, textLogType)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read internal backup for '$habitName': ${e.message}")
            null
        }
    }

    /**
     * Restores the internal backup for [habitName] back to the external SAF URI.
     * Returns true if the restore succeeded, false if no backup exists or the write failed.
     */
    suspend fun restoreFromInternalBackup(
        context: Context,
        uri: Uri,
        habitName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val backup = loadInternalBackup(context, habitName) ?: return@withContext false
        try {
            saveTextLog(uri, context, backup)
            Log.i(TAG, "Restored text log for '$habitName' from internal backup (${backup.size} entries)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore internal backup for '$habitName': ${e.message}")
            false
        }
    }

    /**
     * Bootstraps internal backups for all habits that have a text-input file URI
     * configured but don't yet have an internal backup (or whose external file
     * has newer data). Should be called once on app foreground/startup.
     *
     * @param fileUris Map of habit name → SAF URI string
     * @return number of habits that were backed up
     */
    suspend fun bootstrapInternalBackups(
        context: Context,
        fileUris: Map<String, String>
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        for ((habitName, uriStr) in fileUris) {
            if (uriStr.isBlank()) continue
            try {
                val external = loadTextLog(Uri.parse(uriStr), context)
                val internal = loadInternalBackup(context, habitName)
                // Only save if external has more entries than internal (or no internal exists)
                if (external.isNotEmpty() && (internal == null || external.size > internal.size)) {
                    saveInternalBackup(context, habitName, external)
                    count++
                    Log.i(TAG, "Bootstrapped internal backup for '$habitName' (${external.size} entries)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bootstrap failed for '$habitName': ${e.message}")
            }
        }
        if (count > 0) Log.i(TAG, "Bootstrapped $count text-input internal backups")
        count
    }

    /**
     * Sanitizes a habit name for use as a file name.
     * Replaces characters that are problematic on Android's file system.
     */
    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
    }
}
