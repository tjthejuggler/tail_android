package com.example.tail.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.adviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "tail_advice")

/**
 * A single piece of advice. In Tail there is only one section: "habits".
 */
data class AdviceItem(
    val id: Long,
    val text: String,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Repository for the advice banner feature, backed by DataStore (JSON-serialised list).
 * Simpler than the Room-based version in Wags since Tail has no database layer.
 */
class AdviceRepository(private val context: Context) {

    private val key = stringPreferencesKey("advice_items_json")
    private val gson = Gson()
    private val type = object : TypeToken<List<AdviceItem>>() {}.type

    /** Observe all advice items reactively. */
    fun observeAll(): Flow<List<AdviceItem>> =
        context.adviceDataStore.data.map { prefs ->
            deserialize(prefs[key] ?: "[]")
        }

    /** One-shot fetch of all advice items. */
    suspend fun getAll(): List<AdviceItem> {
        val prefs = context.adviceDataStore.data.map { it[key] ?: "[]" }
        // Collect first emission
        var result: List<AdviceItem> = emptyList()
        prefs.collect { json -> result = deserialize(json); return@collect }
        return result
    }

    suspend fun add(text: String): Long {
        var newId = 0L
        context.adviceDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "[]").toMutableList()
            newId = if (list.isEmpty()) 1L else (list.maxOf { it.id } + 1)
            list.add(AdviceItem(id = newId, text = text.trim()))
            prefs[key] = serialize(list)
        }
        return newId
    }

    suspend fun update(entity: AdviceItem) {
        context.adviceDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.id == entity.id }
            if (idx >= 0) list[idx] = entity
            prefs[key] = serialize(list)
        }
    }

    suspend fun delete(id: Long) {
        context.adviceDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "[]").toMutableList()
            list.removeAll { it.id == id }
            prefs[key] = serialize(list)
        }
    }

    suspend fun updateNotes(adviceId: Long, notes: String?) {
        context.adviceDataStore.edit { prefs ->
            val list = deserialize(prefs[key] ?: "[]").toMutableList()
            val idx = list.indexOfFirst { it.id == adviceId }
            if (idx >= 0) list[idx] = list[idx].copy(notes = notes)
            prefs[key] = serialize(list)
        }
    }

    private fun serialize(items: List<AdviceItem>): String = gson.toJson(items, type)
    private fun deserialize(json: String): List<AdviceItem> =
        try { gson.fromJson(json, type) ?: emptyList() } catch (_: Exception) { emptyList() }
}
