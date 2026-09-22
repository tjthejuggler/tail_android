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
import java.time.LocalTime

/** Current wall-clock time as minutes since midnight — the default position
 *  for the bed/wake time wheels: opening the dialog at tap time pre-sets the
 *  wheel to "now". */
private fun nowMinutesOfDay(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

/** Wake-quality wheel items — a 1–10 scale (replaces the old 1–5 stars). */
private val QUALITY_ITEMS: List<String> = (1..10).map { it.toString() }

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
 * SLEEP-TIME variant dialog.
 *
 * - Bed time: a single [TimeWheelPicker] (the only time control), pre-set to
 *   the current wall-clock time when the dialog opens — or the earlier
 *   entry's time when re-editing the same night.
 * - Room temperature: numeric text field (°C, one decimal allowed) with
 *   quick chips for common values and a "—" clear chip.
 * - Sleep conditions: a text field with an Add button feeding a CHECKBOX
 *   list that combines everything added in this session with every
 *   conditions string ever used on past nights ([options], most recent
 *   first) — any number of entries can be selected; the selected items are
 *   saved comma-joined (none selected clears the stored conditions).
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
    // Field state is keyed on [existing]: the record loads right after the
    // dialog opens, and the new instance re-seeds every field exactly once.
    var bed by remember(existing) { mutableIntStateOf(existing.bed ?: nowMinutesOfDay()) }
    var tempText by remember(existing) {
        mutableStateOf(
            existing.temp?.let { (it / 10.0).toString() } ?: ""
        )
    }
    // Selected conditions as a set — stored back comma-joined on save.
    var selectedConds by remember(existing) {
        mutableStateOf(
            existing.conditions?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        )
    }
    // Entries added in THIS dialog session (pre-seeded from the stored entry
    // so its fragments always appear as selectable checkboxes).
    var addedConds by remember(existing) {
        mutableStateOf(
            existing.conditions?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?: emptyList()
        )
    }
    var newCond by remember { mutableStateOf("") }

    /** Checkbox list: newly added entries first, then everything ever used. */
    val allCondOptions = (addedConds + options).distinct()

    val tempTenths: Int? = tempText.trim().replace(',', '.').toDoubleOrNull()
        ?.let { (it * 10).toInt().takeIf { t -> t in -400..600 } } // −40..60 °C sanity

    SleepDialogScaffold(
        title = "😴 $habitName",
        saveEnabled = true,
        saveLabel = "Save",
        onSave = {
            val conditions = allCondOptions.filter { it in selectedConds }
                .joinToString(", ")
                .trim()
            onSave(bed, tempTenths, conditions)
        },
        onDismiss = onDismiss
    ) {
        Text("Bed time", color = Color(0xFF888888), fontSize = 11.sp)
        TimeWheelPicker(
            hour24 = bed / 60,
            minute = bed % 60,
            onTimeChange = { h, m -> bed = h * 60 + m },
            accent = accent,
            modifier = Modifier.fillMaxWidth()
        )

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

        // ── Sleep conditions (add + multi-select checkboxes) ──
        Text("Sleep conditions", color = Color(0xFF888888), fontSize = 11.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newCond,
                onValueChange = { newCond = it.take(100) },
                placeholder = {
                    Text("fan, window open, heavy blanket…", fontSize = 12.sp, color = Color(0xFF555566))
                },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "+ Add",
                color = accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xFF1A2438), RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        val parts = newCond.split(',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        if (parts.isNotEmpty()) {
                            addedConds = (addedConds + parts).distinct()
                            selectedConds = selectedConds + parts.toSet()
                            newCond = ""
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
        if (allCondOptions.isNotEmpty()) {
            Text("Select all that apply:", color = Color(0xFF666677), fontSize = 10.sp)
            allCondOptions.forEach { opt ->
                val checked = opt in selectedConds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            selectedConds =
                                if (checked) selectedConds - opt else selectedConds + opt
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        // null handler: the surrounding row owns the toggle so a
                        // tap on the box itself doesn't double-fire.
                        onCheckedChange = null,
                        modifier = Modifier.height(32.dp)
                    )
                    Text(
                        text = opt,
                        color = Color(0xFFCCEECC),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * WAKE-TIME variant dialog — wake wheel plus the mini survey:
 * number of awakenings, minutes spent awake, and perceived overall quality
 * on a 1–10 wheel scroller. All survey answers are optional; the wake time
 * alone saves fine, and "clear" removes the quality rating.
 */
@Composable
fun WakeTimeDialog(
    habitName: String,
    existing: SleepRecord,
    onSave: (wakeMinutes: Int, awakenings: Int?, awakeMin: Int?, quality: Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = Color(0xFFFFB74D) // warm amber — morning palette
    // Keyed on [existing] so the async pre-fill re-seeds every field once;
    // the wake wheel defaults to the current time at dialog open.
    var wake by remember(existing) { mutableIntStateOf(existing.wake ?: nowMinutesOfDay()) }
    var awText by remember(existing) { mutableStateOf(existing.awakenings?.toString() ?: "") }
    var awakeText by remember(existing) { mutableStateOf(existing.awakeMin?.toString() ?: "") }
    var quality by remember(existing) { mutableStateOf<Int?>(existing.quality) } // null = not rated

    SleepDialogScaffold(
        title = "🌅 $habitName",
        saveEnabled = true,
        saveLabel = "Save",
        onSave = {
            onSave(
                wake,
                awText.toIntOrNull()?.coerceIn(0, 99),
                awakeText.toIntOrNull()?.coerceIn(0, 1439),
                quality?.takeIf { it in 1..10 }
            )
        },
        onDismiss = onDismiss
    ) {
        Text("Wake time", color = Color(0xFF888888), fontSize = 11.sp)
        TimeWheelPicker(
            hour24 = wake / 60,
            minute = wake % 60,
            onTimeChange = { h, m -> wake = h * 60 + m },
            accent = accent,
            modifier = Modifier.fillMaxWidth()
        )

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
        Text("Overall sleep quality (1–10)", color = Color(0xFF888888), fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            WheelPicker(
                items = QUALITY_ITEMS,
                selectedIndex = (quality ?: 5) - 1,
                onSelectedChange = { idx -> quality = idx + 1 },
                itemHeight = 32.dp,
                accent = accent,
                modifier = Modifier.width(72.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = quality?.let { "$it / 10" } ?: "not rated",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "clear",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier
                    .background(Color(0xFF222222), RoundedCornerShape(8.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { quality = null }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
