package com.example.tail.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
