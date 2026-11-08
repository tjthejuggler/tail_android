package com.example.tail.data.bloodtest

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Manages the per-habit blood-test entry database in internal storage.
 *
 * Layout: `files/blood_tests/<sanitised-habit-name>.json` — JSON array of
 * [BloodTestEntry]. Same internal-storage pattern as MealLogRepository.
 */
class BloodTestRepository(private val context: Context) {

    companion object {
        private const val TAG = "BloodTestRepo"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<BloodTestEntry>>() {}.type

    private val dir: File
        get() = File(context.filesDir, "blood_tests").also { it.mkdirs() }

    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)

    private fun file(habitId: String): File = File(dir, "${sanitise(habitId)}.json")

    /** All entries for the habit, newest-first. */
    fun loadEntries(habitId: String): List<BloodTestEntry> {
        return try {
            val f = file(habitId)
            if (!f.exists()) return emptyList()
            val list: List<BloodTestEntry> = gson.fromJson(f.readText(), listType) ?: emptyList()
            list.sortedWith(compareByDescending<BloodTestEntry> { it.date }.thenByDescending { it.time ?: "" })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load blood test entries for $habitId", e)
            emptyList()
        }
    }

    /** Appends an entry and persists it. Returns the stored entry (id assigned if blank). */
    fun addEntry(habitId: String, entry: BloodTestEntry): BloodTestEntry {
        val stored = if (entry.id.isBlank()) entry.copy(id = UUID.randomUUID().toString()) else entry
        try {
            val current = loadEntries(habitId).toMutableList()
            current.add(0, stored)
            file(habitId).writeText(gson.toJson(current))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add blood test entry", e)
        }
        return stored
    }

    /** Replaces an entry (matched by id). */
    fun updateEntry(habitId: String, updated: BloodTestEntry) {
        try {
            val current = loadEntries(habitId).toMutableList()
            val idx = current.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                current[idx] = updated
                file(habitId).writeText(gson.toJson(current))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update blood test entry ${updated.id}", e)
        }
    }

    /** Deletes an entry by id. */
    fun deleteEntry(habitId: String, entryId: String) {
        try {
            val current = loadEntries(habitId).filter { it.id != entryId }
            file(habitId).writeText(gson.toJson(current))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete blood test entry $entryId", e)
        }
    }

    // ── Aggregations ─────────────────────────────────────────────────────

    /**
     * Every distinct item name ever measured, ordered most-recently-measured
     * first (ties broken by frequency). Powers the manual-entry form and the
     * graph item filter.
     */
    fun uniqueItemNames(habitId: String): List<String> {
        val entries = loadEntries(habitId)
        val lastDate = HashMap<String, String>()
        val count = HashMap<String, Int>()
        for (e in entries) {
            for (item in e.items) {
                val key = item.name.trim()
                if (key.isEmpty()) continue
                lastDate[key] = maxOf(lastDate[key] ?: "", e.date)
                count[key] = (count[key] ?: 0) + 1
            }
        }
        return lastDate.keys.sortedWith(
            compareByDescending<String> { lastDate[it] }.thenByDescending { count[it] ?: 0 }.thenBy { it.lowercase() }
        )
    }

    /** Latest known unit for an item (for pre-filling the manual form). */
    fun lastUnitFor(habitId: String, itemName: String): String? =
        loadEntries(habitId).asSequence()
            .flatMap { it.items }
            .filter { it.name.equals(itemName.trim(), ignoreCase = true) }
            .mapNotNull { it.unit }
            .firstOrNull()

    /** Most recent measurement of [itemName] (entry, item) or null. */
    fun latestValue(habitId: String, itemName: String): Pair<BloodTestEntry, BloodTestItem>? {
        val target = itemName.trim()
        for (e in loadEntries(habitId)) {
            e.items.firstOrNull { it.name.equals(target, ignoreCase = true) }
                ?.let { return e to it }
        }
        return null
    }

    /** "YYYY-MM-DD" → number of items measured that day (sum over entries). */
    fun dailyItemCounts(habitId: String): Map<String, Int> =
        loadEntries(habitId).groupBy { it.date }
            .mapValues { (_, entries) -> entries.sumOf { it.items.size } }

    /** "YYYY-MM-DD" → ×100-scaled item values measured that day (latest per item). */
    fun dailyItemValues(habitId: String): Map<String, Map<String, Double>> {
        val out = HashMap<String, MutableMap<String, Double>>()
        // loadEntries is newest-first, so inserting with putIfAbsent keeps the
        // LATEST measurement of an item on a given date.
        for (e in loadEntries(habitId)) {
            val day = out.getOrPut(e.date) { HashMap() }
            for (item in e.items) {
                if (item.name.isNotBlank()) day.putIfAbsent(item.name.trim(), item.value * 100)
            }
        }
        return out
    }
}
