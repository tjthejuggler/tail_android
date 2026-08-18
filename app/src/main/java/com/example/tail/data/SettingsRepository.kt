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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tail_settings")

private val KEY_FILE_URI = stringPreferencesKey("file_uri")
private val KEY_SCREENS_RELAY_FILE_URI = stringPreferencesKey("screens_relay_file_uri")
// PC floating widget sync (noteVault/tail/ folder shared via Syncthing)
private val KEY_PC_WIDGET_HABITS = stringSetPreferencesKey("pc_widget_habits")
// In-app stats overlay (StatsOverlayService) master switch
private val KEY_STATS_OVERLAY_ENABLED = booleanPreferencesKey("stats_overlay_enabled")
private val KEY_CUSTOM_INPUT = stringSetPreferencesKey("custom_input_habits")
private val KEY_HABIT_ORDER = stringPreferencesKey("habit_order")
private val KEY_HABIT_SCREENS = stringPreferencesKey("habit_screens")
private val KEY_ACTIVE_SCREEN_INDEX = intPreferencesKey("active_screen_index")
private val KEY_TEXT_INPUT_HABITS = stringSetPreferencesKey("text_input_habits")
private val KEY_TEXT_INPUT_OPTIONS_HABITS = stringSetPreferencesKey("text_input_options_habits")
private val KEY_SHARABLE_TEXT_HABITS = stringSetPreferencesKey("sharable_text_habits")
// Stored as "habitName\x00uri|||habitName\x00uri" pairs
private val KEY_TEXT_INPUT_FILE_URIS = stringPreferencesKey("text_input_file_uris")
// Stored as "habitName\x00iconName|||habitName\x00iconName" pairs
private val KEY_HABIT_ICONS = stringPreferencesKey("habit_icons")
// 1-max feature key
private val KEY_MAX_ONE_HABITS = stringSetPreferencesKey("max_one_habits")
// Inverted-binary feature key (e.g. coffee: point + streak on NOT-done days)
private val KEY_INVERTED_BINARY_HABITS = stringSetPreferencesKey("inverted_binary_habits")
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
// Per-link feed-target value overrides (source habit → linked habit → value key),
// stored with the nested-map codec (see [encodeNestedStringMap]).
private val KEY_CONDITIONAL_LINK_VALUES = stringPreferencesKey("conditional_link_values")
// Conditional habits whose Points feeds are capped at 1 per day (sub-setting
// of the conditional type — only meaningful while the habit is conditional).
private val KEY_CONDITIONAL_FEED_MAX_ONE_HABITS = stringSetPreferencesKey("conditional_feed_max_one_habits")
// Conditional habits whose feeds send POINTS (divider-applied delta) instead
// of the raw increment amount (sub-setting of the conditional type).
private val KEY_CONDITIONAL_FEED_POINTS_HABITS = stringSetPreferencesKey("conditional_feed_points_habits")
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
// Stored as "habitName\x00TYPE|||habitName\x00TYPE" pairs
private val KEY_CHESS_COM_HABIT_LINKS = stringPreferencesKey("chess_com_habit_links")
// Garmin integration settings
private val KEY_GARMIN_ENABLED = booleanPreferencesKey("garmin_enabled")
private val KEY_GARMIN_PROXY_URL = stringPreferencesKey("garmin_proxy_url")
private val KEY_GARMIN_APP_TOKEN = stringPreferencesKey("garmin_app_token")
private val KEY_GARMIN_DATE_OF_BIRTH = stringPreferencesKey("garmin_date_of_birth")
// Stored as "habitName\x00TYPE|||habitName\x00TYPE" pairs
private val KEY_GARMIN_HABIT_LINKS = stringPreferencesKey("garmin_habit_links")
// GitHub integration settings
private val KEY_GITHUB_ENABLED = booleanPreferencesKey("github_enabled")
private val KEY_GITHUB_TOKEN = stringPreferencesKey("github_token")
// Stored as "habitName\x00repoUrl|||habitName\x00repoUrl" pairs
private val KEY_GITHUB_REPO_URLS = stringPreferencesKey("github_repo_urls")
// Stored as "habitName\x00METRIC|||habitName\x00METRIC" pairs
private val KEY_GITHUB_METRICS = stringPreferencesKey("github_metrics")
// Tail Bridge settings (PC↔Phone communication protocol)
private val KEY_BRIDGE_ENABLED = booleanPreferencesKey("bridge_enabled")
private val KEY_BRIDGE_URL = stringPreferencesKey("bridge_url")
private val KEY_BRIDGE_TOKEN = stringPreferencesKey("bridge_token")
private val KEY_BRIDGE_MOVIE_HABITS = stringSetPreferencesKey("bridge_movie_habits")
// Movie confirmation flash: "title@lastWatched" markers of prompts already shown/handled
private val KEY_MOVIE_PROMPT_HANDLED = stringSetPreferencesKey("movie_prompt_handled")
// Scheduled habit asks: habit name → daily "HH:mm" ask time
private val KEY_HABIT_SCHEDULE_TIMES = stringPreferencesKey("habit_schedule_times")
// OMDb API key for IMDb ratings
private val KEY_OMDB_API_KEY = stringPreferencesKey("omdb_api_key")
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
private val KEY_MAP_MAIN_HABIT = stringPreferencesKey("map_main_habit")
private val KEY_MAP_HIDE_ZERO_DAYS = booleanPreferencesKey("map_hide_zero_days")
private val KEY_MAP_BEGIN_DATE = stringPreferencesKey("map_begin_date")
// Chess Readiness feature (Phase 1 diagnostic over the floating bubble)
private val KEY_CHESS_READINESS_ENABLED = booleanPreferencesKey("chess_readiness_enabled")
private val KEY_CHESS_READINESS_APP = stringPreferencesKey("chess_readiness_app")
// Stored as "habitName\x000|||habitName\x001" pairs (0 = points, 1 = value/raw, 2 = value2)
private val KEY_GRAPH_VALUE_MODE_HABITS = stringPreferencesKey("graph_value_mode_habits")
// Multi-select graph metrics: "habitName\x00metric1,metric2|||habitName\x00metric1"
private val KEY_GRAPH_METRIC_SELECTION = stringPreferencesKey("graph_metric_selection")
// Per-metric "interpolate zeros": "habitName\x00metric1,metric2|||habitName\x00metric1"
private val KEY_GRAPH_INTERPOLATE_ZERO_METRICS = stringPreferencesKey("graph_interpolate_zero_metrics")
// Secondary value habits (set of habit names that have a second value per day)
private val KEY_SECONDARY_VALUE_HABITS = stringSetPreferencesKey("secondary_value_habits")

// Secondary value fallback habits (use secondary value for points when primary is zero)
private val KEY_SECONDARY_VALUE_FALLBACK_HABITS = stringSetPreferencesKey("secondary_value_fallback_habits")

// Display-only custom labels for value/subtype columns.
// Stored as "habitName\x00key1\x02label1\x01key2\x02label2|||habitName2\x00..." pairs.
private val KEY_VALUE_DISPLAY_LABELS = stringPreferencesKey("value_display_labels")

// Custom point ranges settings
private val KEY_CUSTOM_POINT_RANGES_HABITS = stringSetPreferencesKey("custom_point_ranges_habits")
// Stored as "habitName\x00min0,max0|min1,max1|...|min6,max6|||habitName\x00..." pairs
private val KEY_CUSTOM_POINT_RANGES = stringPreferencesKey("custom_point_ranges")
// Stored as "habitName\x00note text|||habitName\x00another note" pairs (note text can contain any chars)
private val KEY_HABIT_NOTES = stringPreferencesKey("habit_notes")
// Roll forward habits (set of habit names)
private val KEY_ROLL_FORWARD_HABITS = stringSetPreferencesKey("roll_forward_habits")

// Roll forward manual dates (map of habit name → set of date strings)
private val KEY_ROLL_FORWARD_MANUAL_DATES = stringPreferencesKey("roll_forward_manual_dates")

// Migration flag — set to true after the one-time "Launch…Widget" → short-name rename.
private val KEY_MIGRATION_LAUNCH_RENAME_DONE = booleanPreferencesKey("migration_launch_rename_done")

// Migration flag — set to true after the one-time apnea secondary-value data migration.
private val KEY_MIGRATION_APNEA_SECONDARY_DONE = booleanPreferencesKey("migration_apnea_secondary_done")

// Migration flag — set to true after the one-time resonance-breathing secondary-value data migration.
private val KEY_MIGRATION_RESONANCE_SECONDARY_DONE = booleanPreferencesKey("migration_resonance_secondary_done")

// Migration flag — set to true after the one-time move of timer minutes from
// the generic secondary_value: slot to the first-class minutes: slot.
private val KEY_MIGRATION_MINUTES_SLOT_DONE = booleanPreferencesKey("migration_minutes_slot_done")
// One-time initialisation of the per-habit minutes-enabled set (derives it
// from widget connections, minutes-primary flags and existing minutes data).
private val KEY_MIGRATION_MINUTES_TOGGLE_INIT = booleanPreferencesKey("migration_minutes_toggle_init")
// True after the one-time widget/media/movie minutes backfill has run.
private val KEY_MIGRATION_MINUTES_WIDGET_BACKFILL = booleanPreferencesKey("migration_minutes_widget_backfill")

// One-time cleanup: chess.com sync used to record one timestamp per MINUTE;
// trim chess.com-linked habits' daily timestamps to one per game.
private val KEY_CHESS_TIMESTAMPS_TRIMMED = booleanPreferencesKey("migration_chess_timestamps_trimmed")

// Migration flag — set to true after the one-time import of the legacy external
// subtype/timed per-habit SAF JSON files into the internal stores (2026-08-15).
private val KEY_MIGRATION_SUBTYPE_TIMED_INTERNALIZED = booleanPreferencesKey("migration_subtype_timed_internalized")

// ── Meal Habit Engine keys ────────────────────────────────────────────────
private val KEY_MEAL_ENABLED = booleanPreferencesKey("meal_enabled")
private val KEY_MEAL_BASE_URL = stringPreferencesKey("meal_base_url")
private val KEY_MEAL_API_KEY = stringPreferencesKey("meal_api_key")
private val KEY_MEAL_MODEL = stringPreferencesKey("meal_model")
private val KEY_MEAL_SYSTEM_PROMPT = stringPreferencesKey("meal_system_prompt")
private val KEY_MEAL_HABITS = stringSetPreferencesKey("meal_habits")
private val KEY_CAMERA_HABITS = stringSetPreferencesKey("camera_habits")
// App link keys — stored as "app_link_key\x00label|||app_link_key\x00label" pairs
private val KEY_APP_LINKS = stringPreferencesKey("app_links")
// Habit app association keys — stored as "habitName\x00pkg1,pkg2,pkg3|||habitName\x00pkg1" pairs
private val KEY_HABIT_APP_ASSOCIATIONS = stringPreferencesKey("habit_app_associations")
// Habit long-press action keys — stored as "habitName\x00action|||habitName\x00action" pairs
private val KEY_HABIT_LONG_PRESS_ACTIONS = stringPreferencesKey("habit_long_press_actions")
// Habit long-press URL keys — stored as "habitName\x00url|||habitName\x00url" pairs
private val KEY_HABIT_LONG_PRESS_URLS = stringPreferencesKey("habit_long_press_urls")
// Habit long-press URL app keys — stored as "habitName\x00pkg|||habitName\x00pkg" pairs
private val KEY_HABIT_LONG_PRESS_URL_APPS = stringPreferencesKey("habit_long_press_url_apps")
// Widget trigger feature keys
private val KEY_WIDGET_TRIGGER_HABITS = stringSetPreferencesKey("widget_trigger_habits")
// Stored as "habitName\x00packageName|||habitName\x00packageName" pairs
private val KEY_WIDGET_TRIGGER_APPS = stringPreferencesKey("widget_trigger_apps")
// Widget-timer habits where minutes (not sessions) is the primary value
private val KEY_WIDGET_TIMER_MINUTES_PRIMARY = stringSetPreferencesKey("widget_timer_minutes_primary")
// Habits with the first-class minutes value explicitly enabled (opt-in toggle)
private val KEY_MINUTES_ENABLED_HABITS = stringSetPreferencesKey("minutes_enabled_habits")
// Per-habit fallback source for minutes-primary habits (habit → none/sessions/value2),
// stored as an encoded "habitName\x00source" kv string.
private val KEY_MINUTES_PRIMARY_FALLBACKS = stringPreferencesKey("minutes_primary_fallbacks")
// Media habit feature keys (podcasts, Spotify, any audio app).
// The serialized key strings are kept from the original podcast-only
// implementation so existing user configs load unchanged after the rename.
private val KEY_MEDIA_HABITS = stringSetPreferencesKey("podcast_habits")
// Stored as "habitName\x00packageName|||habitName\x00packageName" pairs
private val KEY_MEDIA_APPS = stringPreferencesKey("podcast_apps")

// ── Google Drive backup settings ──────────────────────────────────────
// Whether the automatic daily Google Drive backup is enabled.
private val KEY_GDRIVE_AUTO_ENABLED = booleanPreferencesKey("gdrive_auto_enabled")
// Account name (e-mail) of the signed-in Google account used for Drive backups.
private val KEY_GDRIVE_ACCOUNT_NAME = stringPreferencesKey("gdrive_account_name")
// ISO date ("YYYY-MM-DD") of the most recent successful Drive auto-backup.
private val KEY_GDRIVE_LAST_DATE = stringPreferencesKey("gdrive_last_backup_date")

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

// Serialisation helpers for habit notes (habit name → note text).
// Reuses the same PAIR_SEP / KV_SEP scheme as file URI maps.
private fun encodeHabitNotesMap(map: Map<String, String>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) -> "$k$KV_SEP$v" }

private fun decodeHabitNotesMap(raw: String): Map<String, String> {
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

// Serialisation helpers for Map<String, Set<String>> (habit name → set of metric keys).
// Format: "habitName\x00metric1,metric2|||habitName\x00metric1"
private fun encodeStringSetMap(map: Map<String, Set<String>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        "$k$KV_SEP${v.joinToString(",")}"
    }

private fun decodeStringSetMap(raw: String): Map<String, Set<String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val valueStr = pair.substring(idx + KV_SEP.length)
            val value = if (valueStr.isBlank()) emptySet()
            else valueStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
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

// Serialisation helpers for Map<String, Set<String>> (habit name → set of manually set date strings).
// Format: "habitName\x00date1,date2,date3|||habitName\x00..."
// Dates are stored as comma-separated YYYY-MM-DD strings.
private fun encodeRollForwardManualDates(map: Map<String, Set<String>>): String =
    map.entries.joinToString(PAIR_SEP) { (k, v) ->
        val datesStr = v.joinToString(",")
        "$k$KV_SEP$datesStr"
    }

private fun decodeRollForwardManualDates(raw: String): Map<String, Set<String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val key = pair.substring(0, idx)
            val datesStr = pair.substring(idx + 1)
            val dates = if (datesStr.isBlank()) emptySet() else datesStr.split(",").toSet()
            key to dates
        }
    }.toMap()
}

// Serialisation helpers for Map<String, Map<String, String>> (nested string maps:
// value-display labels, conditional link feed-value overrides).
// Outer format: "habitName\x00inner|||habitName2\x00inner" (same PAIR_SEP / KV_SEP as other maps).
// Inner format: "key1\x02label1\x01key2\x02label2" — uses control chars \x01 / \x02 which cannot
// appear in user-typed text, so no escaping is needed.
private const val INNER_PAIR_SEP = "\u0001"
private const val INNER_KV_SEP = "\u0002"

private fun encodeNestedStringMap(map: Map<String, Map<String, String>>): String =
    map.entries.joinToString(PAIR_SEP) { (habit, inner) ->
        val innerStr = inner.entries.joinToString(INNER_PAIR_SEP) { (k, v) ->
            "$k$INNER_KV_SEP$v"
        }
        "$habit$KV_SEP$innerStr"
    }

private fun decodeNestedStringMap(raw: String): Map<String, Map<String, String>> {
    if (raw.isBlank()) return emptyMap()
    return raw.split(PAIR_SEP).mapNotNull { pair ->
        val idx = pair.indexOf(KV_SEP)
        if (idx < 0) null
        else {
            val habit = pair.substring(0, idx)
            val innerStr = pair.substring(idx + KV_SEP.length)
            val inner = if (innerStr.isBlank()) emptyMap()
            else innerStr.split(INNER_PAIR_SEP).mapNotNull { entry ->
                val ki = entry.indexOf(INNER_KV_SEP)
                if (ki < 0) null
                else entry.substring(0, ki) to entry.substring(ki + INNER_KV_SEP.length)
            }.toMap()
            if (inner.isEmpty()) null else habit to inner
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
            migrateStringSet(KEY_SHARABLE_TEXT_HABITS)
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
            migrateKvKey(KEY_GRAPH_METRIC_SELECTION)
            migrateKvKey(KEY_HABIT_NOTES)
            migrateKvKey(KEY_VALUE_DISPLAY_LABELS)

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

    /**
     * Removes conditional-link entries that reference habits which no longer
     * exist (deleted from every screen and the flat order), plus entries whose
     * key is no longer marked as a conditional habit in [KEY_CONDITIONAL_HABITS].
     * Deleted habits otherwise linger as phantom "Fed by" sources on the habits
     * they used to feed (and as stale link targets). Also prunes the conditional
     * flag set and the feed-max-one set of deleted names. Idempotent: only
     * writes when something was actually removed, so it is safe to call on
     * every startup.
     */
    suspend fun pruneOrphanedConditionalLinks() {
        context.dataStore.edit { prefs ->
            // The set of habit names that actually exist right now. Mirrors
            // getAllHabitNames(): screens are authoritative when present; the
            // legacy flat order is only used when no screens exist. (The order
            // key is stale once screens are in use — deleting a habit in
            // screens mode never rewrites it — so unioning both would keep
            // long-deleted habits "valid" and defeat the prune.)
            // App-link entries are excluded just like in getAllHabitNames():
            // they are launch shortcuts, not habits, so any conditional flag
            // or link pointing at one is treated as orphaned and pruned.
            val screensRaw = prefs[KEY_HABIT_SCREENS] ?: ""
            val orderRaw = prefs[KEY_HABIT_ORDER] ?: ""
            val validNames = if (screensRaw.isNotEmpty()) {
                decodeScreens(screensRaw)
                    .flatMap { it.habitNames }
                    .filterTo(mutableSetOf()) { it.isNotEmpty() && !isAppLink(it) }
            } else {
                orderRaw.split("|||")
                    .filterTo(mutableSetOf()) { it.isNotEmpty() && !isAppLink(it) }
            }
            // Defensive: never wipe links when no habit names could be resolved
            // (e.g. unexpected empty decode) — treat as "cannot verify".
            if (validNames.isEmpty()) return@edit

            // Drop the conditional flag from deleted habits.
            val conditionalRaw = prefs[KEY_CONDITIONAL_HABITS] ?: emptySet()
            val conditional = conditionalRaw.filterTo(mutableSetOf()) { it in validNames }
            if (conditional != conditionalRaw) {
                Log.i("SettingsRepo", "Pruning conditional flags for deleted habits: ${conditionalRaw - conditional}")
                prefs[KEY_CONDITIONAL_HABITS] = conditional
            }

            // Keep only sources that are still conditional AND still exist;
            // drop linked targets that no longer exist.
            val linkedRaw = prefs[KEY_CONDITIONAL_LINKED_HABITS] ?: ""
            var pruned: Map<String, Set<String>> = emptyMap()
            if (linkedRaw.isNotBlank()) {
                val decoded = decodeLinkedHabitsMap(linkedRaw)
                pruned = decoded.mapNotNull { (src, targets) ->
                    if (src !in conditional) return@mapNotNull null
                    val kept = targets.filterTo(mutableSetOf()) { it in validNames }
                    if (kept.isEmpty()) null else src to kept
                }.toMap()
                if (pruned != decoded) {
                    val removedSources = decoded.keys - pruned.keys
                    val removedTargets = decoded.entries.flatMap { (src, targets) ->
                        (targets - (pruned[src] ?: emptySet())).map { "$src→$it" }
                    }
                    Log.i("SettingsRepo", "Pruning orphaned conditional links: sources=$removedSources targets=$removedTargets")
                    prefs[KEY_CONDITIONAL_LINKED_HABITS] = encodeLinkedHabitsMap(pruned)
                }
            }

            // Keep per-link feed-value overrides consistent with the link sets:
            // drop sources that are no longer conditional, and inner entries
            // whose linked habit is no longer in that source's link set.
            val valuesRaw = prefs[KEY_CONDITIONAL_LINK_VALUES]
            if (!valuesRaw.isNullOrBlank()) {
                val values = decodeNestedStringMap(valuesRaw)
                val prunedValues = values.mapNotNull { (src, inner) ->
                    val kept = inner.filterKeys { it in (pruned[src] ?: emptySet()) }
                    if (kept.isEmpty()) null else src to kept
                }.toMap()
                if (prunedValues != values) {
                    Log.i("SettingsRepo", "Pruning orphaned conditional link values for: ${values.keys - prunedValues.keys}")
                    prefs[KEY_CONDITIONAL_LINK_VALUES] = encodeNestedStringMap(prunedValues)
                }
            }

            // Drop feed-max-one flags for habits that no longer exist or are
            // no longer conditional.
            val feedMaxOneRaw = prefs[KEY_CONDITIONAL_FEED_MAX_ONE_HABITS] ?: emptySet()
            val prunedFeedMaxOne = feedMaxOneRaw.filterTo(mutableSetOf()) { it in conditional }
            if (prunedFeedMaxOne != feedMaxOneRaw) {
                Log.i("SettingsRepo", "Pruning feed-max-one flags for: ${feedMaxOneRaw - prunedFeedMaxOne}")
                prefs[KEY_CONDITIONAL_FEED_MAX_ONE_HABITS] = prunedFeedMaxOne
            }
        }
    }

    suspend fun isApneaSecondaryMigrationDone(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_APNEA_SECONDARY_DONE] ?: false }.first()
    }

    suspend fun setApneaSecondaryMigrationDone() {
        context.dataStore.edit { it[KEY_MIGRATION_APNEA_SECONDARY_DONE] = true }
    }

    suspend fun isResonanceSecondaryMigrationDone(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_RESONANCE_SECONDARY_DONE] ?: false }.first()
    }

    suspend fun setResonanceSecondaryMigrationDone() {
        context.dataStore.edit { it[KEY_MIGRATION_RESONANCE_SECONDARY_DONE] = true }
    }

    suspend fun isMinutesSlotMigrationDone(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_MINUTES_SLOT_DONE] ?: false }.first()
    }

    suspend fun setMinutesSlotMigrationDone() {
        context.dataStore.edit { it[KEY_MIGRATION_MINUTES_SLOT_DONE] = true }
    }

    /** True after the one-time minutes-enabled set initialisation has run. */
    suspend fun isMinutesToggleInitDone(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_MINUTES_TOGGLE_INIT] ?: false }.first()
    }

    suspend fun setMinutesToggleInitDone() {
        context.dataStore.edit { it[KEY_MIGRATION_MINUTES_TOGGLE_INIT] = true }
    }

    /** True after the one-time widget/media/movie minutes backfill has run. */
    suspend fun isMinutesWidgetBackfillDone(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_MINUTES_WIDGET_BACKFILL] ?: false }.first()
    }

    suspend fun setMinutesWidgetBackfillDone() {
        context.dataStore.edit { it[KEY_MIGRATION_MINUTES_WIDGET_BACKFILL] = true }
    }

    suspend fun isChessTimestampsTrimDone(): Boolean {
        return context.dataStore.data.map { it[KEY_CHESS_TIMESTAMPS_TRIMMED] ?: false }.first()
    }

    suspend fun setChessTimestampsTrimDone() {
        context.dataStore.edit { it[KEY_CHESS_TIMESTAMPS_TRIMMED] = true }
    }

    suspend fun isSubtypeTimedInternalized(): Boolean {
        return context.dataStore.data.map { it[KEY_MIGRATION_SUBTYPE_TIMED_INTERNALIZED] ?: false }.first()
    }

    suspend fun setSubtypeTimedInternalized() {
        context.dataStore.edit { it[KEY_MIGRATION_SUBTYPE_TIMED_INTERNALIZED] = true }
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
        val chessComHabitLinksRaw = prefs[KEY_CHESS_COM_HABIT_LINKS] ?: ""
        val voiceTriggerIncrementsRaw = prefs[KEY_VOICE_TRIGGER_INCREMENTS] ?: ""
        val customInputAmountsRaw = prefs[KEY_CUSTOM_INPUT_AMOUNTS] ?: ""
        val customInputRecentAmountsRaw = prefs[KEY_CUSTOM_INPUT_RECENT_AMOUNTS] ?: ""
        val garminHabitLinksRaw = prefs[KEY_GARMIN_HABIT_LINKS] ?: ""
        val githubRepoUrlsRaw = prefs[KEY_GITHUB_REPO_URLS] ?: ""
        val githubMetricsRaw = prefs[KEY_GITHUB_METRICS] ?: ""
        val customPointRangesRaw = prefs[KEY_CUSTOM_POINT_RANGES] ?: ""
        val graphValueModeHabitsRaw = prefs[KEY_GRAPH_VALUE_MODE_HABITS] ?: ""
        val graphMetricSelectionRaw = prefs[KEY_GRAPH_METRIC_SELECTION] ?: ""
        val habitNotesRaw = prefs[KEY_HABIT_NOTES] ?: ""
        AppSettings(
            fileUri = prefs[KEY_FILE_URI] ?: "",
            screensRelayFileUri = prefs[KEY_SCREENS_RELAY_FILE_URI] ?: "",
            pcWidgetHabits = prefs[KEY_PC_WIDGET_HABITS] ?: emptySet(),
            statsOverlayEnabled = prefs[KEY_STATS_OVERLAY_ENABLED] ?: false,
            customInputHabits = prefs[KEY_CUSTOM_INPUT] ?: DEFAULT_CUSTOM_INPUT_HABITS,
            habitOrder = customOrder,
            habitScreens = screens,
            activeScreenIndex = activeScreenIndex.coerceAtLeast(0),
            maxOneHabits = prefs[KEY_MAX_ONE_HABITS] ?: emptySet(),
            invertedBinaryHabits = prefs[KEY_INVERTED_BINARY_HABITS] ?: emptySet(),
            textInputHabits = prefs[KEY_TEXT_INPUT_HABITS] ?: emptySet(),
            textInputOptionsHabits = prefs[KEY_TEXT_INPUT_OPTIONS_HABITS] ?: emptySet(),
            sharableTextHabits = prefs[KEY_SHARABLE_TEXT_HABITS] ?: emptySet(),
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
            secondaryValueHabits = prefs[KEY_SECONDARY_VALUE_HABITS] ?: emptySet(),
            secondaryValueFallbackHabits = prefs[KEY_SECONDARY_VALUE_FALLBACK_HABITS] ?: emptySet(),
            conditionalFeedMaxOneHabits = prefs[KEY_CONDITIONAL_FEED_MAX_ONE_HABITS] ?: emptySet(),
            conditionalFeedPointsHabits = prefs[KEY_CONDITIONAL_FEED_POINTS_HABITS] ?: emptySet(),
            conditionalLinkValues = decodeNestedStringMap(prefs[KEY_CONDITIONAL_LINK_VALUES] ?: ""),
            valueDisplayLabels = decodeNestedStringMap(prefs[KEY_VALUE_DISPLAY_LABELS] ?: ""),
            aiIconsEnabled = prefs[KEY_AI_ICONS_ENABLED] ?: false,
            aiIconsApiKey = prefs[KEY_AI_ICONS_API_KEY] ?: "",
            aiIconsBaseUrl = prefs[KEY_AI_ICONS_BASE_URL] ?: "",
            aiIconsEndpoint = prefs[KEY_AI_ICONS_ENDPOINT] ?: "",
            aiIconsModel = prefs[KEY_AI_ICONS_MODEL] ?: "",
            aiIconsQuality = prefs[KEY_AI_ICONS_QUALITY] ?: "",
            chessComEnabled = prefs[KEY_CHESS_COM_ENABLED] ?: false,
            chessComUsername = prefs[KEY_CHESS_COM_USERNAME] ?: "",
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
            mapMainHabit = prefs[KEY_MAP_MAIN_HABIT]?.takeIf { it.isNotEmpty() },
            mapHideZeroDays = prefs[KEY_MAP_HIDE_ZERO_DAYS] ?: false,
            mapBeginDate = prefs[KEY_MAP_BEGIN_DATE] ?: "",
            garminEnabled = prefs[KEY_GARMIN_ENABLED] ?: false,
            garminProxyUrl = prefs[KEY_GARMIN_PROXY_URL] ?: "",
            garminAppToken = prefs[KEY_GARMIN_APP_TOKEN] ?: "",
            garminDateOfBirth = prefs[KEY_GARMIN_DATE_OF_BIRTH] ?: "",
            garminHabitLinks = decodeFileUriMap(garminHabitLinksRaw),
            githubEnabled = prefs[KEY_GITHUB_ENABLED] ?: false,
            githubToken = prefs[KEY_GITHUB_TOKEN] ?: "",
            githubRepoUrls = decodeFileUriMap(githubRepoUrlsRaw),
            githubMetrics = decodeFileUriMap(githubMetricsRaw),
            bridgeEnabled = prefs[KEY_BRIDGE_ENABLED] ?: false,
            bridgeUrl = prefs[KEY_BRIDGE_URL] ?: "",
            bridgeToken = prefs[KEY_BRIDGE_TOKEN] ?: "",
            bridgeMovieHabits = prefs[KEY_BRIDGE_MOVIE_HABITS] ?: emptySet(),
            omdbApiKey = prefs[KEY_OMDB_API_KEY] ?: "",
            habitScheduleTimes = decodeFileUriMap(prefs[KEY_HABIT_SCHEDULE_TIMES] ?: ""),
            customPointRangesHabits = prefs[KEY_CUSTOM_POINT_RANGES_HABITS] ?: emptySet(),
            customPointRanges = decodePointRangesMap(customPointRangesRaw),
            graphValueModeHabits = decodeIntMap(graphValueModeHabitsRaw),
            graphMetricSelection = decodeStringSetMap(graphMetricSelectionRaw),
            graphInterpolateZeroMetrics = decodeStringSetMap(prefs[KEY_GRAPH_INTERPOLATE_ZERO_METRICS] ?: ""),
            habitNotes = decodeHabitNotesMap(habitNotesRaw),
            rollForwardHabits = prefs[KEY_ROLL_FORWARD_HABITS] ?: emptySet(),
            rollForwardManualDates = decodeRollForwardManualDates(prefs[KEY_ROLL_FORWARD_MANUAL_DATES] ?: ""),
            mealEnabled = prefs[KEY_MEAL_ENABLED] ?: false,
            mealBaseUrl = prefs[KEY_MEAL_BASE_URL] ?: "",
            mealApiKey = prefs[KEY_MEAL_API_KEY] ?: "",
            mealModel = prefs[KEY_MEAL_MODEL] ?: "",
            mealSystemPrompt = prefs[KEY_MEAL_SYSTEM_PROMPT] ?: "",
            mealHabits = prefs[KEY_MEAL_HABITS] ?: emptySet(),
            cameraHabits = prefs[KEY_CAMERA_HABITS] ?: emptySet(),
            appLinks = decodeFileUriMap(prefs[KEY_APP_LINKS] ?: ""),
            habitAppAssociations = decodeSubtypesMap(prefs[KEY_HABIT_APP_ASSOCIATIONS] ?: ""),
            habitLongPressActions = decodeFileUriMap(prefs[KEY_HABIT_LONG_PRESS_ACTIONS] ?: ""),
            habitLongPressUrls = decodeFileUriMap(prefs[KEY_HABIT_LONG_PRESS_URLS] ?: ""),
            habitLongPressUrlApps = decodeFileUriMap(prefs[KEY_HABIT_LONG_PRESS_URL_APPS] ?: ""),
            widgetTriggerHabits = prefs[KEY_WIDGET_TRIGGER_HABITS] ?: emptySet(),
            widgetTriggerApps = decodeFileUriMap(prefs[KEY_WIDGET_TRIGGER_APPS] ?: ""),
            widgetTimerMinutesPrimary = prefs[KEY_WIDGET_TIMER_MINUTES_PRIMARY] ?: emptySet(),
            minutesEnabledHabits = prefs[KEY_MINUTES_ENABLED_HABITS] ?: emptySet(),
            minutesPrimaryFallbacks = decodeFileUriMap(prefs[KEY_MINUTES_PRIMARY_FALLBACKS] ?: ""),
            mediaHabits = prefs[KEY_MEDIA_HABITS] ?: emptySet(),
            mediaApps = decodeFileUriMap(prefs[KEY_MEDIA_APPS] ?: ""),
            chessReadinessEnabled = prefs[KEY_CHESS_READINESS_ENABLED] ?: false,
            chessReadinessApp = prefs[KEY_CHESS_READINESS_APP] ?: "",
            gdriveAutoEnabled = prefs[KEY_GDRIVE_AUTO_ENABLED] ?: false,
            gdriveAccountName = prefs[KEY_GDRIVE_ACCOUNT_NAME] ?: "",
            gdriveLastBackupDate = prefs[KEY_GDRIVE_LAST_DATE] ?: ""
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


    /** Saves the master switch for the in-app stats overlay. */
    suspend fun saveStatsOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STATS_OVERLAY_ENABLED] = enabled
        }
    }

    /** Saves the SAF URI for the screens_layout.json relay file. */
    suspend fun saveScreensRelayFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SCREENS_RELAY_FILE_URI] = uri
        }
    }

    /** Saves the set of habits shown on the PC floating bubble widget. */
    suspend fun savePcWidgetHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_PC_WIDGET_HABITS] = habits }
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

    /** Saves the set of habits that have the "inverted binary" type enabled. */
    suspend fun saveInvertedBinaryHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_INVERTED_BINARY_HABITS] = habits
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

    /** Saves the set of text-input habits that accept shares from the system share sheet. */
    suspend fun saveSharableTextHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SHARABLE_TEXT_HABITS] = habits
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

    /** Saves the set of habits that have secondary values enabled. */
    suspend fun saveSecondaryValueHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_SECONDARY_VALUE_HABITS] = habits }
    }

    /** Saves the set of habits that use the secondary value as a fallback for points. */
    suspend fun saveSecondaryValueFallbackHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_SECONDARY_VALUE_FALLBACK_HABITS] = habits }
    }

    /** Saves the display-only value/subtype label overrides (habit name → valueKey → label). */
    suspend fun saveValueDisplayLabels(labels: Map<String, Map<String, String>>) {
        context.dataStore.edit { prefs -> prefs[KEY_VALUE_DISPLAY_LABELS] = encodeNestedStringMap(labels) }
    }

    /** Persists per-link conditional feed-target value overrides (absent link = Points). */
    suspend fun saveConditionalLinkValues(values: Map<String, Map<String, String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONDITIONAL_LINK_VALUES] = encodeNestedStringMap(values)
        }
    }

    /** Saves the set of conditional habits whose point feeds are capped at 1 per day. */
    suspend fun saveConditionalFeedMaxOneHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONDITIONAL_FEED_MAX_ONE_HABITS] = habits
        }
    }

    /** Saves the set of conditional habits whose feeds send points instead of the raw count. */
    suspend fun saveConditionalFeedPointsHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CONDITIONAL_FEED_POINTS_HABITS] = habits
        }
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

    /** Saves the map of habit name → chess.com type link. */
    suspend fun saveChessComHabitLinks(links: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHESS_COM_HABIT_LINKS] = encodeFileUriMap(links)
        }
    }

    /** Saves all chess.com settings at once. */
    suspend fun saveChessComSettings(
        enabled: Boolean,
        username: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CHESS_COM_ENABLED] = enabled
            prefs[KEY_CHESS_COM_USERNAME] = username
        }
    }

    // ── GitHub Integration ────────────────────────────────────────────────

    /** Saves the GitHub enabled flag. */
    suspend fun saveGithubEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_GITHUB_ENABLED] = enabled }
    }

    /** Saves the optional GitHub Personal Access Token. */
    suspend fun saveGithubToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GITHUB_TOKEN] = token }
    }

    /** Saves the map of habit name → GitHub repo URL. */
    suspend fun saveGithubRepoUrls(urls: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GITHUB_REPO_URLS] = encodeFileUriMap(urls)
        }
    }

    /** Saves the map of habit name → GitHubMetric name. */
    suspend fun saveGithubMetrics(metrics: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GITHUB_METRICS] = encodeFileUriMap(metrics)
        }
    }

    /** Saves all GitHub settings at once. */
    suspend fun saveGithubSettings(enabled: Boolean, token: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GITHUB_ENABLED] = enabled
            prefs[KEY_GITHUB_TOKEN] = token
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
        dateOfBirth: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GARMIN_ENABLED] = enabled
            prefs[KEY_GARMIN_PROXY_URL] = proxyUrl
            prefs[KEY_GARMIN_APP_TOKEN] = appToken
            prefs[KEY_GARMIN_DATE_OF_BIRTH] = dateOfBirth
        }
    }

    // ── Tail Bridge ─────────────────────────────────────────────────────

    /** Saves all bridge settings at once. */
    suspend fun saveBridgeSettings(
        enabled: Boolean,
        url: String,
        token: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BRIDGE_ENABLED] = enabled
            prefs[KEY_BRIDGE_URL] = url
            prefs[KEY_BRIDGE_TOKEN] = token
        }
    }

    /** Saves the set of habits linked to the movie bridge. */
    suspend fun saveBridgeMovieHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_BRIDGE_MOVIE_HABITS] = habits }
    }

    /** Saves the habit name → daily "HH:mm" ask-time map for scheduled asks. */
    suspend fun saveHabitScheduleTimes(times: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_SCHEDULE_TIMES] = encodeFileUriMap(times) }
    }

    /** Returns the "title@lastWatched" markers of movie prompts already handled. */
    suspend fun getMoviePromptHandled(): Set<String> {
        return context.dataStore.data.map { it[KEY_MOVIE_PROMPT_HANDLED] ?: emptySet() }.first()
    }

    /** Saves the set of handled movie prompt markers. */
    suspend fun saveMoviePromptHandled(markers: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MOVIE_PROMPT_HANDLED] = markers }
    }

    /** Saves the OMDb API key for IMDb rating lookups. */
    suspend fun saveOmdbApiKey(apiKey: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OMDB_API_KEY] = apiKey }
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

    /** Saves the main habit that determines map dot colors. Pass null to use default monthly average. */
    suspend fun saveMapMainHabit(habitName: String?) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MAP_MAIN_HABIT] = habitName ?: ""
        }
    }

    /** Saves whether to hide days with 0 value (or 0 monthly average if no main habit). */
    suspend fun saveMapHideZeroDays(hide: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_HIDE_ZERO_DAYS] = hide }
    }

    /** Saves the custom start date for the map timeline. Pass empty string to use default earliest date. */
    suspend fun saveMapBeginDate(date: String) {
        context.dataStore.edit { prefs -> prefs[KEY_MAP_BEGIN_DATE] = date }
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

    /** Saves the map of habit name → set of selected graph metric keys. */
    suspend fun saveGraphMetricSelection(selection: Map<String, Set<String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GRAPH_METRIC_SELECTION] = encodeStringSetMap(selection)
        }
    }

    /** Saves the per-habit map of metric keys with "interpolate zeros" enabled. */
    suspend fun saveGraphInterpolateZeroMetrics(selection: Map<String, Set<String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GRAPH_INTERPOLATE_ZERO_METRICS] = encodeStringSetMap(selection)
        }
    }

    // ── Habit Notes ────────────────────────────────────────────────────────

    /** Saves the map of habit name → note text. */
    suspend fun saveHabitNotes(notes: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_NOTES] = encodeHabitNotesMap(notes)
        }
    }

    // ── Roll Forward Habits ─────────────────────────────────────────────────

    /** Saves the set of habits that have the "roll forward" feature enabled. */
    suspend fun saveRollForwardHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROLL_FORWARD_HABITS] = habits
        }
    }

    /** Saves the map of manually set dates for roll forward habits. */
    suspend fun saveRollForwardManualDates(dates: Map<String, Set<String>>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ROLL_FORWARD_MANUAL_DATES] = encodeRollForwardManualDates(dates)
        }
    }

    // ── Meal Habit Engine ────────────────────────────────────────────────

    /** Saves all meal engine settings at once (called from Settings screen). */
    suspend fun saveMealSettings(
        enabled: Boolean,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MEAL_ENABLED] = enabled
            prefs[KEY_MEAL_BASE_URL] = baseUrl
            prefs[KEY_MEAL_API_KEY] = apiKey
            prefs[KEY_MEAL_MODEL] = model
            prefs[KEY_MEAL_SYSTEM_PROMPT] = systemPrompt
        }
    }

    /** Saves the set of habits that have the "Meal" type enabled. */
    suspend fun saveMealHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MEAL_HABITS] = habits }
    }

    /** Saves the set of habits eligible for camera/vision auto-detection. */
    suspend fun saveCameraHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_CAMERA_HABITS] = habits }
    }

    /** Saves the map of app-link key → app display label. */
    suspend fun saveAppLinks(links: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_APP_LINKS] = encodeFileUriMap(links) }
    }

    /** Saves the map of habit name → ordered list of associated app package names. */
    suspend fun saveHabitAppAssociations(associations: Map<String, List<String>>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_APP_ASSOCIATIONS] = encodeSubtypesMap(associations) }
    }

    /** Saves the map of habit name → long-press action string. */
    suspend fun saveHabitLongPressActions(actions: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_LONG_PRESS_ACTIONS] = encodeFileUriMap(actions) }
    }

    /** Saves the map of habit name → URL opened on long-press (LONG_PRESS_URL action). */
    suspend fun saveHabitLongPressUrls(urls: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_LONG_PRESS_URLS] = encodeFileUriMap(urls) }
    }

    /** Saves the map of habit name → package that handles the long-press URL. */
    suspend fun saveHabitLongPressUrlApps(apps: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_HABIT_LONG_PRESS_URL_APPS] = encodeFileUriMap(apps) }
    }

    /** Saves the set of habits that have the "Use Widget" feature enabled. */
    suspend fun saveWidgetTriggerHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_WIDGET_TRIGGER_HABITS] = habits }
    }

    /** Saves the map of habit name → trigger app package name. */
    suspend fun saveWidgetTriggerApps(apps: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_WIDGET_TRIGGER_APPS] = encodeFileUriMap(apps) }
    }

    /** Saves the set of widget-timer habits where minutes is the primary value. */
    suspend fun saveWidgetTimerMinutesPrimary(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_WIDGET_TIMER_MINUTES_PRIMARY] = habits }
    }

    /** Saves the set of habits with the minutes value explicitly enabled. */
    suspend fun saveMinutesEnabledHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MINUTES_ENABLED_HABITS] = habits }
    }

    /** Saves the per-habit fallback source for minutes-primary habits. */
    suspend fun saveMinutesPrimaryFallbacks(map: Map<String, String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MINUTES_PRIMARY_FALLBACKS] = encodeFileUriMap(map)
        }
    }

    /** Saves the set of habits that have the "Media" type enabled. */
    suspend fun saveMediaHabits(habits: Set<String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MEDIA_HABITS] = habits }
    }

    /** Saves the map of habit name → media app package name. */
    suspend fun saveMediaApps(apps: Map<String, String>) {
        context.dataStore.edit { prefs -> prefs[KEY_MEDIA_APPS] = encodeFileUriMap(apps) }
    }

    /** Saves the Chess Readiness global toggle. */
    suspend fun saveChessReadinessEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CHESS_READINESS_ENABLED] = enabled }
    }

    /** Saves the package name of the app associated with Chess Readiness. */
    suspend fun saveChessReadinessApp(packageName: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CHESS_READINESS_APP] = packageName }
    }

    // ── Google Drive Backup ──────────────────────────────────────────────

    /** Saves whether the automatic daily Google Drive backup is enabled. */
    suspend fun saveGdriveAutoEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_GDRIVE_AUTO_ENABLED] = enabled }
    }

    /** Saves the account name (e-mail) of the signed-in Google account. */
    suspend fun saveGdriveAccountName(accountName: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GDRIVE_ACCOUNT_NAME] = accountName }
    }

    /** Saves the ISO date of the most recent successful Drive auto-backup. */
    suspend fun saveGdriveLastBackupDate(date: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GDRIVE_LAST_DATE] = date }
    }
}
