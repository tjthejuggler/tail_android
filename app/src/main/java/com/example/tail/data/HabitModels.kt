package com.example.tail.data

/**
 * All-time high for a rolling window: the peak average value and the date it occurred.
 */
data class RollingHigh(
    val value: Double,   // the peak rolling average
    val date: String     // "YYYY-MM-DD" of the peak
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
    val habitDividers: Map<String, Int> = emptyMap()
)

val DEFAULT_CUSTOM_INPUT_HABITS: Set<String> = setOf(
    "Launch Pushups Widget",
    "Launch Situps Widget",
    "Launch Squats Widget",
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
    "Cold Shower Widget", "Launch Squats Widget", "Launch Situps Widget", "Launch Pushups Widget",
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
