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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.HabitNotification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ASK_TIME_FMT = DateTimeFormatter.ofPattern("MMM d · HH:mm")

/**
 * The in-app notification center — the list of pending habit asks waiting for
 * a Yes/No answer. Opened from the 🔔 icon in the top bar.
 *
 * Every ask shown here also exists as a system notification and flashed once
 * on the first app open after it was created; answering here removes it
 * everywhere.
 */
@Composable
fun NotificationsDialog(
    notifications: List<HabitNotification>,
    onAnswer: (HabitNotification, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E22), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF3A3A40), RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🔔 Notifications",
                    color = Color(0xFF66CCFF),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (notifications.isEmpty()) "" else "${notifications.size} waiting",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (notifications.isEmpty()) {
                Text(
                    text = "Nothing to answer — you're all caught up.",
                    color = Color(0xFF999999),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    notifications.forEach { ask ->
                        NotificationAskRow(ask = ask, onAnswer = onAnswer)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Close", color = Color(0xFF888888), fontSize = 13.sp)
            }
        }
    }
}

/** One pending ask with its Yes/No buttons. */
@Composable
private fun NotificationAskRow(
    ask: HabitNotification,
    onAnswer: (HabitNotification, Boolean) -> Unit
) {
    val emoji = if (ask.type == HabitNotification.TYPE_MOVIE) "🎬" else "❓"
    val timeLabel = try {
        ASK_TIME_FMT.format(Instant.ofEpochMilli(ask.createdAtMillis).atZone(ZoneId.systemDefault()))
    } catch (e: Exception) {
        ""
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2A2E), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$emoji ${ask.title}",
                color = Color(0xFFFFD700),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = timeLabel,
                color = Color(0xFF777777),
                fontSize = 10.sp
            )
        }
        Text(
            text = ask.question,
            color = Color(0xFFAAAAAA),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                    .clickable { onAnswer(ask, true) }
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("✓ Yes", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                modifier = Modifier
                    .background(Color(0xFF5A1A1A), RoundedCornerShape(6.dp))
                    .clickable { onAnswer(ask, false) }
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Text("✗ No", color = Color(0xFFFF8888), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = ask.habitName,
                color = Color(0xFF666666),
                fontSize = 10.sp
            )
        }
    }
}
