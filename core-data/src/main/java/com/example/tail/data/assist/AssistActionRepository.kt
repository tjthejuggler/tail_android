package com.example.tail.data.assist

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.BridgeClient
import com.example.tail.data.SettingsRepository
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.debug.DebugPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * TWO transports, tried in order:
 *
 * 1. **Tail Bridge (preferred, instant)** — POST /api/v1/assist/action on the
 *    same bridge server the movie/widget features already use (connection
 *    auto-derived from the Garmin proxy settings). The bridge appends the
 *    text to the quick_capture_assist dispatcher queue on the PC; the
 *    dispatcher (inotify) runs the fast-path lookup table within
 *    milliseconds — e.g. "play the next episode" starts VLC before any LLM
 *    is involved. Handled in ~100–300 ms end-to-end on the LAN.
 *
 * 2. **SAF file via Syncthing (fallback)** — the original path: writes
 *    `assist_quick_capture.json` (`{"notes": [...]}`) into the SAF-synced
 *    directory chosen for debug_tail.json. The PC-side autoshare watcher now
 *    routes this file through the same dispatcher, so both transports land
 *    in the same pipeline; the file path is just slower (Syncthing sync
 *    interval) and can produce sync-conflict files.
 */
object AssistActionRepository {

    /**
     * Submit [text] as an ACTION request to the PC.
     * Bridge-first; falls back to the SAF file when the bridge is
     * unreachable / unconfigured. Must be called from a coroutine
     * (performs network + file I/O).
     */
    suspend fun submit(context: Context, text: String) = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext

        if (submitViaBridge(context, text)) {
            Log.i(TAG, "Assist action sent via Tail Bridge: \"${text.take(60)}\"")
            return@withContext
        }

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
                Log.i(TAG, "Assist action queued to SAF file (bridge unavailable): \"${text.take(60)}\"")
            } else {
                writeToInternal(context, jsonText)
                Log.i(TAG, "Assist action queued to internal file (no bridge, no SAF dir): \"${text.take(60)}\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write assist action", e)
        }
    }

    /** POST to the Tail Bridge; true when the bridge acked (HTTP 200). */
    private suspend fun submitViaBridge(context: Context, text: String): Boolean {
        return try {
            val settings = SettingsRepository(context).settingsFlow.first()
            val bridge = bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
                ?: return false
            val body = JSONObject().apply {
                put("id", System.currentTimeMillis().toString())
                put("text", text)
            }
            BridgeClient().post(bridge.first, bridge.second, "assist/action", body) != null
        } catch (e: Exception) {
            Log.w(TAG, "Bridge submit failed, falling back to file: ${e.message}")
            false
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
