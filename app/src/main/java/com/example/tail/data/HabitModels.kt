package com.example.tail.data

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
     * Minutes per increment for each chess.com game type.
     * Key is ChessComType.name (BULLET, BLITZ, RAPID).
     * Value is the number of minutes of that activity that equals 1 habit increment.
     * 0 means disabled for that type.
     */
    val chessComMinutesPerIncrement: Map<String, Int> = emptyMap(),
    /**
     * Maps habit name → ChessComType.name for habits linked to chess.com data.
     * When a habit is in this map, its daily count is auto-set from chess.com data.
     */
    val chessComHabitLinks: Map<String, String> = emptyMap(),

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
     * Threshold values for each Garmin metric type.
     * Key is GarminType.name (VO2_MAX, FITNESS_AGE, etc.).
     * Value is the threshold that must be met or exceeded to count as 1 habit increment.
     * 0 means disabled for that type.
     */
    val garminThresholds: Map<String, Int> = emptyMap(),
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
    val customPointRanges: Map<String, List<PointRange>> = emptyMap()
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
