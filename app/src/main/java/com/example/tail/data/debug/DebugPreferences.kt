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
 * the user-chosen directory URI for [debug_tail.json], and per-screen drafts
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

    // ── Draft persistence ─────────────────────────────────────────────────────

    /** Load all persisted drafts from SharedPreferences. */
    fun loadDrafts(): Map<String, DebugDraft> {
        val json = prefs.getString(KEY_DRAFTS, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(json)
            val map = mutableMapOf<String, DebugDraft>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val route = obj.getString("screenRoute")
                map[route] = DebugDraft(
                    screenRoute = route,
                    noteType = NoteType.valueOf(obj.getString("noteType")),
                    noteText = obj.getString("noteText")
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Persist the current drafts map to SharedPreferences. */
    fun saveDrafts(drafts: Map<String, DebugDraft>) {
        val arr = JSONArray()
        drafts.values.forEach { draft ->
            arr.put(JSONObject().apply {
                put("screenRoute", draft.screenRoute)
                put("noteType", draft.noteType.name)
                put("noteText", draft.noteText)
            })
        }
        prefs.edit().putString(KEY_DRAFTS, arr.toString()).apply()
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
    }
}

data class DebugPrefsSnapshot(
    val debugModeEnabled: Boolean = false,
    val debugFileDirUri: String = ""
)
