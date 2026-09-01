package com.example.tail.data

/**
 * Pure, ViewModel-free calculation of a habit's effective points for a date
 * and of a day's total points.
 *
 * Extracted verbatim from HabitViewModel.effectivePointsForDate so the
 * launcher-icon tier switcher (LauncherIconTierManager) — which runs in
 * processes/paths where no ViewModel exists (widget taps, IPC broadcasts,
 * voice increments with the app closed) — computes EXACTLY the same numbers
 * as the in-app spinner. The ViewModel delegates to these functions; do not
 * fork the logic.
 */
object DailyPointsCalculator {

    /**
     * Effective points for [habitName] on [dateStr], applying dividers,
     * inverted-binary rules, minutes-primary rules and the secondary-value
     * fallback — identical to the in-app computation.
     */
    fun effectivePointsForDate(
        habitName: String,
        rawCount: Int,
        dateStr: String,
        db: HabitsDatabase,
        settings: AppSettings
    ): Int {
        val divider = settings.habitDividers[habitName] ?: 1
        // Inverted-binary habits: 1 point on not-done days, 0 on done days —
        // but never before the habit's first recorded entry.
        if (habitName in settings.invertedBinaryHabits) {
            val firstDataDate = db[habitName]
                ?.filterValues { it != 0 }?.keys?.minOrNull()
            if (firstDataDate == null || dateStr < firstDataDate) return 0
            return invertedBinaryPoints(rawCount)
        }
        if (habitName in settings.widgetTimerMinutesPrimary) {
            val minutes = db[minutesKey(habitName)]?.get(dateStr) ?: 0
            return when (
                settings.minutesPrimaryFallbacks[habitName]
                    ?: MINUTES_PRIMARY_FALLBACK_SESSIONS
            ) {
                MINUTES_PRIMARY_FALLBACK_NONE ->
                    applyDivider(minutes, divider)
                MINUTES_PRIMARY_FALLBACK_VALUE2 -> {
                    val v2 = db[secondaryValueKey(habitName)]?.get(dateStr) ?: 0
                    effectivePointsWithFallback(minutes, divider, v2, true)
                }
                else -> effectivePointsWithFallback(minutes, divider, rawCount, true)
            }
        }
        val useFallback = habitName in settings.secondaryValueFallbackHabits
        if (!useFallback) return applyDivider(rawCount, divider)
        val fallbackKey = fallbackSlotKey(
            habitName, settings.secondaryValueHabits, db
        )
        val secVal = db[fallbackKey]?.get(dateStr) ?: 0
        return effectivePointsWithFallback(rawCount, divider, secVal, true)
    }

    /**
     * Total effective points earned on [dateStr] — the number whose tier
     * drives the daily colour (habitPointsTier). Mirrors the day-0 loop of
     * HabitViewModel.getLoadingMetrics: every DB habit except no-points
     * habits and internal value-storage keys.
     */
    fun totalPointsForDate(
        dateStr: String,
        db: HabitsDatabase,
        settings: AppSettings
    ): Int {
        val noPoints = settings.noPointsHabits
        val tracked = db.keys.filter { it !in noPoints && !isInternalValueKey(it) }
        var total = 0
        for (name in tracked) {
            val raw = db[name]?.get(dateStr) ?: 0
            val pts = effectivePointsForDate(name, raw, dateStr, db, settings)
            if (pts > 0) total += pts
        }
        return total
    }
}
