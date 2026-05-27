package com.example.tail.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An icon-sized button that fires [onClick] on press, then — if held down —
 * rapidly repeats after [initialDelayMs] at [repeatIntervalMs] intervals.
 *
 * Releasing the button or moving the finger away cancels the repeat loop.
 * Visually identical to `IconButton` (48 dp touch target, ripple indication).
 */
@Composable
fun RepeatIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    initialDelayMs: Long = 400L,
    repeatIntervalMs: Long = 80L,
    content: @Composable () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    var repeatJob by remember { mutableStateOf<Job?>(null) }

    // Safety net: cancel any running repeat when leaving composition
    DisposableEffect(Unit) {
        onDispose { repeatJob?.cancel() }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .then(
                if (enabled) {
                    Modifier
                        .indication(interactionSource, ripple(bounded = false, radius = 24.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val press = PressInteraction.Press(down.position)
                                interactionSource.tryEmit(press)

                                // Fire immediately on press
                                currentOnClick()

                                // Start repeat loop in a separate coroutine
                                repeatJob = scope.launch {
                                    delay(initialDelayMs)
                                    while (true) {
                                        currentOnClick()
                                        delay(repeatIntervalMs)
                                    }
                                }

                                // Wait for the finger to lift or the gesture to cancel
                                try {
                                    val up = waitForUpOrCancellation()
                                    if (up != null) {
                                        interactionSource.tryEmit(PressInteraction.Release(press))
                                    } else {
                                        interactionSource.tryEmit(PressInteraction.Cancel(press))
                                    }
                                } finally {
                                    repeatJob?.cancel()
                                    repeatJob = null
                                }
                            }
                        }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
