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

/**
 * A brief toast-like notification shown at the bottom of the screen when a habit
 * is incremented. Displays the habit name and action buttons:
 * - When [isTimeless] is false: shows "Edit Time" and "Timeless" buttons
 * - When [isTimeless] is true: shows "(timeless)" label and "Edit Time" button
 *
 * @param habitName The name of the habit that was incremented
 * @param visible Whether the toast is currently showing
 * @param isTimeless Whether the increment was recorded without a timestamp
 * @param onEditTime Called when the user taps "Edit Time"
 * @param onTimeless Called when the user taps "Timeless" to remove the timestamp
 * @param modifier Optional modifier for positioning (e.g. alignment in a Box)
 */
@Composable
fun HabitIncrementToast(
    habitName: String,
    visible: Boolean,
    isTimeless: Boolean = false,
    onEditTime: () -> Unit = {},
    onTimeless: () -> Unit = {},
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
                // Habit name row (may wrap if very long, but won't squish buttons)
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✓ $habitName",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isTimeless) Color(0xFF88CCFF) else Color(0xFF88FF88)
                    )
                    if (isTimeless) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(timeless)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF888888)
                        )
                    }
                }
                // Buttons row — always on its own line below the name
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF004488), RoundedCornerShape(6.dp))
                            .clickable(onClick = onEditTime)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🕐 Edit Time",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                    if (!isTimeless) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF554400), RoundedCornerShape(6.dp))
                                .clickable(onClick = onTimeless)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⏰ Timeless",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFFFDD88),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
