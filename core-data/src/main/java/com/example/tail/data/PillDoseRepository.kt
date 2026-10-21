package com.example.tail.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "PillDoseRepo"
private const val DIR = "pill_dose_data"
private val TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** JSON shape persisted per habit: date "YYYY-MM-DD" → parsed day. */
data class PillDoseFile(val days: MutableMap<String, PillDayDoses> = LinkedHashMap())

/**
 * Persists the STRUCTURED per-day pill dose data for pillLogHabits
 * (servings per pill + per-nutrient daily doses + presence-only lines).
 *
 * Storage is an internal JSON file per habit
 * (`filesDir/pill_dose_data/<safeHabitName>.json`) — fully additive: the raw
 * free-text log (external SAF file + TextInputRepository internal mirror)
 * is the untouched source of truth for the verbatim lines; this sidecar only
 * holds derived, recomputable dose summaries so downstream dose-response
 * analyses get graded data without re-parsing history on every read.
 */
class PillDoseRepository {

    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val fileType = object : TypeToken<PillDoseFile>() {}.type

    private fun fileFor(context: Context, habitName: String): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val safe = habitName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
        return File(dir, "$safe.json")
    }

    /** Loads the whole dose file; empty when none exists yet. */
    suspend fun load(context: Context, habitName: String): PillDoseFile =
        withContext(Dispatchers.IO) {
            val f = fileFor(context, habitName)
            if (!f.exists()) return@withContext PillDoseFile()
            try {
                gson.fromJson<PillDoseFile>(f.readText(), fileType) ?: PillDoseFile()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read dose file for '$habitName': ${e.message}")
                PillDoseFile()
            }
        }

    /** Atomic full-file save. */
    suspend fun save(context: Context, habitName: String, data: PillDoseFile) {
        withContext(Dispatchers.IO) {
            try {
                fileFor(context, habitName).writeText(prettyGson.toJson(data))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save dose file for '$habitName': ${e.message}")
            }
        }
    }

    /** One day's structured doses, or an empty result when absent. */
    suspend fun loadDay(context: Context, habitName: String, date: LocalDate): PillDayDoses =
        load(context, habitName).days[date.toString()] ?: PillDayDoses()

    /**
     * Recomputes one day's entry from that day's raw log lines and persists it.
     */
    suspend fun recomputeDay(
        context: Context,
        habitName: String,
        date: LocalDate,
        lines: List<String>,
        catalog: Map<String, String>
    ) {
        val data = load(context, habitName)
        data.days[date.toString()] = PillLogParser.parseDay(lines, catalog)
        save(context, habitName, data)
    }

    /**
     * Full-history backfill: parses the ENTIRE raw text log (timestamp →
     * free text, values possibly '\n'-joined multi-line) against the catalog
     * and (re)writes every day's structured dose entry. Existing entries are
     * overwritten deterministically — backfill and live parsing produce the
     * same values by construction (same [PillLogParser.parseDay] call).
     *
     * @return number of days written.
     */
    suspend fun backfillFromTextLog(
        context: Context,
        habitName: String,
        textLog: Map<String, String>,
        catalog: Map<String, String>
    ): Int = withContext(Dispatchers.IO) {
        val byDate = LinkedHashMap<String, MutableList<String>>()
        for ((ts, value) in textLog) {
            val date = runCatching {
                LocalDateTime.parse(ts, TS_FMT).toLocalDate().toString()
            }.getOrNull() ?: runCatching {
                ts.take(10) // "YYYY-MM-DD..." fallback for odd keys
            }.getOrNull()?.takeIf { it.length == 10 && it[4] == '-' } ?: continue
            byDate.getOrPut(date) { mutableListOf() }.addAll(PillLogParser.splitLines(value))
        }
        val data = PillDoseFile()
        for ((date, lines) in byDate) {
            data.days[date] = PillLogParser.parseDay(lines, catalog)
        }
        save(context, habitName, data)
        Log.i(TAG, "Backfilled ${data.days.size} days of pill doses for '$habitName'")
        data.days.size
    }
}
