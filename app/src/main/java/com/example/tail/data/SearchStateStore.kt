package com.example.tail.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the global search state (last query + selected habit filters)
 * so it survives app restarts and process death.
 *
 * A saved-but-empty filter set is meaningful (the user deselected every
 * habit via "None"), so [load] returns null only when nothing was ever
 * saved — never for an empty selection.
 */
class SearchStateStore(context: Context) {

    /** Restored search state, or null when nothing was persisted yet. */
    fun load(): SavedState? {
        if (!prefs.contains(KEY_QUERY) && !prefs.contains(KEY_FILTERS)) return null
        // Copy out of the prefs StringSet: it must not be mutated in place.
        val filters = prefs.getStringSet(KEY_FILTERS, emptySet())?.toSet() ?: emptySet()
        return SavedState(
            query = prefs.getString(KEY_QUERY, "") ?: "",
            filters = filters
        )
    }

    fun save(query: String, filters: Set<String>) {
        prefs.edit()
            .putString(KEY_QUERY, query)
            .putStringSet(KEY_FILTERS, filters)
            .apply()
    }

    data class SavedState(val query: String, val filters: Set<String>)

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "tail_search_state"
        private const val KEY_QUERY = "last_query"
        private const val KEY_FILTERS = "last_filters"
    }
}
