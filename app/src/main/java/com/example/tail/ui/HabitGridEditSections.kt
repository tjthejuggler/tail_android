package com.example.tail.ui

// Split out of HabitGridScreen.kt (2026-08-29) to keep individual
// Kotlin source files small enough for IR lowering on this machine.

import kotlin.text.toIntOrNull
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.example.tail.data.backup.HabitRestorePreview
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconRepository
import com.example.tail.data.HabitNotification
import com.example.tail.data.AppIconInfo
import com.example.tail.data.ACTIVITY_ID_PREFIX
import com.example.tail.data.AppIconRepository
import com.example.tail.data.encodeShortcutEntry
import com.example.tail.data.findShortcutInfo
import com.example.tail.data.isActivityEntry
import com.example.tail.data.launchShortcutEntry
import com.example.tail.data.parseShortcutEntry
import com.example.tail.data.queryAppShortcuts
import com.example.tail.data.appIconMonochromeOf
import com.example.tail.data.appIconNameOf
import com.example.tail.data.isAppIconName
import com.example.tail.data.isTextIconName
import com.example.tail.data.renderTextIconBitmap
import com.example.tail.data.textIconCharOf
import com.example.tail.data.textIconNameOf
import com.example.tail.data.BridgeMovie
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminType
import com.example.tail.data.GitHubMetric
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
import com.example.tail.data.GRAPH_METRIC_POINTS
import com.example.tail.data.GRAPH_METRIC_VALUE2
import com.example.tail.data.GRAPH_METRIC_VALUE3
import com.example.tail.data.defaultLabelForValueKey
import com.example.tail.data.displayLabelForValue
import com.example.tail.data.effectiveConditionalLinkValueKey
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.isAppLink
import com.example.tail.data.meal.VisionQueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun EditModeValueEditorRow(
    selectedHabitName: String?,
    garminHabitLinks: Map<String, String>,
    garminMonthlyData: Map<com.example.tail.data.GarminType, Map<String, Int>>,
    garminDateOfBirth: String,
    selectedDate: java.time.LocalDate,
    habitDividers: Map<String, Int>,
    widgetTriggerApps: Map<String, String>,
    widgetTimerMinutesPrimary: Set<String>,
    mediaHabits: Set<String> = emptySet(),
    minutesEnabled: Boolean = false,
    rawTodayCount: Int,
    minutesTodayCount: Int,
    onSetCount: (String, Int) -> Unit,
    onSetMinutesCount: (String, Int) -> Unit
) {
    val habitName = selectedHabitName ?: return
    val isGarminLinked = habitName in garminHabitLinks
    val isDivider = (habitDividers[habitName] ?: 1) > 1
    val isTimerHabit = !widgetTriggerApps[habitName].isNullOrBlank()
    val isMediaHabit = habitName in mediaHabits
    val minutesPrimary = habitName in widgetTimerMinutesPrimary
    if (!(isGarminLinked || isDivider || isTimerHabit || isMediaHabit)) return

    Spacer(modifier = Modifier.height(2.dp))
    // For Garmin habits, derive the value live on every recomposition so it
    // reflects garminMonthlyData updates that arrive asynchronously (e.g. after
    // a "Test Connection" sync). Caching it in remember() keyed only on the
    // habit name would leave a stale "-" when the data lands after selection.
    val garminValueText: String = if (isGarminLinked) {
        val garminType = garminHabitLinks[habitName]?.let { GarminType.fromKey(it) }
        val rawValue: Int? = when (garminType) {
            GarminType.FITNESS_AGE_DISTANCE -> {
                // Calculate fitness age distance on-demand from FITNESS_AGE
                // Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04)
                try {
                    val fitnessAgeData = garminMonthlyData[GarminType.FITNESS_AGE]
                    if (fitnessAgeData != null && garminDateOfBirth.isNotEmpty()) {
                        val fitnessAge = fitnessAgeData[selectedDate.toString()]
                        if (fitnessAge != null) {
                            val dob = java.time.LocalDate.parse(garminDateOfBirth)
                            // Calculate biological age in hundredths of a year
                            val biologicalAgeYears = java.time.temporal.ChronoUnit.YEARS.between(dob, selectedDate).toDouble()
                            val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                            // Distance = fitness_age - biological_age (both in hundredths of a year)
                            fitnessAge - biologicalAgeHundredths
                        } else null
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            else -> {
                val dailyValues = garminType?.let { garminMonthlyData[it] }
                dailyValues?.get(selectedDate.toString())
            }
        }
        // Format per-metric for display (e.g. distance metres → km, 1 decimal).
        rawValue?.let { garminType?.formatDisplayValue(it) ?: it.toString() } ?: "-"
    } else {
        "-"
    }
    // Which value track the editor edits (timer habits only).
    // Defaults to the habit's PRIMARY value and resets if the
    // primary value setting changes. Media habits always edit
    // their MINUTES track — minutes are what the media tracker
    // auto-records and what the user manages manually here.
    var editingMinutes by remember(habitName, minutesPrimary) {
        mutableStateOf(minutesPrimary || isMediaHabit)
    }
    var valuePickerExpanded by remember { mutableStateOf(false) }
    // The value being edited: minutes live in the first-class
    // `minutes:<habit>` slot, sessions in the habit's own slot.
    val editingValue = if (editingMinutes) minutesTodayCount else rawTodayCount
    // Editable remembered field bound to the edited value. Re-keyed on the
    // edited track so switching Minutes/Sessions reloads the other track's
    // stored value instead of keeping stale text.
    var trueValueText by remember(habitName, editingMinutes) {
        mutableStateOf(editingValue.toString())
    }
    var valueFieldFocused by remember { mutableStateOf(false) }
    // Sync when the edited value changes externally (e.g.
    // [−]/[+] buttons, bubble timer, or switching tracks) — but ONLY
    // while the field is NOT focused. While focused, the field text is
    // authoritative: the model value lags the debounced DB write, and
    // coercing text back to the STALE model snapped every keystroke
    // back out (the field "fought" the user — editing a media habit's
    // minutes was impossible). External changes re-sync on focus loss.
    val parsedTrueValue = trueValueText.toIntOrNull()
    if (!isGarminLinked && !valueFieldFocused && parsedTrueValue != editingValue) {
        trueValueText = editingValue.toString()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if ((isTimerHabit || (isDivider && minutesEnabled)) && !isGarminLinked) {
            // Multi-value habit: dropdown choosing which
            // value to set (default = primary value).
            Box {
                Button(
                    onClick = { valuePickerExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (editingMinutes) "Minutes ▾" else "Sessions ▾",
                        fontSize = 10.sp,
                        color = Color(0xFF66CCFF)
                    )
                }
                DropdownMenu(
                    expanded = valuePickerExpanded,
                    onDismissRequest = { valuePickerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Minutes", fontSize = 13.sp) },
                        onClick = {
                            valuePickerExpanded = false
                            editingMinutes = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sessions", fontSize = 13.sp) },
                        onClick = {
                            valuePickerExpanded = false
                            editingMinutes = false
                        }
                    )
                }
            }
        } else {
            // Single-value habit: just the name of the value
            // it represents, as a plain label.
            Text(
                text = when {
                    isGarminLinked -> "garmin value:"
                    isMediaHabit -> "minutes:"
                    else -> "true value:"
                },
                color = Color(0xFFAA88FF),
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        if (isGarminLinked) {
            // Garmin value is read-only — it is derived from
            // garminMonthlyData. Render it as a plain label.
            //
            // It must NOT be an editable text field: a TextField
            // with a no-op onValueChange keeps its own internal
            // text buffer from first composition, which could latch
            // a stale value (e.g. "1") that never refreshed when the
            // real value arrived asynchronously — the user had to
            // type into it to force it to the correct number. A
            // Text always reflects the live derived value.
            Text(
                text = garminValueText,
                color = Color(0xFFAA88FF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp)
            )
        } else {
            OutlinedTextField(
                value = trueValueText,
                onValueChange = { v: String ->
                    // Digits only, with leading zeros collapsed ("05" → "5").
                    // An empty string is allowed here: it is a mid-edit state
                    // that commits a count of 0 and stays empty until the user
                    // types the new number.
                    val digits = v.filter { it.isDigit() }
                    trueValueText = digits.toIntOrNull()?.toString() ?: ""
                    val newCount = trueValueText.toIntOrNull() ?: 0
                    if (editingMinutes) {
                        onSetMinutesCount(habitName, newCount)
                    } else {
                        onSetCount(habitName, newCount)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier
                    .width(64.dp)
                    .onFocusChanged { valueFieldFocused = it.isFocused },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFAA88FF),
                    unfocusedTextColor = Color(0xFFAA88FF),
                    focusedBorderColor = Color(0xFFAA88FF),
                    unfocusedBorderColor = Color(0xFF664488)
                ),
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/**
 * Direct editor for the first-class `minutes:<habit>` slot, shown at the
 * top of the edit bar beside the value editor for habits whose value
 * editor doesn't already cover minutes (plain and divider habits).
 */


@Composable
internal fun EditModeMinutesEditorRow(
    habitName: String,
    minutesToday: Int,
    onSetMinutesToday: (String, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "minutes:",
            color = Color(0xFFAA88FF),
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        var minutesText by remember(habitName) { mutableStateOf(minutesToday.toString()) }
        var minutesFieldFocused by remember { mutableStateOf(false) }
        // Sync when today's minutes change externally (timer, PC widget,
        // media tracker) — but ONLY while the field is NOT focused.
        // While focused the field text is authoritative: the model value
        // lags the debounced DB write, and coercing text to the STALE
        // model snapped every keystroke back out (cursor jumps, the
        // field "fought" the user). External changes re-sync on blur.
        val parsedMinutes = minutesText.toIntOrNull()
        if (!minutesFieldFocused && parsedMinutes != minutesToday) {
            minutesText = minutesToday.toString()
        }
        OutlinedTextField(
            value = minutesText,
            onValueChange = { v: String ->
                val digits = v.filter { it.isDigit() }
                minutesText = digits.toIntOrNull()?.toString() ?: ""
                onSetMinutesToday(habitName, minutesText.toIntOrNull() ?: 0)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(64.dp)
                .onFocusChanged { minutesFieldFocused = it.isFocused },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFAA88FF),
                unfocusedTextColor = Color(0xFFAA88FF),
                focusedBorderColor = Color(0xFFAA88FF),
                unfocusedBorderColor = Color(0xFF664488)
            ),
            textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center)
        )
    }
}

// ── Advanced section ────────────────────────────────────────────────────────

/**
 * Collapsible "Special Habit Types" section in the habit edit panel.
 *
 * Groups the integration-backed habit types — Meal, Chess.com, Media,
 * Garmin, GitHub and Movie Bridge — behind one collapsible header so the
 * edit panel stays tidy. Collapsed by default; the content lambdas are
 * only composed while expanded, so a collapsed section costs nothing.
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit.
 */


@Composable
internal fun SpecialHabitTypesSection(
    mealContent: @Composable () -> Unit,
    weightsContent: @Composable () -> Unit,
    chessComContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit,
    garminContent: @Composable () -> Unit,
    githubContent: @Composable () -> Unit,
    movieBridgeContent: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))
    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
    Spacer(modifier = Modifier.height(4.dp))

    // Expandable header — vertically padded for a larger, easier-to-tap target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SPECIAL HABIT TYPES",
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = if (expanded) "▾" else "▸",
            color = Color(0xFF888888),
            fontSize = 14.sp
        )
    }

    if (expanded) {
        Spacer(modifier = Modifier.height(6.dp))
        mealContent()
        Spacer(modifier = Modifier.height(4.dp))
        weightsContent()
        Spacer(modifier = Modifier.height(4.dp))
        chessComContent()
        mediaContent()
        garminContent()
        githubContent()
        movieBridgeContent()
    }
}

/**
 * Collapsible "Advanced" section in the habit edit panel.
 * Hosts the Invert Data operation and the per-habit Restore-from-Backup.
 */


@Composable
internal fun AdvancedSection(
    habitName: String,
    onInvertHabit: (String) -> Unit,
    onGetInvertPreview: (String) -> InvertPreview?,
    onRestoreFromBackup: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var showInvertDialog by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))
    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
    Spacer(modifier = Modifier.height(4.dp))

    // Expandable header — vertically padded for a larger, easier-to-tap target
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ADVANCED",
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = if (expanded) "▾" else "▸",
            color = Color(0xFF888888),
            fontSize = 14.sp
        )
    }

    if (expanded) {
        Spacer(modifier = Modifier.height(6.dp))

        // ── Invert Data ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "⇄ Invert Data", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = "Swap all 0s ↔ 1s",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Button(
                onClick = { showInvertDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A3A)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Invert", fontSize = 11.sp, color = Color(0xFFCC88CC))
            }
        }

        // ── Restore this habit from a backup file ───────────────────
        // Only this habit is affected; the rest of the backup is
        // ignored. Extracted to its own composable to keep
        // EditModeControlBar under the JVM method-size limit.
        RestoreFromBackupButton(onClick = onRestoreFromBackup)
    }

    if (showInvertDialog) {
        InvertConfirmDialog(
            habitName = habitName,
            preview = onGetInvertPreview(habitName),
            onConfirm = {
                onInvertHabit(habitName)
                showInvertDialog = false
            },
            onDismiss = { showInvertDialog = false }
        )
    }
}

/**
 * Confirmation dialog for the invert operation.
 * Shows a data-loss warning when values > 1 are present.
 */


@Composable
internal fun InvertConfirmDialog(
    habitName: String,
    preview: InvertPreview?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "⇄ Invert Data",
                color = Color(0xFFCC88CC),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This will flip every value for \"$habitName\":",
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (preview == null) {
                Text(
                    text = "This habit has no data to invert.",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            } else {
                // Summary of what will happen
                Text(
                    text = "• ${preview.zeroCount} zero(s) → 1\n" +
                           "• ${preview.oneCount + preview.highValueCount} non-zero value(s) → 0\n" +
                           "• ${preview.totalEntries} total date entries",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                if (preview.highValueCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Text("⚠ ", color = Color(0xFFFFAA00), fontSize = 14.sp)
                        Column {
                            Text(
                                text = "Data loss warning",
                                color = Color(0xFFFFAA00),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$preview.highValueCount entry(ies) have values " +
                                       "greater than 1 (max: ${preview.maxValue}). " +
                                       "This is NOT a reversible swap — all of these " +
                                       "counts will be permanently destroyed and set to 0. " +
                                       "The original values cannot be recovered.",
                                color = Color(0xFFFF8866),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = preview != null && preview.totalEntries > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A3A))
                ) {
                    Text(
                        if (preview?.highValueCount ?: 0 > 0) "Invert Anyway" else "Invert",
                        color = Color(0xFFCC88CC)
                    )
                }
            }
        }
    }
}

// ── Conditional links picker dialog ──────────────────────────────────────────

/**
 * A popup that lists all habits (except the conditional habit itself) as checkboxes.
 * The user can select any number of them as the habits to auto-increment.
 */
/**
 * Edit-panel section shown only for habits that other conditional habits link to.
 * Offers a one-tap "backfill" that recomputes this habit's entire history from every
 * source habit that has it set as a conditional. Extracted into its own composable to
 * keep [EditModeControlBar] under the JVM method-size limit.
 */


@Composable
internal fun ConditionalBackfillSection(
    habitName: String,
    conditionalHabits: Set<String>,
    conditionalLinkedHabits: Map<String, Set<String>>,
    conditionalLinkValues: Map<String, Map<String, String>> = emptyMap(),
    secondaryValueHabits: Set<String> = emptySet(),
    chessComHabitLinks: Map<String, String> = emptyMap(),
    valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    onBackfill: (String) -> Unit
) {
    // Only entries whose key is still an active conditional habit count as sources;
    // orphaned link entries (habit no longer conditional) must be ignored.
    val conditionalSources = remember(conditionalLinkedHabits, conditionalHabits, habitName) {
        conditionalLinkedHabits.entries
            .filter { it.key in conditionalHabits && habitName in it.value }
            .map { it.key }
            .sorted()
    }
    if (conditionalSources.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A0A14), RoundedCornerShape(6.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Conditional Backfill",
                    color = Color(0xFFFF88CC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Fed by ${conditionalSources.size} habit(s): ${conditionalSources.joinToString(", ") { src ->
                        val vk = effectiveConditionalLinkValueKey(
                            conditionalLinkValues, secondaryValueHabits,
                            chessComHabitLinks, src, habitName
                        )
                        if (vk == GRAPH_METRIC_POINTS) src
                        else "$src→${displayLabelForValue(habitName, vk, valueDisplayLabels)}"
                    }}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 10.sp
                )
                Text(
                    text = "Recompute entire history from these sources",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Button(
                onClick = { onBackfill(habitName) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Backfill", fontSize = 11.sp, color = Color(0xFFFF88CC))
            }
        }
    }
}

/**
 * Confirmation popup for a complete conditional backfill. Tells the user the total
 * number of increments that will be applied across the entire history and lists the
 * source habits that feed the target habit, then overwrites the target on confirm.
 */


@Composable
internal fun ConditionalBackfillConfirmDialog(
    habitName: String,
    sources: List<String>,
    totalIncrements: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A0A14), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Conditional Backfill",
                color = Color(0xFFFF88CC),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This will completely overwrite ALL data for \"$habitName\" based " +
                    "on the habits that have it set as a conditional:",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = sources.joinToString(", "),
                color = Color(0xFFFF88CC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Total increments across entire history: $totalIncrements",
                color = Color(0xFFFFCC44),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030))
                ) {
                    Text("Overwrite & Backfill", color = Color(0xFFFF88CC))
                }
            }
        }
    }
}


@Composable
internal fun ConditionalLinksPickerDialog(
    habitName: String,
    allHabitNames: List<String>,
    currentLinks: Set<String>,
    currentValues: Map<String, String> = emptyMap(),
    secondaryValueHabits: Set<String> = emptySet(),
    chessComHabitLinks: Map<String, String> = emptyMap(),
    valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    onConfirm: (Set<String>, Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Alphabetically sorted candidate list (case-insensitive).
    val otherHabits = remember(allHabitNames, habitName) {
        allHabitNames.filter { it != habitName && it.isNotEmpty() }
            .sortedBy { it.lowercase() }
    }
    // Search filter: blank query shows everything; otherwise case-insensitive
    // substring match. Selected habits hidden by the filter stay selected.
    var searchQuery by remember { mutableStateOf("") }
    val visibleHabits = remember(otherHabits, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) otherHabits else otherHabits.filter { it.contains(q, ignoreCase = true) }
    }
    var selected by remember(currentLinks) { mutableStateOf(currentLinks.toMutableSet()) }
    // Linked habit name → feed-value key override (absent = Points, the default)
    var valueChoices by remember(currentValues) { mutableStateOf(currentValues.toMutableMap()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A0A14), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Linked habits for \"$habitName\"",
                color = Color(0xFFFF88CC),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select habits to auto-increment when this habit is tapped. For each linked habit, pick which value it feeds:",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Search filter box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Search habits…", fontSize = 12.sp, color = Color(0xFF888888)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFFF88CC),
                    unfocusedTextColor = Color(0xFFFF88CC),
                    focusedBorderColor = Color(0xFFFF88CC),
                    unfocusedBorderColor = Color(0xFF663355),
                    cursorColor = Color(0xFFFF88CC)
                ),
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (otherHabits.isEmpty()) {
                Text(
                    text = "No other habits available.",
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
            } else if (visibleHabits.isEmpty()) {
                Text(
                    text = "No habits match \"${searchQuery.trim()}\".",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    visibleHabits.forEach { name ->
                        val isChecked = name in selected
                        // Feed-value options: Points always; Value2/Value3 only when
                        // the linked habit actually has those raw slots available.
                        val feedOptions = buildList {
                            add(GRAPH_METRIC_POINTS)
                            if (name in secondaryValueHabits) add(GRAPH_METRIC_VALUE2)
                            if (name in chessComHabitLinks) add(GRAPH_METRIC_VALUE3)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isChecked) Color(0xFF2A0020) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        val next = selected.toMutableSet()
                                        if (isChecked) {
                                            next.remove(name)
                                            val vals = valueChoices.toMutableMap()
                                            vals.remove(name)
                                            valueChoices = vals
                                        } else {
                                            next.add(name)
                                        }
                                        selected = next
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (isChecked) "☑" else "☐",
                                    color = if (isChecked) Color(0xFFFF88CC) else Color(0xFF666666),
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = name,
                                    color = if (isChecked) Color(0xFFFF88CC) else Color(0xFFCCCCCC),
                                    fontSize = 12.sp
                                )
                            }
                            if (isChecked && feedOptions.size > 1) {
                                Row(
                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    feedOptions.forEach { opt ->
                                        val active = (valueChoices[name] ?: GRAPH_METRIC_POINTS) == opt
                                        Text(
                                            text = displayLabelForValue(name, opt, valueDisplayLabels),
                                            color = if (active) Color(0xFFFF88CC) else Color(0xFF888888),
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .background(
                                                    if (active) Color(0xFF4A0030) else Color(0xFF221018),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() }
                                                ) {
                                                    val vals = valueChoices.toMutableMap()
                                                    if (opt == GRAPH_METRIC_POINTS) vals.remove(name) else vals[name] = opt
                                                    valueChoices = vals
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(selected.toSet(), valueChoices.toMap()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030))
                ) {
                    Text("Save (${selected.size})", color = Color(0xFFFF88CC))
                }
            }
        }
    }
}

// ── Dated Entry format info dialog ────────────────────────────────────────────


@Composable
internal fun DatedEntryInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = androidx.compose.ui.Modifier
                .background(Color(0xFF1A1A0A), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Dated Entry Format",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Link a plain-text file that contains date headers followed by paragraph blocks. " +
                       "Each blank-line-separated paragraph under a date counts as +1 for that day.",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Accepted date formats:", color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "  M/D/YY   →  7/13/24", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(text = "  YYYY-MM-DD  →  2025-10-21", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Date lines may start with # heading markers and may have a trailing HH:MM:SS timestamp (both are ignored).",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Example file:", color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "# 2025-03-10\n" +
                       "First paragraph here.\n" +
                       "More lines of the same entry.\n" +
                       "\n" +
                       "Second paragraph — blank line above = new entry.\n" +
                       "\n" +
                       "# 2025-03-11\n" +
                       "Only one paragraph today.",
                color = Color(0xFF88FFCC),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Result: 2025-03-10 = 2,  2025-03-11 = 1",
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Paragraphs can also be separated by a line containing only ,,, instead of a blank line.",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "The file is checked every time the app comes to the foreground. " +
                       "Only files whose size has changed are re-parsed, so this is very efficient.",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A3A00))
                ) {
                    Text("Got it", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}

// ── Dated Entry refresh confirmation dialog ───────────────────────────────────

/**
 * Confirmation dialog shown before manually refreshing a dated-entry habit
 * from its linked file. Warns that the current values will be overwritten and
 * lists exactly which dates change and how. Only the selected habit is
 * affected; confirmation applies the parsed counts shown in the preview.
 */
