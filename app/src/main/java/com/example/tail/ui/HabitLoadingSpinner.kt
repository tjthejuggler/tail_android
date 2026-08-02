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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Tiered loading spinner for the habits screen.
 *
 * The animation's beauty/sophistication scales with today's habit point count,
 * mirroring the 7 colour tiers used across the app:
 *
 *   <14   red     · 14-20 orange · 21-30 green · 31-41 blue
 *   42-48 pink    · 49-55 yellow · 56+   white
 *
 * The higher the tier, the more elaborate the animation. Each tier reuses the
 * vivid `Border*` palette from [HabitColors.kt] so the spinner always matches
 * the colour identity of the day it is loading.
 *
 * Boundaries intentionally match [accentColorForPoints] in MapScreen.kt.
 */

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
private fun tierAccent(tier: Int): Color = when (tier) {
    6 -> BorderGlass
    5 -> BorderYellow
    4 -> BorderPink
    3 -> BorderBlue
    2 -> BorderGreen
    1 -> BorderOrange
    else -> BorderRed
}

/**
 * A loading spinner whose colour and sophistication are chosen by [points].
 *
 * @param points today's total habit points (sum of effective per-habit counts).
 * @param size   overall diameter of the spinner.
 */
@Composable
fun HabitLoadingSpinner(
    points: Int,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp
) {
    val tier = habitPointsTier(points)
    val accent = tierAccent(tier)

    val transition = rememberInfiniteTransition(label = "habitSpinner")

    // Continuous 0→1 phase, one full revolution per 1400ms.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    // Slower secondary phase for counter-rotation / shimmer.
    val phase2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
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
        val c = center
        val radius = this.size.minDimension / 2f
        val ringRadius = radius * 0.78f
        val stroke = radius * 0.10f

        when (tier) {
            // ── 0 · RED — a single humble pulsing arc ───────────────────────
            0 -> {
                val sweep = 90f + 120f * breathe
                drawArc(
                    color = accent,
                    startAngle = phase * 360f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }

            // ── 1 · ORANGE — dual counter-rotating arcs ─────────────────────
            1 -> {
                drawArc(
                    color = accent,
                    startAngle = phase * 360f,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent.copy(alpha = 0.55f),
                    startAngle = -phase2 * 360f + 180f,
                    sweepAngle = 110f,
                    useCenter = false,
                    style = Stroke(width = stroke * 0.7f, cap = StrokeCap.Round)
                )
            }

            // ── 2 · GREEN — orbiting comet with a fading tail ───────────────
            2 -> {
                val headAngle = phase * 360f
                // Tail: a swept arc that trails the head, fading out.
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1f to accent,
                        center = c
                    ),
                    startAngle = headAngle - 130f,
                    sweepAngle = 130f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // Comet head.
                val a = Math.toRadians(headAngle.toDouble())
                drawCircle(
                    color = accent,
                    radius = stroke * 0.9f,
                    center = Offset(
                        (c.x + ringRadius * cos(a)).toFloat(),
                        (c.y + ringRadius * sin(a)).toFloat()
                    )
                )
            }

            // ── 3 · BLUE — three phase-offset orbiting dots ─────────────────
            3 -> {
                // Faint guide ring for depth.
                drawCircle(
                    color = accent.copy(alpha = 0.18f),
                    radius = ringRadius,
                    style = Stroke(width = stroke * 0.4f)
                )
                for (i in 0 until 3) {
                    val ang = phase * 360f + i * 120f
                    val a = Math.toRadians(ang.toDouble())
                    val dotR = stroke * (1.0f - i * 0.18f)
                    drawCircle(
                        color = accent.copy(alpha = 1f - i * 0.28f),
                        radius = dotR,
                        center = Offset(
                            (c.x + ringRadius * cos(a)).toFloat(),
                            (c.y + ringRadius * sin(a)).toFloat()
                        )
                    )
                }
            }

            // ── 4 · PINK — breathing ring + rotating dashed arc ─────────────
            4 -> {
                val pulse = 0.85f + 0.15f * breathe
                drawCircle(
                    color = accent.copy(alpha = 0.25f + 0.35f * breathe),
                    radius = ringRadius * pulse,
                    style = Stroke(width = stroke * 0.5f)
                )
                // Dashed arc: 6 evenly spaced segments rotating around.
                val seg = 32f
                val gap = 28f
                rotate(degrees = phase * 360f, pivot = c) {
                    var start = 0f
                    repeat(6) {
                        drawArc(
                            color = accent,
                            startAngle = start,
                            sweepAngle = seg,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )
                        start += seg + gap
                    }
                }
            }

            // ── 5 · YELLOW — twin comets + shimmering sweep ring ────────────
            5 -> {
                // Shimmering gradient ring slowly rotating opposite the comets.
                rotate(degrees = -phase2 * 360f, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                accent.copy(alpha = 0.1f),
                                accent.copy(alpha = 0.9f),
                                accent.copy(alpha = 0.1f)
                            ),
                            center = c
                        ),
                        radius = ringRadius,
                        style = Stroke(width = stroke * 0.6f)
                    )
                }
                // Twin comets 180° apart, each with a short tail.
                for (i in 0 until 2) {
                    val headAngle = phase * 360f + i * 180f
                    drawArc(
                        color = accent.copy(alpha = 0.5f),
                        startAngle = headAngle - 60f,
                        sweepAngle = 60f,
                        useCenter = false,
                        style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round)
                    )
                    val a = Math.toRadians(headAngle.toDouble())
                    drawCircle(
                        color = accent,
                        radius = stroke * 0.85f,
                        center = Offset(
                            (c.x + ringRadius * cos(a)).toFloat(),
                            (c.y + ringRadius * sin(a)).toFloat()
                        )
                    )
                }
            }

            // ── 6 · WHITE — kaleidoscope: gradient ring, 8 sparkling ────────
            //     orbiting particles and a pulsing core glow.
            else -> {
                // Rotating full-spectrum white/glass gradient ring.
                rotate(degrees = phase * 360f, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                accent.copy(alpha = 0.15f),
                                Color.White,
                                accent.copy(alpha = 0.6f),
                                Color.White,
                                accent.copy(alpha = 0.15f)
                            ),
                            center = c
                        ),
                        radius = ringRadius,
                        style = Stroke(width = stroke * 0.7f)
                    )
                }
                // 8 sparkling particles orbiting, each twinkling on its own phase.
                for (i in 0 until 8) {
                    val ang = -phase2 * 360f + i * 45f
                    val a = Math.toRadians(ang.toDouble())
                    val twinkle = 0.5f + 0.5f * sin((phase * 2 * Math.PI + i).toFloat()).let {
                        kotlin.math.abs(it)
                    }
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f + 0.65f * twinkle),
                        radius = stroke * (0.35f + 0.45f * twinkle),
                        center = Offset(
                            (c.x + ringRadius * cos(a)).toFloat(),
                            (c.y + ringRadius * sin(a)).toFloat()
                        )
                    )
                }
                // Pulsing core glow.
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.9f),
                        1f to Color.Transparent,
                        center = c,
                        radius = radius * (0.3f + 0.25f * breathe)
                    ),
                    radius = radius * (0.3f + 0.25f * breathe),
                    center = c
                )
            }
        }
    }
}
