package com.example.tail.data.debug

/**
 * Maps a navigation route to human-readable context that helps a programmer LLM
 * find the right source files quickly. Each entry provides the screen label,
 * the primary source file, and key functions/composables in that file.
 */
object ScreenContextMapper {

    data class ScreenContext(
        val label: String,
        val sourceFile: String,
        val sourceFunctions: String
    )

    private val routeMap: Map<String, ScreenContext> = mapOf(
        "grid" to ScreenContext(
            "Habit Grid", "ui/HabitGridScreen.kt", "HabitGridScreen, HabitViewModel"
        ),
        "settings" to ScreenContext(
            "Settings", "ui/SettingsScreen.kt", "SettingsScreen, HabitViewModel"
        ),
        "app_stats" to ScreenContext(
            "App Stats", "ui/AppStatsScreen.kt", "AppStatsScreen, HabitViewModel"
        )
    )

    /**
     * Resolves a navigation route string to a [ScreenContext].
     * Falls back to a generic entry for unknown routes.
     */
    fun resolve(route: String?): ScreenContext {
        if (route == null) return ScreenContext("Unknown", "MainActivity.kt", "MainActivity")

        return routeMap[route]
            ?: ScreenContext(route, "Unknown", "Unknown")
    }
}
