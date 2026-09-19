package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.Habit
import com.example.tail.data.SleepRecord
import com.example.tail.data.minutesToClockString

/** Holder for the sleep-suite dialog state — kept out of HabitGridScreen so
 *  the grid composable stays under the JVM 64KB method-size limit. */
internal class SleepDialogStateHolder {
    var habit: Habit? by mutableStateOf(null)
    var record: SleepRecord by mutableStateOf(SleepRecord())
    var options: List<String> by mutableStateOf(emptyList())
}

/**
 * Opens the sleep dialog for [habit], pre-fetching the stored record (dialog
 * pre-fill) and the past-conditions suggestion list. Returns false when the
 * habit has no sleep variant configured (caller falls back to edit mode).
 */
internal fun openSleepDialog(
    holder: SleepDialogStateHolder,
    viewModel: HabitViewModel,
    habit: Habit,
    date: java.time.LocalDate
): Boolean {
    when (viewModel.sleepVariantOf(habit.name)) {
        com.example.tail.data.SLEEP_VARIANT_SLEEP_TIME,
        com.example.tail.data.SLEEP_VARIANT_WAKE_TIME -> {}
        else -> return false
    }
    holder.habit = habit
    holder.record = SleepRecord()
    holder.options = emptyList()
    val dateStr = com.example.tail.data.dateString(date)
    viewModel.loadSleepRecord(habit.name, dateStr) { rec ->
        if (holder.habit?.name == habit.name) holder.record = rec
    }
    viewModel.loadSleepConditionsHistory(habit.name) { opts ->
        if (holder.habit?.name == habit.name) holder.options = opts
    }
    return true
}

/**
 * Stateful renderer for the sleep-suite dialog: reads [holder] and delegates
 * to [SleepDialogHost]. One-line call from HabitGridScreen keeps that
 * composable under the JVM method-size limit.
 */
@Composable
internal fun SleepDialogRenderer(
    holder: SleepDialogStateHolder,
    viewModel: HabitViewModel,
    date: java.time.LocalDate,
    onDismiss: () -> Unit
) {
    holder.habit?.let { habit ->
        SleepDialogHost(
            habit = habit,
            record = holder.record,
            options = holder.options,
            date = date,
            viewModel = viewModel,
            onDismiss = onDismiss
        )
    }
}

/**
 * Shared dialog scaffold for both sleep-suite dialogs: dark themed AlertDialog
 * with the habit name + variant emoji in the title and Cancel/Save buttons.
 */
@Composable
private fun SleepDialogScaffold(
    title: String,
    saveEnabled: Boolean,
    saveLabel: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontSize = 16.sp) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = saveEnabled) {
                Text(saveLabel, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Host composable that picks the right sleep dialog for the tapped habit's
 * variant and dispatches saves through [viewModel]. Extracted from
 * HabitGridScreen so the grid composable stays under the JVM method-size
 * limit.
 */
@Composable
internal fun SleepDialogHost(
    habit: Habit,
    record: SleepRecord,
    options: List<String>,
    date: java.time.LocalDate,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit
) {
    when (viewModel.sleepVariantOf(habit.name)) {
        com.example.tail.data.SLEEP_VARIANT_SLEEP_TIME -> {
            SleepTimeDialog(
                habitName = habit.name,
                existing = record,
                options = options,
                onSave = { bed, temp, cond ->
                    viewModel.saveSleepTimeEntry(
                        habitName = habit.name,
                        bedMinutes = bed,
                        tempTenths = temp,
                        conditions = cond,
                        date = date,
                        // Stamp mirrors the chosen bed time so the timestamp
                        // editor and the timeline graph stay aligned.
                        stampTime = "%02d:%02d:00".format(bed / 60, bed % 60)
                    )
                    onDismiss()
                },
                onDismiss = onDismiss
            )
        }
        com.example.tail.data.SLEEP_VARIANT_WAKE_TIME -> {
            WakeTimeDialog(
                habitName = habit.name,
                existing = record,
                onSave = { wake, aw, awake, q ->
                    viewModel.saveWakeSurveyEntry(
                        habitName = habit.name,
                        wakeMinutes = wake,
                        awakenings = aw,
                        awakeMin = awake,
                        quality = q,
                        date = date,
                        stampTime = "%02d:%02d:00".format(wake / 60, wake % 60)
                    )
                    onDismiss()
                },
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * Wheel-based clock row with ±15 min quick buttons — shared by both sleep dialogs.
 */
@Composable
private fun SleepClockPicker(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "−15",
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0xFF1A2438), RoundedCornerShape(8.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onMinutesChange(((minutes - 15) + 1440) % 1440) }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = minutesToClockString(minutes),
            color = accent,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "+15",
            color = accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0xFF1A2438), RoundedCornerShape(8.dp))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onMinutesChange((minutes + 15) % 1440) }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    TimeWheelPicker(
        hour24 = minutes / 60,
        minute = minutes % 60,
        onTimeChange = { h, m -> onMinutesChange(h * 60 + m) },
        accent = accent,
        compact = true,
        itemHeight = 32.dp
    )
}

/**
 * SLEEP-TIME variant dialog.
 *
 * - Bed time: ±15 buttons + [TimeWheelPicker].
 * - Room temperature: numeric text field (°C, one decimal allowed) with
 *   quick chips for common values and a "—" clear chip.
 * - Sleep conditions: free-text field PLUS option chips built from every
 *   past conditions input ([options], most recent first) — tapping a chip
 *   fills the field. Blank submission is allowed (conditions optional).
 *
 * [existing] pre-fills the dialog from an earlier same-night entry.
 */
@Composable
fun SleepTimeDialog(
    habitName: String,
    existing: SleepRecord,
    options: List<String>,
    onSave: (bedMinutes: Int, tempTenths: Int?, conditions: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = Color(0xFF9FA8FF) // soft indigo — sleep palette
    var bed by remember { mutableIntStateOf(existing.bed ?: DEFAULT_BED_MINUTES) }
    var tempText by remember {
        mutableStateOf(
            existing.temp?.let { (it / 10.0).toString() } ?: ""
        )
    }
    var conditions by remember { mutableStateOf(existing.conditions ?: "") }

    val tempTenths: Int? = tempText.trim().replace(',', '.').toDoubleOrNull()
        ?.let { (it * 10).toInt().takeIf { t -> t in -400..600 } } // −40..60 °C sanity

    SleepDialogScaffold(
        title = "😴 $habitName",
        saveEnabled = true,
        saveLabel = "Save",
        onSave = { onSave(bed, tempTenths, conditions.trim()) },
        onDismiss = onDismiss
    ) {
        Text("Bed time", color = Color(0xFF888888), fontSize = 11.sp)
        SleepClockPicker(minutes = bed, onMinutesChange = { bed = it }, accent = accent)

        HorizontalDivider(color = Color(0xFF333344), thickness = 0.5.dp)

        // ── Room temperature ──
        Text("Room temperature (°C)", color = Color(0xFF888888), fontSize = 11.sp)
        OutlinedTextField(
            value = tempText,
            onValueChange = { v ->
                tempText = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            placeholder = { Text("e.g. 19.5", fontSize = 12.sp, color = Color(0xFF555566)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("17", "18", "19", "20", "21").forEach { t ->
                val isActive = tempText == t
                Text(
                    text = t,
                    color = if (isActive) Color(0xFF000000) else accent,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(
                            if (isActive) accent else Color(0xFF1A1A2E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { tempText = t }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text(
                text = "clear",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { tempText = "" }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider(color = Color(0xFF333344), thickness = 0.5.dp)

        // ── Sleep conditions (text + past-input options) ──
        Text("Sleep conditions", color = Color(0xFF888888), fontSize = 11.sp)
        OutlinedTextField(
            value = conditions,
            onValueChange = { conditions = it.take(200) },
            placeholder = {
                Text("fan, window open, heavy blanket…", fontSize = 12.sp, color = Color(0xFF555566))
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (options.isNotEmpty()) {
            Text("Past inputs:", color = Color(0xFF666677), fontSize = 10.sp)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.take(12).forEach { opt ->
                    Text(
                        text = opt,
                        color = Color(0xFFCCEECC),
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .background(Color(0xFF1A2E1A), RoundedCornerShape(8.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { conditions = opt }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * WAKE-TIME variant dialog — wake clock plus the mini survey:
 * number of awakenings, minutes spent awake, perceived overall quality 1–5.
 * All survey answers are optional; the wake time alone saves fine.
 */
@Composable
fun WakeTimeDialog(
    habitName: String,
    existing: SleepRecord,
    onSave: (wakeMinutes: Int, awakenings: Int?, awakeMin: Int?, quality: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = Color(0xFFFFB74D) // warm amber — morning palette
    var wake by remember { mutableIntStateOf(existing.wake ?: DEFAULT_WAKE_MINUTES) }
    var awText by remember { mutableStateOf(existing.awakenings?.toString() ?: "") }
    var awakeText by remember { mutableStateOf(existing.awakeMin?.toString() ?: "") }
    var quality by remember { mutableIntStateOf(existing.quality ?: 0) } // 0 = unanswered

    SleepDialogScaffold(
        title = "🌅 $habitName",
        saveEnabled = true,
        saveLabel = "Save",
        onSave = {
            onSave(
                wake,
                awText.toIntOrNull()?.coerceIn(0, 99),
                awakeText.toIntOrNull()?.coerceIn(0, 1439),
                quality.takeIf { it in 1..5 }
            )
        },
        onDismiss = onDismiss
    ) {
        Text("Wake time", color = Color(0xFF888888), fontSize = 11.sp)
        SleepClockPicker(minutes = wake, onMinutesChange = { wake = it }, accent = accent)

        HorizontalDivider(color = Color(0xFF333344), thickness = 0.5.dp)

        // ── Mini survey ──
        Text("Night survey", color = Color(0xFF888888), fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = awText,
                onValueChange = { v -> awText = v.filter { it.isDigit() }.take(2) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Awakenings", fontSize = 10.sp) },
                placeholder = { Text("0", fontSize = 12.sp, color = Color(0xFF555566)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = awakeText,
                onValueChange = { v -> awakeText = v.filter { it.isDigit() }.take(4) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text("Awake (min)", fontSize = 10.sp) },
                placeholder = { Text("0", fontSize = 12.sp, color = Color(0xFF555566)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("Overall sleep quality", color = Color(0xFF888888), fontSize = 11.sp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            (1..5).forEach { q ->
                val isActive = quality == q
                Text(
                    text = "${"★".repeat(q)}",
                    color = if (isActive) Color(0xFFFFD54F) else Color(0xFF444455),
                    fontSize = if (isActive) 15.sp else 13.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .background(
                            if (isActive) Color(0xFF33270A) else Color(0xFF1A1A2E),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { quality = if (quality == q) 0 else q }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
    }
}
