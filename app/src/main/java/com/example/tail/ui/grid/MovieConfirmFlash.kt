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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Seconds of inactivity before the flash auto-confirms the movie as watched. */
const val MOVIE_FLASH_AUTO_CONFIRM_SECONDS = 10

/**
 * A flash shown at the bottom of the screen (same style as [HabitIncrementToast])
 * when the desktop bridge detected a movie that has not been logged yet.
 *
 * Asks "did you watch this?" with instant Yes / No buttons. Doing nothing for
 * [autoConfirmSeconds] seconds confirms the movie as watched (handled by the
 * caller's timeout, which calls [onConfirm]).
 *
 * @param title The movie/episode title detected on the desktop
 * @param durationLabel Optional human-readable watch length (e.g. "113 min")
 * @param visible Whether the flash is currently showing
 * @param autoConfirmSeconds Shown in the hint label; must match the caller's timeout
 * @param onConfirm Called for "Yes" and for the inactivity timeout — logs the movie
 * @param onDismiss Called for "No" — skips logging and never asks again
 * @param modifier Optional modifier for positioning (e.g. alignment in a Box)
 */
@Composable
fun MovieConfirmFlash(
    title: String,
    durationLabel: String?,
    visible: Boolean,
    autoConfirmSeconds: Int = MOVIE_FLASH_AUTO_CONFIRM_SECONDS,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                        text = "🎬 $title",
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
                        text = "Watched this?",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFFAAAAAA)
                    )
                    if (durationLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "· $durationLabel",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF888888)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "(auto-logs in ${autoConfirmSeconds}s)",
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
