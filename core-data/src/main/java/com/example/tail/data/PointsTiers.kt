package com.example.tail.data

/**
 * Maps a point total to its 0-based tier index (0 = red … 12 = white/yellow).
 * Boundaries match PointTierColors.TIERS in the UI exactly.
 *
 * Lives in the data layer (moved from ui/HabitLoadingSpinner.kt on 2026-09-07)
 * so DailyPointsCalculator and the tier-bar widget can use it without a
 * reverse dependency on the UI package.
 */
fun habitPointsTier(points: Int): Int = when {
    points >= 98 -> 12
    points >= 91 -> 11
    points >= 84 -> 10
    points >= 77 -> 9
    points >= 70 -> 8
    points >= 63 -> 7
    points >= 56 -> 6
    points >= 49 -> 5
    points >= 42 -> 4
    points >= 31 -> 3
    points >= 21 -> 2
    points >= 14 -> 1
    else         -> 0
}
