package com.example.tail.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
 *      Supernova → Phoenix → Magnetar → Aurora Heart → Galactic Core)
 *      and its primary colour. This is the soul of the piece.
 *
 *  WEEKLY average (7d) — SECONDARY. Grows an orbital halo around the
 *      core: rings, tilted ellipses and satellite swarms, all in the
 *      weekly tier colour.
 *
 *  TODAY's points — MINOR. A central spark (dot → pulse → sparkle cross →
 *      micro-moons → ripples → … → Star of Dawn) in the daily tier colour.
 *
 * ── THE THIRTEEN-TIER LADDER ─────────────────────────────────────────
 *
 *  0 red · 1 orange · 2 green · 3 blue · 4 pink · 5 yellow · 6 white ·
 *  7 white/red · 8 white/orange · 9 white/green · 10 white/blue ·
 *  11 white/pink · 12 white/yellow.
 *
 *  Tiers 7+ are the WHITE+COLOUR combos: the layer's body burns in a
 *  near-white glass tint while its vivid combo hue ([tierComboAccent])
 *  paints the embellishments — the same white-with-coloured-edge language
 *  as the stats overlay's outlined numbers and the habit square's
 *  Glass + border phases. Realistically the monthly/weekly averages top
 *  out around tier 10 (white/blue, 84+ avg points); the daily spark can
 *  climb all the way to tier 12 (white/yellow, 98+ points in one day).
 *
 * ── PATRONAGE — no metric animates alone ─────────────────────────────
 *
 *  The quality of the OTHER two numbers upgrades each layer through
 *  four patronage ranks, derived from the SUM of the supporting tiers
 *  (0–24):  STRANGER (0–3) · ALLY (4–7) · PATRON (8–13) ·
 *  CHAMPION (14+ — only reachable once supporters reach combo tiers).
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
 *  The sum of all three tiers (0–36) drives the global spectacle. The
 *  canvas itself GROWS with grandeur (72 dp → ~240 dp), and milestone
 *  thresholds unlock cross-cutting flourishes behind/around the three
 *  personal layers:
 *
 *      6+   nebula        — a deep-space glow blending all three colours
 *      10+  starfield     — twinkling stars scattered across the void
 *      13+  corona        — a slow rotating ring of arcs beyond the halo
 *      16+  shooting stars— comets streak across the whole canvas
 *      17+  spectrum crown— a full-spectrum ring circling everything
 *      18   TOTALITY      — the perfect 6/6/6: white shockwave pulses
 *      ── the transcendent range (combo tiers) ──
 *      19+  aurora        — curtains of light wave behind everything
 *      22+  constellation — chained stars linked across the sky
 *      25+  polar jets    — beams erupt from the poles of the orrery
 *      28+  halo of halos — orbiting mini spectrum crowns circle the rim
 *      32   TRANSCENDENCE — the reachable summit (10/10/12): prismatic
 *                           shockwaves and a blazing white heart
 *
 *  The step into the transcendent range is deliberately grander than
 *  anything below it — crossing from white into white/red (grandeur 19)
 *  awakens the aurora, and every threshold after adds a whole new
 *  celestial phenomenon, not just a richer version of an old one.
 *
 *  13 tiers × 4 patronage ranks = 52 variants per layer, 156 core
 *  combinations of form — plus resonance ripples when all three tiers
 *  align, plus the eleven grandeur flourishes. No two streaks are alike.
 *
 * The renderers live in [HabitLoadingLayers.kt] (shared primitives and
 * global flourishes), [HabitLoadingMonthly.kt], [HabitLoadingWeekly.kt]
 * and [HabitLoadingDaily.kt].
 *
 * FRAME PRODUCTION: every frame is drawn on a dedicated render thread into a
 * GPU canvas on a SurfaceView surface, composited directly by SurfaceFlinger
 * (see [HabitLoadingThreaded.kt] / [OrreryRenderView]). The main thread is
 * never involved in producing OR displaying frames, so database streaming,
 * DataStore reads, WorkManager and GC pauses cannot stutter the spin.
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

/** Maps a point total to its 0-based tier index (0 = red … 12 = white/yellow).
 *  Boundaries match [PointTierColors.TIERS] exactly. */
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

/**
 * The body colour for a tier index. Plain tiers (0–6) use the vivid
 * Border* palette; the white+colour combo tiers (7–12) use their near-white
 * glass tint, so the layer's body reads as WHITE — the vivid half of the
 * combo comes from [tierComboAccent].
 */
internal fun tierAccent(tier: Int): Color = when (tier) {
    12 -> BorderWhiteYellow
    11 -> BorderWhitePink
    10 -> BorderWhiteBlue
    9  -> BorderWhiteGreen
    8  -> BorderWhiteOrange
    7  -> BorderWhiteRed
    6 -> BorderGlass
    5 -> BorderYellow
    4 -> BorderPink
    3 -> BorderBlue
    2 -> BorderGreen
    1 -> BorderOrange
    else -> BorderRed
}

/**
 * The vivid combo hue for a tier index — the coloured half of the
 * white+colour tiers (7–12). Plain tiers have no second hue, so their
 * "combo" is simply their own accent colour.
 */
internal fun tierComboAccent(tier: Int): Color = when (tier) {
    12 -> BorderYellow
    11 -> BorderPink
    10 -> BorderBlue
    9  -> BorderGreen
    8  -> BorderOrange
    7  -> BorderRed
    else -> tierAccent(tier)
}

/**
 * Maps the combined tier sum of a layer's two supporters (0–24) to a
 * patronage rank:
 *
 *   0 · STRANGER — the layer burns alone            (support sum 0–3)
 *   1 · ALLY     — one strong companion lends aid   (support sum 4–7)
 *   2 · PATRON   — championed by mighty neighbours  (support sum 8–13)
 *   3 · CHAMPION — carried by transcendent company  (support sum 14+,
 *                  only reachable when supporters reach combo tiers)
 */
fun patronageFrom(supportSum: Int): Int = when {
    supportSum >= 14 -> 3
    supportSum >= 8  -> 2
    supportSum >= 4  -> 1
    else             -> 0
}

/**
 * Per-metric tier triple resolved from [LoadingMetrics]. Averages are
 * rounded before tiering, matching the map's accent-colour behaviour.
 *
 * Derived properties encode the synergy system:
 *  - [grandeur] — total spectacle (0–36), drives size + global flourishes.
 *  - [monthPatronage] / [weekPatronage] / [dayPatronage] — how strongly
 *    the other two metrics upgrade each layer's animation (0–3).
 */
data class LoadingTiers(val monthly: Int, val weekly: Int, val daily: Int) {
    /** True when all three metrics sit on the same non-zero tier. */
    val resonant: Boolean
        get() = monthly == weekly && weekly == daily && monthly > 0

    /** Sum of all three tiers (0–36) — the global spectacle budget. */
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

/** Grandeur thresholds at which each global flourish unlocks.
 *  6–18: the classical range (unchanged). 19+: the transcendent range,
 *  unlocked only when metrics reach the white+colour combo tiers. */
internal object GrandeurThresholds {
    const val NEBULA = 6
    const val STARFIELD = 10
    const val CORONA = 13
    const val SHOOTING_STARS = 16
    const val SPECTRUM_CROWN = 17
    const val TOTALITY = 18
    const val AURORA = 19
    const val CONSTELLATION = 22
    const val POLAR_JETS = 25
    const val HALO_OF_HALOS = 28
    const val TRANSCENDENCE = 32
}

/**
 * The tiered loading animation ("The Orrery II").
 *
 * RENDERING MODEL — "ALWAYS SMOOTH, NO MATTER WHAT": frames are produced on
 * a dedicated render thread ([OrreryRenderView], driven by a background
 * Choreographer drawing into a GPU canvas on a SurfaceView surface) and are
 * composited directly by SurfaceFlinger — completely decoupled from the
 * main thread for BOTH production and display. Database streaming, DataStore
 * reads, WorkManager and GC pauses can stall the UI thread all they like —
 * the orrery keeps playing at vsync rate. The previous Compose-Canvas
 * implementation produced every frame on the UI thread and stuttered
 * whenever background loading work ran.
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

    // The canvas itself swells with grandeur — a slow ease-in so mid
    // tiers stay modest and the summit feels earned. The classical range
    // (0–18) is unchanged (72dp → ~191dp); the transcendent range adds a
    // second swell up to ~3.35× (72dp → ~241dp) at grandeur 32+.
    val gClassical = tiers.grandeur.coerceAtMost(18)
    val gTranscendent = (tiers.grandeur - 18).coerceAtLeast(0)
    val growth = 1f +
        1.65f * Math.pow((gClassical / 18f).toDouble(), 1.25).toFloat() +
        0.70f * Math.pow((gTranscendent / 14f).toDouble(), 1.15).toFloat()

    // Tiers are frozen for the session, so the view footprint in dp is
    // constant too: the TextureView is sized once and the render thread
    // never needs main-thread layout information again.
    val densityPxPerDp = LocalDensity.current.density

    AndroidView(
        modifier = modifier.size(size * growth),
        factory = { context -> OrreryRenderView(context) },
        update = { view -> view.configure(tiers, densityPxPerDp) }
    )
}
