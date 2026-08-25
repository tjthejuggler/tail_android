package com.example.tail.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  THE ORRERY II — DAILY SPARK (the heart of the piece)
 * ═══════════════════════════════════════════════════════════════════════
 *
 * The small central accent, owned by TODAY's points tier. A modest but
 * personal touch — the day's own colour burning at the heart of the
 * orrery. Patronage from the monthly and weekly tiers coaxes it to
 * greater life: an ALLY's spark breathes earlier and gains moons sooner;
 * a PATRON's spark becomes a miniature trinity of orbiting light; a
 * CHAMPION's spark becomes a beacon.
 *
 *   tier 0 — dormant (patrons can summon a ghost)
 *   tier 1 — a tiny dot ignites (breathes early with a patron)
 *   tier 2 — breathing (+ cross / first moon with patrons)
 *   tier 3 — sparkle cross (+ moons early with patrons)
 *   tier 4 — first micro-moon (+ halo ring & glints with patrons)
 *   tier 5 — twin moons (+ a swarm with patrons)
 *   tier 6 — ripples overflow (+ diamond core & orbit ring at PATRON)
 *
 * The WHITE+COLOUR combo tiers — the daily spark is the only layer that
 * can climb the whole ladder to white/yellow. The core stays WHITE
 * (the pale glass tint) while the vivid combo hue paints the escalating
 * phenomena; each tier ADDS a new phenomenon on top of the last:
 *
 *   tier 7  — white/red    "Nova Heart"   red pulse rings + orbiting sparks
 *   tier 8  — white/orange "Solar Flare"  orange flare arcs leap from the core
 *   tier 9  — white/green  "Verdant Core" green petals breathe around the core
 *   tier 10 — white/blue   "Micro Galaxy" blue spiral arms + electron ring
 *   tier 11 — white/pink   "Prism Heart"  pink refracted beams rotate through
 *   tier 12 — white/yellow "Star of Dawn" golden rays, crown & ripples —
 *                                 the blazing summit of a perfect day
 */

internal fun DrawScope.drawDailySpark(ctx: LoadingPaintContext) {
    val R = ctx.radius
    val pat = ctx.tiers.dayPatronage
    val col = patronTint(ctx.dayColor, pat)
    val s1 = ctx.monthColor   // the supporters
    val s2 = ctx.weekColor
    val d = ctx.tiers.daily

    // ── tier 0 — the dormant heart ────────────────────────────────────
    if (d == 0) {
        if (pat >= 1) {
            dot(
                col.copy(alpha = 0.10f + 0.10f * twinkle(ctx.phase4, 0.5f)),
                ctx.c,
                R * 0.030f
            )
        }
        if (pat >= 2) {
            ringArc(col.copy(alpha = 0.08f), ctx.c, R * 0.15f, 0f, 360f, R * 0.010f, StrokeCap.Butt)
        }
        return
    }

    // ── the spark itself ──────────────────────────────────────────────
    // Steady at tier 1 (unless an ally lends it breath), breathing onward.
    val pulse = if (d >= 2 || pat >= 1) ctx.breathe else 0.5f
    val sparkR = R * (0.042f + 0.022f * pulse) * (1f + 0.08f * pat)
    dot(col.copy(alpha = 0.65f + 0.35f * pulse), ctx.c, sparkR)

    // ── sparkle cross ─────────────────────────────────────────────────
    if (d >= 3 || (d == 2 && pat >= 2)) {
        val arm = R * (0.10f + 0.05f * ctx.breathe)
        val alpha = 0.35f + 0.45f * ctx.breathe
        rotate(degrees = 45f + ctx.phase3 * 90f, pivot = ctx.c) {
            drawLine(
                col.copy(alpha = alpha),
                androidx.compose.ui.geometry.Offset(ctx.c.x - arm, ctx.c.y),
                androidx.compose.ui.geometry.Offset(ctx.c.x + arm, ctx.c.y),
                strokeWidth = R * 0.016f,
                cap = StrokeCap.Round
            )
            drawLine(
                col.copy(alpha = alpha),
                androidx.compose.ui.geometry.Offset(ctx.c.x, ctx.c.y - arm),
                androidx.compose.ui.geometry.Offset(ctx.c.x, ctx.c.y + arm),
                strokeWidth = R * 0.016f,
                cap = StrokeCap.Round
            )
        }
    }

    // ── micro-moons ───────────────────────────────────────────────────
    // Tier grants the first moons; patrons lend more, and a PATRON's
    // moons alternate through the supporters' colours.
    val baseMoons = when {
        d >= 6 -> 3
        d == 5 -> 2
        d == 4 -> 1
        else -> 0
    }
    val moons = baseMoons + when {
        pat >= 2 -> 2
        pat >= 1 -> 1
        else -> 0
    }
    if (moons > 0) {
        val moonR = R * 0.22f
        for (i in 0 until moons) {
            val dir = if (i % 2 == 0) 1f else -1f
            val base = if (i % 2 == 0) ctx.phase else ctx.phase2
            val speed = if (i % 2 == 0) 1.5f else 0.9f
            val a = dir * base * 360f * speed + i * (360f / moons)
            val mCol = if (pat >= 2 && i >= baseMoons) {
                when (i % 2) { 1 -> s1; else -> s2 }
            } else col
            dot(mCol.copy(alpha = 0.9f), orbitPoint(ctx.c, moonR, a), R * 0.032f)
        }
    }

    // ── halo ring ─────────────────────────────────────────────────────
    if ((d >= 4 && pat >= 1) || d >= 5) {
        ringArc(
            col.copy(alpha = 0.15f + 0.10f * ctx.breathe),
            ctx.c, R * 0.28f, 0f, 360f, R * 0.010f, StrokeCap.Butt
        )
    }

    // ── supporter glints ──────────────────────────────────────────────
    // Tiny stars in the monthly & weekly colours twinkling on the moon orbit.
    if (d >= 4 && pat >= 2) {
        for (i in 0 until 2) {
            val a = ctx.phase3 * 360f + i * 180f + 60f
            val p = orbitPoint(ctx.c, R * 0.22f, a)
            star(
                (if (i == 0) s1 else s2).copy(alpha = 0.30f + 0.40f * twinkle(ctx.phase4, i * 1.8f)),
                p,
                R * 0.045f
            )
        }
    }

    // ── ripples — the spark's energy overflows ────────────────────────
    if (d >= 6) {
        val ripples = 2 + pat
        for (k in 0 until ripples) {
            val p = (ctx.phase + k / ripples.toFloat()) % 1f
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

    // ── PATRON's diamond core — a miniature star at the heart ─────────
    if (d >= 6 && pat >= 2) {
        ringArc(col.copy(alpha = 0.12f), ctx.c, R * 0.30f, 0f, 360f, R * 0.008f, StrokeCap.Butt)
        rotate(degrees = ctx.phase3 * 90f, pivot = ctx.c) {
            star(lerp(col, Color.White, 0.6f).copy(alpha = 0.7f), ctx.c, R * (0.07f + 0.03f * ctx.breathe2))
        }
    }

    // ═══ THE WHITE+COLOUR COMBO TIERS (7–12) ═══════════════════════════
    // The core now burns white; each tier's vivid combo hue adds a new
    // phenomenon, stacking toward the Star of Dawn.

    val combo = ctx.dayCombo

    // ── tier 7 — Nova Heart (white/red) ───────────────────────────────
    // Red pulse rings radiate from the white core while red sparks
    // orbit it with tails of fire.
    if (d >= 7) {
        val pulses = 2 + (if (pat >= 2) 1 else 0)
        for (k in 0 until pulses) {
            val p = (ctx.phase * 1.3f + k / pulses.toFloat()) % 1f
            ringArc(
                combo.copy(alpha = 0.40f * (1f - p)),
                ctx.c,
                R * (0.08f + 0.30f * p),
                0f, 360f,
                R * 0.012f,
                StrokeCap.Butt
            )
        }
        val sparks = if (pat >= 1) 4 else 3
        for (i in 0 until sparks) {
            val a = ctx.phase2 * 360f + i * (360f / sparks)
            cometTail(combo.copy(alpha = 0.6f), ctx.c, R * 0.34f, a, 24f, R * 0.012f, segments = 2)
            dot(combo.copy(alpha = 0.85f), orbitPoint(ctx.c, R * 0.34f, a), R * 0.020f)
        }
    }

    // ── tier 8 — Solar Flare (white/orange) ───────────────────────────
    // Orange flare arcs leap off the white core, rotating like a tiny
    // sun in the middle of the orrery.
    if (d >= 8) {
        val flares = if (pat >= 2) 4 else 3
        for (k in 0 until flares) {
            val a = ctx.phase * 360f * 1.2f + k * (360f / flares)
            val p0 = orbitPoint(ctx.c, R * 0.06f, a)
            val p1 = orbitPoint(ctx.c, R * (0.16f + 0.06f * ctx.breathe), a + 55f)
            val mid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
            val dir = Offset(mid.x - ctx.c.x, mid.y - ctx.c.y)
            val len = kotlin.math.hypot(dir.x, dir.y).coerceAtLeast(1f)
            val reach = R * (0.14f + 0.06f * ctx.breathe)
            val ctrl = Offset(mid.x + dir.x / len * reach, mid.y + dir.y / len * reach)
            val path = Path()
            path.moveTo(p0.x, p0.y)
            path.quadraticTo(ctrl.x, ctrl.y, p1.x, p1.y)
            drawPath(
                path,
                combo.copy(alpha = 0.45f + 0.25f * ctx.breathe),
                style = Stroke(width = R * 0.010f, cap = StrokeCap.Round)
            )
            dot(combo.copy(alpha = 0.7f), p1, R * 0.012f)
        }
        ringArc(combo.copy(alpha = 0.20f + 0.10f * ctx.breathe), ctx.c, R * 0.38f, 0f, 360f, R * 0.008f, StrokeCap.Butt)
    }

    // ── tier 9 — Verdant Core (white/green) ───────────────────────────
    // Green petals breathe around the white core — the heart has become
    // a small living flower.
    if (d >= 9) {
        val petals = if (pat >= 1) 6 else 4
        for (i in 0 until petals) {
            val a = -ctx.phase2 * 360f + i * (360f / petals)
            val breathePetal = 0.5f + 0.5f * kotlin.math.sin((ctx.breathe * Math.PI + i * 1.2f).toDouble()).toFloat()
            dot(
                combo.copy(alpha = 0.35f + 0.40f * breathePetal),
                orbitPoint(ctx.c, R * (0.26f + 0.05f * breathePetal), a),
                R * (0.016f + 0.012f * breathePetal)
            )
        }
        ringArc(combo.copy(alpha = 0.25f + 0.15f * ctx.breathe2), ctx.c, R * 0.42f, 0f, 360f, R * 0.010f, StrokeCap.Butt)
    }

    // ── tier 10 — Micro Galaxy (white/blue) ───────────────────────────
    // Blue spiral arms wind out of the white core — a galaxy in miniature.
    if (d >= 10) {
        val arms = if (pat >= 2) 3 else 2
        rotate(degrees = ctx.phase3 * 360f, pivot = ctx.c) {
            for (arm in 0 until arms) {
                spiralArm(
                    combo.copy(alpha = 0.30f + 0.12f * ctx.breathe),
                    ctx.c,
                    R * 0.06f, R * 0.44f, arm * (360f / arms), 260f,
                    R * 0.008f
                )
            }
        }
        // An electron racing around a precessing tilted orbit.
        val tilt = 25f + ctx.phase3 * 80f
        val theta = ctx.phase * 360f * 2f
        dot(combo.copy(alpha = 0.8f), ellipsePoint(ctx.c, R * 0.36f, R * 0.16f, tilt, theta), R * 0.014f)
    }

    // ── tier 11 — Prism Heart (white/pink) ────────────────────────────
    // Pink refracted beams rotate through the white core, as if the
    // spark were a prism splitting the orrery's light.
    if (d >= 11) {
        val beams = if (pat >= 1) 6 else 4
        rotate(degrees = ctx.phase2 * 180f, pivot = ctx.c) {
            for (i in 0 until beams) {
                val a = Math.toRadians((i * 360f / beams + 22.5f * ctx.breathe).toDouble())
                val inner = R * 0.05f
                val outer = R * (0.30f + 0.08f * ctx.breathe2)
                drawLine(
                    color = combo.copy(alpha = 0.30f + 0.20f * ctx.breathe2),
                    start = Offset((ctx.c.x + inner * kotlin.math.cos(a)).toFloat(), (ctx.c.y + inner * kotlin.math.sin(a)).toFloat()),
                    end = Offset((ctx.c.x + outer * kotlin.math.cos(a)).toFloat(), (ctx.c.y + outer * kotlin.math.sin(a)).toFloat()),
                    strokeWidth = R * 0.009f,
                    cap = StrokeCap.Round
                )
            }
        }
        for (i in 0 until 3) {
            val a = ctx.phase4 * 360f + i * 120f
            star(combo.copy(alpha = 0.35f + 0.25f * twinkle(ctx.phase4, i * 2.0f)), orbitPoint(ctx.c, R * 0.40f, a), R * 0.035f)
        }
    }

    // ── tier 12 — Star of Dawn (white/yellow) ─────────────────────────
    // The summit of a perfect day: golden rays, a circling crown of
    // golden sparkles and rippling dawn-light around a blazing white
    // heart. Grander than everything beneath it combined.
    if (d >= 12) {
        // The golden rays — a great slow-turning star.
        rotate(degrees = ctx.phase3 * 120f, pivot = ctx.c) {
            star(lerp(combo, Color.White, 0.35f).copy(alpha = 0.55f + 0.25f * ctx.breathe2), ctx.c, R * (0.16f + 0.04f * ctx.breathe2))
        }
        // The crown — golden sparkles circling the heart.
        val crown = if (pat >= 2) 6 else 4
        for (i in 0 until crown) {
            val a = ctx.phase2 * 360f + i * (360f / crown)
            val tw = twinkle(ctx.phase4, i * 1.5f)
            star(
                combo.copy(alpha = 0.45f + 0.45f * tw),
                orbitPoint(ctx.c, R * 0.46f, a),
                R * (0.030f + 0.020f * tw)
            )
        }
        // Dawn ripples rolling outward in gold.
        for (k in 0 until 3) {
            val p = (ctx.phase + k / 3f) % 1f
            ringArc(
                combo.copy(alpha = 0.35f * (1f - p)),
                ctx.c,
                R * (0.10f + 0.42f * p),
                0f, 360f,
                R * 0.012f,
                StrokeCap.Butt
            )
        }
        // The blazing white heart of dawn.
        val dawnGlow = R * (0.10f + 0.05f * ctx.breathe2)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.55f + 0.30f * ctx.breathe2),
                1f to Color.Transparent,
                center = ctx.c,
                radius = dawnGlow
            ),
            radius = dawnGlow,
            center = ctx.c
        )
        dot(Color.White, ctx.c, R * 0.030f)
    }
}
