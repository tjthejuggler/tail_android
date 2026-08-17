package com.example.tail.ui

import android.graphics.Color

/**
 * The app-wide point-total colour ranking (the "number colour ranking system").
 *
 * Seven tiers — the same ladder the calendar heatmap and the map accents use:
 * 1-13 red · 14-20 orange · 21-30 green · 31-41 blue ·
 * 42-48 pink · 49-55 yellow · 56+ white.
 *
 * Shared so the View-based stats overlay (which needs plain ARGB ints) and the
 * Compose UI colour identical numbers identically. [CalendarPickerDialog] uses
 * [TIERS] for its heatmap fade; the stats overlay uses [textArgbForPoints] to
 * colour its today / avg7 / avg30 numbers.
 */
object PointTierColors {

    data class Tier(
        val lo: Int,
        val hi: Int,
        val r: Int, val g: Int, val b: Int   // target colour at full intensity
    )

    val TIERS: List<Tier> = listOf(
        Tier( 1, 13,  180,  30,  30),   // red
        Tier(14, 20,  210, 110,  20),   // orange
        Tier(21, 30,   30, 180,  60),   // green
        Tier(31, 41,   40, 100, 220),   // blue
        Tier(42, 48,  200,  60, 180),   // pink
        Tier(49, 55,  210, 190,  30),   // yellow
        Tier(56, Int.MAX_VALUE, 230, 230, 230)  // white
    )

    fun tierFor(points: Int): Tier =
        TIERS.firstOrNull { points <= it.hi } ?: TIERS.last()

    /**
     * Text colour (ARGB) for a point total. `brightness` is the stats
     * overlay's colour-purity slider (0..1):
     *  - 1.0 → the PUREST possible form of the tier's hue: saturation 1 and
     *    value 1 (red → exactly 255,0,0; green → 0,255,0; …).
     *  - lower → progressively duller: desaturated toward grey and slightly
     *    dimmed, so the numbers fade but stay readable on a dark background.
     *
     * The achromatic white tier (56+) has no hue to purify — its pure end is
     * pure white and its dull end is grey. Zero/negative totals get a
     * neutral grey.
     */
    fun textArgbForPoints(points: Int, brightness: Float = 1f): Int {
        if (points <= 0) return 0xFF9E9E9E.toInt()
        val tier = tierFor(points)
        val range = (tier.hi - tier.lo).coerceAtLeast(1)
        val t = ((points - tier.lo).toFloat() / range).coerceIn(0f, 1f)

        val purity = brightness.coerceIn(0f, 1f)
        val hsv = floatArrayOf(0f, 0f, 0f)
        Color.RGBToHSV(tier.r, tier.g, tier.b, hsv)
        val achromatic = hsv[1] < 0.10f   // white tier — no hue to purify

        // Saturation rides the slider: 1.0 = fully saturated pure hue.
        hsv[1] = if (achromatic) hsv[1] else purity

        // Value: the dull end keeps a readable grey (with the subtle
        // within-tier ramp so rank still reads); the pure end is exactly 1.0,
        // so max slider = the mathematically pure colour (255,0,0 for red).
        val dullV = 0.62f + 0.08f * t
        hsv[2] = dullV + (1f - dullV) * purity

        return Color.HSVToColor(255, hsv)
    }
}
