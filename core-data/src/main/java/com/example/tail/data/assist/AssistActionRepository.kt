package com.example.tail.data.assist

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.debug.DebugPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AssistActionRepo"
private const val FILE_NAME = "assist_quick_capture.json"

/**
 * Submits "PC action" requests from the quick capture screen to the PC.
 *
 * Works exactly like the debug-bubble pipeline: writes a JSON file
 * (`assist_quick_capture.json`, `{"notes": [...]}` format) into the same
 * SAF-synced directory chosen for [debug_tail.json]. Syncthing carries the
 * file to the PC, where `autoshare_roocode` watches it and submits the text
 * as a new Roo Code task in the VSCode window open on the
 * `quick_capture_assist` project.
 *
 * The file is replaced on each submit (same convention as
 * DebugNoteRepository.submitQueue) so old requests do not accumulate — the
 * PC watcher drains and clears it.
 */
object AssistActionRepository {

    /**
     * Write [text] as an ACTION request to the assist JSON file.
     * Must be called from a background thread (performs file I/O).
     */
    suspend fun submit(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val note = JSONObject().apply {
                put("id", System.currentTimeMillis().toString())
                put("timestamp", timestamp)
                put("screenRoute", "quick_capture")
                put("screenLabel", "Quick Capture")
                put("sourceFile", "")
                put("sourceFunctions", "")
                put("noteType", "ACTION")
                put("noteText", text)
            }
            val jsonText = JSONObject().apply { put("notes", JSONArray().put(note)) }.toString(2)

            val dirUri = DebugPreferences(context).debugFileDirUri
            if (dirUri.isNotBlank() && writeToSaf(context, dirUri, jsonText)) {
                Log.i(TAG, "Assist action queued to SAF file: \"${text.take(60)}\"")
            } else {
                writeToInternal(context, jsonText)
                Log.i(TAG, "Assist action queued to internal file (no SAF dir): \"${text.take(60)}\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write assist action", e)
        }
    }

    private fun writeToSaf(context: Context, dirUriString: String, jsonText: String): Boolean {
        return try {
            val dirUri = Uri.parse(dirUriString)
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, dirUri)
                ?: return false
            docFile.findFile(FILE_NAME)?.delete()
            val newFile = docFile.createFile("application/json", FILE_NAME) ?: return false
            context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
                os.write(jsonText.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: return false
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF write failed: ${e.message}", e)
            false
        }
    }

    private fun writeToInternal(context: Context, jsonText: String) {
        File(context.filesDir, FILE_NAME).writeText(jsonText)
    }
}
