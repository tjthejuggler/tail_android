package com.example.tail.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY II — SHARED PRIMITIVES & GLOBAL FLOURISHES
 * ═══════════════════════════════════════════════════════════════════════
 *
 * This file holds the drawing vocabulary shared by every layer renderer
 * (arcs, orbits, comets, twinkles, stars, spirals, patronage tints) plus
 * the GRANDEUR flourishes that celebrate the combined might of all three
 * metrics — nebula, starfield, corona, shooting stars, spectrum crown
 * and, at the perfect 6/6/6, Totality itself. Beyond grandeur 18 the
 * TRANSCENDENT flourishes awaken: aurora curtains, constellations,
 * polar jets, the halo of halos and, at the reachable summit (10/10/12),
 * Transcendence itself.
 *
 * The three personal layers live in the sibling files:
 *   [HabitLoadingMonthly.kt] · [HabitLoadingWeekly.kt] · [HabitLoadingDaily.kt]
 */

/** Immutable bundle of everything the layer renderers need for one frame. */
internal class LoadingPaintContext(
    val c: Offset,
    val radius: Float,
    val tiers: LoadingTiers,
    val monthColor: Color,
    val weekColor: Color,
    val dayColor: Color,
    /** The vivid combo hues — equal to the body colours on plain tiers,
     *  the coloured half of the white+colour tiers (7+). */
    val monthCombo: Color,
    val weekCombo: Color,
    val dayCombo: Color,
    val phase: Float,
    val phase2: Float,
    val phase3: Float,
    val phase4: Float,
    val breathe: Float,
    val breathe2: Float
)

// ─────────────────────────── shared primitives ───────────────────────────

/**
 * A patron-boosted layer burns brighter: its own colour is lerped toward
 * white by its patronage rank, so a PATRON orange is visibly richer than
 * a STRANGER orange while remaining unmistakably orange. A CHAMPION's
 * layer burns brightest of all.
 */
internal fun patronTint(base: Color, patron: Int): Color = when (patron) {
    3 -> lerp(base, Color.White, 0.28f)
    2 -> lerp(base, Color.White, 0.18f)
    1 -> lerp(base, Color.White, 0.08f)
    else -> base
}

/** Draws a stroked arc on the circle of radius [r] around [c]. Angles in degrees. */
internal fun DrawScope.ringArc(
    color: Color,
    c: Offset,
    r: Float,
    startAngle: Float,
    sweepAngle: Float,
    stroke: Float,
    cap: StrokeCap = StrokeCap.Round
) {
    val d = r * 2f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(c.x - r, c.y - r),
        size = Size(d, d),
        style = Stroke(width = stroke, cap = cap)
    )
}

/** Point on the circle of radius [r] around [c] at [angleDeg] (0° = 3 o'clock). */
internal fun orbitPoint(c: Offset, r: Float, angleDeg: Float): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    return Offset((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
}

/** Point on a tilted ellipse (semi-axes [rx]/[ry], rotation [tiltDeg]) around [c]. */
internal fun ellipsePoint(c: Offset, rx: Float, ry: Float, tiltDeg: Float, thetaDeg: Float): Offset {
    val t = Math.toRadians(thetaDeg.toDouble())
    val x = rx * cos(t)
    val y = ry * sin(t)
    val tr = Math.toRadians(tiltDeg.toDouble())
    val xr = x * cos(tr) - y * sin(tr)
    val yr = x * sin(tr) + y * cos(tr)
    return Offset((c.x + xr).toFloat(), (c.y + yr).toFloat())
}

/** Filled dot — the particle primitive used everywhere. */
internal fun DrawScope.dot(color: Color, center: Offset, radius: Float) =
    drawCircle(color = color, radius = radius, center = center)

/**
 * A comet tail trailing [tailSweep] degrees behind a head at [headAngle],
 * rendered as [segments] arc slices that shrink and fade towards the tip.
 */
internal fun DrawScope.cometTail(
    color: Color,
    c: Offset,
    r: Float,
    headAngle: Float,
    tailSweep: Float,
    stroke: Float,
    segments: Int = 6
) {
    val seg = tailSweep / segments
    for (j in 1..segments) {
        val fade = 1f - (j - 1).toFloat() / segments   // 1 at the head → 0 at the tip
        ringArc(
            color = color.copy(alpha = 0.55f * fade * fade),
            c = c,
            r = r,
            startAngle = headAngle - j * seg,
            sweepAngle = seg * 0.9f,
            stroke = stroke * (0.30f + 0.70f * fade)
        )
    }
}

/** 0→1 twinkle wave with per-particle [seed] phase offset. */
internal fun twinkle(phase: Float, seed: Float): Float =
    abs(sin(phase * 2.0 * Math.PI + seed)).toFloat()

/** Deterministic 0→1 hash — stable pseudo-randomness for starfields & debris. */
internal fun hash01(seed: Float): Float {
    val x = sin(seed * 127.1f + 311.7f) * 43758.5453f
    return x - kotlin.math.floor(x)
}

/** A four-point sparkle — two thin crossing lines with a bright heart. */
internal fun DrawScope.star(color: Color, center: Offset, r: Float) {
    val w = (r * 0.22f).coerceAtLeast(0.6f)
    drawLine(color, Offset(center.x - r, center.y), Offset(center.x + r, center.y), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(center.x, center.y - r), Offset(center.x, center.y + r), strokeWidth = w, cap = StrokeCap.Round)
    val rd = r * 0.7f
    drawLine(color.copy(alpha = 0.5f), Offset(center.x - rd, center.y - rd), Offset(center.x + rd, center.y + rd), strokeWidth = w * 0.7f, cap = StrokeCap.Round)
    drawLine(color.copy(alpha = 0.5f), Offset(center.x - rd, center.y + rd), Offset(center.x + rd, center.y - rd), strokeWidth = w * 0.7f, cap = StrokeCap.Round)
    dot(color, center, r * 0.18f)
}

/**
 * A logarithmic spiral arm from [r0] to [r1], starting at [startDeg] and
 * sweeping [sweepDeg] — the galaxy-mode flourish of the mightiest halos.
 */
internal fun DrawScope.spiralArm(
    color: Color,
    c: Offset,
    r0: Float,
    r1: Float,
    startDeg: Float,
    sweepDeg: Float,
    width: Float
) {
    val path = Path()
    val steps = 36
    val growth = (r1 / r0).toDouble()
    for (j in 0..steps) {
        val t = j / steps.toFloat()
        val r = (r0 * Math.pow(growth, t.toDouble())).toFloat()
        val p = orbitPoint(c, r, startDeg + sweepDeg * t)
        if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

// ─────────────────────────── grandeur: nebula ────────────────────────────

/**
 * A deep-space glow breathing behind everything else, blending the three
 * metric colours. From grandeur 6 the combined light of the orrery is
 * strong enough to illuminate the void; from grandeur 12 a second, slower
 * layer joins in the day's own colour.
 */
internal fun DrawScope.drawNebula(ctx: LoadingPaintContext) {
    val blend = lerp(lerp(ctx.monthColor, ctx.weekColor, 0.5f), ctx.dayColor, 0.25f)
    val r = ctx.radius * (0.72f + 0.20f * ctx.breathe)
    drawCircle(
        brush = Brush.radialGradient(
            0f to blend.copy(alpha = 0.10f + 0.07f * ctx.breathe),
            1f to Color.Transparent,
            center = ctx.c,
            radius = r
        ),
        radius = r,
        center = ctx.c
    )
    if (ctx.tiers.grandeur >= 12) {
        val r2 = ctx.radius * (0.92f + 0.06f * ctx.breathe2)
        drawCircle(
            brush = Brush.radialGradient(
                0f to ctx.dayColor.copy(alpha = 0.05f + 0.05f * ctx.breathe2),
                0.6f to ctx.monthColor.copy(alpha = 0.04f),
                1f to Color.Transparent,
                center = ctx.c,
                radius = r2
            ),
            radius = r2,
            center = ctx.c
        )
    }
    if (ctx.tiers.grandeur >= 24) {
        // The transcendent nebula: the combo hues bleed together into a
        // third, vast shell — the sky itself is lit by the achievement.
        val r3 = ctx.radius * (1.02f + 0.05f * ctx.breathe)
        drawCircle(
            brush = Brush.radialGradient(
                0f to ctx.dayCombo.copy(alpha = 0.05f + 0.04f * ctx.breathe),
                0.5f to lerp(ctx.monthCombo, ctx.weekCombo, 0.5f).copy(alpha = 0.05f),
                1f to Color.Transparent,
                center = ctx.c,
                radius = r3
            ),
            radius = r3,
            center = ctx.c
        )
    }
}

// ─────────────────────────── grandeur: starfield ─────────────────────────

/**
 * A scatter of twinkling stars across the whole canvas (grandeur ≥ 10).
 * Positions and hues are hashed deterministically — every frame shows the
 * same constellation, one third white, the rest tinted by the three
 * metric colours, as if the orrery had set the very sky alight.
 */
internal fun DrawScope.drawStarfield(ctx: LoadingPaintContext) {
    val cap = if (ctx.tiers.grandeur >= GrandeurThresholds.AURORA) 36 else 22
    val count = (6 + (ctx.tiers.grandeur - GrandeurThresholds.STARFIELD) * 2).coerceAtMost(cap)
    val tints = listOf(ctx.monthColor, ctx.weekColor, ctx.dayColor)
    for (i in 0 until count) {
        val h1 = hash01(i * 1.37f + 0.1f)
        val h2 = hash01(i * 7.91f + 0.4f)
        val r = ctx.radius * (0.30f + 0.67f * h1)
        val p = orbitPoint(ctx.c, r, h2 * 360f)
        val tw = twinkle(ctx.phase4, i * 1.7f)
        val col = if (i % 3 == 0) Color.White else tints[i % 3]
        val size = ctx.radius * (0.006f + 0.008f * h1)
        if (i % 4 == 0) {
            star(col.copy(alpha = 0.25f + 0.55f * tw), p, size * 3.2f)
        } else {
            dot(col.copy(alpha = 0.15f + 0.55f * tw), p, size)
        }
    }
}

// ─────────────────────────── grandeur: corona ────────────────────────────

/**
 * A ring of rotating arcs just beyond the halo (grandeur ≥ 13), as though
 * the whole orrery were crowned by a solar corona. Two counter-drifting
 * arc sets interfere into a slow breathing lattice; from grandeur 15,
 * clock-tick marks rise between them.
 */
internal fun DrawScope.drawCorona(ctx: LoadingPaintContext) {
    val col = lerp(ctx.monthColor, ctx.weekColor, 0.5f)
    val r = ctx.radius * 0.985f
    val thin = ctx.radius * 0.018f
    rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
        var start = 0f
        repeat(12) {
            ringArc(col.copy(alpha = 0.30f + 0.12f * ctx.breathe), ctx.c, r, start, 14f, thin, StrokeCap.Round)
            start += 30f
        }
    }
    rotate(degrees = -ctx.phase3 * 216f, pivot = ctx.c) {
        var start = 15f
        repeat(12) {
            ringArc(col.copy(alpha = 0.16f), ctx.c, r, start, 10f, thin * 0.8f, StrokeCap.Round)
            start += 30f
        }
    }
    if (ctx.tiers.grandeur >= 15) {
        rotate(degrees = ctx.phase3 * 108f, pivot = ctx.c) {
            for (i in 0 until 24) {
                val a = i * 15f
                val inner = r - ctx.radius * 0.035f
                drawLine(
                    color = col.copy(alpha = 0.22f),
                    start = orbitPoint(ctx.c, inner, a),
                    end = orbitPoint(ctx.c, inner - ctx.radius * 0.012f, a),
                    strokeWidth = ctx.radius * 0.008f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

// ─────────────────────────── grandeur: shooting stars ────────────────────

/**
 * Comets streaking across the entire canvas (grandeur ≥ 16). Each flies a
 * hashed chord through the disc on a staggered schedule — a reward so
 * grand the sky itself participates in the celebration.
 */
internal fun DrawScope.drawShootingStars(ctx: LoadingPaintContext) {
    val count = when {
        ctx.tiers.grandeur >= GrandeurThresholds.TRANSCENDENCE -> 5
        ctx.tiers.grandeur >= GrandeurThresholds.AURORA -> 4
        ctx.tiers.grandeur >= GrandeurThresholds.TOTALITY -> 3
        else -> 2
    }
    val rim = ctx.radius * 0.98f
    for (i in 0 until count) {
        val w = (ctx.phase3 * 2f + i / count.toFloat()) % 1f
        val h1 = hash01(i * 13.7f + 2.2f)
        val h2 = hash01(i * 5.31f + 8.8f)
        val angA = h1 * 360f
        val a = orbitPoint(ctx.c, rim, angA)
        val b = orbitPoint(ctx.c, rim, angA + 140f + h2 * 80f)
        val t = w * w * (3f - 2f * w)   // smoothstep flight
        val head = lerp(a, b, t)
        val tail = lerp(a, b, (t - 0.10f).coerceAtLeast(0f))
        val alpha = (sin(Math.PI * w).toFloat() * 0.75f).coerceAtLeast(0f)
        val col = if (i % 2 == 0) Color.White else lerp(ctx.monthColor, ctx.weekColor, 0.5f)
        drawLine(
            color = col.copy(alpha = alpha),
            start = tail,
            end = head,
            strokeWidth = ctx.radius * 0.010f,
            cap = StrokeCap.Round
        )
        dot(col.copy(alpha = alpha), head, ctx.radius * 0.014f)
    }
}

// ─────────────────────────── grandeur: spectrum crown ────────────────────

/**
 * The full reward of the near-perfect orrery (grandeur ≥ 17): a ring
 * cycling through EVERY tier colour at once, circling the whole piece —
 * a reminder of the entire ladder climbed to reach this height.
 */
internal fun DrawScope.drawSpectrumCrown(ctx: LoadingPaintContext) {
    val spectrum = if (ctx.tiers.grandeur >= GrandeurThresholds.AURORA) {
        // The transcendent crown remembers the whole thirteen-rung ladder:
        // the six vivid hues, white, then the six white+colour glass tints.
        listOf(
            BorderRed, BorderOrange, BorderGreen, BorderBlue,
            BorderPink, BorderYellow, BorderGlass, Color.White,
            BorderWhiteRed, BorderWhiteOrange, BorderWhiteGreen,
            BorderWhiteBlue, BorderWhitePink, BorderWhiteYellow, BorderRed
        )
    } else {
        listOf(
            BorderRed, BorderOrange, BorderGreen, BorderBlue,
            BorderPink, BorderYellow, BorderGlass, Color.White, BorderRed
        )
    }
    rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
        drawCircle(
            brush = Brush.sweepGradient(spectrum, center = ctx.c),
            radius = ctx.radius * 0.995f,
            center = ctx.c,
            style = Stroke(width = ctx.radius * 0.012f)
        )
    }
    rotate(degrees = -ctx.phase3 * 540f, pivot = ctx.c) {
        drawCircle(
            brush = Brush.sweepGradient(spectrum.map { it.copy(alpha = 0.35f) }, center = ctx.c),
            radius = ctx.radius * 0.90f,
            center = ctx.c,
            style = Stroke(width = ctx.radius * 0.007f)
        )
    }
}

// ─────────────────────────── grandeur: totality ──────────────────────────

/**
 * The perfect 6/6/6 (grandeur 18). Slow white shockwaves roll outward
 * while the heart of the orrery pulses like a beacon — Totality, the
 * rarest sight in the habit sky.
 */
internal fun DrawScope.drawTotality(ctx: LoadingPaintContext) {
    for (k in 0 until 2) {
        val p = (ctx.breathe2 + k * 0.5f) % 1f
        ringArc(
            Color.White.copy(alpha = 0.16f * (1f - p)),
            ctx.c,
            ctx.radius * (0.55f + 0.44f * p),
            0f, 360f,
            ctx.radius * 0.014f,
            StrokeCap.Butt
        )
    }
    val glowR = ctx.radius * (0.16f + 0.10f * ctx.breathe2)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.10f + 0.08f * ctx.breathe2),
            1f to Color.Transparent,
            center = ctx.c,
            radius = glowR
        ),
        radius = glowR,
        center = ctx.c
    )
}

// ───────────────────── grandeur: aurora (transcendent) ───────────────────

/**
 * Curtains of light waving behind the whole orrery (grandeur ≥ 19 — the
 * first flourish of the white+colour era, and the biggest single step in
 * the entire system: crossing from white into white/red wakes the sky
 * itself). Three ribbons in the three combo hues ripple around the pole,
 * their radius breathing in long slow waves.
 */
internal fun DrawScope.drawAurora(ctx: LoadingPaintContext) {
    val hues = listOf(ctx.monthCombo, ctx.weekCombo, ctx.dayCombo)
    val baseR = ctx.radius * 0.80f
    rotate(degrees = ctx.phase3 * 120f, pivot = ctx.c) {
        for (i in 0 until 3) {
            val col = hues[i]
            val span = 110f + 30f * i
            val start = i * 120f + 20f * ctx.phase2
            val steps = 22
            val path = Path()
            for (j in 0..steps) {
                val t = j / steps.toFloat()
                val ang = start + span * t
                val wave = sin(
                    (ang * 0.10f + ctx.phase3 * 4f * Math.PI + i * 2.1f).toDouble()
                ).toFloat()
                val r = baseR * (0.90f + 0.10f * wave + 0.04f * ctx.breathe2)
                val p = orbitPoint(ctx.c, r, ang)
                if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                col.copy(alpha = 0.16f + 0.10f * ctx.breathe2),
                style = Stroke(width = ctx.radius * 0.030f, cap = StrokeCap.Round)
            )
            // A brighter hem on the curtain's edge.
            drawPath(
                path,
                col.copy(alpha = 0.30f + 0.18f * ctx.breathe2),
                style = Stroke(width = ctx.radius * 0.008f, cap = StrokeCap.Round)
            )
        }
    }
}

// ─────────────────── grandeur: constellation (transcendent) ──────────────

/**
 * Chains of linked stars strung across the void (grandeur ≥ 22) — as
 * though the orrery's light had crystallised into new constellations.
 * Three deterministic chains, their nodes twinkling, their threads in the
 * blended combo light.
 */
internal fun DrawScope.drawConstellation(ctx: LoadingPaintContext) {
    val hues = listOf(ctx.monthCombo, ctx.weekCombo, ctx.dayCombo)
    val thread = lerp(ctx.monthCombo, ctx.weekCombo, 0.5f)
    for (chain in 0 until 3) {
        val col = hues[chain]
        val nodes = 4 + chain
        var prev: Offset? = null
        for (j in 0 until nodes) {
            val h1 = hash01(chain * 17.3f + j * 3.7f + 1.1f)
            val h2 = hash01(chain * 5.9f + j * 11.3f + 4.2f)
            val ang = h1 * 360f + ctx.phase3 * 60f * (if (chain % 2 == 0) 1f else -1f)
            val r = ctx.radius * (0.34f + 0.58f * h2)
            val p = orbitPoint(ctx.c, r, ang)
            if (prev != null) {
                drawLine(
                    color = thread.copy(alpha = 0.14f + 0.08f * ctx.breathe2),
                    start = prev,
                    end = p,
                    strokeWidth = ctx.radius * 0.004f,
                    cap = StrokeCap.Round
                )
            }
            val tw = twinkle(ctx.phase4, chain * 2.6f + j * 1.3f)
            star(col.copy(alpha = 0.30f + 0.45f * tw), p, ctx.radius * (0.018f + 0.014f * tw))
            prev = p
        }
    }
}

// ───────────────────── grandeur: polar jets (transcendent) ───────────────

/**
 * Beams of light erupting from the orrery's poles (grandeur ≥ 25), slowly
 * precessing around the sky. Each jet is a tapered stack of segments in
 * the blended combo light with a burning tip — the achievement has become
 * a lighthouse visible across the whole habit sky.
 */
internal fun DrawScope.drawPolarJets(ctx: LoadingPaintContext) {
    val col = lerp(lerp(ctx.monthCombo, ctx.weekCombo, 0.5f), ctx.dayCombo, 0.34f)
    val tilt = ctx.phase3 * 300f
    rotate(degrees = tilt, pivot = ctx.c) {
        for (dir in 0 until 2) {
            val sign = if (dir == 0) -1f else 1f
            val len = ctx.radius * (0.94f + 0.05f * ctx.breathe2)
            val segs = 4
            for (s in 0 until segs) {
                val t0 = 0.22f + (s / segs.toFloat()) * 0.78f
                val t1 = 0.22f + ((s + 1) / segs.toFloat()) * 0.78f
                val fade = 1f - s / segs.toFloat()
                drawLine(
                    color = col.copy(alpha = (0.34f + 0.20f * ctx.breathe2) * fade),
                    start = Offset(ctx.c.x, ctx.c.y + sign * len * t0),
                    end = Offset(ctx.c.x, ctx.c.y + sign * len * t1),
                    strokeWidth = ctx.radius * (0.030f - 0.024f * (1f - fade)),
                    cap = StrokeCap.Round
                )
            }
            val tip = Offset(ctx.c.x, ctx.c.y + sign * len)
            val tw = twinkle(ctx.phase4, dir * 3.3f)
            dot(Color.White.copy(alpha = 0.55f + 0.35f * tw), tip, ctx.radius * 0.016f)
            star(col.copy(alpha = 0.35f), tip, ctx.radius * 0.05f)
        }
    }
}

// ─────────────────── grandeur: halo of halos (transcendent) ──────────────

/**
 * Miniature spectrum crowns orbiting the rim of the orrery (grandeur ≥
 * 28) — three tiny full-spectrum rings circling the great one, each
 * spinning on its own axis. The crown has become a court.
 */
internal fun DrawScope.drawHaloOfHalos(ctx: LoadingPaintContext) {
    val spectrum = listOf(
        BorderRed, BorderOrange, BorderGreen, BorderBlue,
        BorderPink, BorderYellow, BorderRed
    )
    ringArc(
        Color.White.copy(alpha = 0.10f + 0.06f * ctx.breathe2),
        ctx.c, ctx.radius * 1.0f, 0f, 360f,
        ctx.radius * 0.006f, StrokeCap.Butt
    )
    for (i in 0 until 3) {
        val a = ctx.phase2 * 360f + i * 120f
        val hc = orbitPoint(ctx.c, ctx.radius * 1.0f, a)
        val hr = ctx.radius * 0.055f
        rotate(degrees = ctx.phase4 * 720f + i * 120f, pivot = hc) {
            drawCircle(
                brush = Brush.sweepGradient(spectrum, center = hc),
                radius = hr,
                center = hc,
                style = Stroke(width = ctx.radius * 0.008f)
            )
        }
        dot(Color.White.copy(alpha = 0.5f), hc, ctx.radius * 0.007f)
    }
}

// ─────────────────── grandeur: transcendence (the summit) ────────────────

/**
 * TRANSCENDENCE (grandeur ≥ 32 — the reachable summit of 10/10/12, the
 * white/blue month and week crowned by a white/yellow day). Prismatic
 * shockwaves roll outward through the full thirteen-rung spectrum while
 * a dozen vivid sparks orbit the blazing white heart. Totality was the
 * perfect trinity; Transcendence is the whole ladder climbed at once.
 */
internal fun DrawScope.drawTranscendence(ctx: LoadingPaintContext) {
    val spectrum = listOf(
        BorderRed, BorderOrange, BorderGreen, BorderBlue,
        BorderPink, BorderYellow, Color.White,
        BorderWhiteRed, BorderWhiteOrange, BorderWhiteGreen,
        BorderWhiteBlue, BorderWhitePink, BorderWhiteYellow, BorderRed
    )
    for (k in 0 until 3) {
        val p = (ctx.breathe2 + k / 3f) % 1f
        rotate(degrees = ctx.phase3 * 360f * (if (k % 2 == 0) 1f else -1f), pivot = ctx.c) {
            drawCircle(
                brush = Brush.sweepGradient(spectrum, center = ctx.c),
                radius = ctx.radius * (0.30f + 0.68f * p),
                center = ctx.c,
                style = Stroke(width = ctx.radius * 0.014f * (1f - 0.5f * p))
            )
        }
    }
    val vivid = listOf(
        BorderRed, BorderOrange, BorderGreen,
        BorderBlue, BorderPink, BorderYellow
    )
    for (i in 0 until 12) {
        val a = ctx.phase2 * 360f + i * 30f
        val tw = twinkle(ctx.phase4, i * 1.9f)
        dot(
            vivid[i % 6].copy(alpha = 0.35f + 0.45f * tw),
            orbitPoint(ctx.c, ctx.radius * 0.60f, a),
            ctx.radius * (0.010f + 0.008f * tw)
        )
    }
    val glowR = ctx.radius * (0.20f + 0.12f * ctx.breathe2)
    drawCircle(
        brush = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.16f + 0.12f * ctx.breathe2),
            1f to Color.Transparent,
            center = ctx.c,
            radius = glowR
        ),
        radius = glowR,
        center = ctx.c
    )
}

// ───────────────────────────── resonance ─────────────────────────────────

/**
 * The alignment flourish: when the monthly, weekly and daily tiers all sit
 * on the same non-zero colour, slow ripples expand through the whole
 * orrery in the blended resonance colour — richer ripples the higher the
 * shared tier. A reward for balance across every timescale.
 */
internal fun DrawScope.drawResonance(ctx: LoadingPaintContext) {
    val col = lerp(lerp(ctx.monthColor, ctx.weekColor, 0.5f), ctx.dayColor, 0.34f)
    val ripples = 3 + ctx.tiers.monthly / 2
    for (k in 0 until ripples) {
        val p = (ctx.phase + k / ripples.toFloat()) % 1f
        ringArc(
            col.copy(alpha = 0.28f * (1f - p)),
            ctx.c,
            ctx.radius * (0.10f + 0.85f * p),
            0f, 360f,
            ctx.radius * 0.016f,
            StrokeCap.Butt
        )
    }
}
