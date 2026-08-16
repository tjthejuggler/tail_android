package com.example.tail.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  HABIT LOADING ANIMATION — "THE ORRERY"
 * ═══════════════════════════════════════════════════════════════════════
 *
 * A generative loading animation whose form, colour and complexity are
 * composed from THREE habit metrics, each owning its own artistic layer:
 *
 *  MONTHLY average (30d) — PRIMARY. Selects the core animation archetype
 *      (Ember → Twin Flames → Comet → Atom → Rose Window → Binary Suns →
 *      Supernova) and its primary colour. This is the soul of the piece.
 *
 *  WEEKLY average (7d) — SECONDARY. Grows an orbital halo around the
 *      core: rings, tilted ellipses and satellite swarms, all in the
 *      weekly tier colour.
 *
 *  TODAY's points — MINOR. A central spark (dot → pulse → sparkle cross →
 *      micro-moons → ripples) in the daily tier colour.
 *
 * Every metric climbs the same 7 colour tiers used across the app
 * (see [habitPointsTier]); each tier-up visibly adds or transforms elements
 * in that metric's layer, so there is always something new to unlock.
 * 7 × 7 × 7 = 343 distinct combinations — plus a hidden "resonance"
 * flourish (expanding ripples) when all three tiers align.
 *
 * The renderers for each layer live in [HabitLoadingLayers.kt].
 */

/**
 * The three habit metrics that drive the loading animation.
 *
 * @param monthlyAverage average daily points over the 30-day window ending today.
 * @param weeklyAverage  average daily points over the 7-day window ending today.
 * @param todayPoints    total effective points earned today.
 */
data class LoadingMetrics(
    val monthlyAverage: Double,
    val weeklyAverage: Double,
    val todayPoints: Int
)

/** Maps a point total to its 0-based tier index (0 = red … 6 = white). */
fun habitPointsTier(points: Int): Int = when {
    points >= 56 -> 6
    points >= 49 -> 5
    points >= 42 -> 4
    points >= 31 -> 3
    points >= 21 -> 2
    points >= 14 -> 1
    else         -> 0
}

/** The vivid accent colour for a tier index (matches the Border* palette). */
internal fun tierAccent(tier: Int): Color = when (tier) {
    6 -> BorderGlass
    5 -> BorderYellow
    4 -> BorderPink
    3 -> BorderBlue
    2 -> BorderGreen
    1 -> BorderOrange
    else -> BorderRed
}

/**
 * Per-metric tier triple resolved from [LoadingMetrics]. Averages are
 * rounded before tiering, matching the map's accent-colour behaviour.
 */
data class LoadingTiers(val monthly: Int, val weekly: Int, val daily: Int) {
    /**
     * True when all three metrics sit on the same non-zero tier — the
     * animation then adds a slow "resonance" ripple as a reward for
     * balanced, consistent performance.
     */
    val resonant: Boolean
        get() = monthly == weekly && weekly == daily && monthly > 0
}

/** Resolves the tier triple for a [LoadingMetrics] bundle. */
fun loadingTiers(m: LoadingMetrics): LoadingTiers = LoadingTiers(
    monthly = habitPointsTier(m.monthlyAverage.roundToInt()),
    weekly = habitPointsTier(m.weeklyAverage.roundToInt()),
    daily = habitPointsTier(m.todayPoints)
)

/**
 * The tiered loading animation ("The Orrery").
 *
 * @param monthlyAverage 30-day average daily points — primary form & colour.
 * @param weeklyAverage  7-day average daily points — halo form & colour.
 * @param todayPoints    today's total points — central spark colour.
 * @param size           overall diameter of the animation.
 */
@Composable
fun HabitLoadingSpinner(
    monthlyAverage: Double,
    weeklyAverage: Double,
    todayPoints: Int,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val tiers = loadingTiers(LoadingMetrics(monthlyAverage, weeklyAverage, todayPoints))
    val monthColor = tierAccent(tiers.monthly)
    val weekColor = tierAccent(tiers.weekly)
    val dayColor = tierAccent(tiers.daily)

    val transition = rememberInfiniteTransition(label = "habitSpinner")

    // Continuous 0→1 phase, one full revolution per 1400ms — core rotation.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    // Slower secondary phase for counter-rotation, satellites and shimmer.
    val phase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )
    // Very slow precession for halo rotation and sparkle-cross drift.
    val phase3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase3"
    )
    // Breathing 0→1→0 for pulses and glows.
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    Canvas(modifier = modifier.size(size)) {
        val ctx = LoadingPaintContext(
            c = center,
            radius = this.size.minDimension / 2f,
            tiers = tiers,
            monthColor = monthColor,
            weekColor = weekColor,
            dayColor = dayColor,
            phase = phase,
            phase2 = phase2,
            phase3 = phase3,
            breathe = breathe
        )

        // Deep-space backdrop glow — appears from monthly tier 4 upward and
        // blends the monthly and weekly colours.
        if (tiers.monthly >= 4) drawBackdropGlow(ctx)

        // Weekly halo — the outer orbital system, in the weekly colour.
        drawWeeklyHalo(ctx)

        // Monthly core — the archetype, in the monthly colour.
        drawMonthlyCore(ctx)

        // Daily spark — the small central accent, in the daily colour.
        drawDailySpark(ctx)

        // Resonance flourish when all three tiers align.
        if (tiers.resonant) drawResonance(ctx)
    }
}
