package com.example.tail.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tail_settings")

private val KEY_FILE_URI = stringPreferencesKey("file_uri")
private val KEY_SCREENS_RELAY_FILE_URI = stringPreferencesKey("screens_relay_file_uri")
private val KEY_TASKER_FILE_URI = stringPreferencesKey("tasker_file_uri")
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
// 1-max feature key
private val KEY_MAX_ONE_HABITS = stringSetPreferencesKey("max_one_habits")
// Dated-entry feature keys
private val KEY_DATED_ENTRY_HABITS = stringSetPreferencesKey("dated_entry_habits")
private val KEY_DATED_ENTRY_FILE_URIS = stringPreferencesKey("dated_entry_file_uris")
// Stored as "habitName\x00size|||habitName\x00size" pairs (size as decimal string)
private val KEY_DATED_ENTRY_FILE_SIZES = stringPreferencesKey("dated_entry_file_sizes")
// Stored as "habitName\x00divisor|||habitName\x00divisor" pairs (divisor as decimal string)
private val KEY_HABIT_DIVIDERS = stringPreferencesKey("habit_dividers")
// Conditional habit type keys
private val KEY_CONDITIONAL_HABITS = stringSetPreferencesKey("conditional_habits")
// Stored as "habitName\x00link1,link2,link3|||habitName\x00link1" pairs
private val KEY_CONDITIONAL_LINKED_HABITS = stringPreferencesKey("conditional_linked_habits")
// Subtyped habit type keys
private val KEY_SUBTYPED_HABITS = stringSetPreferencesKey("subtyped_habits")
private val KEY_HABIT_SUBTYPES = stringPreferencesKey("habit_subtypes")
private val KEY_SUBTYPE_DATA_FILE_URIS = stringPreferencesKey("subtype_data_file_uris")
// Timed habit type keys
private val KEY_TIMED_HABITS = stringSetPreferencesKey("timed_habits")
private val KEY_TIMED_DATA_FILE_URIS = stringPreferencesKey("timed_data_file_uris")
// Timeless habit type key
private val KEY_TIMELESS_HABITS = stringSetPreferencesKey("timeless_habits")
// Hidden screens (set of screen IDs)
private val KEY_HIDDEN_SCREENS = stringSetPreferencesKey("hidden_screens")
// Disabled habits (set of habit names)
private val KEY_DISABLED_HABITS = stringSetPreferencesKey("disabled_habits")
// No-points habits (set of habit names)
private val KEY_NO_POINTS_HABITS = stringSetPreferencesKey("no_points_habits")
// AI icon generation settings
private val KEY_AI_ICONS_ENABLED = booleanPreferencesKey("ai_icons_enabled")
private val KEY_AI_ICONS_API_KEY = stringPreferencesKey("ai_icons_api_key")
private val KEY_AI_ICONS_BASE_URL = stringPreferencesKey("ai_icons_base_url")
private val KEY_AI_ICONS_ENDPOINT = stringPreferencesKey("ai_icons_endpoint")
private val KEY_AI_ICONS_MODEL = stringPreferencesKey("ai_icons_model")
private val KEY_AI_ICONS_QUALITY = stringPreferencesKey("ai_icons_quality")
// Chess.com integration settings
private val KEY_CHESS_COM_ENABLED = booleanPreferencesKey("chess_com_enabled")
private val KEY_CHESS_COM_USERNAME = stringPreferencesKey("chess_com_username")
// Stored as "TYPE\x00minutes|||TYPE\x00minutes" pairs
private val KEY_CHESS_COM_MINUTES_PER_INCREMENT = stringPreferencesKey("chess_com_minutes_per_increment")
// Stored as "habitName\x00TYPE|||habitName\x00TYPE" pairs
private val KEY_CHESS_COM_HABIT_LINKS = stringPreferencesKey("chess_com_habit_links")
// Garmin integration settings
private val KEY_GARMIN_ENABLED = booleanPreferencesKey("garmin_enabled")
private val KEY_GARMIN_PROXY_URL = stringPreferencesKey("garmin_proxy_url")
private val KEY_GARMIN_APP_TOKEN = stringPreferencesKey("garmin_app_token")
private val KEY_GARMIN_DATE_OF_BIRTH = stringPreferencesKey("garmin_date_of_birth")
// Stored as "TYPE\x00threshold|||TYPE\x00threshold" pairs
private val KEY_GARMIN_THRESHOLDS = stringPreferencesKey("garmin_thresholds")
// Stored as "habitName\x00TYPE|||habitName\x00TYPE" pairs
private val KEY_GARMIN_HABIT_LINKS = stringPreferencesKey("garmin_habit_links")
// Voice trigger feature keys
private val KEY_VOICE_TRIGGER_ENABLED = booleanPreferencesKey("voice_trigger_enabled")
private val KEY_VOICE_TRIGGER_HABITS = stringSetPreferencesKey("voice_trigger_habits")
// Stored as "habitName\x00word1,word2,word3|||habitName\x00word1" pairs (same as linked habits)
private val KEY_VOICE_TRIGGER_WORDS = stringPreferencesKey("voice_trigger_words")
// Voice subtype habits (habits that use subtypes in voice commands)
private val KEY_VOICE_SUBTYPE_HABITS = stringSetPreferencesKey("voice_subtype_habits")
// Stored as "habitName\x00amount|||habitName\x00amount" pairs (amount as decimal string)
private val KEY_VOICE_TRIGGER_INCREMENTS = stringPreferencesKey("voice_trigger_increments")
// Voice note dictation settings
private val KEY_VOICE_NOTE_ENABLED = booleanPreferencesKey("voice_note_enabled")
private val KEY_VOICE_NOTE_FILE_URI = stringPreferencesKey("voice_note_file_uri")
// Automatic daily backup settings — see AppSettings.autoBackupFolderUri
private val KEY_AUTO_BACKUP_FOLDER_URI = stringPreferencesKey("auto_backup_folder_uri")
private val KEY_AUTO_BACKUP_LAST_DATE = stringPreferencesKey("auto_backup_last_date")
// Custom input increment amounts — stored as "habitName\x00amt1,amt2,amt3|||…" pairs
private val KEY_CUSTOM_INPUT_AMOUNTS = stringPreferencesKey("custom_input_amounts")
// Custom input recent amounts — stored as "habitName\x00amt1,amt2,amt3|||…" pairs (most recent first)
private val KEY_CUSTOM_INPUT_RECENT_AMOUNTS = stringPreferencesKey("custom_input_recent_amounts")
// Map screen stats settings
private val KEY_MAP_STATS_HABITS = stringSetPreferencesKey("map_stats_habits")
private val KEY_MAP_STATS_SHOW_TEXT_HABITS = stringSetPreferencesKey("map_stats_show_text_habits")
// Stored as "habitName\x000|||habitName\x001" pairs (0 = points, 1 = value/raw)
private val KEY_GRAPH_VALUE_MODE_HABITS = stringPreferencesKey("graph_value_mode_habits")

// Custom point ranges settings
private val KEY_CUSTOM_POINT_RANGES_HABITS = stringSetPreferencesKey("custom_point_ranges_habits")
// Stored as "habitName\x00min0,max0|min1,max1|...|min6,max6|||habitName\x00..." pairs
private val KEY_CUSTOM_POINT_RANGES = stringPreferencesKey("custom_point_ranges")

// Migration flag — set to true after the one-time "Launch…Widget" → short-name rename.
private val KEY_MIGRATION_LAUNCH_RENAME_DONE = booleanPreferencesKey("migration_launch_rename_done")

/**
 * One-time rename mapping for legacy "Launch … Widget" habit names.
 * Applied to all DataStore keys that store habit names (sets, lists, map keys, screen lists).
 */
private val HABIT_RENAME_MAP: Map<String, String> = mapOf(
    "Launch Pushups Widget" to "Pushups",
    "Launch Situps Widget"  to "Situps",
    "Launch Squats Widget"  to "Squats"
)

/** Replace any old habit name with its new name using [HABIT_RENAME_MAP]. */
private fun migrateNameStr(name: String): String = HABIT_RENAME_MAP[name] ?: name

/** Migrate a raw `|||`-separated string (used for habit order). */
private fun migrateDelimitedStr(raw: String, sep: String = "|||"): String {
    if (raw.isBlank()) return raw
    return raw.split(sep).joinToString(sep) { migrateNameStr(it) }
}

/** Migrate a raw encoded screens string (id\tname\thabit1|habit2\n…). */
private fun migrateScreensStr(raw: String): String {
    if (raw.isBlank()) return raw
    return raw.split("\n").joinToString("\n") { line ->
        val parts = line.split("\t", limit = 3)
        if (parts.size < 3) line
        else {
            val habits = parts[2].split(Regex("(?<!\\\\)\\|"))
                .joinToString("|") { migrateNameStr(it) }
            "${parts[0]}\t${parts[1]}\t$habits"
        }
    }
}

/**
 * Migrate a raw KV_SEP/PAIR_SEP-encoded map string — renames keys (habit names)
 * while preserving values. Works for file-URI maps, int maps, long maps, etc.
 */
private fun migrateKvMapStr(raw: String): String {
    if (raw.isBlank()) return raw
    return raw.split(PAIR_SEP).joinToString(PAIR_SEP) { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) pair
        else migrateNameStr(pair.substring(0, idx)) + KV_SEP + pair.substring(idx + 1)
    }
}

/**
 * Migrate a raw linked-habits map string — renames both keys AND values (linked habit names).
 */
private fun migrateLinkedHabitsStr(raw: String): String {
    if (raw.isBlank()) return raw
    return raw.split(PAIR_SEP).joinToString(PAIR_SEP) { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) pair
        else {
            val key = migrateNameStr(pair.substring(0, idx))
            val links = pair.substring(idx + 1)
                .split(Regex("(?<!\\\\),"))
                .joinToString(",") { migrateNameStr(it) }
            "$key$KV_SEP$links"
        }
    }
}

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

// Serialisation helpers for Map<String, Int> (habit name → divisor).
// Reuses the same PAIR_SEP / KV_SEP scheme; value is stored as decimal string.
private fun encodeIntMap(map: Map<String, Int>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) -> "$k$KV_SEP$v" }

private fun decodeIntMap(raw: String): Map<String, Int> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val value = pair.substring(idx + 1).toIntOrNull() ?: return@mapNotNull null
            key to value
        }
    }.toMap()
}

// Serialisation helpers for Map<String, List<Int>> (habit name → ordered list of ints).
// Format: "habitName\x00amt1,amt2,amt3|||habitName\x00amt1,amt2"
private fun encodeIntListMap(map: Map<String, List<Int>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        "$k$KV_SEP${v.joinToString(",")}"
    }

private fun decodeIntListMap(raw: String): Map<String, List<Int>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val values = pair.substring(idx + 1)
                .split(",")
                .mapNotNull { it.toIntOrNull() }
            if (values.isEmpty()) null else key to values
        }
    }.toMap()
}

// Serialisation helpers for Map<String, List<String>> (habit name → ordered list of subtype names).
// Format: "habitName\x00type1,type2,type3|||habitName\x00type1,type2"
// Commas inside subtype names are escaped as \, to avoid ambiguity.
private fun encodeSubtypesMap(map: Map<String, List<String>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        val types = v.joinToString(LINK_SEP) { it.replace(",", "\\,") }
        "$k$KV_SEP$types"
    }

private fun decodeSubtypesMap(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val typesStr = pair.substring(idx + 1)
            val types = if (typesStr.isEmpty()) emptyList()
            else typesStr.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }
            key to types
        }
    }.toMap()
}

// Serialisation helpers for Map<String, Set<String>> (habit name → set of linked habit names).
// Format: "habitName\x00link1,link2,link3|||habitName\x00link1"
// Commas inside habit names are escaped as \, to avoid ambiguity.
private const val LINK_SEP = ","

private fun encodeLinkedHabitsMap(map: Map<String, Set<String>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        val links = v.joinToString(LINK_SEP) { it.replace(",", "\\,") }
        "$k$KV_SEP$links"
    }

private fun decodeLinkedHabitsMap(raw: String): Map<String, Set<String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val linksStr = pair.substring(idx + 1)
            val links = if (linksStr.isEmpty()) emptySet()
            else linksStr.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }.toSet()
            key to links
        }
    }.toMap()
}

// Serialisation helpers for Map<String, List<PointRange>> (habit name → 7 point ranges).
// Format: "habitName\x00min0,max0|min1,max1|...|min6,max6|||habitName\x00..."
// Each range is stored as "min,max" with pipe separators between ranges.
private fun encodePointRangesMap(map: Map<String, List<PointRange>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        val rangesStr = v.joinToString("|") { range -> "${range.min},${range.max}" }
        "$k$KV_SEP$rangesStr"
    }

private fun decodePointRangesMap(raw: String): Map<String, List<PointRange>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val rangesStr = pair.substring(idx + 1)
            val ranges = rangesStr.split("|").mapNotNull { rangeStr ->
                val parts = rangeStr.split(",")
                if (parts.size == 2) {
                    val min = parts[0].toIntOrNull()
                    val max = parts[1].toIntOrNull()
                    if (min != null && max != null) PointRange(min, max) else null
                } else null
            }
            // Support unlimited ranges - no padding or truncation
            key to ranges
        }
    }.toMap()
}

/**
 * Persists app settings (file URIs, custom input habits, custom habit order, habit screens)
 * using DataStore.
 */
class SettingsRepository(private val context: Context) {

    /**
     * One-time migration: renames legacy "Launch … Widget" habit names to their
     * short forms ("Pushups", "Situps", "Squats") across every DataStore key that
     * stores habit names. Safe to call multiple times — it checks a boolean flag
     * and no-ops if the migration was already applied.
     */
    suspend fun migrateHabitNames() {
        context.dataStore.edit { prefs ->
            if (prefs[KEY_MIGRATION_LAUNCH_RENAME_DONE] == true) return@edit

            Log.i("SettingsRepo", "Running one-time habit rename migration…")

            // --- StringSet keys: replace old names in the set ---
            fun migrateStringSet(key: Preferences.Key<Set<String>>) {
                val current = prefs[key] ?: return
                val migrated = current.map { migrateNameStr(it) }.toSet()
                if (migrated != current) prefs[key] = migrated
            }
            migrateStringSet(KEY_CUSTOM_INPUT)
            migrateStringSet(KEY_MAX_ONE_HABITS)
            migrateStringSet(KEY_TEXT_INPUT_HABITS)
            migrateStringSet(KEY_TEXT_INPUT_OPTIONS_HABITS)
            migrateStringSet(KEY_DATED_ENTRY_HABITS)
            migrateStringSet(KEY_CONDITIONAL_HABITS)
            migrateStringSet(KEY_SUBTYPED_HABITS)
            migrateStringSet(KEY_TIMED_HABITS)
            migrateStringSet(KEY_TIMELESS_HABITS)
            migrateStringSet(KEY_VOICE_TRIGGER_HABITS)
            migrateStringSet(KEY_CUSTOM_POINT_RANGES_HABITS)

            // --- Delimited-string keys (habit order) ---
            val orderRaw = prefs[KEY_HABIT_ORDER] ?: ""
            if (orderRaw.isNotEmpty()) {
                val migrated = migrateDelimitedStr(orderRaw)
                if (migrated != orderRaw) prefs[KEY_HABIT_ORDER] = migrated
            }

            // --- Screens (encoded string with habit names inside) ---
            val screensRaw = prefs[KEY_HABIT_SCREENS] ?: ""
            if (screensRaw.isNotEmpty()) {
                val migrated = migrateScreensStr(screensRaw)
                if (migrated != screensRaw) prefs[KEY_HABIT_SCREENS] = migrated
            }

            // --- KV-map keys (habit name → value): rename keys only ---
            fun migrateKvKey(key: Preferences.Key<String>) {
                val raw = prefs[key] ?: return
                if (raw.isBlank()) return
                val migrated = migrateKvMapStr(raw)
                if (migrated != raw) prefs[key] = migrated
            }
            migrateKvKey(KEY_TEXT_INPUT_FILE_URIS)
            migrateKvKey(KEY_HABIT_ICONS)
            migrateKvKey(KEY_DATED_ENTRY_FILE_URIS)
            migrateKvKey(KEY_DATED_ENTRY_FILE_SIZES)
            migrateKvKey(KEY_HABIT_DIVIDERS)
            migrateKvKey(KEY_HABIT_SUBTYPES)
            migrateKvKey(KEY_SUBTYPE_DATA_FILE_URIS)
            migrateKvKey(KEY_TIMED_DATA_FILE_URIS)
            migrateKvKey(KEY_CUSTOM_POINT_RANGES)
            migrateKvKey(KEY_GRAPH_VALUE_MODE_HABITS)

            // --- Linked-habits map: rename both keys and values ---
            val linkedRaw = prefs[KEY_CONDITIONAL_LINKED_HABITS] ?: ""
            if (linkedRaw.isNotBlank()) {
                val migrated = migrateLinkedHabitsStr(linkedRaw)
                if (migrated != linkedRaw) prefs[KEY_CONDITIONAL_LINKED_HABITS] = migrated
            }

            // --- Voice trigger words map: rename both keys and values ---
            val voiceTriggerRaw = prefs[KEY_VOICE_TRIGGER_WORDS] ?: ""
            if (voiceTriggerRaw.isNotBlank()) {
                val migrated = migrateLinkedHabitsStr(voiceTriggerRaw)
                if (migrated != voiceTriggerRaw) prefs[KEY_VOICE_TRIGGER_WORDS] = migrated
            }

            prefs[KEY_MIGRATION_LAUNCH_RENAME_DONE] = true
            Log.i("SettingsRepo", "Habit rename migration complete.")
        }
    }

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
        val habitDividersRaw = prefs[KEY_HABIT_DIVIDERS] ?: ""
        val conditionalLinkedHabitsRaw = prefs[KEY_CONDITIONAL_LINKED_HABITS] ?: ""
        val habitSubtypesRaw = prefs[KEY_HABIT_SUBTYPES] ?: ""
        val subtypeDataFileUrisRaw = prefs[KEY_SUBTYPE_DATA_FILE_URIS] ?: ""
        val timedDataFileUrisRaw = prefs[KEY_TIMED_DATA_FILE_URIS] ?: ""
        val chessComMinutesRaw = prefs[KEY_CHESS_COM_MINUTES_PER_INCREMENT] ?: ""
        val chessComHabitLinksRaw = prefs[KEY_CHESS_COM_HABIT_LINKS] ?: ""
        val voiceTriggerIncrementsRaw = prefs[KEY_VOICE_TRIGGER_INCREMENTS] ?: ""
        val customInputAmountsRaw = prefs[KEY_CUSTOM_INPUT_AMOUNTS] ?: ""
        val customInputRecentAmountsRaw = prefs[KEY_CUSTOM_INPUT_RECENT_AMOUNTS] ?: ""
        val garminThresholdsRaw = prefs[KEY_GARMIN_THRESHOLDS] ?: ""
        val garminHabitLinksRaw = prefs[KEY_GARMIN_HABIT_LINKS] ?: ""
        val customPointRangesRaw = prefs[KEY_CUSTOM_POINT_RANGES] ?: ""
        val graphValueModeHabitsRaw = prefs[KEY_GRAPH_VALUE_MODE_HABITS] ?: ""
        AppSettings(
            fileUri = prefs[KEY_FILE_URI] ?: "",
            screensRelayFileUri = prefs[KEY_SCREENS_RELAY_FILE_URI] ?: "",
            taskerFileUri = prefs[KEY_TASKER_FILE_URI] ?: "",
            customInputHabits = prefs[KEY_CUSTOM_INPUT] ?: DEFAULT_CUSTOM_INPUT_HABITS,
            habitOrder = customOrder,
            habitScreens = screens,
            activeScreenIndex = activeScreenIndex.coerceAtLeast(0),
            maxOneHabits = prefs[KEY_MAX_ONE_HABITS] ?: emptySet(),
            textInputHabits = prefs[KEY_TEXT_INPUT_HABITS] ?: emptySet(),
            textInputOptionsHabits = prefs[KEY_TEXT_INPUT_OPTIONS_HABITS] ?: emptySet(),
            textInputFileUris = decodeFileUriMap(textInputFileUrisRaw),
            habitIcons = decodeFileUriMap(habitIconsRaw),
            datedEntryHabits = prefs[KEY_DATED_ENTRY_HABITS] ?: emptySet(),
            datedEntryFileUris = decodeFileUriMap(datedEntryFileUrisRaw),
            datedEntryFileSizes = decodeLongMap(datedEntryFileSizesRaw),
            habitDividers = decodeIntMap(habitDividersRaw),
            conditionalHabits = prefs[KEY_CONDITIONAL_HABITS] ?: emptySet(),
            conditionalLinkedHabits = decodeLinkedHabitsMap(conditionalLinkedHabitsRaw),
            subtypedHabits = prefs[KEY_SUBTYPED_HABITS] ?: emptySet(),
            habitSubtypes = decodeSubtypesMap(habitSubtypesRaw),
            subtypeDataFileUris = decodeFileUriMap(subtypeDataFileUrisRaw),
            timedHabits = prefs[KEY_TIMED_HABITS] ?: emptySet(),
            timedDataFileUris = decodeFileUriMap(timedDataFileUrisRaw),
            timelessHabits = prefs[KEY_TIMELESS_HABITS] ?: emptySet(),
            hiddenScreens = prefs[KEY_HIDDEN_SCREENS] ?: emptySet(),
            disabledHabits = prefs[KEY_DISABLED_HABITS] ?: emptySet(),
            noPointsHabits = prefs[KEY_NO_POINTS_HABITS] ?: emptySet(),
            aiIconsEnabled = prefs[KEY_AI_ICONS_ENABLED] ?: false,
            aiIconsApiKey = prefs[KEY_AI_ICONS_API_KEY] ?: "",
            aiIconsBaseUrl = prefs[KEY_AI_ICONS_BASE_URL] ?: "",
            aiIconsEndpoint = prefs[KEY_AI_ICONS_ENDPOINT] ?: "",
            aiIconsModel = prefs[KEY_AI_ICONS_MODEL] ?: "",
            aiIconsQuality = prefs[KEY_AI_ICONS_QUALITY] ?: "",
            chessComEnabled = prefs[KEY_CHESS_COM_ENABLED] ?: false,
            chessComUsername = prefs[KEY_CHESS_COM_USERNAME] ?: "",
            chessComMinutesPerIncrement = decodeIntMap(chessComMinutesRaw),
            chessComHabitLinks = decodeFileUriMap(chessComHabitLinksRaw),
            voiceTriggerEnabled = prefs[KEY_VOICE_TRIGGER_ENABLED] ?: false,
            voiceTriggerHabits = prefs[KEY_VOICE_TRIGGER_HABITS] ?: emptySet(),
            voiceTriggerWords = decodeLinkedHabitsMap(prefs[KEY_VOICE_TRIGGER_WORDS] ?: ""),
            voiceTriggerIncrements = decodeIntMap(voiceTriggerIncrementsRaw),
            voiceSubtypeHabits = prefs[KEY_VOICE_SUBTYPE_HABITS] ?: emptySet(),
            voiceNoteEnabled = prefs[KEY_VOICE_NOTE_ENABLED] ?: false,
            voiceNoteFileUri = prefs[KEY_VOICE_NOTE_FILE_URI] ?: "",
            autoBackupFolderUri = prefs[KEY_AUTO_BACKUP_FOLDER_URI] ?: "",
            autoBackupLastDate = prefs[KEY_AUTO_BACKUP_LAST_DATE] ?: "",
            customInputAmounts = decodeIntListMap(customInputAmountsRaw),
            customInputRecentAmounts = decodeIntListMap(customInputRecentAmountsRaw),
            mapStatsHabits = prefs[KEY_MAP_STATS_HABITS] ?: emptySet(),
            mapStatsShowTextHabits = prefs[KEY_MAP_STATS_SHOW_TEXT_HABITS] ?: emptySet(),
            garminEnabled = prefs[KEY_GARMIN_ENABLED] ?: false,
            garminProxyUrl = prefs[KEY_GARMIN_PROXY_URL] ?: "",
            garminAppToken = prefs[KEY_GARMIN_APP_TOKEN] ?: "",
            garminDateOfBirth = prefs[KEY_GARMIN_DATE_OF_BIRTH] ?: "",
            garminThresholds = decodeIntMap(garminThresholdsRaw),
            garminHabitLinks = decodeFileUriMap(garminHabitLinksRaw),
            customPointRangesHabits = prefs[KEY_CUSTOM_POINT_RANGES_HABITS] ?: emptySet(),
            customPointRanges = decodePointRangesMap(customPointRangesRaw),
            graphValueModeHabits = decodeIntMap(graphValueModeHabitsRaw)
        )
    }

    /** Saves the per-habit custom increment button amounts. */
    suspend fun saveCustomInputAmounts(amounts: Map<String, List<Int>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_INPUT_AMOUNTS] = encodeIntListMap(amounts)
        }
    }

    /** Saves the per-habit recent increment amounts (up to 3, most recent first). */
    suspend fun saveCustomInputRecentAmounts(recent: Map<String, List<Int>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_INPUT_RECENT_AMOUNTS] = encodeIntListMap(recent)
        }
    }

    /** Saves the SAF tree URI for the automatic daily backup folder. */
    suspend fun saveAutoBackupFolderUri(uri: String) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_BACKUP_FOLDER_URI] = uri }
    }

    /** Saves the ISO date string of the most recent successful automatic backup. */
    suspend fun saveAutoBackupLastDate(date: String) {
        context.dataStore.edit { prefs -> prefs[KEY_AUTO_BACKUP_LAST_DATE] = date }
    }

    suspend fun saveFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILE_URI] = uri
        }
    }

    /** Saves the SAF URI for the Tasker stats relay txt file. */
    suspend fun saveTaskerFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TASKER_FILE_URI] = uri
        }
    }

    /** Saves the SAF URI for the screens_layout.json relay file. */
    suspend fun saveScreensRelayFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREENS_RELAY_FILE_URI] = uri
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

    /** Saves the set of habits that have the "1 max" cap enabled. */
    suspend fun saveMaxOneHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAX_ONE_HABITS] = habits
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

    /**
     * Saves the map of habit name → divisor value for the "divider" feature.
     * A divisor of 1 (or absent) means no division.
     */
    suspend fun saveHabitDividers(dividers: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_DIVIDERS] = encodeIntMap(dividers)
        }
    }

    /** Saves the set of habits that have the "conditional" type enabled. */
    suspend fun saveConditionalHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONDITIONAL_HABITS] = habits
        }
    }

    /** Saves the map of conditional habit name → set of linked habit names. */
    suspend fun saveConditionalLinkedHabits(links: Map<String, Set<String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONDITIONAL_LINKED_HABITS] = encodeLinkedHabitsMap(links)
        }
    }

    /** Saves the set of habits that have the "subtyped" type enabled. */
    suspend fun saveSubtypedHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_SUBTYPED_HABITS] = habits }
    }

    /** Saves the map of habit name → ordered list of subtype names. */
    suspend fun saveHabitSubtypes(subtypes: Map<String, List<String>>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_SUBTYPES] = encodeSubtypesMap(subtypes) }
    }

    /** Saves the map of habit name → SAF URI for the subtype data file. */
    suspend fun saveSubtypeDataFileUris(uris: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_SUBTYPE_DATA_FILE_URIS] = encodeFileUriMap(uris) }
    }

    /** Saves the set of habits that have the "timed" type enabled. */
    suspend fun saveTimedHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_TIMED_HABITS] = habits }
    }

    /** Saves the map of habit name → SAF URI for the timed data file. */
    suspend fun saveTimedDataFileUris(uris: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_TIMED_DATA_FILE_URIS] = encodeFileUriMap(uris) }
    }

    /** Saves the set of habits that have the "timeless" feature enabled. */
    suspend fun saveTimelessHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_TIMELESS_HABITS] = habits }
    }

    /** Saves the set of screen IDs that are hidden. */
    suspend fun saveHiddenScreens(screenIds: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_HIDDEN_SCREENS] = screenIds }
    }

    /** Saves the set of habits that are disabled. */
    suspend fun saveDisabledHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_DISABLED_HABITS] = habits }
    }

    /** Saves the set of habits that don't affect point totals. */
    suspend fun saveNoPointsHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_NO_POINTS_HABITS] = habits }
    }

    /** Saves all AI icon generation settings at once. */
    suspend fun saveAiIconSettings(
        enabled: Boolean,
        apiKey: String,
        baseUrl: String,
        endpoint: String,
        model: String,
        quality: String = ""
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AI_ICONS_ENABLED] = enabled
            prefs[KEY_AI_ICONS_API_KEY] = apiKey
            prefs[KEY_AI_ICONS_BASE_URL] = baseUrl
            prefs[KEY_AI_ICONS_ENDPOINT] = endpoint
            prefs[KEY_AI_ICONS_MODEL] = model
            prefs[KEY_AI_ICONS_QUALITY] = quality
        }
    }

    // ── Chess.com Integration ────────────────────────────────────────────

    /** Saves the chess.com enabled flag. */
    suspend fun saveChessComEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CHESS_COM_ENABLED] = enabled }
    }

    /** Saves the chess.com username. */
    suspend fun saveChessComUsername(username: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CHESS_COM_USERNAME] = username }
    }

    /** Saves the minutes-per-increment map for chess.com types. */
    suspend fun saveChessComMinutesPerIncrement(minutes: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHESS_COM_MINUTES_PER_INCREMENT] = encodeIntMap(minutes)
        }
    }

    /** Saves the map of habit name → chess.com type link. */
    suspend fun saveChessComHabitLinks(links: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHESS_COM_HABIT_LINKS] = encodeFileUriMap(links)
        }
    }

    /** Saves all chess.com settings at once. */
    suspend fun saveChessComSettings(
        enabled: Boolean,
        username: String,
        minutesPerIncrement: Map<String, Int>
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHESS_COM_ENABLED] = enabled
            prefs[KEY_CHESS_COM_USERNAME] = username
            prefs[KEY_CHESS_COM_MINUTES_PER_INCREMENT] = encodeIntMap(minutesPerIncrement)
        }
    }

    // ── Garmin Integration ────────────────────────────────────────────────

    /** Saves the Garmin enabled flag. */
    suspend fun saveGarminEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_GARMIN_ENABLED] = enabled }
    }

    /** Saves the Garmin proxy URL. */
    suspend fun saveGarminProxyUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GARMIN_PROXY_URL] = url }
    }

    /** Saves the Garmin app token. */
    suspend fun saveGarminAppToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GARMIN_APP_TOKEN] = token }
    }

    suspend fun saveGarminDateOfBirth(dateOfBirth: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GARMIN_DATE_OF_BIRTH] = dateOfBirth }
    }

    /** Saves the threshold map for Garmin metric types. */
    suspend fun saveGarminThresholds(thresholds: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GARMIN_THRESHOLDS] = encodeIntMap(thresholds)
        }
    }

    /** Saves the map of habit name → Garmin type link. */
    suspend fun saveGarminHabitLinks(links: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GARMIN_HABIT_LINKS] = encodeFileUriMap(links)
        }
    }

    /** Saves all Garmin settings at once. */
    suspend fun saveGarminSettings(
        enabled: Boolean,
        proxyUrl: String,
        appToken: String,
        dateOfBirth: String,
        thresholds: Map<String, Int>
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GARMIN_ENABLED] = enabled
            prefs[KEY_GARMIN_PROXY_URL] = proxyUrl
            prefs[KEY_GARMIN_APP_TOKEN] = appToken
            prefs[KEY_GARMIN_DATE_OF_BIRTH] = dateOfBirth
            prefs[KEY_GARMIN_THRESHOLDS] = encodeIntMap(thresholds)
        }
    }

    // ── Voice Trigger ────────────────────────────────────────────────────

    /** Saves the global voice trigger enabled flag. */
    suspend fun saveVoiceTriggerEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_TRIGGER_ENABLED] = enabled }
    }

    /** Saves the set of habits that have voice trigger enabled. */
    suspend fun saveVoiceTriggerHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_TRIGGER_HABITS] = habits }
    }

    /** Saves the map of habit name → set of trigger words. */
    suspend fun saveVoiceTriggerWords(words: Map<String, Set<String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOICE_TRIGGER_WORDS] = encodeLinkedHabitsMap(words)
        }
    }

    /** Saves the map of habit name → fixed voice increment amount. */
    suspend fun saveVoiceTriggerIncrements(increments: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VOICE_TRIGGER_INCREMENTS] = encodeIntMap(increments)
        }
    }

    /** Saves the set of habits that have "use subtypes voice" enabled. */
    suspend fun saveVoiceSubtypeHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_SUBTYPE_HABITS] = habits }
    }

    // ── Voice Note Dictation ─────────────────────────────────────────────

    /** Saves the global voice note enabled flag. */
    suspend fun saveVoiceNoteEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_NOTE_ENABLED] = enabled }
    }

    /** Saves the SAF URI for the voice note markdown file. */
    suspend fun saveVoiceNoteFileUri(uri: String) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_NOTE_FILE_URI] = uri }
    }

    // ── Map Screen Stats Settings ────────────────────────────────────────

    /** Saves the set of habits selected for display in the map stats panel. */
    suspend fun saveMapStatsHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_STATS_HABITS] = habits }
    }

    /** Saves the set of text-input habits whose text should be shown in the map stats panel. */
    suspend fun saveMapStatsShowTextHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_STATS_SHOW_TEXT_HABITS] = habits }
    }

    // ── Custom Point Ranges Settings ────────────────────────────────────────

    /** Saves the set of habits that have custom point ranges enabled. */
    suspend fun saveCustomPointRangesHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_CUSTOM_POINT_RANGES_HABITS] = habits }
    }

    /** Saves the map of habit name → list of 7 point ranges. */
    suspend fun saveCustomPointRanges(ranges: Map<String, List<PointRange>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_POINT_RANGES] = encodePointRangesMap(ranges)
        }
    }

    /** Saves the map of habit name → graph value mode (0 = points, 1 = value/raw). */
    suspend fun saveGraphValueModeHabits(modes: Map<String, Int>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GRAPH_VALUE_MODE_HABITS] = encodeIntMap(modes)
        }
    }
}
