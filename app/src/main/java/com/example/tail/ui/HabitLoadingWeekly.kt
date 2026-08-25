package com.example.tail.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY II — WEEKLY HALO (the orbital system)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The outer orbital system at ~0.95 R, owned by the WEEKLY average tier.
 * Each tier grows the constellation; each PATRONAGE rank enriches it:
 *
 *   STRANGER — the sky as it is, empty or sparse.
 *   ALLY     — brighter colour, extra satellites and trails.
 *   PATRON   — companion planets in the supporters' colours (monthly &
 *              daily), asteroid belts, and at the summit: galaxy mode.
 *
 *   0 · RED    "Empty Sky"     — nothing at all (red weeks get void)
 *   1 · ORANGE "First Ring"    — a single faint ring on the horizon
 *   2 · GREEN  "Satellite"     — the ring gains a moon with a trail
 *   3 · BLUE   "Tilted Orbit"  — a tilted ellipse with two moons
 *   4 · PINK   "Twin Rings"    — dashed counter-rotating ring systems
 *   5 · YELLOW "Shimmer Ring"  — a gradient sweep ring with a swarm
 *   6 · WHITE  "Full Halo"     — spectrum rings, swarms, belts, spirals
 *
 * The WHITE+COLOUR combo tiers (the weekly average realistically tops out
 * around white/blue) — white ring systems wearing their vivid combo hue:
 *
 *   7 · WHITE/RED    "Ember Halo"     — white ring, red flare satellites
 *   8 · WHITE/ORANGE "Molten Rings"   — white ring, orange lava dashes
 *   9 · WHITE/GREEN  "Aurora Veil"    — white ring, green waving veil
 *  10 · WHITE/BLUE   "Spiral Galaxy"  — white core ring, blue spiral arms
 */

internal fun DrawScope.drawWeeklyHalo(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val haloR = R * 0.95f
    val thin = R * 0.035f
    val pat = ctx.tiers.weekPatronage
    val col = patronTint(ctx.weekColor, pat)
    val s1 = ctx.monthColor   // the supporters
    val s2 = ctx.dayColor

    when (ctx.tiers.weekly) {

        // ── 0 · RED — Empty Sky ───────────────────────────────────────
        // A red week earns void. Patrons can only coax out ghosts.
        0 -> {
            if (pat >= 1) {
                val a = ctx.phase3 * 360f
                dot(col.copy(alpha = 0.22f + 0.10f * twinkle(ctx.phase4, 0.7f)), orbitPoint(ctx.c, haloR * 0.90f, a), R * 0.028f)
            }
            if (pat >= 2) {
                for (i in 0 until 3) {
                    val start = i * 120f + ctx.phase3 * 180f
                    ringArc(
                        col.copy(alpha = 0.14f + 0.12f * twinkle(ctx.phase4, i * 2.3f)),
                        ctx.c, haloR, start, 26f, thin * 0.8f, StrokeCap.Butt
                    )
                }
                for (i in 0 until 2) {
                    val a = -ctx.phase3 * 360f + i * 180f
                    dot(col.copy(alpha = 0.25f), orbitPoint(ctx.c, haloR * 0.85f, a), R * 0.020f)
                }
            }
        }

        // ── 1 · ORANGE — First Ring ───────────────────────────────────
        1 -> {
            ringArc(col.copy(alpha = if (pat >= 2) 0.30f else 0.20f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
            if (pat >= 1) {
                val a = ctx.phase2 * 360f
                ringArc(col.copy(alpha = 0.50f), ctx.c, haloR, a - 34f, 34f, thin * 1.1f)
                dot(col, orbitPoint(ctx.c, haloR, a), R * 0.05f)
            }
            if (pat >= 2) {
                val a2 = -ctx.phase2 * 360f + 180f
                ringArc(col.copy(alpha = 0.40f), ctx.c, haloR, a2 - 26f, 26f, thin * 0.9f)
                dot(col.copy(alpha = 0.8f), orbitPoint(ctx.c, haloR, a2), R * 0.04f)
                for (i in 0 until 3) {
                    val a = i * 120f + 60f
                    val tw = twinkle(ctx.phase4, i * 1.6f)
                    dot(
                        (if (i % 2 == 0) s1 else s2).copy(alpha = 0.35f + 0.45f * tw),
                        orbitPoint(ctx.c, haloR, a),
                        R * (0.016f + 0.012f * tw)
                    )
                }
            }
        }

        // ── 2 · GREEN — Satellite ─────────────────────────────────────
        2 -> {
            if (pat >= 2) {
                // The orbit leaves the plane of the circle.
                val tilt = -20f
                val rx = haloR
                val ry = haloR * 0.88f
                rotate(degrees = tilt, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = ry / rx, pivot = ctx.c) {
                        ringArc(col.copy(alpha = 0.22f), ctx.c, rx, 0f, 360f, thin, StrokeCap.Butt)
                    }
                }
                for (i in 0 until 3) {
                    val theta = ctx.phase2 * 360f + i * 120f
                    cometTail(col.copy(alpha = 0.45f), ctx.c, haloR, theta, 22f, thin * 0.6f, segments = 2)
                    dot(col.copy(alpha = if (i == 0) 1f else 0.75f), ellipsePoint(ctx.c, rx, ry, tilt, theta), R * 0.042f)
                }
            } else {
                ringArc(col.copy(alpha = 0.20f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
                val a = ctx.phase2 * 360f
                ringArc(col.copy(alpha = 0.50f), ctx.c, haloR, a - 34f, 34f, thin * 1.1f)
                dot(col, orbitPoint(ctx.c, haloR, a), R * 0.05f)
                if (pat >= 1) {
                    val a2 = a + 180f
                    ringArc(col.copy(alpha = 0.40f), ctx.c, haloR, a2 - 26f, 26f, thin * 0.9f)
                    dot(col.copy(alpha = 0.8f), orbitPoint(ctx.c, haloR, a2), R * 0.04f)
                    dot(col.copy(alpha = 0.5f), orbitPoint(ctx.c, haloR, a + 90f), R * 0.022f)
                    dot(col.copy(alpha = 0.5f), orbitPoint(ctx.c, haloR, a + 270f), R * 0.022f)
                }
            }
        }

        // ── 3 · BLUE — Tilted Orbit ───────────────────────────────────
        3 -> {
            val ellipses = if (pat >= 1) 2 else 1
            for (e in 0 until ellipses) {
                val tilt = if (e == 0) -20f else 25f
                val rx = haloR
                val ry = haloR * (if (e == 0) 0.88f else 0.70f)
                rotate(degrees = tilt, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = ry / rx, pivot = ctx.c) {
                        ringArc(col.copy(alpha = 0.22f), ctx.c, rx, 0f, 360f, thin, StrokeCap.Butt)
                    }
                }
                val moons = if (pat >= 2) 2 else if (pat >= 1) 1 else 2
                for (i in 0 until moons) {
                    val dir = if ((i + e) % 2 == 0) 1f else -1f
                    val theta = dir * ctx.phase2 * 360f + i * 180f + e * 90f
                    if (pat >= 2) {
                        for (k in 1..3) {
                            dot(
                                col.copy(alpha = 0.30f * (1f - k / 4f)),
                                ellipsePoint(ctx.c, rx, ry, tilt, theta - dir * k * 8f),
                                R * 0.030f * (1f - k / 5f)
                            )
                        }
                    }
                    dot(col.copy(alpha = 0.9f), ellipsePoint(ctx.c, rx, ry, tilt, theta), R * 0.045f)
                }
            }
            if (pat >= 2) {
                // Gyroscope: a precessing equatorial band and companion
                // bodies in the supporters' colours.
                val prec = -20f + 40f * ctx.phase3
                rotate(degrees = prec, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = 0.55f, pivot = ctx.c) {
                        ringArc(col.copy(alpha = 0.14f), ctx.c, haloR * 0.93f, 0f, 360f, thin * 0.6f, StrokeCap.Butt)
                    }
                }
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f + i * 180f
                    dot(
                        (if (i == 0) s1 else s2).copy(alpha = 0.55f + 0.35f * twinkle(ctx.phase4, i * 1.2f)),
                        orbitPoint(ctx.c, haloR * 0.96f, a),
                        R * 0.026f
                    )
                }
            }
        }

        // ── 4 · PINK — Twin Rings ─────────────────────────────────────
        4 -> {
            ringArc(col.copy(alpha = 0.22f), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                var start = 0f
                repeat(8) {
                    ringArc(col.copy(alpha = 0.55f), ctx.c, haloR * 0.88f, start, 20f, thin * 0.8f)
                    start += 45f
                }
            }
            if (pat >= 1) {
                rotate(degrees = ctx.phase2 * 300f, pivot = ctx.c) {
                    var start = 12f
                    repeat(8) {
                        ringArc(col.copy(alpha = 0.35f), ctx.c, haloR * 0.79f, start, 16f, thin * 0.6f)
                        start += 45f
                    }
                }
            }
            if (pat >= 2) {
                // Moiré interference: a third partial set at its own speed.
                rotate(degrees = ctx.phase3 * 420f, pivot = ctx.c) {
                    var start = 6f
                    repeat(6) {
                        ringArc(col.copy(alpha = 0.20f), ctx.c, haloR * 0.955f, start, 34f, thin * 0.5f)
                        start += 60f
                    }
                }
            }
            val sats = if (pat >= 2) 6 else if (pat >= 1) 5 else 3
            for (i in 0 until sats) {
                val a = ctx.phase2 * 360f + i * (360f / sats) + 40f
                val tw = twinkle(ctx.phase, i * 1.9f)
                if (pat >= 2) {
                    ringArc(col.copy(alpha = 0.35f), ctx.c, haloR, a - 20f, 20f, thin * 0.55f)
                }
                dot(
                    col.copy(alpha = 0.55f + 0.45f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.035f + 0.02f * tw)
                )
            }
            if (pat >= 2) {
                // Companion planets, each with a micro-moon of its own.
                for (i in 0 until 2) {
                    val a = -ctx.phase3 * 360f + i * 180f + 90f
                    val pc = orbitPoint(ctx.c, haloR * 0.88f, a)
                    val pcCol = if (i == 0) s1 else s2
                    dot(pcCol.copy(alpha = 0.85f), pc, R * 0.034f)
                    val ma = ctx.phase4 * 360f * 2f
                    dot(pcCol.copy(alpha = 0.5f), orbitPoint(pc, R * 0.06f, ma), R * 0.014f)
                }
            }
        }

        // ── 5 · YELLOW — Shimmer Ring ─────────────────────────────────
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
            if (pat >= 1) {
                rotate(degrees = -ctx.phase3 * 540f, pivot = ctx.c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                col.copy(alpha = 0.05f),
                                col.copy(alpha = 0.55f),
                                col.copy(alpha = 0.05f)
                            ),
                            center = ctx.c
                        ),
                        radius = haloR * 0.88f,
                        center = ctx.c,
                        style = Stroke(width = thin * 0.8f)
                    )
                }
            }
            if (pat >= 2) {
                ringArc(col.copy(alpha = 0.18f), ctx.c, haloR * 0.78f, 0f, 360f, thin * 0.6f, StrokeCap.Butt)
                // Shadow bands drifting across the ring plane.
                for (i in 0 until 2) {
                    val a = ctx.phase3 * 360f + i * 180f
                    ringArc(Color.Black.copy(alpha = 0.10f), ctx.c, haloR, a, 50f, thin * 1.4f, StrokeCap.Butt)
                }
            }
            val sats = if (pat >= 2) 8 else if (pat >= 1) 6 else 4
            for (i in 0 until sats) {
                val a = ctx.phase2 * 360f + i * (360f / sats)
                ringArc(col.copy(alpha = 0.45f), ctx.c, haloR, a - 26f, 26f, thin * 0.7f)
                val tw = twinkle(ctx.phase, i * 1.7f)
                dot(
                    col.copy(alpha = 0.60f + 0.40f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.04f + 0.02f * tw)
                )
            }
            if (pat >= 2) {
                // The shepherd pair — two bright companions in the
                // supporters' colours herding the swarm.
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f + i * 180f
                    val sc = orbitPoint(ctx.c, haloR * 0.92f, a)
                    dot((if (i == 0) s1 else s2).copy(alpha = 0.9f), sc, R * 0.040f)
                    star((if (i == 0) s1 else s2).copy(alpha = 0.35f), sc, R * 0.09f)
                }
            }
        }

        // ── 6 · WHITE — Full Halo / Galaxy ────────────────────────────
        6 -> {
            val rings = if (pat >= 2) 3 else if (pat >= 1) 2 else 1
            val radii = floatArrayOf(haloR, haloR * 0.90f, haloR * 0.85f)
            val speeds = floatArrayOf(ctx.phase3 * 360f, -ctx.phase3 * 540f, ctx.phase3 * 720f)
            for (r in 0 until rings) {
                rotate(degrees = speeds[r], pivot = ctx.c) {
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
                        radius = radii[r],
                        center = ctx.c,
                        style = Stroke(width = thin * (1.3f - 0.15f * r))
                    )
                }
            }
            ringArc(col.copy(alpha = 0.18f), ctx.c, haloR * 0.86f, 0f, 360f, thin * 0.7f, StrokeCap.Butt)

            val sats = if (pat >= 2) 12 else if (pat >= 1) 10 else 6
            for (i in 0 until sats) {
                val a = ctx.phase2 * 360f + i * (360f / sats)
                ringArc(col.copy(alpha = 0.40f), ctx.c, haloR, a - 22f, 22f, thin * 0.6f)
                val tw = twinkle(ctx.phase, i * 1.3f)
                dot(
                    col.copy(alpha = 0.55f + 0.45f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.035f + 0.025f * tw)
                )
            }

            // Asteroid belt — a dotted ring of hashed rocks.
            val belt = if (pat >= 2) 24 else if (pat >= 1) 16 else 0
            if (belt > 0) {
                val beltR = haloR * 0.78f
                for (i in 0 until belt) {
                    val h = hash01(i * 2.13f + 0.7f)
                    val a = ctx.phase3 * 360f * 0.8f + i * (360f / belt)
                    val jr = beltR * (0.96f + 0.08f * h)
                    val tw = twinkle(ctx.phase4, i * 0.9f)
                    dot(
                        col.copy(alpha = 0.18f + 0.30f * tw * h),
                        orbitPoint(ctx.c, jr, a),
                        R * (0.006f + 0.008f * h)
                    )
                }
            }

            if (pat >= 2) {
                // GALAXY MODE — spiral arms of stars winding outward,
                // and binary companions in the supporters' colours.
                for (arm in 0 until 2) {
                    val a0 = ctx.phase3 * 360f + arm * 180f
                    spiralArm(
                        col.copy(alpha = 0.14f + 0.06f * ctx.breathe2),
                        ctx.c,
                        R * 0.30f, R * 0.90f, a0, 300f,
                        R * 0.010f
                    )
                }
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f * (if (i == 0) 1f else -1f) + i * 180f
                    val bc = orbitPoint(ctx.c, haloR * 0.90f, a)
                    val bcCol = if (i == 0) s1 else s2
                    dot(bcCol.copy(alpha = 0.9f), bc, R * 0.038f)
                    val ma = ctx.phase4 * 360f * 2f
                    dot(bcCol.copy(alpha = 0.55f), orbitPoint(bc, R * 0.07f, ma), R * 0.016f)
                    dot(bcCol.copy(alpha = 0.35f), orbitPoint(bc, R * 0.07f, ma + 180f), R * 0.012f)
                }
            }
        }

        // ── 7 · WHITE/RED — Ember Halo ────────────────────────────────
        // A white ring crowned by red flare satellites that streak
        // around it, trailing fire — the halo of the Phoenix era.
        7 -> {
            val red = ctx.weekCombo
            ringArc(col.copy(alpha = 0.55f + 0.20f * ctx.breathe), ctx.c, haloR, 0f, 360f, thin * 1.2f, StrokeCap.Butt)
            if (pat >= 1) {
                ringArc(Color.White.copy(alpha = 0.18f), ctx.c, haloR * 0.90f, 0f, 360f, thin * 0.5f, StrokeCap.Butt)
            }
            val flares = if (pat >= 2) 5 else if (pat >= 1) 4 else 3
            for (i in 0 until flares) {
                val a = ctx.phase2 * 360f + i * (360f / flares)
                cometTail(red.copy(alpha = 0.7f), ctx.c, haloR, a, 30f, thin * 0.8f, segments = 3)
                val tw = twinkle(ctx.phase4, i * 1.9f)
                dot(
                    red.copy(alpha = 0.70f + 0.30f * tw),
                    orbitPoint(ctx.c, haloR, a),
                    R * (0.035f + 0.020f * tw)
                )
                if (pat >= 1) {
                    star(red.copy(alpha = 0.25f + 0.20f * tw), orbitPoint(ctx.c, haloR, a), R * 0.075f)
                }
            }
            // Ghost arcs of the red era drifting inside the ring.
            val ghosts = if (pat >= 2) 4 else 2
            for (i in 0 until ghosts) {
                val start = i * (360f / ghosts) + ctx.phase3 * 200f
                ringArc(red.copy(alpha = 0.16f + 0.10f * ctx.breathe2), ctx.c, haloR * 0.82f, start, 40f, thin * 0.6f)
            }
            if (pat >= 3) {
                // CHAMPION: a red shockwave rolling around the halo track.
                val p = ctx.phase2 % 1f
                ringArc(red.copy(alpha = 0.55f * (1f - p)), ctx.c, haloR * (0.80f + 0.20f * p), 0f, 360f, thin * 0.7f, StrokeCap.Butt)
                for (i in 0 until 2) {
                    val a = -ctx.phase3 * 360f + i * 180f
                    dot((if (i == 0) s1 else s2).copy(alpha = 0.8f), orbitPoint(ctx.c, haloR * 0.95f, a), R * 0.028f)
                }
            }
        }

        // ── 8 · WHITE/ORANGE — Molten Rings ───────────────────────────
        // A white ring wrapped in counter-rotating orange lava dashes,
        // with sparks spitting from the rim.
        8 -> {
            val orange = ctx.weekCombo
            ringArc(col.copy(alpha = 0.50f + 0.20f * ctx.breathe), ctx.c, haloR, 0f, 360f, thin * 1.1f, StrokeCap.Butt)
            val dashes = if (pat >= 2) 10 else if (pat >= 1) 8 else 6
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                var start = 0f
                repeat(dashes) {
                    ringArc(orange.copy(alpha = 0.60f + 0.25f * ctx.breathe), ctx.c, haloR * 0.90f, start, 360f / dashes - 16f, thin * 0.8f)
                    start += 360f / dashes
                }
            }
            if (pat >= 1) {
                rotate(degrees = ctx.phase2 * 300f, pivot = ctx.c) {
                    var start = 10f
                    repeat(dashes) {
                        ringArc(orange.copy(alpha = 0.35f), ctx.c, haloR * 0.80f, start, 360f / dashes - 22f, thin * 0.55f)
                        start += 360f / dashes
                    }
                }
            }
            // Sparks spitting from the molten rim.
            val sparks = if (pat >= 2) 8 else 5
            for (i in 0 until sparks) {
                val a = ctx.phase * 360f + i * (360f / sparks)
                val h = hash01(i * 4.1f + 0.3f)
                val tw = twinkle(ctx.phase4, i * 1.6f)
                dot(
                    orange.copy(alpha = 0.35f + 0.50f * tw),
                    orbitPoint(ctx.c, haloR * (0.96f + 0.06f * h), a),
                    R * (0.010f + 0.012f * tw)
                )
            }
            if (pat >= 3) {
                // CHAMPION: a white shimmer sweep circling outside the
                // lava, plus supporter companions herding the sparks.
                rotate(degrees = ctx.phase3 * 540f, pivot = ctx.c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.White.copy(alpha = 0.05f),
                                Color.White.copy(alpha = 0.45f),
                                Color.White.copy(alpha = 0.05f)
                            ),
                            center = ctx.c
                        ),
                        radius = haloR * 0.98f,
                        center = ctx.c,
                        style = Stroke(width = thin * 0.5f)
                    )
                }
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f * (if (i == 0) 1f else -1f) + i * 180f
                    dot((if (i == 0) s1 else s2).copy(alpha = 0.85f), orbitPoint(ctx.c, haloR * 0.92f, a), R * 0.030f)
                }
            }
        }

        // ── 9 · WHITE/GREEN — Aurora Veil ─────────────────────────────
        // A white ring behind a waving green veil — the halo has become
        // the horizon of a green dawn.
        9 -> {
            val green = ctx.weekCombo
            ringArc(col.copy(alpha = 0.45f + 0.15f * ctx.breathe), ctx.c, haloR, 0f, 360f, thin, StrokeCap.Butt)
            // The veil — a ring whose radius waves around its circumference.
            val veils = if (pat >= 2) 3 else 2
            rotate(degrees = ctx.phase3 * 180f, pivot = ctx.c) {
                for (v in 0 until veils) {
                    val steps = 40
                    val path = Path()
                    for (j in 0..steps) {
                        val ang = (j / steps.toFloat()) * 360f
                        val wave = kotlin.math.sin((ang * 0.09f + ctx.phase3 * 4f * Math.PI + v * 2.2f).toDouble()).toFloat()
                        val r = haloR * (0.86f + 0.07f * wave + 0.03f * ctx.breathe2 - 0.05f * v)
                        val p = orbitPoint(ctx.c, r, ang)
                        if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, green.copy(alpha = 0.14f + 0.08f * ctx.breathe2), style = Stroke(width = thin * 0.9f, cap = StrokeCap.Round))
                    drawPath(path, green.copy(alpha = 0.35f + 0.15f * ctx.breathe2), style = Stroke(width = thin * 0.25f, cap = StrokeCap.Round))
                }
            }
            // Drifting motes of green light caught in the veil.
            val motes = if (pat >= 1) 8 else 5
            for (i in 0 until motes) {
                val h = hash01(i * 5.3f + 1.7f)
                val a = ctx.phase2 * 360f * 0.7f + h * 360f
                val tw = twinkle(ctx.phase4, i * 2.4f)
                dot(
                    green.copy(alpha = 0.25f + 0.45f * tw),
                    orbitPoint(ctx.c, haloR * (0.83f + 0.12f * h), a),
                    R * (0.008f + 0.010f * tw)
                )
            }
            if (pat >= 3) {
                // CHAMPION: the veil doubles and the supporters' lights
                // shine through it.
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f + i * 180f + 45f
                    star((if (i == 0) s1 else s2).copy(alpha = 0.45f), orbitPoint(ctx.c, haloR * 0.88f, a), R * 0.07f)
                }
            }
        }

        // ── 10+ · WHITE/BLUE — Spiral Galaxy ──────────────────────────
        // The grandest halo (white/blue and beyond): a white core ring
        // with blue spiral arms winding outward through a star swarm.
        else -> {
            val blue = ctx.weekCombo
            ringArc(col.copy(alpha = 0.55f + 0.20f * ctx.breathe), ctx.c, haloR * 0.92f, 0f, 360f, thin, StrokeCap.Butt)
            ringArc(Color.White.copy(alpha = 0.20f), ctx.c, haloR * 0.85f, 0f, 360f, thin * 0.4f, StrokeCap.Butt)
            val arms = if (pat >= 2) 3 else 2
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                for (arm in 0 until arms) {
                    val a0 = arm * (360f / arms)
                    spiralArm(blue.copy(alpha = 0.25f + 0.10f * ctx.breathe2), ctx.c, R * 0.42f, R * 0.97f, a0, 320f, thin * 0.5f)
                    if (pat >= 1) {
                        spiralArm(blue.copy(alpha = 0.12f), ctx.c, R * 0.50f, R * 0.90f, a0 + 50f, 280f, thin * 0.25f)
                    }
                }
            }
            // The star swarm riding the arms.
            val swarm = if (pat >= 3) 16 else if (pat >= 2) 12 else 8
            for (i in 0 until swarm) {
                val h = hash01(i * 2.9f + 0.6f)
                val t = hash01(i * 6.7f + 3.1f)
                val ang = ctx.phase3 * 360f + t * 320f + (i % arms) * (360f / arms)
                val r = R * (0.44f + 0.52f * t)
                val tw = twinkle(ctx.phase4, i * 1.2f)
                val pCol = if (pat >= 2 && i % 4 == 0) Color.White else blue
                dot(
                    pCol.copy(alpha = 0.30f + 0.50f * tw),
                    orbitPoint(ctx.c, r, ang + 10f * h),
                    R * (0.009f + 0.011f * tw)
                )
            }
            if (pat >= 2) {
                // PATRON+: a blue asteroid belt between the arms.
                for (i in 0 until 20) {
                    val h = hash01(i * 3.7f + 2.2f)
                    val a = ctx.phase3 * 360f * 0.9f + i * 18f
                    dot(
                        blue.copy(alpha = 0.15f + 0.25f * twinkle(ctx.phase4, i * 0.8f) * h),
                        orbitPoint(ctx.c, R * 0.68f * (0.97f + 0.06f * h), a),
                        R * (0.005f + 0.007f * h)
                    )
                }
            }
            if (pat >= 3) {
                // CHAMPION: twin shepherd galaxies in the supporters'
                // colours herding the swarm.
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f * (if (i == 0) 1f else -1f) + i * 180f
                    val gc = orbitPoint(ctx.c, haloR * 0.95f, a)
                    val gcCol = if (i == 0) s1 else s2
                    dot(gcCol.copy(alpha = 0.9f), gc, R * 0.034f)
                    star(gcCol.copy(alpha = 0.30f), gc, R * 0.085f)
                }
            }
        }
    }
}
