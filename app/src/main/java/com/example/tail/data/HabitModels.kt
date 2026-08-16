package com.example.tail.data

// ════════════════════════════════════════════════════════════════════════════
//  Long-press action constants
// ════════════════════════════════════════════════════════════════════════════

/** Long-press launches the associated app(s) or opens the app picker (default). */
const val LONG_PRESS_APP = "app"
/** Long-press opens the camera capture screen (meal habits only). */
const val LONG_PRESS_CAMERA = "camera"
/** Long-press opens the meal details dialog (meal habits only). */
const val LONG_PRESS_DETAILS = "details"
/** Long-press opens the configured URL (see AppSettings.habitLongPressUrls). */
const val LONG_PRESS_URL = "url"

/**
 * Returns the effective long-press action for a habit.
 * Defaults to [LONG_PRESS_APP] when no explicit value is stored.
 */
fun effectiveLongPressAction(stored: String?): String =
    stored?.takeIf { it.isNotBlank() } ?: LONG_PRESS_APP

/**
 * All valid long-press actions for a non-meal habit.
 */
val STANDARD_LONG_PRESS_ACTIONS = listOf(LONG_PRESS_APP, LONG_PRESS_URL)

/**
 * All valid long-press actions for a meal habit.
 */
val MEAL_LONG_PRESS_ACTIONS = listOf(LONG_PRESS_APP, LONG_PRESS_URL, LONG_PRESS_CAMERA, LONG_PRESS_DETAILS)

/**
 * All-time high for a rolling window: the peak average value and the date it occurred.
 */
data class RollingHigh(
    val value: Double,   // the peak rolling average
    val date: String     // "YYYY-MM-DD" of the peak
)

/**
 * Represents a single point range for custom point calculation.
 * Each range has a min and max value (inclusive) that determines which point value
 * is assigned based on the "true value" or "garmin value" of a habit.
 */
data class PointRange(
    val min: Int = Int.MIN_VALUE,
    val max: Int = Int.MAX_VALUE
)

/**
 * Represents a single habit with all computed stats for display.
 */
data class Habit(
    val name: String,
    /** The effective "points" value for today — raw count divided by [divider] (rounded, min 1 if non-zero). */
    val todayCount: Int = 0,
    /** The raw stored count for today, before any divider is applied. Used in the edit bar. */
    val rawTodayCount: Int = 0,
    val currentStreak: Int = 0,       // positive = streak, negative = antistreak
    val longestStreak: Int = 0,
    val allTimeHighDay: Int = 0,      // top-left: max single-day raw count
    val useCustomInput: Boolean = false,

    /**
     * When > 1, the raw stored count is divided by this value (rounded to nearest int)
     * to produce the displayed "points" value. The raw count is always stored as-is in
     * the database; only the display and totals use the divided value.
     * 0 or 1 means no division (normal behaviour).
     */
    val divider: Int = 1,

    // Current rolling averages (matching desktop current_values)
    val currentDayValue: Int = 0,     // most recent entry's raw value
    val avgLast7Days: Double = 0.0,
    val avgLast30Days: Double = 0.0,
    val avgLast365Days: Double = 0.0,

    // All-time high rolling windows (matching desktop all_time_high_values)
    val allTimeHighWeek: RollingHigh = RollingHigh(0.0, ""),
    val allTimeHighMonth: RollingHigh = RollingHigh(0.0, ""),
    val allTimeHighYear: RollingHigh = RollingHigh(0.0, ""),
    val allTimeHighDayDate: String = ""  // date of the all-time high single day
)

/**
 * Lightweight per-day stats used by the world-map screen's info panel.
 * Computed on demand from the in-memory cached habit DB; we deliberately
 * skip the full streak rebuild so the slider stays smooth while scrubbing.
 */
data class DayStats(
    val date: java.time.LocalDate,
    /** Sum of points (raw / divider) across all tracked habits with non-zero raw. */
    val totalPoints: Int,
    /**
     * Average daily points over the 30-day window ending on [date]
     * (i.e. [date] and the 29 days prior).
     */
    val monthlyAverage: Double,
    /**
     * Length of the consecutive run of "any habit done" days ending on [date].
     * Treated as a rough day-level streak indicator on the map info panel.
     */
    val streakDays: Int,
    /**
     * Length of the consecutive run of "no habit done" days ending on [date].
     * Treated as a rough day-level anti-streak indicator on the map info panel.
     */
    val antiStreakDays: Int
)

/**
 * Returns the effective "points" value for a raw count given a divider.
 * When [divider] <= 1 the raw count is returned unchanged.
 * Otherwise the result is rounded to the nearest whole number.
 * If the raw count is > 0 the result is always at least 1 (never rounds down to 0).
 */
fun applyDivider(rawCount: Int, divider: Int): Int {
    if (divider <= 1) return rawCount
    if (rawCount <= 0) return 0
    val divided = Math.round(rawCount.toDouble() / divider).toInt()
    return maxOf(divided, 1)
}

/**
 * Calculates points from custom point ranges.
 * Returns the index (0-6) of the first range that contains [value], or 0 if no match.
 * Ranges are checked in order; the first matching range wins.
 */
fun calculatePointsFromRanges(value: Int, ranges: List<PointRange>): Int {
    for ((index, range) in ranges.withIndex()) {
        if (value >= range.min && value <= range.max) {
            return index
        }
    }
    return 0
}

/**
 * Raw database format matching habitsdb.txt:
 * { "Habit Name": { "2026-01-05": 1, "2026-01-06": 0 } }
 */
typealias HabitsDatabase = Map<String, Map<String, Int>>

// ── App Link helpers ────────────────────────────────────────────────────────
/**
 * Prefix used to distinguish app-link entries from regular habits in the
 * screen's [HabitScreen.habitNames] list. The full key is
 * `"$APP_LINK_PREFIX<packageName>"` (e.g. `"app_link:com.example.app"`).
 */
const val APP_LINK_PREFIX = "app_link:"

/** Returns true if [name] is an app-link entry (starts with [APP_LINK_PREFIX]). */
fun isAppLink(name: String): Boolean = name.startsWith(APP_LINK_PREFIX)

/** Builds the internal key for an app link from its [packageName]. */
fun appLinkKey(packageName: String): String = "$APP_LINK_PREFIX$packageName"

/** Extracts the package name from an app-link key, or null if [name] is not an app link. */
fun appLinkPackageName(name: String): String? =
    if (isAppLink(name)) name.removePrefix(APP_LINK_PREFIX) else null

// ── Secondary Value helpers ──────────────────────────────────────────────────
/**
 * Prefix used to store secondary-value entries alongside regular habits in the
 * shared habitsdb.txt JSON file.  For a habit named "Meditations", its
 * secondary values are stored under the key `"secondary_value:Meditations"`.
 *
 * This convention lets secondary values sync automatically via Syncthing (same
 * file), be written by external tools (Python scripts, Wags), and be filtered
 * out of habit-list / stats iterations with a simple prefix check.
 */
const val SECONDARY_VALUE_PREFIX = "secondary_value:"

/**
 * Prefix for the SECONDARY value slot (`"secondary_value2:Habit"`), used by
 * integrations that feed three values per day into one habit (e.g. chess.com:
 * games → primary count, minutes → `secondary_value:`, wins → `secondary_value2:`).
 */
const val SECONDARY_VALUE2_PREFIX = "secondary_value2:"

/**
 * Returns true if [name] is any secondary-value storage key
 * (either [SECONDARY_VALUE_PREFIX] or [SECONDARY_VALUE2_PREFIX]).
 */
fun isSecondaryValueKey(name: String): Boolean =
    name.startsWith(SECONDARY_VALUE_PREFIX) || name.startsWith(SECONDARY_VALUE2_PREFIX)

/** Returns true if [name] is a second-slot secondary-value storage key. */
fun isSecondaryValue2Key(name: String): Boolean = name.startsWith(SECONDARY_VALUE2_PREFIX)

/** Builds the storage key for the secondary values of [habitName]. */
fun secondaryValueKey(habitName: String): String = "$SECONDARY_VALUE_PREFIX$habitName"

/** Builds the storage key for the second-slot secondary values of [habitName]. */
fun secondaryValue2Key(habitName: String): String = "$SECONDARY_VALUE2_PREFIX$habitName"

/** Extracts the habit name from a secondary-value key, or null if [name] is not one. */
fun secondaryValueHabitName(name: String): String? = when {
    name.startsWith(SECONDARY_VALUE_PREFIX) -> name.removePrefix(SECONDARY_VALUE_PREFIX)
    name.startsWith(SECONDARY_VALUE2_PREFIX) -> name.removePrefix(SECONDARY_VALUE2_PREFIX)
    else -> null
}

// ── Display-label helpers (UI-only overrides) ────────────────────────────────
/**
 * Returns the **default** human-readable label for a value/metric key when no
 * custom display label has been set by the user.
 *
 * For secondary-value metrics ([GRAPH_METRIC_VALUE1], [GRAPH_METRIC_VALUE2]) and
 * meal metrics, this returns a fixed English string.  For subtype names (which
 * are arbitrary user-defined strings), the default label IS the subtype name
 * itself, so the [else] branch handles that case.
 */
fun defaultLabelForValueKey(valueKey: String): String = when (valueKey) {
    GRAPH_METRIC_POINTS -> "Points"
    GRAPH_METRIC_VALUE1 -> "Value 1"
    GRAPH_METRIC_VALUE2 -> "Value 2"
    GRAPH_METRIC_VALUE3 -> "Value 3"
    GRAPH_METRIC_CALORIES -> "Calories"
    GRAPH_METRIC_PROTEIN -> "Protein"
    GRAPH_METRIC_CARBS -> "Carbs"
    GRAPH_METRIC_FAT -> "Fat"
    GRAPH_METRIC_IMDB -> "IMDb Avg"
    GRAPH_METRIC_RUNTIME -> "Runtime (min)"
    GRAPH_METRIC_GITHUB_LINES -> "Lines Changed"
    GRAPH_METRIC_GITHUB_COMMITS -> "Commits"
    GRAPH_METRIC_GITHUB_ADDITIONS -> "Additions"
    GRAPH_METRIC_GITHUB_DELETIONS -> "Deletions"
    else -> valueKey
}

/**
 * Resolves the display label for a given [habitName] + [valueKey].
 *
 * [labels] is the `valueDisplayLabels` map from [AppSettings] — a nested map of
 * `habitName → (valueKey → customLabel)`.  If a non-blank custom label exists it
 * is returned; otherwise the [defaultLabelForValueKey] fallback is used.
 *
 * This is **display-only**: the underlying [valueKey] (e.g. `"value2"` or a
 * subtype name) is never changed, so backend storage, external integrations
 * (ContentProvider, Python scripts, Wags), and the subtype-data JSON files are
 * completely unaffected.
 */
fun displayLabelForValue(
    habitName: String,
    valueKey: String,
    labels: Map<String, Map<String, String>>
): String {
    val custom = labels[habitName]?.get(valueKey)
    return if (!custom.isNullOrBlank()) custom else defaultLabelForValueKey(valueKey)
}

// ── Graph metric keys ────────────────────────────────────────────────────────
// String keys used by [AppSettings.graphMetricSelection] and the graph UI.
// Multiple metrics can be active per habit, each rendered as a separate line.

/** Points (count-based, applies to all habits). */
const val GRAPH_METRIC_POINTS = "points"
/** Value1 — raw value / Garmin value (applies to all habits). */
const val GRAPH_METRIC_VALUE1 = "value1"
/** Value2 — secondary value (only for habits in [AppSettings.secondaryValueHabits]). */
const val GRAPH_METRIC_VALUE2 = "value2"
/**
 * Value3 — second-slot secondary value (`secondary_value2:` storage key).
 * Currently written by the chess.com integration (daily win percentage, 0-100).
 */
const val GRAPH_METRIC_VALUE3 = "value3"
/** Calories — sum of meal calories for the day (meal habits only). */
const val GRAPH_METRIC_CALORIES = "calories"
/** Protein in grams (meal habits only). */
const val GRAPH_METRIC_PROTEIN = "protein"
/** Carbs in grams (meal habits only). */
const val GRAPH_METRIC_CARBS = "carbs"
/** Fat in grams (meal habits only). */
const val GRAPH_METRIC_FAT = "fat"
/**
 * IMDb average rating — the average IMDb rating of all movies/episodes watched
 * that day (stored as rating × 10 in the secondary-value slot, e.g. 8.8 → 88).
 * Only available for movie-type habits linked to the bridge with an OMDb API key.
 */
const val GRAPH_METRIC_IMDB = "imdb"
/**
 * Runtime minutes — the total watch-minutes of all movies/episodes watched that
 * day, summed from the "(N min)" annotations in the habit's text entries.
 * Available for movie-type habits linked to the bridge (no OMDb API key needed).
 */
const val GRAPH_METRIC_RUNTIME = "runtime"

// ── GitHub graph metrics (GitHub-type habits only) ───────────────────────────
/** Total lines changed = additions + deletions per day (GitHub habits only). */
const val GRAPH_METRIC_GITHUB_LINES = "github_lines"
/** Number of commits per day (GitHub habits only). */
const val GRAPH_METRIC_GITHUB_COMMITS = "github_commits"
/** Lines added per day (GitHub habits only). */
const val GRAPH_METRIC_GITHUB_ADDITIONS = "github_additions"
/** Lines deleted per day (GitHub habits only). */
const val GRAPH_METRIC_GITHUB_DELETIONS = "github_deletions"

/**
 * A selectable graph metric option shown as a toggle button.
 * [key] is persisted in [AppSettings.graphMetricSelection].
 */
data class GraphMetricOption(
    val key: String,
    val label: String
)

/**
 * A named screen (page) of habits. Each screen has a unique id, a display name,
 * and an ordered list of habit names that appear on it.
 */
data class HabitScreen(
    val id: String,
    val name: String,
    val habitNames: List<String>
)

/**
 * App settings stored in DataStore.
 */
data class AppSettings(
    /** SAF URI for habitsdb.txt — the single unified habit database shared with the PC. */
    val fileUri: String = "",
    /**
     * SAF URI for the screens_layout.json relay file shared with the PC widget.
     * When set, the app writes the current screen layout to this file whenever
     * screens are created, renamed, reordered, or habits are moved between screens.
     * The PC widget reads this file to mirror the same multi-screen layout.
     */
    val screensRelayFileUri: String = "",
    /**
     * SAF URI for the Tasker relay txt file.
     * When set, the app writes three lines to this file after every habit count change:
     *   today=<N>          — total habits done today (count > 0)
     *   avg7=<X.XX>        — average habits done per day over the last 7 days
     *   avg30=<X.XX>       — average habits done per day over the last 30 days
     */
    val taskerFileUri: String = "",
    val customInputHabits: Set<String> = DEFAULT_CUSTOM_INPUT_HABITS,
    /** Custom display order for habits (legacy flat list, used when screens is empty). */
    val habitOrder: List<String> = emptyList(),
    /**
     * Named screens of habits. When non-empty, the app shows one screen at a time
     * and the flat [habitOrder] is ignored. The first screen is always "general" by default.
     */
    val habitScreens: List<HabitScreen> = emptyList(),
    /** Index of the currently active screen (persisted so the app reopens on the same screen). */
    val activeScreenIndex: Int = 0,

    /**
     * Habits that have the "1 max" feature enabled.
     * When a habit is in this set, its daily count is capped at 1 — tapping it
     * when already at 1 has no effect (binary done/not-done behaviour).
     */
    val maxOneHabits: Set<String> = emptySet(),

    /**
     * Habits that have the "text input" feature enabled.
     * When a habit is in this set, tapping it shows a text-entry popup instead of
     * (or in addition to) incrementing the numeric count.
     */
    val textInputHabits: Set<String> = emptySet(),

    /**
     * Habits that have the "show options" sub-feature enabled.
     * Only meaningful when the habit is also in [textInputHabits].
     * When enabled, the text-entry popup also shows a list of all unique past entries
     * so the user can pick one instead of typing from scratch.
     */
    val textInputOptionsHabits: Set<String> = emptySet(),

    /**
     * Text-input habits that are "sharable" via the Android system share sheet.
     * Only meaningful when the habit is also in [textInputHabits].
     * When a habit is in this set, ShareTextActivity (the "Share → tail" target)
     * lists it as a destination: sharing selected text from anywhere on the phone
     * saves it as a timestamped entry in the habit's text log and increments
     * the habit count by 1.
     */
    val sharableTextHabits: Set<String> = emptySet(),

    /**
     * Maps habit name → SAF URI string for the per-habit text-log JSON file.
     * Format of that file: { "2023-07-07 10:00:17": "some text", ... }
     */
    val textInputFileUris: Map<String, String> = emptyMap(),

    /**
     * Maps habit name → icon name (without .png extension) for custom icon overrides.
     * When a habit is in this map, its icon is shown from the named drawable instead of
     * the default HABIT_ICON mapping.
     */
    val habitIcons: Map<String, String> = emptyMap(),

    /**
     * Habits that have the "Dated Entry" feature enabled.
     * When a habit is in this set, its count for each day is automatically derived
     * by parsing a linked plain-text file that contains date headers and paragraph blocks.
     * Each blank-line-separated paragraph under a date counts as +1 for that day.
     */
    val datedEntryHabits: Set<String> = emptySet(),

    /**
     * Maps habit name → SAF URI string for the per-habit dated-entry source file.
     * The file uses date headers (M/D/YY or YYYY-MM-DD) followed by paragraph blocks.
     */
    val datedEntryFileUris: Map<String, String> = emptyMap(),

    /**
     * Maps habit name → last-seen file size (bytes) for the dated-entry source file.
     * Used to detect changes efficiently: if the size hasn't changed since the last
     * sync we skip re-parsing entirely.
     *
     * When the file only grows (new entries appended), we use this as a seek offset:
     * we re-read from [lastOverlapBytes] before the old size to catch any date header
     * that straddles the boundary, then parse only the new tail. This keeps parse time
     * O(new content) rather than O(total file size) as the file grows.
     */
    val datedEntryFileSizes: Map<String, Long> = emptyMap(),

    /**
     * Maps habit name → divisor value for the "divider" feature.
     * When a habit is in this map with a value > 1, the raw stored count is divided
     * by that value (rounded to nearest int) to produce the displayed points value.
     * The raw count is always stored unchanged in the database.
     */
    val habitDividers: Map<String, Int> = emptyMap(),

    /**
     * Habits that have the "conditional" type enabled.
     * When a habit is in this set, tapping it also auto-increments all habits
     * listed in [conditionalLinkedHabits] for that habit.
     */
    val conditionalHabits: Set<String> = emptySet(),

    /**
     * Maps a conditional habit name → the set of other habit names that should be
     * auto-incremented whenever the conditional habit is tapped.
     */
    val conditionalLinkedHabits: Map<String, Set<String>> = emptyMap(),

    /** Habits that have the "subtyped" feature enabled. */
    val subtypedHabits: Set<String> = emptySet(),

    /**
     * Maps habit name → ordered list of subtype names.
     * The first subtype is the "default" subtype.
     */
    val habitSubtypes: Map<String, List<String>> = emptyMap(),

    /**
     * Maps habit name → SAF URI string for the per-habit subtype data JSON file.
     * Format of that file: { "2026-01-15": { "chinups": 5, "wide": 3 }, ... }
     */
    val subtypeDataFileUris: Map<String, String> = emptyMap(),

    /** Habits that have the "timed" feature enabled.
     *  When a timed habit is incremented, a timestamped session entry is also
     *  appended to its timed data JSON file so individual sessions can be tracked. */
    val timedHabits: Set<String> = emptySet(),

    /**
     * Maps habit name → SAF URI string for the per-habit timed data JSON file.
     * Format: { "2026-01-18 11:45:06": { "subtype": "chinups", "count": 5 }, ... }
     */
    val timedDataFileUris: Map<String, String> = emptyMap(),

    /**
     * Habits that have the "timeless" feature enabled.
     * When a timeless habit is incremented, no timestamp is recorded by default
     * (the increment is recorded as timeless). The user can still edit the time
     * via the toast's "Edit Time" button to add a timestamp retroactively.
     */
    val timelessHabits: Set<String> = emptySet(),

    /**
     * Set of screen IDs that are "hidden". A hidden screen's name is not shown
     * in the top tab bar when it is not the active screen. When selected, it
     * shows its name normally in the active tab bubble.
     */
    val hiddenScreens: Set<String> = emptySet(),

    /**
     * Habits that are "disabled". A disabled habit shows a red ✕ overlay on its
     * icon square and its current streak / anti-streak do NOT affect aggregate
     * totals in the stats screen. The anti-streak continues to grow even while
     * disabled. Disabling only means the habit can't be tapped and doesn't
     * count toward stats.
     */
    val disabledHabits: Set<String> = emptySet(),

    /**
     * Habits that don't affect point totals.
     * When a habit is in this set, it can still be incremented and tracked,
     * but its points are NOT included in any totals (daily totals, averages,
     * streaks, ATH stats, etc.). Useful for tracking metrics (like Garmin data)
     * without them affecting your overall habit count.
     */
    val noPointsHabits: Set<String> = emptySet(),

    /**
     * Habits that have the "Secondary Value" feature enabled.
     * When a habit is in this set, it can store a second integer value per day
     * (stored in habitsdb.txt under the key "secondary_value:<habitName>").
     * The primary value remains the normal stored count; the secondary value
     * is accessible via the graph screen's "Value2" button.
     *
     * Example use-case: Meditation habit where primary = minutes,
     * secondary = session count.
     */
    val secondaryValueHabits: Set<String> = emptySet(),

    /**
     * Habits that use the secondary value as a **fallback for points** when
     * the primary value is zero.
     *
     * When a habit is in this set AND in [secondaryValueHabits], the points
     * calculation falls back to the secondary value (Value2) on days where the
     * primary value (Value1) is zero or missing.  The fallback points are set
     * to the raw secondary value (no divider applied).
     *
     * Example use-case: Apnea habit where primary = minutes, secondary = sessions.
     * On days with no minutes recorded but sessions > 0, the session count is
     * used for points so the habit still counts toward streaks and totals.
     */
    val secondaryValueFallbackHabits: Set<String> = emptySet(),

    /**
     * **Display-only** custom labels for a habit's value/subtype columns.
     *
     * Outer key = habit name, inner key = the backend value identifier
     * (e.g. `"value1"`, `"value2"` for secondary-value habits, or a subtype
     * name for subtyped habits), inner value = the label the user wants to see
     * in the UI.
     *
     * This is purely a presentation overlay — the backend keys, the subtype-data
     * JSON files, the ContentProvider, and all external integrations continue to
     * use the original identifiers.  See [displayLabelForValue].
     */
    val valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),

    // ── AI Icon Generation settings ──────────────────────────────────────
    /** Whether AI icon generation is enabled (user must opt in via Settings). */
    val aiIconsEnabled: Boolean = false,
    /** API key for the image generation service. */
    val aiIconsApiKey: String = "",
    /** Base URL for the image generation API (e.g. "https://api.openai.com"). */
    val aiIconsBaseUrl: String = "",
    /** Endpoint path appended to the base URL (e.g. "/v1/images/generations"). */
    val aiIconsEndpoint: String = "",
    /** Model name to pass to the API (e.g. "dall-e-3"). */
    val aiIconsModel: String = "",
    /** Quality tier for the selected model (e.g. "standard", "1k", "medium"). */
    val aiIconsQuality: String = "",

    // ── Chess.com Integration settings ───────────────────────────────────
    /** Whether chess.com integration is enabled. */
    val chessComEnabled: Boolean = false,
    /** The user's chess.com username. */
    val chessComUsername: String = "",
    /**
     * Maps habit name → ChessComType.name for habits linked to chess.com data.
     * When a habit is in this map, its daily values are auto-set from chess.com data:
     * games → primary count, minutes → `secondary_value:` slot, wins →
     * `secondary_value2:` slot. Points are derived via the habit's divider setting.
     */
    val chessComHabitLinks: Map<String, String> = emptyMap(),

    // ── GitHub Integration settings ────────────────────────────────────────
    /**
     * Whether GitHub integration is enabled (global toggle in Settings).
     * When enabled, the per-habit edit panel shows a GitHub section where the
     * user can link a habit to a public GitHub repository.
     */
    val githubEnabled: Boolean = false,
    /**
     * Optional GitHub Personal Access Token (classic or fine-grained).
     * When set, raises the API rate limit from 60 to 5 000 requests/hour.
     * Only needs public_repo (or no scopes for public repos) read access.
     */
    val githubToken: String = "",
    /**
     * Maps habit name → public GitHub repository URL for habits linked to
     * GitHub data.  When a habit is in this map, its daily count is auto-set
     * from the repository's commit activity.
     *
     * Example value: "https://github.com/torvalds/linux"
     */
    val githubRepoUrls: Map<String, String> = emptyMap(),
    /**
     * Maps habit name → GitHubMetric.name for the metric to track.
     * Defaults to LINES_CHANGED when absent.
     */
    val githubMetrics: Map<String, String> = emptyMap(),

    // ── Voice Trigger settings ────────────────────────────────────────────
    /** Global on/off for voice trigger feature (must be enabled in Settings). */
    val voiceTriggerEnabled: Boolean = false,
    /** Habits that have voice trigger enabled (per-habit toggle in edit mode). */
    val voiceTriggerHabits: Set<String> = emptySet(),
    /**
     * Maps habit name → set of trigger words (stored lowercase).
     * When the VoiceHabitService hears speech containing any of these words,
     * the corresponding habit is incremented.
     */
    val voiceTriggerWords: Map<String, Set<String>> = emptyMap(),

    /**
     * Maps habit name → fixed increment amount for voice commands.
     * When a voice trigger fires and no spoken number is detected, this value
     * is used instead of the default of 1. For example, set to 500 for a
     * water habit measured in millilitres.
     * Absent or 0 means default to 1.
     */
    val voiceTriggerIncrements: Map<String, Int> = emptyMap(),

    /**
     * Habits that have the "use subtypes voice" feature enabled.
     * Only meaningful when the habit is also in [voiceTriggerHabits] AND [subtypedHabits].
     * When enabled, the voice service also checks for subtype names in the spoken text
     * after the trigger word, and parses an optional number for the increment amount.
     * If no subtype is heard, the first subtype is used as default.
     * If no number is heard, the amount defaults to 1.
     */
    val voiceSubtypeHabits: Set<String> = emptySet(),

    // ── Voice Note Dictation settings ─────────────────────────────────────
    /** Global on/off for voice note dictation feature. */
    val voiceNoteEnabled: Boolean = false,
    /** SAF URI for the notes markdown file to prepend dictated notes to. */
    val voiceNoteFileUri: String = "",

    // ── Custom input increment amounts ────────────────────────────────────
    /**
     * Maps habit name → ordered list of quick-increment button amounts.
     * When absent, the default [DEFAULT_CUSTOM_INPUT_AMOUNTS] is used.
     */
    val customInputAmounts: Map<String, List<Int>> = emptyMap(),

    /**
     * Maps habit name → list of the most recently used increment amounts (up to 3).
     * Most recent first. Used to show "recent" quick-add buttons in the IncrementDialog.
     */
    val customInputRecentAmounts: Map<String, List<Int>> = emptyMap(),

    // ── Automatic daily backup settings ───────────────────────────────────
    /**
     * SAF tree URI for the folder where automatic daily backups are written.
     * When non-empty, on the FIRST app launch of each calendar day (and before
     * any habit DB read/write) the app exports a full backup bundle to
     * `<folder>/tail_auto_backup_YYYY-MM-DD.json`. Old backups remain until
     * the user deletes them manually via Settings. Empty = feature disabled
     * (no folder picked yet).
     *
     * This was added in response to a near-total database wipe incident where
     * Syncthing-related conditions caused a transient load failure followed
     * by an empty-skeleton overwrite. See ADR / README for details.
     */
    val autoBackupFolderUri: String = "",
    /**
     * ISO date string ("YYYY-MM-DD") of the most recent successful automatic
     * backup. Compared against [java.time.LocalDate.now] on launch to decide
     * whether to run a fresh backup. Empty = never backed up.
     */
    val autoBackupLastDate: String = "",

    // ── Map screen stats settings ────────────────────────────────────────
    /**
     * Habits selected to show their daily values in the map screen's stats panel.
     * The user picks these from the map settings dialog.
     */
    val mapStatsHabits: Set<String> = emptySet(),

    /**
     * Text-input habits whose text entries should be shown in the map stats panel.
     * Only meaningful for habits that are also in [mapStatsHabits] AND [textInputHabits].
     */
    val mapStatsShowTextHabits: Set<String> = emptySet(),

    // ── Garmin Integration settings ────────────────────────────────────────
    /** Whether Garmin integration is enabled. */
    val garminEnabled: Boolean = false,
    /** URL of the Garmin proxy API (e.g., "https://your-proxy.onrender.com"). */
    val garminProxyUrl: String = "",
    /** Authentication token for the Garmin proxy API. */
    val garminAppToken: String = "",
    /**
     * Maps habit name → GarminType.name for habits linked to Garmin data.
     * When a habit is in this map, its daily count is auto-set from Garmin data.
     */
    val garminHabitLinks: Map<String, String> = emptyMap(),
    /**
     * User's date of birth in ISO format (YYYY-MM-DD).
     * Used to calculate biological age for fitness age distance calculations.
     */
    val garminDateOfBirth: String = "",

    // ── Tail Bridge settings (PC↔Phone communication protocol) ──────────────
    /**
     * Whether the Tail Bridge integration is enabled.
     * The bridge is a desktop server that tethers data (movies, future sources)
     * to the phone. See tail_bridge/ in the project root.
     */
    val bridgeEnabled: Boolean = false,
    /** Base URL of the Tail Bridge server (e.g., "http://192.168.1.100:8001"). */
    val bridgeUrl: String = "",
    /** Authentication token for the bridge (X-App-Auth header). */
    val bridgeToken: String = "",
    /**
     * Text-input habits that are linked to the movie bridge.
     * When such a habit is tapped, the app fetches the latest watched movie
     * from the desktop and pre-fills the text entry for confirmation/editing.
     */
    val bridgeMovieHabits: Set<String> = emptySet(),

    // ── OMDb / IMDb ratings settings ──────────────────────────────────────
    /**
     * API key for the OMDb API (https://omdbapi.com/).
     * When set, the app fetches IMDb ratings for movie/episode entries and
     * stores the daily average as a secondary value (see [secondaryValueHabits]).
     * The key is entered by the user in the Bridge settings section.
     */
    val omdbApiKey: String = "",

    // ── Custom Point Ranges settings ────────────────────────────────────────
    /**
     * Habits that have custom point ranges enabled.
     * When enabled, the habit's points are calculated based on which range
     * the "true value" or "garmin value" falls into, rather than using the
     * standard divider or raw count.
     */
    val customPointRangesHabits: Set<String> = emptySet(),
    /**
     * Maps habit name → list of 7 point ranges (indices 0-6).
     * Each range has a min and max value (inclusive).
     * The "true value" or "garmin value" is checked against each range in order,
     * and the index of the first matching range becomes the point value.
     * Ranges can overlap; the first match wins.
     */
    val customPointRanges: Map<String, List<PointRange>> = emptyMap(),

    /**
     * Maps habit name → graph value mode:
     *   0 = points (default)
     *   1 = Value1 (raw value / true value / garmin value)
     *   2 = Value2 (secondary value, only for habits in [secondaryValueHabits])
     * When absent or 0, points are shown.
     *
     * **Deprecated** in favour of [graphMetricSelection] which supports selecting
     * multiple metrics simultaneously. Kept for backward-compatibility migration.
     */
    val graphValueModeHabits: Map<String, Int> = emptyMap(),

    /**
     * Multi-select graph metrics per habit.
     *
     * Maps habit name → set of metric keys (see [GRAPH_METRIC_POINTS] etc.).
     * Multiple metrics can be selected at once, each rendered as a separate line.
     * When absent for a habit, the default is `{ [GRAPH_METRIC_POINTS] }`.
     *
     * Meal habits additionally support [GRAPH_METRIC_CALORIES], [GRAPH_METRIC_PROTEIN],
     * [GRAPH_METRIC_CARBS], [GRAPH_METRIC_FAT].
     */
    val graphMetricSelection: Map<String, Set<String>> = emptyMap(),

    // ── Map Settings ────────────────────────────────────────────────────────
    /**
     * The habit name that determines map dot coloring. When set, the dots on the map
     * are colored based on this habit's daily value for each location. When null,
     * the monthly average of all points is used instead.
     */
    val mapMainHabit: String? = null,
    /**
     * When true, days where the main habit value is 0 (or monthly average is 0 if
     * no main habit is set) will not show a dot on the map.
     */
    val mapHideZeroDays: Boolean = false,
    /**
     * Custom begin date for the map timeline in ISO format (YYYY-MM-DD).
     * When empty, the map shows from the earliest location or habit date.
     * When set, the map timeline starts from this date instead.
     */
    val mapBeginDate: String = "",

    /**
     * Maps habit name → note text for per-habit notes.
     * Users can write notes about each habit in edit mode.
     */
    val habitNotes: Map<String, String> = emptyMap(),

    /**
     * Habits that have the "roll forward" feature enabled.
     * When a habit is in this set, two behaviors apply:
     * 1. When a new day begins, this habit is automatically set to the value it had on the previous day.
     * 2. When set in past days, it automatically sets all days after that to the same value up until
     *    the next day that was manually set. This creates "segments" of consistent values.
     */
    val rollForwardHabits: Set<String> = emptySet(),

    /**
     * Tracks which dates were manually set for roll forward habits.
     * Maps habit name → set of date strings (YYYY-MM-DD) that were explicitly set by the user.
     * This distinguishes manually set dates from dates that were automatically filled by roll forward.
     */
    val rollForwardManualDates: Map<String, Set<String>> = emptyMap(),

    // ── Meal Habit Engine settings ───────────────────────────────────────
    /** Whether the Meal habit engine (vision pipeline) is globally enabled. */
    val mealEnabled: Boolean = false,
    /** Base URL for the multimodal LLM API (e.g. "https://api.openai.com/v1"). */
    val mealBaseUrl: String = "",
    /** API key (Bearer token) for the LLM endpoint. */
    val mealApiKey: String = "",
    /** Model name to use for vision inference (e.g. "gpt-4o"). */
    val mealModel: String = "",
    /** User-defined custom system prompt / dietary rules merged into every vision call. */
    val mealSystemPrompt: String = "",
    /** Habits that have the "Meal" type enabled. */
    val mealHabits: Set<String> = emptySet(),

    // ── App Link settings ──────────────────────────────────────────────────
    /**
     * Maps app-link key → app display label.
     * The key is the full prefixed name stored in [HabitScreen.habitNames]
     * (e.g. `"app_link:com.example.app"`). The value is the human-readable
     * app name shown in the UI (e.g. `"Settings"`).
     * App links occupy grid cells but are NOT incrementable habits — tapping
     * one launches the corresponding app via [android.content.Intent.ACTION_MAIN].
     */
    val appLinks: Map<String, String> = emptyMap(),

    // ── Habit App Association settings ──────────────────────────────────────
    /**
     * Maps habit name → ordered list of package names associated with that habit.
     * When a habit has one or more associated apps, long-pressing it in the grid
     * launches the app directly (if only one) or shows a picker (if multiple).
     * A blue "↗" indicator is shown on the habit button to signal the association.
     * Unlike [appLinks], these are not separate grid cells — they augment
     * existing habits.
     */
    val habitAppAssociations: Map<String, List<String>> = emptyMap(),

    // ── Long-press action settings ──────────────────────────────────────────
    /**
     * Maps habit name → long-press action string (one of [LONG_PRESS_APP],
     * [LONG_PRESS_URL], [LONG_PRESS_CAMERA], [LONG_PRESS_DETAILS]).
     * Habits not present in this map default to [LONG_PRESS_APP].
     */
    val habitLongPressActions: Map<String, String> = emptyMap(),

    /**
     * Maps habit name → URL opened when the habit's long-press action is
     * [LONG_PRESS_URL]. Habits without an entry (or with a blank URL) fall
     * back to the default app-launch behaviour.
     */
    val habitLongPressUrls: Map<String, String> = emptyMap(),

    /**
     * Maps habit name → package name of the app that should handle the
     * [LONG_PRESS_URL] link (via Intent.setPackage). Habits without an entry
     * open the URL in the default browser; if the chosen app can't handle
     * the link, the browser is used as a fallback.
     */
    val habitLongPressUrlApps: Map<String, String> = emptyMap(),

    // ── Widget Trigger settings ─────────────────────────────────────────────
    /**
     * Habits that have the "Use Widget" feature enabled.
     * When a habit is in this set, the floating bubble widget automatically
     * appears whenever the associated trigger app (see [widgetTriggerApps])
     * is in the foreground, and disappears when it leaves.
     */
    val widgetTriggerHabits: Set<String> = emptySet(),

    /**
     * Maps habit name → package name of the app that triggers the floating bubble.
     * When the app identified by this package name comes to the foreground,
     * [com.example.tail.widget.FloatingBubbleService] is started.
     * When it leaves the foreground, the bubble is stopped.
     * Only meaningful for habits in [widgetTriggerHabits].
     */
    val widgetTriggerApps: Map<String, String> = emptyMap(),

    // ── Chess Readiness settings ───────────────────────────────────────────
    /**
     * Whether the Chess Readiness feature is enabled (global toggle in the
     * widget section of Settings). When enabled together with
     * [chessReadinessApp], the floating bubble also appears over that app and
     * its popup menu gains a "Chess Readiness" option that launches the
     * Phase 1 Pre-Session Diagnostic flow.
     */
    val chessReadinessEnabled: Boolean = false,

    /**
     * Package name of the app associated with Chess Readiness (typically the
     * chess app). The floating bubble appears over this app and offers the
     * readiness diagnostic. Only meaningful when [chessReadinessEnabled].
     */
    val chessReadinessApp: String = "",

    /**
     * Widget-timer habits where MINUTES is the primary value.
     *
     * For habits in this set, the timer minutes (stored in the secondary-value
     * slot `secondary_value:<habitName>`) are treated as the PRIMARY value for
     * points/display, and the raw session count becomes the fallback (used for
     * points only on days where minutes are zero). Habits with a widget trigger
     * but NOT in this set keep the standard behaviour: sessions primary,
     * minutes as the fallback secondary value.
     *
     * Defaults to "minutes primary" when a trigger app is first configured;
     * the user can swap this per habit in edit mode.
     */
    val widgetTimerMinutesPrimary: Set<String> = emptySet(),

    // ── Google Drive backup settings ─────────────────────────────────────
    /** Whether the automatic daily Google Drive backup is enabled. */
    val gdriveAutoEnabled: Boolean = false,
    /** Account name (e-mail) of the signed-in Google account used for Drive backups. */
    val gdriveAccountName: String = "",
    /** ISO date ("YYYY-MM-DD") of the most recent successful Drive auto-backup. */
    val gdriveLastBackupDate: String = ""
)

/** Default quick-increment amounts shown in the IncrementDialog when no custom amounts are set. */
val DEFAULT_CUSTOM_INPUT_AMOUNTS: List<Int> = listOf(1, 5, 10, 30, 50)

val DEFAULT_CUSTOM_INPUT_HABITS: Set<String> = setOf(
    "Pushups",
    "Situps",
    "Squats",
    "Cold Shower Widget",
    "Sweat"
)

/**
 * The canonical ordered list of 76 habits matching the desktop app exactly.
 */
/**
 * Habits in row-major order for Android's LazyVerticalGrid (left-to-right, top-to-bottom).
 * The desktop app uses column-major order (top-to-bottom per column), so this list is
 * transposed from the original desktop order to produce the same visual layout.
 * Desktop: 8 cols × 10 rows, col-major. Android: same grid, row-major.
 * Transformation: Android position (row, col) → desktop index = col*10 + row.
 */
val HABIT_ORDER: List<String> = listOf(
    // Row 1
    "Juggle lights", "Joggle", "Blind juggle", "Juggling Balls Carry",
    "Juggling Others Learn", "Most Collisions", "No Coffee", "Tracked Sleep",
    // Row 2
    "Unique juggle", "Create juggle", "Song juggle", "Move juggle",
    "Juggle run", "Free", "Magic practiced", "Magic performed",
    // Row 3
    "Juggling record broke", "Fun juggle", "Janki used", "Filmed juggle",
    "Watch juggle", "Inspired juggle", "Juggle goal", "Balanced",
    // Row 4
    "Dream acted", "Drm Review", "Lucidity trained", "Unusual experience",
    "Meditations", "Kind stranger", "Broke record", "Grumpy blocker",
    // Row 5
    "Sleep watch", "Early phone", "Anki created", "Anki mydis done",
    "Some anki", "Health learned", "Took pills", "Flossed",
    // Row 6
    "Apnea walked", "Apnea practiced", "Apnea apb", "Apnea spb",
    "Lung stretch", "Sweat", "Fasted", "Todos done",
    // Row 7
    "Cold Shower Widget", "Squats", "Situps", "Pushups",
    "Cardio sessions", "Good posture", "HIT", "Fresh air",
    // Row 8
    "Programming sessions", "Juggling tech sessions", "Writing sessions", "UC post",
    "AI tool", "Drew", "Question asked", "Talk stranger",
    // Row 9
    "Book read", "Podcast finished", "Educational video watched", "Article read",
    "Read academic", "Language studied", "Music listen", "Memory practice",
    // Row 10
    "Fiction Book Intake", "Fiction Video Intake", "Chess", "Rabbit Hole",
    "Speak AI", "Communication Improved", "Unusually Kind"
)
