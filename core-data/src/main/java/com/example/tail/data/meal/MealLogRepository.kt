package com.example.tail.data.meal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "MealLogRepo"

/**
 * Captures (photos/taps/voice) within this window of a meal group's FIRST
 * entry merge into that meal — one meal, one habit increment.
 */
const val MEAL_GROUP_WINDOW_MS: Long = 60 * 60 * 1000L

/**
 * Manages meal log entries and their associated images in the app's internal storage.
 *
 * Layout:
 *  - `files/meal_logs/<sanitised-habit-name>.json`  — JSON array of [MealLog] entries
 *  - `files/meal_images/<uuid>.jpg`                  — compressed captured photos
 *
 * This follows the same internal-storage pattern as [com.example.tail.data.AiIconRepository].
 */
class MealLogRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<MealLog>>() {}.type

    private val logsDir: File
        get() = File(context.filesDir, "meal_logs").also { it.mkdirs() }

    private val imagesDir: File
        get() = File(context.filesDir, "meal_images").also { it.mkdirs() }

    /** Sanitises a habit name for use as a filename component. */
    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    private fun logFile(habitId: String): File =
        File(logsDir, "${sanitise(habitId)}.json")

    // ── Image storage ───────────────────────────────────────────────────

    /**
     * Saves a compressed JPEG copy of [bitmap] and returns the relative path
     * (relative to [Context.filesDir]) that can be stored in [MealLog.imageUri].
     */
    fun saveImage(bitmap: Bitmap): String {
        val id = UUID.randomUUID().toString()
        val file = File(imagesDir, "$id.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        // Return path relative to filesDir so it's portable across installs
        return "meal_images/$id.jpg"
    }

    /**
     * Saves raw JPEG bytes (e.g. from CameraX) and returns the relative path.
     */
    fun saveImageBytes(bytes: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val file = File(imagesDir, "$id.jpg")
        file.writeBytes(bytes)
        return "meal_images/$id.jpg"
    }

    /** Resolves a relative path (from [MealLog.imageUri]) to an absolute [File]. */
    fun resolveImage(relativePath: String?): File? {
        if (relativePath.isNullOrBlank()) return null
        val file = File(context.filesDir, relativePath)
        return if (file.exists()) file else null
    }

    /** Loads a Bitmap from a relative path, or null if the file doesn't exist. */
    fun loadImageBitmap(relativePath: String?): Bitmap? {
        val file = resolveImage(relativePath) ?: return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode image $relativePath", e)
            null
        }
    }

    /** Deletes an image file by its relative path. */
    fun deleteImage(relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        resolveImage(relativePath)?.delete()
    }

    // ── MealLog CRUD ────────────────────────────────────────────────────

    /**
     * Normalises a log loaded from JSON: migrates the legacy single
     * [MealLog.imageUri] into [MealLog.imageUris] (Gson leaves fields that
     * are missing in old files at null).
     */
    private fun MealLog.normalized(): MealLog =
        if (imageUris == null) copy(imageUris = listOfNotNull(imageUri)) else this

    /** Returns all meal logs for the given habit, sorted newest-first. */
    fun loadLogs(habitId: String): List<MealLog> {
        return try {
            val file = logFile(habitId)
            if (!file.exists()) return emptyList()
            val json = file.readText()
            val list: List<MealLog> = gson.fromJson(json, listType) ?: emptyList()
            list.map { it.normalized() }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load meal logs for $habitId", e)
            emptyList()
        }
    }

    /**
     * Returns the meal group that is still "active" at [at] (default: now) —
     * i.e. the newest log whose capture-group anchor is within
     * [MEAL_GROUP_WINDOW_MS]. Captures that land in this window merge into
     * that meal instead of creating a new one (single meal increment).
     */
    fun findActiveGroup(
        habitId: String,
        at: Long = System.currentTimeMillis(),
        windowMs: Long = MEAL_GROUP_WINDOW_MS
    ): MealLog? {
        val recent = loadLogs(habitId).firstOrNull() ?: run {
            QcDiag.log("GROUP", "findActiveGroup('$habitId'): no logs at all → null")
            return null
        }
        val delta = at - recent.anchorTime()
        return if (delta <= windowMs) {
            QcDiag.log(
                "GROUP",
                "findActiveGroup('$habitId'): ACTIVE group ${QcDiag.short(recent.id)} " +
                    "'${recent.title}' anchorDeltaMs=$delta ≤ windowMs=$windowMs → merge"
            )
            recent
        } else {
            QcDiag.log(
                "GROUP",
                "findActiveGroup('$habitId'): newest ${QcDiag.short(recent.id)} too old " +
                    "(anchorDeltaMs=$delta > windowMs=$windowMs) → null"
            )
            null
        }
    }

    /**
     * Index of every ingredient tag used by this habit's meals, mapped to the
     * number of meals carrying it. Powers the tag filter chips and future
     * graphing/search over ingredients.
     */
    fun allIngredientTags(habitId: String): Map<String, Int> {
        return loadLogs(habitId)
            .flatMap { it.ingredientsDetected }
            .groupingBy { it }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
            .toMap()
    }

    /** Adds a new meal log entry and persists it. */
    fun addLog(log: MealLog) {
        try {
            val current = loadLogs(log.habitId).toMutableList()
            current.add(0, log)
            logFile(log.habitId).writeText(gson.toJson(current))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add meal log", e)
        }
    }

    /** Updates an existing meal log entry (matched by [MealLog.id]). */
    fun updateLog(updated: MealLog) {
        try {
            val current = loadLogs(updated.habitId).toMutableList()
            val idx = current.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                current[idx] = updated
                logFile(updated.habitId).writeText(gson.toJson(current))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update meal log ${updated.id}", e)
        }
    }

    /** Deletes a meal log entry by id. Also removes its image if present. */
    fun deleteLog(habitId: String, logId: String) {
        try {
            val current = loadLogs(habitId).toMutableList()
            val target = current.find { it.id == logId }
            if (target != null) {
                deleteImage(target.imageUri)
                current.removeAll { it.id == logId }
                logFile(habitId).writeText(gson.toJson(current))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete meal log $logId", e)
        }
    }

    /** Deletes all meal logs and images for a habit (used when disabling the meal type). */
    fun deleteAllForHabit(habitId: String) {
        try {
            val logs = loadLogs(habitId)
            logs.forEach { deleteImage(it.imageUri) }
            logFile(habitId).delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete all logs for $habitId", e)
        }
    }

    /**
     * Returns the total calorie count for a given date (YYYY-MM-DD).
     * Used for the meal detail screen's daily summary.
     */
    fun totalCaloriesForDate(habitId: String, dateStr: String): Int {
        return loadLogs(habitId)
            .filter { formatEpochDate(it.timestamp) == dateStr }
            .sumOf { it.calories }
    }

    /**
     * Aggregated macro/nutrition totals for a single day.
     */
    data class DayTotals(
        val calories: Int = 0,
        val proteinGrams: Double = 0.0,
        val carbsGrams: Double = 0.0,
        val fatGrams: Double = 0.0,
        val mealCount: Int = 0
    )

    /**
     * Returns the full macro breakdown for a single date (YYYY-MM-DD).
     * Used by the graph day-details popup to show daily totals.
     */
    fun dayTotals(habitId: String, dateStr: String): DayTotals {
        val dayLogs = loadLogs(habitId).filter { formatEpochDate(it.timestamp) == dateStr }
        return DayTotals(
            calories = dayLogs.sumOf { it.calories },
            proteinGrams = dayLogs.sumOf { it.macronutrients.proteinGrams },
            carbsGrams = dayLogs.sumOf { it.macronutrients.carbsGrams },
            fatGrams = dayLogs.sumOf { it.macronutrients.fatGrams },
            mealCount = dayLogs.size
        )
    }

    /**
     * Returns per-day macro aggregates for every day in [startDate, endDate].
     *
     * The result maps "YYYY-MM-DD" → [DayTotals]. Days with no meals are omitted
     * (callers treat missing days as zero). This is used by the graph to plot
     * calories / protein / carbs / fat as separate time-series.
     */
    fun dailyAggregates(
        habitId: String,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate
    ): Map<String, DayTotals> {
        val logs = loadLogs(habitId)
        if (logs.isEmpty()) return emptyMap()

        val startStr = startDate.toString()
        val endStr = endDate.toString()
        return logs
            .filter {
                val ds = formatEpochDate(it.timestamp)
                ds >= startStr && ds <= endStr
            }
            .groupBy { formatEpochDate(it.timestamp) }
            .mapValues { (_, dayLogs) ->
                DayTotals(
                    calories = dayLogs.sumOf { it.calories },
                    proteinGrams = dayLogs.sumOf { it.macronutrients.proteinGrams },
                    carbsGrams = dayLogs.sumOf { it.macronutrients.carbsGrams },
                    fatGrams = dayLogs.sumOf { it.macronutrients.fatGrams },
                    mealCount = dayLogs.size
                )
            }
    }

    /** Formats an epoch-millis timestamp as "YYYY-MM-DD". */
    private fun formatEpochDate(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        return instant.atZone(java.time.ZoneId.systemDefault())
            .toLocalDate().toString()
    }
}
