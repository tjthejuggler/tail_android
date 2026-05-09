package com.example.tail.widget

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Per-widget state used by the lock-screen habit list widget.
 *
 * State is intentionally kept TINY and widget-LOCAL — it is *not* shared with the
 * main app's settings. Only widget interactions reorder the list.
 *
 * Stored entries (per appWidgetId):
 *   - `expanded_<id>`        — "1" if the widget is currently expanded, else absent.
 *   - `recent_<id>`          — `|||`-separated list of habit names in MOST-RECENTLY-TAPPED-FIRST order.
 *                              Habits not in this list keep their canonical order from settings.
 *   - `max1tap_<id>`         — `|||`-separated `name\x00YYYY-MM-DD` pairs, recording the last date
 *                              a max-one habit was tapped from the widget. While this date == today,
 *                              that habit is forced to the bottom of the widget list. As soon as the
 *                              date rolls over (i.e. it is no longer "today"), the entry is treated
 *                              as expired and the habit goes back to its normal recent-tap position.
 */
internal val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tail_widget_state"
)

private const val SEP_LIST = "|||"
private const val SEP_PAIR = "\u0000"

private fun expandedKey(id: Int) = stringPreferencesKey("expanded_$id")
private fun recentKey(id: Int)   = stringPreferencesKey("recent_$id")
private fun max1TapKey(id: Int)  = stringPreferencesKey("max1tap_$id")

object WidgetPreferences {

    // ── Expand / collapse ──────────────────────────────────────────────────

    suspend fun isExpanded(context: Context, widgetId: Int): Boolean {
        val prefs = context.widgetDataStore.data.first()
        return prefs[expandedKey(widgetId)] == "1"
    }

    suspend fun setExpanded(context: Context, widgetId: Int, expanded: Boolean) {
        context.widgetDataStore.edit { prefs ->
            if (expanded) prefs[expandedKey(widgetId)] = "1"
            else prefs.remove(expandedKey(widgetId))
        }
    }

    // ── Recent-tap ordering ────────────────────────────────────────────────

    /**
     * Returns the per-widget recent-tap order, MOST-RECENT-FIRST.
     */
    suspend fun getRecentOrder(context: Context, widgetId: Int): List<String> {
        val raw = context.widgetDataStore.data.first()[recentKey(widgetId)] ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(SEP_LIST).filter { it.isNotBlank() }
    }

    /**
     * Records that [habitName] was just tapped from this widget — moves it to the
     * top of the recent-order list (deduping any earlier entry).
     */
    suspend fun recordTap(context: Context, widgetId: Int, habitName: String) {
        context.widgetDataStore.edit { prefs ->
            val current = prefs[recentKey(widgetId)]
                ?.split(SEP_LIST)
                ?.filter { it.isNotBlank() && it != habitName }
                ?: emptyList()
            val updated = (listOf(habitName) + current).joinToString(SEP_LIST)
            prefs[recentKey(widgetId)] = updated
        }
    }

    // ── Max-1 "send to bottom for the rest of today" tracking ──────────────

    /**
     * Returns the set of habit names that, having been tapped from the widget today
     * AND being max-one habits, should be forced to the bottom of the widget list
     * for the remainder of the current local day.
     *
     * Stale entries (from previous days) are silently ignored — they will be cleaned
     * up the next time [recordMax1Tap] runs.
     */
    suspend fun getMax1HabitsToHideToday(context: Context, widgetId: Int): Set<String> {
        val raw = context.widgetDataStore.data.first()[max1TapKey(widgetId)] ?: return emptySet()
        if (raw.isBlank()) return emptySet()
        val today = LocalDate.now().toString()
        return raw.split(SEP_LIST)
            .mapNotNull { entry ->
                val parts = entry.split(SEP_PAIR)
                if (parts.size != 2) null else parts[0] to parts[1]
            }
            .filter { (_, date) -> date == today }
            .map { it.first }
            .toSet()
    }

    /**
     * Records that [habitName] (a max-one habit) was tapped from this widget today.
     * Also opportunistically prunes any stale entries from previous days.
     */
    suspend fun recordMax1Tap(context: Context, widgetId: Int, habitName: String) {
        val today = LocalDate.now().toString()
        context.widgetDataStore.edit { prefs ->
            val current: List<Pair<String, String>> =
                prefs[max1TapKey(widgetId)]
                    ?.split(SEP_LIST)
                    ?.mapNotNull { entry ->
                        val parts = entry.split(SEP_PAIR)
                        if (parts.size != 2) null else parts[0] to parts[1]
                    }
                    ?.filter { (name, date) -> date == today && name != habitName }
                    ?: emptyList()
            val updated = (current + (habitName to today))
                .joinToString(SEP_LIST) { (n, d) -> "$n$SEP_PAIR$d" }
            prefs[max1TapKey(widgetId)] = updated
        }
    }

    /**
     * Clears all per-widget state (called when a widget instance is deleted).
     */
    suspend fun clear(context: Context, widgetId: Int) {
        context.widgetDataStore.edit { prefs ->
            prefs.remove(expandedKey(widgetId))
            prefs.remove(recentKey(widgetId))
            prefs.remove(max1TapKey(widgetId))
        }
    }
}
