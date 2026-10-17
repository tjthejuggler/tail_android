package com.example.tail.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.ui.common.TimeWheelPicker
import com.example.tail.ui.viewmodel.ComboSession
import kotlinx.coroutines.delay

/** One in-dialog session row (draft state; minutes parsed on submit). */
private data class DraftSession(
    val habitName: String,
    val minutesText: String
)

/**
 * Combo-input popup.
 *
 * Shows the habits selected in Settings, each with a "+" button that adds a
 * session row (habit name + minutes field). Any number of sessions per habit
 * can be added, in any interleaved order. One anchor time (the standard time
 * wheel) with a Start/End toggle — the chosen mode is reported back through
 * [onModeChange] so it can be remembered for the next use.
 *
 * When a "+" button adds a session, the "Entered" list scrolls to (and focuses)
 * the new row so it is never hidden behind the keyboard. The two list regions
 * sit in rounded, slightly-elevated containers so the foreground dialog and
 * the inner scrollable areas read as distinct layers.
 *
 * [onSubmit] receives the sessions in the order they were added, the anchor
 * time and the mode; the timestamps are computed by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboHabitDialog(
    memberHabits: List<String>,
    initialEndMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onSubmit: (sessions: List<ComboSession>, anchorHour: Int, anchorMinute: Int, endMode: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var endMode by remember { mutableStateOf(initialEndMode) }
    var hour by remember { mutableIntStateOf(java.time.LocalTime.now().hour) }
    var minute by remember { mutableIntStateOf(java.time.LocalTime.now().minute) }
    var sessions by remember { mutableStateOf(listOf<DraftSession>()) }

    // Scroll-to-reveal state: the index of the newest added row to reveal,
    // bumped with a counter so repeated additions of the same index retrigger.
    var revealIndex by remember { mutableIntStateOf(-1) }
    var revealTick by remember { mutableIntStateOf(0) }
    val minutesFocus = remember { FocusRequester() }

    val canSubmit = sessions.any { (it.minutesText.toIntOrNull() ?: 0) > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚡ Combo Input", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                // ── Anchor time + Start/End toggle ──────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !endMode,
                        onClick = {
                            endMode = false
                            onModeChange(false)
                        },
                        label = { Text("Start", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = endMode,
                        onClick = {
                            endMode = true
                            onModeChange(true)
                        },
                        label = { Text("End", fontSize = 12.sp) }
                    )
                    Text(
                        text = "time of the whole block",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                TimeWheelPicker(
                    hour24 = hour,
                    minute = minute,
                    onTimeChange = { h, m -> hour = h; minute = m },
                    compact = true
                )
                Spacer(modifier = Modifier.height(10.dp))

                // ── Member habits with "+" buttons (inner scrollable area) ─
                Text("Sessions", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                ElevatedListContainer {
                    LazyColumn(
                        state = rememberLazyListState(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        itemsIndexed(memberHabits) { i, name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    name,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        sessions = sessions + DraftSession(name, "")
                                        revealIndex = sessions.lastIndex
                                        revealTick++
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 12.dp, vertical = 2.dp
                                    )
                                ) { Text("+", fontSize = 16.sp) }
                            }
                            if (i < memberHabits.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }

                // ── Added sessions (minutes input each) ────────────────────
                if (sessions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Entered", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(4.dp))
                    val listState = rememberLazyListState()
                    RevealEffect(listState, revealIndex, revealTick, minutesFocus)
                    ElevatedListContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                        ) {
                            itemsIndexed(sessions) { idx, s ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${idx + 1}. ${s.habitName}",
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = s.minutesText,
                                        onValueChange = { txt ->
                                            sessions = sessions.toMutableList().also {
                                                it[idx] = s.copy(minutesText = txt.filter { c -> c.isDigit() })
                                            }
                                        },
                                        label = { Text("min", fontSize = 11.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(0.45f)
                                            .padding(start = 6.dp)
                                            .then(
                                                if (idx == revealIndex) Modifier.focusRequester(minutesFocus)
                                                else Modifier
                                            )
                                    )
                                    TextButton(onClick = {
                                        sessions = sessions.filterIndexed { j, _ -> j != idx }
                                    }) { Text("✕", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val out = sessions.mapNotNull { s ->
                        val mins = s.minutesText.toIntOrNull() ?: 0
                        if (mins > 0) ComboSession(s.habitName, mins) else null
                    }
                    if (out.isNotEmpty()) onSubmit(out, hour, minute, endMode)
                },
                enabled = canSubmit
            ) { Text("Submit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * A rounded, subtly elevated container for the dialog's inner scrollable
 * regions — a shade above the dialog surface so the "foreground card" and
 * the "inner list" layers read as distinct.
 */
@Composable
private fun ElevatedListContainer(content: @Composable () -> Unit) {
    val bg = MaterialTheme.colorScheme.run {
        // surfaceContainerHigh is a step above the dialog surface; fall back
        // gracefully on older Material3 defaults where it equals the surface.
        if (surfaceContainerHigh == surface) surface.copy(alpha = 0.95f).compositeOverBlack(0.04f)
        else surfaceContainerHigh
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(vertical = 4.dp)
    ) { content() }
}

/** Darkens a color slightly toward black by [amount] (0..1) — fallback tint. */
private fun Color.compositeOverBlack(amount: Float): Color = Color(
    red = red * (1f - amount),
    green = green * (1f - amount),
    blue = blue * (1f - amount),
    alpha = alpha
)

/**
 * Scrolls the "Entered" list so the newly added row is fully visible above
 * the keyboard, then focuses its minutes field. Runs on every reveal tick;
 * the small delay lets the new item compose first.
 */
@Composable
private fun RevealEffect(
    listState: LazyListState,
    revealIndex: Int,
    revealTick: Int,
    focus: FocusRequester
) {
    LaunchedEffect(revealTick) {
        if (revealIndex < 0 || revealTick == 0) return@LaunchedEffect
        delay(100)
        // Overshoot by one item when possible so the row clears the IME inset.
        val target = (revealIndex + 1).coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
        if (target >= 0) {
            listState.animateScrollToItem(target)
        }
        // A second pass after the keyboard animates in keeps the row visible.
        delay(250)
        val stillTarget = (revealIndex + 1).coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
        if (stillTarget >= 0) {
            listState.scrollToItem(stillTarget)
        }
        runCatching { focus.requestFocus() }
    }
}
