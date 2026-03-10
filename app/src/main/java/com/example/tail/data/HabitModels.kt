package com.example.tail.data

/**
 * Represents a single habit with all computed stats for display.
 */
data class Habit(
    val name: String,
    val todayCount: Int = 0,
    val currentStreak: Int = 0,       // positive = streak, negative = antistreak
    val longestStreak: Int = 0,
    val allTimeHighDay: Int = 0,
    val useCustomInput: Boolean = false
)

/**
 * Raw database format matching habitsdb_phone.txt:
 * { "Habit Name": { "2026-01-05": 1, "2026-01-06": 0 } }
 */
typealias HabitsDatabase = Map<String, Map<String, Int>>

/**
 * App settings stored in DataStore.
 */
data class AppSettings(
    val fileUri: String = "",
    val historicalFileUri: String = "",
    val customInputHabits: Set<String> = DEFAULT_CUSTOM_INPUT_HABITS
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
