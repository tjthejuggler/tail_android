package com.example.tail.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * a PATRON's spark becomes a miniature trinity of orbiting light.
 *
 *   tier 0 — dormant (patrons can summon a ghost)
 *   tier 1 — a tiny dot ignites (breathes early with a patron)
 *   tier 2 — breathing (+ cross / first moon with patrons)
 *   tier 3 — sparkle cross (+ moons early with patrons)
 *   tier 4 — first micro-moon (+ halo ring & glints with patrons)
 *   tier 5 — twin moons (+ a swarm with patrons)
 *   tier 6 — ripples overflow (+ diamond core & orbit ring at PATRON)
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
}
