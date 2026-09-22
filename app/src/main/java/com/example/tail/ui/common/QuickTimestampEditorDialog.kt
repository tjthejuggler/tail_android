package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Quick popup for adjusting a habit increment timestamp.
 *
 * Features a stylish three-wheel scrolling time picker (Hour · Minute · AM/PM)
 * for precise, intuitive time selection.
 *
 * @param habitName The habit being adjusted
 * @param originalTime The original "HH:mm:ss" timestamp that was recorded
 * @param onConfirm Called with the adjusted "HH:mm:ss" time when user taps Done
 * @param onDismiss Called when user cancels
 */
@Composable
fun QuickTimestampEditorDialog(
    habitName: String,
    originalTime: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val originalLocalTime = remember(originalTime) {
        runCatching { LocalTime.parse(originalTime) }.getOrDefault(LocalTime.now())
    }

    // Absolute time state — the wheels modify this directly
    var currentHour24 by remember { mutableIntStateOf(originalLocalTime.hour) }
    var currentMinute by remember { mutableIntStateOf(originalLocalTime.minute) }

    // Offset from original (for display only)
    val offsetMinutes = remember(currentHour24, currentMinute) {
        val currentTotal = currentHour24 * 60 + currentMinute
        val originalTotal = originalLocalTime.hour * 60 + originalLocalTime.minute
        currentTotal - originalTotal
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(16.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "🕐 Adjust Time",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = habitName,
                fontSize = 12.sp,
                color = Color(0xFF999999),
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Scrolling wheel time picker ──────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2A2E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                TimeWheelPicker(
                    hour24 = currentHour24,
                    minute = currentMinute,
                    onTimeChange = { h, m ->
                        currentHour24 = h
                        currentMinute = m
                    },
                    accent = Color(0xFF88DDFF)
                )
            }

            // Offset indicator
            if (offsetMinutes != 0) {
                Spacer(modifier = Modifier.height(6.dp))
                val offsetHours = offsetMinutes / 60
                val offsetMins = offsetMinutes % 60
                val offsetText = buildString {
                    append("(")
                    if (offsetMinutes > 0) append("+")
                    if (offsetHours != 0) {
                        append("${offsetHours}h")
                        if (offsetMins != 0) append(" ")
                    }
                    if (offsetMins != 0) {
                        if (offsetHours == 0 && offsetMinutes > 0) append("+")
                        append("${offsetMins}m")
                    }
                    append(")")
                }
                Text(
                    text = offsetText,
                    fontSize = 13.sp,
                    color = Color(0xFF88AA88),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Reset button
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(8.dp))
                        .clickable {
                            currentHour24 = originalLocalTime.hour
                            currentMinute = originalLocalTime.minute
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Reset", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Cancel
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF333333), RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    }

                    // Done
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF004488), RoundedCornerShape(8.dp))
                            .clickable {
                                val adjusted = LocalTime.of(currentHour24, currentMinute)
                                onConfirm(adjusted.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
                            }
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text("Done", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
