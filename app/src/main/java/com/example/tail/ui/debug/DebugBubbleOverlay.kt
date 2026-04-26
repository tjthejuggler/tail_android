package com.example.tail.ui.debug

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.debug.DebugNoteRepository
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.data.debug.NoteType
import com.example.tail.data.debug.ScreenContextMapper
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Semantic colors for the debug bubble indicators
private val BubbleCyan = Color(0xFF00BCD4)
private val BubbleOrange = Color(0xFFFF9800)
private val BubbleGreen = Color(0xFF4CAF50)
private val BubbleRed = Color(0xFFF44336)

/**
 * A draggable floating bubble overlay for the debug mode.
 *
 * - Draggable around the screen
 * - Shows a bright pulsing badge with the count of notes on the current screen
 * - Tapping opens [DebugNoteDialog] with Save/Queue/Submit flow
 * - Only visible when debug mode is enabled in settings
 */
@Composable
fun DebugBubbleOverlay(
    currentRoute: String?,
    debugPrefs: DebugPreferences,
    debugNoteRepo: DebugNoteRepository
) {
    val prefsSnapshot by debugPrefs.snapshot.collectAsState()
    if (!prefsSnapshot.debugModeEnabled) return

    val screenContext = remember(currentRoute) {
        ScreenContextMapper.resolve(currentRoute)
    }

    val queue by debugNoteRepo.queue.collectAsState()
    val drafts by debugNoteRepo.drafts.collectAsState()
    val savedNotesByScreen by debugNoteRepo.savedNotesByScreen.collectAsState()

    val queuedCount = queue.count { it.screenRoute == currentRoute }
    val savedCount = savedNotesByScreen[currentRoute]?.size ?: 0
    val hasDraft = drafts.containsKey(currentRoute)
    val hasQueued = queuedCount > 0
    val hasSaved = savedCount > 0
    val totalQueueSize = queue.size

    var showNoteDialog by remember { mutableStateOf(false) }

    // Drag state
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }
    val bubbleSize = 48.dp
    val bubbleSizePx = with(density) { bubbleSize.toPx() }

    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx * 0.4f) }

    val scope = rememberCoroutineScope()

    // Pulsing animation for the badge when notes exist
    val infiniteTransition = rememberInfiniteTransition(label = "badgePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // The bubble — draggable + clickable
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(bubbleSize)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    when {
                        hasQueued -> BubbleOrange.copy(alpha = pulseAlpha)
                        hasDraft -> BubbleCyan.copy(alpha = pulseAlpha)
                        hasSaved -> BubbleGreen.copy(alpha = 0.85f)
                        totalQueueSize > 0 -> BubbleOrange.copy(alpha = 0.6f)
                        else -> BubbleCyan.copy(alpha = 0.85f)
                    }
                )
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x)
                            .coerceIn(0f, screenWidthPx - bubbleSizePx)
                        offsetY = (offsetY + dragAmount.y)
                            .coerceIn(0f, screenHeightPx - bubbleSizePx)
                    }
                }
                .clickable { showNoteDialog = true },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "🐛",
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center)
            )

            // Yellow badge = queued notes count on current screen
            if (hasQueued) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-6).dp)
                        .size(20.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.Yellow),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = queuedCount.toString(),
                        color = Color.Black,
                        fontSize = 10.sp
                    )
                }
            }

            // Cyan dot = draft exists for this screen
            if (hasDraft && !hasQueued) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-6).dp)
                        .size(14.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(BubbleCyan)
                )
            }

            // Green dot = saved/submitted notes exist for this screen
            if (hasSaved && !hasQueued && !hasDraft) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-6).dp)
                        .size(14.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(BubbleGreen)
                )
            }

            // Small yellow dot for global queue items on other screens
            if (totalQueueSize > 0 && !hasQueued && !hasDraft && !hasSaved) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-4).dp)
                        .size(10.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.Yellow)
                )
            }
        }
    }

    // Note dialog
    if (showNoteDialog) {
        val draft = drafts[currentRoute]
        DebugNoteDialog(
            screenContext = screenContext,
            currentRoute = currentRoute ?: "unknown",
            draftText = draft?.noteText ?: "",
            draftType = draft?.noteType ?: NoteType.BUG,
            queuedNotes = queue,
            savedNotesByScreen = savedNotesByScreen,
            noteCountOnScreen = queuedCount,
            onDismiss = { showNoteDialog = false },
            onSaveDraft = { noteType, text ->
                debugNoteRepo.saveDraft(currentRoute ?: "unknown", noteType, text)
            },
            onQueueNote = { noteType, text ->
                debugNoteRepo.enqueueNote(
                    screenRoute = currentRoute ?: "unknown",
                    screenContext = screenContext,
                    noteType = noteType,
                    noteText = text
                )
            },
            onSubmitQueue = {
                scope.launch {
                    debugNoteRepo.submitQueue()
                }
            },
            onRemoveFromQueue = { noteId ->
                debugNoteRepo.removeFromQueue(noteId)
            }
        )
    }
}
