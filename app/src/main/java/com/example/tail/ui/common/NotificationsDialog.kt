package com.example.tail.ui.common

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.example.tail.data.HabitNotification
import com.example.tail.data.tailcue.TailCue
import com.example.tail.notify.TailCueFeedback
import com.example.tail.ui.grid.MovieMinutesWheelRow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
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
    onAnswer: (HabitNotification, Boolean, Int?) -> Unit,
    onDismiss: () -> Unit,
    /** Opens the App Stats screen; used by the app-stats record notices. */
    onOpenAppStats: () -> Unit = {}
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
                        if (ask.id.startsWith(TailCue.ASK_PREFIX)) {
                            CueWarningRow(ask = ask)
                        } else {
                            NotificationAskRow(
                                ask = ask,
                                onAnswer = onAnswer,
                                onOpenAppStats = onOpenAppStats
                            )
                        }
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

/**
 * One pending ask with its Yes/No buttons (a single OK for info notices).
 * Movie asks also show the editable Length wheel — the file-derived watch
 * length by default, overridable for partially-watched movies.
 */
@Composable
private fun NotificationAskRow(
    ask: HabitNotification,
    onAnswer: (HabitNotification, Boolean, Int?) -> Unit,
    onOpenAppStats: () -> Unit = {}
) {
    // Editable watch length for movie asks (partial watches).
    var movieMinutes by remember(ask.id) {
        mutableIntStateOf(
            if (ask.type == HabitNotification.TYPE_MOVIE) {
                HabitNotification.parseMoviePayload(ask.payload).second
            } else 0
        )
    }
    val isMovie = ask.type == HabitNotification.TYPE_MOVIE
    val emoji = when (ask.type) {
        HabitNotification.TYPE_MOVIE -> "🎬"
        HabitNotification.TYPE_INFO -> "⚠️"
        else -> "❓"
    }
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
        // Editable length (movie asks only).
        if (isMovie) {
            MovieMinutesWheelRow(
                minutes = movieMinutes,
                onMinutesChange = { movieMinutes = it }
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (ask.type == HabitNotification.TYPE_INFO) {
                // Informational — acknowledging removes it everywhere; the
                // answer value itself is a no-op for this type.
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                        .clickable { onAnswer(ask, true, null) }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("✓ OK", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                        .clickable { onAnswer(ask, true, if (isMovie) movieMinutes else null) }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("✓ Yes", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF5A1A1A), RoundedCornerShape(6.dp))
                        .clickable { onAnswer(ask, false, null) }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("✗ No", color = Color(0xFFFF8888), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            if (ask.id.startsWith("appstats:")) {
                // App-stats record notice — the label doubles as a link to
                // the App Stats screen (same deep link as the system
                // notification's tap action).
                Text(
                    text = "${ask.habitName} ›",
                    color = Color(0xFF66CCFF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onOpenAppStats() }
                )
            } else {
                Text(
                    text = ask.habitName,
                    color = Color(0xFF666666),
                    fontSize = 10.sp
                )
            }
        }
    }
}


/**
 * A TailCue predictive warning: what's coming, why, and what to do about it.
 *
 * 👍 / 👎 (optionally with a note explaining WHY — that note is what steers
 * future warnings and research in TailCue) or a plain ✓ dismiss. Feedback is
 * sent to the PC via the bridge; the full history with ratings lives in the
 * TailCue webapp.
 */
@Composable
private fun CueWarningRow(ask: HabitNotification) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var noteFor by remember { mutableStateOf<Int?>(null) }   // 1 | -1 | null
    var noteText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val advice = TailCue.parseAdvice(ask.payload)
    val timeLabel = try {
        ASK_TIME_FMT.format(Instant.ofEpochMilli(ask.createdAtMillis).atZone(ZoneId.systemDefault()))
    } catch (e: Exception) {
        ""
    }

    // Pending note dialog: explain WHY you like/dislike this warning.
    noteFor?.let { rating ->
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { androidx.compose.material3.Text(
                if (rating == 1) "👍 Why is this warning useful?" else "👎 Why is this warning off?"
            ) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { androidx.compose.material3.Text("Optional note — steers future warnings") },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    busy = true
                    val r = rating
                    val txt = noteText.trim()
                    scope.launch {
                        TailCueFeedback.sendAndDismiss(context, ask, r, txt.ifBlank { null })
                        busy = false
                        noteFor = null
                    }
                }) { androidx.compose.material3.Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { noteFor = null }) { androidx.compose.material3.Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2438), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF5A4A80), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "🔔 ${ask.title}",
                color = Color(0xFFD8B4FE),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(text = timeLabel, color = Color(0xFF777777), fontSize = 10.sp)
        }
        Text(text = ask.question, color = Color(0xFFAAAAAA), fontSize = 12.sp)
        if (advice.isNotBlank() && !ask.question.contains(advice)) {
            Text(
                text = "💡 $advice",
                color = Color(0xFF9CDCB0),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy) { noteFor = 1; noteText = "" }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) { Text("👍", color = Color.White, fontSize = 12.sp) }
            Box(
                modifier = Modifier
                    .background(Color(0xFF5A1A1A), RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy) { noteFor = -1; noteText = "" }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) { Text("👎", color = Color.White, fontSize = 12.sp) }
            Box(
                modifier = Modifier
                    .background(Color(0xFF333338), RoundedCornerShape(6.dp))
                    .clickable(enabled = !busy) {
                        busy = true
                        scope.launch {
                            TailCueFeedback.sendAndDismiss(context, ask, 0, null)
                            busy = false
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) { Text("✓", color = Color(0xFFBBBBBB), fontSize = 12.sp) }
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "TailCue · feedback improves future warnings", color = Color(0xFF666666), fontSize = 9.sp)
        }
    }
}
