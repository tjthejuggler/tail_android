package com.example.tail.ipc

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure parser/validator for the **protocol v5** `EXTRA_TIMES_JSON` payload that
 * accompanies `ACTION_SET_HABIT_VALUES` broadcasts (sent by Inuit).
 *
 * Payload shape:
 * ```json
 * {"2026-01-15": ["09:13:02", "09:14:44"], "2026-01-16": ["21:07:10"]}
 * ```
 *
 * Each date maps to the list of "HH:mm:ss" times at which one unit of the
 * habit was recorded that day (one time string per answered question). Tail
 * REPLACES the habit's timestamps for every date present, mirroring the SET
 * semantics the counts payload already uses — so re-running a backfill always
 * converges to the sender's authoritative history.
 *
 * Kept free of Android imports so it is plain-JVM unit-testable.
 */
object HabitTimesPayload {

    private val TIME_LENIENT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:m:s")

    /**
     * Parses the payload into `date → sorted list of "HH:mm:ss" times`.
     *
     * Robustness rules (a malformed entry never kills the whole backfill):
     *  - unparseable dates are skipped;
     *  - time strings are normalised to zero-padded "HH:mm:ss" (accepts "9:3:2");
     *  - invalid times (25:00:00, garbage) are dropped individually;
     *  - duplicate times are KEPT — N identical strings mean N units recorded
     *    at that moment, matching how [com.example.tail.data.HabitTimestampRepository]
     *    stores multi-unit increments;
     *  - a date whose every time is invalid yields an EMPTY list, which the
     *    receiver turns into "clear this date's timestamps".
     */
    fun parse(json: String): Map<LocalDate, List<String>> {
        val result = LinkedHashMap<LocalDate, List<String>>()
        val obj = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            return emptyMap()
        }
        for (key in obj.keys()) {
            val date = try {
                LocalDate.parse(key)
            } catch (e: Exception) {
                continue
            }
            val arr = obj.optJSONArray(key) ?: continue
            val times = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                normaliseTime(arr.optString(i))?.let { times.add(it) }
            }
            result[date] = times.sorted()
        }
        return result
    }

    /**
     * Validates and normalises one time string to "HH:mm:ss", or null when it
     * is not a real time of day (so "24:00:00" and "ab:cd" are dropped).
     */
    fun normaliseTime(raw: String): String? {
        val parts = raw.trim().split(":")
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
        return LocalTime.of(h, m, s).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }
}
