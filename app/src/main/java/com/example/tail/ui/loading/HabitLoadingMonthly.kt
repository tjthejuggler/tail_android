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
 *   6 · WHITE  "Supernova"    — spectrum rings, particles, rays, shockwaves
 *
 * The WHITE+COLOUR combo tiers (the monthly average realistically tops out
 * around white/blue). The body burns in the near-white glass tint while
 * the vivid combo hue paints the embellishments — each step grander than
 * the one from yellow to white was:
 *
 *   7 · WHITE/RED    "Phoenix"      — white ring body, red wings, embers
 *   8 · WHITE/ORANGE "Magnetar"     — white heart, orange field lines, arcs
 *   9 · WHITE/GREEN  "Aurora Heart" — white star, green curtains, ripples
 *  10 · WHITE/BLUE   "Galactic Core"— white bulge, blue spiral arms, swarm
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
                    path.quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
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

        // ── 6 · WHITE — Supernova ─────────────────────────────────────
        6 -> {
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

        // ── 7 · WHITE/RED — Phoenix ───────────────────────────────────
        // The first combo tier: a white ring body reborn from the
        // supernova, wearing great red wings that beat with the breath,
        // trailing embers. A step grander than anything below it.
        7 -> {
            val red = ctx.monthCombo
            // The white body — a luminous ring turning slowly.
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        listOf(
                            col.copy(alpha = 0.20f),
                            Color.White,
                            col.copy(alpha = 0.70f),
                            Color.White,
                            col.copy(alpha = 0.20f)
                        ),
                        center = ctx.c
                    ),
                    radius = coreR * 0.92f,
                    center = ctx.c,
                    style = Stroke(width = stroke * 0.8f)
                )
            }
            // The wings — two pairs of great red arcs beating outward.
            val flap = 0.5f + 0.5f * ctx.breathe
            val wingPairs = if (pat >= 3) 3 else 2
            for (w in 0 until wingPairs) {
                val base = ctx.phase * 360f + w * (360f / wingPairs)
                val sweep = 60f + 50f * flap + 14f * w
                ringArc(red.copy(alpha = 0.75f), ctx.c, coreR * (1.04f + 0.05f * w), base, sweep, stroke * 0.85f)
                ringArc(red.copy(alpha = 0.45f), ctx.c, coreR * (1.04f + 0.05f * w), base + 180f, sweep, stroke * 0.85f)
                if (pat >= 1) {
                    cometTail(red.copy(alpha = 0.6f), ctx.c, coreR * (1.04f + 0.05f * w), base + sweep, 36f, stroke * 0.5f, segments = 3)
                }
            }
            // Embers rising from the pyre.
            val embers = if (pat >= 2) 10 else 6
            for (i in 0 until embers) {
                val a = -ctx.phase2 * 360f + i * (360f / embers)
                val rr = coreR * (0.95f + 0.25f * hash01(i * 2.7f))
                val tw = twinkle(ctx.phase4, i * 1.5f)
                dot(
                    red.copy(alpha = 0.35f + 0.50f * tw),
                    orbitPoint(ctx.c, rr, a),
                    R * (0.012f + 0.014f * tw)
                )
            }
            // The white heart of the reborn star.
            dot(Color.White.copy(alpha = 0.80f + 0.20f * ctx.breathe), ctx.c, R * (0.055f + 0.02f * ctx.breathe))
            if (pat >= 2) {
                // Shockwaves of rebirth rolling outward.
                for (k in 0 until 3) {
                    val p = (ctx.phase * 1.4f + k / 3f) % 1f
                    ringArc(red.copy(alpha = 0.28f * (1f - p)), ctx.c, coreR * (0.25f + 0.95f * p), 0f, 360f, R * 0.012f, StrokeCap.Butt)
                }
            }
            if (pat >= 3) {
                // CHAMPION: a white flare cross through the heart and a
                // second, vaster wing pair in the supporters' colours.
                val arm = coreR * 1.35f
                rotate(degrees = ctx.phase3 * 60f, pivot = ctx.c) {
                    drawLine(Color.White.copy(alpha = 0.30f + 0.20f * ctx.breathe2), Offset(ctx.c.x - arm, ctx.c.y), Offset(ctx.c.x + arm, ctx.c.y), strokeWidth = R * 0.012f, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = 0.30f + 0.20f * ctx.breathe2), Offset(ctx.c.x, ctx.c.y - arm), Offset(ctx.c.x, ctx.c.y + arm), strokeWidth = R * 0.012f, cap = StrokeCap.Round)
                }
                for (i in 0 until 2) {
                    val a = ctx.phase2 * 360f + i * 180f + 90f
                    dot(
                        (if (i == 0) s1 else s2).copy(alpha = 0.6f + 0.4f * twinkle(ctx.phase4, i * 2.2f)),
                        orbitPoint(ctx.c, coreR * 1.18f, a),
                        R * 0.026f
                    )
                }
            }
        }

        // ── 8 · WHITE/ORANGE — Magnetar ───────────────────────────────
        // A white heart wrapped in orange magnetic field lines that
        // precess like a cage of light, with arcs leaping between poles.
        8 -> {
            val orange = ctx.monthCombo
            dot(Color.White.copy(alpha = 0.85f + 0.15f * ctx.breathe), ctx.c, R * (0.05f + 0.015f * ctx.breathe))
            ringArc(col.copy(alpha = 0.25f), ctx.c, coreR * 0.55f, 0f, 360f, stroke * 0.3f, StrokeCap.Butt)
            // The field-line cage — nested tilted ellipses, precessing.
            val lines = if (pat >= 3) 7 else if (pat >= 2) 6 else if (pat >= 1) 5 else 4
            val prec = ctx.phase3 * 40f
            for (i in 0 until lines) {
                val tilt = i * (180f / lines) + prec
                val ry = coreR * (0.42f + 0.10f * (i % 2))
                rotate(degrees = tilt, pivot = ctx.c) {
                    scale(scaleX = 1f, scaleY = ry / coreR, pivot = ctx.c) {
                        ringArc(orange.copy(alpha = 0.30f + 0.10f * ctx.breathe), ctx.c, coreR, 0f, 360f, stroke * 0.22f, StrokeCap.Butt)
                    }
                }
                // A riding spark on each line — the field made visible.
                val theta = ctx.phase * 360f * (if (i % 2 == 0) 1f else -1f) + i * (360f / lines)
                if (pat >= 1) {
                    ellipseTrail(orange.copy(alpha = 0.6f), ctx.c, coreR, ry, tilt, theta, if (i % 2 == 0) 1f else -1f, R * 0.038f)
                }
                dot(orange.copy(alpha = 0.85f), ellipsePoint(ctx.c, coreR, ry, tilt, theta), R * 0.036f)
            }
            // Arcs leaping between the poles.
            val leaps = if (pat >= 2) 4 else 2
            for (k in 0 until leaps) {
                val a = ctx.phase2 * 360f + k * (360f / leaps)
                val p0 = orbitPoint(ctx.c, coreR * 0.35f, a)
                val p1 = orbitPoint(ctx.c, coreR * 0.35f, a + 180f)
                val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                val dir = Offset(mid.x - ctx.c.x, mid.y - ctx.c.y)
                val len = kotlin.math.hypot(dir.x, dir.y).coerceAtLeast(1f)
                val reach = coreR * (0.85f + 0.25f * ctx.breathe) * (if (k % 2 == 0) 1f else -1f)
                val ctrl = Offset(mid.x + dir.x / len * reach, mid.y + dir.y / len * reach)
                val path = Path()
                path.moveTo(p0.x, p0.y)
                path.quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
                drawPath(path, orange.copy(alpha = 0.35f + 0.20f * ctx.breathe), style = Stroke(width = stroke * 0.16f, cap = StrokeCap.Round))
            }
            if (pat >= 3) {
                // CHAMPION: a white star heart and supporter glints riding
                // the outermost field line.
                star(lerp(col, Color.White, 0.7f).copy(alpha = 0.7f), ctx.c, R * (0.08f + 0.03f * ctx.breathe2))
                for (i in 0 until 2) {
                    val a = -ctx.phase3 * 360f + i * 180f
                    dot(
                        (if (i == 0) s1 else s2).copy(alpha = 0.55f + 0.40f * twinkle(ctx.phase4, i * 1.7f)),
                        orbitPoint(ctx.c, coreR * 1.05f, a),
                        R * 0.022f
                    )
                }
            }
        }

        // ── 9 · WHITE/GREEN — Aurora Heart ────────────────────────────
        // A white star heart with green curtains of light rippling
        // around it — the orrery's own northern lights.
        9 -> {
            val green = ctx.monthCombo
            star(lerp(col, Color.White, 0.6f).copy(alpha = 0.75f + 0.25f * ctx.breathe), ctx.c, R * (0.09f + 0.03f * ctx.breathe))
            dot(Color.White.copy(alpha = 0.9f), ctx.c, R * 0.035f)
            // The curtains — wavy ring ribbons breathing around the heart.
            val curtains = if (pat >= 2) 4 else 3
            rotate(degrees = ctx.phase3 * 240f, pivot = ctx.c) {
                for (i in 0 until curtains) {
                    val span = 90f + 20f * i
                    val start = i * (360f / curtains) + 18f * ctx.phase2
                    val steps = 20
                    val path = Path()
                    for (j in 0..steps) {
                        val t = j / steps.toFloat()
                        val ang = start + span * t
                        val wave = sin((ang * 0.12f + ctx.phase3 * 5f * Math.PI + i * 1.9f).toDouble()).toFloat()
                        val r = coreR * (0.68f + 0.16f * wave + 0.05f * ctx.breathe2)
                        val p = orbitPoint(ctx.c, r, ang)
                        if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, green.copy(alpha = 0.20f + 0.12f * ctx.breathe2), style = Stroke(width = stroke * 0.42f, cap = StrokeCap.Round))
                    drawPath(path, green.copy(alpha = 0.45f + 0.20f * ctx.breathe2), style = Stroke(width = stroke * 0.10f, cap = StrokeCap.Round))
                }
            }
            // Ripples of green light spreading from the heart.
            val waves = if (pat >= 1) 3 else 2
            for (k in 0 until waves) {
                val p = (ctx.phase + k / waves.toFloat()) % 1f
                ringArc(green.copy(alpha = 0.30f * (1f - p)), ctx.c, coreR * (0.15f + 0.85f * p), 0f, 360f, R * 0.012f, StrokeCap.Butt)
            }
            // Petals of light orbiting through the curtains.
            val petals = if (pat >= 2) 6 else 4
            for (i in 0 until petals) {
                val a = ctx.phase2 * 360f + i * (360f / petals)
                val tw = twinkle(ctx.phase4, i * 1.3f)
                dot(
                    green.copy(alpha = 0.40f + 0.45f * tw),
                    orbitPoint(ctx.c, coreR * 0.80f, a),
                    R * (0.016f + 0.012f * tw)
                )
            }
            if (pat >= 3) {
                // CHAMPION: a second, counter-waving curtain layer and a
                // white glow enveloping the whole heart.
                rotate(degrees = -ctx.phase3 * 300f, pivot = ctx.c) {
                    val path = Path()
                    val steps = 26
                    for (j in 0..steps) {
                        val t = j / steps.toFloat()
                        val ang = 30f + 300f * t
                        val wave = sin((ang * 0.09f - ctx.phase3 * 6f * Math.PI).toDouble()).toFloat()
                        val r = coreR * (0.92f + 0.10f * wave)
                        val p = orbitPoint(ctx.c, r, ang)
                        if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, lerp(green, Color.White, 0.4f).copy(alpha = 0.25f + 0.12f * ctx.breathe2), style = Stroke(width = stroke * 0.30f, cap = StrokeCap.Round))
                }
                val g = coreR * (0.9f + 0.1f * ctx.breathe2)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.12f + 0.08f * ctx.breathe2),
                        1f to Color.Transparent,
                        center = ctx.c, radius = g
                    ),
                    radius = g, center = ctx.c
                )
            }
        }

        // ── 10+ · WHITE/BLUE — Galactic Core ──────────────────────────
        // The grandest monthly form (white/blue and beyond): a white
        // bulge blazing at the centre of blue spiral arms winding
        // outward, swarming with stars.
        else -> {
            val blue = ctx.monthCombo
            // The white bulge.
            val bulge = coreR * (0.55f + 0.10f * ctx.breathe2)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.90f),
                    0.5f to col.copy(alpha = 0.35f),
                    1f to Color.Transparent,
                    center = ctx.c,
                    radius = bulge
                ),
                radius = bulge,
                center = ctx.c
            )
            ringArc(col.copy(alpha = 0.30f), ctx.c, coreR * 0.60f, 0f, 360f, stroke * 0.25f, StrokeCap.Butt)
            // The spiral arms, winding outward and rotating.
            val arms = if (pat >= 2) 3 else 2
            rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
                for (arm in 0 until arms) {
                    val a0 = arm * (360f / arms)
                    spiralArm(blue.copy(alpha = 0.30f + 0.12f * ctx.breathe), ctx.c, coreR * 0.22f, coreR * 1.18f, a0, 300f, stroke * 0.34f)
                    if (pat >= 1) {
                        spiralArm(blue.copy(alpha = 0.16f), ctx.c, coreR * 0.30f, coreR * 1.05f, a0 + 40f, 260f, stroke * 0.18f)
                    }
                }
            }
            // The star swarm riding the arms.
            val swarm = if (pat >= 3) 18 else if (pat >= 2) 14 else 10
            for (i in 0 until swarm) {
                val h = hash01(i * 3.3f + 0.9f)
                val t = hash01(i * 7.1f + 2.5f)
                val ang = ctx.phase3 * 360f + t * 300f + (i % arms) * (360f / arms)
                val r = coreR * (0.25f + 0.95f * t)
                val tw = twinkle(ctx.phase4, i * 1.1f)
                val pCol = if (pat >= 2 && i % 4 == 0) Color.White else blue
                dot(
                    pCol.copy(alpha = 0.30f + 0.55f * tw),
                    orbitPoint(ctx.c, r, ang + 8f * h),
                    R * (0.010f + 0.012f * tw)
                )
            }
            // A counter-rotating electron ring through the bulge.
            val ry = coreR * 0.45f
            rotate(degrees = -20f + ctx.phase3 * 60f, pivot = ctx.c) {
                scale(scaleX = 1f, scaleY = ry / coreR, pivot = ctx.c) {
                    ringArc(blue.copy(alpha = 0.25f), ctx.c, coreR, 0f, 360f, stroke * 0.16f, StrokeCap.Butt)
                }
            }
            if (pat >= 2) {
                // PATRON+: the core's white lens flare.
                val arm = coreR * 1.25f
                val flareAlpha = 0.24f + 0.18f * ctx.breathe2
                rotate(degrees = ctx.phase3 * 30f, pivot = ctx.c) {
                    drawLine(Color.White.copy(alpha = flareAlpha), Offset(ctx.c.x - arm, ctx.c.y), Offset(ctx.c.x + arm, ctx.c.y), strokeWidth = R * 0.010f, cap = StrokeCap.Round)
                    drawLine(Color.White.copy(alpha = flareAlpha), Offset(ctx.c.x, ctx.c.y - arm), Offset(ctx.c.x, ctx.c.y + arm), strokeWidth = R * 0.010f, cap = StrokeCap.Round)
                }
            }
            if (pat >= 3) {
                // CHAMPION: binary companions in the supporters' colours
                // herding the outer swarm.
                for (i in 0 until 2) {
                    val a = ctx.phase * 360f * (if (i == 0) 1f else -1f) + i * 180f
                    val bc = orbitPoint(ctx.c, coreR * 1.10f, a)
                    val bcCol = if (i == 0) s1 else s2
                    dot(bcCol.copy(alpha = 0.9f), bc, R * 0.032f)
                    star(bcCol.copy(alpha = 0.35f), bc, R * 0.08f)
                }
            }
        }
    }
}
