package com.example.tail.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// ── Palette (mirrors AppStatsScreen / GraphsScreen tones) ─────────────────────

private val SectionBg = Color(0xFF1A1A2E)
private val SectionTitleColor = Color(0xFFFFD700)
private val LabelColor = Color(0xFFADD8E6)
private val ValueColor = Color.White
private val DimColor = Color(0xFF888888)
private val AxisColor = Color(0xFF333344)
private val HighlightGold = Color(0xFFFFD700)

// ── Section card ──────────────────────────────────────────────────────────────

/**
 * Rounded dark card with a gold emoji-titled header — identical visual
 * language to the sections on the App Stats screen.
 */
@Composable
fun StatsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(SectionBg, RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = SectionTitleColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            trailing?.invoke()
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}

// ── Summary chip ──────────────────────────────────────────────────────────────

/** Compact summary tile: big value over a small label, used in the header row. */
@Composable
fun StatChip(
    value: String,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Color(0xFF12121F), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = accent,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = label,
            color = DimColor,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

// ── Bar chart ─────────────────────────────────────────────────────────────────

/**
 * Minimal, stylish bar chart drawn on a [Canvas] (same technique as the
 * habit graphs screen: nativeCanvas text for crisp labels).
 *
 * Interactions:
 *  • Tap a bar → [onBarTap] with its index (drives the detail popup).
 *  • Horizontal drag → [onPanBuckets] with a signed bucket count; positive
 *    means "pan to older data" (finger moved right). Vertical drags pass
 *    through to the surrounding scrollable column.
 *
 * @param values one bar per period, in chronological order
 * @param labels x-axis label per bar (thinned automatically to fit, based on
 *        the real measured width of the labels — narrow labels like 2-digit
 *        years can appear under every bar)
 * @param barColor bar fill; the LAST bar is gold (the "current" period)
 * @param valueFormatter formats the value drawn above the tallest bars
 * @param selectedIndex bar tapped last (drawn gold while its popup is open)
 * @param canPan enables the horizontal-drag window panning
 * @param axisTextSize font size for the x-axis labels; slightly smaller for
 *        dense charts (e.g. the "All" range)
 */
@Composable
fun StatsBarChart(
    values: List<Float>,
    labels: List<String>,
    barColor: Color,
    modifier: Modifier = Modifier,
    valueFormatter: (Float) -> String = { it.roundToInt().toString() },
    selectedIndex: Int? = null,
    canPan: Boolean = false,
    onPanBuckets: (Int) -> Unit = {},
    onBarTap: (Int) -> Unit = {},
    axisTextSize: TextUnit = 11.sp
) {
    val density = LocalDensity.current
    // sp-based sizes so the axis text is comfortably readable, unlike raw
    // pixel-sized paint text which ends up tiny on high-density screens.
    val labelTextPx = with(density) { axisTextSize.toPx() }
    val valueTextPx = with(density) { 10.sp.toPx() }

    // Gesture handlers must always call the LATEST callbacks. The
    // pointerInput blocks below are keyed on the list SIZE, which does not
    // change while panning a fixed window (e.g. the 30-bucket 1M chart) —
    // without this, taps fired stale closures that captured the pre-pan
    // window, so bars reported the wrong day after swiping.
    val currentOnBarTap by rememberUpdatedState(onBarTap)
    val currentOnPanBuckets by rememberUpdatedState(onPanBuckets)

    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(0xAD, 0xD8, 0xE6)
        textSize = labelTextPx
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val valuePaint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = valueTextPx
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp + with(density) { 24.dp })
            .pointerInput(values.size) {
                detectTapGestures { offset ->
                    if (values.isEmpty()) return@detectTapGestures
                    val slotPx = size.width.toFloat() / values.size
                    if (slotPx <= 0f) return@detectTapGestures
                    val idx = (offset.x / slotPx).toInt()
                    if (idx in values.indices) currentOnBarTap(idx)
                }
            }
            .pointerInput(canPan, values.size) {
                if (!canPan || values.isEmpty()) return@pointerInput
                var dragAccum = 0f
                detectHorizontalDragGestures { change, amount ->
                    change.consume()
                    dragAccum += amount
                    val slotPx = size.width.toFloat() / values.size
                    if (slotPx > 0f) {
                        val buckets = (dragAccum / slotPx).toInt()
                        if (buckets != 0) {
                            currentOnPanBuckets(buckets)
                            dragAccum -= buckets * slotPx
                        }
                    }
                }
            }
    ) {
        if (values.isEmpty()) return@Canvas
        val maxValue = max(values.max(), 0.001f)

        val labelZone = 22.dp.toPx()
        val chartBottom = size.height - labelZone
        val barAreaTop = 16.dp.toPx() // room for value labels above bars
        val chartHeightPx = (chartBottom - barAreaTop).coerceAtLeast(1f)

        // ── Baseline ──
        drawLine(
            color = AxisColor,
            start = Offset(0f, chartBottom),
            end = Offset(size.width, chartBottom),
            strokeWidth = 1.dp.toPx()
        )

        val barCount = values.size
        val gap = 3.dp.toPx()
        val slot = size.width / barCount
        val barWidth = max(slot - gap, 2.dp.toPx())

        // Thin x-labels + value labels so they never overlap. The stride is
        // derived from the REAL measured width of the widest label, so
        // narrow labels (2-digit years, bare day numbers) get a label under
        // every bar while wide ones ("Aug 24") thin out as needed.
        val maxLabelWidth = labels.maxOfOrNull { labelPaint.measureText(it) } ?: 0f
        val minSlotPerLabel = maxLabelWidth + 6.dp.toPx()
        val labelEvery = max(ceil(minSlotPerLabel / slot).toInt(), 1)
        val valueEvery = if (barCount > 24) labelEvery else 1

        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barWidth) / 2f
            val h = (v / maxValue) * chartHeightPx
            if (h > 0f) {
                val base = when {
                    i == selectedIndex -> HighlightGold
                    i == barCount - 1 -> HighlightGold.copy(alpha = 0.85f)
                    else -> barColor
                }
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(base.copy(alpha = 0.95f), base.copy(alpha = 0.45f)),
                        startY = chartBottom - h,
                        endY = chartBottom
                    ),
                    topLeft = Offset(x, chartBottom - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
                // Value above notable bars (tallest or ≥60% of max, thinned)
                if (v >= maxValue * 0.6f && i % valueEvery == 0) {
                    drawContext.canvas.nativeCanvas.drawText(
                        valueFormatter(v),
                        x + barWidth / 2f,
                        chartBottom - h - 4.dp.toPx(),
                        valuePaint
                    )
                }
            }
            // X label under the bar (strict stride — never forced onto the
            // last bar, which used to collide with its neighbour on 1M/1Y)
            if (i % labelEvery == 0) {
                drawContext.canvas.nativeCanvas.drawText(
                    labels.getOrElse(i) { "" },
                    x + barWidth / 2f,
                    (chartBottom + labelZone) - 5.dp.toPx(),
                    labelPaint
                )
            }
        }
    }
}

// ── Leaderboard rows ──────────────────────────────────────────────────────────

/**
 * One "top place" row: rank, name, days count and a proportional progress
 * bar in the section's accent colour.
 */
@Composable
fun TopPlaceRow(
    rank: Int,
    name: String,
    days: Int,
    maxDays: Int,
    accent: Color,
    trailingLabel: String = "d"
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$rank",
                    color = DimColor,
                    fontSize = 11.sp,
                    modifier = Modifier.width(18.dp)
                )
                Text(
                    text = name,
                    color = LabelColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                text = "$days $trailingLabel",
                color = ValueColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0xFF26263A), RoundedCornerShape(2.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = if (maxDays > 0) (days.toFloat() / maxDays) else 0f)
                    .height(4.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
        }
    }
}
