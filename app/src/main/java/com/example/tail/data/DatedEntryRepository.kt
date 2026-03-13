package com.example.tail.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parses "Dated Entry" source files and returns a per-day count map.
 *
 * File format (same as the dream journal parsed by parse_dreams.py):
 *   - Date headers: either  M/D/YY  (e.g. 7/13/24) or  YYYY-MM-DD  (e.g. 2025-10-21)
 *     - The header line may start with optional markdown heading markers (#, ##, …)
 *     - A trailing HH:MM:SS timestamp on the same line is ignored
 *   - After a date header, each non-empty "chunk" of text separated by blank lines
 *     or ,,, separator lines counts as +1 for that day.
 *   - If there are no chunks before the next date, the count for that day is 0.
 *
 * Change detection strategy:
 *   New entries are prepended to the top of the file, so there is no cheap
 *   "read only the new tail" shortcut — the whole file must be read whenever
 *   it changes.  We use [getFileSize] (a metadata-only SAF query, no stream
 *   open) to detect whether the file has changed at all.  If the size is
 *   identical to the last-seen size we skip the parse entirely.  This means
 *   the cost on a typical foreground resume is one tiny ContentResolver query
 *   per dated-entry habit — essentially free.
 */
class DatedEntryRepository {

    // ── Regex patterns (mirrors parse_dreams.py) ──────────────────────────────

    /** Matches  7/13/24  or  07/13/24  (M/D/YY or MM/DD/YY) at the start of a stripped line. */
    private val shortDateRe = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2})(?![/\d])""")

    /** Matches  2025-10-21  or  2025-3-14  (YYYY-M-D) at the start of a stripped line. */
    private val longDateRe = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")

    /** Matches a bare HH:MM:SS timestamp (used to strip trailing timestamps from date lines). */
    private val timestampRe = Regex("""^\d{2}:\d{2}:\d{2}$""")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the current byte-size of the document at [uri] using the SAF
     * ContentResolver metadata, without reading the file contents.
     * Returns -1 if the size cannot be determined (e.g. URI no longer valid).
     */
    fun getFileSize(uri: Uri, context: Context): Long {
        return try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            ) ?: return -1L
            cursor.use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Parses the entire dated-entry file at [uri] and returns a map of
     * "YYYY-MM-DD" → paragraph-chunk count for every date found in the file.
     *
     * Because new entries are prepended (added at the top), a full read is
     * always required when the file has changed.  The size-check in the
     * ViewModel ensures this is only called when necessary.
     *
     * Runs on [Dispatchers.IO]. Returns an empty map on any error.
     */
    suspend fun parseFile(uri: Uri, context: Context): Map<String, Int> =
        withContext(Dispatchers.IO) {
            try {
                val lines = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.readLines()
                    ?: return@withContext emptyMap()
                parseLinesInternal(lines)
            } catch (e: Exception) {
                emptyMap()
            }
        }

    // ── Internal parsing ──────────────────────────────────────────────────────

    /**
     * Core parser — pure function operating on a list of strings.
     * Exposed as internal so it can be unit-tested without a real URI.
     */
    internal fun parseLinesInternal(lines: List<String>): Map<String, Int> {
        val results = mutableMapOf<String, Int>()

        var currentDate: String? = null
        var currentBody = mutableListOf<String>()

        fun flush() {
            val d = currentDate ?: return
            val count = countChunks(currentBody)
            results[d] = (results[d] ?: 0) + count
            currentDate = null
            currentBody = mutableListOf()
        }

        for (line in lines) {
            val (date, rest) = parseDateHeader(line)
            if (date != null) {
                flush()
                currentDate = date
                if (!rest.isNullOrEmpty()) currentBody.add(rest)
            } else {
                if (currentDate != null) {
                    currentBody.add(line.trimEnd('\n', '\r'))
                }
            }
        }
        flush()

        return results
    }

    /**
     * Attempts to parse a date header from [line].
     * Returns (dateString "YYYY-MM-DD", restOfLine) on success, or (null, null).
     *
     * Rules:
     *  - Leading whitespace and markdown heading markers (#) are stripped.
     *  - The date must appear at the very start of the stripped line.
     *  - A trailing HH:MM:SS timestamp is stripped and not returned as body text.
     */
    private fun parseDateHeader(line: String): Pair<String?, String?> {
        val stripped = line.trim().trimStart('#').trim()

        // Try short format  M/D/YY
        shortDateRe.find(stripped)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val day   = m.groupValues[2].toIntOrNull() ?: return@let
            val yr2   = m.groupValues[3].toIntOrNull() ?: return@let
            val year  = 2000 + yr2
            if (!isValidDate(year, month, day)) return@let
            val dateStr = "%04d-%02d-%02d".format(year, month, day)
            val rest = stripped.substring(m.range.last + 1).trim()
            val cleanRest = if (timestampRe.matches(rest)) "" else rest
            return dateStr to cleanRest
        }

        // Try long format  YYYY-MM-DD
        longDateRe.find(stripped)?.let { m ->
            val year  = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day   = m.groupValues[3].toIntOrNull() ?: return@let
            if (!isValidDate(year, month, day)) return@let
            val dateStr = "%04d-%02d-%02d".format(year, month, day)
            val rest = stripped.substring(m.range.last + 1).trim()
            val cleanRest = if (timestampRe.matches(rest)) "" else rest
            return dateStr to cleanRest
        }

        return null to null
    }

    /**
     * Counts the number of non-empty "chunks" in [lines].
     * A chunk is a run of one or more non-separator lines.
     * Separators are blank lines or lines containing only ",,,"
     */
    private fun countChunks(lines: List<String>): Int {
        var inChunk = false
        var count = 0
        for (line in lines) {
            val s = line.trim()
            if (s.isEmpty() || s == ",,,") {
                inChunk = false
            } else {
                if (!inChunk) {
                    count++
                    inChunk = true
                }
            }
        }
        return count
    }

    /** Basic calendar validation to reject garbage date matches. */
    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false
        if (year < 1900 || year > 2200) return false
        return true
    }
}
