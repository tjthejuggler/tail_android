package com.example.tail.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tail.data.AdviceItem

/**
 * A thin banner that shows a random piece of advice.
 * Swipe left → previous advice, swipe right → next random advice.
 * Tap → opens a note dialog to write thoughts about this advice.
 * Hidden when no advice exists.
 *
 * Shows up to 5 visible lines; longer text is silently scrollable with no
 * visible scrollbar so the UI stays clean.
 */
@Composable
fun AdviceBanner(
    viewModel: AdviceViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Randomize advice every time this screen becomes the active destination.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.randomizeOnEntry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val list = state.items
    if (list.isEmpty()) return

    val idx = state.currentIndex
    val advice = list.getOrNull(idx) ?: return

    // Track swipe direction for animation
    var swipeDirection by remember { mutableIntStateOf(0) }

    // Track which advice item to show notes dialog for
    var noteDialogAdvice by remember { mutableStateOf<AdviceItem?>(null) }

    // 5 lines × 16sp lineHeight ≈ 80dp content + 12dp vertical padding = ~92dp max
    val maxBannerHeight = 92.dp

    AnimatedContent(
        targetState = advice.id to advice.text,
        transitionSpec = {
            if (swipeDirection >= 0) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "advice_banner"
    ) { (_, text) ->
        var dragTotal by remember { mutableFloatStateOf(0f) }
        var dragged by remember { mutableStateOf(false) }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxBannerHeight)
                .background(Color(0xFF1E1E1E).copy(alpha = 0.85f))
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dragTotal > 80f) {
                                // Swiped right → previous
                                swipeDirection = -1
                                viewModel.previous()
                                dragged = true
                            } else if (dragTotal < -80f) {
                                // Swiped left → next random
                                swipeDirection = 1
                                viewModel.nextRandom()
                                dragged = true
                            }
                            dragTotal = 0f
                        },
                        onDragCancel = { dragTotal = 0f }
                    ) { _, dragAmount ->
                        dragTotal += dragAmount
                    }
                }
                .clickable {
                    // Only open note dialog if we didn't just finish a swipe
                    if (!dragged) {
                        noteDialogAdvice = advice
                    }
                    dragged = false
                }
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$text".toMarkdownAnnotatedString(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 16.sp
                ),
                color = Color(0xFFD0D0D0),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // ── Note dialog ─────────────────────────────────────────────────────────
    noteDialogAdvice?.let { adviceItem ->
        AdviceNoteDialog(
            adviceText = adviceItem.text,
            currentNotes = adviceItem.notes,
            onSave = { notes ->
                viewModel.saveNotes(adviceItem.id, notes)
            },
            onDismiss = { noteDialogAdvice = null }
        )
    }
}
