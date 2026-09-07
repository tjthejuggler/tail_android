package com.example.tail.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Metadata for a single AI-generated icon stored on disk.
 */
data class AiIcon(
    /** Unique identifier (used as filename without extension). */
    val id: String,
    /** The prompt that was used to generate this icon. */
    val prompt: String,
    /** Timestamp (ISO-8601) when the icon was generated. */
    val createdAt: String
)

/**
 * Manages AI-generated icon files in the app's internal storage.
 *
 * Icons are stored as white-on-transparent PNGs in `files/ai_icons/`.
 * A JSON index file (`ai_icons_index.json`) tracks metadata for each icon.
 */
class AiIconRepository(private val context: Context) {

    private val gson = Gson()
    private val iconsDir: File
        get() = File(context.filesDir, "ai_icons").also { it.mkdirs() }
    private val indexFile: File
        get() = File(iconsDir, "ai_icons_index.json")

    /** Returns the list of all stored AI icon metadata, sorted newest-first. */
    fun listIcons(): List<AiIcon> {
        return try {
            if (!indexFile.exists()) return emptyList()
            val json = indexFile.readText()
            val type = object : TypeToken<List<AiIcon>>() {}.type
            val list: List<AiIcon> = gson.fromJson(json, type) ?: emptyList()
            list.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Log.e("AiIconRepo", "Failed to read index", e)
            emptyList()
        }
    }

    /** Returns the File for a given icon id, or null if it doesn't exist. */
    fun getIconFile(iconId: String): File? {
        val file = File(iconsDir, "$iconId.png")
        return if (file.exists()) file else null
    }

    /** Loads a Bitmap for the given icon id, or null if not found. */
    fun loadBitmap(iconId: String): Bitmap? {
        val file = getIconFile(iconId) ?: return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e("AiIconRepo", "Failed to decode $iconId", e)
            null
        }
    }

    /**
     * Saves a new AI-generated icon.
     * @param bitmap The processed (white-on-transparent) bitmap to save.
     * @param prompt The prompt used to generate it.
     * @return The AiIcon metadata for the saved icon.
     */
    fun saveIcon(bitmap: Bitmap, prompt: String): AiIcon {
        val id = "ai_${System.currentTimeMillis()}"
        val createdAt = java.time.Instant.now().toString()
        val file = File(iconsDir, "$id.png")

        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val icon = AiIcon(id = id, prompt = prompt, createdAt = createdAt)
        val current = listIcons().toMutableList()
        current.add(0, icon)
        indexFile.writeText(gson.toJson(current))

        Log.i("AiIconRepo", "Saved AI icon $id (${bitmap.width}x${bitmap.height})")
        return icon
    }

    /** Deletes an AI icon by id. */
    fun deleteIcon(iconId: String) {
        val file = File(iconsDir, "$iconId.png")
        if (file.exists()) file.delete()

        val current = listIcons().toMutableList()
        current.removeAll { it.id == iconId }
        indexFile.writeText(gson.toJson(current))
    }

    /** Returns all stored icon IDs (for use in the icon picker). */
    fun allIconIds(): List<String> = listIcons().map { it.id }
}
