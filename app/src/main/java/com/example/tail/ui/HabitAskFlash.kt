package com.example.tail.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Seconds the one-time ask flash stays on screen before it hides itself. */
const val HABIT_ASK_FLASH_SECONDS = 12

/**
 * A flash shown at the bottom of the screen (same style as
 * [HabitIncrementToast]) for a pending habit-ask notification. Used for the
 * movie-bridge asks and the scheduled habit asks.
 *
 * Unlike the old movie flash there is NO auto-confirm: when the timeout
 * expires the flash simply hides and the ask keeps waiting in the
 * notification center (and as a system notification) until it is answered.
 *
 * @param title Headline (movie title or habit name)
 * @param question The yes/no question (e.g. "Watched this?")
 * @param metaLabel Optional extra info (e.g. "113 min" or "Scheduled 22:00")
 * @param visible Whether the flash is currently showing
 * @param onConfirm "Yes" — applies the yes effect everywhere
 * @param onDismiss "No" — applies the no effect everywhere
 * @param modifier Optional modifier for positioning (e.g. alignment in a Box)
 */
@Composable
fun HabitAskFlash(
    title: String,
    question: String,
    metaLabel: String?,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onHide: () -> Unit = {}
) {
    // Auto-hide after the timeout — the ask is NOT answered here; it keeps
    // waiting in the notification center (and as a system notification).
    LaunchedEffect(visible) {
        if (visible) {
            delay(HABIT_ASK_FLASH_SECONDS * 1000L)
            onHide()
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF2A2A2E), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Title row (may wrap if very long, but won't squish buttons)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFFFD700)
                    )
                }
                // Question + meta row
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = question,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFFAAAAAA)
                    )
                    if (metaLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "· $metaLabel",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF888888)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(waits in 🔔 if unanswered)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF888888)
                    )
                }
                // Buttons row — always on its own line below the question
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                            .clickable(onClick = onConfirm)
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "✓ Yes",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF5A1A1A), RoundedCornerShape(6.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "✗ No",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF8888),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
