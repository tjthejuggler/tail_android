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
 * A saved note — persisted in SharedPreferences, visible in the Saved tab.
 * Can be edited or queued for submission.
 */
data class SavedNote(
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
 * Manages debug notes with a save → queue → submit flow.
 *
 * - **Saved**: notes persisted in SharedPreferences, visible in the Saved tab
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
    // ── Saved notes (persisted to SharedPreferences) ──────────────────

    private val _savedNotes = MutableStateFlow<List<SavedNote>>(debugPrefs.loadSavedNotes())
    val savedNotes: StateFlow<List<SavedNote>> = _savedNotes.asStateFlow()

    fun saveNote(
        screenRoute: String,
        screenContext: ScreenContext,
        noteType: NoteType,
        noteText: String
    ) {
        val note = SavedNote(
            screenRoute = screenRoute,
            screenLabel = screenContext.label,
            sourceFile = screenContext.sourceFile,
            sourceFunctions = screenContext.sourceFunctions,
            noteType = noteType,
            noteText = noteText
        )
        _savedNotes.value = _savedNotes.value + note
        debugPrefs.saveSavedNotes(_savedNotes.value)
    }

    fun updateSavedNote(noteId: String, noteType: NoteType, noteText: String) {
        _savedNotes.value = _savedNotes.value.map {
            if (it.id == noteId) it.copy(noteType = noteType, noteText = noteText) else it
        }
        debugPrefs.saveSavedNotes(_savedNotes.value)
    }

    fun deleteSavedNote(noteId: String) {
        _savedNotes.value = _savedNotes.value.filter { it.id != noteId }
        debugPrefs.saveSavedNotes(_savedNotes.value)
    }

    fun queueSavedNote(noteId: String) {
        val note = _savedNotes.value.find { it.id == noteId } ?: return
        _savedNotes.value = _savedNotes.value.filter { it.id != noteId }
        debugPrefs.saveSavedNotes(_savedNotes.value)

        val queued = QueuedNote(
            id = note.id,
            timestamp = note.timestamp,
            screenRoute = note.screenRoute,
            screenLabel = note.screenLabel,
            sourceFile = note.sourceFile,
            sourceFunctions = note.sourceFunctions,
            noteType = note.noteType,
            noteText = note.noteText
        )
        _queue.value = _queue.value + queued
    }

    fun hasSavedNotesForScreen(route: String?): Boolean {
        if (route == null) return false
        return _savedNotes.value.any { it.screenRoute == route }
    }

    fun savedNoteCountForScreen(route: String?): Int {
        if (route == null) return 0
        return _savedNotes.value.count { it.screenRoute == route }
    }

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
    }

    fun removeFromQueue(noteId: String) {
        _queue.value = _queue.value.filter { it.id != noteId }
    }

    /**
     * Submit all queued notes to the JSON file and clear the queue.
     * After submission, notes are completely gone from the app —
     * they only live in the JSON file. The file is overwritten each time
     * so old submitted notes do not accumulate.
     */
    suspend fun submitQueue() = withContext(Dispatchers.IO) {
        // Atomically drain the queue first — prevents double-submit if called
        // concurrently (e.g. user taps "Submit All" twice quickly).
        val queued = _queue.value
        if (queued.isEmpty()) return@withContext
        _queue.value = emptyList()

        // Write ONLY the newly submitted notes — replace the file entirely
        val entries = queued.map { qn ->
            DebugNoteEntry(
                id = qn.id,
                timestamp = qn.timestamp,
                screenRoute = qn.screenRoute,
                screenLabel = qn.screenLabel,
                sourceFile = qn.sourceFile,
                sourceFunctions = qn.sourceFunctions,
                noteType = qn.noteType.name,
                noteText = qn.noteText
            )
        }
        writeEntriesToFile(entries)
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

    // ── One-time cleanup ──────────────────────────────────────────────

    init {
        // One-time cleanup: clear stale submitted notes and legacy drafts
        // Uses a versioned flag so we can re-run cleanup after code changes.
        if (!debugPrefs.legacyDataV2Cleared) {
            // Delete internal file
            runCatching { File(context.filesDir, FILE_NAME).delete() }
            // Delete SAF file if configured
            runCatching { deleteSafFile() }
            debugPrefs.clearLegacyDrafts()
            debugPrefs.legacyDataV2Cleared = true
        }
    }

    private fun deleteSafFile() {
        val dirUri = debugPrefs.debugFileDirUri
        if (dirUri.isBlank()) return
        val treeUri = Uri.parse(dirUri)
        val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
        docFile?.findFile(FILE_NAME)?.delete()
    }

    // ── File I/O ──────────────────────────────────────────────────────

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
