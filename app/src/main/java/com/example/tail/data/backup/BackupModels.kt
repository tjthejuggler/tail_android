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
    val voiceNoteMarkdown: String? = null
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
    val taskerFileUri: String = "",
    val customInputHabits: List<String> = emptyList(),
    val habitOrder: List<String> = emptyList(),
    val habitScreens: List<HabitScreenBackup> = emptyList(),
    val activeScreenIndex: Int = 0,
    val maxOneHabits: List<String> = emptyList(),
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
    val chessComMinutesPerIncrement: Map<String, Int> = emptyMap(),
    val chessComHabitLinks: Map<String, String> = emptyMap(),
    val voiceTriggerEnabled: Boolean = false,
    val voiceTriggerHabits: List<String> = emptyList(),
    val voiceTriggerWords: Map<String, List<String>> = emptyMap(),
    val voiceTriggerIncrements: Map<String, Int> = emptyMap(),
    val voiceSubtypeHabits: List<String> = emptyList(),
    val voiceNoteEnabled: Boolean = false,
    val voiceNoteFileUri: String = ""
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
    val savedNotes: List<DebugSavedNoteBackup> = emptyList()
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
