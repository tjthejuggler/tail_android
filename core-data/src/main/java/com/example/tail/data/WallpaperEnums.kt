package com.example.tail.data

/**
 * Which point statistic drives the wallpaper choice.
 *
 * All three values come from [computeTaskerStats] — the single source of
 * truth for today / avg7 / avg30 point totals (same numbers as the stats
 * overlay and the old Tasker relay).
 *
 * Moved from ui-independent wallpaper package into data on 2026-09-07 so
 * AppSettings / SettingsRepository can reference it without a reverse
 * dependency on the wallpaper package.
 */
enum class WallpaperMetric(val label: String) {
    TODAY("Today's points"),
    WEEKLY("7-day average"),
    MONTHLY("30-day average");

    companion object {
        /** Decodes a persisted enum name, falling back to TODAY. */
        fun fromName(raw: String?): WallpaperMetric =
            entries.firstOrNull { it.name == raw } ?: TODAY
    }

    /** Extracts this metric's value from the computed stats. */
    fun select(stats: TaskerStats): Double = when (this) {
        TODAY -> stats.today.toDouble()
        WEEKLY -> stats.avg7
        MONTHLY -> stats.avg30
    }
}

/** Which wallpaper surface(s) the image is applied to. */
enum class WallpaperTarget(val label: String) {
    SYSTEM("Home screen"),
    LOCK("Lock screen"),
    BOTH("Both");

    companion object {
        /** Decodes a persisted enum name, falling back to SYSTEM. */
        fun fromName(raw: String?): WallpaperTarget =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}
