package com.example.tail.ui

import android.graphics.Color

/**
 * The app-wide point-total colour ranking (the "number colour ranking system").
 *
 * Thirteen tiers — the original ladder the calendar heatmap and the map
 * accents use, extended past white with WHITE+COLOUR combo tiers (mirroring
 * the habit square's Glass + border phases):
 *
 *   1-13 red · 14-20 orange · 21-30 green · 31-41 blue · 42-48 pink ·
 *   49-55 yellow · 56-62 white · 63-69 white/red · 70-76 white/orange ·
 *   77-83 white/green · 84-90 white/blue · 91-97 white/pink · 98+ white/yellow.
 *
 * The combo-tier widths follow the 7-point cadence of the tiers immediately
 * before white (pink 42-48, yellow 49-55).
 *
 * Shared so the View-based stats overlay (which needs plain ARGB ints) and the
 * Compose UI colour identical numbers identically. [CalendarPickerDialog] uses
 * [TIERS] for its heatmap fade; the stats overlay uses [textArgbForPoints] to
 * colour its today / avg7 / avg30 numbers, plus [accentArgbForPoints] for the
 * thin coloured outline the white+colour tiers wear.
 */
object PointTierColors {

    data class Tier(
        val lo: Int,
        val hi: Int,
        val r: Int, val g: Int, val b: Int,   // target colour at full intensity
        /** Vivid combo hue (ARGB) for the white+colour tiers; 0 = plain tier. */
        val accent: Int = 0
    )

    val TIERS: List<Tier> = listOf(
        Tier( 1, 13,  180,  30,  30),                                          // red
        Tier(14, 20,  210, 110,  20),                                          // orange
        Tier(21, 30,   30, 180,  60),                                          // green
        Tier(31, 41,   40, 100, 220),                                          // blue
        Tier(42, 48,  200,  60, 180),                                          // pink
        Tier(49, 55,  210, 190,  30),                                          // yellow
        Tier(56, 62,  230, 230, 230),                                          // white
        Tier(63, 69,  255, 225, 222, accent = 0xFFCC3333.toInt()),             // white/red
        Tier(70, 76,  255, 228, 205, accent = 0xFFE07020.toInt()),             // white/orange
        Tier(77, 83,  222, 255, 228, accent = 0xFF33AA55.toInt()),             // white/green
        Tier(84, 90,  222, 235, 255, accent = 0xFF3366DD.toInt()),             // white/blue
        Tier(91, 97,  255, 222, 242, accent = 0xFFDD44AA.toInt()),             // white/pink
        Tier(98, Int.MAX_VALUE, 255, 250, 210, accent = 0xFFDDCC00.toInt())    // white/yellow
    )

    fun tierFor(points: Int): Tier =
        TIERS.firstOrNull { points <= it.hi } ?: TIERS.last()

    /**
     * Text FILL colour (ARGB) for a point total. `brightness` is the stats
     * overlay's colour-purity slider (0..1):
     *  - 1.0 → the PUREST possible form of the tier's hue: saturation 1 and
     *    value 1 (red → exactly 255,0,0; green → 0,255,0; …).
     *  - lower → progressively duller: desaturated toward grey and slightly
     *    dimmed, so the numbers fade but stay readable on a dark background.
     *
     * The achromatic white tier (56-62) has no hue to purify — its pure end is
     * pure white and its dull end is grey. The white+colour combo tiers
     * (63+) behave the same way: the FILL stays white and the tier's hue is
     * carried by [accentArgbForPoints] (the thin outline / accent), so the
     * combo always reads as "white numbers wearing a coloured edge".
     * Zero/negative totals get a neutral grey.
     */
    fun textArgbForPoints(points: Int, brightness: Float = 1f): Int {
        if (points <= 0) return 0xFF9E9E9E.toInt()
        val tier = tierFor(points)
        val range = (tier.hi - tier.lo).coerceAtLeast(1)
        val t = ((points - tier.lo).toFloat() / range).coerceIn(0f, 1f)

        val purity = brightness.coerceIn(0f, 1f)
        val hsv = floatArrayOf(0f, 0f, 0f)
        if (tier.accent != 0) {
            // White+colour combo tier: white fill, no hue purification —
            // the vivid hue belongs to the accent, not the fill.
            hsv[1] = 0f
            val dullV = 0.78f + 0.10f * t
            hsv[2] = dullV + (1f - dullV) * purity
            return Color.HSVToColor(255, hsv)
        }
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

    /**
     * The vivid combo hue (ARGB) for a point total — the coloured half of the
     * white+colour tiers (63+). Returns 0 (transparent) for every plain tier,
     * so callers can simply skip the accent when it is 0. The stats overlay
     * draws it as a thin outline around the white numbers; the loading
     * animation's high-tier renderers use their own palette instead.
     */
    fun accentArgbForPoints(points: Int): Int =
        if (points <= 0) 0 else tierFor(points).accent
}
