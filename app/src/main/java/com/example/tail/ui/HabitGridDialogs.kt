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
internal fun DatedEntryRefreshConfirmDialog(
    preview: HabitViewModel.DatedEntryRefreshPreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val delta = preview.totalDelta
    val deltaColor = when {
        delta > 0 -> Color(0xFF66BB6A)
        delta < 0 -> Color(0xFFEF5350)
        else -> Color(0xFFCCCCCC)
    }
    val deltaText = when {
        delta > 0 -> "Total: ${preview.currentTotal} → ${preview.newTotal} (+$delta)"
        delta < 0 -> "Total: ${preview.currentTotal} → ${preview.newTotal} ($delta)"
        else -> "Total unchanged (${preview.currentTotal})"
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Refresh \"${preview.habitName}\" from file?",
                color = Color(0xFF88AAFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This will overwrite the current values for \"${preview.habitName}\" " +
                        "with the counts re-parsed from the linked file. This cannot be undone.",
                color = Color(0xFFEF9A9A),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!preview.hasChanges) {
                Text(
                    text = "No changes — the stored values already match the file " +
                            "(${preview.newDayCount} day(s), total ${preview.newTotal}).",
                    color = Color(0xFF66BB6A),
                    fontSize = 13.sp
                )
            } else {
                Text(
                    text = "Current: ${preview.currentTotal} over ${preview.currentDayCount} day(s)",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp
                )
                Text(
                    text = "From file: ${preview.newTotal} over ${preview.newDayCount} day(s)",
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = deltaText,
                    color = deltaColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (preview.changedDates.isNotEmpty()) {
                    Text(
                        text = "Changed (${preview.changedDates.size}):",
                        color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    for ((date, old, new) in preview.changedDates.take(8)) {
                        val diff = new - old
                        Text(
                            text = "  $date: $old → $new (${if (diff > 0) "+" else ""}$diff)",
                            color = if (diff > 0) Color(0xFF66BB6A) else Color(0xFFEF5350),
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    if (preview.changedDates.size > 8) {
                        Text(
                            text = "  … and ${preview.changedDates.size - 8} more",
                            color = Color(0xFF888888), fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (preview.addedDates.isNotEmpty()) {
                    Text(
                        text = "New from file (${preview.addedDates.size}):",
                        color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    for ((date, count) in preview.addedDates.take(8)) {
                        Text(
                            text = "  $date: +$count",
                            color = Color(0xFF66BB6A),
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    if (preview.addedDates.size > 8) {
                        Text(
                            text = "  … and ${preview.addedDates.size - 8} more",
                            color = Color(0xFF888888), fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (preview.removedDates.isNotEmpty()) {
                    Text(
                        text = "Removed (${preview.removedDates.size}):",
                        color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                    )
                    for ((date, old) in preview.removedDates.take(8)) {
                        Text(
                            text = "  $date: had $old (dropped)",
                            color = Color(0xFFEF5350),
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    if (preview.removedDates.size > 8) {
                        Text(
                            text = "  … and ${preview.removedDates.size - 8} more",
                            color = Color(0xFF888888), fontSize = 11.sp
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Only this habit's values are replaced — linked habits are not adjusted.",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
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
                    enabled = preview.hasChanges,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A3A),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    )
                ) {
                    Text(
                        "Refresh",
                        color = if (preview.hasChanges) Color(0xFF88AAFF) else Color(0xFF555566)
                    )
                }
            }
        }
    }
}

// ── Rename screen dialog ──────────────────────────────────────────────────────


@Composable
internal fun RenameScreenDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Rename Screen",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Screen name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Rename", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Rename habit dialog ────────────────────────────────────────────────────────


@Composable
internal fun RenameHabitDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var showConfirmation by remember { mutableStateOf(false) }

    if (showConfirmation) {
        // Confirmation dialog
        Dialog(onDismissRequest = { showConfirmation = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Rename Habit",
                    color = Color(0xFFFFAA00),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Are you sure you want to rename \"$currentName\" to \"$name\"?",
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This will update the habit name in the database and all settings.",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            showConfirmation = false
                            onConfirm(name.trim())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                    ) {
                        Text("Rename", color = Color(0xFFFFAA00))
                    }
                }
            }
        }
    } else {
        // Name entry dialog
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Rename Habit",
                    color = Color(0xFFFFAA00),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Habit name", color = Color(0xFF888888)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFAA00),
                        unfocusedBorderColor = Color(0xFF555555)
                    )
                )
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
                        onClick = {
                            val trimmed = name.trim()
                            if (trimmed.isNotEmpty() && trimmed != currentName) {
                                showConfirmation = true
                            } else if (trimmed == currentName) {
                                onDismiss()
                            }
                        },
                        enabled = name.trim().isNotEmpty() && name.trim() != currentName,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                    ) {
                        Text("Next", color = Color(0xFFFFAA00))
                    }
                }
            }
        }
    }
}

// ── Add screen dialog ─────────────────────────────────────────────────────────


@Composable
internal fun AddScreenDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "New Screen",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Screen name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Add", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Increment amounts editor dialog ───────────────────────────────────────────

/**
 * Dialog for editing the quick-increment button amounts for a custom-input habit.
 * Shows the current amounts as editable chips and allows adding/removing values.
 * Saving with an empty list resets to the default amounts.
 */


@Composable
internal fun IncrementAmountsEditorDialog(
    habitName: String,
    currentAmounts: List<Int>,
    onSave: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    // Represent each amount as a text field string so the user can edit freely
    var amountsText by remember { mutableStateOf(currentAmounts.joinToString(", ")) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Increment amounts",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter amounts separated by commas:",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = amountsText,
                onValueChange = { amountsText = it },
                label = { Text("e.g. 1, 5, 10, 30, 50", color = Color(0xFF666666)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFCC44),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFCC44)
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Leave empty to use defaults (1, 5, 10, 30, 50)",
                color = Color(0xFF666666),
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val parsed = amountsText
                            .split(",")
                            .mapNotNull { it.trim().toIntOrNull() }
                            .filter { it > 0 }
                            .distinct()
                        onSave(parsed)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2800))
                ) {
                    Text("Save", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}


// ── Custom Point Ranges dialog ─────────────────────────────────────────────────
@Composable
internal fun PointRangesEditorDialog(
    habitName: String,
    currentRanges: List<com.example.tail.data.PointRange>,
    onSave: (List<com.example.tail.data.PointRange>) -> Unit,
    onDismiss: () -> Unit
) {
    // State for each range's min and max values - dynamic list
    val rangeStates = remember(currentRanges) {
        mutableStateListOf(
            *currentRanges.map { range ->
                mutableStateOf(Pair(
                    if (range.min == Int.MIN_VALUE) "" else range.min.toString(),
                    if (range.max == Int.MAX_VALUE) "" else range.max.toString()
                ))
            }.toTypedArray()
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Custom Point Ranges",
                color = Color(0xFFBB88FF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter min/max values for each point level:",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Leave min empty for no minimum, leave max empty for no maximum.",
                color = Color(0xFF666666),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Range rows for each point level (dynamic)
            for (i in rangeStates.indices) {
                val (minText, maxText) = rangeStates[i].value
                var localMin by remember { mutableStateOf(minText) }
                var localMax by remember { mutableStateOf(maxText) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$i",
                        color = Color(0xFFBB88FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    OutlinedTextField(
                        value = localMin,
                        onValueChange = {
                            localMin = it
                            rangeStates[i].value = Pair(localMin, localMax)
                        },
                        label = { Text("Min", fontSize = 10.sp, color = Color(0xFF666666)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFBB88FF),
                            unfocusedBorderColor = Color(0xFF554488),
                            cursorColor = Color(0xFFBB88FF)
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                    OutlinedTextField(
                        value = localMax,
                        onValueChange = {
                            localMax = it
                            rangeStates[i].value = Pair(localMin, localMax)
                        },
                        label = { Text("Max", fontSize = 10.sp, color = Color(0xFF666666)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFBB88FF),
                            unfocusedBorderColor = Color(0xFF554488),
                            cursorColor = Color(0xFFBB88FF)
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add Range button
            Button(
                onClick = {
                    // Auto-calculate new range based on the previous one
                    val lastIndex = rangeStates.size - 1
                    val (prevMinText, prevMaxText) = rangeStates[lastIndex].value
                    
                    val newMin = if (prevMaxText.isNotEmpty()) {
                        val prevMax = prevMaxText.toIntOrNull() ?: 0
                        (prevMax + 1).toString()
                    } else {
                        ""
                    }
                    
                    val newMax = if (prevMinText.isNotEmpty() && prevMaxText.isNotEmpty()) {
                        val prevMin = prevMinText.toIntOrNull() ?: 0
                        val prevMax = prevMaxText.toIntOrNull() ?: 0
                        val range = prevMax - prevMin
                        (prevMax + 1 + range).toString()
                    } else {
                        ""
                    }
                    
                    rangeStates.add(mutableStateOf(Pair(newMin, newMax)))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A6A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Add Point Range", color = Color(0xFFBB88FF), fontSize = 12.sp)
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
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val ranges = rangeStates.map { state -> val (minText, maxText) = state.value
                            com.example.tail.data.PointRange(
                                min = minText.toIntOrNull() ?: Int.MIN_VALUE,
                                max = maxText.toIntOrNull() ?: Int.MAX_VALUE
                            )
                        }
                        onSave(ranges)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A6A))
                ) {
                    Text("Save", color = Color(0xFFBB88FF))
                }
            }
        }
    }
}

// ── Add habit dialog ──────────────────────────────────────────────────────────


@Composable
internal fun AddHabitDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Add Habit",
                color = Color(0xFF88FF88),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Habit name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF88FF88),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A))
                ) {
                    Text("Add", color = Color(0xFF88FF88))
                }
            }
        }
    }
}

// ── Delete habit confirmation dialog ─────────────────────────────────────────

// ── Set count dialog ──────────────────────────────────────────────────────────

/**
 * Dialog for directly typing in a new today-count value for a habit.
 * Pre-filled with the current count. Confirming sets the raw count to the
 * entered value (the displayed points value may differ if a divider is set).
 */


@Composable
internal fun SetCountDialog(
    habitName: String,
    currentCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var countText by remember { mutableStateOf(currentCount.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Set today's count",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter { c -> c.isDigit() } },
                label = { Text("Count", color = Color(0xFF888888)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFAA00)
                )
            )
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
                    onClick = {
                        val newCount = countText.toIntOrNull() ?: 0
                        onConfirm(newCount)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Set", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Habit note dialog ──────────────────────────────────────────────────────────

/**
 * Dialog for editing a habit's note.
 */


@Composable
internal fun HabitNoteDialog(
    habitName: String,
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var noteText by remember { mutableStateOf(initialNote) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Edit Note",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text("Add a note about this habit...", color = Color(0xFF666666), fontSize = 11.sp) },
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 200.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFCC44),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFCC44)
                ),
                textStyle = TextStyle(fontSize = 13.sp)
            )
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
                    onClick = { onConfirm(noteText) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A4A00))
                ) {
                    Text("Save", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}

// ── Delete habit confirmation dialog ─────────────────────────────────────────


@Composable
internal fun DeleteHabitConfirmDialog(
    habitName: String,
    isAppLink: Boolean = false,
    /** Days of stored data found in the JSON (primary + secondary slots). */
    dataDayCount: Int = 0,
    /** Called with true when the user also opted to purge the JSON data. */
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Opt-in purge toggle — defaults OFF so plain delete keeps history.
    var alsoDeleteData by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = if (isAppLink) "Remove App Link" else "Delete Habit",
                color = Color(0xFFFF8888),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isAppLink) {
                    "Remove \"$habitName\" from the grid?"
                } else if (dataDayCount > 0) {
                    "Remove \"$habitName\" from the grid?\n\n" +
                        "This habit has $dataDayCount day${if (dataDayCount == 1) "" else "s"} of data " +
                        "in your JSON files."
                } else {
                    "Remove \"$habitName\" from the grid?\n\n" +
                        "No data for this habit was found in your JSON files."
                },
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )

            // Optional data purge — only offered for real habits with data.
            if (!isAppLink && dataDayCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Also delete its data",
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Permanently removes $dataDayCount day" +
                                "${if (dataDayCount == 1) "" else "s"} of history",
                            color = Color(0xFFFF8888),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = alsoDeleteData,
                        onCheckedChange = { alsoDeleteData = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFF8888),
                            checkedTrackColor = Color(0xFF3A0000),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
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
                    onClick = { onConfirm(alsoDeleteData) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (alsoDeleteData) Color(0xFF550000) else Color(0xFF3A0000)
                    )
                ) {
                    Text(
                        if (alsoDeleteData) "Delete All" else "Delete",
                        color = Color(0xFFFF8888)
                    )
                }
            }
        }
    }
}

// ── "1 max" recalc confirmation dialog ─────────────────────────────────────────


@Composable
internal fun MaxOneRecalcConfirmDialog(
    habitName: String,
    affectedDays: Int,
    onUpdatePast: () -> Unit,
    onFutureOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Enable 1 max?",
                color = Color(0xFF88FF88),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            val msg = if (affectedDays > 0) {
                "\"$habitName\" has $affectedDays past day(s) with a count above 1.\n\n" +
                    "Cap all past entries to 1? This will permanently reduce those point totals."
            } else {
                "\"$habitName\" has no past entries above 1, so no totals need updating."
            }
            Text(
                text = msg,
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
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
                TextButton(onClick = onFutureOnly) {
                    Text("Future only", color = Color(0xFFAAAAAA))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onUpdatePast,
                    enabled = affectedDays > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A4A1A),
                        disabledContainerColor = Color(0xFF1A2A1A)
                    )
                ) {
                    Text(
                        "Update past",
                        color = if (affectedDays > 0) Color(0xFF88FF88) else Color(0xFF556655)
                    )
                }
            }
        }
    }
}

// ── "1 max" restore confirmation dialog ────────────────────────────────────────


@Composable
internal fun MaxOneRestoreConfirmDialog(
    habitName: String,
    restorableDays: Int,
    onRestore: () -> Unit,
    onLeaveAsIs: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Disable 1 max?",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            val msg = if (restorableDays > 0) {
                "While \"1 max\" was on, $restorableDays past day(s) were capped to 1.\n\n" +
                    "Their timestamps still record the true count. " +
                    "Restore those entries so they count fully toward totals again?"
            } else {
                "\"$habitName\" has no past entries that can be restored from timestamps " +
                    "(none were capped, or no timestamps were recorded)."
            }
            Text(
                text = msg,
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
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
                TextButton(onClick = onLeaveAsIs) {
                    Text("Leave as-is", color = Color(0xFFAAAAAA))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRestore,
                    enabled = restorableDays > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A2A00),
                        disabledContainerColor = Color(0xFF2A2A1A)
                    )
                ) {
                    Text(
                        "Restore",
                        color = if (restorableDays > 0) Color(0xFFFFCC44) else Color(0xFF665544)
                    )
                }
            }
        }
    }
}

// ── Chess.com link toggle (extracted to keep EditModeControlBar small) ────────

/**
 * Row + dropdown for linking the selected habit to a Chess.com game type.
 * Extracted from EditModeControlBar to stay under the JVM method-size limit.
 */


@Composable
internal fun ChessComLinkToggle(
    habitName: String,
    links: Map<String, String>,
    onSetLink: (String?) -> Unit
) {
    val currentLink = links[habitName]
    val isLinked = currentLink != null
    var dropdownExpanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "♟ Chess.com", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isLinked) {
                    val typeName = ChessComType.fromKey(currentLink)?.label ?: currentLink
                    "Linked to: $typeName"
                } else "Not linked to Chess.com",
                color = if (isLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isLinked,
            onCheckedChange = { checked ->
                if (checked) {
                    dropdownExpanded = true
                } else {
                    onSetLink(null)
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF44BBFF),
                checkedTrackColor = Color(0xFF003355),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    // Chess.com type picker dropdown
    if (isLinked || dropdownExpanded) {
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            Button(
                onClick = { dropdownExpanded = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003355)),
                modifier = Modifier.height(32.dp)
            ) {
                val label = if (currentLink != null) {
                    ChessComType.fromKey(currentLink)?.label ?: "Select type"
                } else "Select type"
                Text(label, fontSize = 11.sp, color = Color(0xFF44BBFF))
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                ChessComType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            onSetLink(type.name)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
}

// ── Restore-from-backup button (extracted to keep EditModeControlBar small) ───

/**
 * Full-width "Restore from Backup" button shown at the bottom of the habit-type
 * switches in the edit panel. Opens a file picker to choose a backup file, then
 * a confirmation dialog. Only the selected habit is restored.
 */
