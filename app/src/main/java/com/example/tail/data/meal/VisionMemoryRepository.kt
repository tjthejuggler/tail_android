package com.example.tail.data.meal

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

private const val TAG = "VisionMemory"

/**
 * A single learned image→habit association taught by the user via the tandem
 * voice+camera capture flow (hold the capture button → photo + spoken
 * instruction are sent to the LLM together).
 *
 * Stored as a JSON array in `files/vision_memory.json`. The whole list is
 * injected into every vision system prompt so the LLM "remembers" what each
 * kind of picture means. The user can view and edit these entries in
 * Settings → Vision Memory.
 */
data class VisionMemoryEntry(
    /** Unique identifier (UUID-style string). */
    val id: String,
    /** Epoch milliseconds when the association was taught. */
    val timestamp: Long,
    /** The spoken instruction the user gave while teaching. */
    val voiceNote: String = "",
    /** LLM-generated generalized description of the photo subject. */
    val visualDescription: String = "",
    /** The habit to increment when a similar photo is seen. */
    val habitName: String = "",
    /** Optional subtype within the habit. */
    val subtypeName: String? = null,
    /** Increment amount (default 1). */
    val incrementAmount: Int = 1
)

/**
 * Persists the LLM vision memory — the set of user-taught image→habit
 * associations injected into every vision call.
 *
 * Follows the same internal-storage JSON pattern as [MealLogRepository]
 * and [VisionQueueRepository].
 */
class VisionMemoryRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<List<VisionMemoryEntry>>() {}.type

    private val memoryFile: File
        get() = File(context.filesDir, "vision_memory.json")

    /** Loads all memory entries (oldest-first, as stored). */
    fun loadEntries(): List<VisionMemoryEntry> {
        return try {
            if (!memoryFile.exists()) return emptyList()
            val json = memoryFile.readText()
            val list: List<VisionMemoryEntry> = gson.fromJson(json, listType) ?: emptyList()
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vision memory", e)
            emptyList()
        }
    }

    private fun saveEntries(entries: List<VisionMemoryEntry>) {
        try {
            memoryFile.writeText(gson.toJson(entries))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vision memory", e)
        }
    }

    /** Adds a new taught association and persists it. */
    fun addEntry(entry: VisionMemoryEntry) {
        saveEntries(loadEntries() + entry)
        Log.i(TAG, "Added vision memory entry: '${entry.visualDescription.take(60)}' → ${entry.habitName}")
    }

    /** Updates an existing entry (matched by [VisionMemoryEntry.id]). */
    fun updateEntry(updated: VisionMemoryEntry) {
        saveEntries(loadEntries().map { if (it.id == updated.id) updated else it })
    }

    /** Deletes an entry by id. */
    fun deleteEntry(id: String) {
        saveEntries(loadEntries().filterNot { it.id == id })
    }

    /**
     * Formats the memory as a text block for injection into the vision
     * system prompt. Returns an empty string when nothing has been taught.
     */
    fun buildMemoryPrompt(): String {
        val entries = loadEntries()
        if (entries.isEmpty()) return ""
        return entries.joinToString("\n") { e ->
            val sub = e.subtypeName?.takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""
            val amount = if (e.incrementAmount != 1) " by ${e.incrementAmount}" else ""
            val said = if (e.voiceNote.isNotBlank()) " (user said: \"${e.voiceNote}\")" else ""
            "- ${e.visualDescription} → increment \"$e.habitName\"$sub$amount$said"
        }
    }

    companion object {
        /**
         * Convenience factory for a new entry with a generated id.
         * Memory is TEXT-ONLY by design: a generalized visual description,
         * never the image itself (too large to inject into prompts).
         */
        fun newEntry(
            timestamp: Long,
            voiceNote: String,
            visualDescription: String,
            habitName: String,
            subtypeName: String?,
            incrementAmount: Int
        ): VisionMemoryEntry = VisionMemoryEntry(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            voiceNote = voiceNote,
            visualDescription = visualDescription,
            habitName = habitName,
            subtypeName = subtypeName,
            incrementAmount = incrementAmount
        )
    }
}
