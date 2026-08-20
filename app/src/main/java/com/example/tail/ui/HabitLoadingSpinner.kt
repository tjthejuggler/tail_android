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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  HABIT LOADING ANIMATION — "THE ORRERY II: PATRONAGE & GRANDEUR"
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
 * ── PATRONAGE — no metric animates alone ─────────────────────────────
 *
 *  The quality of the OTHER two numbers upgrades each layer through
 *  three patronage ranks, derived from the SUM of the supporting tiers
 *  (0–12):  STRANGER (0–3) · ALLY (4–7) · PATRON (8–12).
 *
 *  A boosted layer is drawn brighter — its own colour is lerped toward
 *  white — and grows extra embellishments (trails, sparkles, companion
 *  bodies, halos) tinted with the SUPPORTERS' colours. An orange weekly
 *  halo championed by a yellow month and a yellow day is a far richer
 *  orange than one burning alone in an empty sky. A red ember with two
 *  mighty patrons becomes a well-fed fire; alone, it is a sad flicker.
 *
 * ── GRANDEUR — the whole is more than the sum of its parts ──────────
 *
 *  The sum of all three tiers (0–18) drives the global spectacle. The
 *  canvas itself GROWS with grandeur (72 dp → ~190 dp), and milestone
 *  thresholds unlock cross-cutting flourishes behind/around the three
 *  personal layers:
 *
 *      6+   nebula        — a deep-space glow blending all three colours
 *      10+  starfield     — twinkling stars scattered across the void
 *      13+  corona        — a slow rotating ring of arcs beyond the halo
 *      16+  shooting stars— comets streak across the whole canvas
 *      17+  spectrum crown— a full-spectrum ring circling everything
 *      18   TOTALITY      — the perfect 6/6/6: white shockwave pulses
 *
 *  7 tiers × 3 patronage ranks = 21 variants per layer, 63 core
 *  combinations of form — plus resonance ripples when all three tiers
 *  align, plus the six grandeur flourishes. No two streaks are alike.
 *
 * The renderers live in [HabitLoadingLayers.kt] (shared primitives and
 * global flourishes), [HabitLoadingMonthly.kt], [HabitLoadingWeekly.kt]
 * and [HabitLoadingDaily.kt].
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
 * Maps the combined tier sum of a layer's two supporters (0–12) to a
 * patronage rank:
 *
 *   0 · STRANGER — the layer burns alone            (support sum 0–3)
 *   1 · ALLY     — one strong companion lends aid   (support sum 4–7)
 *   2 · PATRON   — championed by mighty neighbours  (support sum 8–12)
 */
fun patronageFrom(supportSum: Int): Int = when {
    supportSum >= 8 -> 2
    supportSum >= 4 -> 1
    else            -> 0
}

/**
 * Per-metric tier triple resolved from [LoadingMetrics]. Averages are
 * rounded before tiering, matching the map's accent-colour behaviour.
 *
 * Derived properties encode the synergy system:
 *  - [grandeur] — total spectacle (0–18), drives size + global flourishes.
 *  - [monthPatronage] / [weekPatronage] / [dayPatronage] — how strongly
 *    the other two metrics upgrade each layer's animation (0–2).
 */
data class LoadingTiers(val monthly: Int, val weekly: Int, val daily: Int) {
    /** True when all three metrics sit on the same non-zero tier. */
    val resonant: Boolean
        get() = monthly == weekly && weekly == daily && monthly > 0

    /** Sum of all three tiers (0–18) — the global spectacle budget. */
    val grandeur: Int
        get() = monthly + weekly + daily

    /** The weekly + daily tiers lend their strength to the monthly core. */
    val monthPatronage: Int
        get() = patronageFrom(weekly + daily)

    /** The monthly + daily tiers lend their strength to the weekly halo. */
    val weekPatronage: Int
        get() = patronageFrom(monthly + daily)

    /** The monthly + weekly tiers lend their strength to the daily spark. */
    val dayPatronage: Int
        get() = patronageFrom(monthly + weekly)
}

/** Resolves the tier triple for a [LoadingMetrics] bundle. */
fun loadingTiers(m: LoadingMetrics): LoadingTiers = LoadingTiers(
    monthly = habitPointsTier(m.monthlyAverage.roundToInt()),
    weekly = habitPointsTier(m.weeklyAverage.roundToInt()),
    daily = habitPointsTier(m.todayPoints)
)

/** Grandeur thresholds at which each global flourish unlocks. */
internal object GrandeurThresholds {
    const val NEBULA = 6
    const val STARFIELD = 10
    const val CORONA = 13
    const val SHOOTING_STARS = 16
    const val SPECTRUM_CROWN = 17
    const val TOTALITY = 18
}

/**
 * The tiered loading animation ("The Orrery II").
 *
 * @param monthlyAverage 30-day average daily points — primary form & colour.
 * @param weeklyAverage  7-day average daily points — halo form & colour.
 * @param todayPoints    today's total points — central spark colour.
 * @param modifier       standard compose modifier.
 * @param size           BASE diameter at zero grandeur; the animation grows
 *                       beyond this (up to ~2.6×) as grandeur rises.
 */
@Composable
fun HabitLoadingSpinner(
    monthlyAverage: Double,
    weeklyAverage: Double,
    todayPoints: Int,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    // Freeze the tiers for the whole lifetime of this composition — i.e. for
    // one continuous loading session. Mid-load metric emissions (fresh
    // averages arriving while the DB streams in) must NOT morph the colours,
    // form or canvas size mid-spin: that looked like the animation
    // "restarting". A fresh load session re-enters composition and picks up
    // the then-current metrics.
    val tiers = remember {
        val frozen = loadingTiers(LoadingMetrics(monthlyAverage, weeklyAverage, todayPoints))
        android.util.Log.d(
            "Orrery",
            "tiers m=${frozen.monthly} w=${frozen.weekly} d=${frozen.daily} " +
                "grandeur=${frozen.grandeur} raw(m=$monthlyAverage w=$weeklyAverage t=$todayPoints)"
        )
        frozen
    }
    val monthColor = tierAccent(tiers.monthly)
    val weekColor = tierAccent(tiers.weekly)
    val dayColor = tierAccent(tiers.daily)

    // The canvas itself swells with grandeur — a slow ease-in so mid
    // tiers stay modest and the summit feels earned (72dp → ~190dp).
    val growth = 1f + 1.65f * Math.pow((tiers.grandeur / 18f).toDouble(), 1.25).toFloat()

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
    // Fast shimmer for twinkles, flickers and stardust.
    val phase4 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase4"
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
    // Slow, deep breath for the grandest glows and totality pulses.
    val breathe2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe2"
    )

    Canvas(modifier = modifier.size(size * growth)) {
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
            phase4 = phase4,
            breathe = breathe,
            breathe2 = breathe2
        )
        val g = tiers.grandeur

        // ── Global flourishes (behind) ─────────────────────────────────
        if (g >= GrandeurThresholds.NEBULA) drawNebula(ctx)
        if (g >= GrandeurThresholds.STARFIELD) drawStarfield(ctx)
        if (g >= GrandeurThresholds.CORONA) drawCorona(ctx)

        // ── The three personal layers ──────────────────────────────────
        drawWeeklyHalo(ctx)   // outer orbital system, in the weekly colour
        drawMonthlyCore(ctx)  // the central archetype, in the monthly colour
        drawDailySpark(ctx)   // the small central accent, in the daily colour

        // ── Rewards ────────────────────────────────────────────────────
        if (tiers.resonant) drawResonance(ctx)

        // ── Global flourishes (in front) ───────────────────────────────
        if (g >= GrandeurThresholds.SHOOTING_STARS) drawShootingStars(ctx)
        if (g >= GrandeurThresholds.SPECTRUM_CROWN) drawSpectrumCrown(ctx)
        if (g >= GrandeurThresholds.TOTALITY) drawTotality(ctx)
    }
}
