package com.example.tail.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tail_settings")

private val KEY_FILE_URI = stringPreferencesKey("file_uri")
private val KEY_HISTORICAL_FILE_URI = stringPreferencesKey("historical_file_uri")
private val KEY_TOTALS_FILE_URI = stringPreferencesKey("totals_file_uri")
private val KEY_CUSTOM_INPUT = stringSetPreferencesKey("custom_input_habits")
private val KEY_HABIT_ORDER = stringPreferencesKey("habit_order")
private val KEY_HABIT_SCREENS = stringPreferencesKey("habit_screens")
private val KEY_ACTIVE_SCREEN_INDEX = intPreferencesKey("active_screen_index")

// Serialisation helpers for HabitScreen list.
// Format: each screen is "id\tname\thabit1|habit2|habit3", screens separated by "\n"
private fun encodeScreens(screens: List<HabitScreen>): String =
    screens.joinToString("\n") { screen ->
        val habitsStr = screen.habitNames.joinToString("|") { it.replace("|", "\\|") }
        "${screen.id}\t${screen.name}\t$habitsStr"
    }

private fun decodeScreens(raw: String): List<HabitScreen> {
    if (raw.isBlank()) return emptyList()
    return raw.split("\n").mapNotNull { line ->
        val parts = line.split("\t", limit = 3)
        if (parts.size < 3) return@mapNotNull null
        val id = parts[0]
        val name = parts[1]
        val habits = if (parts[2].isEmpty()) emptyList()
        else parts[2].split(Regex("(?<!\\\\)\\|")).map { it.replace("\\|", "|") }
        HabitScreen(id = id, name = name, habitNames = habits)
    }
}

/**
 * Persists app settings (file URIs, custom input habits, custom habit order, habit screens)
 * using DataStore.
 */
class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val orderStr = prefs[KEY_HABIT_ORDER] ?: ""
        val customOrder = if (orderStr.isNotEmpty()) {
            orderStr.split("|||").filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val screensRaw = prefs[KEY_HABIT_SCREENS] ?: ""
        val screens = decodeScreens(screensRaw)
        val activeScreenIndex = prefs[KEY_ACTIVE_SCREEN_INDEX] ?: 0
        AppSettings(
            fileUri = prefs[KEY_FILE_URI] ?: "",
            historicalFileUri = prefs[KEY_HISTORICAL_FILE_URI] ?: "",
            totalsFileUri = prefs[KEY_TOTALS_FILE_URI] ?: "",
            customInputHabits = prefs[KEY_CUSTOM_INPUT] ?: DEFAULT_CUSTOM_INPUT_HABITS,
            habitOrder = customOrder,
            habitScreens = screens,
            activeScreenIndex = activeScreenIndex.coerceAtLeast(0)
        )
    }

    suspend fun saveFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILE_URI] = uri
        }
    }

    suspend fun saveHistoricalFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HISTORICAL_FILE_URI] = uri
        }
    }

    suspend fun saveTotalsFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOTALS_FILE_URI] = uri
        }
    }

    suspend fun saveCustomInputHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_INPUT] = habits
        }
    }

    /** Saves a custom habit display order. Pass empty list to reset to default. */
    suspend fun saveHabitOrder(order: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_ORDER] = order.joinToString("|||")
        }
    }

    /** Saves the full list of habit screens. */
    suspend fun saveHabitScreens(screens: List<HabitScreen>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_SCREENS] = encodeScreens(screens)
        }
    }

    /** Saves the active screen index. */
    suspend fun saveActiveScreenIndex(index: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_SCREEN_INDEX] = index
        }
    }
}
