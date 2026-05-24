package com.example.tail.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small analog clock that shows the time of day for secondary locations.
 *
 * @param timeMinutes Minutes since midnight (0–1439). Determines hand positions.
 *   If null, the hands cycle through a full 24h day rapidly using [spinPhase].
 * @param spinPhase 0f–1f fraction of a 24h day used when [timeMinutes] is null.
 *   The caller animates this from 0→1 to sweep through midnight→midnight.
 * @param accent Colour for the hour hand and tick marks.
 * @param sizeDp Clock diameter in dp (default 52).
 */
@Composable
fun DayClock(
    timeMinutes: Int?,
    spinPhase: Float,
    accent: Color,
    sizeDp: Float = 52f
) {
    val size = sizeDp.dp
    Canvas(modifier = Modifier.size(size)) {
        val cx = size.toPx() / 2f
        val cy = size.toPx() / 2f
        val r = size.toPx() / 2f - 2f

        // Clock face background
        drawCircle(color = Color(0xFF1A1A1A), radius = r, center = Offset(cx, cy))
        // Clock face border
        drawCircle(color = Color(0xFF444444), radius = r, center = Offset(cx, cy), style = Stroke(width = 1.2f))

        // Hour tick marks (12 positions)
        for (i in 0 until 12) {
            val angle = i * 30f - 90f  // 0h at top
            val rad = Math.toRadians(angle.toDouble())
            val innerR = r * 0.78f
            val outerR = r * 0.92f
            val x1 = cx + innerR * cos(rad).toFloat()
            val y1 = cy + innerR * sin(rad).toFloat()
            val x2 = cx + outerR * cos(rad).toFloat()
            val y2 = cy + outerR * sin(rad).toFloat()
            val tickColor = if (i % 3 == 0) accent.copy(alpha = 0.7f) else Color(0xFF555555)
            drawLine(color = tickColor, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = if (i % 3 == 0) 1.5f else 0.8f)
        }

        // Compute hand angles from timeMinutes or spinPhase
        val effectiveMinutes: Float = if (timeMinutes != null) {
            timeMinutes.toFloat()
        } else {
            // spinPhase 0→1 maps to 0→1439 minutes (full 24h cycle)
            spinPhase * 1440f
        }

        val hours = (effectiveMinutes / 60f)
        val mins = effectiveMinutes % 60f
        val hourAngle = (hours % 12f + mins / 60f) * 30f - 90f
        val minuteAngle = mins * 6f - 90f

        // Hour hand
        val hourRad = Math.toRadians(hourAngle.toDouble())
        val hourLen = r * 0.5f
        drawLine(
            color = accent,
            start = Offset(cx, cy),
            end = Offset(cx + hourLen * cos(hourRad).toFloat(), cy + hourLen * sin(hourRad).toFloat()),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round
        )

        // Minute hand
        val minRad = Math.toRadians(minuteAngle.toDouble())
        val minLen = r * 0.7f
        drawLine(
            color = Color(0xFFCCCCCC),
            start = Offset(cx, cy),
            end = Offset(cx + minLen * cos(minRad).toFloat(), cy + minLen * sin(minRad).toFloat()),
            strokeWidth = 1.5f,
            cap = StrokeCap.Round
        )

        // Center dot
        drawCircle(color = accent, radius = 2.5f, center = Offset(cx, cy))
    }
}
