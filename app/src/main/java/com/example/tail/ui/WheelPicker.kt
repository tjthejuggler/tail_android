package com.example.tail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Vertical wheel picker with momentum fling and snap-to-item.
 *
 * Swiping up/down scrolls through items. After release, the picker
 * flings with momentum then snaps to the nearest item.
 *
 * @param items Labels to display (one per slot).
 * @param selectedIndex Currently selected item index.
 * @param onSelectedChange Called when the snapped selection changes.
 * @param itemHeight Height of each item row.
 * @param visibleItems Number of visible items (should be odd for centered selection).
 * @param accent Color for the selected item text and highlight.
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 40.dp,
    visibleItems: Int = 5,
    accent: Color = Color.White
) {
    if (items.isEmpty()) return

    val halfVisible = visibleItems / 2
    val density = LocalDensity.current
    val itemPx = with(density) { itemHeight.toPx() }
    val maxIndex = items.size - 1

    // Plain state for the offset — updated directly during drag (no suspend calls)
    var offsetValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }

    // Animatable used only in LaunchedEffect (regular coroutine scope)
    val animatable = remember { Animatable(offsetValue) }

    // Fling trigger: incrementing counter signals a new fling request
    var flingId by remember { mutableIntStateOf(0) }
    var flingStart by remember { mutableFloatStateOf(0f) }
    var flingVelocity by remember { mutableFloatStateOf(0f) }

    // Handle fling + snap in a regular coroutine scope
    LaunchedEffect(flingId) {
        if (flingId == 0) return@LaunchedEffect
        animatable.stop()
        animatable.snapTo(flingStart)
        if (abs(flingVelocity) > 0.5f) {
            try {
                animatable.animateDecay(
                    initialVelocity = flingVelocity,
                    animationSpec = exponentialDecay(frictionMultiplier = 1.0f)
                )
            } catch (_: CancellationException) {
                return@LaunchedEffect
            }
        }
        val nearest = animatable.value.roundToInt().coerceIn(0, maxIndex)
        if (abs(animatable.value - nearest) > 0.01f) {
            try {
                animatable.animateTo(nearest.toFloat(), tween(150))
            } catch (_: CancellationException) {
                return@LaunchedEffect
            }
        }
        offsetValue = nearest.toFloat()
        onSelectedChange(nearest)
    }

    // Sync animatable value back to offsetValue during animation
    LaunchedEffect(Unit) {
        while (true) {
            if (animatable.isRunning) {
                offsetValue = animatable.value
            }
            kotlinx.coroutines.delay(16)
        }
    }

    // Sync external selectedIndex changes (no animation running)
    LaunchedEffect(selectedIndex) {
        if (!animatable.isRunning && abs(offsetValue - selectedIndex) > 0.01f) {
            animatable.stop()
            animatable.snapTo(offsetValue)
            try {
                animatable.animateTo(selectedIndex.toFloat(), tween(200))
            } catch (_: CancellationException) {
                return@LaunchedEffect
            }
            offsetValue = selectedIndex.toFloat()
        }
    }

    Box(
        modifier = modifier
            .height(itemHeight * visibleItems)
            .clipToBounds()
            .pointerInput(items.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastY = down.position.y
                    var lastTime = down.uptimeMillis
                    var velocity = 0f

                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        for (change in event.changes) {
                            if (!change.pressed) {
                                released = true
                                break
                            }
                            val dy = lastY - change.position.y
                            val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                            val instantV = dy / dt * 1000f
                            velocity = velocity * 0.4f + instantV * 0.6f

                            val indexDelta = dy / itemPx
                            offsetValue = (offsetValue + indexDelta).coerceIn(0f, maxIndex.toFloat())

                            lastY = change.position.y
                            lastTime = change.uptimeMillis
                            change.consume()
                        }
                    }

                    // Trigger fling via state change (no suspend calls here!)
                    flingStart = offsetValue
                    flingVelocity = velocity / itemPx
                    flingId++
                }
            }
    ) {
        // Selection highlight bar
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight)
                .background(accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
        )

        // Render visible items
        for (i in items.indices) {
            val distance = i - offsetValue
            if (abs(distance) > halfVisible + 1.5f) continue

            val absDist = abs(distance)
            val itemAlpha = when {
                absDist < 0.5f -> 1f
                absDist < 1.5f -> 1f - (absDist - 0.5f) * 0.5f
                else -> 0.25f.coerceAtLeast(0.1f)
            }

            key(i) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            translationY = distance * itemPx
                            alpha = itemAlpha
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = items[i],
                        color = if (absDist < 0.5f) accent else Color(0xFF666666),
                        fontSize = if (absDist < 0.5f) 18.sp else 14.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  TimeWheelPicker — three-wheel time selector (Hour · Minute · AM/PM)
// ═══════════════════════════════════════════════════════════════════════════

/** Hour labels for the12-hour wheel. */
private val HOUR_ITEMS_12 = (1..12).map { it.toString() }

/** Minute labels for the minute wheel. */
private val MINUTE_ITEMS_60 = (0..59).map { String.format("%02d", it) }

/** AM/PM labels. */
private val AMPM_ITEMS = listOf("AM", "PM")

/**
 * Convert a24-hour value to a12-hour display value (1-12).
 */
private fun Int.to12Hour(): Int = when {
    this == 0 -> 12
    this > 12 -> this - 12
    else -> this
}

/**
 * Convert a12-hour display value + isPm flag to a24-hour value.
 */
private fun to24Hour(hour12: Int, isPm: Boolean): Int = when {
    hour12 == 12 && !isPm -> 0
    hour12 == 12 && isPm -> 12
    isPm -> hour12 + 12
    else -> hour12
}

/**
 * A stylish three-wheel time picker: scrollable Hour (1-12), Minute (00-59),
 * and AM/PM wheels. Designed to be used alongside quick +/- offset buttons.
 *
 * The wheels show the **absolute** time — scrolling any wheel directly sets
 * the target hour/minute. External state changes (e.g. from +/- buttons)
 * animate the wheels to the new position.
 *
 * @param hour24 Current hour in24-hour format (0-23).
 * @param minute Current minute (0-59).
 * @param onTimeChange Called with the new (hour24, minute) whenever any wheel settles.
 * @param accent Accent colour for selected items and highlight bars.
 * @param itemHeight Height of each wheel row.
 * @param visibleItems Number of visible rows per wheel (should be odd).
 * @param compact When true, uses smaller wheels suitable for inline editors.
 */
@Composable
fun TimeWheelPicker(
    hour24: Int,
    minute: Int,
    onTimeChange: (hour24: Int, minute: Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF88DDFF),
    itemHeight: Dp = 36.dp,
    visibleItems: Int = 5,
    compact: Boolean = false
) {
    val isPm = hour24 >= 12
    val displayHour = hour24.to12Hour()

    val hourWheelWidth = if (compact) 44.dp else 56.dp
    val minuteWheelWidth = if (compact) 50.dp else 60.dp
    val ampmWheelWidth = if (compact) 40.dp else 48.dp
    val colonSize = if (compact) 14.sp else 18.sp
    val wheelItemHeight = if (compact) (itemHeight * 0.85f) else itemHeight
    val wheelVisible = if (compact) 3 else visibleItems

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Hour wheel ──
        WheelPicker(
            items = HOUR_ITEMS_12,
            selectedIndex = displayHour - 1,
            onSelectedChange = { idx ->
                val h12 = idx + 1
                onTimeChange(to24Hour(h12, isPm), minute)
            },
            itemHeight = wheelItemHeight,
            visibleItems = wheelVisible,
            accent = accent,
            modifier = Modifier.width(hourWheelWidth)
        )

        Text(
            text = ":",
            color = Color(0xFF888888),
            fontSize = colonSize,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        // ── Minute wheel ──
        WheelPicker(
            items = MINUTE_ITEMS_60,
            selectedIndex = minute,
            onSelectedChange = { m ->
                onTimeChange(hour24, m)
            },
            itemHeight = wheelItemHeight,
            visibleItems = wheelVisible,
            accent = accent,
            modifier = Modifier.width(minuteWheelWidth)
        )

        // ── AM/PM wheel ──
        WheelPicker(
            items = AMPM_ITEMS,
            selectedIndex = if (isPm) 1 else 0,
            onSelectedChange = { idx ->
                val newIsPm = idx == 1
                onTimeChange(to24Hour(displayHour, newIsPm), minute)
            },
            itemHeight = wheelItemHeight,
            visibleItems = wheelVisible,
            accent = accent,
            modifier = Modifier.width(ampmWheelWidth)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  DurationWheelPicker — two-wheel length selector (Hours · Minutes)
// ═══════════════════════════════════════════════════════════════════════════

/** Hour labels for the duration wheel (0–10 h covers movies, episodes and binges). */
private val DURATION_HOUR_ITEMS = (0..10).map { it.toString() }

/** Minute labels for the duration wheel. */
private val DURATION_MINUTE_ITEMS = (0..59).map { String.format("%02d", it) }

/**
 * A two-wheel duration picker: scrollable Hours (0-10) and Minutes (00-59).
 * Used for the suggested movie/episode watch-length in the text-input dialog,
 * so the length is editable with the same wheel interaction as times.
 *
 * @param totalMinutes Current duration in total minutes (e.g. 92).
 * @param onDurationChange Called with the new total minutes whenever a wheel settles.
 */
@Composable
fun DurationWheelPicker(
    totalMinutes: Int,
    onDurationChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF88DDFF),
    itemHeight: Dp = 36.dp,
    visibleItems: Int = 5
) {
    val hours = (totalMinutes / 60).coerceIn(0, 10)
    val minutes = (totalMinutes % 60).coerceIn(0, 59)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Hours wheel ──
        WheelPicker(
            items = DURATION_HOUR_ITEMS,
            selectedIndex = hours,
            onSelectedChange = { h ->
                onDurationChange(h * 60 + minutes)
            },
            itemHeight = itemHeight,
            visibleItems = visibleItems,
            accent = accent,
            modifier = Modifier.width(52.dp)
        )

        Text(
            text = "h",
            color = Color(0xFF888888),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 3.dp)
        )

        // ── Minutes wheel ──
        WheelPicker(
            items = DURATION_MINUTE_ITEMS,
            selectedIndex = minutes,
            onSelectedChange = { m ->
                onDurationChange(hours * 60 + m)
            },
            itemHeight = itemHeight,
            visibleItems = visibleItems,
            accent = accent,
            modifier = Modifier.width(56.dp)
        )

        Text(
            text = "m",
            color = Color(0xFF888888),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 3.dp)
        )
    }
}
