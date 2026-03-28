package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads/writes per-habit subtype breakdown JSON files via SAF URI.
 * File format: { "2026-01-15": { "chinups": 5, "wide": 3 }, ... }
 */
class SubtypeDataRepository {
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val mapType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

    /** Loads the full subtype data file. Returns empty map on error. */
    suspend fun loadSubtypeData(uri: Uri, context: Context): Map<String, Map<String, Int>> =
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    gson.fromJson<Map<String, Map<String, Int>>>(text, mapType) ?: emptyMap()
                } ?: emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }
        }

    /** Saves the full subtype data file. */
    suspend fun saveSubtypeData(uri: Uri, context: Context, data: Map<String, Map<String, Int>>) =
        withContext(Dispatchers.IO) {
            try {
                val json = prettyGson.toJson(data)
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { it.write(json) }
                }
            } catch (e: Exception) {
                // Best-effort
            }
        }

    /** Gets the breakdown for a single date. */
    suspend fun getBreakdownForDate(
        uri: Uri, context: Context, dateStr: String
    ): Map<String, Int> {
        val data = loadSubtypeData(uri, context)
        return data[dateStr] ?: emptyMap()
    }

    /** Adds increments to a single date's breakdown and saves. */
    suspend fun addToDate(
        uri: Uri, context: Context, dateStr: String, increments: Map<String, Int>
    ) {
        val data = loadSubtypeData(uri, context).toMutableMap()
        val existing = data[dateStr]?.toMutableMap() ?: mutableMapOf()
        for ((subtype, amount) in increments) {
            if (amount > 0) {
                existing[subtype] = (existing[subtype] ?: 0) + amount
            }
        }
        data[dateStr] = existing
        saveSubtypeData(uri, context, data.toSortedMap())
    }
}
