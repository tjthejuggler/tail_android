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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Quick popup for adjusting a habit increment timestamp by adding/subtracting
 * hours and minutes. Designed for fast one-handed use.
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

    var currentOffsetMinutes by remember { mutableStateOf(0) }

    val displayTime = remember(originalLocalTime, currentOffsetMinutes) {
        val adjusted = originalLocalTime.plusMinutes(currentOffsetMinutes.toLong())
        val fmt = DateTimeFormatter.ofPattern("h:mm a")
        adjusted.format(fmt)
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

            // Current time display
            Box(
                modifier = Modifier
                    .background(Color(0xFF2A2A2E), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(
                    text = displayTime,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF88DDFF),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            if (currentOffsetMinutes != 0) {
                val offsetHours = currentOffsetMinutes / 60
                val offsetMins = currentOffsetMinutes % 60
                val offsetText = buildString {
                    append("(")
                    if (currentOffsetMinutes > 0) append("+")
                    if (offsetHours != 0) {
                        append("${offsetHours}h")
                        if (offsetMins != 0) append(" ")
                    }
                    if (offsetMins != 0) {
                        if (offsetHours == 0 && currentOffsetMinutes > 0) append("+")
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

            Spacer(modifier = Modifier.height(16.dp))

            // Hour adjustment row
            Text(
                text = "Hours",
                fontSize = 11.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(-3, -2, -1, 1, 2, 3).forEach { offset ->
                    OffsetButton(
                        label = if (offset > 0) "+${offset}h" else "${offset}h",
                        onClick = { currentOffsetMinutes += offset * 60 },
                        size = 38.dp,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Minute adjustment row
            Text(
                text = "Minutes",
                fontSize = 11.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(-30, -15, -5, 5, 15, 30).forEach { offset ->
                    OffsetButton(
                        label = if (offset > 0) "+${offset}m" else "${offset}m",
                        onClick = { currentOffsetMinutes += offset },
                        size = 38.dp,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Reset button
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333333), RoundedCornerShape(8.dp))
                        .clickable { currentOffsetMinutes = 0 }
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
                                val adjusted = originalLocalTime.plusMinutes(currentOffsetMinutes.toLong())
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

@Composable
private fun OffsetButton(
    label: String,
    onClick: () -> Unit,
    size: Dp = 38.dp,
    fontSize: TextUnit = 11.sp
) {
    Box(
        modifier = Modifier
            .background(Color(0xFF2A2A2E), CircleShape)
            .border(1.dp, Color(0xFF555555), CircleShape)
            .size(size)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = fontSize,
            color = Color(0xFFCCDDFF),
            fontWeight = FontWeight.Medium
        )
    }
}
