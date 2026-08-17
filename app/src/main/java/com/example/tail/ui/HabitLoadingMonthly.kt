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
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY II — MONTHLY CORE (the soul of the piece)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The central archetype at ~0.62 R, owned by the MONTHLY average tier.
 * Each tier is a distinct celestial body; each PATRONAGE rank enriches it:
 *
 *   STRANGER — the body as it is, alone in the dark.
 *   ALLY     — brighter colour, guides and trails appear.
 *   PATRON   — the supporters' own colours (weekly & daily) join the
 *              dance as companion sparks, glows and debris.
 *
 *   0 · RED    "Ember"        — a single humble flickering arc
 *   1 · ORANGE "Twin Flames"  — two counter-rotating arcs
 *   2 · GREEN  "Comet"        — a comet with a fading tail
 *   3 · BLUE   "Atom"         — tilted elliptical orbits with electrons
 *   4 · PINK   "Rose Window"  — counter-rotating dashed cathedral rings
 *   5 · YELLOW "Binary Suns"  — twin suns in a shimmering sweep
 *   6 · GLASS  "Supernova"    — spectrum rings, particles, rays, shockwaves
 */

/** Short fading trail of dots behind an electron on a tilted ellipse. */
private fun DrawScope.ellipseTrail(
    color: Color,
    c: Offset,
    rx: Float,
    ry: Float,
    tilt: Float,
    thetaDeg: Float,
    dir: Float,
    size: Float
) {
    for (k in 1..4) {
        val fade = 1f - k / 5f
        dot(
            color.copy(alpha = 0.45f * fade),
            ellipsePoint(c, rx, ry, tilt, thetaDeg - dir * k * 9f),
            size * (0.35f + 0.65f * fade)
        )
    }
}

internal fun DrawScope.drawMonthlyCore(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val coreR = R * 0.62f
    val stroke = R * 0.085f
    val pat = ctx.tiers.monthPatronage
    val col = patronTint(ctx.monthColor, pat)
    val s1 = ctx.weekColor   // the supporters
    val s2 = ctx.dayColor

    when (ctx.tiers.monthly) {

        // ── 0 · RED — Ember ───────────────────────────────────────────
        // Alone: a sad flickering arc. Aided: a guide ring and a fatter
        // flame. Championed: a well-fed fire with stoker sparks.
        0 -> {
            val flicker = 0.55f + 0.25f * twinkle(ctx.phase4, 1.3f)
            if (pat >= 1) {
                ringArc(col.copy(alpha = 0.10f), ctx.c, coreR, 0f, 360f, stroke * 0.3f, StrokeCap.Butt)
            }
            val sweep = if (pat >= 1) 100f + 120f * ctx.breathe else 70f + 90f * ctx.breathe
            ringArc(
                col.copy(alpha = (flicker + if (pat >= 2) 0.25f else 0f).coerceAtMost(1f)),
                ctx.c, coreR, ctx.phase * 360f, sweep,
                stroke * (if (pat >= 2) 1.15f else 0.85f)
            )
            if (pat >= 2) {
                val g = coreR * (0.55f + 0.15f * ctx.breathe)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to col.copy(alpha = 0.16f + 0.10f * ctx.breathe),
                        1f to Color.Transparent,
                        center = ctx.c, radius = g
                    ),
                    radius = g, center = ctx.c
                )
                for (i in 0 until 2) {
                    val a = ctx.phase2 * 360f + i * 180f
                    dot(
                        (if (i == 0) s1 else s2).copy(alpha = 0.75f),
                        orbitPoint(ctx.c, coreR * 0.8f, a),
                        R * 0.024f
                    )
                }
            }
        }

        // ── 1 · ORANGE — Twin Flames ──────────────────────────────────
        1 -> {
            ringArc(col.copy(alpha = 0.15f), ctx.c, coreR, 0f, 360f, stroke * 0.4f, StrokeCap.Butt)
            val head = ctx.phase * 360f
            ringArc(col, ctx.c, coreR, head, 110f, stroke)
            ringArc(col.copy(alpha = 0.55f), ctx.c, coreR, -ctx.phase2 * 360f + 180f, 110f, stroke * 0.7f)
            if (pat >= 1) {
                // The flames learn to trail fire as they run.
                cometTail(col.copy(alpha = 0.8f), ctx.c, coreR, head, 40f, stroke * 0.6f, segments = 3)
                rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                    var start = 0f
                    repeat(6) {
                        ringArc(col.copy(alpha = 0.22f), ctx.c, coreR * 0.78f, start, 26f, stroke * 0.3f)
                        start += 60f
                    }
                }
            }
            if (pat >= 2) {
                ringArc(col.copy(alpha = 0.35f), ctx.c, coreR * 0.78f, ctx.phase3 * 720f, 90f, stroke * 0.5f)
                for (i in 0 until 2) {
                    val a = if (i == 0) head else -ctx.phase2 * 360f + 180f
                    val tw = twinkle(ctx.phase4, i * 2.1f)
                    dot(
                        (if (i == 0) s1 else s2).copy(alpha = 0.5f + 0.5f * tw),
                        orbitPoint(ctx.c, coreR, a),
                        R * (0.026f + 0.016f * tw)
                    )
                }
            }
        }

        // ── 2 · GREEN — Comet ─────────────────────────────────────────
        2 -> {
            ringArc(col.copy(alpha = 0.12f), ctx.c, coreR, 0f, 360f, stroke * 0.35f, StrokeCap.Butt)
            val head = ctx.phase * 360f
            cometTail(col, ctx.c, coreR, head, 140f, stroke)
            dot(col, orbitPoint(ctx.c, coreR, head), R * 0.075f)
            if (pat >= 1) {
                // A second, fainter comet chases the first around the sky.
                val head2 = -ctx.phase2 * 360f + 120f
                cometTail(col.copy(alpha = 0.5f), ctx.c, coreR, head2, 90f, stroke * 0.55f, segments = 4)
                dot(col.copy(alpha = 0.7f), orbitPoint(ctx.c, coreR, head2), R * 0.05f)
            }
            if (pat >= 2) {
                // Debris in the supporters' colours scattered along the tail.
                for (j in 0 until 6) {
                    val t = j / 6f
                    val ang = head - 140f * (0.15f + 0.85f * t)
                    val jr = coreR * (0.82f + 0.30f * hash01(j * 3.1f))
                    val tw = twinkle(ctx.phase4, j * 1.9f)
                    dot(
                        (if (j % 2 == 0) s1 else s2).copy(alpha = 0.30f + 0.50f * tw),
                        orbitPoint(ctx.c, jr, ang),
                        R * (0.014f + 0.012f * tw)
                    )
                }
                val g = R * (0.10f + 0.05f * ctx.breathe)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to col.copy(alpha = 0.30f + 0.20f * ctx.breathe),
                        1f to Color.Transparent,
                        center = ctx.c, radius = g
                    ),
                    radius = g, center = ctx.c
                )
            }
        }

        // ── 3 · BLUE — Atom ───────────────────────────────────────────
        3 -> {
            ringArc(col.copy(alpha = 0.10f), ctx.c, coreR, 0f, 360f, stroke * 0.3f, StrokeCap.Butt)
            val orbits = if (pat >= 2) 5 else if (pat >= 1) 4 else 3
            val ry = coreR * 0.38f
            for (i in 0 until orbits) {
                val tilt = i * (180f / orbits) + 15f
                rotate(degrees = tilt, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = ry / coreR, pivot = ctx.c) {
                        ringArc(col.copy(alpha = 0.35f), ctx.c, coreR, 0f, 360f, stroke * 0.35f, StrokeCap.Butt)
                    }
                }
                val dir = if (i % 2 == 0) 1f else -1f
                val theta = dir * ctx.phase * 360f + i * (360f / orbits)
                val eCol = if (pat >= 2 && i >= 3) (if (i == 3) s1 else s2) else col
                if (pat >= 1) ellipseTrail(eCol, ctx.c, coreR, ry, tilt, theta, dir, R * 0.05f)
                dot(eCol, ellipsePoint(ctx.c, coreR, ry, tilt, theta), R * 0.05f)
            }
            dot(col.copy(alpha = 0.85f), ctx.c, R * (0.05f + 0.02f * ctx.breathe))
            if (pat >= 2) {
                val g = R * (0.14f + 0.06f * ctx.breathe)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to col.copy(alpha = 0.35f),
                        1f to Color.Transparent,
                        center = ctx.c, radius = g
                    ),
                    radius = g, center = ctx.c
                )
            }
        }

        // ── 4 · PINK — Rose Window ────────────────────────────────────
        4 -> {
            val pulse = 0.85f + 0.15f * ctx.breathe
            ringArc(
                col.copy(alpha = 0.25f + 0.35f * ctx.breathe),
                ctx.c, coreR * pulse, 0f, 360f, stroke * 0.5f, StrokeCap.Butt
            )
            val petals = if (pat >= 1) 8 else 6
            rotate(degrees = ctx.phase * 360f, pivot = ctx.c) {
                var start = 0f
                repeat(petals) {
                    ringArc(col, ctx.c, coreR, start, 360f / petals - 28f, stroke)
                    start += 360f / petals
                }
            }
            rotate(degrees = -ctx.phase2 * 360f, pivot = ctx.c) {
                var start = 15f
                repeat(4) {
                    ringArc(col.copy(alpha = 0.6f), ctx.c, coreR * 0.8f, start, 40f, stroke * 0.55f)
                    start += 90f
                }
            }
            if (pat >= 1) {
                ringArc(col.copy(alpha = 0.15f), ctx.c, coreR * 1.05f, 0f, 360f, stroke * 0.3f, StrokeCap.Butt)
            }
            if (pat >= 2) {
                // Full cathedral: a rotating spoke frame and a centre gem.
                rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                    for (i in 0 until 12) {
                        val a = Math.toRadians((i * 30f).toDouble())
                        drawLine(
                            color = col.copy(alpha = 0.22f),
                            start = Offset((ctx.c.x + coreR * 0.25f * cos(a)).toFloat(), (ctx.c.y + coreR * 0.25f * sin(a)).toFloat()),
                            end = Offset((ctx.c.x + coreR * 0.95f * cos(a)).toFloat(), (ctx.c.y + coreR * 0.95f * sin(a)).toFloat()),
                            strokeWidth = stroke * 0.14f,
                            cap = StrokeCap.Round
                        )
                    }
                }
                val gem = R * (0.10f + 0.05f * ctx.breathe)
                star(lerp(col, Color.White, 0.5f), ctx.c, gem)
                for (i in 0 until 4) {
                    val a = ctx.phase2 * 360f + i * 90f + 45f
                    dot(
                        (if (i % 2 == 0) s1 else s2).copy(alpha = 0.6f + 0.4f * twinkle(ctx.phase4, i * 1.4f)),
                        orbitPoint(ctx.c, coreR * 0.62f, a),
                        R * 0.022f
                    )
                }
            }
        }

        // ── 5 · YELLOW — Binary Suns ──────────────────────────────────
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
            val suns = if (pat >= 1) 3 else 2
            for (i in 0 until suns) {
                val head = ctx.phase * 360f + i * (360f / suns)
                cometTail(col.copy(alpha = 0.85f), ctx.c, coreR, head, 70f, stroke * 0.8f, segments = 4)
                val pulse = if (pat >= 2 && i < 2) (if (i == 0) ctx.breathe else 1f - ctx.breathe) else 0.5f
                dot(col, orbitPoint(ctx.c, coreR, head), R * (0.06f + 0.03f * pulse))
            }
            if (pat >= 1) {
                // Corona flares leaping from the rim.
                rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                    for (i in 0 until 8) {
                        val a = Math.toRadians((i * 45f).toDouble())
                        val r0 = coreR * 1.02f
                        val r1 = coreR * (1.10f + 0.06f * ctx.breathe)
                        drawLine(
                            color = col.copy(alpha = 0.30f + 0.25f * ctx.breathe),
                            start = Offset((ctx.c.x + r0 * cos(a)).toFloat(), (ctx.c.y + r0 * sin(a)).toFloat()),
                            end = Offset((ctx.c.x + r1 * cos(a)).toFloat(), (ctx.c.y + r1 * sin(a)).toFloat()),
                            strokeWidth = stroke * 0.22f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
            if (pat >= 2) {
                // Prominence arcs bridging the two lead suns, plus a
                // chromosphere glow and supporter glints on the rim.
                val a0 = ctx.phase * 360f
                val a1 = a0 + 180f
                val p0 = orbitPoint(ctx.c, coreR, a0)
                val p1 = orbitPoint(ctx.c, coreR, a1)
                val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                val dir = Offset(mid.x - ctx.c.x, mid.y - ctx.c.y)
                val len = kotlin.math.hypot(dir.x, dir.y).coerceAtLeast(1f)
                for (k in 1..2) {
                    val reach = coreR * (0.55f + 0.18f * k) * ctx.breathe
                    val ctrl = Offset(
                        mid.x + dir.x / len * reach,
                        mid.y + dir.y / len * reach
                    )
                    val path = Path()
                    path.moveTo(p0.x, p0.y)
                    path.quadraticBezierTo(ctrl.x, ctrl.y, p1.x, p1.y)
                    drawPath(
                        path,
                        col.copy(alpha = 0.30f + 0.20f * ctx.breathe),
                        style = Stroke(width = stroke * 0.18f, cap = StrokeCap.Round)
                    )
                }
                val g = coreR * (0.85f + 0.15f * ctx.breathe2)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to col.copy(alpha = 0.14f + 0.08f * ctx.breathe2),
                        1f to Color.Transparent,
                        center = ctx.c, radius = g
                    ),
                    radius = g, center = ctx.c
                )
                for (i in 0 until 4) {
                    val a = -ctx.phase3 * 360f + i * 90f
                    val tw = twinkle(ctx.phase4, i * 1.1f)
                    dot(
                        (if (i % 2 == 0) s1 else s2).copy(alpha = 0.40f + 0.50f * tw),
                        orbitPoint(ctx.c, coreR * 1.12f, a),
                        R * (0.018f + 0.014f * tw)
                    )
                }
            }
        }

        // ── 6 · GLASS — Supernova ─────────────────────────────────────
        else -> {
            val rings = if (pat >= 2) 3 else if (pat >= 1) 2 else 1
            val ringRadii = floatArrayOf(coreR, coreR * 0.92f, coreR * 0.84f)
            val ringSpeeds = floatArrayOf(ctx.phase * 360f, -ctx.phase2 * 360f, ctx.phase3 * 720f)
            for (r in 0 until rings) {
                rotate(degrees = ringSpeeds[r], pivot = ctx.c) {
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
                        radius = ringRadii[r],
                        center = ctx.c,
                        style = Stroke(width = stroke * (0.7f - 0.1f * r))
                    )
                }
            }
            val particles = if (pat >= 2) 16 else if (pat >= 1) 12 else 8
            for (i in 0 until particles) {
                val a = -ctx.phase2 * 360f + i * (360f / particles)
                val tw = twinkle(ctx.phase, i * 0.8f)
                val pCol = if (pat >= 2) {
                    when (i % 3) { 0 -> Color.White; 1 -> s1; else -> s2 }
                } else Color.White
                if (pat >= 1) {
                    cometTail(pCol.copy(alpha = 0.5f), ctx.c, coreR, a, 18f, stroke * 0.35f, segments = 2)
                }
                dot(
                    pCol.copy(alpha = 0.30f + 0.60f * tw),
                    orbitPoint(ctx.c, coreR, a),
                    R * (0.030f + 0.040f * tw)
                )
            }
            val rays = if (pat >= 2) 8 else if (pat >= 1) 6 else 4
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                for (i in 0 until rays) {
                    val a = Math.toRadians((i * 360f / rays).toDouble())
                    val inner = coreR * 0.30f
                    val outer = coreR * ((if (pat >= 2) 1.25f else 1.02f) + 0.10f * ctx.breathe)
                    drawLine(
                        color = col.copy(alpha = 0.22f + 0.18f * ctx.breathe),
                        start = Offset((ctx.c.x + inner * cos(a)).toFloat(), (ctx.c.y + inner * sin(a)).toFloat()),
                        end = Offset((ctx.c.x + outer * cos(a)).toFloat(), (ctx.c.y + outer * sin(a)).toFloat()),
                        strokeWidth = stroke * 0.30f,
                        cap = StrokeCap.Round
                    )
                }
            }
            if (pat >= 1) {
                // Shockwaves rolling outward from the blast.
                val waves = if (pat >= 2) 3 else 2
                for (k in 0 until waves) {
                    val p = (ctx.phase * 1.5f + k / waves.toFloat()) % 1f
                    ringArc(
                        col.copy(alpha = 0.25f * (1f - p)),
                        ctx.c, coreR * (0.20f + 0.80f * p),
                        0f, 360f, R * 0.012f, StrokeCap.Butt
                    )
                }
            }
            if (pat >= 2) {
                // Lens-flare star cross through the heart of the blast.
                val arm = coreR * 1.3f
                val flareAlpha = 0.22f + 0.20f * ctx.breathe2
                rotate(degrees = ctx.phase3 * 45f, pivot = ctx.c) {
                    drawLine(Color.White.copy(alpha = flareAlpha), Offset(ctx.c.x - arm, ctx.c.y), Offset(ctx.c.x + arm, ctx.c.y), strokeWidth = R * 0.012f, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = flareAlpha), Offset(ctx.c.x, ctx.c.y - arm), Offset(ctx.c.x, ctx.c.y + arm), strokeWidth = R * 0.012f, cap = StrokeCap.Round)
                }
                // Two mini-galaxy swarms orbiting the blast in the
                // supporters' colours — the wreckage has become art.
                for (g in 0 until 2) {
                    val ga = ctx.phase3 * 360f * (if (g == 0) 1f else -1f) + g * 180f
                    val gc = orbitPoint(ctx.c, coreR * 1.15f, ga)
                    val gcCol = if (g == 0) s1 else s2
                    val gry = R * 0.09f
                    rotate(degrees = ga, pivot = gc) {
                        for (m in 0 until 3) {
                            val mt = ctx.phase4 * 360f * 2f + m * 120f
                            dot(gcCol.copy(alpha = 0.7f), ellipsePoint(gc, R * 0.13f, gry, 0f, mt), R * 0.018f)
                        }
                    }
                }
            }
            val glowR = R * ((if (pat >= 2) 0.30f else 0.28f) + (if (pat >= 2) 0.25f else 0.22f) * ctx.breathe)
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
