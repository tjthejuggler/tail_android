package com.example.tail.data.debug

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists debug-mode settings: whether the floating bubble is enabled,
 * the user-chosen directory URI for [debug_tail.json], and saved notes
 * so they survive app restarts.
 */
class DebugPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var debugModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()
            refresh()
        }

    /** User-chosen directory URI (from SAF folder picker) for debug_tail.json. */
    var debugFileDirUri: String
        get() = prefs.getString(KEY_DEBUG_DIR_URI, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_DEBUG_DIR_URI, value).apply()
            refresh()
        }

    /** Versioned cleanup flag: clear stale submitted notes and legacy drafts.
     *  V2 also clears the SAF file. */
    var legacyDataV2Cleared: Boolean
        get() = prefs.getBoolean(KEY_LEGACY_V2_CLEARED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LEGACY_V2_CLEARED, value).apply()
        }

    // ── Saved notes persistence ─────────────────────────────────────────────

    /** Load all persisted saved notes from SharedPreferences. */
    fun loadSavedNotes(): List<SavedNote> {
        val json = prefs.getString(KEY_SAVED_NOTES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            val notes = mutableListOf<SavedNote>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                notes.add(SavedNote(
                    id = obj.getString("id"),
                    timestamp = obj.getString("timestamp"),
                    screenRoute = obj.getString("screenRoute"),
                    screenLabel = obj.getString("screenLabel"),
                    sourceFile = obj.getString("sourceFile"),
                    sourceFunctions = obj.getString("sourceFunctions"),
                    noteType = NoteType.valueOf(obj.getString("noteType")),
                    noteText = obj.getString("noteText")
                ))
            }
            notes
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Persist the current saved notes list to SharedPreferences. */
    fun saveSavedNotes(notes: List<SavedNote>) {
        val arr = JSONArray()
        notes.forEach { note ->
            arr.put(JSONObject().apply {
                put("id", note.id)
                put("timestamp", note.timestamp)
                put("screenRoute", note.screenRoute)
                put("screenLabel", note.screenLabel)
                put("sourceFile", note.sourceFile)
                put("sourceFunctions", note.sourceFunctions)
                put("noteType", note.noteType.name)
                put("noteText", note.noteText)
            })
        }
        prefs.edit().putString(KEY_SAVED_NOTES, arr.toString()).apply()
    }

    /** Clear legacy drafts from SharedPreferences. */
    fun clearLegacyDrafts() {
        prefs.edit().remove(KEY_DRAFTS).apply()
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<DebugPrefsSnapshot> = _snapshot.asStateFlow()

    fun refresh() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot() = DebugPrefsSnapshot(
        debugModeEnabled = debugModeEnabled,
        debugFileDirUri = debugFileDirUri
    )

    companion object {
        private const val PREFS_NAME = "tail_debug_prefs"
        private const val KEY_DEBUG_MODE = "debug_mode_enabled"
        private const val KEY_DEBUG_DIR_URI = "debug_dir_uri"
        private const val KEY_DRAFTS = "debug_drafts_json"
        private const val KEY_SAVED_NOTES = "debug_saved_notes_json"
        private const val KEY_LEGACY_V2_CLEARED = "debug_legacy_v2_cleared"
    }
}

data class DebugPrefsSnapshot(
    val debugModeEnabled: Boolean = false,
    val debugFileDirUri: String = ""
)
