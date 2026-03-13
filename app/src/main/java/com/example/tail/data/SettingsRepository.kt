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
private val KEY_TEXT_INPUT_HABITS = stringSetPreferencesKey("text_input_habits")
private val KEY_TEXT_INPUT_OPTIONS_HABITS = stringSetPreferencesKey("text_input_options_habits")
// Stored as "habitName\x00uri|||habitName\x00uri" pairs
private val KEY_TEXT_INPUT_FILE_URIS = stringPreferencesKey("text_input_file_uris")
// Stored as "habitName\x00iconName|||habitName\x00iconName" pairs
private val KEY_HABIT_ICONS = stringPreferencesKey("habit_icons")
// Dated-entry feature keys
private val KEY_DATED_ENTRY_HABITS = stringSetPreferencesKey("dated_entry_habits")
private val KEY_DATED_ENTRY_FILE_URIS = stringPreferencesKey("dated_entry_file_uris")
// Stored as "habitName\x00size|||habitName\x00size" pairs (size as decimal string)
private val KEY_DATED_ENTRY_FILE_SIZES = stringPreferencesKey("dated_entry_file_sizes")

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

// Serialisation helpers for Map<String, String> (habit name → URI).
// Format: "habitName\x00uri|||habitName\x00uri"
// We use \x00 (null byte) as the name/uri separator since it can't appear in either.
private const val PAIR_SEP = "|||"
private const val KV_SEP = "\u0000"

private fun encodeFileUriMap(map: Map<String, String>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) -> "$k$KV_SEP$v" }

private fun decodeFileUriMap(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
    }.toMap()
}

// Serialisation helpers for Map<String, Long> (habit name → file size).
// Reuses the same PAIR_SEP / KV_SEP scheme; value is stored as decimal string.
private fun encodeLongMap(map: Map<String, Long>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) -> "$k$KV_SEP$v" }

private fun decodeLongMap(raw: String): Map<String, Long> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
            key to value
        }
    }.toMap()
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
        val textInputFileUrisRaw = prefs[KEY_TEXT_INPUT_FILE_URIS] ?: ""
        val habitIconsRaw = prefs[KEY_HABIT_ICONS] ?: ""
        val datedEntryFileUrisRaw = prefs[KEY_DATED_ENTRY_FILE_URIS] ?: ""
        val datedEntryFileSizesRaw = prefs[KEY_DATED_ENTRY_FILE_SIZES] ?: ""
        AppSettings(
            fileUri = prefs[KEY_FILE_URI] ?: "",
            historicalFileUri = prefs[KEY_HISTORICAL_FILE_URI] ?: "",
            totalsFileUri = prefs[KEY_TOTALS_FILE_URI] ?: "",
            customInputHabits = prefs[KEY_CUSTOM_INPUT] ?: DEFAULT_CUSTOM_INPUT_HABITS,
            habitOrder = customOrder,
            habitScreens = screens,
            activeScreenIndex = activeScreenIndex.coerceAtLeast(0),
            textInputHabits = prefs[KEY_TEXT_INPUT_HABITS] ?: emptySet(),
            textInputOptionsHabits = prefs[KEY_TEXT_INPUT_OPTIONS_HABITS] ?: emptySet(),
            textInputFileUris = decodeFileUriMap(textInputFileUrisRaw),
            habitIcons = decodeFileUriMap(habitIconsRaw),
            datedEntryHabits = prefs[KEY_DATED_ENTRY_HABITS] ?: emptySet(),
            datedEntryFileUris = decodeFileUriMap(datedEntryFileUrisRaw),
            datedEntryFileSizes = decodeLongMap(datedEntryFileSizesRaw)
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

    /** Saves the set of habits that have text input enabled. */
    suspend fun saveTextInputHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TEXT_INPUT_HABITS] = habits
        }
    }

    /** Saves the set of habits that have the "show options" sub-feature enabled. */
    suspend fun saveTextInputOptionsHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TEXT_INPUT_OPTIONS_HABITS] = habits
        }
    }

    /** Saves the map of habit name → text-log file URI. */
    suspend fun saveTextInputFileUris(uris: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TEXT_INPUT_FILE_URIS] = encodeFileUriMap(uris)
        }
    }

    /** Saves the map of habit name → icon name (custom icon overrides). */
    suspend fun saveHabitIcons(icons: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_ICONS] = encodeFileUriMap(icons)
        }
    }

    /** Saves the set of habits that have the "Dated Entry" feature enabled. */
    suspend fun saveDatedEntryHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DATED_ENTRY_HABITS] = habits
        }
    }

    /** Saves the map of habit name → dated-entry source file URI. */
    suspend fun saveDatedEntryFileUris(uris: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DATED_ENTRY_FILE_URIS] = encodeFileUriMap(uris)
        }
    }

    /**
     * Saves the map of habit name → last-seen file size (bytes).
     * Updated after each successful parse so we can skip unchanged files.
     */
    suspend fun saveDatedEntryFileSizes(sizes: Map<String, Long>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DATED_ENTRY_FILE_SIZES] = encodeLongMap(sizes)
        }
    }
}
