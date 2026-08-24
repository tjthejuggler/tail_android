package com.example.tail.data.backup

/**
 * On-disk JSON schema for a complete Tail backup.
 *
 * IMPORTANT: This file is the canonical wire format. Adding new fields is fine —
 * they will be ignored by older readers. Removing or renaming fields requires
 * bumping [BackupBundle.schemaVersion] and writing a migration in
 * [BackupManager].
 *
 * Every property is nullable / has a default so a partial / forward-compatible
 * backup can still be parsed.
 */
data class BackupBundle(
    /** Wire-format version. Bumped on incompatible changes. */
    val schemaVersion: Int = SCHEMA_VERSION,

    /** Human readable app version label (best-effort, may be empty). */
    val appVersion: String = "",

    /** ISO-8601 UTC timestamp of when this backup file was written. */
    val exportedAt: String = "",

    /** Marker so we can validate this is actually a Tail backup file. */
    val magic: String = MAGIC,

    /** All DataStore-backed app settings — see [com.example.tail.data.AppSettings]. */
    val settings: SettingsSection = SettingsSection(),

    /** Advice banner items (incl. notes). */
    val advice: List<AdviceBackupItem> = emptyList(),

    /** Location data: per-day labels, per-day coordinates, ignored country list. */
    val locations: LocationsSection = LocationsSection(),

    /** Debug-mode persistent state (saved notes etc.). */
    val debug: DebugSection = DebugSection(),

    /**
     * Full content of `habitsdb.txt` (the unified habit database). Map of
     * habit name → date string → raw count. May be empty if the user has not
     * picked a habits-DB file yet, in which case nothing was loaded.
     */
    val habitsDb: Map<String, Map<String, Int>> = emptyMap(),

    /**
     * Full content of `files/habit_timestamps.json` — per-habit per-date
     * timestamp lists. Internal storage; always available.
     */
    val habitTimestamps: Map<String, Map<String, List<String>>> = emptyMap(),

    /** AI-generated icon library: metadata index + base64-encoded PNG bytes. */
    val aiIcons: AiIconsSection = AiIconsSection(),

    /**
     * Content of the per-habit external files (text-input logs, dated-entry
     * sources, subtype JSONs, timed JSONs). Keyed by habit name so they can
     * be re-written to new SAF URIs the user picks after import.
     */
    val perHabitFiles: PerHabitFilesSection = PerHabitFilesSection(),

    /** Full markdown text of the voice-note dictation file (or null). */
    val voiceNoteMarkdown: String? = null,

    /** Meal-habit logs + captured meal photos (base64 JPEG). */
    val meal: MealSection = MealSection(),

    /** Raw content of `files/vision_queue.json` (pending vision captures), or null. */
    val visionQueueJson: String? = null,

    /**
     * Additional SharedPreferences stores that hold user data but are not part
     * of AppSettings (chess readiness history, chess phase-2 audits, …).
     * Keyed by prefs file name.
     */
    val extraPrefs: Map<String, List<PrefEntryBackup>> = emptyMap()
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val MAGIC = "tail-backup"
    }
}

/**
 * Mirror of [com.example.tail.data.AppSettings] as plain JSON. We use a
 * dedicated copy (instead of serialising AppSettings directly) so future
 * field renames in AppSettings do not silently break older backups.
 */
data class SettingsSection(
    val fileUri: String = "",
    val screensRelayFileUri: String = "",
    val pcWidgetHabits: List<String> = emptyList(),
    val customInputHabits: List<String> = emptyList(),
    val habitOrder: List<String> = emptyList(),
    val habitScreens: List<HabitScreenBackup> = emptyList(),
    val activeScreenIndex: Int = 0,
    val maxOneHabits: List<String> = emptyList(),
    val invertedBinaryHabits: List<String> = emptyList(),
    val minutesEnabledHabits: List<String> = emptyList(),
    val minutesPrimaryFallbacks: Map<String, String> = emptyMap(),
    val textInputHabits: List<String> = emptyList(),
    val textInputOptionsHabits: List<String> = emptyList(),
    val textInputFileUris: Map<String, String> = emptyMap(),
    val habitIcons: Map<String, String> = emptyMap(),
    val datedEntryHabits: List<String> = emptyList(),
    val datedEntryFileUris: Map<String, String> = emptyMap(),
    val datedEntryFileSizes: Map<String, Long> = emptyMap(),
    val habitDividers: Map<String, Int> = emptyMap(),
    val conditionalHabits: List<String> = emptyList(),
    val conditionalLinkedHabits: Map<String, List<String>> = emptyMap(),
    val conditionalLinkValues: Map<String, Map<String, String>> = emptyMap(),
    val conditionalFeedMaxOneHabits: List<String> = emptyList(),
    val conditionalFeedPointsHabits: List<String> = emptyList(),
    val subtypedHabits: List<String> = emptyList(),
    val habitSubtypes: Map<String, List<String>> = emptyMap(),
    val subtypeDataFileUris: Map<String, String> = emptyMap(),
    val timedHabits: List<String> = emptyList(),
    val timedDataFileUris: Map<String, String> = emptyMap(),
    val timelessHabits: List<String> = emptyList(),
    val hiddenScreens: List<String> = emptyList(),
    val disabledHabits: List<String> = emptyList(),
    val noPointsHabits: List<String> = emptyList(),
    val aiIconsEnabled: Boolean = false,
    val aiIconsApiKey: String = "",
    val aiIconsBaseUrl: String = "",
    val aiIconsEndpoint: String = "",
    val aiIconsModel: String = "",
    val aiIconsQuality: String = "",
    val chessComEnabled: Boolean = false,
    val chessComUsername: String = "",
    val chessComHabitLinks: Map<String, String> = emptyMap(),
    val voiceTriggerEnabled: Boolean = false,
    val voiceTriggerHabits: List<String> = emptyList(),
    val voiceTriggerWords: Map<String, List<String>> = emptyMap(),
    val voiceTriggerIncrements: Map<String, Int> = emptyMap(),
    val voiceSubtypeHabits: List<String> = emptyList(),
    val voiceNoteEnabled: Boolean = false,
    val voiceNoteFileUri: String = "",

    // ── Fields added in the 2026-08 completeness audit ───────────────────
    val sharableTextHabits: List<String> = emptyList(),
    val secondaryValueHabits: List<String> = emptyList(),
    val secondaryValueFallbackHabits: List<String> = emptyList(),
    val valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    val customInputAmounts: Map<String, List<Int>> = emptyMap(),
    val customInputRecentAmounts: Map<String, List<Int>> = emptyMap(),
    val autoBackupFolderUri: String = "",
    val mapStatsHabits: List<String> = emptyList(),
    val mapStatsShowTextHabits: List<String> = emptyList(),
    val mapMainHabit: String? = null,
    val mapHideZeroDays: Boolean = false,
    val mapBeginDate: String = "",
    val garminEnabled: Boolean = false,
    val garminProxyUrl: String = "",
    val garminAppToken: String = "",
    val garminDateOfBirth: String = "",
    val garminHabitLinks: Map<String, String> = emptyMap(),
    val githubEnabled: Boolean = false,
    val githubToken: String = "",
    val githubRepoUrls: Map<String, String> = emptyMap(),
    val githubMetrics: Map<String, String> = emptyMap(),
    val bridgeEnabled: Boolean = false,
    val bridgeUrl: String = "",
    val bridgeToken: String = "",
    val bridgeMovieHabits: List<String> = emptyList(),
    val omdbApiKey: String = "",
    val customPointRangesHabits: List<String> = emptyList(),
    val customPointRanges: Map<String, List<PointRangeBackup>> = emptyMap(),
    val graphValueModeHabits: Map<String, Int> = emptyMap(),
    val graphMetricSelection: Map<String, List<String>> = emptyMap(),
    val graphInterpolateZeroMetrics: Map<String, List<String>> = emptyMap(),
    val habitNotes: Map<String, String> = emptyMap(),
    val rollForwardHabits: List<String> = emptyList(),
    val rollForwardManualDates: Map<String, List<String>> = emptyMap(),
    val mealEnabled: Boolean = false,
    val mealBaseUrl: String = "",
    val mealApiKey: String = "",
    val mealModel: String = "",
    val mealSystemPrompt: String = "",
    val mealHabits: List<String> = emptyList(),
    val weightsHabits: List<String> = emptyList(),
    val weightsRecentExercises: Map<String, List<String>> = emptyMap(),
    val cameraHabits: List<String> = emptyList(),
    val appLinks: Map<String, String> = emptyMap(),
    val habitAppAssociations: Map<String, List<String>> = emptyMap(),
    val habitLongPressActions: Map<String, String> = emptyMap(),
    val habitLongPressUrls: Map<String, String> = emptyMap(),
    val habitLongPressUrlApps: Map<String, String> = emptyMap(),
    val widgetTriggerHabits: List<String> = emptyList(),
    val widgetTriggerApps: Map<String, String> = emptyMap(),
    val widgetTimerMinutesPrimary: List<String> = emptyList(),
    val chessReadinessEnabled: Boolean = false,
    val chessReadinessApp: String = "",
    val gdriveAutoEnabled: Boolean = false,
    val gdriveAccountName: String = "",

    // Points-driven wallpaper (enum names persisted as strings)
    val wallpaperEnabled: Boolean = false,
    val wallpaperDirUri: String = "",
    val wallpaperTarget: String = "SYSTEM",
    val wallpaperMetric: String = "TODAY"
)

/** Backup form of [com.example.tail.data.PointRange]. */
data class PointRangeBackup(
    val min: Int = 0,
    val max: Int = 0
)

/** Backup form of [com.example.tail.data.HabitScreen]. */
data class HabitScreenBackup(
    val id: String = "",
    val name: String = "",
    val habitNames: List<String> = emptyList()
)

/** Backup form of [com.example.tail.data.AdviceItem] (id+text+notes+createdAt). */
data class AdviceBackupItem(
    val id: Long = 0L,
    val text: String = "",
    val notes: String? = null,
    val createdAt: Long = 0L
)

data class LocationsSection(
    /** date string ("YYYY-MM-DD") → "City, Region, Country" label. */
    val labels: Map<String, String> = emptyMap(),
    /** date string ("YYYY-MM-DD") → "lat,lon" string. */
    val coords: Map<String, String> = emptyMap(),
    /** Country / region names the user excludes from the country count. */
    val ignoredCountries: List<String> = emptyList(),
    /** Whether the one-time US-states seed has run. */
    val ignoredCountriesSeeded: Boolean = false
)

data class DebugSection(
    val debugModeEnabled: Boolean = false,
    val debugFileDirUri: String = "",
    val savedNotes: List<DebugSavedNoteBackup> = emptyList(),
    /** Raw content of `files/debug_tail.json` (submitted debug notes archive), or null. */
    val debugTailJson: String? = null
)

/** Backup form of [com.example.tail.data.debug.SavedNote]. */
data class DebugSavedNoteBackup(
    val id: String = "",
    val timestamp: String = "",
    val screenRoute: String = "",
    val screenLabel: String = "",
    val sourceFile: String = "",
    val sourceFunctions: String = "",
    val noteType: String = "",
    val noteText: String = ""
)

data class AiIconsSection(
    /** Metadata for every saved AI icon. */
    val index: List<AiIconBackup> = emptyList(),
    /**
     * Map of icon id → base64-encoded PNG bytes. We embed the PNGs directly so
     * the backup file is fully self-contained.
     */
    val files: Map<String, String> = emptyMap()
)

data class AiIconBackup(
    val id: String = "",
    val prompt: String = "",
    val createdAt: String = ""
)

data class PerHabitFilesSection(
    /** habit name → text-log map ("YYYY-MM-DD HH:mm:ss" → text). */
    val textInput: Map<String, Map<String, String>> = emptyMap(),

    /** habit name → full plain-text content of the dated-entry source file. */
    val datedEntry: Map<String, String> = emptyMap(),

    /** habit name → subtype data map ("YYYY-MM-DD" → { subtype → count }). */
    val subtypeData: Map<String, Map<String, Map<String, Int>>> = emptyMap(),

    /**
     * habit name → timed data map ("YYYY-MM-DD HH:mm:ss" → { "subtype": ?, "count": N }).
     * Subtype value is stored as a String (or null) inside a generic map so we
     * don't have to drag the runtime [com.example.tail.data.TimedEntry] type
     * through Gson reflection.
     */
    val timedData: Map<String, Map<String, Map<String, Any?>>> = emptyMap()
)

/**
 * Meal-habit engine data from internal storage:
 *  - `files/meal_logs/<sanitised-habit>.json` — raw JSON text per file
 *  - `files/meal_images/<uuid>.jpg` — base64-encoded JPEG bytes per file
 */
data class MealSection(
    val logs: Map<String, String> = emptyMap(),
    val images: Map<String, String> = emptyMap()
)

/**
 * One typed SharedPreferences entry. The explicit type tag lets restore write
 * the value back with the correct putX() call (SharedPreferences stores values
 * per-type; writing an Int as Long would crash later getInt() readers).
 */
data class PrefEntryBackup(
    val key: String = "",
    val type: String = "",
    val boolValue: Boolean? = null,
    val intValue: Long? = null,
    val floatValue: Double? = null,
    val stringValue: String? = null,
    val stringSetValue: List<String> = emptyList()
)
