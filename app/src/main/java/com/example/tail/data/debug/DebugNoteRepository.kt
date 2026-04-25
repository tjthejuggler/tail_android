package com.example.tail.data.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.debug.ScreenContextMapper.ScreenContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DebugNoteRepo"
private const val FILE_NAME = "debug_tail.json"

/**
 * Note type for debug notes.
 */
enum class NoteType(val label: String) {
    BUG("Bug"),
    FEATURE("Feature"),
    NOTE("Note")
}

/**
 * A single debug note entry as stored in the JSON file.
 */
data class DebugNoteEntry(
    val id: String,
    val timestamp: String,
    val screenRoute: String,
    val screenLabel: String,
    val sourceFile: String,
    val sourceFunctions: String,
    val noteType: String,
    val noteText: String
)

/**
 * A draft note — saved per-screen but not yet queued for submission.
 */
data class DebugDraft(
    val screenRoute: String,
    val noteType: NoteType = NoteType.BUG,
    val noteText: String = ""
)

/**
 * A queued note — ready to be submitted to the JSON file.
 */
data class QueuedNote(
    val id: String = System.currentTimeMillis().toString(),
    val timestamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
    val screenRoute: String,
    val screenLabel: String,
    val sourceFile: String,
    val sourceFunctions: String,
    val noteType: NoteType,
    val noteText: String
)

/**
 * Manages debug notes with a draft → queue → submit flow.
 *
 * - **Drafts**: per-screen scratchpads that persist across dialog opens
 * - **Queue**: notes ready for batch submission (drives the bubble badge)
 * - **Submit**: writes all queued notes to [debug_tail.json] and clears them
 *
 * After submission, notes are completely gone from the app —
 * they only live in the JSON file for a programmer LLM to read.
 */
class DebugNoteRepository(
    private val context: Context,
    private val debugPrefs: DebugPreferences
) {
    // ── Drafts (per-screen, in-memory) ────────────────────────────────────────

    private val _drafts = MutableStateFlow<Map<String, DebugDraft>>(emptyMap())
    val drafts: StateFlow<Map<String, DebugDraft>> = _drafts.asStateFlow()

    fun saveDraft(screenRoute: String, noteType: NoteType, noteText: String) {
        val current = _drafts.value.toMutableMap()
        current[screenRoute] = DebugDraft(screenRoute, noteType, noteText)
        _drafts.value = current
    }

    fun getDraft(screenRoute: String): DebugDraft? = _drafts.value[screenRoute]

    // ── Queue (in-memory, visible in dialog, drives badge) ────────────────────

    private val _queue = MutableStateFlow<List<QueuedNote>>(emptyList())
    val queue: StateFlow<List<QueuedNote>> = _queue.asStateFlow()

    val queueSize: Int get() = _queue.value.size

    fun enqueueNote(
        screenRoute: String,
        screenContext: ScreenContext,
        noteType: NoteType,
        noteText: String
    ) {
        val note = QueuedNote(
            screenRoute = screenRoute,
            screenLabel = screenContext.label,
            sourceFile = screenContext.sourceFile,
            sourceFunctions = screenContext.sourceFunctions,
            noteType = noteType,
            noteText = noteText
        )
        _queue.value = _queue.value + note

        // Clear the draft for this screen since it's been queued
        val current = _drafts.value.toMutableMap()
        current.remove(screenRoute)
        _drafts.value = current
    }

    fun removeFromQueue(noteId: String) {
        _queue.value = _queue.value.filter { it.id != noteId }
    }

    /**
     * Submit all queued notes to the JSON file and clear the queue.
     * After submission, notes are completely gone from the app —
     * they only live in the JSON file.
     */
    suspend fun submitQueue() = withContext(Dispatchers.IO) {
        val queued = _queue.value
        if (queued.isEmpty()) return@withContext

        // Load existing notes from file
        val existingNotes = loadExistingEntries().toMutableList()

        // Add queued notes
        queued.forEach { qn ->
            existingNotes.add(DebugNoteEntry(
                id = qn.id,
                timestamp = qn.timestamp,
                screenRoute = qn.screenRoute,
                screenLabel = qn.screenLabel,
                sourceFile = qn.sourceFile,
                sourceFunctions = qn.sourceFunctions,
                noteType = qn.noteType.name,
                noteText = qn.noteText
            ))
        }

        // Write combined
        writeEntriesToFile(existingNotes)

        // Clear queue — notes are now only in the file, not in the app
        _queue.value = emptyList()

        // Refresh saved notes from file so the green indicator updates
        loadSavedNotes()
    }

    // ── Submitted notes (from file, for "saved" indicator) ────────────────────

    private val _savedNotesByScreen = MutableStateFlow<Map<String, List<DebugNoteEntry>>>(emptyMap())
    val savedNotesByScreen: StateFlow<Map<String, List<DebugNoteEntry>>> = _savedNotesByScreen.asStateFlow()

    init {
        loadSavedNotes()
    }

    fun hasSavedNotesForScreen(route: String?): Boolean {
        if (route == null) return false
        return (_savedNotesByScreen.value[route]?.size ?: 0) > 0
    }

    fun savedNoteCountForScreen(route: String?): Int {
        if (route == null) return 0
        return _savedNotesByScreen.value[route]?.size ?: 0
    }

    // ── Badge / indicator (queued = yellow, saved = green) ────────────────────

    fun hasNotesForScreen(route: String?): Boolean {
        if (route == null) return false
        return hasQueuedNotesForScreen(route) || hasSavedNotesForScreen(route)
    }

    fun queuedNoteCountForScreen(route: String?): Int {
        if (route == null) return 0
        return _queue.value.count { it.screenRoute == route }
    }

    fun hasQueuedNotesForScreen(route: String): Boolean {
        return _queue.value.any { it.screenRoute == route }
    }

    private fun loadSavedNotes() {
        try {
            val entries = loadExistingEntries()
            _savedNotesByScreen.value = entries.groupBy { it.screenRoute }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load saved notes", e)
        }
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private fun loadExistingEntries(): List<DebugNoteEntry> {
        val dirUri = debugPrefs.debugFileDirUri
        val contents = if (dirUri.isNotBlank()) {
            readFromSaf(dirUri)
        } else {
            readFromInternal()
        }
        if (contents.isNullOrBlank()) return emptyList()
        return parseEntries(contents)
    }

    private fun readFromInternal(): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return file.readText()
    }

    private fun readFromSaf(dirUriString: String): String? {
        return try {
            val dirUri = Uri.parse(dirUriString)
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, dirUri)
                ?: return null
            val existing = docFile.findFile(FILE_NAME)
            if (existing == null || !existing.exists()) return null
            context.contentResolver.openInputStream(existing.uri)?.bufferedReader()?.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read from SAF", e)
            readFromInternal()
        }
    }

    private fun parseEntries(contents: String): List<DebugNoteEntry> {
        val root = JSONObject(contents)
        val arr = root.optJSONArray("notes") ?: return emptyList()
        val notes = mutableListOf<DebugNoteEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            notes.add(DebugNoteEntry(
                id = obj.getString("id"),
                timestamp = obj.getString("timestamp"),
                screenRoute = obj.getString("screenRoute"),
                screenLabel = obj.getString("screenLabel"),
                sourceFile = obj.getString("sourceFile"),
                sourceFunctions = obj.getString("sourceFunctions"),
                noteType = obj.getString("noteType"),
                noteText = obj.getString("noteText")
            ))
        }
        return notes
    }

    private fun writeEntriesToFile(entries: List<DebugNoteEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(JSONObject().apply {
                    put("id", entry.id)
                    put("timestamp", entry.timestamp)
                    put("screenRoute", entry.screenRoute)
                    put("screenLabel", entry.screenLabel)
                    put("sourceFile", entry.sourceFile)
                    put("sourceFunctions", entry.sourceFunctions)
                    put("noteType", entry.noteType)
                    put("noteText", entry.noteText)
                })
            }
            val jsonText = JSONObject().apply { put("notes", arr) }.toString(2)

            val dirUri = debugPrefs.debugFileDirUri
            if (dirUri.isNotBlank()) {
                writeToSaf(dirUri, jsonText)
            } else {
                writeToInternal(jsonText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug notes", e)
        }
    }

    private fun writeToInternal(jsonText: String) {
        val file = File(context.filesDir, FILE_NAME)
        file.writeText(jsonText)
    }

    private fun writeToSaf(dirUriString: String, jsonText: String) {
        val dirUri = Uri.parse(dirUriString)
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, dirUri)
            ?: run {
                Log.e(TAG, "Cannot access SAF directory, falling back to internal")
                writeToInternal(jsonText)
                return
            }
        docFile.findFile(FILE_NAME)?.delete()
        val newFile = docFile.createFile("application/json", FILE_NAME) ?: run {
            Log.e(TAG, "Cannot create file in SAF directory, falling back to internal")
            writeToInternal(jsonText)
            return
        }
        context.contentResolver.openOutputStream(newFile.uri)?.use { os ->
            os.write(jsonText.toByteArray(Charsets.UTF_8))
            os.flush()
        } ?: run {
            Log.e(TAG, "Cannot open output stream, falling back to internal")
            writeToInternal(jsonText)
        }
    }
}
