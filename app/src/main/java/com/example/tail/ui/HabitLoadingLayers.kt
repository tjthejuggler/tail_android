package com.example.tail.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY — LAYER RENDERERS
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Each artistic layer of the loading animation is drawn by one renderer
 * below, dispatched from [HabitLoadingSpinner]:
 *
 *   drawBackdropGlow — faint deep-space glow (monthly tier ≥ 4).
 *   drawWeeklyHalo   — outer orbital system, owned by the WEEKLY average.
 *   drawMonthlyCore  — the central archetype, owned by the MONTHLY average.
 *   drawDailySpark   — the small central accent, owned by TODAY's points.
 *   drawResonance    — reward ripples when all three tiers align.
 *
 * Within each layer, elements are strictly cumulative: every tier adds or
 * transforms something, so levelling up any metric always reveals a new
 * detail. All geometry is derived from [LoadingPaintContext.radius] so the
 * whole piece scales with the composable's size.
 */

/** Immutable bundle of everything the layer renderers need for one frame. */
internal class LoadingPaintContext(
    val c: Offset,
    val radius: Float,
    val tiers: LoadingTiers,
    val monthColor: Color,
    val weekColor: Color,
    val dayColor: Color,
    val phase: Float,
    val phase2: Float,
    val phase3: Float,
    val breathe: Float
)

// ─────────────────────────── shared primitives ───────────────────────────

/** Draws a stroked arc on the circle of radius [r] around [c]. Angles in degrees. */
private fun DrawScope.ringArc(
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
private fun orbitPoint(c: Offset, r: Float, angleDeg: Float): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    return Offset((c.x + r * cos(a)).toFloat(), (c.y + r * sin(a)).toFloat())
}

/** Point on a tilted ellipse (semi-axes [rx]/[ry], rotation [tiltDeg]) around [c]. */
private fun ellipsePoint(c: Offset, rx: Float, ry: Float, tiltDeg: Float, thetaDeg: Float): Offset {
    val t = Math.toRadians(thetaDeg.toDouble())
    val x = rx * cos(t)
    val y = ry * sin(t)
    val tr = Math.toRadians(tiltDeg.toDouble())
    val xr = x * cos(tr) - y * sin(tr)
    val yr = x * sin(tr) + y * cos(tr)
    return Offset((c.x + xr).toFloat(), (c.y + yr).toFloat())
}

/** Filled dot — the particle primitive used everywhere. */
private fun DrawScope.dot(color: Color, center: Offset, radius: Float) =
    drawCircle(color = color, radius = radius, center = center)

/**
 * A comet tail trailing [tailSweep] degrees behind a head at [headAngle],
 * rendered as [segments] arc slices that shrink and fade towards the tip.
 */
private fun DrawScope.cometTail(
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
private fun twinkle(phase: Float, seed: Float): Float =
    abs(sin(phase * 2.0 * Math.PI + seed)).toFloat()

// ───────────────────────────── backdrop glow ─────────────────────────────

/**
 * Faint radial glow that breathes behind everything else, blending the
 * monthly and weekly colours. Only the mightiest cores (monthly tier ≥ 4)
 * radiate enough energy to light up the void.
 */
internal fun DrawScope.drawBackdropGlow(ctx: LoadingPaintContext) {
    val glow = lerp(ctx.monthColor, ctx.weekColor, 0.5f)
    val r = ctx.radius * (0.72f + 0.18f * ctx.breathe)
    drawCircle(
        brush = Brush.radialGradient(
            0f to glow.copy(alpha = 0.10f + 0.06f * ctx.breathe),
            1f to Color.Transparent,
            center = ctx.c,
            radius = r
        ),
        radius = r,
        center = ctx.c
    )
}

// ───────────────────────────── weekly halo ───────────────────────────────

/**
 * The outer orbital system at ~0.95 R, owned by the WEEKLY average tier.
 * Each tier grows the constellation:
 *
 *   0 — empty sky
 *   1 — a single faint ring appears on the horizon
 *   2 — …joined by its first satellite with a short orbital trail
 *   3 — the ring becomes a tilted ellipse with two opposing satellites
 *   4 — twin rings (one dashed, counter-rotating) + three twinkling satellites
 *   5 — a shimmering gradient ring + four satellites with trails
 *   6 — the full halo: precessing spectrum ring, inner companion ring and
 *       a six-satellite swarm, each with its own twinkle and trail
 */
internal fun DrawScope.drawWeeklyHalo(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val haloR = R * 0.95f
    val thin = R * 0.035f
    val col = ctx.weekColor

    when (ctx.tiers.weekly) {
        0 -> Unit

        1 -> ringArc(col.copy(alpha = 0.20f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)

        2 -> {
            ringArc(col.copy(alpha = 0.20f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
            val a = ctx.phase2 * 360f
            ringArc(col.copy(alpha = 0.50f), ctx.c, haloR, a - 34f, 34f, thin * 1.1f)
            dot(col, orbitPoint(ctx.c, haloR, a), R * 0.05f)
        }

        3 -> {
            // Tilted elliptical orbit — the halo leaves the plane of the circle.
            val tilt = -20f
            val rx = haloR
            val ry = haloR * 0.88f
            rotate(degrees = tilt, pivot = ctx.c) {
                scale(scaleX = 1f, scaleY = ry / rx, pivot = ctx.c) {
                    ringArc(col.copy(alpha = 0.22f), ctx.c, rx, 0f, 360f, thin, StrokeCap.Butt)
                }
            }
            for (i in 0 until 2) {
                val theta = ctx.phase2 * 360f + i * 180f
                dot(
                    col.copy(alpha = if (i == 0) 1f else 0.7f),
                    ellipsePoint(ctx.c, rx, ry, tilt, theta),
                    R * 0.045f
                )
            }
        }

        4 -> {
            ringArc(col.copy(alpha = 0.22f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                var start = 0f
                repeat(8) {
                    ringArc(col.copy(alpha = 0.55f), ctx.c, haloR * 0.88f, start, 20f, thin * 0.8f)
                    start += 45f
                }
            }
            for (i in 0 until 3) {
                val a = ctx.phase2 * 360f + i * 120f + 40f
                val tw = twinkle(ctx.phase, i * 1.9f)
                dot(
                    col.copy(alpha = 0.55f + 0.45f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.035f + 0.02f * tw)
                )
            }
        }

        5 -> {
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            col.copy(alpha = 0.08f),
                            col.copy(alpha = 0.85f),
                            col.copy(alpha = 0.08f)
                        ),
                        center = ctx.c
                    ),
                    radius = haloR,
                    center = ctx.c,
                    style = Stroke(width = thin * 1.2f)
                )
            }
            for (i in 0 until 4) {
                val a = ctx.phase2 * 360f + i * 90f
                ringArc(col.copy(alpha = 0.45f), ctx.c, haloR, a - 26f, 26f, thin * 0.7f)
                val tw = twinkle(ctx.phase, i * 1.7f)
                dot(
                    col.copy(alpha = 0.60f + 0.40f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.04f + 0.02f * tw)
                )
            }
        }

        else -> { // 6 — the full halo
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            col.copy(alpha = 0.10f),
                            col.copy(alpha = 0.95f),
                            col.copy(alpha = 0.25f),
                            col.copy(alpha = 0.95f),
                            col.copy(alpha = 0.10f)
                        ),
                        center = ctx.c
                    ),
                    radius = haloR,
                    center = ctx.c,
                    style = Stroke(width = thin * 1.3f)
                )
            }
            ringArc(col.copy(alpha = 0.18f), ctx.c, haloR * 0.86f, 0f, 360f, thin * 0.7f, StrokeCap.Butt)
            for (i in 0 until 6) {
                val a = ctx.phase2 * 360f + i * 60f
                ringArc(col.copy(alpha = 0.40f), ctx.c, haloR, a - 22f, 22f, thin * 0.6f)
                val tw = twinkle(ctx.phase, i * 1.3f)
                dot(
                    col.copy(alpha = 0.55f + 0.45f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.035f + 0.025f * tw)
                )
            }
        }
    }
}

// ───────────────────────────── monthly core ──────────────────────────────

/**
 * The central archetype at ~0.62 R, owned by the MONTHLY average tier.
 * The soul of the piece — each tier is a distinct celestial body:
 *
 *   0 · RED    "Ember"        — a single humble breathing arc
 *   1 · ORANGE "Twin Flames"  — two counter-rotating arcs on a faint guide
 *   2 · GREEN  "Comet"        — a comet with a segmented fading tail
 *   3 · BLUE   "Atom"         — three tilted elliptical orbits with
 *                               counter-running electrons and a nucleus
 *   4 · PINK   "Rose Window"  — a breathing ring framed by two
 *                               counter-rotating dashed arc rings
 *   5 · YELLOW "Binary Suns"  — twin comets inside a shimmering sweep ring
 *   6 · GLASS  "Supernova"    — a rotating spectrum ring, eight twinkling
 *                               particles, four rays and a pulsing core glow
 */
internal fun DrawScope.drawMonthlyCore(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val coreR = R * 0.62f
    val stroke = R * 0.085f
    val col = ctx.monthColor

    when (ctx.tiers.monthly) {
        // ── 0 · RED — Ember ───────────────────────────────────────────────
        0 -> {
            val sweep = 80f + 110f * ctx.breathe
            ringArc(col, ctx.c, coreR, ctx.phase * 360f, sweep, stroke)
        }

        // ── 1 · ORANGE — Twin Flames ─────────────────────────────────────
        1 -> {
            ringArc(col.copy(alpha = 0.15f), ctx.c, coreR, 0f, 360f, stroke * 0.4f, StrokeCap.Butt)
            ringArc(col, ctx.c, coreR, ctx.phase * 360f, 110f, stroke)
            ringArc(col.copy(alpha = 0.55f), ctx.c, coreR, -ctx.phase2 * 360f + 180f, 110f, stroke * 0.7f)
        }

        // ── 2 · GREEN — Comet ────────────────────────────────────────────
        2 -> {
            ringArc(col.copy(alpha = 0.12f), ctx.c, coreR, 0f, 360f, stroke * 0.35f, StrokeCap.Butt)
            val head = ctx.phase * 360f
            cometTail(col, ctx.c, coreR, head, 140f, stroke)
            dot(col, orbitPoint(ctx.c, coreR, head), R * 0.075f)
        }

        // ── 3 · BLUE — Atom ──────────────────────────────────────────────
        3 -> {
            ringArc(col.copy(alpha = 0.10f), ctx.c, coreR, 0f, 360f, stroke * 0.3f, StrokeCap.Butt)
            val ry = coreR * 0.38f
            for (i in 0 until 3) {
                val tilt = i * 60f + 15f
                rotate(degrees = tilt, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = ry / coreR, pivot = ctx.c) {
                        ringArc(col.copy(alpha = 0.35f), ctx.c, coreR, 0f, 360f, stroke * 0.35f, StrokeCap.Butt)
                    }
                }
                // Electron — alternating direction so the orbits feel alive.
                val dir = if (i % 2 == 0) 1f else -1f
                val theta = dir * ctx.phase * 360f + i * 120f
                dot(col, ellipsePoint(ctx.c, coreR, ry, tilt, theta), R * 0.05f)
            }
            // Pulsing nucleus.
            dot(col.copy(alpha = 0.85f), ctx.c, R * (0.05f + 0.02f * ctx.breathe))
        }

        // ── 4 · PINK — Rose Window ───────────────────────────────────────
        4 -> {
            val pulse = 0.85f + 0.15f * ctx.breathe
            ringArc(
                col.copy(alpha = 0.25f + 0.35f * ctx.breathe),
                ctx.c, coreR * pulse, 0f, 360f, stroke * 0.5f, StrokeCap.Butt
            )
            rotate(degrees = ctx.phase * 360f, pivot = ctx.c) {
                var start = 0f
                repeat(6) {
                    ringArc(col, ctx.c, coreR, start, 32f, stroke)
                    start += 60f
                }
            }
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                var start = 15f
                repeat(4) {
                    ringArc(col.copy(alpha = 0.6f), ctx.c, coreR * 0.8f, start, 40f, stroke * 0.55f)
                    start += 90f
                }
            }
        }

        // ── 5 · YELLOW — Binary Suns ─────────────────────────────────────
        5 -> {
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            col.copy(alpha = 0.10f),
                            col.copy(alpha = 0.90f),
                            col.copy(alpha = 0.10f)
                        ),
                        center = ctx.c
                    ),
                    radius = coreR,
                    center = ctx.c,
                    style = Stroke(width = stroke * 0.6f)
                )
            }
            for (i in 0 until 2) {
                val head = ctx.phase * 360f + i * 180f
                cometTail(col.copy(alpha = 0.85f), ctx.c, coreR, head, 70f, stroke * 0.8f, segments = 4)
                dot(col, orbitPoint(ctx.c, coreR, head), R * 0.07f)
            }
        }

        // ── 6 · GLASS — Supernova ────────────────────────────────────────
        else -> {
            rotate(degrees = ctx.phase * 360f, pivot = ctx.c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            col.copy(alpha = 0.15f),
                            Color.White,
                            col.copy(alpha = 0.60f),
                            Color.White,
                            col.copy(alpha = 0.15f)
                        ),
                        center = ctx.c
                    ),
                    radius = coreR,
                    center = ctx.c,
                    style = Stroke(width = stroke * 0.7f)
                )
            }
            // Eight sparkling particles counter-orbiting, each on its own twinkle.
            for (i in 0 until 8) {
                val a = -ctx.phase2 * 360f + i * 45f
                val tw = twinkle(ctx.phase, i * 0.8f)
                dot(
                    Color.White.copy(alpha = 0.30f + 0.60f * tw),
                    orbitPoint(ctx.c, coreR, a),
                    R * (0.03f + 0.04f * tw)
                )
            }
            // Four rays slowly sweeping through the blast.
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                for (i in 0 until 4) {
                    val a = Math.toRadians((i * 90f).toDouble())
                    val inner = coreR * 0.30f
                    val outer = coreR * (1.02f + 0.10f * ctx.breathe)
                    drawLine(
                        color = col.copy(alpha = 0.22f + 0.18f * ctx.breathe),
                        start = Offset(
                            (ctx.c.x + inner * cos(a)).toFloat(),
                            (ctx.c.y + inner * sin(a)).toFloat()
                        ),
                        end = Offset(
                            (ctx.c.x + outer * cos(a)).toFloat(),
                            (ctx.c.y + outer * sin(a)).toFloat()
                        ),
                        strokeWidth = stroke * 0.30f,
                        cap = StrokeCap.Round
                    )
                }
            }
            // Pulsing core glow.
            val glowR = R * (0.28f + 0.22f * ctx.breathe)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.85f),
                    1f to Color.Transparent,
                    center = ctx.c,
                    radius = glowR
                ),
                radius = glowR,
                center = ctx.c
            )
        }
    }
}

// ───────────────────────────── daily spark ───────────────────────────────

/**
 * The small central accent, owned by TODAY's points tier. A modest but
 * personal touch — the day's own colour burning at the heart of the piece:
 *
 *   0 — dormant
 *   1 — a tiny steady dot ignites
 *   2 — …it learns to breathe
 *   3 — …and sprouts a slowly turning sparkle cross
 *   4 — …a first micro-moon takes orbit around it
 *   5 — …joined by a second, counter-orbiting moon
 *   6 — three moons plus two ripple rings expanding from the spark
 */
internal fun DrawScope.drawDailySpark(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val col = ctx.dayColor
    val d = ctx.tiers.daily
    if (d == 0) return

    // The spark itself — steady at tier 1, breathing from tier 2 onward.
    val pulse = if (d >= 2) ctx.breathe else 0.5f
    dot(
        col.copy(alpha = 0.65f + 0.35f * pulse),
        ctx.c,
        R * (0.042f + 0.022f * pulse)
    )

    // Sparkle cross — two breathing arms drifting on a slow diagonal.
    if (d >= 3) {
        val arm = R * (0.10f + 0.05f * ctx.breathe)
        val alpha = 0.35f + 0.45f * ctx.breathe
        rotate(degrees = 45f + ctx.phase3 * 90f, pivot = ctx.c) {
            drawLine(
                col.copy(alpha = alpha),
                Offset(ctx.c.x - arm, ctx.c.y),
                Offset(ctx.c.x + arm, ctx.c.y),
                strokeWidth = R * 0.016f,
                cap = StrokeCap.Round
            )
            drawLine(
                col.copy(alpha = alpha),
                Offset(ctx.c.x, ctx.c.y - arm),
                Offset(ctx.c.x, ctx.c.y + arm),
                strokeWidth = R * 0.016f,
                cap = StrokeCap.Round
            )
        }
    }

    // Micro-moons — tiny satellites circling the spark at increasing count.
    if (d >= 4) {
        val moons = if (d >= 6) 3 else d - 3   // tier 4 → 1, 5 → 2, 6 → 3
        val moonR = R * 0.22f
        for (i in 0 until moons) {
            val dir = if (i % 2 == 0) 1f else -1f
            val base = if (i % 2 == 0) ctx.phase else ctx.phase2
            val speed = if (i % 2 == 0) 1.5f else 0.9f
            val a = dir * base * 360f * speed + i * 120f
            dot(col.copy(alpha = 0.9f), orbitPoint(ctx.c, moonR, a), R * 0.032f)
        }
    }

    // Ripples — the spark's energy overflows at the highest tier.
    if (d >= 6) {
        for (k in 0 until 2) {
            val p = (ctx.phase + k * 0.5f) % 1f
            ringArc(
                col.copy(alpha = 0.35f * (1f - p)),
                ctx.c,
                R * (0.06f + 0.24f * p),
                0f, 360f,
                R * 0.014f,
                StrokeCap.Butt
            )
        }
    }
}

// ───────────────────────────── resonance ─────────────────────────────────

/**
 * The hidden flourish: when the monthly, weekly and daily tiers all align
 * on the same non-zero colour, three slow ripples expand through the whole
 * orrery in the blended resonance colour — a reward for balanced,
 * consistent performance across every timescale.
 */
internal fun DrawScope.drawResonance(ctx: LoadingPaintContext) {
    val col = lerp(lerp(ctx.monthColor, ctx.weekColor, 0.5f), ctx.dayColor, 0.34f)
    for (k in 0 until 3) {
        val p = (ctx.phase + k / 3f) % 1f
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
