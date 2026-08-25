package com.example.tail.ipc

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure selection/truncation rules for the Inuit text-habit sharing endpoint
 * (see [HabitsContentProvider], paths `text_habits` and `text_habits/recent`).
 *
 * The goal: give the Inuit trivia trainer a TINY, RECENT slice of the user's
 * text-input habit logs — enough to inspire a question angle, never enough
 * to flood an LLM context window or leak the whole history:
 *  - only entries from the last [MAX_AGE_DAYS] days,
 *  - at most [clampLimit] entries per habit (default 3, hard cap 5),
 *  - each entry truncated to [MAX_ENTRY_CHARS] characters.
 *
 * All functions are pure and JVM-testable; the provider does the I/O.
 */
object InuitTextSharing {

    /** Entries per habit when the caller doesn't ask for a specific limit. */
    const val DEFAULT_LIMIT = 3

    /** Hard ceiling on entries per habit, no matter what the caller asks. */
    const val MAX_LIMIT = 5

    /** Per-entry character cap (truncation adds no ellipsis budget). */
    const val MAX_ENTRY_CHARS = 300

    /** Only entries timestamped within this window are ever shared. */
    const val MAX_AGE_DAYS = 14L

    /** Text-log key format used by TextInputRepository ("yyyy-MM-dd HH:mm:ss"). */
    private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** One parsed log entry: original key + parsed time + raw text. */
    private data class Parsed(val key: String, val time: LocalDateTime, val text: String)

    /** Clamps a requested per-habit entry limit into [1, MAX_LIMIT]; null → default. */
    fun clampLimit(requested: Int?): Int =
        when {
            requested == null || requested <= 0 -> DEFAULT_LIMIT
            else -> requested.coerceAtMost(MAX_LIMIT)
        }

    /** Truncates [text] to at most [maxChars] characters, appending "…" when cut. */
    fun truncate(text: String, maxChars: Int = MAX_ENTRY_CHARS): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "…"

    /**
     * Picks the most recent shareable entries from one habit's log.
     *
     * @param log  timestamp-keyed text log (keys "yyyy-MM-dd HH:mm:ss")
     * @param limit  already-clamped per-habit entry count (see [clampLimit])
     * @param now  reference time for the recency window
     * @return up to [limit] (timestamp, text) pairs, NEWEST FIRST, each text
     *         truncated; entries with unparseable keys or older than
     *         [MAX_AGE_DAYS] are skipped.
     */
    fun recentEntries(
        log: Map<String, String>,
        limit: Int,
        now: LocalDateTime = LocalDateTime.now()
    ): List<Pair<String, String>> {
        val cutoff = now.minusDays(MAX_AGE_DAYS)
        val horizon = now.plusMinutes(1) // tolerate tiny clock skew from external writers
        return log.entries
            .asSequence()
            .mapNotNull { (key, text) ->
                val time = try {
                    LocalDateTime.parse(key, TS_FMT)
                } catch (_: Exception) {
                    null
                } ?: return@mapNotNull null
                Parsed(key, time, text)
            }
            .filter { !it.time.isBefore(cutoff) && it.time.isBefore(horizon) }
            .sortedByDescending { it.time }
            .take(limit.coerceAtLeast(0))
            .map { it.key to truncate(it.text) }
            .toList()
    }
}
